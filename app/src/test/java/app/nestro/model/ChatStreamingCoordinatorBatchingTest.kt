package app.nestro.model

import app.nestro.data.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamingCoordinatorBatchingTest {
    @Test
    fun batchesTinyProviderDeltasAndPersistsOnlyAtBoundaries() = kotlinx.coroutines.runBlocking {
        var persistCalls = 0
        val partialUpdates = mutableListOf<String>()
        val coordinator = ChatStreamingCoordinator(
            transport = ChatStreamingTransport { _, _, _, _, _, _, _, _, _, onChunk ->
                repeat(32) { onChunk("x") }
                "x".repeat(32)
            },
            partialStateStore = ChatPartialStateStore { _, _, _, _, _, _ -> persistCalls++ }
        )
        val result = coordinator.execute(
            configs = listOf(ModelConfig("https://example.com", "test", "key")),
            history = emptyList(),
            systemPrompt = "",
            temperature = null,
            maxTokens = null,
            onUsage = { _, _, _ -> },
            requestId = "request",
            conversationId = "conversation",
            projectId = "project",
            prompt = "prompt",
            startedAt = 1L,
            shouldStop = { false },
            onPartialAnswer = { partialUpdates += it }
        )
        assertEquals("x".repeat(32), result.partialAnswer)
        assertEquals("x".repeat(32), result.response)
        assertTrue(partialUpdates.size <= 2)
        assertEquals(1, persistCalls)
    }
}
