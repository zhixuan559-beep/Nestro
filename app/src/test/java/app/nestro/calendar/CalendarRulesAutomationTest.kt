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
        automationProjectId: String = "project-a",
        automationDelayMinutes: Int = 0,
        automationGeneration: Long = 0L
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
        automationProjectId = automationProjectId,
        automationDelayMinutes = automationDelayMinutes,
        automationGeneration = automationGeneration
    )

    @Test
    fun automationOnlyEventSchedulesAtEventTimeWithoutReminder() {
        val start = System.currentTimeMillis() + 3_600_000L
        val value = event(startAt = start)

        assertEquals(start, CalendarRules.nextAutomationOccurrenceStart(value, start - 10_000L))
        assertNull(CalendarRules.nextAutomationOccurrenceStart(value, start + 1L))
        assertNull(CalendarRules.nextAutomationOccurrenceStart(value.copy(completed = true), start - 10_000L))
    }

    @Test
    fun recurringAutomationCreatesIndependentNextOccurrence() {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val value = event(startAt = start, recurrence = CalendarRules.DAILY)

        val next = CalendarRules.nextAutomationOccurrenceStart(value, start + 1L)

        assertTrue(next != null && next > start)
        assertTrue(CalendarRules.isOccurrence(value, start))
        assertTrue(CalendarRules.isOccurrence(value, next!!))
    }

    @Test
    fun automationStillRunsWhenReminderLeadTimeHasAlreadyPassed() {
        val start = System.currentTimeMillis() + 10 * 60_000L
        val value = event(startAt = start, reminderMinutes = 30)

        assertNull(CalendarRules.nextOccurrenceStart(value, System.currentTimeMillis()))
        assertEquals(start, CalendarRules.nextAutomationOccurrenceStart(value, System.currentTimeMillis()))
    }

    @Test
    fun automationDelayIsAppliedAfterTheOccurrenceAndIncludedInNextRun() {
        val start = System.currentTimeMillis() + 3_600_000L
        val value = event(startAt = start, automationDelayMinutes = 45)

        assertEquals(start + 45 * 60_000L, CalendarRules.automationTriggerAt(value, start))
        assertEquals(start + 45 * 60_000L, CalendarRules.nextAutomationRunAt(value, start - 1L))
        assertEquals(start + 45 * 60_000L, CalendarRules.nextAutomationRunAt(value, start + 1L))
        assertNull(CalendarRules.automationTriggerAt(value, start + 1L))
    }

    @Test
    fun delayedOneTimeAutomationRemainsScheduledAfterTheEventStarts() {
        val start = System.currentTimeMillis() - 5 * 60_000L
        val value = event(startAt = start, automationDelayMinutes = 30)

        assertEquals(start, CalendarRules.nextAutomationScheduledOccurrenceStart(value, System.currentTimeMillis()))
        assertEquals(start + 30 * 60_000L, CalendarRules.nextAutomationRunAt(value, System.currentTimeMillis()))
    }

    @Test
    fun recurringAutomationDelayMovesEachOccurrenceWithoutChangingOccurrenceIdentity() {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val value = event(startAt = start, recurrence = CalendarRules.DAILY, automationDelayMinutes = 30)
        val nextOccurrence = CalendarRules.nextAutomationOccurrenceStart(value, start + 1L)!!

        assertTrue(CalendarRules.isOccurrence(value, nextOccurrence))
        assertEquals(nextOccurrence + 30 * 60_000L, CalendarRules.automationTriggerAt(value, nextOccurrence))
        assertEquals(nextOccurrence + 30 * 60_000L, CalendarRules.nextAutomationRunAt(value, start + 1L))
    }

    @Test
    fun automationDelayMustStayWithinSupportedRange() {
        val start = System.currentTimeMillis() + 60_000L
        assertNull(CalendarRules.validate(event(startAt = start, automationDelayMinutes = Int.MAX_VALUE)))
        assertNull(CalendarRules.validate(event(startAt = start, automationDelayMinutes = -CalendarRules.MAX_AUTOMATION_ADVANCE_MINUTES)))
        assertTrue(CalendarRules.validate(event(startAt = start, automationDelayMinutes = -CalendarRules.MAX_AUTOMATION_ADVANCE_MINUTES - 1)).orEmpty().contains("偏移"))
    }

    @Test
    fun automationDefinitionGenerationPreventsReusingCancelledOccurrenceIdentity() {
        val start = System.currentTimeMillis() + 60_000L
        val legacy = event(startAt = start)
        val first = legacy.copy(automationGeneration = 1L)
        val edited = first.copy(automationGeneration = 2L)

        assertEquals("calendar-calendar-event-$start", CalendarRules.automationTaskId(legacy, start))
        assertEquals("calendar:calendar-event:$start", CalendarRules.automationIdempotencyKey(legacy, start))
        assertTrue(CalendarRules.automationTaskId(first, start) != CalendarRules.automationTaskId(edited, start))
        assertTrue(CalendarRules.automationIdempotencyKey(first, start) != CalendarRules.automationIdempotencyKey(edited, start))
        assertTrue(CalendarRules.automationTaskId(edited, start).length <= 120)
    }

    @Test
    fun automationTargetRequiresAProjectScope() {
        val start = System.currentTimeMillis() + 60_000L
        assertTrue(CalendarRules.validate(event(startAt = start, automationProjectId = "")).orEmpty().contains("项目"))
        assertNull(CalendarRules.validate(event(startAt = start)))
        assertFalse(CalendarRules.validate(event(startAt = start, automationPrompt = "x".repeat(12_001))).isNullOrBlank())
    }
    @Test
    fun supportsPreEventOffsetAndRunsImmediatelyIfThatTriggerWasMissed() {
        val start = 1_800_000_000_000L
        val value = event(startAt = start, automationDelayMinutes = -30)
        assertEquals(start - 30 * 60_000L, CalendarRules.automationTriggerAt(value, start))
        assertEquals(start, CalendarRules.nextAutomationScheduledOccurrenceStart(value, start - 10 * 60_000L))
        assertEquals(start - 10 * 60_000L, CalendarRules.nextAutomationRunAt(value, start - 10 * 60_000L))
        assertNull(CalendarRules.nextAutomationScheduledOccurrenceStart(value, start + 1L))
    }

    @Test
    fun recurringPreEventOffsetSkipsPastOccurrenceAndSchedulesNextOne() {
        val start = 1_800_000_000_000L
        val value = event(startAt = start, recurrence = CalendarRules.DAILY, automationDelayMinutes = -15)
        val next = CalendarRules.nextAutomationScheduledOccurrenceStart(value, start + 5 * 60_000L)
        assertEquals(start + 24 * 60 * 60 * 1000L, next)
    }


    @Test
    fun missedPreEventTriggerIsNotScheduledAgainAfterDispatch() {
        val start = 1_800_000_000_000L
        val value = event(startAt = start, automationDelayMinutes = -30)
        val dispatched = value.copy(
            lastTaskId = CalendarRules.automationTaskId(value, start),
            lastRunAt = start - 10 * 60_000L
        )
        assertNull(CalendarRules.nextAutomationScheduledOccurrenceStart(dispatched, start - 10 * 60_000L))
    }

    @Test
    fun definitionGenerationAllowsNewPreEventTriggerAfterPreviousDispatch() {
        val start = 1_800_000_000_000L
        val original = event(startAt = start, automationDelayMinutes = -30, automationGeneration = 1L)
        val dispatched = original.copy(lastTaskId = CalendarRules.automationTaskId(original, start))
        val edited = dispatched.copy(automationGeneration = 2L)
        assertEquals(start, CalendarRules.nextAutomationScheduledOccurrenceStart(edited, start - 10 * 60_000L))
    }


    @Test
    fun latePreEventDispatchDoesNotBackfillEveryFutureRecurrence() {
        val start = 1_800_000_000_000L
        val value = event(startAt = start, recurrence = CalendarRules.DAILY, automationDelayMinutes = -10_080)
        val dispatched = value.copy(
            lastTaskId = CalendarRules.automationTaskId(value, start),
            lastRunAt = start - 6 * 24 * 60 * 60 * 1000L
        )
        val now = start - 6 * 24 * 60 * 60 * 1000L
        assertEquals(start + 7 * 24 * 60 * 60 * 1000L, CalendarRules.nextAutomationScheduledOccurrenceStart(dispatched, now))
    }


}
