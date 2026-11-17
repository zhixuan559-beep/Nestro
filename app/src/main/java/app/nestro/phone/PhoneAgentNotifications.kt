package app.nestro.phone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.nestro.data.PhoneAgentTaskStore
import androidx.work.WorkManager

object PhoneAgentNotifications {
    private const val CHANNEL_ID = "phone_agent_tasks"
    private const val ACTION_CANCEL = "app.nestro.action.CANCEL_PHONE_TASK"
    private const val EXTRA_TASK_ID = "task_id"

    fun show(context: Context, taskId: String, title: String, message: String, active: Boolean) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Phone Agent 任务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示用户主动启动的手机自动化任务状态"
                }
            )
        }
        val cancelIntent = Intent(app, PhoneAgentNotificationReceiver::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_TASK_ID, taskId)
        val pending = PendingIntent.getBroadcast(
            app, taskId.hashCode(), cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(active)
            .setAutoCancel(!active)
        if (active) builder.addAction(android.R.drawable.ic_media_pause, "停止任务", pending)
        NotificationManagerCompat.from(app).notify(taskId.hashCode(), builder.build())
    }

    fun cancel(context: Context, taskId: String) {
        NotificationManagerCompat.from(context.applicationContext).cancel(taskId.hashCode())
    }

    internal fun cancelAction(): String = ACTION_CANCEL
    internal fun taskIdExtra(): String = EXTRA_TASK_ID
}

class PhoneAgentNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PhoneAgentNotifications.cancelAction()) return
        val taskId = intent.getStringExtra(PhoneAgentNotifications.taskIdExtra()).orEmpty()
        if (taskId.isBlank()) return
        context.applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            .edit().putBoolean("phone_task_stop_requested", true).apply()
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("nestro-phone-agent-$taskId")
        PhoneAgentNotifications.show(context, taskId, "Phone Agent 已停止", "已收到停止请求，任务状态正在保存。", false)
    }
}
