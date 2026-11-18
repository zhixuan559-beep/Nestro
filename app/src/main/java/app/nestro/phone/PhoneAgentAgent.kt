package app.nestro.phone

import android.content.Context
import app.nestro.data.NestroDao
import app.nestro.data.PhoneAgentTaskStore
import app.nestro.data.runCatchingCancellable
import app.nestro.agent.AgentTaskStatus
import app.nestro.agent.AgentToolCatalog
import app.nestro.agent.InMemoryAgentToolCatalog
import app.nestro.agent.RoomAgentTaskStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

object PhoneAgentAgent {
    enum class LoadPolicy { ON_DEMAND, EAGER }

    data class AgentCapability(
        val id: String,
        val loadPolicy: LoadPolicy = LoadPolicy.ON_DEMAND,
        val estimatedCost: Int = 1
    )

    /**
     * Select only the capabilities required by the current task. Loading is declarative;
     * execution and permissions remain in the existing Phone Agent service/executor.
     */
    fun selectCapabilities(task: String, available: List<AgentCapability>, maxCost: Int = 6): List<AgentCapability> {
        val normalized = task.lowercase()
        return available.asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .filter { it.loadPolicy == LoadPolicy.EAGER || normalized.contains(it.id.lowercase()) }
            .sortedBy { it.estimatedCost }
            .runningFold(0 to emptyList<AgentCapability>()) { acc, capability ->
                if (acc.first + capability.estimatedCost <= maxCost) {
                    (acc.first + capability.estimatedCost) to (acc.second + capability)
                } else acc
            }.lastOrNull()?.second ?: emptyList()
    }


    data class Result(val outcome: PhoneAgentExecutor.Outcome, val message: String)

    suspend fun run(
        context: Context,
        dao: NestroDao,
        taskId: String,
        afterUserHandoff: Boolean = false,
        allowRetryCurrentAction: Boolean = false,
        toolCatalog: AgentToolCatalog = InMemoryAgentToolCatalog()
    ): Result {
        var task = dao.phoneAgentTask(taskId)
            ?: return Result(PhoneAgentExecutor.Outcome.FAILED, "任务不存在")
        if (isCancellationRequested(context, taskId, task.status)) {
            return Result(PhoneAgentExecutor.Outcome.CANCELLED, "任务已取消")
        }
        val service = PhoneAgentExecutionEnvironmentProvider.active
            ?: return pause(dao, taskId, "无障碍服务尚未连接")
        if (!service.hasValidGrant()) return pause(dao, taskId, "没有有效的任务授权")
        val existingActions = try { PhoneAgentTaskStore.decodeActions(task.planJson) } catch (_: Exception) { null }
        if (existingActions != null) {
            val resumed = PhoneAgentExecutor.resume(
                dao = dao,
                taskId = taskId,
                afterUserHandoff = afterUserHandoff,
                allowRetryCurrentAction = allowRetryCurrentAction,
                catalog = toolCatalog
            )
            if (resumed.outcome != PhoneAgentExecutor.Outcome.COMPLETED) {
                return Result(resumed.outcome, resumed.handoffReason)
            }
        } else if (task.status == PhoneAgentTaskStore.STATUS_RUNNING && !allowRetryCurrentAction) {
            PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, task.currentStep)
            return Result(PhoneAgentExecutor.Outcome.PAUSED, "任务曾在规划期间中断，请核对当前页面后重新启动。")
        }

        val actionHistory = StringBuilder()
        PhoneAgentTaskStore.recentLogs(dao, taskId, 20).forEach { log ->
            actionHistory.append("#").append(log.actionIndex + 1).append(" ")
                .append(log.action).append("：").append(if (log.success) "成功" else "失败")
                .append("，").append(log.detail.take(100)).append("\n")
        }
        var loop = 0
        while (loop < 8) {
            if (service.isTaskStopped()) return pause(dao, taskId, "用户已请求停止")
            if (!service.hasValidGrant()) return pause(dao, taskId, "操作授权已失效")
            task = dao.phoneAgentTask(taskId)
                ?: return Result(PhoneAgentExecutor.Outcome.FAILED, "任务不存在")
            if (isCancellationRequested(context, taskId, task.status)) {
                return Result(PhoneAgentExecutor.Outcome.CANCELLED, "任务已取消")
            }
            PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_RUNNING, task.currentStep)
            val screen = service.captureVisibleText()
            val plan = try {
                PhoneAgentModelClient.planNext(
                    context = context,
                    goal = task.goal,
                    category = task.category,
                    visibleScreen = screen,
                    recentActions = actionHistory.toString()
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return pause(dao, taskId, error.message ?: "动作规划失败")
            }
            task = dao.phoneAgentTask(taskId)
                ?: return Result(PhoneAgentExecutor.Outcome.FAILED, "任务不存在")
            if (isCancellationRequested(context, taskId, task.status)) {
                return Result(PhoneAgentExecutor.Outcome.CANCELLED, "任务已取消")
            }
            if (service.isTaskStopped()) return pause(dao, taskId, "用户已请求停止")

            if (plan.actions.isEmpty()) {
                if (plan.done && plan.completionEvidence.isNotBlank() &&
                    service.verifyVisibleText(plan.completionEvidence)
                ) {
                    val current = dao.phoneAgentTask(taskId) ?: task
                    val count = try {
                        PhoneAgentTaskStore.decodeActions(current.planJson).size
                    } catch (_: Exception) {
                        current.currentStep
                    }
                    PhoneAgentTaskStore.recordActionResult(
                        dao, taskId, count, "任务完成",
                        true,
                        plan.summary.ifBlank { "任务已根据页面证据核验完成" }
                    )
                    PhoneAgentTaskStore.updateProgress(
                        dao, taskId, PhoneAgentTaskStore.STATUS_COMPLETED, count
                    )
                    val sharedStateStore = RoomAgentTaskStateStore(dao)
                    sharedStateStore.get(taskId)?.let { state ->
                        sharedStateStore.put(state.copy(
                            status = AgentTaskStatus.COMPLETED,
                            currentStep = count,
                            lastError = "",
                            updatedAt = System.currentTimeMillis()
                        ))
                    }
                    return Result(PhoneAgentExecutor.Outcome.COMPLETED, plan.summary.ifBlank { "任务已根据页面证据核验完成" })
                }
                return pause(dao, taskId, "当前页面没有足够证据证明目标已完成。")
            }

            val execution = PhoneAgentExecutor.execute(
                dao = dao,
                taskId = taskId,
                actions = plan.actions,
                markTaskCompleted = false,
                catalog = toolCatalog
            ) { completed, result ->
                actionHistory.append("#").append(completed).append(" ")
                    .append(result.action).append("：")
                    .append(if (result.success) "成功" else "失败")
                    .append("，").append(result.detail.take(100)).append("\n")
            }
            if (execution.outcome != PhoneAgentExecutor.Outcome.COMPLETED) {
                return Result(execution.outcome, execution.handoffReason)
            }
            delay(450)
            loop++
        }
        return pause(dao, taskId, "达到单次自动执行轮数上限，任务已暂停，避免无限操作。")
    }

    private fun isCancellationRequested(context: Context, taskId: String, taskStatus: String): Boolean =
        PhoneAgentCancellationPolicy.isCancellationRequested(
            taskStatus,
            context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
                .getBoolean(PhoneAgentWorkScheduler.cancelKey(taskId), false)
        )

    private suspend fun pause(dao: NestroDao, taskId: String, message: String): Result {
        val task = dao.phoneAgentTask(taskId)
        if (task != null && task.status !in setOf(
                PhoneAgentTaskStore.STATUS_COMPLETED,
                PhoneAgentTaskStore.STATUS_CANCELLED,
                PhoneAgentTaskStore.STATUS_WAITING_USER,
                PhoneAgentTaskStore.STATUS_FAILED
            )
        ) {
            if (task.status == PhoneAgentTaskStore.STATUS_RUNNING) {
                PhoneAgentTaskStore.markInterrupted(dao, taskId)
            } else {
                PhoneAgentTaskStore.updateProgress(
                    dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, task.currentStep
                )
            }
        }
        if (task != null) {
            runCatchingCancellable {
                PhoneAgentTaskStore.recordActionResult(dao, taskId, task.currentStep, "任务状态", false, message)
            }
        }
        runCatchingCancellable {
            val stateStore = RoomAgentTaskStateStore(dao)
            val state = stateStore.get(taskId)
            if (state != null && state.status !in setOf(
                    AgentTaskStatus.COMPLETED,
                    AgentTaskStatus.CANCELLED,
                    AgentTaskStatus.FAILED,
                    AgentTaskStatus.WAITING_FOR_USER
                )) {
                stateStore.put(state.copy(
                    status = AgentTaskStatus.PAUSED,
                    lastError = message.take(2_000),
                    updatedAt = System.currentTimeMillis()
                ))
            }
        }
        return Result(PhoneAgentExecutor.Outcome.PAUSED, message)
    }
}
