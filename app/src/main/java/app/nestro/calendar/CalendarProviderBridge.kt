package app.nestro.calendar

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Instant

object CalendarProviderBridge {
    data class WritableCalendar(val id: Long, val name: String)

    fun canRead(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canWrite(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun importEvents(context: Context, repository: CalendarRepository, from: Long, until: Long): Int {
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
                val allDay = cursor.getInt(allDayIndex) != 0
                val localStart = if (allDay) {
                    Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } else begin
                val localEnd = if (allDay) {
                    Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } else end
                val event = CalendarEvent(
                    id = "system-$systemCalendarId-$systemEventId-$begin",
                    title = cursor.getString(titleIndex)?.take(120)?.ifBlank { "未命名日程" } ?: "未命名日程",
                    description = cursor.getString(descriptionIndex)?.take(4000).orEmpty(),
                    location = cursor.getString(locationIndex)?.take(300).orEmpty(),
                    startAt = localStart,
                    endAt = localEnd,
                    allDay = allDay,
                    recurrence = CalendarRules.NONE,
                    reminderMinutes = -1,
                    completed = false,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    sourceSystemEventId = systemEventId,
                    sourceSystemCalendarId = systemCalendarId,
                    sourceSystemImported = true
                )
                repository.save(event)
                imported++
            }
        } ?: error("无法读取系统日历内容")
        return imported
    }

    suspend fun exportEvents(context: Context, repository: CalendarRepository, from: Long, until: Long): Int {
        check(canWrite(context)) { "尚未授予写入系统日历权限" }
        val target = writableCalendars(context).firstOrNull()
            ?: error("没有可写入的系统日历，请先在系统日历应用中创建或启用一个日历")
        val zone = ZoneId.systemDefault().id
        val events = repository.allEvents()
            .filter { !it.completed && !it.sourceSystemImported }
        var written = 0
        events.forEach { event ->
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, target.id)
                put(CalendarContract.Events.TITLE, event.title)
                put(CalendarContract.Events.DESCRIPTION, event.description)
                put(CalendarContract.Events.EVENT_LOCATION, event.location)
                val start = if (event.allDay) {
                    Instant.ofEpochMilli(event.startAt).atZone(ZoneId.systemDefault()).toLocalDate()
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                } else event.startAt
                val end = if (event.allDay) {
                    Instant.ofEpochMilli(event.endAt).atZone(ZoneId.systemDefault()).toLocalDate()
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                } else event.endAt
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, if (event.allDay) "UTC" else zone)
                put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
                put(CalendarContract.Events.HAS_ALARM, 0)
                when (event.recurrence) {
                    CalendarRules.DAILY -> put(CalendarContract.Events.RRULE, "FREQ=DAILY")
                    CalendarRules.WEEKLY -> put(CalendarContract.Events.RRULE, "FREQ=WEEKLY")
                    CalendarRules.MONTHLY -> put(CalendarContract.Events.RRULE, "FREQ=MONTHLY")
                    CalendarRules.YEARLY -> put(CalendarContract.Events.RRULE, "FREQ=YEARLY")
                }
            }
            val linkedId = event.sourceSystemEventId
            val linkedCalendarId = event.sourceSystemCalendarId ?: target.id
            val updated = linkedId?.let { id ->
                context.contentResolver.update(
                    CalendarContract.Events.CONTENT_URI, values,
                    "${CalendarContract.Events._ID}=? AND ${CalendarContract.Events.CALENDAR_ID}=?",
                    arrayOf(id.toString(), linkedCalendarId.toString())
                )
            } ?: 0
            val systemEventId = if (updated > 0 && linkedId != null) linkedId else {
                val inserted = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    ?: error("系统日历拒绝写入：${event.title}")
                inserted.lastPathSegment?.toLongOrNull() ?: error("系统日历未返回事件编号")
            }
            context.contentResolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID}=?",
                arrayOf(systemEventId.toString())
            )
            repository.save(event.copy(
                sourceSystemEventId = systemEventId,
                sourceSystemCalendarId = target.id,
                sourceSystemImported = false
            ))
            written++
        }
        return written
    }

    fun deleteExportedEvent(context: Context, event: CalendarEvent) {
        check(canWrite(context)) { "删除系统日历中的关联日程需要写入权限" }
        val eventId = event.sourceSystemEventId ?: return
        context.contentResolver.delete(
            CalendarContract.Reminders.CONTENT_URI,
            "${CalendarContract.Reminders.EVENT_ID}=?",
            arrayOf(eventId.toString())
        )
        context.contentResolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events._ID}=? AND ${CalendarContract.Events.CALENDAR_ID}=?",
            arrayOf(eventId.toString(), event.sourceSystemCalendarId?.toString().orEmpty())
        )
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
