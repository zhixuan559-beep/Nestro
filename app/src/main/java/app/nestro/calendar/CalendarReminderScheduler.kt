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
import app.nestro.agent.AgentTaskStatus
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object CalendarReminderScheduler {
    private const val CHANNEL_ID = "calendar_reminders"

    fun schedule(context: Context, event: CalendarEvent, replaceAutomation: Boolean = true) {
        val now = System.currentTimeMillis()
        val workManager = WorkManager.getInstance(context)

        val reminderOccurrence = if (event.reminderMinutes >= 0) {
            CalendarRules.nextOccurrenceStart(event, now)
        } else null
        if (reminderOccurrence == null) {
            workManager.cancelUniqueWork(workName(event.id))
        } else {
            val triggerAt = reminderOccurrence - event.reminderMinutes * 60_000L
            val request = OneTimeWorkRequestBuilder<CalendarReminderWorker>()
                .setInputData(workDataOf("event_id" to event.id, "occurrence_start" to reminderOccurrence))
                .setInitialDelay((triggerAt - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .addTag("calendar-reminder")
                .build()
            workManager.enqueueUniqueWork(workName(event.id), ExistingWorkPolicy.REPLACE, request)
        }

        if (replaceAutomation) workManager.cancelAllWorkByTag(automationTag(event.id))
        val automationOccurrence = if (event.automationPrompt.isNotBlank() && event.automationProjectId.isNotBlank()) {
            CalendarRules.nextAutomationScheduledOccurrenceStart(event, now)
        } else null
        if (automationOccurrence != null) {
            val automationTriggerAt = CalendarRules.automationTriggerAt(event, automationOccurrence)
            if (automationTriggerAt != null) {
                val automationRequest = OneTimeWorkRequestBuilder<CalendarAutomationWorker>()
                    .setInputData(workDataOf(
                        "event_id" to event.id,
                        "occurrence_start" to automationOccurrence,
                        "automation_generation" to event.automationGeneration
                    ))
                    .setInitialDelay((automationTriggerAt - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                    .addTag("calendar-automation")
                    .addTag(automationTag(event.id))
                    .build()
                workManager.enqueueUniqueWork(
                    automationWorkName(event.id, automationOccurrence, event.automationGeneration),
                    ExistingWorkPolicy.KEEP,
                    automationRequest
                )
            }
        }
    }

    fun cancel(context: Context, eventId: String) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(workName(eventId))
        workManager.cancelAllWorkByTag(automationTag(eventId))
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag("calendar-reminder")
        WorkManager.getInstance(context).cancelAllWorkByTag("calendar-automation")
    }

    private fun workName(eventId: String) = "calendar-reminder-$eventId"
    private fun automationTag(eventId: String) = "calendar-automation-$eventId"
    private fun automationWorkName(eventId: String, occurrenceStart: Long, generation: Long) =
        if (generation == 0L) "calendar-automation-$eventId-$occurrenceStart"
        else "calendar-automation-$eventId-$occurrenceStart-g$generation"

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
        val latestEvent = repository.event(eventId)
        if (latestEvent != null) {
            CalendarReminderScheduler.schedule(applicationContext, latestEvent, replaceAutomation = false)
        }
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
        val scheduledGeneration = inputData.getLong("automation_generation", 0L)
        if (event.automationGeneration != scheduledGeneration) return Result.success()
        if (event.completed || event.automationPrompt.isBlank() || event.automationProjectId.isBlank()) {
            return Result.success()
        }
        if (!CalendarRules.isOccurrence(event, occurrenceStart)) {
            CalendarReminderScheduler.schedule(applicationContext, event, replaceAutomation = false)
            return Result.success()
        }
        val automationTriggerAt = CalendarRules.automationTriggerAt(event, occurrenceStart)
            ?: return Result.success()
        if (System.currentTimeMillis() + 60_000L < automationTriggerAt) return Result.retry()
        val app = applicationContext as? NestroApplication ?: return Result.retry()
        val taskId = CalendarRules.automationTaskId(event, occurrenceStart)
        val input = JSONObject()
            .put("eventId", eventId)
            .put("occurrenceStart", occurrenceStart)
            .put("prompt", event.automationPrompt.take(12_000))
            .toString()
        val idempotencyKey = CalendarRules.automationIdempotencyKey(event, occurrenceStart)
        val nextRunAt = CalendarRules.nextAutomationRunAt(event, automationTriggerAt) ?: 0L
        val repository = CalendarRepository(applicationContext)
        val existing = app.agentTaskCoordinator.state(taskId)
        val dispatched = if (existing != null) {
            existing
        } else {
            repository.recordAutomationDispatch(
                eventId = eventId,
                nextRunAt = nextRunAt,
                lastRunAt = System.currentTimeMillis(),
                taskId = taskId,
                idempotencyKey = idempotencyKey
            )
            try {
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
                    idempotencyKey = idempotencyKey,
                    maxRetries = 2
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (runAttemptCount < 3) return Result.retry()
                repository.recordAutomationResult(
                    eventId,
                    "FAILED",
                    error = (error.message ?: "统一任务机接收日历任务失败").take(2_000)
                )
                return Result.failure()
            }
        }
        val persistedStatus = when (dispatched.status) {
            AgentTaskStatus.CREATED,
            AgentTaskStatus.QUEUED,
            AgentTaskStatus.RETRY_WAIT -> "QUEUED"
            AgentTaskStatus.RUNNING -> "RUNNING"
            AgentTaskStatus.WAITING_FOR_USER -> "WAITING_FOR_USER"
            AgentTaskStatus.WAITING_FOR_TASK -> "WAITING_FOR_TASK"
            AgentTaskStatus.PAUSED -> "PAUSED"
            AgentTaskStatus.COMPLETED -> "COMPLETED"
            AgentTaskStatus.FAILED -> "FAILED"
            AgentTaskStatus.CANCELLED -> "CANCELLED"
        }
        if (persistedStatus != "QUEUED") {
            repository.recordAutomationResult(
                eventId = eventId,
                status = persistedStatus,
                result = if (persistedStatus == "COMPLETED") dispatched.resultSummary else "",
                error = if (persistedStatus in setOf("FAILED", "CANCELLED", "WAITING_FOR_USER", "WAITING_FOR_TASK", "PAUSED")) dispatched.lastError else ""
            )
        }
        CalendarReminderScheduler.schedule(applicationContext, event, replaceAutomation = false)
        return Result.success()
    }
}
