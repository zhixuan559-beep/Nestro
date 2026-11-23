package app.nestro.data

import kotlinx.coroutines.CancellationException

data class ChatTimeoutPolicy(
    val connectMs: Long = 20_000,
    val readMs: Long = 90_000,
    val callMs: Long = 180_000
) {
    init {
        require(connectMs in 1_000L..120_000L)
        require(readMs in 1_000L..300_000L)
        require(callMs >= readMs)
    }
}

data class ChatRetryPlan(val retryNumber: Int, val maxRetries: Int, val delayMs: Long)

/** Network policy shared by chat completion and streaming requests. */
object ChatNetworkPolicy {
    const val MAX_RETRIES = 2
    const val BASE_RETRY_DELAY_MS = 800L

    fun timeoutPolicy() = ChatTimeoutPolicy()

    fun retryPlan(error: Throwable, attempt: Int): ChatRetryPlan? {
        if (attempt !in 0 until MAX_RETRIES || error is CancellationException) return null
        if (!ChatRequestPolicy.shouldRetry(error)) return null
        return ChatRetryPlan(attempt + 1, MAX_RETRIES, BASE_RETRY_DELAY_MS * (1L shl attempt))
    }

    fun classify(error: Throwable): String {
        if (error is CancellationException) return "cancelled"
        val message = error.message.orEmpty()
        return when {
            Regex("HTTP\\s+401|HTTP\\s+403", RegexOption.IGNORE_CASE).containsMatchIn(message) -> "auth"
            Regex("HTTP\\s+429", RegexOption.IGNORE_CASE).containsMatchIn(message) -> "rate_limited"
            Regex("HTTP\\s+4\\d{2}", RegexOption.IGNORE_CASE).containsMatchIn(message) -> "request"
            Regex("HTTP\\s+5\\d{2}", RegexOption.IGNORE_CASE).containsMatchIn(message) -> "server"
            ChatRequestPolicy.shouldRetry(error) -> "transport"
            else -> "unknown"
        }
    }
}
