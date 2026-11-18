package app.nestro.data

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Recovers a chat request interrupted by process recreation.
 *
 * This boundary owns the persisted request marker and conversation snapshot
 * update; Compose only applies the returned state to the visible screen.
 */
internal data class PendingChatRequest(
    val requestId: String,
    val conversationId: String,
    val projectId: String,
    val prompt: String,
    val partialAnswer: String
) {
    fun restoredPromptFor(activeConversationId: String): String? =
        prompt.takeIf { it.isNotBlank() && activeConversationId == conversationId }
}

internal fun parsePendingChatRequest(raw: String?): PendingChatRequest? {
    val pending = runCatching { JSONObject(raw ?: "{}") }.getOrNull() ?: return null
    if (pending.optString("status") != "running") return null
    val requestId = pending.optString("request_id")
    val conversationId = pending.optString("conversation_id")
    val projectId = pending.optString("project_id", "default")
    if (requestId.isBlank() || conversationId.isBlank() || projectId.isBlank()) return null
    return PendingChatRequest(
        requestId = requestId,
        conversationId = conversationId,
        projectId = projectId,
        prompt = pending.optString("prompt"),
        partialAnswer = pending.optString("partial_answer")
    )
}

internal fun PendingChatRequest.matches(record: ConversationRecord): Boolean =
    record.id == conversationId && record.projectId == projectId

internal fun recoverInterruptedAssistantMessage(
    messages: List<ChatMessage>,
    requestId: String,
    partialAnswer: String
): List<ChatMessage> {
    val recoveredContent = if (partialAnswer.isBlank()) {
        "请求在应用重建时中断，原始输入已恢复，可重新发送。"
    } else {
        partialAnswer + "\n\n[请求中断，已保留已接收内容]"
    }
    val existingIndex = messages.indexOfFirst { it.id == requestId && it.role == "assistant" }
    if (existingIndex < 0) {
        return messages + ChatMessage(requestId, "assistant", recoveredContent)
    }

    val existing = messages[existingIndex]
    if (
        existing.content == recoveredContent ||
        (partialAnswer.isNotBlank() && existing.content == partialAnswer) ||
        recoveredContent in existing.versions ||
        (partialAnswer.isNotBlank() && partialAnswer in existing.versions)
    ) {
        return messages
    }

    val versions = (existing.allVersions() + recoveredContent)
        .filter(String::isNotBlank)
        .distinct()
        .takeLast(20)
    val recovered = existing.copy(
        content = recoveredContent,
        versions = versions,
        activeVersion = versions.lastIndex
    )
    return messages.toMutableList().also { it[existingIndex] = recovered }
}

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
        val pending = parsePendingChatRequest(
            preferences.getString("chat_request_state_v1", "{}")
        )
        if (pending == null) {
            preferences.edit().remove("chat_request_state_v1").apply()
            return Result(conversations, null, null, false)
        }

        val requestId = pending.requestId
        val conversationId = pending.conversationId
        val projectId = pending.projectId
        val restoredPrompt = pending.prompt
        val partial = pending.partialAnswer
        var updatedConversations = conversations
        var activeMessages: List<ChatMessage>? = null

        val record = conversations.firstOrNull { it.id == conversationId }
            ?.takeIf(pending::matches)
        if (record != null) {
            val existingMessages = if (databaseReady) {
                persistence.loadMessages(conversationId).orEmpty()
            } else {
                record.messages
            }
            val recovered = recoverInterruptedAssistantMessage(existingMessages, requestId, partial)
            if (recovered != existingMessages) {
                updatedConversations = ConversationPreferencesStore.saveConversationMessages(
                    preferences, conversationId, recovered, projectId, conversations
                )
                if (databaseReady) persistence.persistRecoveredMessages(conversationId, recovered)
                if (activeConversationId == conversationId) activeMessages = recovered
            }
        }

        val prompt = record?.let { pending.restoredPromptFor(activeConversationId) }
        if (prompt != null) {
            preferences.edit().putString("draft_$conversationId", prompt).apply()
        }
        preferences.edit().remove("chat_request_state_v1").apply()
        return Result(updatedConversations, activeMessages, prompt, true)
    }
}
