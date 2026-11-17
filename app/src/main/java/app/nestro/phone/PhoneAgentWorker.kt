package app.nestro.phone

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.nestro.data.NestroDatabase
import app.nestro.data.PhoneAgentTaskStore
import kotlinx.coroutines.CancellationException

class PhoneAgentWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(PhoneAgentWorkScheduler.KEY_TASK_ID)
            ?: return Result.success()
        try {
            return executeTask(taskId)
        } finally {
            applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
                .edit().remove(PhoneAgentWorkScheduler.pendingKey(taskId)).remove(PhoneAgentWorkScheduler.cancelKey(taskId)).remove("phone_task_stop_requested").apply()
        }
    }

    private suspend fun executeTask(taskId: String): Result {
        val afterUserHandoff = inputData.getBoolean(PhoneAgentWorkScheduler.KEY_AFTER_HANDOFF, false)
        val allowRetryCurrentAction = inputData.getBoolean(PhoneAgentWorkScheduler.KEY_ALLOW_RETRY, false)
        val dao = NestroDatabase.get(applicationContext).dao()
        val task = dao.phoneAgentTask(taskId) ?: return Result.success()

        if (task.status in setOf(PhoneAgentTaskStore.STATUS_COMPLETED, PhoneAgentTaskStore.STATUS_CANCELLED)) return Result.success()
        applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            .edit().remove("phone_task_stop_requested").apply()
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
            val result = PhoneAgentAgent.run(applicationContext, dao, taskId, afterUserHandoff, allowRetryCurrentAction)
            val cancelRequested = applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
                .getBoolean(PhoneAgentWorkScheduler.cancelKey(taskId), false)
            if (cancelRequested || dao.phoneAgentTask(taskId)?.status == PhoneAgentTaskStore.STATUS_CANCELLED) {
                val latest = dao.phoneAgentTask(taskId)
                if (latest != null && latest.status !in setOf(PhoneAgentTaskStore.STATUS_COMPLETED, PhoneAgentTaskStore.STATUS_CANCELLED)) {
                    PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_CANCELLED, latest.currentStep)
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
            }
        } catch (cancelled: CancellationException) {
            val latest = dao.phoneAgentTask(taskId)
            val userCancelled = applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
                .getBoolean(PhoneAgentWorkScheduler.cancelKey(taskId), false)
            if (latest != null && latest.status == PhoneAgentTaskStore.STATUS_RUNNING) {
                if (userCancelled) PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_CANCELLED, latest.currentStep)
                else PhoneAgentTaskStore.markInterrupted(dao, taskId)
            }
            PhoneAgentNotifications.show(
                applicationContext, taskId,
                if (userCancelled) "Phone Agent 已取消" else "Phone Agent 已暂停",
                if (userCancelled) "任务已停止，不会自动重新执行。" else "任务已停止，重新打开应用后可核对页面并继续。",
                false
            )
            if (userCancelled) Result.success() else throw cancelled
        } catch (_: Throwable) {
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
