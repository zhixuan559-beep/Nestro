package app.nestro.model

import android.content.SharedPreferences
import app.nestro.data.ChatMessage
import app.nestro.data.ModelConfig
import app.nestro.data.runCatchingCancellable
import org.json.JSONObject

internal fun interface ChatStreamingTransport {
    suspend fun stream(
        configs: List<ModelConfig>,
        history: List<ChatMessage>,
        systemPrompt: String,
        temperature: Double?,
        maxTokens: Int?,
        onUsage: (ModelConfig, Int, Int) -> Unit,
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
    suspend fun execute(
        configs: List<ModelConfig>,
        history: List<ChatMessage>,
        systemPrompt: String,
        temperature: Double?,
        maxTokens: Int?,
        onUsage: (ModelConfig, Int, Int) -> Unit,
        requestId: String,
        conversationId: String,
        projectId: String,
        prompt: String,
        startedAt: Long,
        shouldStop: () -> Boolean,
        onPartialAnswer: suspend (String) -> Unit
    ): ChatStreamExecutionResult {
        val partial = StringBuilder()
        val result = runCatchingCancellable {
            transport.stream(configs, history, systemPrompt, temperature, maxTokens, onUsage, requestId, shouldStop) { chunk ->
                partial.append(chunk)
                val current = partial.toString()
                partialStateStore.persist(requestId, conversationId, projectId, prompt, current, startedAt)
                onPartialAnswer(current)
            }
        }
        return ChatStreamExecutionResult(
            response = result.getOrNull(),
            partialAnswer = partial.toString(),
            failure = result.exceptionOrNull()
        )
    }
}
