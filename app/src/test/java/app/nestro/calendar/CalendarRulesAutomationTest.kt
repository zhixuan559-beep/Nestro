package app.nestro.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CalendarRulesAutomationTest {
    private fun event(
        startAt: Long,
        recurrence: String = CalendarRules.NONE,
        reminderMinutes: Int = -1,
        automationPrompt: String = "整理本周项目进度",
        automationProjectId: String = "project-a"
    ) = CalendarEvent(
        id = "calendar-event",
        title = "自动任务",
        description = "",
        location = "",
        startAt = startAt,
        endAt = startAt + 60_000L,
        allDay = false,
        recurrence = recurrence,
        reminderMinutes = reminderMinutes,
        completed = false,
        createdAt = startAt - 1_000L,
        updatedAt = startAt - 1_000L,
        automationPrompt = automationPrompt,
        automationProjectId = automationProjectId
    )

    @Test
    fun automationOnlyEventSchedulesAtEventTimeWithoutReminder() {
        val start = System.currentTimeMillis() + 3_600_000L
        val value = event(startAt = start)

        assertEquals(start, CalendarRules.nextOccurrenceStart(value, start - 10_000L))
        assertNull(CalendarRules.nextOccurrenceStart(value, start + 1L))
        assertNull(CalendarRules.nextOccurrenceStart(value.copy(completed = true), start - 10_000L))
    }

    @Test
    fun recurringAutomationCreatesIndependentNextOccurrence() {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val value = event(startAt = start, recurrence = CalendarRules.DAILY)

        val next = CalendarRules.nextOccurrenceStart(value, start + 1L)

        assertTrue(next != null && next > start)
        assertTrue(CalendarRules.isOccurrence(value, start))
        assertTrue(CalendarRules.isOccurrence(value, next!!))
    }

    @Test
    fun automationTargetRequiresAProjectScope() {
        val start = System.currentTimeMillis() + 60_000L
        assertTrue(CalendarRules.validate(event(startAt = start, automationProjectId = "")).orEmpty().contains("项目"))
        assertNull(CalendarRules.validate(event(startAt = start)))
        assertFalse(CalendarRules.validate(event(startAt = start, automationPrompt = "x".repeat(12_001))).isNullOrBlank())
    }
}
