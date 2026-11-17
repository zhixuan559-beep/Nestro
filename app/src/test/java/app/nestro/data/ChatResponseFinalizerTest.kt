package app.nestro.data

import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatResponseFinalizerTest {
    @Test
    fun successfulResponseWinsAndCreatesAssistantHistory() {
        val history = listOf(ChatMessage("user-1", "user", "问题"))
        val result = ChatResponseFinalizer.build(
            result = Result.success("模型回复"), partialAnswer = "部分回复", stopRequested = false,
            previousAssistant = null, requestHistory = history, assistantId = "assistant-1"
        )
        assertEquals("模型回复", result.finalText)
        assertEquals("模型回复", result.completedHistory.last().content)
        assertEquals(listOf("模型回复"), result.completedHistory.last().versions)
        assertEquals(ChatResponseStatus.SUCCESS, result.status)
    }

    @Test
    fun partialAnswerIsPreservedWhenRequestFails() {
        val result = ChatResponseFinalizer.build(
            result = Result.failure(IllegalStateException("network")), partialAnswer = "已生成部分",
            stopRequested = false, previousAssistant = null, requestHistory = emptyList(), assistantId = "a2"
        )
        assertEquals("已生成部分", result.finalText)
        assertEquals(ChatResponseStatus.FAILURE, result.status)
    }

    @Test
    fun stopWithoutPartialUsesStoppedMessageAndStatus() {
        val result = ChatResponseFinalizer.build(
            result = Result.failure(IllegalStateException("stopped")), partialAnswer = "",
            stopRequested = true, previousAssistant = null, requestHistory = emptyList(), assistantId = "a3"
        )
        assertEquals("已停止生成。", result.finalText)
        assertEquals(ChatResponseStatus.STOPPED, result.status)
    }

    @Test
    fun timeoutWithoutPartialUsesSpecificRetryMessage() {
        val timeout = TimeoutCancellationException("timeout")
        val result = ChatResponseFinalizer.build(
            result = Result.failure(timeout), partialAnswer = "", stopRequested = false,
            previousAssistant = null, requestHistory = emptyList(), assistantId = "a4"
        )
        assertEquals("请求失败：本次请求超时，请重试或缩短内容。", result.finalText)
    }
}
