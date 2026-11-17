package app.nestro.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestPolicyTest {
    @Test
    fun trimsHistoryToCharacterAndMessageCountBudgets() {
        val history = (1..60).map {
            ChatHistoryItem(if (it % 2 == 0) "assistant" else "user", "x".repeat(2_000))
        }
        val bounded = ChatRequestPolicy.boundedHistory(history, "system", 16_384, 2_048)
        assertTrue(bounded.size <= 40)
        assertTrue(bounded.sumOf { it.content.length } <= ChatRequestPolicy.MAX_HISTORY_CHARS)
        assertEquals(history.last().role, bounded.last().role)
        assertEquals(history.last().content, bounded.last().content)
    }

    @Test
    fun keepsAndTruncatesLatestOversizedMessage() {
        val bounded = ChatRequestPolicy.boundedHistory(
            listOf(ChatHistoryItem("user", "x".repeat(100_000))), "", 4_096, 512
        )
        assertEquals(1, bounded.size)
        assertTrue(bounded.single().content.length < 100_000)
        assertTrue(bounded.single().content.length <= (4_096 - 512) * 4)
        assertTrue(bounded.single().content.endsWith("x"))
    }

    @Test
    fun budgetsChineseHistoryConservatively() {
        assertEquals(100, ChatRequestPolicy.estimateTokens("中".repeat(100)))
        val bounded = ChatRequestPolicy.boundedHistory(
            listOf(ChatHistoryItem("user", "中".repeat(1_000))), "", 100, 50
        )
        assertEquals(1, bounded.size)
        assertTrue(bounded.single().content.length <= 50)
    }

    @Test
    fun truncationNeverSplitsSupplementaryUnicodeCharacters() {
        val bounded = ChatRequestPolicy.boundedHistory(
            listOf(ChatHistoryItem("user", "😀".repeat(20))), "", 5, 0
        )
        assertEquals("😀".repeat(5), bounded.single().content)
    }

    @Test
    fun doesNotCreateHistoryBudgetWhenSystemPromptConsumesContext() {
        val bounded = ChatRequestPolicy.boundedHistory(
            listOf(ChatHistoryItem("user", "must not be sent")), "中".repeat(1_000), 1_000, 256
        )
        assertTrue(bounded.isEmpty())
    }

    @Test
    fun respectsSmallConfiguredContextWindowsInsteadOfInflatingThem() {
        val bounded = ChatRequestPolicy.boundedHistory(
            listOf(ChatHistoryItem("user", "x".repeat(1_000))), "", 100, 50
        )
        assertEquals(1, bounded.size)
        assertTrue(bounded.single().content.length <= 200)
    }

    @Test
    fun handlesEmptyHistoryAndWhitespaceTokenEstimates() {
        assertTrue(ChatRequestPolicy.boundedHistory(emptyList(), "").isEmpty())
        assertEquals(0, ChatRequestPolicy.estimateTokens("  \n "))
        assertEquals(25, ChatRequestPolicy.estimateTokens("x".repeat(100)))
    }

    @Test
    fun retriesOnlyTransientErrors() {
        assertTrue(ChatRequestPolicy.shouldRetry(IOException("connection reset")))
        assertTrue(ChatRequestPolicy.shouldRetry(IllegalStateException("HTTP 429")))
        assertTrue(ChatRequestPolicy.shouldRetry(IllegalStateException("HTTP 503")))
        assertTrue(ChatRequestPolicy.shouldRetry(IllegalStateException("request timed out")))
        assertFalse(ChatRequestPolicy.shouldRetry(IllegalStateException("HTTP 401")))
        assertFalse(ChatRequestPolicy.shouldRetry(IllegalStateException("invalid request")))
    }

    @Test
    fun doesNotRetryCancellationWrappedByTransportFailure() {
        assertFalse(ChatRequestPolicy.shouldRetry(
            IOException("connection closed", CancellationException("user stopped"))
        ))
    }

    @Test
    fun recognizesTransientFailureInNestedCause() {
        assertTrue(ChatRequestPolicy.shouldRetry(
            IllegalStateException("request failed", IOException("socket timeout"))
        ))
    }
}
