package app.nestro.calendar

import android.content.Context
import java.util.UUID

class CalendarRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = CalendarDatabase.get(appContext).calendarDao()

    suspend fun allEvents(): List<CalendarEvent> = dao.allEvents()

    suspend fun isAlreadyExported(calendarId: Long, eventId: Long): Boolean =
        dao.exportedSystemEvent(calendarId, eventId) != null

    suspend fun importSystemEvent(event: CalendarEvent): CalendarEvent {
        val old = dao.event(event.id)
        return save(event.copy(
            completed = old?.completed ?: false,
            automationPrompt = old?.automationPrompt.orEmpty(),
            automationProjectId = old?.automationProjectId.orEmpty(),
            nextRunAt = old?.nextRunAt ?: 0L,
            lastRunAt = old?.lastRunAt ?: 0L,
            lastTaskId = old?.lastTaskId.orEmpty(),
            lastIdempotencyKey = old?.lastIdempotencyKey.orEmpty(),
            automationStatus = old?.automationStatus ?: "IDLE",
            automationResult = old?.automationResult.orEmpty(),
            automationError = old?.automationError.orEmpty(),
            automationUpdatedAt = old?.automationUpdatedAt ?: 0L
        ))
    }

    suspend fun save(event: CalendarEvent): CalendarEvent {
        CalendarRules.validate(event)?.let { error(it) }
        val now = System.currentTimeMillis()
        var old = dao.event(event.id)
        val automationDefinitionChanged = old != null && (
            old.automationPrompt != event.automationPrompt ||
                old.automationProjectId != event.automationProjectId ||
                old.startAt != event.startAt ||
                old.recurrence != event.recurrence ||
                old.completed != event.completed
            )
        if (automationDefinitionChanged) {
            cancelActiveAutomation(old!!, "日历自动任务配置已修改，旧任务已停止")
            old = dao.event(event.id) ?: old
        }
        val saved = event.copy(
            title = event.title.trim(),
            description = event.description.trim(),
            location = event.location.trim(),
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
            sourceSystemEventId = event.sourceSystemEventId ?: old?.sourceSystemEventId,
            sourceSystemCalendarId = event.sourceSystemCalendarId ?: old?.sourceSystemCalendarId,
            sourceSystemImported = event.sourceSystemImported || old?.sourceSystemImported == true,
            nextRunAt = old?.nextRunAt ?: event.nextRunAt,
            lastRunAt = old?.lastRunAt ?: event.lastRunAt,
            lastTaskId = old?.lastTaskId ?: event.lastTaskId,
            lastIdempotencyKey = old?.lastIdempotencyKey ?: event.lastIdempotencyKey,
            automationStatus = old?.automationStatus ?: event.automationStatus,
            automationResult = old?.automationResult ?: event.automationResult,
            automationError = old?.automationError ?: event.automationError,
            automationUpdatedAt = old?.automationUpdatedAt ?: event.automationUpdatedAt
        )
        dao.put(saved)
        val nextAutomationRun = if (saved.automationPrompt.isNotBlank()) {
            CalendarRules.nextAutomationOccurrenceStart(saved, now) ?: 0L
        } else 0L
        dao.updateAutomationNextRunAt(saved.id, nextAutomationRun, now)
        CalendarReminderScheduler.schedule(appContext, saved)
        return saved
    }

    suspend fun recordAutomationDispatch(
        eventId: String,
        nextRunAt: Long,
        lastRunAt: Long,
        taskId: String,
        idempotencyKey: String
    ) {
        dao.recordAutomationDispatch(
            id = eventId,
            nextRunAt = nextRunAt,
            lastRunAt = lastRunAt,
            taskId = taskId,
            idempotencyKey = idempotencyKey,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun recordAutomationResult(
        eventId: String,
        status: String,
        result: String = "",
        error: String = ""
    ) {
        require(status in setOf("IDLE", "QUEUED", "RUNNING", "WAITING_FOR_USER", "WAITING_FOR_TASK", "PAUSED", "COMPLETED", "FAILED", "CANCELLED"))
        dao.recordAutomationResult(
            id = eventId,
            status = status,
            result = result.take(8_000),
            error = error.take(2_000),
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun create(
        title: String, description: String, location: String, startAt: Long, endAt: Long,
        allDay: Boolean, recurrence: String, reminderMinutes: Int
    ): CalendarEvent {
        val now = System.currentTimeMillis()
        return save(CalendarEvent(
            id = UUID.randomUUID().toString(), title = title, description = description,
            location = location, startAt = startAt, endAt = endAt, allDay = allDay,
            recurrence = recurrence, reminderMinutes = reminderMinutes, completed = false,
            createdAt = now, updatedAt = now
        ))
    }

    suspend fun delete(id: String) {
        val event = dao.event(id)
        if (event != null) cancelActiveAutomation(event, "关联日程已删除")
        if (event != null && event.sourceSystemEventId != null && !event.sourceSystemImported) {
            CalendarProviderBridge.deleteExportedEvent(appContext, event)
        }
        dao.delete(id)
        CalendarReminderScheduler.cancel(appContext, id)
    }

    suspend fun setCompleted(id: String, completed: Boolean) {
        val event = dao.event(id) ?: return
        if (completed) cancelActiveAutomation(event, "日程已标记完成，自动任务已停止")
        val updated = event.copy(completed = completed, updatedAt = System.currentTimeMillis())
        dao.put(updated)
        CalendarReminderScheduler.schedule(appContext, updated)
    }

    private suspend fun cancelActiveAutomation(event: CalendarEvent, reason: String) {
        if (event.lastTaskId.isBlank()) return
        val nestroApp = appContext as? app.nestro.NestroApplication ?: return
        val state = nestroApp.agentTaskCoordinator.state(event.lastTaskId) ?: return
        if (state.status in setOf(
                app.nestro.agent.AgentTaskStatus.CREATED,
                app.nestro.agent.AgentTaskStatus.QUEUED,
                app.nestro.agent.AgentTaskStatus.RUNNING,
                app.nestro.agent.AgentTaskStatus.RETRY_WAIT,
                app.nestro.agent.AgentTaskStatus.WAITING_FOR_USER,
                app.nestro.agent.AgentTaskStatus.WAITING_FOR_TASK,
                app.nestro.agent.AgentTaskStatus.PAUSED
            )
        ) {
            nestroApp.agentTaskCoordinator.cancel(event.lastTaskId)
            recordAutomationResult(event.id, "CANCELLED", error = reason)
        }
    }

    suspend fun reconcileSchedules() {
        val events = dao.allEvents()
        val activeIds = events.filter {
            !it.completed && (it.reminderMinutes >= 0 || it.automationPrompt.isNotBlank())
        }.map { it.id }.toSet()
        events.forEach { event ->
            if (event.id in activeIds) CalendarReminderScheduler.schedule(appContext, event)
            else CalendarReminderScheduler.cancel(appContext, event.id)
        }
    }
}
