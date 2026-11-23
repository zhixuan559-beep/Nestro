package app.nestro.data

import android.content.SharedPreferences

internal data class ActiveConversationSelection(
    val projectId: String,
    val conversationId: String
)

internal fun resolveActiveConversationSelection(
    projects: List<ProjectRecord>,
    conversations: List<ConversationRecord>,
    savedProjectId: String?,
    savedConversationId: String?
): ActiveConversationSelection {
    val activeProjectId = savedProjectId?.takeIf { id ->
        projects.any { it.id == id && it.archivedAt == 0L }
    } ?: "default"
    val activeConversationId = conversations.firstOrNull {
        it.id == savedConversationId && it.projectId == activeProjectId
    }?.id ?: conversations.firstOrNull { it.projectId == activeProjectId }?.id.orEmpty()
    return ActiveConversationSelection(activeProjectId, activeConversationId)
}

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
        val statusMessage: String?,
        val projectStorageReadOnly: Boolean
    )

    suspend fun restore(preferences: SharedPreferences): StartupState {
        val restored = runCatchingCancellable { persistence.restore(preferences) }
        val databaseReady = restored.isSuccess
        var conversations: List<ConversationRecord>
        val projects: List<ProjectRecord>
        val statusMessage: String?
        var projectStorageReadOnly = false

        if (restored.isSuccess) {
            val data = restored.getOrThrow()
            conversations = data.conversations
            projects = data.projects
            preferences.edit().putBoolean("room_storage_initialized", true).apply()
            statusMessage = null
        } else {
            conversations = ConversationPreferencesStore.loadConversations(preferences)
            val loadedProjects = runCatchingCancellable {
                ProjectPreferencesStore.loadProjects(preferences)
            }
            if (loadedProjects.isSuccess) {
                projects = loadedProjects.getOrThrow()
                statusMessage = "本地数据库暂不可用，已回退到本机旧存储"
            } else {
                projects = listOf(ProjectRecord("default", "默认空间", "", System.currentTimeMillis()))
                projectStorageReadOnly = true
                statusMessage = "项目空间旧存储校验失败，原始数据未覆盖；项目编辑已锁定，请先导出备份并修复数据"
            }
        }

        val selection = resolveActiveConversationSelection(
            projects = projects,
            conversations = conversations,
            savedProjectId = preferences.getString("active_project_id", "default"),
            savedConversationId = preferences.getString("active_conversation_id", "")
        )
        val activeProjectId = selection.projectId
        val activeConversationId = selection.conversationId

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
            statusMessage = statusMessage,
            projectStorageReadOnly = projectStorageReadOnly
        )
    }
}
