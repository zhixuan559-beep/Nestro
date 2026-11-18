package app.nestro.model

import app.nestro.data.ChatMessage
import app.nestro.data.ModelConfig
import app.nestro.data.OpenAiClient
import java.util.UUID

/** Application boundary for non-UI chat transport and request cancellation. */
class ChatExecutionService {
    internal fun completeWithFallback(
        configs: List<ModelConfig>,
        history: List<ChatMessage>,
        systemPrompt: String,
        connectTimeoutMillis: Int = 20_000,
        readTimeoutMillis: Int = 90_000,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onUsage: (ModelConfig, Int, Int) -> Unit = { _, _, _ -> },
        onProviderUsage: (ModelConfig, Int, Int) -> Unit = { _, _, _ -> }
    ): String = OpenAiClient.completeWithFallback(
        configs, history, systemPrompt, connectTimeoutMillis, readTimeoutMillis,
        temperature, maxTokens, onUsage, onProviderUsage
    )

    internal suspend fun streamWithFallback(
        configs: List<ModelConfig>,
        history: List<ChatMessage>,
        systemPrompt: String,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onUsage: (ModelConfig, Int, Int) -> Unit = { _, _, _ -> },
        onProviderUsage: (ModelConfig, Int, Int) -> Unit = { _, _, _ -> },
        requestId: String = UUID.randomUUID().toString(),
        shouldStop: () -> Boolean = { false },
        onChunk: suspend (String) -> Unit
    ): String = OpenAiClient.streamWithFallback(
        configs, history, systemPrompt, temperature, maxTokens, onUsage, onProviderUsage, requestId,
        shouldStop = shouldStop,
        onChunk = onChunk
    )

    internal fun cancel(requestId: String? = null) = OpenAiClient.cancel(requestId)
}
