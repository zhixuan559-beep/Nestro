package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertTeamCollaborationEventTest {
    @Test
    fun codecRoundTripsOrderedProjectScopedEvents() {
        val events = listOf(
            ExpertTeamCollaborationEvent(taskId = "task-1", projectId = "project-a", teamId = "team-1",
                sequence = 1, type = "team_started", message = "开始执行"),
            ExpertTeamCollaborationEvent(taskId = "task-1", projectId = "project-a", teamId = "team-1",
                sequence = 2, type = "member_handoff", memberName = "运营师",
                relatedMemberName = "剪辑师", status = "requested", message = "剪辑 60 秒视频")
        )
        assertEquals(events, ExpertTeamCollaborationEventCodec.decode(ExpertTeamCollaborationEventCodec.encode(events)))
    }

    @Test
    fun codecDropsInvalidEventsAndBoundsHistory() {
        val raw = """[{"id":"bad","taskId":"task-1","projectId":"project-a","teamId":"team-1","sequence":0,"type":"arbitrary_untrusted_type","createdAt":1}]"""
        assertTrue(ExpertTeamCollaborationEventCodec.decode(raw).isEmpty())
        val many = List(520) { index ->
            ExpertTeamCollaborationEvent(taskId = "task-1", projectId = "project-a", teamId = "team-1",
                sequence = index.toLong(), type = "member_message", message = "message-$index")
        }
        assertEquals(300, ExpertTeamCollaborationEventCodec.decode(ExpertTeamCollaborationEventCodec.encode(many)).size)
    }
}
