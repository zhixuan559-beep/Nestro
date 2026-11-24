package app.nestro.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NestroDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "nestro_migration_test.db"

    @After fun cleanUp() {
        context.deleteDatabase(databaseName)
    }


    @Test fun cancelledPhoneAgentTaskCannotBeRevivedByProgressOrPlanWrites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            val now = System.currentTimeMillis()
            dao.putPhoneAgentTask(
                PhoneAgentTaskEntity(
                    id = "cancelled-phone-task", projectId = "p1", conversationId = "c1",
                    goal = "停止后不可恢复", category = "DEVICE_TASK",
                    status = PhoneAgentTaskStore.STATUS_CANCELLED, planJson = "[]",
                    currentStep = 0, createdAt = now, updatedAt = now
                )
            )
            dao.updatePhoneAgentTaskProgress(
                "cancelled-phone-task", PhoneAgentTaskStore.STATUS_RUNNING, 0, now + 1
            )
            dao.updatePhoneAgentTaskPlan("cancelled-phone-task", "[]", now + 2)
            assertEquals(
                PhoneAgentTaskStore.STATUS_CANCELLED,
                dao.phoneAgentTask("cancelled-phone-task")?.status
            )
        } finally {
            db.close()
        }
    }

    @Test fun projectDeletionGuardChecksEveryProjectOwnedDataFamily() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            dao.putProjects(listOf(ProjectEntity("p1", "项目一", "", 1)))
            assertEquals(false, dao.projectHasLinkedData("p1"))

            dao.putConversation(ConversationEntity("c1", "项目会话", 1, "p1"))
            assertEquals(true, dao.projectHasLinkedData("p1"))
            dao.deleteAllConversations()

            val hash = "a".repeat(64)
            dao.insertKnowledgeDocument(KnowledgeDocumentEntity("doc1", "p1", "知识", "text/plain", 1, 1, hash))
            assertEquals(true, dao.projectHasLinkedData("p1"))
            dao.deleteAllKnowledgeDocuments()

            dao.putUserMemory(UserMemoryEntity("mem1", "p1", "fact", "记忆", "fp1", "c-missing", "m1", 0.9, 1, 1))
            assertEquals(true, dao.projectHasLinkedData("p1"))
            dao.deleteAllUserMemories()

            dao.putPhoneAgentTask(PhoneAgentTaskEntity("task1", "p1", "c-missing", "目标", "general", "queued", "[]", 0, 1, 1))
            assertEquals(true, dao.projectHasLinkedData("p1"))
            assertEquals(false, dao.deleteProjectIfEmpty("p1", "default"))
            dao.deleteAllPhoneAgentTasks()

            assertEquals(true, dao.deleteProjectIfEmpty("p1", "default"))
            assertEquals(emptyList<String>(), dao.projectIds())
            assertEquals(false, dao.deleteProjectIfEmpty("default", "default"))
        } finally {
            db.close()
        }
    }

    @Test fun freshDatabaseKeepsFtsIndexSynchronizedWithKnowledgeChunks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            dao.insertKnowledgeDocument(KnowledgeDocumentEntity("fresh-doc", "p1", "Fresh", "text/plain", 1, 1, "c".repeat(64)))
            dao.insertKnowledgeChunks(listOf(KnowledgeChunkEntity("fresh-chunk", "fresh-doc", "p1", 0, "SQLite offline retrieval")))
            dao.insertKnowledgeChunkFts(listOf(KnowledgeChunkFtsEntity("fresh-chunk".stableFtsRowId(), "fresh-chunk", "p1", "SQLite offline retrieval")))
            assertEquals("fresh-chunk", dao.searchKnowledgeChunksFts("p1", "\"sqlite\"", 10).single().id)
            dao.deleteKnowledgeDocumentAndIndex("fresh-doc", "p1")
            assertEquals(emptyList<KnowledgeChunkEntity>(), dao.searchKnowledgeChunksFts("p1", "\"sqlite\"", 10))
        } finally {
            db.close()
        }
    }

    @Test fun knowledgeSearchIsolatedByProjectForFtsAndChineseFallback() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            dao.insertKnowledgeDocument(KnowledgeDocumentEntity("p1-doc", "p1", "项目一资料", "text/plain", 1, 1, "1".repeat(64)))
            dao.insertKnowledgeDocument(KnowledgeDocumentEntity("p2-doc", "p2", "项目二资料", "text/plain", 1, 1, "2".repeat(64)))
            dao.insertKnowledgeChunks(listOf(
                KnowledgeChunkEntity("p1-chunk", "p1-doc", "p1", 0, "SQLite 私有知识库定位资料"),
                KnowledgeChunkEntity("p2-chunk", "p2-doc", "p2", 0, "SQLite 公开知识库定位资料")
            ))
            dao.insertKnowledgeChunkFts(listOf(
                KnowledgeChunkFtsEntity("p1-chunk".stableFtsRowId(), "p1-chunk", "p1", "SQLite 私有知识库定位资料"),
                KnowledgeChunkFtsEntity("p2-chunk".stableFtsRowId(), "p2-chunk", "p2", "SQLite 公开知识库定位资料")
            ))

            val p1English = KnowledgeStore.search(dao, "p1", "SQLite", 6)
            val p2English = KnowledgeStore.search(dao, "p2", "SQLite", 6)
            assertEquals(listOf("项目一资料"), p1English.map { it.documentName })
            assertEquals(listOf("项目二资料"), p2English.map { it.documentName })

            val p1Chinese = KnowledgeStore.search(dao, "p1", "私有知识库定位", 6)
            assertEquals(listOf("项目一资料"), p1Chinese.map { it.documentName })
        } finally {
            db.close()
        }
    }

    @Test fun candidateWindowSamplesLaterChunksInsteadOfOnlyEarlyPositions() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            dao.insertKnowledgeDocument(KnowledgeDocumentEntity("sample-doc", "p1", "长文档", "text/plain", 1, 1, "d".repeat(64)))
            dao.insertKnowledgeChunks((0..250).map { position ->
                KnowledgeChunkEntity("sample-$position", "sample-doc", "p1", position, "共同关键词 命中位置 $position")
            })
            val candidates = dao.searchKnowledgeChunks("p1", "共同关键词", 200)
            assertEquals(200, candidates.size)
            assertTrue("候选窗口应覆盖文档后部片段", candidates.any { it.position >= 230 })
        } finally {
            db.close()
        }
    }

    @Test fun duplicateDocumentHashIsIgnoredWithoutReplacingItsChunks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            val original = KnowledgeDocumentEntity("original-doc", "p1", "原文", "text/plain", 1, 1, "e".repeat(64))
            val duplicate = KnowledgeDocumentEntity("duplicate-doc", "p1", "重复文档", "text/plain", 1, 2, "e".repeat(64))
            val saved = dao.insertKnowledgeBundleIfAbsent(original, listOf(KnowledgeChunkEntity("original-chunk", "original-doc", "p1", 0, "原始内容")))
            val ignored = dao.insertKnowledgeBundleIfAbsent(duplicate, listOf(KnowledgeChunkEntity("duplicate-chunk", "duplicate-doc", "p1", 0, "错误覆盖")))
            assertEquals("original-doc", saved.id)
            assertEquals("original-doc", ignored.id)
            assertEquals(listOf("原始内容"), dao.allKnowledgeChunks().map { it.content })
        } finally {
            db.close()
        }
    }

    @Test fun deletingKnowledgeDocumentRemovesItsFullTextIndexRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.dao()
            dao.putProjects(listOf(ProjectEntity("p1", "项目一", "", 1L)))
            val document = KnowledgeDocumentEntity("doc-delete", "p1", "待删除文档", "text/plain", 12L, 1L, "c".repeat(64))
            val chunk = KnowledgeChunkEntity("chunk-delete", document.id, "p1", 0, "deleteindexmarker searchable content")
            dao.insertKnowledgeDocument(document)
            dao.insertKnowledgeChunks(listOf(chunk))
            dao.insertKnowledgeChunkFts(listOf(KnowledgeChunkFtsEntity(chunk.id.stableFtsRowId(), chunk.id, chunk.projectId, chunk.content)))

            val before = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM knowledge_chunks_fts")
            assertEquals(1, before.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 })

            dao.deleteKnowledgeDocumentAndIndex(document.id, "p1")

            val after = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM knowledge_chunks_fts")
            assertEquals(0, after.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 })
            assertEquals(emptyList<KnowledgeChunkEntity>(), dao.searchKnowledgeChunksFts("p1", "deleteindexmarker*", 5))
        } finally {
            db.close()
        }
    }

    @Test fun migratesVersionOneDatabaseThroughVersionFifteenWithoutLosingConversationData() = runBlocking {
        context.deleteDatabase(databaseName)
        val legacy = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        legacy.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, updatedAt INTEGER NOT NULL, projectId TEXT NOT NULL)")
        legacy.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, position INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        legacy.execSQL("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
        legacy.execSQL("CREATE INDEX index_messages_conversationId_position ON messages(conversationId, position)")
        legacy.execSQL("CREATE TABLE projects (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, instructions TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        legacy.execSQL("CREATE TABLE user_memories (id TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, category TEXT NOT NULL, content TEXT NOT NULL, fingerprint TEXT NOT NULL, sourceConversationId TEXT NOT NULL, sourceMessageId TEXT NOT NULL, confidence REAL NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        legacy.execSQL("INSERT INTO projects VALUES ('default', '默认空间', '', 1)")
        legacy.execSQL("INSERT INTO conversations VALUES ('c1', '旧会话', 100, 'default')")
        legacy.execSQL("INSERT INTO messages VALUES ('m1', 'c1', 0, 'user', '迁移前内容')")
        legacy.execSQL("INSERT INTO user_memories VALUES ('mem-before', 'default', 'preference', '迁移前记忆内容', 'fp-before', 'c1', 'm1', 0.9, 1, 1)")
        legacy.version = 1
        legacy.close()

        val migrated = Room.databaseBuilder(context, NestroDatabase::class.java, databaseName)
            .addMigrations(*NestroDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val rows = migrated.dao().loadConversations()
            val versionCursor = migrated.openHelper.writableDatabase.query("PRAGMA user_version")
            val schemaVersion = versionCursor.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 }
            assertEquals(15, schemaVersion)
            assertEquals(1, rows.size)
            assertEquals("旧会话", rows.single().conversation.title)
            assertEquals("迁移前内容", rows.single().messages.single().content)
            assertEquals("[]", rows.single().messages.single().versionsJson)
            assertEquals(0, rows.single().messages.single().activeVersion)
            assertNotNull(migrated.dao().allKnowledgeDocuments())
            val embeddingTable = migrated.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='knowledge_chunk_embeddings'"
            )
            assertEquals(true, embeddingTable.use { it.moveToFirst() })
            migrated.dao().insertKnowledgeDocument(KnowledgeDocumentEntity("doc-fts", "default", "FTS 文档", "text/plain", 10, 2, "b".repeat(64)))
            val ftsChunk = KnowledgeChunkEntity("chunk-fts", "doc-fts", "default", 0, "统一调度 虚拟屏 工具权限")
            migrated.dao().insertKnowledgeChunks(listOf(ftsChunk))
            migrated.dao().insertKnowledgeChunkFts(listOf(KnowledgeChunkFtsEntity(ftsChunk.id.stableFtsRowId(), ftsChunk.id, ftsChunk.projectId, ftsChunk.content)))
            assertEquals("chunk-fts", migrated.dao().searchKnowledgeChunksFts("default", "虚拟屏*", 5).single().id)
            assertEquals(0L, migrated.dao().loadProjects().single { it.id == "default" }.archivedAt)
            migrated.dao().putUserMemory(UserMemoryEntity("mem1", "default", "fact", "保留的记忆", "fp1", "c1", "m1", 0.9, 1, 1))
            assertEquals(1, migrated.dao().allUserMemories().size)
            assertEquals(false, migrated.dao().userMemories("default").single().shareAcrossProjects)
            assertEquals(1, migrated.dao().setUserMemoryShareAcrossProjects("mem-before", "default", true, 2L))
            assertEquals(true, migrated.dao().userMemories("default").single().shareAcrossProjects)
            migrated.dao().putAgentTaskState(AgentTaskStateEntity(
                "agent-1", "phone_agent", "PAUSED", 2, "{checkpoint}", "", 2,
                projectId = "project-a", conversationId = "conversation-a", kind = "phone",
                capabilityId = "phone_agent.execute", title = "手机任务", input = "查找资料",
                idempotencyKey = "project-a:phone:request-1", maxRetries = 2
            ))
            assertEquals("PAUSED", migrated.dao().agentTaskState("agent-1")?.status)
            assertEquals("{checkpoint}", migrated.dao().agentTaskState("agent-1")?.checkpoint)
            assertEquals("project-a", migrated.dao().agentTaskState("agent-1")?.projectId)
            assertEquals("project-a:phone:request-1", migrated.dao().agentTaskStateByIdempotencyKey("project-a:phone:request-1")?.idempotencyKey)
            val duplicateState = migrated.dao().putAgentTaskStateIfAbsent(
                AgentTaskStateEntity(
                    "agent-duplicate", "phone_agent", "QUEUED", 0, "", "", 4,
                    idempotencyKey = "project-a:phone:request-1"
                )
            )
            assertEquals("agent-1", duplicateState.taskId)
            migrated.dao().insertAgentTaskTrace(AgentTaskTraceEntity("trace-1", "agent-1", "tool_succeeded", "ok", 3))
            assertEquals("tool_succeeded", migrated.dao().recentAgentTaskTraces("agent-1").single().event)
        } finally {
            migrated.close()
        }
    }
}
