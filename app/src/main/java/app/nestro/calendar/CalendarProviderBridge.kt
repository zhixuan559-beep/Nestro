package app.nestro.calendar

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.ZoneId

object CalendarProviderBridge {
    data class WritableCalendar(val id: Long, val name: String)

    fun canRead(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canWrite(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun importEvents(context: Context, repository: CalendarRepository, from: Long, until: Long): Int {
        check(canRead(context)) { "尚未授予读取系统日历权限" }
        require(until > from) { "导入时间范围无效" }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString()).appendPath(until.toString()).build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Events.ALL_DAY
        )
        var imported = 0
        context.contentResolver.query(uri, projection, null, null, "begin ASC")?.use { cursor ->
            val eventIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val calendarIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val descriptionIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
            val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
            while (cursor.moveToNext()) {
                val systemEventId = cursor.getLong(eventIdIndex)
                val systemCalendarId = cursor.getLong(calendarIdIndex)
                val begin = cursor.getLong(beginIndex)
                val end = cursor.getLong(endIndex)
                if (systemEventId <= 0L || systemCalendarId <= 0L || end <= begin) continue
                val event = CalendarEvent(
                    id = "system-$systemCalendarId-$systemEventId-$begin",
                    title = cursor.getString(titleIndex)?.take(120)?.ifBlank { "未命名日程" } ?: "未命名日程",
                    description = cursor.getString(descriptionIndex)?.take(4000).orEmpty(),
                    location = cursor.getString(locationIndex)?.take(300).orEmpty(),
                    startAt = begin,
                    endAt = end,
                    allDay = cursor.getInt(allDayIndex) != 0,
                    recurrence = CalendarRules.NONE,
                    reminderMinutes = -1,
                    completed = false,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    sourceSystemEventId = systemEventId,
                    sourceSystemCalendarId = systemCalendarId
                )
                repository.save(event)
                imported++
            }
        } ?: error("无法读取系统日历内容")
        return imported
    }

    fun exportEvents(context: Context, repository: CalendarRepository, from: Long, until: Long): Int {
        check(canWrite(context)) { "尚未授予写入系统日历权限" }
        val target = writableCalendars(context).firstOrNull()
            ?: error("没有可写入的系统日历，请先在系统日历应用中创建或启用一个日历")
        val zone = ZoneId.systemDefault().id
        val events = kotlinx.coroutines.runBlocking { repository.allEvents() }
            .filter { it.startAt < until && it.endAt >= from && !it.completed && it.sourceSystemEventId == null }
        var written = 0
        events.forEach { event ->
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, target.id)
                put(CalendarContract.Events.TITLE, event.title)
                put(CalendarContract.Events.DESCRIPTION, event.description)
                put(CalendarContract.Events.EVENT_LOCATION, event.location)
                put(CalendarContract.Events.DTSTART, event.startAt)
                put(CalendarContract.Events.DTEND, event.endAt)
                put(CalendarContract.Events.EVENT_TIMEZONE, zone)
                put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
                put(CalendarContract.Events.HAS_ALARM, if (event.reminderMinutes >= 0) 1 else 0)
                when (event.recurrence) {
                    CalendarRules.DAILY -> put(CalendarContract.Events.RRULE, "FREQ=DAILY")
                    CalendarRules.WEEKLY -> put(CalendarContract.Events.RRULE, "FREQ=WEEKLY")
                    CalendarRules.MONTHLY -> put(CalendarContract.Events.RRULE, "FREQ=MONTHLY")
                    CalendarRules.YEARLY -> put(CalendarContract.Events.RRULE, "FREQ=YEARLY")
                }
            }
            val inserted = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: error("系统日历拒绝写入：${event.title}")
            val systemEventId = inserted.lastPathSegment?.toLongOrNull()
                ?: error("系统日历未返回事件编号")
            if (event.reminderMinutes >= 0) {
                val reminder = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, systemEventId)
                    put(CalendarContract.Reminders.MINUTES, event.reminderMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder)
            }
            repository.save(event.copy(
                sourceSystemEventId = systemEventId,
                sourceSystemCalendarId = target.id
            ))
            written++
        }
        return written
    }

    fun writableCalendars(context: Context): List<WritableCalendar> {
        check(canRead(context)) { "读取系统日历列表需要日历读取权限" }
        check(canWrite(context)) { "读取可写日历需要日历写入权限" }
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        val result = mutableListOf<WritableCalendar>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection,
            "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            while (cursor.moveToNext()) result += WritableCalendar(
                cursor.getLong(idIndex), cursor.getString(nameIndex).orEmpty()
            )
        } ?: error("无法读取系统日历列表")
        return result
    }
}
