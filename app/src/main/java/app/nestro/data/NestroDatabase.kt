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

@Entity(tableName = "knowledge_documents", indices = [Index(value = ["projectId"]), Index(value = ["contentHash"], unique = true)])
data class KnowledgeDocumentEntity(
    @PrimaryKey val id: String, val projectId: String, val name: String,
    val mimeType: String, val sizeBytes: Long, val importedAt: Long, val contentHash: String
)
@Entity(tableName = "knowledge_chunks", foreignKeys = [ForeignKey(entity = KnowledgeDocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["documentId"]), Index(value = ["projectId"])])
data class KnowledgeChunkEntity(@PrimaryKey val id: String, val documentId: String, val projectId: String, val position: Int, val content: String)

@Fts4
@Entity(tableName = "knowledge_chunks_fts")
data class KnowledgeChunkFtsEntity(
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


@Dao
interface NestroDao {
    @Transaction
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun loadConversations(): List<ConversationWithMessages>

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
    @Query("SELECT * FROM user_memories ORDER BY projectId, updatedAt DESC")
    suspend fun allUserMemories(): List<UserMemoryEntity>

    @Query("SELECT * FROM user_memories WHERE projectId = :projectId AND fingerprint = :fingerprint LIMIT 1")
    suspend fun userMemoryByFingerprint(projectId: String, fingerprint: String): UserMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putUserMemory(memory: UserMemoryEntity)

    @Query("UPDATE user_memories SET content = :content, category = :category, fingerprint = :fingerprint, updatedAt = :updatedAt WHERE id = :id AND projectId = :projectId")
    suspend fun updateUserMemory(id: String, projectId: String, content: String, category: String, fingerprint: String, updatedAt: Long)

    @Query("DELETE FROM user_memories WHERE id = :id AND projectId = :projectId")
    suspend fun deleteUserMemory(id: String, projectId: String)

    @Query("DELETE FROM user_memories WHERE projectId = :projectId")
    suspend fun deleteUserMemoriesForProject(projectId: String)

    @Query("DELETE FROM user_memories WHERE projectId = :projectId AND id NOT IN (SELECT id FROM user_memories WHERE projectId = :projectId ORDER BY updatedAt DESC LIMIT :keep)")
    suspend fun trimUserMemories(projectId: String, keep: Int = 200)

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
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertKnowledgeDocuments(documents: List<KnowledgeDocumentEntity>)
    @Query("SELECT * FROM knowledge_documents ORDER BY projectId, importedAt DESC") suspend fun allKnowledgeDocuments(): List<KnowledgeDocumentEntity>
    @Query("SELECT * FROM knowledge_chunks ORDER BY projectId, documentId, position") suspend fun allKnowledgeChunks(): List<KnowledgeChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearMessages(conversationId: String)

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun deleteConversationRows(ids: List<String>)

    @Query("DELETE FROM user_memories WHERE sourceConversationId IN (:ids)")
    suspend fun deleteUserMemoriesForConversations(ids: List<String>)

    @Transaction
    suspend fun deleteConversations(ids: List<String>) {
        if (ids.isEmpty()) return
        deleteUserMemoriesForConversations(ids)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProjects(projects: List<ProjectEntity>)

    @Query("DELETE FROM projects WHERE id IN (:ids)")
    suspend fun deleteProjects(ids: List<String>)
    @Query("SELECT * FROM knowledge_documents WHERE projectId = :projectId ORDER BY importedAt DESC")
    suspend fun knowledgeDocuments(projectId: String): List<KnowledgeDocumentEntity>
    @Query("SELECT * FROM knowledge_documents WHERE contentHash = :hash LIMIT 1")
    suspend fun knowledgeDocumentByHash(hash: String): KnowledgeDocumentEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertKnowledgeDocument(document: KnowledgeDocumentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKnowledgeChunks(chunks: List<KnowledgeChunkEntity>)
    @Transaction
    suspend fun insertKnowledgeBundleIfAbsent(
        document: KnowledgeDocumentEntity,
        chunks: List<KnowledgeChunkEntity>
    ): KnowledgeDocumentEntity {
        val existing = knowledgeDocumentByHash(document.contentHash)
        if (existing != null) return existing
        insertKnowledgeDocument(document)
        insertKnowledgeChunks(chunks)
        return document
    }
    @Query("DELETE FROM knowledge_documents WHERE id = :documentId AND projectId = :projectId")
    suspend fun deleteKnowledgeDocument(documentId: String, projectId: String)
    @Query("DELETE FROM knowledge_documents WHERE projectId = :projectId")
    suspend fun deleteKnowledgeDocumentsForProject(projectId: String)
    @Query("DELETE FROM phone_agent_tasks WHERE projectId = :projectId")
    suspend fun deletePhoneAgentTasksForProject(projectId: String)
    @Transaction
    suspend fun deleteProjectAndKnowledge(projectId: String) {
        deleteKnowledgeDocumentsForProject(projectId)
        deletePhoneAgentTasksForProject(projectId)
        deleteUserMemoriesForProject(projectId)
        deleteProjects(listOf(projectId))
    }
        @Query("SELECT kc.* FROM knowledge_chunks_fts JOIN knowledge_chunks AS kc ON kc.id = knowledge_chunks_fts.chunkId WHERE knowledge_chunks_fts MATCH :matchQuery AND kc.projectId = :projectId ORDER BY kc.position ASC LIMIT :limit")
    suspend fun searchKnowledgeChunksFts(projectId: String, matchQuery: String, limit: Int = 12): List<KnowledgeChunkEntity>

    @Query("SELECT * FROM knowledge_chunks WHERE projectId = :projectId AND INSTR(LOWER(content), LOWER(:term)) > 0 ORDER BY position ASC LIMIT :limit")
    suspend fun searchKnowledgeChunks(projectId: String, term: String, limit: Int = 8): List<KnowledgeChunkEntity>

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
    entities = [ConversationEntity::class, MessageEntity::class, ProjectEntity::class, KnowledgeDocumentEntity::class, KnowledgeChunkEntity::class, KnowledgeChunkFtsEntity::class, PhoneAgentTaskEntity::class, PhoneAgentTaskLogEntity::class, UserMemoryEntity::class],
    version = 9,
    exportSchema = false
)
abstract class NestroDatabase : RoomDatabase() {
    abstract fun dao(): NestroDao

    companion object {
        private fun createKnowledgeFts(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_chunks_fts USING fts4(chunkId, projectId, content, tokenize=unicode61)")
            db.execSQL("INSERT INTO knowledge_chunks_fts(chunkId, projectId, content) SELECT id, projectId, content FROM knowledge_chunks")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS knowledge_chunks_fts_ai AFTER INSERT ON knowledge_chunks BEGIN INSERT INTO knowledge_chunks_fts(chunkId, projectId, content) VALUES (new.id, new.projectId, new.content); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS knowledge_chunks_fts_ad AFTER DELETE ON knowledge_chunks BEGIN DELETE FROM knowledge_chunks_fts WHERE chunkId = old.id; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS knowledge_chunks_fts_au AFTER UPDATE OF projectId, content ON knowledge_chunks BEGIN DELETE FROM knowledge_chunks_fts WHERE chunkId = old.id; INSERT INTO knowledge_chunks_fts(chunkId, projectId, content) VALUES (new.id, new.projectId, new.content); END")
        }

        internal val KNOWLEDGE_FTS_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createKnowledgeFts(db)
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createKnowledgeFts(db)
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
        internal val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
        @Volatile private var instance: NestroDatabase? = null

        fun get(context: Context): NestroDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NestroDatabase::class.java,
                    "nestro_local.db"
                ).addMigrations(*ALL_MIGRATIONS)
                    .addCallback(KNOWLEDGE_FTS_CALLBACK).build().also { instance = it }
            }
    }
}
