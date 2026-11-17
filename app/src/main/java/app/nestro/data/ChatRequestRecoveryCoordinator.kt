package app.nestro.data

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Recovers a chat request interrupted by process recreation.
 *
 * This boundary owns the persisted request marker and conversation snapshot
 * update; Compose only applies the returned state to the visible screen.
 */
internal class ChatRequestRecoveryCoordinator(
    private val persistence: ConversationPersistenceService
) {
    internal data class Result(
        val conversations: List<ConversationRecord>,
        val activeMessages: List<ChatMessage>?,
        val restoredPrompt: String?,
        val interrupted: Boolean
    )

    suspend fun recover(
        preferences: SharedPreferences,
        conversations: List<ConversationRecord>,
        databaseReady: Boolean,
        activeConversationId: String
    ): Result {
        val pending = runCatching {
            JSONObject(preferences.getString("chat_request_state_v1", "{}") ?: "{}")
        }.getOrNull()
        if (pending?.optString("status") != "running") {
            preferences.edit().remove("chat_request_state_v1").apply()
            return Result(conversations, null, null, false)
        }

        val requestId = pending.optString("request_id")
        val conversationId = pending.optString("conversation_id")
        val projectId = pending.optString("project_id", "default")
        val restoredPrompt = pending.optString("prompt")
        val partial = pending.optString("partial_answer")
        var updatedConversations = conversations
        var activeMessages: List<ChatMessage>? = null

        val record = conversations.firstOrNull { it.id == conversationId }
        if (record != null) {
            val existingMessages = if (databaseReady) {
                persistence.loadMessages(conversationId).orEmpty()
            } else {
                record.messages
            }
            if (existingMessages.none { it.id == requestId }) {
                val content = if (partial.isBlank()) {
                    "请求在应用重建时中断，原始输入已恢复，可重新发送。"
                } else {
                    partial + "\n\n[请求中断，已保留已接收内容]"
                }
                val recovered = existingMessages + ChatMessage(requestId, "assistant", content)
                updatedConversations = ConversationPreferencesStore.saveConversationMessages(
                    preferences, conversationId, recovered, projectId, conversations
                )
                if (activeConversationId == conversationId) activeMessages = recovered
            }
        }

        val prompt = restoredPrompt.takeIf {
            it.isNotBlank() && activeConversationId == conversationId
        }
        if (prompt != null) {
            preferences.edit().putString("draft_$conversationId", prompt).apply()
        }
        preferences.edit().remove("chat_request_state_v1").apply()
        return Result(updatedConversations, activeMessages, prompt, true)
    }
}
