package app.nestro.ui

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nestro.data.ConversationEntity
import app.nestro.data.KnowledgeChunkEntity
import app.nestro.data.KnowledgeDocumentEntity
import app.nestro.data.LocalBackupCompatibility
import app.nestro.data.MessageEntity
import app.nestro.data.NestroDatabase
import app.nestro.data.ProjectEntity
import app.nestro.data.UserMemoryEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalBackupRoundTripTest {
    @Test fun restoresRoomDataAndUserPreferencesFromVerifiedBackup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "nestro_backup_round_trip.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, NestroDatabase::class.java, databaseName)
            .addMigrations(*NestroDatabase.ALL_MIGRATIONS)
            .build()
        val prefs = context.getSharedPreferences("nestro_backup_round_trip", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear()
                .putString("messages", "[]")
                .putString("active_project_id", "p1")
                .putString("active_conversation_id", "c1")
                .putString("draft_c1", "恢复后继续写的草稿")
                .putString("global_memory_v1", "始终使用简洁中文")
                .putBoolean("automatic_memory_enabled", true)
                .putFloat("tts_speech_rate", 1.2f)
                .putString("phone_audit", JSONArray().put(JSONObject().put("at", 123L).put("action", "TASK_START").put("detail", "round trip")).toString())
                .commit()

            val dao = database.dao()
            dao.putProjects(listOf(
                ProjectEntity("default", "默认项目", "", 1L),
                ProjectEntity("p1", "测试项目", "项目指令", 2L)
            ))
            dao.putConversation(ConversationEntity("c1", "备份测试会话", 3L, "p1"))
            dao.putMessages(listOf(MessageEntity("m1", "c1", 0, "user", "保留这条消息", "[]", 0)))
            val hash = LocalBackupCompatibility.sha256("doc")
            dao.insertKnowledgeDocument(KnowledgeDocumentEntity("d1", "p1", "资料.txt", "text/plain", 3L, 4L, hash))
            dao.insertKnowledgeChunks(listOf(KnowledgeChunkEntity("k1", "d1", "p1", 0, "这是需要恢复的知识库片段内容")))
            dao.putUserMemory(UserMemoryEntity("mem1", "p1", "preference", "偏好使用简洁中文表达", "fp1", "c1", "m1", 0.9, 5L, 6L))

            val backup = buildLocalBackup(prefs, database)
            assertTrue(LocalBackupCompatibility.verifyChecksum(JSONObject(backup)))

            database.withTransaction {
                dao.deleteAllConversations()
                dao.deleteAllKnowledgeDocuments()
                dao.deleteAllUserMemories()
                dao.deleteAllPhoneAgentTasks()
                dao.deleteAllProjects()
            }
            prefs.edit().putBoolean("automatic_memory_enabled", false).putFloat("tts_speech_rate", 1.0f).putString("phone_audit", "[]").commit()

            restoreLocalBackup(context, prefs, backup, database)

            assertEquals("保留这条消息", dao.loadConversations().single().messages.single().content)
            assertEquals("这是需要恢复的知识库片段内容", dao.allKnowledgeChunks().single().content)
            assertEquals("偏好使用简洁中文表达", dao.allUserMemories().single().content)
            assertTrue(prefs.getBoolean("automatic_memory_enabled", false))
            assertEquals(1.2f, prefs.getFloat("tts_speech_rate", 1.0f), 0.001f)
            assertEquals("TASK_START", JSONArray(prefs.getString("phone_audit", "[]")).getJSONObject(0).getString("action"))
            assertEquals("恢复后继续写的草稿", prefs.getString("draft_c1", ""))
            assertEquals("始终使用简洁中文", prefs.getString("global_memory_v1", ""))

            val tamperedBackup = backup.replace("保留这条消息", "被篡改的消息")
            assertTrue("篡改后的备份必须被拒绝", runCatching {
                restoreLocalBackup(context, prefs, tamperedBackup, database)
            }.isFailure)
            assertEquals("校验失败不得破坏现有数据", "保留这条消息", dao.loadConversations().single().messages.single().content)
        } finally {
            database.close()
            prefs.edit().clear().commit()
            context.deleteDatabase(databaseName)
        }
    }
}
