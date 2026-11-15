package app.nestro.phone

import android.content.Context
import app.nestro.data.NestroDao
import app.nestro.data.PhoneAgentTaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

object PhoneAgentAgent {
    data class Result(val outcome: PhoneAgentExecutor.Outcome, val message: String)

    suspend fun run(
        context: Context,
        dao: NestroDao,
        taskId: String,
        afterUserHandoff: Boolean = false,
        allowRetryCurrentAction: Boolean = false
    ): Result {
        val service = PhoneAgentService.activeService
            ?: return pause(dao, taskId, "无障碍服务尚未连接")
        if (!service.hasValidGrant()) return pause(dao, taskId, "没有有效的任务授权")

        var task = dao.phoneAgentTask(taskId)
            ?: return Result(PhoneAgentExecutor.Outcome.FAILED, "任务不存在")
        val existingActions = runCatching { PhoneAgentTaskStore.decodeActions(task.planJson) }.getOrNull()
        if (existingActions != null) {
            val resumed = PhoneAgentExecutor.resume(
                dao = dao,
                taskId = taskId,
                afterUserHandoff = afterUserHandoff,
                allowRetryCurrentAction = allowRetryCurrentAction
            )
            if (resumed.outcome != PhoneAgentExecutor.Outcome.COMPLETED) {
                return Result(resumed.outcome, resumed.handoffReason)
            }
        } else if (task.status == PhoneAgentTaskStore.STATUS_RUNNING) {
            PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, task.currentStep)
            return Result(PhoneAgentExecutor.Outcome.PAUSED, "任务曾在规划期间中断，请重新启动任务。")
        }

        val actionHistory = StringBuilder()
        var loop = 0
        while (loop < 8) {
            if (service.isTaskStopped()) return pause(dao, taskId, "用户已请求停止")
            if (!service.hasValidGrant()) return pause(dao, taskId, "操作授权已失效")
            task = dao.phoneAgentTask(taskId)
                ?: return Result(PhoneAgentExecutor.Outcome.FAILED, "任务不存在")
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
            } catch (error: Throwable) {
                return pause(dao, taskId, error.message ?: "动作规划失败")
            }

            if (plan.actions.isEmpty()) {
                if (plan.done && plan.completionEvidence.isNotBlank() &&
                    service.verifyVisibleText(plan.completionEvidence)
                ) {
                    val current = dao.phoneAgentTask(taskId) ?: task
                    val count = runCatching {
                        PhoneAgentTaskStore.decodeActions(current.planJson).size
                    }.getOrDefault(current.currentStep)
                    PhoneAgentTaskStore.updateProgress(
                        dao, taskId, PhoneAgentTaskStore.STATUS_COMPLETED, count
                    )
                    return Result(PhoneAgentExecutor.Outcome.COMPLETED, "任务已根据页面证据核验完成")
                }
                return pause(dao, taskId, "当前页面没有足够证据证明目标已完成。")
            }

            val execution = PhoneAgentExecutor.execute(
                dao = dao,
                taskId = taskId,
                actions = plan.actions,
                markTaskCompleted = false
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

    private suspend fun pause(dao: NestroDao, taskId: String, message: String): Result {
        val task = dao.phoneAgentTask(taskId)
        if (task != null && task.status !in setOf(
                PhoneAgentTaskStore.STATUS_COMPLETED,
                PhoneAgentTaskStore.STATUS_CANCELLED,
                PhoneAgentTaskStore.STATUS_WAITING_USER
            )
        ) {
            PhoneAgentTaskStore.updateProgress(
                dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, task.currentStep
            )
        }
        return Result(PhoneAgentExecutor.Outcome.PAUSED, message)
    }
}
