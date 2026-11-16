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


    @Test fun freshDatabaseKeepsFtsIndexSynchronizedWithKnowledgeChunks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .addCallback(NestroDatabase.KNOWLEDGE_FTS_CALLBACK)
            .allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            dao.insertKnowledgeDocument(KnowledgeDocumentEntity("fresh-doc", "p1", "Fresh", "text/plain", 1, 1, "c".repeat(64)))
            dao.insertKnowledgeChunks(listOf(KnowledgeChunkEntity("fresh-chunk", "fresh-doc", "p1", 0, "SQLite offline retrieval")))
            assertEquals("fresh-chunk", dao.searchKnowledgeChunksFts("p1", "\"sqlite\"", 10).single().id)
            dao.deleteKnowledgeDocument("fresh-doc", "p1")
            assertEquals(emptyList<KnowledgeChunkEntity>(), dao.searchKnowledgeChunksFts("p1", "\"sqlite\"", 10))
        } finally {
            db.close()
        }
    }

    @Test fun knowledgeSearchIsolatedByProjectForFtsAndChineseFallback() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .addCallback(NestroDatabase.KNOWLEDGE_FTS_CALLBACK)
            .allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            dao.insertKnowledgeDocument(KnowledgeDocumentEntity("p1-doc", "p1", "项目一资料", "text/plain", 1, 1, "1".repeat(64)))
            dao.insertKnowledgeDocument(KnowledgeDocumentEntity("p2-doc", "p2", "项目二资料", "text/plain", 1, 1, "2".repeat(64)))
            dao.insertKnowledgeChunks(listOf(
                KnowledgeChunkEntity("p1-chunk", "p1-doc", "p1", 0, "SQLite 私有知识库定位资料"),
                KnowledgeChunkEntity("p2-chunk", "p2-doc", "p2", 0, "SQLite 公开知识库定位资料")
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
            .addCallback(NestroDatabase.KNOWLEDGE_FTS_CALLBACK)
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
            .addCallback(NestroDatabase.KNOWLEDGE_FTS_CALLBACK)
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

    @Test fun projectDeletionGuardChecksEveryProjectOwnedDataFamily() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .addCallback(NestroDatabase.KNOWLEDGE_FTS_CALLBACK)
            .allowMainThreadQueries().build()
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

    @Test fun memorySharingRequiresExplicitOwnerAuthorization() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            dao.putProjects(listOf(ProjectEntity("p1", "项目一", "", 1L), ProjectEntity("p2", "项目二", "", 2L)))
            dao.putUserMemory(UserMemoryEntity("private", "p1", "preference", "项目一私有记忆", "fp-private", "c1", "m1", 0.9, 1, 1))
            dao.putUserMemory(UserMemoryEntity("shared", "p1", "constraint", "项目一授权共享记忆", "fp-shared", "c1", "m2", 0.9, 2, 2, true))
            assertEquals(listOf("shared"), dao.userMemoriesVisible("p2").map { it.id })
            assertEquals(setOf("private", "shared"), dao.userMemoriesVisible("p1").map { it.id }.toSet())
            assertEquals(1, dao.setUserMemorySharing("shared", "p1", false, 3))
            assertEquals(emptyList<UserMemoryEntity>(), dao.userMemoriesVisible("p2"))
            assertEquals(1, dao.setUserMemorySharing("shared", "p1", true, 4))
            repeat(200) { index ->
                dao.putUserMemory(UserMemoryEntity("local-$index", "p2", "preference", "项目二私有记忆内容 $index", "fp-local-$index", "c2", "m2", 0.9, index.toLong(), index.toLong()))
            }
            val visibleInManager = UserMemoryStore.list(dao, "p2").map { it.id }
            assertTrue("本项目已有 200 条记忆时，管理页仍须显示授权共享记忆", "shared" in visibleInManager)
            val recalledShared = UserMemoryStore.recall(dao, "p2", "授权共享记忆").memories.map { it.id }
            assertTrue("本项目记忆达到上限时仍应召回明确授权的共享记忆", "shared" in recalledShared)
        } finally {
            db.close()
        }
    }

    @Test fun memoryUpsertKeepsIdentityAndSharingWhileUpdatingContent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            dao.putUserMemory(UserMemoryEntity("stable-id", "p1", "preference", "旧的偏好内容需要更新", "fp-old", "", "", 0.9, 10, 10, true))
            val updated = dao.upsertUserMemoryByFingerprint(UserMemoryEntity("new-id", "p1", "preference", "新的偏好内容需要更新", "fp-new", "c1", "m1", 0.95, 20, 20, false))
            assertEquals("stable-id", updated.id)
            assertEquals(10L, updated.createdAt)
            assertTrue(updated.shareAcrossProjects)
            assertEquals("新的偏好内容需要更新", dao.userMemories("p1").single().content)
            assertEquals("fp-new", dao.userMemories("p1").single().fingerprint)
        } finally {
            db.close()
        }
    }

    @Test fun migratesVersionOneDatabaseThroughVersionElevenWithoutLosingConversationData() = runBlocking {
        context.deleteDatabase(databaseName)
        val legacy = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        legacy.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, updatedAt INTEGER NOT NULL, projectId TEXT NOT NULL)")
        legacy.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, position INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        legacy.execSQL("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
        legacy.execSQL("CREATE INDEX index_messages_conversationId_position ON messages(conversationId, position)")
        legacy.execSQL("CREATE TABLE projects (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, instructions TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        legacy.execSQL("INSERT INTO projects VALUES ('default', '默认空间', '', 1)")
        legacy.execSQL("INSERT INTO conversations VALUES ('c1', '旧会话', 100, 'default')")
        legacy.execSQL("INSERT INTO messages VALUES ('m1', 'c1', 0, 'user', '迁移前内容')")
        legacy.version = 1
        legacy.close()

        val migrated = Room.databaseBuilder(context, NestroDatabase::class.java, databaseName)
            .addMigrations(*NestroDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val rows = migrated.dao().loadConversations()
            val versionCursor = migrated.openHelper.writableDatabase.query("PRAGMA user_version")
            val schemaVersion = versionCursor.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 }
            assertEquals(11, schemaVersion)
            assertEquals(1, rows.size)
            assertEquals("旧会话", rows.single().conversation.title)
            assertEquals("迁移前内容", rows.single().messages.single().content)
            assertEquals("[]", rows.single().messages.single().versionsJson)
            assertEquals(0, rows.single().messages.single().activeVersion)
            assertNotNull(migrated.dao().allKnowledgeDocuments())
            val indexCursor = migrated.openHelper.writableDatabase.query("PRAGMA index_list('knowledge_chunks')")
            val indexNames = indexCursor.use { cursor ->
                val names = mutableSetOf<String>()
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) names += cursor.getString(nameColumn)
                names
            }
            assertTrue(indexNames.contains("index_knowledge_chunks_documentId_position"))
            assertTrue(indexNames.contains("index_knowledge_chunks_projectId_position"))
            val memoryColumnsCursor = migrated.openHelper.writableDatabase.query("PRAGMA table_info('user_memories')")
            val memoryColumns = memoryColumnsCursor.use { cursor ->
                val names = mutableSetOf<String>()
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) names += cursor.getString(nameColumn)
                names
            }
            assertTrue(memoryColumns.contains("shareAcrossProjects"))
            val dao = migrated.dao()
            dao.insertKnowledgeDocument(KnowledgeDocumentEntity("fts-doc", "default", "FTS", "text/plain", 1, 2, "b".repeat(64)))
            dao.insertKnowledgeChunks(listOf(KnowledgeChunkEntity("fts-chunk", "fts-doc", "default", 0, "Android offline search index")))
            assertEquals("fts-chunk", dao.searchKnowledgeChunksFts("default", "\"android\"", 10).single().id)
            dao.deleteKnowledgeDocument("fts-doc", "default")
            assertEquals(emptyList<KnowledgeChunkEntity>(), dao.searchKnowledgeChunksFts("default", "\"android\"", 10))
            assertEquals(0L, migrated.dao().loadProjects().single { it.id == "default" }.archivedAt)
            migrated.dao().putUserMemory(UserMemoryEntity("mem1", "default", "fact", "保留的记忆", "fp1", "c1", "m1", 0.9, 1, 1))
            assertEquals(1, migrated.dao().allUserMemories().size)
        } finally {
            migrated.close()
        }
    }
}
