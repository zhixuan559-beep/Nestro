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
        return save(event.copy(completed = old?.completed ?: false))
    }

    suspend fun save(event: CalendarEvent): CalendarEvent {
        CalendarRules.validate(event)?.let { error(it) }
        val now = System.currentTimeMillis()
        val old = dao.event(event.id)
        val saved = event.copy(
            title = event.title.trim(),
            description = event.description.trim(),
            location = event.location.trim(),
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
            sourceSystemEventId = event.sourceSystemEventId ?: old?.sourceSystemEventId,
            sourceSystemCalendarId = event.sourceSystemCalendarId ?: old?.sourceSystemCalendarId,
            sourceSystemImported = event.sourceSystemImported || old?.sourceSystemImported == true
        )
        dao.put(saved)
        CalendarReminderScheduler.schedule(appContext, saved)
        return saved
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
        if (event != null && event.sourceSystemEventId != null && !event.sourceSystemImported) {
            CalendarProviderBridge.deleteExportedEvent(appContext, event)
        }
        dao.delete(id)
        CalendarReminderScheduler.cancel(appContext, id)
    }

    suspend fun setCompleted(id: String, completed: Boolean) {
        val event = dao.event(id) ?: return
        val updated = event.copy(completed = completed, updatedAt = System.currentTimeMillis())
        dao.put(updated)
        CalendarReminderScheduler.schedule(appContext, updated)
    }

    suspend fun reconcileSchedules() {
        val events = dao.allEvents()
        val activeIds = events.filter { !it.completed && it.reminderMinutes >= 0 }.map { it.id }.toSet()
        events.forEach { event ->
            if (event.id in activeIds) CalendarReminderScheduler.schedule(appContext, event)
            else CalendarReminderScheduler.cancel(appContext, event.id)
        }
    }
}
