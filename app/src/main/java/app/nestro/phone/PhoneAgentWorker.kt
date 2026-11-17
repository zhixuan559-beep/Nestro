package app.nestro.phone

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.nestro.data.NestroDatabase
import app.nestro.data.PhoneAgentTaskStore
import app.nestro.data.runCatchingCancellable
import app.nestro.NestroApplication
import app.nestro.agent.AgentTaskStatus
import app.nestro.agent.AgentTaskState
import app.nestro.agent.RoomAgentTaskStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

class PhoneAgentWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(PhoneAgentWorkScheduler.KEY_TASK_ID)
            ?: return Result.success()
        val app = applicationContext as NestroApplication
        try {
            app.ensureBackupRestoreRecovered()
        } catch (_: Exception) {
            return Result.retry()
        }
        val preferences = applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        // WorkManager input survives process death and automatic retries. Rehydrate
        // handoff/retry intent before the coordinator handler reads its durable flags.
        preferences.edit()
            .putBoolean(
                PhoneAgentWorkScheduler.afterHandoffKey(taskId),
                inputData.getBoolean(
                    PhoneAgentWorkScheduler.KEY_AFTER_HANDOFF,
                    preferences.getBoolean(PhoneAgentWorkScheduler.afterHandoffKey(taskId), false)
                )
            )
            .putBoolean(
                PhoneAgentWorkScheduler.allowRetryKey(taskId),
                inputData.getBoolean(
                    PhoneAgentWorkScheduler.KEY_ALLOW_RETRY,
                    preferences.getBoolean(PhoneAgentWorkScheduler.allowRetryKey(taskId), false)
                )
            )
            .apply()
        try {
            return executeTask(taskId)
        } finally {
            applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
                .edit().remove(PhoneAgentWorkScheduler.pendingKey(taskId)).remove(PhoneAgentWorkScheduler.cancelKey(taskId)).remove(PhoneAgentWorkScheduler.afterHandoffKey(taskId)).remove(PhoneAgentWorkScheduler.allowRetryKey(taskId)).remove("phone_task_stop_requested").apply()
        }
    }

    /** WorkManager schedules the task; the application coordinator owns execution. */
    private suspend fun runWithSharedCoordinator(
        dao: app.nestro.data.NestroDao,
        taskId: String
    ): PhoneAgentAgent.Result {
        val app = applicationContext as NestroApplication
        val coordinator = app.agentTaskCoordinator
        val stateStore = RoomAgentTaskStateStore(dao)
        val task = dao.phoneAgentTask(taskId) ?: error("任务不存在")
        val existing = stateStore.get(taskId)
        if (existing == null) {
            stateStore.put(AgentTaskState(
                taskId = taskId,
                owner = "phone_agent",
                status = AgentTaskStatus.QUEUED,
                currentStep = task.currentStep,
                checkpoint = task.planJson.take(64_000)
            ))
        } else {
            require(existing.owner == "phone_agent") { "Phone Agent 任务 ID 已被其他模块占用" }
        }

        when (stateStore.get(taskId)?.status) {
            AgentTaskStatus.CREATED,
            AgentTaskStatus.QUEUED -> coordinator.enqueue(taskId, "phone_agent", task.planJson.take(64_000))
            AgentTaskStatus.PAUSED,
            AgentTaskStatus.FAILED,
            AgentTaskStatus.WAITING_FOR_USER -> coordinator.resume(taskId)
            // After process recreation, the durable Room state can still say RUNNING
            // while the in-memory coordinator has no active handler. Reattach only when idle;
            // if another Worker already owns the active handler this is a no-op.
            AgentTaskStatus.RUNNING -> coordinator.requeueRunningTaskIfIdle(taskId)
            AgentTaskStatus.COMPLETED -> return PhoneAgentAgent.Result(PhoneAgentExecutor.Outcome.COMPLETED, "任务已完成")
            AgentTaskStatus.CANCELLED -> return PhoneAgentAgent.Result(PhoneAgentExecutor.Outcome.CANCELLED, "任务已取消")
            null -> error("无法初始化 Phone Agent 任务状态")
        }

        try {
            while (true) {
                val state = stateStore.get(taskId) ?: error("任务状态丢失")
                when (state.status) {
                    AgentTaskStatus.COMPLETED -> return PhoneAgentAgent.Result(PhoneAgentExecutor.Outcome.COMPLETED, "任务已完成")
                    AgentTaskStatus.PAUSED -> return PhoneAgentAgent.Result(PhoneAgentExecutor.Outcome.PAUSED, state.lastError)
                    AgentTaskStatus.WAITING_FOR_USER -> return PhoneAgentAgent.Result(PhoneAgentExecutor.Outcome.WAITING_USER, state.lastError)
                    AgentTaskStatus.FAILED -> return PhoneAgentAgent.Result(PhoneAgentExecutor.Outcome.FAILED, state.lastError)
                    AgentTaskStatus.CANCELLED -> return PhoneAgentAgent.Result(PhoneAgentExecutor.Outcome.CANCELLED, "任务已取消")
                    else -> delay(200)
                }
            }
        } catch (cancelled: CancellationException) {
            val userCancelled = applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
                .getBoolean(PhoneAgentWorkScheduler.cancelKey(taskId), false)
            withContext(kotlinx.coroutines.NonCancellable) {
                if (userCancelled) coordinator.cancel(taskId) else coordinator.pause(taskId)
            }
            throw cancelled
        }
    }

    private suspend fun executeTask(taskId: String): Result {
        val dao = NestroDatabase.get(applicationContext).dao()
        val task = dao.phoneAgentTask(taskId) ?: return Result.success()

        val preferences = applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        if (task.status == PhoneAgentTaskStore.STATUS_COMPLETED) return Result.success()
        if (PhoneAgentCancellationPolicy.isCancellationRequested(
                task.status,
                preferences.getBoolean(PhoneAgentWorkScheduler.cancelKey(taskId), false)
            )
        ) {
            val store = RoomAgentTaskStateStore(dao)
            store.get(taskId)?.let { state ->
                if (state.status != AgentTaskStatus.COMPLETED) store.put(state.copy(
                    status = AgentTaskStatus.CANCELLED,
                    lastError = "用户取消任务",
                    updatedAt = System.currentTimeMillis()
                ))
            }
            PhoneAgentNotifications.show(applicationContext, taskId, "Phone Agent 已取消", "任务已停止，不会自动重新执行。", false)
            return Result.success()
        }
        preferences.edit().remove("phone_task_stop_requested").apply()
        setForeground(PhoneAgentNotifications.foregroundInfo(applicationContext, taskId, "Phone Agent 正在执行", task.goal.take(100)))
        PhoneAgentNotifications.show(applicationContext, taskId, "Phone Agent 正在执行", task.goal.take(100), true)
        if (PhoneAgentService.activeService == null) {
            if (task.status !in setOf(PhoneAgentTaskStore.STATUS_FAILED, PhoneAgentTaskStore.STATUS_WAITING_USER)) {
                PhoneAgentTaskStore.markInterrupted(dao, taskId)
            }
            PhoneAgentNotifications.show(applicationContext, taskId, "Phone Agent 已暂停", "无障碍服务未连接，请重新打开应用并检查任务状态。", false)
            return Result.success()
        }

        return try {
            val result = runWithSharedCoordinator(dao, taskId)
            val cancelRequested = applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
                .getBoolean(PhoneAgentWorkScheduler.cancelKey(taskId), false)
            if (cancelRequested || dao.phoneAgentTask(taskId)?.status == PhoneAgentTaskStore.STATUS_CANCELLED ||
                result.outcome == PhoneAgentExecutor.Outcome.CANCELLED
            ) {
                val latest = dao.phoneAgentTask(taskId)
                if (latest != null && latest.status !in setOf(PhoneAgentTaskStore.STATUS_COMPLETED, PhoneAgentTaskStore.STATUS_CANCELLED)) {
                    PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_CANCELLED, latest.currentStep)
                }
                RoomAgentTaskStateStore(dao).get(taskId)?.let { state ->
                    if (state.status != AgentTaskStatus.COMPLETED) RoomAgentTaskStateStore(dao).put(state.copy(
                        status = AgentTaskStatus.CANCELLED,
                        lastError = "用户取消任务",
                        updatedAt = System.currentTimeMillis()
                    ))
                }
                PhoneAgentNotifications.show(applicationContext, taskId, "Phone Agent 已取消", "任务已停止，不会自动重新执行。", false)
                return Result.success()
            }
            when (result.outcome) {
                PhoneAgentExecutor.Outcome.COMPLETED -> {
                    PhoneAgentNotifications.show(applicationContext, taskId, "Phone Agent 已完成", result.message.take(160), false)
                    Result.success()
                }
                PhoneAgentExecutor.Outcome.WAITING_USER -> {
                    PhoneAgentNotifications.show(applicationContext, taskId, "Phone Agent 需要你接手", result.message.take(160), false)
                    Result.success()
                }
                PhoneAgentExecutor.Outcome.PAUSED -> {
                    PhoneAgentNotifications.show(applicationContext, taskId, "Phone Agent 已暂停", result.message.take(160), false)
                    Result.success()
                }
                PhoneAgentExecutor.Outcome.FAILED -> {
                    PhoneAgentNotifications.show(applicationContext, taskId, "Phone Agent 执行失败", result.message.take(160), false)
                    Result.success()
                }
                PhoneAgentExecutor.Outcome.CANCELLED -> {
                    PhoneAgentNotifications.show(applicationContext, taskId, "Phone Agent 已取消", "任务已停止，不会自动重新执行。", false)
                    Result.success()
                }
            }
        } catch (cancelled: CancellationException) {
            val userCancelled = applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
                .getBoolean(PhoneAgentWorkScheduler.cancelKey(taskId), false)
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    val latest = dao.phoneAgentTask(taskId)
                    if (latest != null && latest.status == PhoneAgentTaskStore.STATUS_RUNNING) {
                        if (userCancelled) PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_CANCELLED, latest.currentStep)
                        else PhoneAgentTaskStore.markInterrupted(dao, taskId)
                    }
                    runCatchingCancellable {
                        val stateStore = RoomAgentTaskStateStore(dao)
                        stateStore.get(taskId)?.let { state ->
                            if (state.status !in setOf(AgentTaskStatus.COMPLETED, AgentTaskStatus.FAILED, AgentTaskStatus.WAITING_FOR_USER)) {
                                stateStore.put(state.copy(
                                    status = if (userCancelled) AgentTaskStatus.CANCELLED else AgentTaskStatus.PAUSED,
                                    lastError = if (userCancelled) "用户取消任务" else "后台任务被中断，可核对当前页面后继续",
                                    updatedAt = System.currentTimeMillis()
                                ))
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Preserve the original cancellation after best-effort durable cleanup.
                }
            }
            PhoneAgentNotifications.show(
                applicationContext, taskId,
                if (userCancelled) "Phone Agent 已取消" else "Phone Agent 已暂停",
                if (userCancelled) "任务已停止，不会自动重新执行。" else "任务已停止，重新打开应用后可核对页面并继续。",
                false
            )
            if (userCancelled) Result.success() else throw cancelled
        } catch (error: Exception) {
            val latest = dao.phoneAgentTask(taskId)
            if (latest != null && latest.status == PhoneAgentTaskStore.STATUS_RUNNING) {
                PhoneAgentTaskStore.recordActionResult(dao, taskId, latest.currentStep, "任务失败", false, "后台执行发生未处理异常")
                PhoneAgentTaskStore.markInterrupted(dao, taskId)
            }
            PhoneAgentNotifications.show(applicationContext, taskId, "Phone Agent 已暂停待核验", "后台操作出现异常。为避免重复点击或重复提交，请先核对目标应用当前页面，再手动继续。", false)
            Result.success()
        }
    }
}
