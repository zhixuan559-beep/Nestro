package app.nestro.calendar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.nestro.MainActivity
import app.nestro.NestroApplication
import app.nestro.agent.AgentTaskMetadata
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object CalendarReminderScheduler {
    private const val CHANNEL_ID = "calendar_reminders"

    fun schedule(context: Context, event: CalendarEvent, replaceAutomation: Boolean = true) {
        val now = System.currentTimeMillis()
        val occurrenceStart = CalendarRules.nextOccurrenceStart(event, now)
        if (occurrenceStart == null) {
            cancel(context, event.id)
            return
        }
        val workManager = WorkManager.getInstance(context)
        if (event.reminderMinutes >= 0) {
            val triggerAt = occurrenceStart - event.reminderMinutes * 60_000L
            val request = OneTimeWorkRequestBuilder<CalendarReminderWorker>()
                .setInputData(workDataOf("event_id" to event.id, "occurrence_start" to occurrenceStart))
                .setInitialDelay((triggerAt - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .addTag("calendar-reminder")
                .build()
            workManager.enqueueUniqueWork(workName(event.id), ExistingWorkPolicy.REPLACE, request)
        } else {
            workManager.cancelUniqueWork(workName(event.id))
        }

        if (replaceAutomation) workManager.cancelAllWorkByTag(automationTag(event.id))
        if (event.automationPrompt.isNotBlank() && event.automationProjectId.isNotBlank()) {
            val automationRequest = OneTimeWorkRequestBuilder<CalendarAutomationWorker>()
                .setInputData(workDataOf("event_id" to event.id, "occurrence_start" to occurrenceStart))
                .setInitialDelay((occurrenceStart - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .addTag(automationTag(event.id))
                .build()
            workManager.enqueueUniqueWork(
                automationWorkName(event.id, occurrenceStart),
                ExistingWorkPolicy.KEEP,
                automationRequest
            )
        }
    }

    fun cancel(context: Context, eventId: String) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(workName(eventId))
        workManager.cancelAllWorkByTag(automationTag(eventId))
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag("calendar-reminder")
    }

    private fun workName(eventId: String) = "calendar-reminder-$eventId"
    private fun automationTag(eventId: String) = "calendar-automation-$eventId"
    private fun automationWorkName(eventId: String, occurrenceStart: Long) = "calendar-automation-$eventId-$occurrenceStart"

    internal fun notify(context: Context, event: CalendarEvent, occurrenceStart: Long) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "日程提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "提醒即将开始的日程"
                }
            )
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_page", "calendar")
            putExtra("calendar_event_id", event.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, event.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val time = Instant.ofEpochMilli(occurrenceStart).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(event.title)
            .setContentText("日程即将开始 · $time")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                buildString {
                    append("日程即将开始 · ").append(time)
                    if (event.location.isNotBlank()) append("\n地点：").append(event.location)
                    if (event.description.isNotBlank()) append("\n").append(event.description.take(300))
                }
            ))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(event.id.hashCode(), notification) }
    }
}

class CalendarReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("event_id") ?: return Result.failure()
        val occurrenceStart = inputData.getLong("occurrence_start", -1L)
        if (occurrenceStart <= 0L) return Result.failure()
        val dao = CalendarDatabase.get(applicationContext).calendarDao()
        val event = dao.event(id) ?: return Result.success()
        if (event.completed || event.reminderMinutes < 0) return Result.success()
        if (!CalendarRules.isOccurrence(event, occurrenceStart)) {
            CalendarReminderScheduler.schedule(applicationContext, event)
            return Result.success()
        }
        val expectedTrigger = occurrenceStart - event.reminderMinutes * 60_000L
        if (System.currentTimeMillis() + 60_000L < expectedTrigger) {
            CalendarReminderScheduler.schedule(applicationContext, event)
            return Result.success()
        }
        CalendarReminderScheduler.notify(applicationContext, event, occurrenceStart)
        CalendarReminderScheduler.schedule(applicationContext, event, replaceAutomation = false)
        return Result.success()
    }
}

class CalendarAutomationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getString("event_id") ?: return Result.failure()
        val occurrenceStart = inputData.getLong("occurrence_start", -1L)
        if (occurrenceStart <= 0L) return Result.failure()
        val dao = CalendarDatabase.get(applicationContext).calendarDao()
        val event = dao.event(eventId) ?: return Result.success()
        if (event.completed || event.automationPrompt.isBlank() || event.automationProjectId.isBlank()) {
            return Result.success()
        }
        if (!CalendarRules.isOccurrence(event, occurrenceStart)) {
            CalendarReminderScheduler.schedule(applicationContext, event, replaceAutomation = false)
            return Result.success()
        }
        if (System.currentTimeMillis() + 60_000L < occurrenceStart) return Result.retry()
        val app = applicationContext as? NestroApplication ?: return Result.retry()
        val taskId = "calendar-$eventId-$occurrenceStart"
        val input = JSONObject()
            .put("eventId", eventId)
            .put("occurrenceStart", occurrenceStart)
            .put("prompt", event.automationPrompt.take(12_000))
            .toString()
        app.agentTaskCoordinator.enqueue(
            taskId = taskId,
            owner = "calendar_automation",
            metadata = AgentTaskMetadata(
                projectId = event.automationProjectId,
                conversationId = "calendar-$eventId",
                kind = "calendar_automation",
                capabilityId = "calendar.schedule",
                title = event.title.take(160),
                input = input
            ),
            idempotencyKey = "calendar:$eventId:$occurrenceStart",
            maxRetries = 0
        )
        CalendarReminderScheduler.schedule(applicationContext, event, replaceAutomation = false)
        return Result.success()
    }
}
