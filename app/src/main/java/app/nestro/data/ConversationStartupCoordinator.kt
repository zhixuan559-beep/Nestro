package app.nestro.data

import android.content.SharedPreferences

/**
 * Restores the conversation/project snapshot and resolves the active chat
 * before Compose applies the result to screen state.
 */
internal class ConversationStartupCoordinator(
    private val persistence: ConversationPersistenceService
) {
    internal data class StartupState(
        val conversations: List<ConversationRecord>,
        val projects: List<ProjectRecord>,
        val activeProjectId: String,
        val activeConversationId: String,
        val messages: List<ChatMessage>,
        val persistedConversationVersions: Map<String, Int>,
        val databaseReady: Boolean,
        val statusMessage: String?
    )

    suspend fun restore(preferences: SharedPreferences): StartupState {
        val restored = runCatching { persistence.restore(preferences) }
        val databaseReady = restored.isSuccess
        var conversations: List<ConversationRecord>
        val projects: List<ProjectRecord>
        val statusMessage: String?

        if (restored.isSuccess) {
            val data = restored.getOrThrow()
            conversations = data.conversations
            projects = data.projects
            preferences.edit().putBoolean("room_storage_initialized", true).apply()
            statusMessage = null
        } else {
            conversations = ConversationPreferencesStore.loadConversations(preferences)
            projects = ProjectPreferencesStore.loadProjects(preferences)
            statusMessage = "本地数据库暂不可用，已回退到本机旧存储"
        }

        val savedProjectId = preferences.getString("active_project_id", "default") ?: "default"
        val activeProjectId = savedProjectId.takeIf { id ->
            projects.any { it.id == id && it.archivedAt == 0L }
        } ?: "default"
        val savedConversationId = preferences.getString("active_conversation_id", "") ?: ""
        val activeConversationId = conversations.firstOrNull {
            it.id == savedConversationId && it.projectId == activeProjectId
        }?.id ?: conversations.firstOrNull { it.projectId == activeProjectId }?.id.orEmpty()

        val activeMessages = if (databaseReady && activeConversationId.isNotBlank()) {
            persistence.loadMessages(activeConversationId).orEmpty()
        } else {
            conversations.firstOrNull { it.id == activeConversationId }?.messages.orEmpty()
        }
        if (activeConversationId.isNotBlank()) {
            conversations = conversations.map { record ->
                if (record.id == activeConversationId) {
                    record.copy(messages = activeMessages, messagesLoaded = true)
                } else record
            }
        }
        preferences.edit()
            .putString("active_project_id", activeProjectId)
            .putString("active_conversation_id", activeConversationId)
            .apply()

        return StartupState(
            conversations = conversations,
            projects = projects,
            activeProjectId = activeProjectId,
            activeConversationId = activeConversationId,
            messages = activeMessages,
            persistedConversationVersions = if (databaseReady) {
                conversations.associate { it.id to it.hashCode() }
            } else emptyMap(),
            databaseReady = databaseReady,
            statusMessage = statusMessage
        )
    }
}
