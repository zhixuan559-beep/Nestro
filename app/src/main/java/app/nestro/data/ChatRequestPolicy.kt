package app.nestro.data

import java.io.IOException
import kotlinx.coroutines.CancellationException

data class ChatHistoryItem(val role: String, val content: String)

object ChatRequestPolicy {
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
            if (remainingTokenUnits <= 0L || remainingChars <= 0) break
            val content = takeLastWithinBudget(
                message.content,
                maxChars = remainingChars,
                maxTokenUnits = remainingTokenUnits
            )
            if (content.isEmpty()) continue

            boundedNewestFirst += message.copy(content = content)
            remainingChars -= content.length
            remainingTokenUnits -= tokenUnits(content)
        }

        return boundedNewestFirst.asReversed()
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
