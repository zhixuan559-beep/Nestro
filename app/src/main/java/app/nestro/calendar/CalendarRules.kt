package app.nestro.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

object CalendarRules {
    const val NONE = "NONE"
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"
    const val YEARLY = "YEARLY"
    val recurrenceValues = setOf(NONE, DAILY, WEEKLY, MONTHLY, YEARLY)

    data class Occurrence(val event: CalendarEvent, val startAt: Long, val endAt: Long)

    fun validate(event: CalendarEvent): String? {
        if (event.id.isBlank() || event.id.length > 80) return "日程标识无效"
        if (event.title.isBlank() || event.title.length > 120) return "标题不能为空，且不能超过 120 个字符"
        if (event.description.length > 4000) return "备注不能超过 4000 个字符"
        if (event.location.length > 300) return "地点不能超过 300 个字符"
        if (event.startAt <= 0L || event.endAt <= event.startAt) return "结束时间必须晚于开始时间"
        if (event.endAt - event.startAt > 31L * 24 * 60 * 60 * 1000) return "单次日程时长不能超过 31 天"
        if (event.recurrence !in recurrenceValues) return "重复规则无效"
        if (event.reminderMinutes !in setOf(-1, 0, 5, 10, 15, 30, 60, 1440)) return "提醒时间无效"
        return null
    }

    private fun nextStart(
        startAt: Long,
        recurrence: String,
        anchorDay: Int,
        anchorMonth: Int
    ): Long {
        val zone = ZoneId.systemDefault()
        val local = Instant.ofEpochMilli(startAt).atZone(zone)
        val next = when (recurrence) {
            DAILY -> local.plusDays(1)
            WEEKLY -> local.plusWeeks(1)
            MONTHLY -> {
                val month = java.time.YearMonth.from(local).plusMonths(1)
                month.atDay(anchorDay.coerceAtMost(month.lengthOfMonth()))
                    .atTime(local.toLocalTime()).atZone(zone)
            }
            YEARLY -> {
                val date = LocalDate.of(
                    local.year + 1, anchorMonth,
                    anchorDay.coerceAtMost(java.time.YearMonth.of(local.year + 1, anchorMonth).lengthOfMonth())
                )
                date.atTime(local.toLocalTime()).atZone(zone)
            }
            else -> return Long.MAX_VALUE
        }
        return next.toInstant().toEpochMilli()
    }

    /** Returns the next occurrence whose reminder time is strictly after now. */
    fun nextOccurrenceStart(event: CalendarEvent, now: Long): Long? {
        if (event.completed || event.reminderMinutes < 0) return null
        var candidate = event.startAt
        val anchor = Instant.ofEpochMilli(event.startAt).atZone(ZoneId.systemDefault())
        val reminderMillis = event.reminderMinutes * 60_000L
        if (event.recurrence == NONE) {
            return candidate.takeIf { it - reminderMillis > now }
        }
        var guard = 0
        while (candidate - reminderMillis <= now && guard++ < 20_000) {
            candidate = nextStart(candidate, event.recurrence, anchor.dayOfMonth, anchor.monthValue)
            if (candidate == Long.MAX_VALUE) return null
        }
        return candidate.takeIf { it != Long.MAX_VALUE && it - reminderMillis > now }
    }

    fun occurrences(events: List<CalendarEvent>, from: Long, until: Long, limit: Int = 500): List<Occurrence> {
        if (until <= from || limit <= 0) return emptyList()
        val result = ArrayList<Occurrence>()
        for (event in events) {
            if (event.completed) continue
            val duration = event.endAt - event.startAt
            var start = event.startAt
            val anchor = Instant.ofEpochMilli(event.startAt).atZone(ZoneId.systemDefault())
            var guard = 0
            while (start < until && result.size < limit && guard++ < 20_000) {
                val occurrenceLocalDate = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()
                val end = if (event.allDay) occurrenceLocalDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() else start + duration
                if (end >= from) result += Occurrence(event, start, end)
                if (event.recurrence == NONE) break
                start = nextStart(start, event.recurrence, anchor.dayOfMonth, anchor.monthValue)
                if (start == Long.MAX_VALUE) break
            }
            if (result.size >= limit) break
        }
        return result.sortedWith(compareBy<Occurrence> { it.startAt }.thenBy { it.event.title })
    }

    fun isOccurrence(event: CalendarEvent, occurrenceStart: Long): Boolean {
        if (occurrenceStart < event.startAt) return false
        if (event.recurrence == NONE) return occurrenceStart == event.startAt
        var candidate = event.startAt
        val anchor = Instant.ofEpochMilli(event.startAt).atZone(ZoneId.systemDefault())
        var guard = 0
        while (candidate < occurrenceStart && guard++ < 20_000) {
            candidate = nextStart(candidate, event.recurrence, anchor.dayOfMonth, anchor.monthValue)
            if (candidate == Long.MAX_VALUE) return false
        }
        return candidate == occurrenceStart
    }

    fun dateTime(date: LocalDate, hour: Int, minute: Int): Long {
        require(hour in 0..23 && minute in 0..59)
        return date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
