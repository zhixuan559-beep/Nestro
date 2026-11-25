package app.nestro.model

import app.nestro.data.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamingCoordinatorTest {
    @Test
    fun persistsEachPartialAndReturnsFinalResponse() = runBlocking {
        val persisted = mutableListOf<String>()
        val persistedStarts = mutableListOf<Long>()
        val visible = mutableListOf<String>()
        // 分块需超过持久化/界面批处理阈值（256 字符/16 字符），否则小分块会被
        // 刻意合并（见 batchesTinyProviderDeltasAndPersistsOnlyAtBoundaries），
        // 本用例验证的是“每个达到阈值的 partial 都被持久化并回显”。
        val firstChunk = "第一段" + "一".repeat(300)
        val secondChunk = "第二段" + "二".repeat(300)
        val coordinator = ChatStreamingCoordinator(
            transport = ChatStreamingTransport { _, _, _, _, _, _, _, _, _, onChunk ->
                onChunk(firstChunk)
                onChunk(secondChunk)
                "完整回复"
            },
            partialStateStore = ChatPartialStateStore { _, _, _, _, partial, startedAt -> persisted += partial; persistedStarts += startedAt }
        )
        val result = coordinator.execute(
            configs = emptyList(),
            history = listOf(ChatMessage("m1", "user", "问题")),
            systemPrompt = "系统上下文",
            temperature = null,
            maxTokens = null,
            onUsage = { _, _, _ -> },
            requestId = "request-1",
            conversationId = "conversation-1",
            projectId = "project-1",
            prompt = "问题",
            startedAt = 123L,
            shouldStop = { false },
            onPartialAnswer = { visible += it }
        )
        assertEquals(listOf(firstChunk, firstChunk + secondChunk), persisted)
        assertEquals(persisted, visible)
        assertEquals(listOf(123L, 123L), persistedStarts)
        assertEquals(firstChunk + secondChunk, result.partialAnswer)
        assertEquals("完整回复", result.response)
        assertEquals(null, result.failure)
    }

    @Test
    fun preservesPartialAnswerWhenTransportFails() = runBlocking {
        val coordinator = ChatStreamingCoordinator(
            transport = ChatStreamingTransport { _, _, _, _, _, _, _, _, _, onChunk ->
                onChunk("已生成部分")
                error("网络断开")
            },
            partialStateStore = ChatPartialStateStore { _, _, _, _, _, _ -> }
        )
        val result = coordinator.execute(
            configs = emptyList(), history = emptyList(), systemPrompt = "",
            temperature = null, maxTokens = null, onUsage = { _, _, _ -> },
            requestId = "request-2", conversationId = "conversation-2",
            projectId = "project-2", prompt = "问题", startedAt = 456L, shouldStop = { false },
            onPartialAnswer = { }
        )
        assertEquals("已生成部分", result.partialAnswer)
        assertEquals("网络断开", result.failure?.message)
    }

    @Test
    fun propagatesCallerCancellationInsteadOfReturningFailure() = runBlocking {
        val coordinator = ChatStreamingCoordinator(
            transport = ChatStreamingTransport { _, _, _, _, _, _, _, _, _, _ ->
                throw CancellationException("caller cancelled")
            },
            partialStateStore = ChatPartialStateStore { _, _, _, _, _, _ -> }
        )
        val result = runCatching {
            coordinator.execute(
                configs = emptyList(), history = emptyList(), systemPrompt = "",
                temperature = null, maxTokens = null, onUsage = { _, _, _ -> },
                requestId = "request-3", conversationId = "conversation-3",
                projectId = "project-3", prompt = "问题", startedAt = 789L, shouldStop = { false },
                onPartialAnswer = { }
            )
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
    @Test
    fun partialStateEncodingPreservesRequestMetadataAndBoundsText() {
        val raw = encodeChatRequestState("r1", "c1", "p1", "原始问题", "x".repeat(25_000), 987L)
        val state = JSONObject(raw)
        assertEquals("running", state.getString("status"))
        assertEquals("r1", state.getString("request_id"))
        assertEquals("c1", state.getString("conversation_id"))
        assertEquals("p1", state.getString("project_id"))
        assertEquals("原始问题", state.getString("prompt"))
        assertEquals(24_000, state.getString("partial_answer").length)
        assertEquals(987L, state.getLong("started_at"))
    }

}
