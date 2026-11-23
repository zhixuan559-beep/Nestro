package app.nestro.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExecutionUiStateTest {
    @Test
    fun blocksDeletionOnlyForConversationOwnedByRunningRequest() {
        assertFalse(canDeleteConversation("conversation-1", "conversation-1", busy = true))
        assertTrue(canDeleteConversation("conversation-2", "conversation-1", busy = true))
        assertTrue(canDeleteConversation("conversation-1", "conversation-1", busy = false))
        assertTrue(canDeleteConversation("conversation-1", "", busy = true))
    }
}
