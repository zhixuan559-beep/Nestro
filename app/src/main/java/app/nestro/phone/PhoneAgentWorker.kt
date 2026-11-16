package app.nestro.phone

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.nestro.data.NestroDatabase
import app.nestro.data.CapabilityTaskState
import app.nestro.data.CapabilityTaskStore
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

        val capabilityTask = dao.capabilityTaskByCheckpoint("phone_agent_task_id:$taskId")
        if (task.status == PhoneAgentTaskStore.STATUS_CANCELLED) {
            if (capabilityTask != null && !CapabilityTaskState.isTerminal(capabilityTask.state) && CapabilityTaskState.canTransition(capabilityTask.state, CapabilityTaskState.CANCELLED)) {
                CapabilityTaskStore.transition(dao, capabilityTask.id, CapabilityTaskState.CANCELLED, "phone_agent_cancelled", "用户取消 Phone Agent 任务")
            }
            return Result.success()
        }
        if (task.status == PhoneAgentTaskStore.STATUS_COMPLETED) return Result.success()
        if (PhoneAgentService.activeService == null) {
            if (capabilityTask != null && capabilityTask.state in setOf(app.nestro.data.CapabilityTaskState.RUNNING, app.nestro.data.CapabilityTaskState.QUEUED)) {
                app.nestro.data.CapabilityTaskStore.transition(dao, capabilityTask.id, app.nestro.data.CapabilityTaskState.WAITING_USER, "phone_agent_waiting_service", "等待开启 Phone Agent 无障碍服务")
            }
            if (task.status !in setOf(PhoneAgentTaskStore.STATUS_FAILED, PhoneAgentTaskStore.STATUS_WAITING_USER)) {
                PhoneAgentTaskStore.markInterrupted(dao, taskId)
            }
            return Result.success()
        }

        return try {
            if (capabilityTask != null && capabilityTask.state in setOf(app.nestro.data.CapabilityTaskState.WAITING_USER, app.nestro.data.CapabilityTaskState.QUEUED)) {
                app.nestro.data.CapabilityTaskStore.transition(dao, capabilityTask.id, app.nestro.data.CapabilityTaskState.RUNNING, "phone_agent_started", "Phone Agent 已开始执行")
            }
            val result = PhoneAgentAgent.run(applicationContext, dao, taskId, afterUserHandoff, allowRetryCurrentAction)
            val latestCapabilityTask = dao.capabilityTaskByCheckpoint("phone_agent_task_id:$taskId")
            if (latestCapabilityTask?.state == app.nestro.data.CapabilityTaskState.RUNNING) {
                val cancelledByUser = dao.phoneAgentTask(taskId)?.status == PhoneAgentTaskStore.STATUS_CANCELLED
                val nextState = if (cancelledByUser) CapabilityTaskState.CANCELLED else when (result.outcome) {
                    PhoneAgentExecutor.Outcome.COMPLETED -> app.nestro.data.CapabilityTaskState.SUCCEEDED
                    PhoneAgentExecutor.Outcome.WAITING_USER -> app.nestro.data.CapabilityTaskState.WAITING_USER
                    PhoneAgentExecutor.Outcome.PAUSED -> app.nestro.data.CapabilityTaskState.PAUSED
                    PhoneAgentExecutor.Outcome.FAILED -> app.nestro.data.CapabilityTaskState.FAILED
                }
                app.nestro.data.CapabilityTaskStore.transition(dao, latestCapabilityTask.id, nextState, "phone_agent_finished", "Phone Agent 设备任务状态：${result.outcome.name}")
            }
            when (result.outcome) {
                PhoneAgentExecutor.Outcome.COMPLETED,
                PhoneAgentExecutor.Outcome.WAITING_USER,
                PhoneAgentExecutor.Outcome.PAUSED -> Result.success()
                PhoneAgentExecutor.Outcome.FAILED -> Result.failure()
            }
        } catch (cancelled: CancellationException) {
            val latest = dao.phoneAgentTask(taskId)
            if (latest != null && latest.status == PhoneAgentTaskStore.STATUS_RUNNING) {
                PhoneAgentTaskStore.markInterrupted(dao, taskId)
            }
            val linked = dao.capabilityTaskByCheckpoint("phone_agent_task_id:$taskId")
            if (linked?.state == app.nestro.data.CapabilityTaskState.RUNNING) {
                app.nestro.data.CapabilityTaskStore.transition(dao, linked.id, app.nestro.data.CapabilityTaskState.PAUSED, "phone_agent_interrupted", "设备任务中断，等待用户恢复")
            }
            throw cancelled
        } catch (_: Throwable) {
            val latest = dao.phoneAgentTask(taskId)
            if (latest != null && latest.status == PhoneAgentTaskStore.STATUS_RUNNING) {
                PhoneAgentTaskStore.recordActionResult(dao, taskId, latest.currentStep, "任务失败", false, "后台执行发生未处理异常")
                PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_FAILED, latest.currentStep)
            }
            val linked = dao.capabilityTaskByCheckpoint("phone_agent_task_id:$taskId")
            if (linked?.state == app.nestro.data.CapabilityTaskState.RUNNING) {
                app.nestro.data.CapabilityTaskStore.transition(dao, linked.id, app.nestro.data.CapabilityTaskState.FAILED, "phone_agent_failed", "Phone Agent 后台执行异常")
            }
            Result.failure()
        }
    }
}
