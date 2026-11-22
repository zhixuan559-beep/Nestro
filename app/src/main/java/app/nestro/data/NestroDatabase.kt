package app.nestro.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Dao
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal fun String.stableFtsRowId(): Long = java.security.MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8)).take(8).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) } and Long.MAX_VALUE

@Entity(tableName = "conversations", indices = [Index(value = ["projectId", "updatedAt"])])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val updatedAt: Long,
    val projectId: String
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId"]), Index(value = ["conversationId", "position"]), Index(value = ["conversationId", "role", "position"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val position: Int,
    val role: String,
    val content: String,
    val versionsJson: String = "[]",
    val activeVersion: Int = 0
)

@Entity(tableName = "knowledge_documents", indices = [Index(value = ["projectId"]), Index(value = ["projectId", "contentHash"], unique = true)])
data class KnowledgeDocumentEntity(
    @PrimaryKey val id: String, val projectId: String, val name: String,
    val mimeType: String, val sizeBytes: Long, val importedAt: Long, val contentHash: String
)
@Entity(tableName = "knowledge_chunks", foreignKeys = [ForeignKey(entity = KnowledgeDocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["documentId"]), Index(value = ["projectId"])])
data class KnowledgeChunkEntity(@PrimaryKey val id: String, val documentId: String, val projectId: String, val position: Int, val content: String)

@Entity(
    tableName = "knowledge_chunk_embeddings",
    primaryKeys = ["chunkId", "modelId"],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["modelId"])]
)
data class KnowledgeChunkEmbeddingEntity(
    val chunkId: String,
    val modelId: String,
    val dimensions: Int,
    val embedding: ByteArray
)


@Fts4(tokenizer = androidx.room.FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "knowledge_chunks_fts")
data class KnowledgeChunkFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val chunkId: String,
    val projectId: String,
    val content: String
)
@Entity(
    tableName = "phone_agent_task_logs",
    foreignKeys = [ForeignKey(entity = PhoneAgentTaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["taskId"]), Index(value = ["taskId", "createdAt"])]
)
data class PhoneAgentTaskLogEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val actionIndex: Int,
    val action: String,
    val success: Boolean,
    val detail: String,
    val createdAt: Long
)

@Entity(
    tableName = "user_memories",
    indices = [
        Index(value = ["projectId", "updatedAt"]),
        Index(value = ["projectId", "fingerprint"], unique = true),
        Index(value = ["sourceConversationId"])
    ]
)
data class UserMemoryEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val category: String,
    val content: String,
    val fingerprint: String,
    val sourceConversationId: String,
    val sourceMessageId: String,
    val confidence: Double,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val shareAcrossProjects: Boolean = false
)

@Entity(
    tableName = "agent_task_traces",
    indices = [Index(value = ["taskId", "createdAt"])]
)
data class AgentTaskTraceEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val event: String,
    val detail: String,
    val createdAt: Long
)

@Entity(tableName = "agent_task_states")
data class AgentTaskStateEntity(
    @PrimaryKey val taskId: String,
    val owner: String,
    val status: String,
    val currentStep: Int,
    val checkpoint: String,
    val lastError: String,
    val updatedAt: Long
)

@Entity(tableName = "phone_agent_tasks", indices = [Index(value = ["projectId", "updatedAt"]), Index(value = ["conversationId"])])
data class PhoneAgentTaskEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val conversationId: String,
    val goal: String,
    val category: String,
    val status: String,
    val planJson: String,
    val currentStep: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val instructions: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val archivedAt: Long = 0L
)

data class ConversationWithMessages(
    @androidx.room.Embedded val conversation: ConversationEntity,
    @Relation(parentColumn = "id", entityColumn = "conversationId")
    val messages: List<MessageEntity>
)

data class ConversationMemoryHit(
    val messageId: String,
    val conversationId: String,
    val conversationTitle: String,
    val role: String,
    val content: String,
    val updatedAt: Long
)

data class ConversationListItem(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val projectId: String,
    val lastMessagePreview: String,
    val messageCount: Int
)


@Dao
interface NestroDao {
    @Transaction
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun loadConversations(): List<ConversationWithMessages>

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun conversationCount(): Int

    @Query("""
        SELECT c.id AS id, c.title AS title, c.updatedAt AS updatedAt, c.projectId AS projectId,
               COALESCE((SELECT SUBSTR(m.content, 1, 240) FROM messages AS m WHERE m.conversationId = c.id
                         ORDER BY m.position DESC LIMIT 1), '') AS lastMessagePreview,
               (SELECT COUNT(*) FROM messages AS countMessage WHERE countMessage.conversationId = c.id) AS messageCount
        FROM conversations AS c
        ORDER BY c.updatedAt DESC, c.id DESC
    """)
    suspend fun allConversationSummaries(): List<ConversationListItem>

    @Query("""
        SELECT c.id AS id, c.title AS title, c.updatedAt AS updatedAt, c.projectId AS projectId,
               COALESCE((SELECT SUBSTR(m.content, 1, 240) FROM messages AS m WHERE m.conversationId = c.id
                         ORDER BY m.position DESC LIMIT 1), '') AS lastMessagePreview,
               (SELECT COUNT(*) FROM messages AS countMessage WHERE countMessage.conversationId = c.id) AS messageCount
        FROM conversations AS c
        WHERE c.projectId = :projectId
          AND (:query = '' OR INSTR(LOWER(c.title), LOWER(:query)) > 0
               OR EXISTS (SELECT 1 FROM messages AS searchMessage
                          WHERE searchMessage.conversationId = c.id
                            AND INSTR(LOWER(searchMessage.content), LOWER(:query)) > 0))
        ORDER BY c.updatedAt DESC, c.id DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun conversationListPage(
        projectId: String,
        query: String,
        limit: Int,
        offset: Int
    ): List<ConversationListItem>

    @Transaction
    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun conversationWithMessages(conversationId: String): ConversationWithMessages?

    @Query("""
        SELECT m.id AS messageId, m.conversationId AS conversationId,
               c.title AS conversationTitle, m.role AS role,
               m.content AS content, c.updatedAt AS updatedAt
        FROM messages AS m
        INNER JOIN conversations AS c ON c.id = m.conversationId
        WHERE c.projectId = :projectId
          AND c.id != :currentConversationId
          AND m.role IN ('user', 'assistant')
          AND LENGTH(TRIM(m.content)) > 0
          AND LOWER(m.content) LIKE '%' || LOWER(:term) || '%'
        ORDER BY c.updatedAt DESC, m.position DESC
        LIMIT :limit
    """)
    suspend fun searchConversationMessages(
        projectId: String,
        currentConversationId: String,
        term: String,
        limit: Int = 6
    ): List<ConversationMemoryHit>


    @Query("""
        SELECT m.id AS messageId, m.conversationId AS conversationId,
               c.title AS conversationTitle, m.role AS role,
               m.content AS content, c.updatedAt AS updatedAt
        FROM messages AS m INNER JOIN conversations AS c ON c.id = m.conversationId
        WHERE c.projectId = :projectId AND c.id != :currentConversationId
          AND m.role IN ('user', 'assistant') AND LENGTH(TRIM(m.content)) > 0
          AND (
            (:term1 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term1) || '%') OR
            (:term2 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term2) || '%') OR
            (:term3 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term3) || '%') OR
            (:term4 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term4) || '%') OR
            (:term5 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term5) || '%') OR
            (:term6 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term6) || '%') OR
            (:term7 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term7) || '%') OR
            (:term8 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term8) || '%') OR
            (:term9 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term9) || '%') OR
            (:term10 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term10) || '%') OR
            (:term11 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term11) || '%') OR
            (:term12 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term12) || '%')
          )
        ORDER BY (
            CASE WHEN :term1 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term1) || '%' THEN 1 ELSE 0 END +
            CASE WHEN :term2 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term2) || '%' THEN 1 ELSE 0 END +
            CASE WHEN :term3 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term3) || '%' THEN 1 ELSE 0 END +
            CASE WHEN :term4 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term4) || '%' THEN 1 ELSE 0 END +
            CASE WHEN :term5 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term5) || '%' THEN 1 ELSE 0 END +
            CASE WHEN :term6 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term6) || '%' THEN 1 ELSE 0 END +
            CASE WHEN :term7 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term7) || '%' THEN 1 ELSE 0 END +
            CASE WHEN :term8 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term8) || '%' THEN 1 ELSE 0 END +
            CASE WHEN :term9 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term9) || '%' THEN 1 ELSE 0 END +
            CASE WHEN :term10 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term10) || '%' THEN 1 ELSE 0 END +
            CASE WHEN :term11 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term11) || '%' THEN 1 ELSE 0 END +
            CASE WHEN :term12 != '' AND LOWER(m.content) LIKE '%' || LOWER(:term12) || '%' THEN 1 ELSE 0 END
        ) DESC, c.updatedAt DESC, m.position DESC LIMIT :limit
    """)
    suspend fun searchConversationMessagesByTerms(
        projectId: String, currentConversationId: String,
        term1: String,
        term2: String,
        term3: String,
        term4: String,
        term5: String,
        term6: String,
        term7: String,
        term8: String,
        term9: String,
        term10: String,
        term11: String,
        term12: String,
        limit: Int = 40
    ): List<ConversationMemoryHit>
    @Query("SELECT * FROM user_memories WHERE projectId = :projectId ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun userMemories(projectId: String, limit: Int = 200): List<UserMemoryEntity>

    @Query("SELECT * FROM user_memories WHERE projectId = :projectId OR shareAcrossProjects = 1 ORDER BY CASE WHEN projectId = :projectId THEN 0 ELSE 1 END, updatedAt DESC LIMIT :limit")
    suspend fun userMemoriesForRecall(projectId: String, limit: Int = 200): List<UserMemoryEntity>

    @Query("UPDATE user_memories SET shareAcrossProjects = :enabled, updatedAt = :updatedAt WHERE id = :id AND projectId = :ownerProjectId")
    suspend fun setUserMemoryShareAcrossProjects(id: String, ownerProjectId: String, enabled: Boolean, updatedAt: Long): Int
    @Query("SELECT * FROM user_memories ORDER BY projectId, updatedAt DESC")
    suspend fun allUserMemories(): List<UserMemoryEntity>

    @Query("SELECT * FROM user_memories WHERE projectId = :projectId AND fingerprint = :fingerprint LIMIT 1")
    suspend fun userMemoryByFingerprint(projectId: String, fingerprint: String): UserMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putUserMemory(memory: UserMemoryEntity)

    @Query("UPDATE user_memories SET content = :content, category = :category, fingerprint = :fingerprint, updatedAt = :updatedAt WHERE id = :id AND projectId = :projectId")
    suspend fun updateUserMemory(id: String, projectId: String, content: String, category: String, fingerprint: String, updatedAt: Long): Int

    @Query("DELETE FROM user_memories WHERE id = :id AND projectId = :projectId")
    suspend fun deleteUserMemory(id: String, projectId: String): Int

    @Query("DELETE FROM user_memories WHERE projectId = :projectId")
    suspend fun deleteUserMemoriesForProject(projectId: String)

    @Transaction
    suspend fun importUserMemoryIfAbsent(memory: UserMemoryEntity): Boolean {
        if (userMemoryByFingerprint(memory.projectId, memory.fingerprint) != null) return false
        putUserMemory(memory)
        trimUserMemories(memory.projectId, 200)
        return true
    }

    @Query("DELETE FROM user_memories WHERE projectId = :projectId AND id NOT IN (SELECT id FROM user_memories WHERE projectId = :projectId ORDER BY updatedAt DESC LIMIT :keep)")
    suspend fun trimUserMemories(projectId: String, keep: Int = 200)

    @Query("SELECT * FROM agent_task_states WHERE taskId = :taskId LIMIT 1")
    suspend fun agentTaskState(taskId: String): AgentTaskStateEntity?

    @Query("SELECT * FROM agent_task_states ORDER BY updatedAt DESC")
    suspend fun allAgentTaskStates(): List<AgentTaskStateEntity>

    @Query("SELECT * FROM agent_task_states WHERE owner LIKE 'generated-plugin:%' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recentGeneratedPluginTaskStates(limit: Int = 10): List<AgentTaskStateEntity>

    @Query("SELECT * FROM agent_task_states WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_FOR_USER', 'PAUSED', 'FAILED') ORDER BY updatedAt ASC")
    suspend fun recoverableAgentTaskStates(): List<AgentTaskStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAgentTaskState(state: AgentTaskStateEntity)

    @Query("DELETE FROM agent_task_states WHERE taskId = :taskId")
    suspend fun deleteAgentTaskState(taskId: String)

    @Query("DELETE FROM agent_task_states")
    suspend fun deleteAllAgentTaskStates()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgentTaskTrace(trace: AgentTaskTraceEntity)

    @Query("SELECT * FROM agent_task_traces ORDER BY createdAt ASC")
    suspend fun allAgentTaskTraces(): List<AgentTaskTraceEntity>

    @Query("DELETE FROM agent_task_traces")
    suspend fun deleteAllAgentTaskTraces()

    @Query("SELECT * FROM agent_task_traces WHERE taskId = :taskId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentAgentTaskTraces(taskId: String, limit: Int = 100): List<AgentTaskTraceEntity>

    @Query("DELETE FROM agent_task_traces WHERE taskId = :taskId AND id NOT IN (SELECT id FROM agent_task_traces WHERE taskId = :taskId ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun pruneAgentTaskTraces(taskId: String, keep: Int = 500)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPhoneAgentTask(task: PhoneAgentTaskEntity)

    @Query("SELECT * FROM phone_agent_tasks WHERE projectId = :projectId ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun phoneAgentTasks(projectId: String, limit: Int = 50): List<PhoneAgentTaskEntity>
    @Query("SELECT * FROM phone_agent_tasks ORDER BY updatedAt DESC")
    suspend fun allPhoneAgentTasks(): List<PhoneAgentTaskEntity>

    @Query("SELECT * FROM phone_agent_tasks WHERE id = :taskId LIMIT 1")
    suspend fun phoneAgentTask(taskId: String): PhoneAgentTaskEntity?

    @Query("DELETE FROM phone_agent_tasks WHERE id = :taskId")
    suspend fun deletePhoneAgentTask(taskId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoneAgentTaskLog(log: PhoneAgentTaskLogEntity)

    @Query("SELECT * FROM phone_agent_task_logs WHERE taskId = :taskId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun phoneAgentTaskLogs(taskId: String, limit: Int = 100): List<PhoneAgentTaskLogEntity>
    @Query("SELECT * FROM phone_agent_task_logs WHERE taskId = :taskId ORDER BY createdAt DESC")
    suspend fun allPhoneAgentTaskLogs(taskId: String): List<PhoneAgentTaskLogEntity>

    @Query("DELETE FROM phone_agent_task_logs WHERE taskId = :taskId AND id NOT IN (SELECT id FROM phone_agent_task_logs WHERE taskId = :taskId ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun trimPhoneAgentTaskLogs(taskId: String, keep: Int = 100)



    @Query("UPDATE phone_agent_tasks SET status = :status, currentStep = :currentStep, updatedAt = :updatedAt WHERE id = :taskId")
    suspend fun updatePhoneAgentTaskProgress(taskId: String, status: String, currentStep: Int, updatedAt: Long)

    @Query("UPDATE phone_agent_tasks SET status = 'cancelled', updatedAt = :updatedAt WHERE id = :taskId AND status NOT IN ('completed', 'cancelled')")
    suspend fun cancelPhoneAgentTaskIfActive(taskId: String, updatedAt: Long): Int

    @Query("UPDATE phone_agent_tasks SET planJson = :planJson, status = 'planned', currentStep = 0, updatedAt = :updatedAt WHERE id = :taskId")
    suspend fun updatePhoneAgentTaskPlan(taskId: String, planJson: String, updatedAt: Long)

    @Query("UPDATE phone_agent_tasks SET goal = :goal, updatedAt = :updatedAt WHERE id = :taskId")
    suspend fun updatePhoneAgentTaskGoal(taskId: String, goal: String, updatedAt: Long)

    @Query("SELECT id FROM conversations")
    suspend fun conversationIds(): List<String>

    @Query("DELETE FROM conversations") suspend fun deleteAllConversations()
    @Query("DELETE FROM projects") suspend fun deleteAllProjects()
    @Query("DELETE FROM user_memories") suspend fun deleteAllUserMemories()
    @Query("DELETE FROM phone_agent_tasks") suspend fun deleteAllPhoneAgentTasks()
    @Query("DELETE FROM knowledge_documents") suspend fun deleteAllKnowledgeDocuments()
    @Query("DELETE FROM knowledge_chunks_fts") suspend fun deleteAllKnowledgeChunkFts()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertKnowledgeDocuments(documents: List<KnowledgeDocumentEntity>)
    @Query("SELECT * FROM knowledge_documents ORDER BY projectId, importedAt DESC") suspend fun allKnowledgeDocuments(): List<KnowledgeDocumentEntity>
    @Query("SELECT * FROM knowledge_chunks ORDER BY projectId, documentId, position") suspend fun allKnowledgeChunks(): List<KnowledgeChunkEntity>

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt, projectId = :projectId WHERE id = :id")
    suspend fun updateConversationMetadata(
        id: String,
        title: String,
        updatedAt: Long,
        projectId: String
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Transaction
    suspend fun putConversation(conversation: ConversationEntity) {
        val updated = updateConversationMetadata(
            conversation.id,
            conversation.title,
            conversation.updatedAt,
            conversation.projectId
        )
        if (updated == 0) insertConversation(conversation)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearMessages(conversationId: String)

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun deleteConversationRows(ids: List<String>)

    @Query("SELECT DISTINCT projectId FROM conversations WHERE id IN (:ids)")
    suspend fun conversationProjectIds(ids: List<String>): List<String>

    @Query("DELETE FROM user_memories WHERE projectId = :projectId AND sourceConversationId IN (:ids)")
    suspend fun deleteUserMemoriesForConversations(ids: List<String>, projectId: String)

    @Transaction
    suspend fun deleteConversations(ids: List<String>) {
        if (ids.isEmpty()) return
        conversationProjectIds(ids).forEach { projectId ->
            deleteUserMemoriesForConversations(ids, projectId)
        }
        deleteConversationRows(ids)
    }

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    suspend fun loadProjects(): List<ProjectEntity>

    @Query("SELECT id FROM projects")
    suspend fun projectIds(): List<String>

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM conversations
            WHERE projectId = :projectId
              AND (title != '新对话' OR EXISTS(SELECT 1 FROM messages WHERE messages.conversationId = conversations.id))
        )
            OR EXISTS(SELECT 1 FROM knowledge_documents WHERE projectId = :projectId)
            OR EXISTS(SELECT 1 FROM phone_agent_tasks WHERE projectId = :projectId)
            OR EXISTS(SELECT 1 FROM user_memories WHERE projectId = :projectId)
    """)
    suspend fun projectHasLinkedData(projectId: String): Boolean

    @Query("SELECT id FROM conversations WHERE projectId = :projectId")
    suspend fun projectConversationIds(projectId: String): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM projects WHERE id = :projectId)")
    suspend fun projectExists(projectId: String): Boolean

    @Query("DELETE FROM conversations WHERE projectId = :projectId AND title = '新对话' AND NOT EXISTS(SELECT 1 FROM messages WHERE messages.conversationId = conversations.id)")
    suspend fun deleteEmptyProjectConversations(projectId: String)

    @Transaction
    suspend fun deleteProjectIfEmpty(projectId: String, activeProjectId: String): Boolean {
        if (projectId.isBlank() || projectId == "default" || projectId == activeProjectId) return false
        if (!projectExists(projectId) || projectHasLinkedData(projectId)) return false
        deleteEmptyProjectConversations(projectId)
        deleteProjectAndKnowledge(projectId)
        return true
    }

    @Transaction
    suspend fun recoverPendingProjectDeletionIfEmpty(projectId: String, activeProjectId: String): Boolean {
        if (projectId.isBlank() || projectId == "default" || projectId == activeProjectId) return false
        if (projectHasLinkedData(projectId)) return false
        if (projectExists(projectId)) {
            deleteEmptyProjectConversations(projectId)
            deleteProjectAndKnowledge(projectId)
        } else {
            deleteEmptyProjectConversations(projectId)
        }
        return true
    }

    @Transaction
    suspend fun duplicateProject(
        sourceProjectId: String,
        newProjectId: String,
        newName: String,
        createdAt: Long
    ): Boolean {
        if (sourceProjectId.isBlank() || newProjectId.isBlank() || sourceProjectId == newProjectId ||
            createdAt < 0L || projectExists(newProjectId)) return false
        val currentProjects = loadProjects()
        val source = currentProjects.firstOrNull { it.id == sourceProjectId } ?: return false
        val validation = ProjectSpaceRules.validate(
            newName, "", currentProjects.map { ProjectSpaceRules.Entry(it.id, it.name) }
        )
        if (validation != null) return false

        val sourceConversations = loadConversations().filter { it.conversation.projectId == sourceProjectId }
        val conversationIds = sourceConversations.associate { it.conversation.id to java.util.UUID.randomUUID().toString() }
        val messageIds = sourceConversations.flatMap { it.messages }.associate { it.id to java.util.UUID.randomUUID().toString() }

        val sourceDocuments = knowledgeDocuments(sourceProjectId)
        val documentIds = sourceDocuments.associate { it.id to java.util.UUID.randomUUID().toString() }
        val sourceChunks = knowledgeChunksForProject(sourceProjectId)

        putProjects(listOf(ProjectEntity(newProjectId, newName.trim(), source.instructions, createdAt, 0L)))
        sourceConversations.forEach { row ->
            val newConversationId = conversationIds.getValue(row.conversation.id)
            putConversation(row.conversation.copy(id = newConversationId, projectId = newProjectId, updatedAt = createdAt))
            val copiedMessages = row.messages.map { message ->
                message.copy(
                    id = messageIds.getValue(message.id),
                    conversationId = newConversationId
                )
            }
            if (copiedMessages.isNotEmpty()) putMessages(copiedMessages)
        }

        val copiedDocuments = sourceDocuments.map { document ->
            document.copy(
                id = documentIds.getValue(document.id),
                projectId = newProjectId,
                importedAt = createdAt
            )
        }
        if (copiedDocuments.isNotEmpty()) insertKnowledgeDocuments(copiedDocuments)
        val copiedChunks = sourceChunks.map { chunk ->
            chunk.copy(
                id = java.util.UUID.randomUUID().toString(),
                documentId = documentIds[chunk.documentId] ?: error("项目知识片段缺少所属文档"),
                projectId = newProjectId
            )
        }
        if (copiedChunks.isNotEmpty()) {
            insertKnowledgeChunks(copiedChunks)
            insertKnowledgeChunkFts(copiedChunks.map { chunk ->
                KnowledgeChunkFtsEntity(chunk.id.stableFtsRowId(), chunk.id, newProjectId, chunk.content)
            })
        }

        userMemories(sourceProjectId)
            .filter { memory ->
                memory.sourceConversationId in conversationIds && memory.sourceMessageId in messageIds
            }
            .forEach { memory ->
                putUserMemory(memory.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    projectId = newProjectId,
                    sourceConversationId = conversationIds.getValue(memory.sourceConversationId),
                    sourceMessageId = messageIds.getValue(memory.sourceMessageId),
                    fingerprint = UserMemoryStore.fingerprint(memory.category + "|" + memory.content.lowercase()),
                    createdAt = createdAt,
                    updatedAt = createdAt
                ))
            }
        return true
    }

    @Transaction
    suspend fun rollbackProjectDuplicate(projectId: String) {
        require(projectId.isNotBlank() && projectId != "default") { "复制回滚项目 ID 无效" }
        val ids = projectConversationIds(projectId)
        if (ids.isNotEmpty()) deleteConversations(ids)
        deleteProjectAndKnowledge(projectId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProjects(projects: List<ProjectEntity>)

    @Query("DELETE FROM projects WHERE id IN (:ids)")
    suspend fun deleteProjects(ids: List<String>)
    @Query("SELECT * FROM knowledge_documents WHERE projectId = :projectId ORDER BY importedAt DESC")
    suspend fun knowledgeDocuments(projectId: String): List<KnowledgeDocumentEntity>
    @Query("SELECT * FROM knowledge_chunks WHERE projectId = :projectId ORDER BY documentId, position")
    suspend fun knowledgeChunksForProject(projectId: String): List<KnowledgeChunkEntity>
    @Query("SELECT * FROM knowledge_documents WHERE projectId = :projectId AND contentHash = :hash LIMIT 1")
    suspend fun knowledgeDocumentByHash(projectId: String, hash: String): KnowledgeDocumentEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertKnowledgeDocument(document: KnowledgeDocumentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKnowledgeChunks(chunks: List<KnowledgeChunkEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKnowledgeChunkFts(rows: List<KnowledgeChunkFtsEntity>)

    @Query("DELETE FROM knowledge_chunks_fts WHERE chunkId IN (:chunkIds)")
    suspend fun deleteKnowledgeChunkFts(chunkIds: List<String>)

    @Query("SELECT c.* FROM knowledge_chunks c INNER JOIN knowledge_chunks_fts f ON f.chunkId = c.id WHERE c.projectId = :projectId AND knowledge_chunks_fts MATCH :query ORDER BY f.rowid LIMIT :limit")
    suspend fun searchKnowledgeChunksFts(projectId: String, query: String, limit: Int = 12): List<KnowledgeChunkEntity>

    @Transaction
    suspend fun insertKnowledgeBundleIfAbsent(
        document: KnowledgeDocumentEntity,
        chunks: List<KnowledgeChunkEntity>
    ): KnowledgeDocumentEntity {
        val existing = knowledgeDocumentByHash(document.projectId, document.contentHash)
        if (existing != null) return existing
        insertKnowledgeDocument(document)
        insertKnowledgeChunks(chunks)
        insertKnowledgeChunkFts(chunks.map { chunk -> KnowledgeChunkFtsEntity(chunk.id.stableFtsRowId(), chunk.id, chunk.projectId, chunk.content) })
        return document
    }
    @Query("SELECT id FROM knowledge_chunks WHERE documentId = :documentId AND projectId = :projectId")
    suspend fun knowledgeChunkIdsForDocument(documentId: String, projectId: String): List<String>

    @Query("DELETE FROM knowledge_documents WHERE id = :documentId AND projectId = :projectId")
    suspend fun deleteKnowledgeDocument(documentId: String, projectId: String)

    @Transaction
    suspend fun deleteKnowledgeDocumentAndIndex(documentId: String, projectId: String) {
        val chunkIds = knowledgeChunkIdsForDocument(documentId, projectId)
        if (chunkIds.isNotEmpty()) deleteKnowledgeChunkFts(chunkIds)
        deleteKnowledgeDocument(documentId, projectId)
    }
    @Query("DELETE FROM knowledge_documents WHERE projectId = :projectId")
    suspend fun deleteKnowledgeDocumentsForProject(projectId: String)
    @Query("DELETE FROM knowledge_chunks_fts WHERE projectId = :projectId")
    suspend fun deleteKnowledgeChunkFtsForProject(projectId: String)
    @Query("DELETE FROM phone_agent_tasks WHERE projectId = :projectId")
    suspend fun deletePhoneAgentTasksForProject(projectId: String)
    @Transaction
    suspend fun deleteProjectAndKnowledge(projectId: String) {
        deleteKnowledgeChunkFtsForProject(projectId)
        deleteKnowledgeDocumentsForProject(projectId)
        deletePhoneAgentTasksForProject(projectId)
        deleteUserMemoriesForProject(projectId)
        deleteProjects(listOf(projectId))
    }
        @Query("SELECT * FROM knowledge_chunks WHERE projectId = :projectId AND INSTR(LOWER(content), LOWER(:term)) > 0 ORDER BY position ASC LIMIT :limit")
    suspend fun searchKnowledgeChunks(projectId: String, term: String, limit: Int = 8): List<KnowledgeChunkEntity>
    @Query("SELECT * FROM knowledge_chunks WHERE documentId = :documentId AND projectId = :projectId ORDER BY position ASC")
    suspend fun knowledgeChunksForDocument(documentId: String, projectId: String): List<KnowledgeChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putKnowledgeChunkEmbeddings(embeddings: List<KnowledgeChunkEmbeddingEntity>)

    @Query("SELECT e.* FROM knowledge_chunk_embeddings AS e INNER JOIN knowledge_chunks AS c ON c.id = e.chunkId WHERE c.projectId = :projectId AND e.modelId = :modelId ORDER BY c.documentId ASC, c.position ASC")
    suspend fun knowledgeChunkEmbeddingsForProject(projectId: String, modelId: String): List<KnowledgeChunkEmbeddingEntity>

    @Query("DELETE FROM knowledge_chunk_embeddings WHERE modelId = :modelId AND chunkId IN (SELECT id FROM knowledge_chunks WHERE projectId = :projectId)")
    suspend fun deleteKnowledgeChunkEmbeddingsForProject(projectId: String, modelId: String)

    @Query("SELECT COUNT(*) FROM knowledge_chunk_embeddings AS e INNER JOIN knowledge_chunks AS c ON c.id = e.chunkId WHERE c.projectId = :projectId AND e.modelId = :modelId")
    suspend fun knowledgeChunkEmbeddingCount(projectId: String, modelId: String): Int
    @Query("SELECT COUNT(*) FROM knowledge_chunks WHERE projectId = :projectId")
    suspend fun knowledgeChunkCount(projectId: String): Int
    @Query("SELECT AVG(LENGTH(content)) FROM knowledge_chunks WHERE projectId = :projectId")
    suspend fun knowledgeAverageChunkLength(projectId: String): Double?
    @Query("SELECT COUNT(*) FROM knowledge_chunks WHERE projectId = :projectId AND INSTR(LOWER(content), LOWER(:term)) > 0")
    suspend fun knowledgeChunkDocumentFrequency(projectId: String, term: String): Int
    @Query("SELECT COUNT(*) FROM knowledge_chunks_fts JOIN knowledge_chunks AS kc ON kc.id = knowledge_chunks_fts.chunkId WHERE knowledge_chunks_fts MATCH :matchQuery AND kc.projectId = :projectId")
    suspend fun knowledgeFtsDocumentFrequency(projectId: String, matchQuery: String): Int

}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, ProjectEntity::class, KnowledgeDocumentEntity::class, KnowledgeChunkEntity::class, KnowledgeChunkFtsEntity::class, KnowledgeChunkEmbeddingEntity::class, PhoneAgentTaskEntity::class, PhoneAgentTaskLogEntity::class, UserMemoryEntity::class, AgentTaskStateEntity::class, AgentTaskTraceEntity::class],
    version = 14,
    exportSchema = false
)
abstract class NestroDatabase : RoomDatabase() {
    abstract fun dao(): NestroDao

    companion object {
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_chunk_embeddings (chunkId TEXT NOT NULL, modelId TEXT NOT NULL, dimensions INTEGER NOT NULL, embedding BLOB NOT NULL, PRIMARY KEY(chunkId, modelId), FOREIGN KEY(chunkId) REFERENCES knowledge_chunks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunk_embeddings_modelId ON knowledge_chunk_embeddings(modelId)")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_memories ADD COLUMN shareAcrossProjects INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_knowledge_documents_contentHash")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_knowledge_documents_projectId_contentHash ON knowledge_documents(projectId, contentHash)")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_chunks_fts USING fts4(chunkId, projectId, content, tokenize=unicode61)")
                db.execSQL("INSERT OR REPLACE INTO knowledge_chunks_fts(chunkId, projectId, content) SELECT id, projectId, content FROM knowledge_chunks")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS agent_task_traces (id TEXT NOT NULL, taskId TEXT NOT NULL, event TEXT NOT NULL, detail TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_task_traces_taskId_createdAt ON agent_task_traces(taskId, createdAt)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS agent_task_states (taskId TEXT NOT NULL, owner TEXT NOT NULL, status TEXT NOT NULL, currentStep INTEGER NOT NULL, checkpoint TEXT NOT NULL, lastError TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(taskId))")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN archivedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN versionsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE messages ADD COLUMN activeVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS user_memories (id TEXT NOT NULL, projectId TEXT NOT NULL, category TEXT NOT NULL, content TEXT NOT NULL, fingerprint TEXT NOT NULL, sourceConversationId TEXT NOT NULL, sourceMessageId TEXT NOT NULL, confidence REAL NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_memories_projectId_updatedAt ON user_memories(projectId, updatedAt)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_user_memories_projectId_fingerprint ON user_memories(projectId, fingerprint)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_memories_sourceConversationId ON user_memories(sourceConversationId)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS phone_agent_task_logs (id TEXT NOT NULL, taskId TEXT NOT NULL, actionIndex INTEGER NOT NULL, action TEXT NOT NULL, success INTEGER NOT NULL, detail TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(taskId) REFERENCES phone_agent_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_phone_agent_task_logs_taskId ON phone_agent_task_logs(taskId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_phone_agent_task_logs_taskId_createdAt ON phone_agent_task_logs(taskId, createdAt)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS phone_agent_tasks (id TEXT NOT NULL, projectId TEXT NOT NULL, conversationId TEXT NOT NULL, goal TEXT NOT NULL, category TEXT NOT NULL, status TEXT NOT NULL, planJson TEXT NOT NULL, currentStep INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_phone_agent_tasks_projectId_updatedAt ON phone_agent_tasks(projectId, updatedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_phone_agent_tasks_conversationId ON phone_agent_tasks(conversationId)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_projectId_updatedAt ON conversations(projectId, updatedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId_role_position ON messages(conversationId, role, position)")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_documents (id TEXT NOT NULL, projectId TEXT NOT NULL, name TEXT NOT NULL, mimeType TEXT NOT NULL, sizeBytes INTEGER NOT NULL, importedAt INTEGER NOT NULL, contentHash TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_projectId ON knowledge_documents(projectId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_knowledge_documents_contentHash ON knowledge_documents(contentHash)")
                db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_chunks (id TEXT NOT NULL, documentId TEXT NOT NULL, projectId TEXT NOT NULL, position INTEGER NOT NULL, content TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(documentId) REFERENCES knowledge_documents(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunks_documentId ON knowledge_chunks(documentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunks_projectId ON knowledge_chunks(projectId)")
            }
        }
        internal val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
        @Volatile private var instance: NestroDatabase? = null

        fun get(context: Context): NestroDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NestroDatabase::class.java,
                    "nestro_local.db"
                ).addMigrations(*ALL_MIGRATIONS).build().also { instance = it }
            }
    }
}
