package app.nestro.ui

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nestro.data.ConversationEntity
import app.nestro.data.KnowledgeChunkEntity
import app.nestro.data.KnowledgeStore
import app.nestro.data.KnowledgeDocumentEntity
import app.nestro.data.LocalBackupCompatibility
import app.nestro.data.MessageEntity
import app.nestro.data.NestroDatabase
import app.nestro.data.ProjectEntity
import app.nestro.data.UserMemoryEntity
import app.nestro.data.UserMemoryStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            .addCallback(NestroDatabase.KNOWLEDGE_FTS_CALLBACK)
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
                ProjectEntity("p1", "测试项目", "项目指令", 2L),
                ProjectEntity("p2", "隔离测试项目", "", 3L)
            ))
            dao.putConversation(ConversationEntity("c1", "备份测试会话", 3L, "p1"))
            dao.putConversation(ConversationEntity("c2", "另一条会话", 4L, "p1"))
            dao.putMessages(listOf(
                MessageEntity("m1", "c1", 0, "user", "保留这条消息", "[]", 0),
                MessageEntity("m2", "c2", 0, "user", "另一条会话的消息", "[]", 0)
            ))
            val hash = LocalBackupCompatibility.sha256("doc")
            dao.insertKnowledgeDocument(KnowledgeDocumentEntity("d1", "p1", "资料.txt", "text/plain", 3L, 4L, hash))
            dao.insertKnowledgeChunks(listOf(KnowledgeChunkEntity("k1", "d1", "p1", 0, "这是需要恢复的知识库片段内容")))
            dao.putUserMemory(UserMemoryEntity("mem1", "p1", "preference", "偏好使用简洁中文表达", UserMemoryStore.fingerprintFor("preference|偏好使用简洁中文表达"), "c1", "m1", 0.9, 5L, 6L))
            dao.putUserMemory(UserMemoryEntity("mem2", "p1", "preference", "授权共享的偏好记忆用于其他项目检索", UserMemoryStore.fingerprintFor("preference|授权共享的偏好记忆用于其他项目检索"), "c1", "m1", 0.95, 5L, 7L, true))
            assertTrue("备份前应能创建无来源会话的手动记忆", UserMemoryStore.saveManual(dao, "p2", "这个项目默认采用本地优先的记忆检索策略", "constraint"))

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
            assertTrue("恢复必须保留非默认项目", dao.loadProjects().any { it.id == "p1" })
            assertEquals("这是需要恢复的知识库片段内容", dao.allKnowledgeChunks().single().content)
            val restoredHits = KnowledgeStore.search(dao, "p1", "需要恢复的知识库", 6)
            assertTrue("恢复后必须能通过重建/同步的检索索引命中文档", restoredHits.any { it.content.contains("这是需要恢复的知识库片段内容") })
            assertEquals(3, dao.allUserMemories().size)
            val restoredPreference = dao.allUserMemories().first { it.id == "mem1" }
            assertEquals("偏好使用简洁中文表达", restoredPreference.content)
            assertEquals("c1", restoredPreference.sourceConversationId)
            assertEquals("m1", restoredPreference.sourceMessageId)
            assertFalse(restoredPreference.shareAcrossProjects)
            assertTrue(dao.allUserMemories().first { it.id == "mem2" }.shareAcrossProjects)
            val localProjectRecall = UserMemoryStore.recall(dao, "p1", "简洁中文表达").memories.map { it.id }
            assertTrue("恢复后的本项目记忆必须可以正常召回", "mem1" in localProjectRecall)
            val otherProjectRecall = UserMemoryStore.recall(dao, "p2", "授权共享偏好记忆").memories.map { it.id }
            assertTrue("明确授权的记忆应能跨项目召回", "mem2" in otherProjectRecall)
            assertFalse("未授权的项目记忆不得跨项目召回", "mem1" in otherProjectRecall)
            val restoredManualMemory = dao.userMemories("p2").single()
            assertEquals("constraint", restoredManualMemory.category)
            assertEquals("", restoredManualMemory.sourceConversationId)
            assertFalse("恢复后同内容手动记忆应识别重复", UserMemoryStore.saveManual(dao, "p2", "这个项目默认采用本地优先的记忆检索策略", "constraint"))
            assertTrue(prefs.getBoolean("automatic_memory_enabled", false))
            assertEquals(1.2f, prefs.getFloat("tts_speech_rate", 1.0f), 0.001f)
            assertEquals("TASK_START", JSONArray(prefs.getString("phone_audit", "[]")).getJSONObject(0).getString("action"))
            assertEquals("恢复后继续写的草稿", prefs.getString("draft_c1", ""))
            assertEquals("始终使用简洁中文", prefs.getString("global_memory_v1", ""))

            val tamperedBackup = backup.replace("保留这条消息", "被篡改的消息")
            assertTrue("篡改后的备份必须被拒绝", runCatching {
                restoreLocalBackup(context, prefs, tamperedBackup, database)
            }.isFailure)
            val checksumMissingBackup = JSONObject(backup).apply { remove("checksum_sha256") }.toString()
            assertTrue("当前版本缺少校验和的备份必须被拒绝", runCatching {
                restoreLocalBackup(context, prefs, checksumMissingBackup, database)
            }.isFailure)
            val invalidMemorySourceBackup = JSONObject(backup).apply {
                val rows = getJSONArray("user_memories")
                rows.getJSONObject(0).put("sourceMessageId", "m2")
                remove("checksum_sha256")
                put("checksum_sha256", LocalBackupCompatibility.sha256(LocalBackupCompatibility.canonicalJson(this)))
            }.toString()
            assertTrue("来源消息与来源会话不匹配的记忆必须被拒绝", runCatching {
                restoreLocalBackup(context, prefs, invalidMemorySourceBackup, database)
            }.isFailure)
            val incompleteMemorySourceBackup = JSONObject(backup).apply {
                val rows = getJSONArray("user_memories")
                rows.getJSONObject(0).put("sourceConversationId", "")
                remove("checksum_sha256")
                put("checksum_sha256", LocalBackupCompatibility.sha256(LocalBackupCompatibility.canonicalJson(this)))
            }.toString()
            assertTrue("来源会话与消息不得只提供其中一个", runCatching {
                restoreLocalBackup(context, prefs, incompleteMemorySourceBackup, database)
            }.isFailure)
            assertEquals("校验失败不得破坏现有数据", "保留这条消息", dao.loadConversations().single().messages.single().content)
        } finally {
            database.close()
            prefs.edit().clear().commit()
            context.deleteDatabase(databaseName)
        }
    }
}
