package app.nestro.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ConversationStartupCoordinatorTest {
    @Test
    fun cancellableResultReturnsOrdinaryFailures() = runBlocking {
        val result = runCatchingCancellable<String> { error("storage unavailable") }

        assertFalse(result.isSuccess)
        assertEquals("storage unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun cancellableResultRethrowsCoroutineCancellation() = runBlocking {
        val cancelled = CancellationException("startup cancelled")
        try {
            runCatchingCancellable<String> { throw cancelled }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    @Test
    fun startupSelectionKeepsSavedConversationWithinActiveProject() {
        val projects = listOf(
            ProjectRecord("default", "Default", "", 1L),
            ProjectRecord("work", "Work", "", 2L)
        )
        val conversations = listOf(
            ConversationRecord("default-chat", "Default chat", 1L, emptyList(), "default"),
            ConversationRecord("work-chat", "Work chat", 2L, emptyList(), "work")
        )

        val selected = resolveActiveConversationSelection(projects, conversations, "work", "work-chat")

        assertEquals("work", selected.projectId)
        assertEquals("work-chat", selected.conversationId)
    }

    @Test
    fun startupSelectionFallsBackFromArchivedProjectAndForeignConversation() {
        val projects = listOf(
            ProjectRecord("default", "Default", "", 1L),
            ProjectRecord("archived", "Archived", "", 2L, archivedAt = 3L)
        )
        val conversations = listOf(
            ConversationRecord("default-chat", "Default chat", 1L, emptyList(), "default"),
            ConversationRecord("archived-chat", "Archived chat", 2L, emptyList(), "archived")
        )

        val selected = resolveActiveConversationSelection(projects, conversations, "archived", "archived-chat")

        assertEquals("default", selected.projectId)
        assertEquals("default-chat", selected.conversationId)
    }

    @Test
    fun startupSelectionUsesFirstConversationWhenSavedConversationIsMissing() {
        val projects = listOf(ProjectRecord("work", "Work", "", 1L))
        val conversations = listOf(
            ConversationRecord("first", "First", 1L, emptyList(), "work"),
            ConversationRecord("second", "Second", 2L, emptyList(), "work")
        )

        val selected = resolveActiveConversationSelection(projects, conversations, "work", "missing")

        assertEquals("first", selected.conversationId)
    }

}
