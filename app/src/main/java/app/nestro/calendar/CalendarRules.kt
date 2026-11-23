package app.nestro.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object CalendarRules {
    const val MAX_AUTOMATION_ADVANCE_MINUTES = 10_080
    const val MAX_AUTOMATION_DELAY_MINUTES = Int.MAX_VALUE
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
        if (event.automationPrompt.length > 12_000) return "自动执行目标不能超过 12000 个字符"
        if (event.automationDelayMinutes !in -MAX_AUTOMATION_ADVANCE_MINUTES..MAX_AUTOMATION_DELAY_MINUTES) return "自动任务偏移范围无效（最多提前 7 天）"
        if (event.automationGeneration < 0L) return "自动任务版本无效"
        if (event.automationPrompt.isNotBlank() &&
            !event.automationProjectId.matches(Regex("^[A-Za-z0-9_-]{1,80}$"))
        ) return "自动执行任务必须绑定有效项目"
        if (event.startAt <= 0L || event.endAt <= event.startAt) return "结束时间必须晚于开始时间"
        if (event.endAt - event.startAt > 3660L * 24 * 60 * 60 * 1000) return "单次日程时长不能超过 10 年"
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

    /** Returns the next occurrence whose reminder lead-time is still ahead of now. */
    fun nextOccurrenceStart(event: CalendarEvent, now: Long): Long? {
        if (event.completed || event.reminderMinutes < 0) return null
        return nextOccurrenceAfter(event, now, event.reminderMinutes * 60_000L)
    }

    /** Returns the next actual event start for automation, independent of reminder settings. */
    fun nextAutomationOccurrenceStart(event: CalendarEvent, now: Long): Long? {
        if (event.completed || event.automationPrompt.isBlank()) return null
        return nextOccurrenceAfter(event, now, 0L)
    }

    private fun automationTriggerAtUnchecked(event: CalendarEvent, occurrenceStart: Long): Long? {
        if (event.automationPrompt.isBlank() || occurrenceStart < event.startAt) return null
        val offsetMillis = event.automationDelayMinutes.toLong() * 60_000L
        return when {
            offsetMillis > 0L && Long.MAX_VALUE - occurrenceStart < offsetMillis -> Long.MAX_VALUE
            offsetMillis < 0L && occurrenceStart < Long.MIN_VALUE - offsetMillis -> Long.MIN_VALUE
            else -> occurrenceStart + offsetMillis
        }
    }

    /** Returns the scheduled execution instant for a valid occurrence of this event. */
    fun automationTriggerAt(event: CalendarEvent, occurrenceStart: Long): Long? {
        if (!isOccurrence(event, occurrenceStart)) return null
        return automationTriggerAtUnchecked(event, occurrenceStart)
    }

    /** Finds the next occurrence whose trigger instant is pending; a missed pre-event trigger runs immediately while the event is still upcoming. */
    fun nextAutomationScheduledOccurrenceStart(event: CalendarEvent, now: Long): Long? {
        if (event.completed || event.automationPrompt.isBlank()) return null
        var candidate = event.startAt
        val anchor = Instant.ofEpochMilli(event.startAt).atZone(ZoneId.systemDefault())
        var guard = 0
        while (guard++ < 20_000) {
            val triggerAt = automationTriggerAtUnchecked(event, candidate) ?: return null
            if (triggerAt > now || (event.automationDelayMinutes < 0 && candidate > now)) return candidate
            if (event.recurrence == NONE) return null
            candidate = nextStart(candidate, event.recurrence, anchor.dayOfMonth, anchor.monthValue)
            if (candidate == Long.MAX_VALUE) return null
        }
        return null
    }

    /** Returns the next automation execution instant, including its configured post-event delay. */
    fun nextAutomationRunAt(event: CalendarEvent, now: Long): Long? {
        val occurrence = nextAutomationScheduledOccurrenceStart(event, now) ?: return null
        val triggerAt = automationTriggerAt(event, occurrence) ?: return null
        return triggerAt.coerceAtLeast(now)
    }

    /** Stable per-definition task identity; edits advance automationGeneration before rescheduling. */
    fun automationTaskId(event: CalendarEvent, occurrenceStart: Long): String {
        require(event.id.isNotBlank() && event.id.length <= 80)
        require(occurrenceStart > 0L && event.automationGeneration >= 0L)
        return if (event.automationGeneration == 0L) {
            "calendar-${event.id}-$occurrenceStart"
        } else {
            "cal-${event.id}-$occurrenceStart-g${event.automationGeneration}"
        }
    }

    fun automationIdempotencyKey(event: CalendarEvent, occurrenceStart: Long): String {
        require(event.id.isNotBlank() && event.id.length <= 80)
        require(occurrenceStart > 0L && event.automationGeneration >= 0L)
        return if (event.automationGeneration == 0L) {
            "calendar:${event.id}:$occurrenceStart"
        } else {
            "calendar:${event.id}:$occurrenceStart:g${event.automationGeneration}"
        }
    }

    private fun nextOccurrenceAfter(event: CalendarEvent, now: Long, leadTimeMillis: Long): Long? {
        var candidate = event.startAt
        val anchor = Instant.ofEpochMilli(event.startAt).atZone(ZoneId.systemDefault())
        if (event.recurrence == NONE) return candidate.takeIf { it - leadTimeMillis > now }
        var guard = 0
        while (candidate - leadTimeMillis <= now && guard++ < 20_000) {
            candidate = nextStart(candidate, event.recurrence, anchor.dayOfMonth, anchor.monthValue)
            if (candidate == Long.MAX_VALUE) return null
        }
        return candidate.takeIf { it != Long.MAX_VALUE && it - leadTimeMillis > now }
    }

    fun occurrences(events: List<CalendarEvent>, from: Long, until: Long, limit: Int = 500): List<Occurrence> {
        if (until <= from || limit <= 0) return emptyList()
        val result = ArrayList<Occurrence>()
        for (event in events) {
            if (event.completed) continue
            val duration = event.endAt - event.startAt
            val zone = ZoneId.systemDefault()
            val originalStartDate = Instant.ofEpochMilli(event.startAt).atZone(zone).toLocalDate()
            val allDaySpan = java.time.temporal.ChronoUnit.DAYS.between(
                originalStartDate,
                Instant.ofEpochMilli(event.endAt).atZone(zone).toLocalDate()
            ).coerceAtLeast(1)
            var start = event.startAt
            val anchor = Instant.ofEpochMilli(event.startAt).atZone(zone)
            var guard = 0
            while (start < until && result.size < limit && guard++ < 20_000) {
                val occurrenceLocalDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                val end = if (event.allDay) occurrenceLocalDate.plusDays(allDaySpan).atStartOfDay(zone).toInstant().toEpochMilli() else start + duration
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
