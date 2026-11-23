package app.nestro.data

internal fun ChatMessage.allVersions(): List<String> = when {
    versions.isEmpty() -> listOf(content)
    activeVersion >= versions.size -> (versions + content).distinct().takeLast(20)
    else -> versions.distinct().takeLast(20)
}

internal enum class ChatResponseStatus { STOPPED, SUCCESS, FAILURE }

internal data class ChatResponseFinalization(
    val finalText: String,
    val completedHistory: List<ChatMessage>,
    val status: ChatResponseStatus
)

/** Applies response/partial/stop precedence and preserves assistant revision history. */
internal object ChatResponseFinalizer {
    fun build(
        result: Result<String>,
        partialAnswer: String,
        stopRequested: Boolean,
        previousAssistant: ChatMessage?,
        requestHistory: List<ChatMessage>,
        assistantId: String
    ): ChatResponseFinalization {
        val finalText = when {
            result.isSuccess -> result.getOrThrow()
            partialAnswer.isNotEmpty() -> partialAnswer
            stopRequested -> "已停止生成。"
            result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException -> "请求失败：本次请求超时，请重试或缩短内容。"
            else -> "请求失败：${result.exceptionOrNull()?.message ?: "请检查网络、地址和 Key"}"
        }
        val priorVersions = previousAssistant?.allVersions().orEmpty()
        val nextVersions = (priorVersions + finalText).filter(String::isNotBlank).distinct().takeLast(20)
        val history = requestHistory + ChatMessage(assistantId, "assistant", finalText, nextVersions, nextVersions.lastIndex)
        val status = when {
            stopRequested -> ChatResponseStatus.STOPPED
            result.isSuccess -> ChatResponseStatus.SUCCESS
            else -> ChatResponseStatus.FAILURE
        }
        return ChatResponseFinalization(finalText, history, status)
    }
}
