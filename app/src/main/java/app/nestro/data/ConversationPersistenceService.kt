package app.nestro.data

import android.content.SharedPreferences
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Owns Room restore/migration and snapshot persistence for conversations and projects. */
class ConversationPersistenceService(private val database: NestroDatabase) {
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
            val canRecover = ProjectSpaceRules.canRecoverPendingDeletion(
                projectId = pendingProjectDeleteId,
                activeProjectId = activeId,
                hasRunningRequest = hasRunningRequest,
                hasDraft = hasDraft,
                hasLocalConversationData = hasLocalConversationData,
                hasLinkedData = hasLinkedData
            )
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
                val editor = prefs.edit()
                (roomConversationIds + localRecords.filter { it.projectId == pendingProjectDeleteId }.map { it.id })
                    .distinct().forEach { editor.remove("draft_$it") }
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
            database.withTransaction {
                val conversationIds = legacyConversations.map { it.id }
                val staleConversationIds = dao.conversationIds().filterNot { it in conversationIds }
                if (staleConversationIds.isNotEmpty()) dao.deleteConversations(staleConversationIds)
                legacyConversations.forEach { record ->
                    dao.putConversation(ConversationEntity(record.id, record.title, record.updatedAt, record.projectId))
                    dao.clearMessages(record.id)
                    val rows = record.messages.mapIndexed { index, message ->
                        MessageEntity(
                            message.id, record.id, index, message.role, message.content,
                            JSONArray(message.versions.take(20)).toString(), message.activeVersion
                        )
                    }
                    if (rows.isNotEmpty()) dao.putMessages(rows)
                }
                dao.putProjects(legacyProjects.map {
                    ProjectEntity(it.id, it.name, it.instructions, it.createdAt, it.archivedAt)
                })
                val projectIds = legacyProjects.map { it.id }
                val staleProjectIds = dao.projectIds().filterNot { it in projectIds }
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
                decodeVersions(message.versionsJson), message.activeVersion
            )
        }
    }

    private fun decodeVersions(raw: String): List<String> = runCatching {
        val values = JSONArray(raw)
        (0 until values.length()).map { values.optString(it) }
    }.getOrDefault(emptyList())

    internal suspend fun persist(
        prefs: SharedPreferences,
        conversations: List<ConversationRecord>,
        projects: List<ProjectRecord>,
        knownVersions: Map<String, Int>,
        snapshotTimestamp: Long
    ) = withContext(Dispatchers.IO) {
        val dao = database.dao()
        database.withTransaction {
            val currentIds = conversations.map { it.id }
            val removedIds = dao.conversationIds().filterNot { it in currentIds }
            if (removedIds.isNotEmpty()) dao.deleteConversations(removedIds)
            conversations.filter { knownVersions[it.id] != it.hashCode() }.forEach { record ->
                dao.putConversation(ConversationEntity(record.id, record.title, record.updatedAt, record.projectId))
                if (record.messagesLoaded) {
                    dao.clearMessages(record.id)
                    val rows = record.messages.mapIndexed { index, message ->
                        MessageEntity(
                            message.id, record.id, index, message.role, message.content,
                            JSONArray(message.versions.take(20)).toString(), message.activeVersion
                        )
                    }
                    if (rows.isNotEmpty()) dao.putMessages(rows)
                }
            }
            dao.putProjects(projects.map {
                ProjectEntity(it.id, it.name, it.instructions, it.createdAt, it.archivedAt)
            })
            val currentProjectIds = projects.map { it.id }
            val removedProjectIds = dao.projectIds().filterNot { it in currentProjectIds }
            removedProjectIds.forEach { dao.deleteProjectAndKnowledge(it) }
        }
        ConversationPreferencesStore.saveConversationList(prefs, conversations, markDirty = false)
        ProjectPreferencesStore.saveProjects(prefs, projects, markDirty = false)
        prefs.edit().putLong("room_synced_at", snapshotTimestamp).apply()
    }
}
