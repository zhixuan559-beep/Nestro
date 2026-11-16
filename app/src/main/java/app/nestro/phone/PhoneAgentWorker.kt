package app.nestro.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
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
            ?: return Result.success()
        val afterUserHandoff = inputData.getBoolean(PhoneAgentWorkScheduler.KEY_AFTER_HANDOFF, false)
        val allowRetryCurrentAction = inputData.getBoolean(PhoneAgentWorkScheduler.KEY_ALLOW_RETRY, false)
        val dao = NestroDatabase.get(applicationContext).dao()
        val task = dao.phoneAgentTask(taskId) ?: return Result.success()

        val capabilityTask = dao.capabilityTaskByCheckpoint("phone_agent_task_id:$taskId")
        if (task.status == PhoneAgentTaskStore.STATUS_CANCELLED) {
            if (capabilityTask != null && !CapabilityTaskState.isTerminal(capabilityTask.state) && CapabilityTaskState.canTransition(capabilityTask.state, CapabilityTaskState.CANCELLED)) {
                CapabilityTaskStore.transition(dao, capabilityTask.id, CapabilityTaskState.CANCELLED, "phone_agent_cancelled", "用户取消 Phone Agent 任务")
            }
            return Result.success()
        }
        if (task.status == PhoneAgentTaskStore.STATUS_COMPLETED) return Result.success()
        val service = PhoneAgentService.activeService
        if (service == null) {
            if (capabilityTask != null && capabilityTask.state in setOf(app.nestro.data.CapabilityTaskState.RUNNING, app.nestro.data.CapabilityTaskState.QUEUED)) {
                app.nestro.data.CapabilityTaskStore.transition(dao, capabilityTask.id, app.nestro.data.CapabilityTaskState.WAITING_USER, "phone_agent_waiting_service", "等待开启 Phone Agent 无障碍服务")
            }
            if (task.status !in setOf(PhoneAgentTaskStore.STATUS_FAILED, PhoneAgentTaskStore.STATUS_WAITING_USER)) {
                PhoneAgentTaskStore.markInterrupted(dao, taskId)
            }
            postTaskStatusNotification("Phone Agent 等待你处理", "请开启无障碍服务或检查授权，然后回到 Nestro 继续任务。", taskId)
            return Result.success()
        }

        return try {
            setForeground(createForegroundInfo())
            if (capabilityTask != null) {
                var linkedState = capabilityTask.state
                if (linkedState == app.nestro.data.CapabilityTaskState.PAUSED) {
                    app.nestro.data.CapabilityTaskStore.transition(
                        dao, capabilityTask.id, app.nestro.data.CapabilityTaskState.QUEUED,
                        "phone_agent_resumed", "Phone Agent 子任务经用户确认后重新入队"
                    )
                    linkedState = app.nestro.data.CapabilityTaskState.QUEUED
                }
                if (linkedState in setOf(app.nestro.data.CapabilityTaskState.WAITING_USER, app.nestro.data.CapabilityTaskState.QUEUED)) {
                    app.nestro.data.CapabilityTaskStore.transition(
                        dao, capabilityTask.id, app.nestro.data.CapabilityTaskState.RUNNING,
                        "phone_agent_started", "Phone Agent 已开始执行"
                    )
                }
            }
            val result = PhoneAgentAgent.run(applicationContext, dao, taskId, afterUserHandoff, allowRetryCurrentAction)
            when (result.outcome) {
                PhoneAgentExecutor.Outcome.COMPLETED -> postTaskStatusNotification("Phone Agent 任务已完成", "目标已根据当前页面证据核验。", taskId)
                PhoneAgentExecutor.Outcome.WAITING_USER -> postTaskStatusNotification("Phone Agent 需要你接手", "登录、验证或高风险步骤需要你本人处理。", taskId)
                PhoneAgentExecutor.Outcome.PAUSED -> postTaskStatusNotification("Phone Agent 任务已暂停", "请检查当前页面或授权状态，再回到 Nestro 恢复。", taskId)
                PhoneAgentExecutor.Outcome.FAILED -> postTaskStatusNotification("Phone Agent 执行异常", "请查看本地任务记录，核对页面后再重试。", taskId)
            }
            val latestCapabilityTask = dao.capabilityTaskByCheckpoint("phone_agent_task_id:$taskId")
            if (latestCapabilityTask?.state == app.nestro.data.CapabilityTaskState.RUNNING) {
                val cancelledByUser = dao.phoneAgentTask(taskId)?.status == PhoneAgentTaskStore.STATUS_CANCELLED
                val nextState = if (cancelledByUser) CapabilityTaskState.CANCELLED else when (result.outcome) {
                    PhoneAgentExecutor.Outcome.COMPLETED -> app.nestro.data.CapabilityTaskState.SUCCEEDED
                    PhoneAgentExecutor.Outcome.WAITING_USER -> app.nestro.data.CapabilityTaskState.WAITING_USER
                    PhoneAgentExecutor.Outcome.PAUSED -> app.nestro.data.CapabilityTaskState.PAUSED
                    PhoneAgentExecutor.Outcome.FAILED -> app.nestro.data.CapabilityTaskState.PAUSED
                }
                app.nestro.data.CapabilityTaskStore.transition(dao, latestCapabilityTask.id, nextState, "phone_agent_finished", "Phone Agent 设备任务状态：${result.outcome.name}")
            }
            when (result.outcome) {
                PhoneAgentExecutor.Outcome.COMPLETED,
                PhoneAgentExecutor.Outcome.WAITING_USER,
                PhoneAgentExecutor.Outcome.PAUSED -> Result.success()
                // Task failure is persisted in Room; keep the serial queue moving for later tasks.
                PhoneAgentExecutor.Outcome.FAILED -> Result.success()
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
                app.nestro.data.CapabilityTaskStore.transition(dao, linked.id, app.nestro.data.CapabilityTaskState.PAUSED, "phone_agent_failed", "Phone Agent 后台执行异常，等待用户核对后重试")
            }
            // The failure is recorded locally; do not fail the unique chain and block queued tasks.
            postTaskStatusNotification("Phone Agent 执行异常", "任务已暂停；请核对页面和任务记录后重试。", taskId)
            Result.success()
        } finally {
            // The single-use grant is consumed after this worker execution.
            // A paused/handoff task needs a fresh explicit grant before it resumes.
            service.finishSingleGrantIfNeeded()
        }
    }

    private fun postTaskStatusNotification(title: String, message: String, taskId: String) {
        runCatching {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "手机任务运行状态",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            val launchIntent = applicationContext.packageManager
                .getLaunchIntentForPackage(applicationContext.packageName)
            val contentIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    applicationContext,
                    taskId.hashCode(),
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val builder = Notification.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle(title)
                .setContentText(message)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
            if (contentIntent != null) builder.setContentIntent(contentIntent)
            val notificationId = NOTIFICATION_RESULT_ID_BASE + (taskId.hashCode() and 0x7fff)
            manager.notify(notificationId, builder.build())
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "手机任务运行状态",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                8108,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val builder = Notification.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Nestro 正在执行手机任务")
            .setContentText("仅在授权范围内操作；登录和高风险步骤会暂停并交由你确认。")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "nestro_phone_agent"
        private const val NOTIFICATION_ID = 8108
        private const val NOTIFICATION_RESULT_ID_BASE = 9000
    }
}
