package app.nestro.ui

import app.nestro.data.ExpertTeamCollaborationEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpertTeamEventPaginationTest {
    @Test
    fun showsTheMostRecentPageInNewestFirstOrder() {
        val events = (1L..20L).map { sequence ->
            ExpertTeamCollaborationEvent(
                taskId = "task",
                projectId = "project",
                teamId = "team",
                sequence = sequence,
                type = "member_message",
                createdAt = sequence
            )
        }

        assertEquals(
            (20L downTo 5L).toList(),
            visibleExpertTeamEvents(events, EXPERT_TEAM_EVENT_PAGE_SIZE).map { it.sequence }
        )
    }

    @Test
    fun capsVisibleHistoryAtTheRetainedWindow() {
        val events = (1L..150L).map { sequence ->
            ExpertTeamCollaborationEvent(
                taskId = "task",
                projectId = "project",
                teamId = "team",
                sequence = sequence,
                type = "member_message",
                createdAt = sequence
            )
        }

        val visible = visibleExpertTeamEvents(events, 500)

        assertEquals(EXPERT_TEAM_EVENT_MAX_VISIBLE, visible.size)
        assertEquals(150L, visible.first().sequence)
        assertEquals(51L, visible.last().sequence)
    }
}
