package app.nestro.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Strict message-version codec shared by Room persistence and restore paths. */
internal fun encodeMessageVersions(message: ChatMessage): String {
    require(message.versions.size <= 20) { "回答版本数量超过持久化上限" }
    require(message.versions.all { it.length <= 200_000 }) { "回答版本内容超过持久化上限" }
    require(message.activeVersion >= 0 && (message.versions.isEmpty() || message.activeVersion < message.versions.size)) {
        "回答版本索引无效"
    }
    return JSONArray(message.versions).toString()
}

internal fun decodeMessageVersions(raw: String, activeVersion: Int): List<String> {
    val values = JSONArray(raw)
    require(values.length() <= 20) { "回答版本数量超过持久化上限" }
    val versions = (0 until values.length()).map { index ->
        require(values.opt(index) is String) { "回答版本格式无效" }
        values.getString(index).also { require(it.length <= 200_000) { "回答版本内容超过持久化上限" } }
    }
    require(activeVersion >= 0 && (versions.isEmpty() || activeVersion < versions.size)) { "回答版本索引无效" }
    return versions
}


/** Owns Room restore/migration and snapshot persistence for conversations and projects. */
class ConversationPersistenceService(private val database: NestroDatabase, private val context: Context? = null) {
    internal data class RestoreResult(
        val conversations: List<ConversationRecord>,
        val projects: List<ProjectRecord>
    )

    internal suspend fun restore(prefs: SharedPreferences): RestoreResult = withContext(Dispatchers.IO) {
        val dao = database.dao()
        val pendingProjectDeleteId = prefs.getString("pending_project_delete_id", "").orEmpty()
        if (pendingProjectDeleteId.isNotBlank()) {
            val localRecords = ConversationPreferencesStore.loadConversations(prefs)
            val roomConversationIds = dao.projectConversationIds(pendingProjectDeleteId)
            val activeId = prefs.getString("active_project_id", "default") ?: "default"
            val pendingRequest = runCatching {
                JSONObject(prefs.getString("chat_request_state_v1", "{}") ?: "{}")
            }.getOrNull()
            val hasRunningRequest = pendingRequest?.optString("status") == "running" &&
                pendingRequest?.optString("project_id") == pendingProjectDeleteId
            val pendingConversationIds = (
                roomConversationIds + localRecords.filter { it.projectId == pendingProjectDeleteId }.map { it.id }
            ).toSet()
            val hasDraft = pendingConversationIds.any { id ->
                (prefs.getString("draft_$id", "") ?: "").isNotBlank()
            }
            val hasLocalConversationData = localRecords.any {
                it.projectId == pendingProjectDeleteId && (it.messages.isNotEmpty() || it.title != "新对话")
            }
            val hasLinkedData = dao.projectHasLinkedData(pendingProjectDeleteId)
            val hasExclusiveResources = runCatching {
                ProjectResourceScopeStore.resourcesOwnedOnlyByProject(
                    ProjectResourceScopeStore.load(prefs), pendingProjectDeleteId
                ).isNotEmpty()
            }.getOrDefault(true)
            val artifactsCleaned = context?.let { appContext ->
                runCatching { ArtifactStore.deleteProjectIfEmpty(appContext, pendingProjectDeleteId) }.getOrDefault(false)
            } ?: true
            val canRecover = !hasExclusiveResources && artifactsCleaned && ProjectSpaceRules.canRecoverPendingDeletion(
                projectId = pendingProjectDeleteId,
                activeProjectId = activeId,
                hasRunningRequest = hasRunningRequest,
                hasDraft = hasDraft,
                hasLocalConversationData = hasLocalConversationData,
                hasLinkedData = hasLinkedData
            )
            if (canRecover) {
                UserMemoryStore.invalidateAndAwaitPendingExtractions(pendingProjectDeleteId)
            }
            val deleted = canRecover &&
                dao.recoverPendingProjectDeletionIfEmpty(pendingProjectDeleteId, activeId)
            if (deleted) {
                ConversationPreferencesStore.saveConversationList(
                    prefs,
                    localRecords.filterNot { it.projectId == pendingProjectDeleteId },
                    markDirty = false
                )
                ProjectPreferencesStore.saveProjects(
                    prefs,
                    ProjectPreferencesStore.loadProjects(prefs).filterNot { it.id == pendingProjectDeleteId },
                    markDirty = false
                )
                ProjectResourceScopeStore.save(
                    prefs,
                    ProjectResourceScopeStore.removeProjectScope(
                        ProjectResourceScopeStore.load(prefs),
                        pendingProjectDeleteId
                    )
                )
                ProjectMemorySettingsStore.removeProject(prefs, pendingProjectDeleteId)
                ProjectAccessControlStore.removeProject(prefs, pendingProjectDeleteId)
                val editor = prefs.edit()
                (roomConversationIds + localRecords.filter { it.projectId == pendingProjectDeleteId }.map { it.id })
                    .distinct().forEach { editor.remove("draft_$it") }
                editor.putString(
                    "workflow_runs_v1",
                    WorkflowRunHistoryCodec.encode(
                        WorkflowRunHistoryCodec.decode(prefs.getString("workflow_runs_v1", "[]") ?: "[]")
                            .filterNot { it.projectId == pendingProjectDeleteId }
                    )
                )
                editor.putString(
                    "expert_team_runs_v1",
                    ExpertTeamRunHistoryCodec.encode(
                        ExpertTeamRunHistoryCodec.decode(prefs.getString("expert_team_runs_v1", "[]") ?: "[]")
                            .filterNot { it.projectId == pendingProjectDeleteId }
                    )
                )
                editor.remove("project_last_opened_$pendingProjectDeleteId")
                editor.remove("pending_project_delete_id").commit()
            } else {
                prefs.edit().remove("pending_project_delete_id").commit()
            }
        }

        val storedConversationCount = dao.conversationCount()
        val hasPendingLocalSnapshot = prefs.getLong("local_snapshot_saved_at", 0L) >
            prefs.getLong("room_synced_at", 0L)
        val restored = if (storedConversationCount == 0 || hasPendingLocalSnapshot) {
            val legacyConversations = ConversationPreferencesStore.loadConversations(prefs)
            val legacyProjects = ProjectPreferencesStore.loadProjects(prefs)
            val conversationIds = legacyConversations.map { it.id }
            val staleConversationIds = dao.conversationIds().filterNot { it in conversationIds }
            val staleConversationProjects = dao.conversationProjectIds(staleConversationIds).toSet()
            val projectIds = legacyProjects.map { it.id }
            val staleProjectIds = dao.projectIds().filterNot { it in projectIds }
            (staleConversationProjects + staleProjectIds).forEach {
                UserMemoryStore.invalidateAndAwaitPendingExtractions(it)
            }
            database.withTransaction {
                if (staleConversationIds.isNotEmpty()) dao.deleteConversations(staleConversationIds)
                legacyConversations.forEach { record ->
                    dao.putConversation(ConversationEntity(record.id, record.title, record.updatedAt, record.projectId))
                    dao.clearMessages(record.id)
                    val rows = record.messages.mapIndexed { index, message ->
                        MessageEntity(
                            message.id, record.id, index, message.role, message.content,
                            encodeMessageVersions(message)
                        )
                    }
                    if (rows.isNotEmpty()) dao.putMessages(rows)
                }
                dao.putProjects(legacyProjects.map {
                    ProjectEntity(it.id, it.name, it.instructions, it.createdAt, it.archivedAt)
                })
                staleProjectIds.forEach { dao.deleteProjectAndKnowledge(it) }
            }
            prefs.edit().putLong("room_synced_at", prefs.getLong("local_snapshot_saved_at", 0L)).apply()
            RestoreResult(
                dao.allConversationSummaries().map {
                    ConversationRecord(it.id, it.title, it.updatedAt, emptyList(), it.projectId, messagesLoaded = false)
                },
                legacyProjects
            )
        } else {
            val conversations = dao.allConversationSummaries().map {
                ConversationRecord(it.id, it.title, it.updatedAt, emptyList(), it.projectId, messagesLoaded = false)
            }
            val storedProjects = dao.loadProjects().map {
                ProjectRecord(it.id, it.name, it.instructions, it.createdAt, it.archivedAt)
            }
            val projects = if (storedProjects.isEmpty()) ProjectPreferencesStore.loadProjects(prefs) else storedProjects
            if (storedProjects.isEmpty()) dao.putProjects(projects.map {
                ProjectEntity(it.id, it.name, it.instructions, it.createdAt, it.archivedAt)
            })
            RestoreResult(conversations, projects)
        }
        restored
    }

    internal suspend fun loadConversationPage(
        projectId: String,
        query: String,
        limit: Int,
        offset: Int
    ): List<ConversationListItem> = withContext(Dispatchers.IO) {
        database.dao().conversationListPage(projectId, query, limit, offset)
    }

    internal suspend fun loadMessages(conversationId: String): List<ChatMessage>? = withContext(Dispatchers.IO) {
        val entity = database.dao().conversationWithMessages(conversationId) ?: return@withContext null
        entity.messages.sortedBy { it.position }.map { message ->
            ChatMessage(
                message.id, message.role, message.content,
                decodeMessageVersions(message.versionsJson, message.activeVersion), message.activeVersion
            )
        }
    }

    /**
     * Persist the completed conversation before automatic memory extraction references
     * its source message. This closes the race with the debounced snapshot effect.
     */
    internal suspend fun persistConversationMessages(
        conversationId: String,
        projectId: String,
        messages: List<ChatMessage>
    ) = withContext(Dispatchers.IO) {
        require(conversationId.isNotBlank() && conversationId.length <= 120) { "会话 ID 无效" }
        require(projectId.isNotBlank() && projectId.length <= 120) { "项目 ID 无效" }
        validateChatMessages(messages)
        val dao = database.dao()
        database.withTransaction {
            require(dao.projectExists(projectId)) { "会话所属项目不存在，拒绝持久化" }
            val current = dao.conversationWithMessages(conversationId)?.conversation
            require(current == null || current.projectId == projectId) {
                "会话项目归属已变化，拒绝跨项目覆盖"
            }
            val now = System.currentTimeMillis()
            val firstPrompt = messages.firstOrNull { it.role == "user" }?.content?.take(32)
                ?.ifBlank { "新对话" } ?: current?.title ?: "新对话"
            val title = if (current == null || current.title == "新对话" || current.title == "旧对话") {
                firstPrompt
            } else current.title
            dao.putConversation(
                ConversationEntity(conversationId, title, now, projectId)
            )
            dao.clearMessages(conversationId)
            val rows = messages.mapIndexed { index, message ->
                MessageEntity(
                    message.id,
                    conversationId,
                    index,
                    message.role,
                    message.content,
                    encodeMessageVersions(message)
                )
            }
            if (rows.isNotEmpty()) dao.putMessages(rows)
        }
    }

    /** Persist an interrupted chat recovery directly to Room before startup continues. */
    internal suspend fun persistRecoveredMessages(conversationId: String, messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        require(conversationId.isNotBlank() && conversationId.length <= 120) { "会话 ID 无效" }
        validateChatMessages(messages)
        val dao = database.dao()
        database.withTransaction {
            val current = dao.conversationWithMessages(conversationId)?.conversation
                ?: return@withTransaction
            require(dao.projectExists(current.projectId)) { "会话所属项目不存在，拒绝恢复消息" }
            val firstPrompt = messages.firstOrNull { it.role == "user" }?.content?.take(32)
                ?.ifBlank { "新对话" } ?: current.title
            val title = if (current.title == "新对话" || current.title == "旧对话") firstPrompt else current.title
            dao.putConversation(current.copy(title = title, updatedAt = System.currentTimeMillis()))
            dao.clearMessages(conversationId)
            val rows = messages.mapIndexed { index, message ->
                MessageEntity(
                    message.id, conversationId, index, message.role, message.content,
                    encodeMessageVersions(message)
                )
            }
            if (rows.isNotEmpty()) dao.putMessages(rows)
        }
    }

    internal suspend fun persist(
        prefs: SharedPreferences,
        conversations: List<ConversationRecord>,
        projects: List<ProjectRecord>,
        knownVersions: Map<String, Int>,
        snapshotTimestamp: Long
    ) = withContext(Dispatchers.IO) {
        validateConversationRecords(conversations)
        ProjectPreferencesStore.projectsToJson(projects)
        val projectIds = projects.mapTo(mutableSetOf()) { it.id }
        require(conversations.all { it.projectId in projectIds }) { "会话引用了不存在的项目空间" }
        val dao = database.dao()
        val currentIds = conversations.map { it.id }
        val removedIds = dao.conversationIds().filterNot { it in currentIds }
        val removedConversationProjects = dao.conversationProjectIds(removedIds).toSet()
        val currentProjectIds = projects.map { it.id }
        val removedProjectIds = dao.projectIds().filterNot { it in currentProjectIds }
        (removedConversationProjects + removedProjectIds).forEach {
            UserMemoryStore.invalidateAndAwaitPendingExtractions(it)
        }
        database.withTransaction {
            if (removedIds.isNotEmpty()) dao.deleteConversations(removedIds)
            conversations.filter { knownVersions[it.id] != it.hashCode() }.forEach { record ->
                dao.putConversation(ConversationEntity(record.id, record.title, record.updatedAt, record.projectId))
                if (record.messagesLoaded) {
                    dao.clearMessages(record.id)
                    val rows = record.messages.mapIndexed { index, message ->
                        MessageEntity(
                            message.id, record.id, index, message.role, message.content,
                            encodeMessageVersions(message)
                        )
                    }
                    if (rows.isNotEmpty()) dao.putMessages(rows)
                }
            }
            dao.putProjects(projects.map {
                ProjectEntity(it.id, it.name, it.instructions, it.createdAt, it.archivedAt)
            })
            removedProjectIds.forEach { dao.deleteProjectAndKnowledge(it) }
        }
        ConversationPreferencesStore.saveConversationList(prefs, conversations, markDirty = false)
        ProjectPreferencesStore.saveProjects(prefs, projects, markDirty = false)
        prefs.edit().putLong("room_synced_at", snapshotTimestamp).apply()
    }
}
