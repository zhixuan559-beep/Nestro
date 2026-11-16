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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NestroDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "nestro_migration_test.db"

    @After fun cleanUp() {
        context.deleteDatabase(databaseName)
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

    @Test fun migratesVersionOneDatabaseThroughVersionSevenWithoutLosingConversationData() = runBlocking {
        context.deleteDatabase(databaseName)
        val legacy = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        legacy.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, updatedAt INTEGER NOT NULL, projectId TEXT NOT NULL)")
        legacy.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, position INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        legacy.execSQL("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
        legacy.execSQL("CREATE INDEX index_messages_conversationId_position ON messages(conversationId, position)")
        legacy.execSQL("CREATE TABLE projects (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, instructions TEXT NOT NULL, createdAt INTEGER NOT NULL)")
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
            assertEquals(7, schemaVersion)
            assertEquals(1, rows.size)
            assertEquals("旧会话", rows.single().conversation.title)
            assertEquals("迁移前内容", rows.single().messages.single().content)
            assertEquals("[]", rows.single().messages.single().versionsJson)
            assertEquals(0, rows.single().messages.single().activeVersion)
            assertNotNull(migrated.dao().allKnowledgeDocuments())
            migrated.dao().putUserMemory(UserMemoryEntity("mem1", "default", "fact", "保留的记忆", "fp1", "c1", "m1", 0.9, 1, 1))
            assertEquals(1, migrated.dao().allUserMemories().size)
        } finally {
            migrated.close()
        }
    }
}
