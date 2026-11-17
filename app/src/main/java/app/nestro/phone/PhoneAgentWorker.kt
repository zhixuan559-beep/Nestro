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
            ?: return Result.failure()
        val afterUserHandoff = inputData.getBoolean(PhoneAgentWorkScheduler.KEY_AFTER_HANDOFF, false)
        val allowRetryCurrentAction = inputData.getBoolean(PhoneAgentWorkScheduler.KEY_ALLOW_RETRY, false)
        val dao = NestroDatabase.get(applicationContext).dao()
        val task = dao.phoneAgentTask(taskId) ?: return Result.failure()

        if (task.status in setOf(PhoneAgentTaskStore.STATUS_COMPLETED, PhoneAgentTaskStore.STATUS_CANCELLED)) return Result.success()
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
                    Result.failure()
                }
            }
        } catch (cancelled: CancellationException) {
            val latest = dao.phoneAgentTask(taskId)
            if (latest != null && latest.status == PhoneAgentTaskStore.STATUS_RUNNING) {
                PhoneAgentTaskStore.markInterrupted(dao, taskId)
            }
            PhoneAgentNotifications.show(applicationContext, taskId, "Phone Agent 已暂停", "任务已停止，重新打开应用后可核对页面并继续。", false)
            throw cancelled
        } catch (_: Throwable) {
            val latest = dao.phoneAgentTask(taskId)
            if (latest != null && latest.status == PhoneAgentTaskStore.STATUS_RUNNING) {
                PhoneAgentTaskStore.recordActionResult(dao, taskId, latest.currentStep, "任务失败", false, "后台执行发生未处理异常")
                if (runAttemptCount < 2) {
                    PhoneAgentNotifications.show(applicationContext, taskId, "Phone Agent 正在重试", "后台执行遇到临时异常，系统将进行有限重试。", true)
                    return Result.retry()
                }
                PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_FAILED, latest.currentStep)
            }
            PhoneAgentNotifications.show(applicationContext, taskId, "Phone Agent 执行失败", "重试次数已用尽，请打开任务页查看并手动重试。", false)
            Result.failure()
        }
    }
}
