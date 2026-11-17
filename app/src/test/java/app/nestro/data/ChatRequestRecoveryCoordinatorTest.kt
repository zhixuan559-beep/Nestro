package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRequestRecoveryCoordinatorTest {
    @Test
    fun malformedRequestMarkerIsIgnored() {
        assertNull(parsePendingChatRequest("{not-json"))
    }

    @Test
    fun nonRunningRequestMarkerIsIgnored() {
        assertNull(parsePendingChatRequest("""{"status":"completed","request_id":"r","conversation_id":"c"}"""))
    }

    @Test
    fun runningRequestRestoresOnlyItsOwnConversationPrompt() {
        val pending = parsePendingChatRequest(
            """{"status":"running","request_id":"r1","conversation_id":"c1","project_id":"p1","prompt":"继续写","partial_answer":"已完成一半"}"""
        )

        assertEquals("r1", pending?.requestId)
        assertEquals("p1", pending?.projectId)
        assertEquals("已完成一半", pending?.partialAnswer)
        assertEquals("继续写", pending?.restoredPromptFor("c1"))
        assertNull(pending?.restoredPromptFor("c2"))
    }

    @Test
    fun runningRequestWithoutStableIdentifiersIsIgnored() {
        assertNull(parsePendingChatRequest("""{"status":"running","request_id":"","conversation_id":"c"}"""))
        assertNull(parsePendingChatRequest("""{"status":"running","request_id":"r","conversation_id":""}"""))
    }
}
