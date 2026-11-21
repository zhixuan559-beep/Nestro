package app.nestro.model

import android.content.SharedPreferences
import app.nestro.data.ChatMessage
import app.nestro.data.ModelConfig
import app.nestro.data.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun interface ChatStreamingTransport {
    suspend fun stream(
        configs: List<ModelConfig>,
        history: List<ChatMessage>,
        systemPrompt: String,
        temperature: Double?,
        maxTokens: Int?,
        onUsage: (ModelConfig, Int, Int) -> Unit,
        onProviderUsage: (ModelConfig, Int, Int) -> Unit,
        requestId: String,
        shouldStop: () -> Boolean,
        onChunk: suspend (String) -> Unit
    ): String
}

internal fun interface ChatPartialStateStore {
    fun persist(requestId: String, conversationId: String, projectId: String, prompt: String, partialAnswer: String, startedAt: Long)
}

internal class SharedPreferencesChatPartialStateStore(
    private val preferences: SharedPreferences
) : ChatPartialStateStore {
    override fun persist(requestId: String, conversationId: String, projectId: String, prompt: String, partialAnswer: String, startedAt: Long) {
        preferences.edit().putString(
            "chat_request_state_v1",
            encodeChatRequestState(requestId, conversationId, projectId, prompt, partialAnswer, startedAt)
        ).apply()
    }
}

internal fun encodeChatRequestState(
    requestId: String,
    conversationId: String,
    projectId: String,
    prompt: String,
    partialAnswer: String,
    startedAt: Long
): String = JSONObject().put("status", "running")
    .put("request_id", requestId)
    .put("conversation_id", conversationId)
    .put("project_id", projectId)
    .put("prompt", prompt)
    .put("partial_answer", partialAnswer.take(24_000))
    .put("started_at", startedAt)
    .toString()
internal data class ChatStreamExecutionResult(
    val response: String?,
    val partialAnswer: String,
    val failure: Throwable?
)

/** Streams model output while persisting crash-recoverable partial answers. */
internal class ChatStreamingCoordinator(
    private val transport: ChatStreamingTransport,
    private val partialStateStore: ChatPartialStateStore
) {
    private companion object {
        const val UI_UPDATE_EVERY_CHARS = 16
        const val PERSIST_EVERY_CHARS = 256
        const val PERSIST_EVERY_MILLIS = 1_500L
    }
    suspend fun execute(
        configs: List<ModelConfig>,
        history: List<ChatMessage>,
        systemPrompt: String,
        temperature: Double?,
        maxTokens: Int?,
        onUsage: (ModelConfig, Int, Int) -> Unit,
        onProviderUsage: (ModelConfig, Int, Int) -> Unit = { _, _, _ -> },
        requestId: String,
        conversationId: String,
        projectId: String,
        prompt: String,
        startedAt: Long,
        shouldStop: () -> Boolean,
        onPartialAnswer: suspend (String) -> Unit
    ): ChatStreamExecutionResult {
        val partial = StringBuilder()
        val uiPending = StringBuilder()
        var lastPersistedLength = 0
        var lastPersistAt = System.currentTimeMillis()
        val result = try {
            runCatchingCancellable {
                transport.stream(configs, history, systemPrompt, temperature, maxTokens, onUsage, onProviderUsage, requestId, shouldStop) { chunk ->
                    partial.append(chunk)
                    uiPending.append(chunk)
                    val now = System.currentTimeMillis()
                    if (partial.length - lastPersistedLength >= PERSIST_EVERY_CHARS || now - lastPersistAt >= PERSIST_EVERY_MILLIS) {
                        partialStateStore.persist(requestId, conversationId, projectId, prompt, partial.toString(), startedAt)
                        lastPersistedLength = partial.length
                        lastPersistAt = now
                    }
                    // Batch tiny provider deltas to avoid a Compose recomposition per token.
                    if (uiPending.length >= UI_UPDATE_EVERY_CHARS || uiPending.indexOf("\n") >= 0) {
                        uiPending.setLength(0)
                        onPartialAnswer(partial.toString())
                    }
                }
            }
        } finally {
            // Keep the latest recoverable answer even when the caller cancels the stream.
            withContext(NonCancellable) {
                if (partial.length > lastPersistedLength) {
                    runCatching {
                        partialStateStore.persist(requestId, conversationId, projectId, prompt, partial.toString(), startedAt)
                    }
                }
            }
        }
        if (uiPending.isNotEmpty()) onPartialAnswer(partial.toString())
        return ChatStreamExecutionResult(
            response = result.getOrNull(),
            partialAnswer = partial.toString(),
            failure = result.exceptionOrNull()
        )
    }
}
