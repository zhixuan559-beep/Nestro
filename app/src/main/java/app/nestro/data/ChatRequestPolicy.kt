package app.nestro.data

import java.io.IOException
import kotlinx.coroutines.CancellationException

internal data class ChatHistoryItem(
    val role: String,
    val content: String,
    val mediaParts: List<ChatMessageMediaPart> = emptyList()
)

internal object ChatRequestPolicy {
    const val MAX_HISTORY_CHARS = 48_000
    private val transientHttpStatus = Regex("""HTTP\s+(408|425|429|5\d\d)""", RegexOption.IGNORE_CASE)

    /**
     * A deliberately conservative estimate: ASCII characters average four per token,
     * while each non-ASCII code point reserves one token. This avoids under-budgeting
     * Chinese/Japanese/Korean conversations with an English-only character ratio.
     */
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return ((tokenUnits(text) + 3L) / 4L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(1)
    }

    fun boundedHistory(
        history: List<ChatHistoryItem>,
        systemPrompt: String,
        contextWindowTokens: Int = 16_384,
        outputReserveTokens: Int = 2_048
    ): List<ChatHistoryItem> {
        if (history.isEmpty()) return emptyList()

        val contextBudget = contextWindowTokens.coerceAtLeast(1)
        val outputReserve = outputReserveTokens.coerceIn(0, contextBudget)
        val historyTokenBudget = (
            contextBudget - estimateTokens(systemPrompt) - outputReserve
        ).coerceAtLeast(0)
        if (historyTokenBudget == 0) return emptyList()

        var remainingTokenUnits = historyTokenBudget.toLong() * 4L
        var remainingChars = MAX_HISTORY_CHARS
        val boundedNewestFirst = ArrayList<ChatHistoryItem>()

        for (message in history.asReversed().take(40)) {
            if (remainingTokenUnits <= 0L || (remainingChars <= 0 && message.mediaParts.isEmpty())) break
            var mediaTokenUnits = 0L
            val retainedMediaParts = message.mediaParts.filter { part ->
                val cost = part.estimatedTokens.coerceAtLeast(1).toLong() * 4L
                if (cost <= remainingTokenUnits - mediaTokenUnits) {
                    mediaTokenUnits += cost
                    true
                } else false
            }
            val content = takeLastWithinBudget(
                VoiceMessageMarker.strip(message.content),
                maxChars = remainingChars,
                maxTokenUnits = (remainingTokenUnits - mediaTokenUnits).coerceAtLeast(0L)
            )
            if (content.isEmpty() && retainedMediaParts.isEmpty()) continue

            boundedNewestFirst += message.copy(content = content, mediaParts = retainedMediaParts)
            remainingChars -= content.length
            remainingTokenUnits -= tokenUnits(content) + mediaTokenUnits
        }

        return boundedNewestFirst.asReversed()
    }

    internal fun boundedChatHistory(
        history: List<ChatMessage>,
        systemPrompt: String,
        contextWindowTokens: Int = 16_384,
        outputReserveTokens: Int = 2_048
    ): List<ChatMessage> = boundedHistory(
        history.map { ChatHistoryItem(it.role, it.content, it.mediaParts) },
        systemPrompt,
        contextWindowTokens,
        outputReserveTokens
    ).mapIndexed { index, item ->
        ChatMessage("request-context-$index", item.role, item.content, mediaParts = item.mediaParts)
    }

    private fun takeLastWithinBudget(
        text: String,
        maxChars: Int,
        maxTokenUnits: Long
    ): String {
        var index = text.length
        var usedChars = 0
        var usedUnits = 0L

        while (index > 0) {
            val codePoint = text.codePointBefore(index)
            val charCount = Character.charCount(codePoint)
            val cost = if (codePoint <= 0x7F) 1L else 4L
            if (usedChars + charCount > maxChars || usedUnits + cost > maxTokenUnits) break
            usedChars += charCount
            usedUnits += cost
            index -= charCount
        }

        return text.substring(index)
    }

    private fun tokenUnits(text: String): Long {
        var index = 0
        var units = 0L
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            units += if (codePoint <= 0x7F) 1L else 4L
            index += Character.charCount(codePoint)
        }
        return units
    }

    fun shouldRetry(error: Throwable): Boolean {
        var current: Throwable? = error
        var retryable = false

        // Cancellation must win even when a transport layer wraps it in an IOException.
        repeat(5) {
            val cause = current ?: return retryable
            if (cause is CancellationException) return false

            if (cause is IOException) retryable = true
            val message = cause.message.orEmpty()
            if (
                transientHttpStatus.containsMatchIn(message) ||
                message.contains("timeout", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) ||
                message.contains("connection reset", ignoreCase = true) ||
                message.contains("连接重置") ||
                message.contains("unexpected end", ignoreCase = true)
            ) {
                retryable = true
            }

            current = cause.cause
        }

        return retryable
    }
}


/** Model-specific context window selection shared by every request entry point. */
internal object ModelContextWindowPolicy {
    const val DEFAULT_CONTEXT_WINDOW_TOKENS = 16_384
    const val DEFAULT_OUTPUT_RESERVE_TOKENS = 2_048

    /** Use the smallest candidate window so failover never receives an oversized prompt. */
    fun effectiveContextWindow(configs: List<ModelConfig>): Int =
        configs.minOfOrNull { it.contextWindowTokens ?: DEFAULT_CONTEXT_WINDOW_TOKENS }
            ?: DEFAULT_CONTEXT_WINDOW_TOKENS

    fun outputReserve(maxTokens: Int?, contextWindowTokens: Int): Int =
        (maxTokens ?: DEFAULT_OUTPUT_RESERVE_TOKENS).coerceIn(0, contextWindowTokens.coerceAtLeast(1))
}
