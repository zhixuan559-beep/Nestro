package app.nestro.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarRulesTest {
    private fun event(
        start: ZonedDateTime,
        recurrence: String = CalendarRules.NONE,
        reminder: Int = 10
    ) = CalendarEvent(
        id = "event-1",
        title = "测试日程",
        description = "",
        location = "",
        startAt = start.toInstant().toEpochMilli(),
        endAt = start.plusHours(1).toInstant().toEpochMilli(),
        allDay = false,
        recurrence = recurrence,
        reminderMinutes = reminder,
        completed = false,
        createdAt = start.toInstant().toEpochMilli(),
        updatedAt = start.toInstant().toEpochMilli()
    )

    @Test fun validationRejectsInvalidDurationAndRepeatRule() {
        val start = ZonedDateTime.now()
        assertNotNull(CalendarRules.validate(event(start).copy(endAt = start.toInstant().toEpochMilli())))
        assertNotNull(CalendarRules.validate(event(start).copy(recurrence = "EVERY_MINUTE")))
        assertNull(CalendarRules.validate(event(start)))
    }

    @Test fun expiredOneTimeReminderIsNotRescheduled() {
        val start = ZonedDateTime.now().minusDays(2)
        assertNull(CalendarRules.nextOccurrenceStart(event(start), System.currentTimeMillis()))
    }

    @Test fun dailyReminderAdvancesByLocalCalendarDay() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone).withHour(8).withMinute(55).withSecond(0).withNano(0)
        val start = now.minusDays(2).withHour(9).withMinute(0)
        val expected = start.plusDays(2).toInstant().toEpochMilli()
        val actual = CalendarRules.nextOccurrenceStart(
            event(start, CalendarRules.DAILY, 10),
            now.toInstant().toEpochMilli()
        )
        assertEquals(expected, actual)
    }

    @Test fun weeklyOccurrencesExpandInsideRequestedRange() {
        val start = LocalDate.of(2026, 1, 5).atTime(9, 0).atZone(ZoneId.systemDefault())
        val event = event(start, CalendarRules.WEEKLY, -1)
        val occurrences = CalendarRules.occurrences(
            listOf(event),
            start.toInstant().toEpochMilli(),
            start.plusDays(22).toInstant().toEpochMilli()
        )
        assertEquals(4, occurrences.size)
        assertTrue(occurrences.zipWithNext().all { (a, b) -> b.startAt > a.startAt })
    }
}
