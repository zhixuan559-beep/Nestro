package app.nestro.ui

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nestro.data.ArtifactStore
import app.nestro.data.ConversationEntity
import app.nestro.data.KnowledgeChunkEntity
import app.nestro.data.KnowledgeDocumentEntity
import app.nestro.data.LocalBackupCompatibility
import app.nestro.data.LocalBackupRepository
import app.nestro.data.persistLocalBackupRestoreJournal
import app.nestro.data.LocalVault
import app.nestro.data.ModelConfig
import app.nestro.data.MessageEntity
import app.nestro.data.NestroDatabase
import app.nestro.data.ProjectEntity
import app.nestro.data.UserMemoryEntity
import java.io.File
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
        // ArtifactStore uses app-private files shared by instrumentation tests. Start from a
        // clean store so deliberately corrupted fixtures from other tests cannot leak in.
        File(context.filesDir, "artifacts").deleteRecursively()
        LocalVault.clearAllApiKeys(context)
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

            LocalVault.writeApiKey(context, "old-key", "must-not-survive-restore")
            LocalVault.writeProfile(context, "old-profile", "legacy-secret-must-not-survive")
            LocalVault.write(context, ModelConfig("https://old.example/v1", "old-model", "active-secret-must-not-survive"))

            val artifactTitle = "备份往返测试-" + System.currentTimeMillis()
            val savedArtifact = ArtifactStore.save(context, "p1", artifactTitle, "# 产物备份内容")
            val backupRepository = LocalBackupRepository(context, database)
            val backup = backupRepository.createBackup(prefs)
            assertTrue(LocalBackupCompatibility.verifyChecksum(JSONObject(backup)))
            ArtifactStore.delete(context, savedArtifact)

            database.withTransaction {
                dao.deleteAllConversations()
                dao.deleteAllKnowledgeDocuments()
                dao.deleteAllUserMemories()
                dao.deleteAllPhoneAgentTasks()
                dao.deleteAllProjects()
            }
            prefs.edit().putBoolean("automatic_memory_enabled", false).putString("phone_audit", "[]").commit()

            backupRepository.restoreBackup(prefs, backup)

            assertEquals("保留这条消息", dao.loadConversations().single().messages.single().content)
            assertTrue("恢复必须保留非默认项目", dao.loadProjects().any { it.id == "p1" })
            assertEquals("这是需要恢复的知识库片段内容", dao.allKnowledgeChunks().single().content)
            assertEquals("偏好使用简洁中文表达", dao.allUserMemories().single().content)
            assertTrue(prefs.getBoolean("automatic_memory_enabled", false))
            assertEquals("TASK_START", JSONArray(prefs.getString("phone_audit", "[]")).getJSONObject(0).getString("action"))
            assertEquals("恢复后继续写的草稿", prefs.getString("draft_c1", ""))
            assertEquals("始终使用简洁中文", prefs.getString("global_memory_v1", ""))
            assertEquals("恢复不能沿用旧 API Key", "", LocalVault.readApiKey(context, "old-key"))
            assertEquals("恢复不能沿用旧 Profile Key", "", LocalVault.readProfile(context, "old-profile"))
            assertEquals("恢复不能沿用旧活动模型 Key", "", LocalVault.read(context).apiKey)
            val restoredArtifact = ArtifactStore.list(context, "p1").single { it.title == artifactTitle }
            assertEquals("# 产物备份内容", ArtifactStore.read(context, restoredArtifact))

            val tamperedBackup = backup.replace("保留这条消息", "被篡改的消息")
            assertTrue("篡改后的备份必须被拒绝", runCatching {
                backupRepository.restoreBackup(prefs, tamperedBackup)
            }.isFailure)
            val checksumMissingBackup = JSONObject(backup).apply { remove("checksum_sha256") }.toString()
            assertTrue("当前版本缺少校验和的备份必须被拒绝", runCatching {
                backupRepository.restoreBackup(prefs, checksumMissingBackup)
            }.isFailure)
            assertEquals("校验失败不得破坏现有数据", "保留这条消息", dao.loadConversations().single().messages.single().content)
        } finally {
            File(context.filesDir, "artifacts").deleteRecursively()
            File(context.filesDir, "backup-restore").deleteRecursively()
            LocalVault.clearAllApiKeys(context)
            database.close()
            prefs.edit().clear().commit()
            context.deleteDatabase(databaseName)
        }
    }
    @Test fun interruptedRestoreJournalRecoversRoomPreferencesAndEncryptedKeys() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "artifacts").deleteRecursively()
        LocalVault.clearAllApiKeys(context)
        val databaseName = "nestro_backup_journal_recovery.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, NestroDatabase::class.java, databaseName)
            .addMigrations(*NestroDatabase.ALL_MIGRATIONS)
            .build()
        val prefs = context.getSharedPreferences("nestro_backup_journal_test", Context.MODE_PRIVATE)
        val vaultPrefs = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putString("messages", "[]").putString("global_memory_v1", "恢复前的记忆")
                .putBoolean("automatic_memory_enabled", true).putString("draft_old", "旧草稿").commit()
            LocalVault.writeApiKey(context, "journal-key", "encrypted-old-secret")
            val oldArtifact = ArtifactStore.save(context, "default", "journal-old-artifact", "# 恢复前的作品")
            val dao = database.dao()
            dao.putProjects(listOf(ProjectEntity("default", "默认项目", "", 1L)))
            dao.putConversation(ConversationEntity("journal-c1", "恢复日志测试", 1L, "default"))
            dao.putMessages(listOf(MessageEntity("journal-m1", "journal-c1", 0, "user", "恢复前消息", "[]", 0)))
            val repository = LocalBackupRepository(context, database)
            persistLocalBackupRestoreJournal(context, prefs, database)
            database.withTransaction {
                dao.deleteAllConversations()
                dao.deleteAllKnowledgeDocuments()
                dao.deleteAllUserMemories()
                dao.deleteAllPhoneAgentTasks()
                dao.deleteAllProjects()
            }
            prefs.edit().clear().putString("global_memory_v1", "错误的新状态").commit()
            LocalVault.clearAllApiKeys(context)
            ArtifactStore.delete(context, oldArtifact)
            repository.recoverInterruptedRestore(prefs)
            assertEquals("恢复前消息", dao.loadConversations().single().messages.single().content)
            assertEquals("恢复前的记忆", prefs.getString("global_memory_v1", ""))
            assertTrue(prefs.getBoolean("automatic_memory_enabled", false))
            assertEquals("旧草稿", prefs.getString("draft_old", ""))
            assertEquals("encrypted-old-secret", LocalVault.readApiKey(context, "journal-key"))
            val recoveredArtifact = ArtifactStore.list(context, "default").single { it.title == "journal-old-artifact" }
            assertEquals("# 恢复前的作品", ArtifactStore.read(context, recoveredArtifact))
            assertTrue(!File(context.filesDir, "backup-restore/restore-journal.json").exists())
        } finally {
            database.close()
            prefs.edit().clear().commit()
            vaultPrefs.edit().clear().commit()
            File(context.filesDir, "artifacts").deleteRecursively()
            File(context.filesDir, "backup-restore").deleteRecursively()
            LocalVault.clearAllApiKeys(context)
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun backupExportRecoversInterruptedRestoreBeforeTakingSnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "artifacts").deleteRecursively()
        File(context.filesDir, "backup-restore").deleteRecursively()
        val databaseName = "nestro_backup_export_recovery.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, NestroDatabase::class.java, databaseName)
            .addMigrations(*NestroDatabase.ALL_MIGRATIONS)
            .build()
        val prefs = context.getSharedPreferences("nestro_backup_export_recovery", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putString("messages", "[]")
                .putString("global_memory_v1", "导出前应保留的原始记忆").commit()
            val dao = database.dao()
            dao.putProjects(listOf(ProjectEntity("default", "默认项目", "", 1L)))
            dao.putConversation(ConversationEntity("export-c1", "导出恢复测试", 1L, "default"))
            dao.putMessages(listOf(MessageEntity("export-m1", "export-c1", 0, "user", "原始消息", "[]", 0)))
            val repository = LocalBackupRepository(context, database)
            persistLocalBackupRestoreJournal(context, prefs, database)
            database.withTransaction {
                dao.deleteAllConversations()
                dao.deleteAllProjects()
            }
            prefs.edit().putString("global_memory_v1", "错误的部分恢复状态").commit()

            val exported = repository.createBackup(prefs)

            val exportedRoot = JSONObject(exported)
            assertTrue(LocalBackupCompatibility.verifyChecksum(exportedRoot))
            assertEquals(
                "原始消息",
                exportedRoot.getJSONArray("conversations_v1").getJSONObject(0)
                    .getJSONArray("messages").getJSONObject(0).getString("content")
            )
            assertEquals("原始消息", dao.loadConversations().single().messages.single().content)
            assertEquals("导出前应保留的原始记忆", prefs.getString("global_memory_v1", ""))
            assertTrue(!File(context.filesDir, "backup-restore/restore-journal.json").exists())
        } finally {
            database.close()
            prefs.edit().clear().commit()
            File(context.filesDir, "artifacts").deleteRecursively()
            File(context.filesDir, "backup-restore").deleteRecursively()
            context.deleteDatabase(databaseName)
        }
    }

}
