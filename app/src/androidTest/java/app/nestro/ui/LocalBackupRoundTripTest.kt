package app.nestro.ui

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nestro.data.ArtifactStore
import app.nestro.data.AgentTaskStateEntity
import app.nestro.data.AgentTaskTraceEntity
import app.nestro.data.ConversationEntity
import app.nestro.data.KnowledgeChunkEntity
import app.nestro.data.KnowledgeDocumentEntity
import app.nestro.data.LocalBackupCompatibility
import app.nestro.data.LocalBackupRepository
import app.nestro.data.ChatRequestStateStore
import app.nestro.data.persistLocalBackupRestoreJournal
import app.nestro.data.LocalVault
import app.nestro.data.ModelConfig
import app.nestro.data.ModelProfileCatalogCodec
import app.nestro.data.ModelProfileMetadata
import app.nestro.model.ModelRegistryCodec
import app.nestro.data.MessageEntity
import app.nestro.data.NestroDatabase
import app.nestro.data.ProjectEntity
import app.nestro.data.PhoneAgentTaskEntity
import app.nestro.data.PhoneAgentTaskStore
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
    @Test fun backupExportPreservesLegacyConversationsWhenRoomIsEmpty() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "nestro_backup_legacy_conversations.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, NestroDatabase::class.java, databaseName)
            .addMigrations(*NestroDatabase.ALL_MIGRATIONS)
            .build()
        val prefs = context.getSharedPreferences("nestro_backup_legacy_conversations", Context.MODE_PRIVATE)
        try {
            val legacyMessages = JSONArray().put(
                JSONObject().put("id", "legacy-m1").put("role", "user").put("content", "来自旧存储的消息")
                    .put("versions", JSONArray()).put("active_version", 0)
            )
            val legacyConversations = JSONArray().put(
                JSONObject().put("id", "legacy-c1").put("title", "旧存储会话")
                    .put("updated_at", 10L).put("project_id", "default").put("messages", legacyMessages)
            )
            prefs.edit().clear().putString("messages", "[]")
                .putString("conversations_v1", legacyConversations.toString()).commit()

            val backup = LocalBackupRepository(context, database).createBackup(prefs)
            val restoredConversations = JSONObject(backup).getJSONArray("conversations_v1")
            assertEquals("legacy-c1", restoredConversations.getJSONObject(0).getString("id"))
            assertEquals(
                "来自旧存储的消息",
                restoredConversations.getJSONObject(0).getJSONArray("messages").getJSONObject(0).getString("content")
            )
        } finally {
            database.close()
            prefs.edit().clear().commit()
            File(context.filesDir, "artifacts").deleteRecursively()
            File(context.filesDir, "backup-restore").deleteRecursively()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun backupExportRejectsCorruptRevisionAndAuditData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "nestro_backup_corrupt_source.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, NestroDatabase::class.java, databaseName)
            .addMigrations(*NestroDatabase.ALL_MIGRATIONS)
            .build()
        val prefs = context.getSharedPreferences("nestro_backup_corrupt_source", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putString("messages", "[]").commit()
            val dao = database.dao()
            dao.putProjects(listOf(ProjectEntity("default", "默认项目", "", 1L)))
            dao.putConversation(ConversationEntity("corrupt-c1", "损坏数据测试", 1L, "default"))
            dao.putMessages(listOf(MessageEntity("corrupt-m1", "corrupt-c1", 0, "assistant", "回复", "{invalid", 0)))
            val repository = LocalBackupRepository(context, database)

            assertTrue("损坏的回答版本记录不得被静默丢弃", runCatching { repository.createBackup(prefs) }.isFailure)

            dao.putMessages(listOf(MessageEntity("corrupt-m1", "corrupt-c1", 0, "assistant", "回复", "[]", 0)))
            prefs.edit().putString("phone_audit", "{invalid").commit()
            assertTrue("损坏的安全审计记录不得被静默清空", runCatching { repository.createBackup(prefs) }.isFailure)

            prefs.edit().putString("phone_audit", JSONArray().put("invalid-row").toString()).commit()
            assertTrue("无效的审计条目不得被跳过", runCatching { repository.createBackup(prefs) }.isFailure)

            val oversizedAudit = JSONArray()
            repeat(101) { index ->
                oversizedAudit.put(JSONObject().put("at", index.toLong()).put("action", "TASK_STEP").put("detail", "审计记录 $index"))
            }
            prefs.edit().putString("phone_audit", oversizedAudit.toString()).commit()
            assertTrue("导出不得静默丢弃超过 100 条的旧审计记录", runCatching { repository.createBackup(prefs) }.isFailure)

            prefs.edit().putString("phone_audit", "[]").commit()
            dao.putMessages(listOf(MessageEntity("corrupt-m1", "corrupt-c1", 0, "assistant", "回复", "[1]", 0)))
            assertTrue("非字符串回答版本不得被静默写入备份", runCatching { repository.createBackup(prefs) }.isFailure)
        } finally {
            database.close()
            prefs.edit().clear().commit()
            File(context.filesDir, "artifacts").deleteRecursively()
            File(context.filesDir, "backup-restore").deleteRecursively()
            context.deleteDatabase(databaseName)
        }
    }

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
                .putString("reasoning_effort_v1", "high")
                .putFloat("tts_speech_rate", 1.2f)
                .putBoolean("chat_task_mode_enabled", true)
                .putString("phone_audit", JSONArray().put(JSONObject().put("at", 123L).put("action", "TASK_START").put("detail", "round trip")).toString())
                .commit()
            val backupProfile = ModelProfileMetadata("backup-profile", "备份模型", "https://api.example/v1", "example-model")
            val backupRegistry = ModelRegistryCodec.migrateLegacy(listOf(backupProfile))
            prefs.edit()
                .putString("model_profiles_v1", ModelProfileCatalogCodec.encode(listOf(backupProfile)))
                .putString("model_registry_v1", ModelRegistryCodec.encode(backupRegistry))
                .putString("active_model_registry_id", "legacy-model-backup-profile")
                .putString("active_model_profile_id", "")
                .putString("base_url", "https://api.example/v1")
                .putString("model", "example-model")
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
            dao.insertKnowledgeChunks(listOf(KnowledgeChunkEntity("k1", "d1", "p1", 0, "这是需要恢复的知识库片段内容 restoreindexmarker")))
            dao.putUserMemory(UserMemoryEntity("mem1", "p1", "preference", "偏好使用简洁中文表达", "fp1", "c1", "m1", 0.9, 5L, 6L))
            dao.putPhoneAgentTask(PhoneAgentTaskEntity(
                "phone-task-1", "p1", "c1", "恢复后由用户手动继续", "GENERAL",
                PhoneAgentTaskStore.STATUS_RUNNING, "[]", 0, 7L, 8L
            ))
            dao.putAgentTaskState(AgentTaskStateEntity(
                "phone-task-1", "phone_agent", "RUNNING", 0, "检查点数据", "", 7L
            ))
            dao.insertAgentTaskTrace(AgentTaskTraceEntity(
                "agent-trace-1", "phone-task-1", "checkpoint_saved", "已保存检查点", 8L
            ))

            LocalVault.writeApiKey(context, "old-key", "must-not-survive-restore")
            LocalVault.writeProfile(context, "old-profile", "legacy-secret-must-not-survive")
            LocalVault.write(context, ModelConfig("https://old.example/v1", "old-model", "active-secret-must-not-survive"))

            val artifactTitle = "备份往返测试-" + System.currentTimeMillis()
            val savedArtifact = ArtifactStore.save(context, "p1", artifactTitle, "# 产物备份内容")
            val backupRepository = LocalBackupRepository(context, database)
            val backup = backupRepository.createBackup(prefs)
            assertTrue(LocalBackupCompatibility.verifyChecksum(JSONObject(backup)))
            assertTrue("外部备份不得包含任何 API Key 明文", !backup.contains("must-not-survive-restore"))
            assertTrue("外部备份不得包含旧 Profile Key 明文", !backup.contains("legacy-secret-must-not-survive"))
            assertTrue("外部备份不得包含活动模型 Key 明文", !backup.contains("active-secret-must-not-survive"))
            ArtifactStore.delete(context, savedArtifact)

            database.withTransaction {
                dao.deleteAllConversations()
                dao.deleteAllKnowledgeDocuments()
                dao.deleteAllUserMemories()
                dao.deleteAllPhoneAgentTasks()
                dao.deleteAllAgentTaskStates()
                dao.deleteAllAgentTaskTraces()
                dao.deleteAllProjects()
            }
            // Simulate an orphaned FTS row left by an older/partial restore.
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO knowledge_chunks_fts(rowid, chunkId, projectId, content) VALUES(?, ?, ?, ?)",
                arrayOf(987654321L, "stale-chunk", "p1", "staleindexmarker")
            )
            prefs.edit().putBoolean("automatic_memory_enabled", false).putString("reasoning_effort_v1", "low")
                .putFloat("tts_speech_rate", 1.0f).putString("phone_audit", "[]")
                .putString("pending_project_delete_id", "stale-device-project")
                .putLong("phone_task_grant_until", System.currentTimeMillis() + 60_000L)
                .putBoolean("phone_single_grant", true)
                .putBoolean("phone_task_stop_requested", true)
                .putBoolean("phone_agent_work_pending_old-task", true)
                .putLong("phone_agent_work_pending_at_old-task", 1L)
                .putBoolean("phone_agent_after_handoff_old-task", true)
                .putBoolean("phone_agent_allow_retry_old-task", true)
                .putBoolean("phone_agent_cancel_requested_old-task", true).commit()
            ChatRequestStateStore.save(prefs, "stale-request", "stale-conversation", "stale-project", "旧请求", "旧部分回答", 1L)

            backupRepository.restoreBackup(prefs, backup)

            assertEquals("保留这条消息", dao.loadConversations().single().messages.single().content)
            assertTrue("恢复必须保留非默认项目", dao.loadProjects().any { it.id == "p1" })
            assertEquals("这是需要恢复的知识库片段内容 restoreindexmarker", dao.allKnowledgeChunks().single().content)
            assertEquals("k1", dao.searchKnowledgeChunksFts("p1", "restoreindexmarker", 5).single().id)
            val ftsCount = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM knowledge_chunks_fts")
            assertEquals(1, ftsCount.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 })
            assertEquals("偏好使用简洁中文表达", dao.allUserMemories().single().content)
            assertEquals("检查点数据", dao.agentTaskState("phone-task-1")?.checkpoint)
            assertEquals("PAUSED", dao.agentTaskState("phone-task-1")?.status)
            assertTrue(dao.agentTaskState("phone-task-1")?.lastError?.contains("手动继续") == true)
            assertEquals(PhoneAgentTaskStore.STATUS_PAUSED, dao.phoneAgentTask("phone-task-1")?.status)
            assertEquals("已保存检查点", dao.recentAgentTaskTraces("phone-task-1").single().detail)
            assertTrue(prefs.getBoolean("automatic_memory_enabled", false))
            assertEquals("high", prefs.getString("reasoning_effort_v1", "low"))
            assertEquals(1.2f, prefs.getFloat("tts_speech_rate", 1.0f), 0.001f)
            assertEquals("TASK_START", JSONArray(prefs.getString("phone_audit", "[]")).getJSONObject(0).getString("action"))
            assertTrue("备份应保留任务模式开关", prefs.getBoolean("chat_task_mode_enabled", false))
            assertEquals("legacy-model-backup-profile", prefs.getString("active_model_registry_id", ""))
            assertEquals(null, ChatRequestStateStore.load(prefs))
            assertTrue(!prefs.contains("pending_project_delete_id"))
            assertEquals(0L, prefs.getLong("phone_task_grant_until", 0L))
            assertTrue(!prefs.getBoolean("phone_single_grant", false))
            assertTrue(!prefs.getBoolean("phone_task_stop_requested", false))
            assertTrue(!prefs.contains("phone_agent_work_pending_old-task"))
            assertTrue(!prefs.contains("phone_agent_work_pending_at_old-task"))
            assertTrue(!prefs.contains("phone_agent_after_handoff_old-task"))
            assertTrue(!prefs.contains("phone_agent_allow_retry_old-task"))
            assertTrue(!prefs.contains("phone_agent_cancel_requested_old-task"))
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
    @Test fun backupAndRestoreRejectMissingActiveConversationReference() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "nestro_backup_active_conversation.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, NestroDatabase::class.java, databaseName)
            .addMigrations(*NestroDatabase.ALL_MIGRATIONS)
            .build()
        val prefs = context.getSharedPreferences("nestro_backup_active_conversation", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putString("messages", "[]")
                .putString("active_conversation_id", "active-c1").commit()
            val dao = database.dao()
            dao.putProjects(listOf(ProjectEntity("default", "默认项目", "", 1L)))
            dao.putConversation(ConversationEntity("active-c1", "活动会话", 1L, "default"))
            dao.putMessages(listOf(MessageEntity("active-m1", "active-c1", 0, "user", "原始消息", "[]", 0)))
            val repository = LocalBackupRepository(context, database)

            val backup = repository.createBackup(prefs)
            prefs.edit().putString("active_conversation_id", "missing-c1").commit()
            assertTrue("导出不得把无效活动会话静默切换到第一条会话",
                runCatching { repository.createBackup(prefs) }.isFailure)

            prefs.edit().putString("active_conversation_id", "active-c1").commit()
            val invalidActiveConversationBackup = JSONObject(backup).apply {
                remove("checksum_sha256")
                put("active_conversation_id", "missing-c1")
                put("checksum_sha256", LocalBackupCompatibility.sha256(
                    LocalBackupCompatibility.canonicalJson(this)
                ))
            }.toString()
            assertTrue("恢复不得把无效活动会话静默切换到第一条会话",
                runCatching { repository.restoreBackup(prefs, invalidActiveConversationBackup) }.isFailure)
            assertEquals("原会话必须保持不变", "active-c1", dao.loadConversations().single().conversation.id)
            assertEquals("原活动会话引用必须保持不变", "active-c1", prefs.getString("active_conversation_id", ""))

            val invalidActiveProfileBackup = JSONObject(backup).apply {
                remove("checksum_sha256")
                put("active_model_profile_id", "missing-profile")
                put("checksum_sha256", LocalBackupCompatibility.sha256(
                    LocalBackupCompatibility.canonicalJson(this)
                ))
            }.toString()
            assertTrue("恢复不得静默清空无效的活动模型配置",
                runCatching { repository.restoreBackup(prefs, invalidActiveProfileBackup) }.isFailure)

            val invalidSelectedTeamBackup = JSONObject(backup).apply {
                remove("checksum_sha256")
                put("selected_team_id", "missing-team")
                put("checksum_sha256", LocalBackupCompatibility.sha256(
                    LocalBackupCompatibility.canonicalJson(this)
                ))
            }.toString()
            assertTrue("恢复不得静默清空无效的已选专家团队",
                runCatching { repository.restoreBackup(prefs, invalidSelectedTeamBackup) }.isFailure)
            assertEquals("引用校验失败不得破坏原会话", "active-c1", dao.loadConversations().single().conversation.id)
        } finally {
            database.close()
            prefs.edit().clear().commit()
            File(context.filesDir, "artifacts").deleteRecursively()
            File(context.filesDir, "backup-restore").deleteRecursively()
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


    @Test fun restoreIsRejectedWhilePhoneAgentTaskIsNonTerminal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "artifacts").deleteRecursively()
        File(context.filesDir, "backup-restore").deleteRecursively()
        val databaseName = "nestro_backup_active_phone_task.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, NestroDatabase::class.java, databaseName)
            .addMigrations(*NestroDatabase.ALL_MIGRATIONS)
            .build()
        val prefs = context.getSharedPreferences("nestro_backup_active_phone_task", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putString("messages", "[]").commit()
            database.dao().putProjects(listOf(ProjectEntity("default", "默认项目", "", 1L)))
            val repository = LocalBackupRepository(context, database)
            val backup = repository.createBackup(prefs)
            val activeStatuses = listOf(
                "planned" to PhoneAgentTaskStore.STATUS_PLANNED,
                "running" to PhoneAgentTaskStore.STATUS_RUNNING,
                "paused" to PhoneAgentTaskStore.STATUS_PAUSED,
                "waiting" to PhoneAgentTaskStore.STATUS_WAITING_USER
            )
            activeStatuses.forEach { (id, status) ->
                database.dao().putPhoneAgentTask(
                    PhoneAgentTaskEntity(
                        id = id,
                        projectId = "default",
                        conversationId = "",
                        goal = "未结束的设备任务",
                        category = "GENERAL",
                        status = status,
                        planJson = "[]",
                        currentStep = 0,
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                )
            }
            assertTrue(runCatching { repository.restoreBackup(prefs, backup) }.isFailure)
            assertEquals(4, database.dao().allPhoneAgentTasks().size)
            assertTrue(!File(context.filesDir, "backup-restore/restore-journal.json").exists())
        } finally {
            database.close()
            prefs.edit().clear().commit()
            File(context.filesDir, "artifacts").deleteRecursively()
            File(context.filesDir, "backup-restore").deleteRecursively()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun backupRejectsSilentTruncationOfTitlesDraftsAndRestoredFields() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "artifacts").deleteRecursively()
        File(context.filesDir, "backup-restore").deleteRecursively()
        val databaseName = "nestro_backup_no_truncation.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, NestroDatabase::class.java, databaseName)
            .addMigrations(*NestroDatabase.ALL_MIGRATIONS)
            .build()
        val prefs = context.getSharedPreferences("nestro_backup_no_truncation", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putString("messages", "[]").commit()
            val dao = database.dao()
            dao.putProjects(listOf(ProjectEntity("default", "默认项目", "", 1L)))
            dao.putConversation(ConversationEntity("backup-c1", "有效标题", 1L, "default"))
            val repository = LocalBackupRepository(context, database)

            dao.putConversation(ConversationEntity("backup-c1", "标题".repeat(41), 1L, "default"))
            assertTrue("导出不得静默截断超长会话标题", runCatching { repository.createBackup(prefs) }.isFailure)
            dao.putConversation(ConversationEntity("backup-c1", "有效标题", 1L, "default"))

            prefs.edit().putString("draft_backup-c1", "x".repeat(100_001)).commit()
            assertTrue("导出不得静默截断超长草稿", runCatching { repository.createBackup(prefs) }.isFailure)
            prefs.edit().remove("draft_backup-c1").putString("draft_missing", "孤立草稿").commit()
            assertTrue("导出不得静默丢弃孤立草稿", runCatching { repository.createBackup(prefs) }.isFailure)
            prefs.edit().remove("draft_missing").commit()
            prefs.edit().putInt("draft_backup-c1", 123).commit()
            assertTrue("导出不得忽略类型错误的草稿字段", runCatching { repository.createBackup(prefs) }.isFailure)
            prefs.edit().remove("draft_backup-c1").commit()

            val validBackup = repository.createBackup(prefs)

            prefs.edit().putString("active_project_id", "missing-project").commit()
            assertTrue("导出不得静默把不存在的活动项目写入备份", runCatching { repository.createBackup(prefs) }.isFailure)
            prefs.edit().putString("active_project_id", "default").commit()

            dao.putProjects(listOf(
                ProjectEntity("default", "默认项目", "", 1L),
                ProjectEntity("archived-project", "已归档项目", "", 2L, 3L)
            ))
            prefs.edit().putString("active_project_id", "archived-project").commit()
            assertTrue("导出不得把已归档项目作为活动项目写入备份", runCatching { repository.createBackup(prefs) }.isFailure)
            dao.putProjects(listOf(ProjectEntity("default", "默认项目", "", 1L)))
            prefs.edit().putString("active_project_id", "default").commit()

            fun signedBackup(change: (JSONObject) -> Unit): String {
                val root = JSONObject(validBackup)
                change(root)
                root.remove("checksum_sha256")
                root.put("checksum_sha256", LocalBackupCompatibility.sha256(LocalBackupCompatibility.canonicalJson(root)))
                return root.toString()
            }

            val longTaskConversationId = signedBackup { root ->
                root.put("phone_agent_tasks", JSONArray().put(
                    JSONObject().put("id", "task-long-conversation").put("projectId", "default")
                        .put("conversationId", "c".repeat(121)).put("goal", "测试任务")
                        .put("category", "GENERAL").put("status", PhoneAgentTaskStore.STATUS_COMPLETED)
                        .put("planJson", "[]").put("currentStep", 0).put("createdAt", 1L).put("updatedAt", 1L)
                ))
            }
            assertTrue("恢复不得静默截断 Phone Agent 会话 ID", runCatching { repository.restoreBackup(prefs, longTaskConversationId) }.isFailure)

            val longMimeType = signedBackup { root ->
                root.put("knowledge_documents_v1", JSONArray().put(
                    JSONObject().put("id", "doc-long-mime").put("projectId", "default")
                        .put("name", "文档").put("mimeType", "m".repeat(121)).put("sizeBytes", 0L)
                        .put("importedAt", 1L).put("contentHash", "a".repeat(64))
                ))
                root.put("knowledge_chunks_v1", JSONArray())
            }
            assertTrue("恢复不得静默截断知识库 MIME 类型", runCatching { repository.restoreBackup(prefs, longMimeType) }.isFailure)

            val unknownTeamMember = signedBackup { root ->
                root.put("expert_teams_v1", JSONArray().put(
                    JSONObject().put("id", "team-invalid-member").put("name", "无效团队")
                        .put("description", "").put("members", JSONArray().put("文案官").put("不存在的专家"))
                ).toString())
            }
            assertTrue("恢复不得静默过滤无效专家团队成员", runCatching { repository.restoreBackup(prefs, unknownTeamMember) }.isFailure)

            val longTeamName = signedBackup { root ->
                root.put("expert_teams_v1", JSONArray().put(
                    JSONObject().put("id", "team-long-name").put("name", "团队".repeat(21))
                        .put("description", "").put("members", JSONArray().put("文案官").put("选题狐"))
                ).toString())
            }
            assertTrue("恢复不得静默截断专家团队名称", runCatching { repository.restoreBackup(prefs, longTeamName) }.isFailure)

            val nonStringMessageVersion = signedBackup { root ->
                val rows = root.getJSONArray("conversations_v1")
                rows.getJSONObject(0).put("messages", JSONArray().put(
                    JSONObject().put("id", "version-type-m1").put("role", "assistant").put("content", "回答")
                        .put("versions", JSONArray().put(7)).put("active_version", 0)
                ))
            }
            assertTrue("恢复不得把非字符串回答版本强制转成文本", runCatching { repository.restoreBackup(prefs, nonStringMessageVersion) }.isFailure)

            val wrongVersionFieldType = signedBackup { root ->
                val rows = root.getJSONArray("conversations_v1")
                rows.getJSONObject(0).put("messages", JSONArray().put(
                    JSONObject().put("id", "version-field-m1").put("role", "assistant").put("content", "回答")
                        .put("versions", "[\"有效版本\"]").put("active_version", 0)
                ))
            }
            assertTrue("恢复不得忽略类型错误的回答版本字段", runCatching { repository.restoreBackup(prefs, wrongVersionFieldType) }.isFailure)

            val wrongArtifactCollectionType = signedBackup { root ->
                root.put("artifacts_v1", "{}")
            }
            assertTrue("恢复不得把类型错误的产物清单当成空清单", runCatching { repository.restoreBackup(prefs, wrongArtifactCollectionType) }.isFailure)

            val wrongKnowledgeCollectionType = signedBackup { root ->
                root.put("knowledge_documents_v1", JSONObject().put("unexpected", true))
            }
            assertTrue("恢复不得把类型错误的知识库清单当成空清单", runCatching { repository.restoreBackup(prefs, wrongKnowledgeCollectionType) }.isFailure)

            val wrongDraftMapType = signedBackup { root ->
                root.put("conversation_drafts_v1", JSONArray().put("not-a-draft-map"))
            }
            assertTrue("恢复不得把类型错误的草稿映射当成空对象", runCatching { repository.restoreBackup(prefs, wrongDraftMapType) }.isFailure)

            val oversizedProjectInstructions = signedBackup { root ->
                root.put("projects", JSONArray().put(
                    JSONObject().put("id", "default").put("name", "默认空间")
                        .put("instructions", "x".repeat(4_001)).put("created_at", 1L).put("archived_at", 0L)
                ).toString())
            }
            assertTrue("恢复不得静默截断项目空间指令", runCatching { repository.restoreBackup(prefs, oversizedProjectInstructions) }.isFailure)

            val missingActiveProject = signedBackup { root ->
                root.put("active_project_id", "missing-project")
            }
            assertTrue("恢复不得把不存在的活动项目静默重置为默认项目", runCatching { repository.restoreBackup(prefs, missingActiveProject) }.isFailure)

            val archivedActiveProject = signedBackup { root ->
                root.put("projects", JSONArray()
                    .put(JSONObject().put("id", "default").put("name", "默认空间").put("instructions", "").put("created_at", 1L).put("archived_at", 0L))
                    .put(JSONObject().put("id", "archived-project").put("name", "已归档项目").put("instructions", "").put("created_at", 2L).put("archived_at", 3L)).toString())
                root.put("active_project_id", "archived-project")
            }
            assertTrue("恢复不得把已归档项目设为活动项目", runCatching { repository.restoreBackup(prefs, archivedActiveProject) }.isFailure)

            val duplicateMemoryFingerprint = signedBackup { root ->
                val memory = JSONObject().put("projectId", "default").put("category", "preference")
                    .put("content", "偏好使用简洁中文表达").put("fingerprint", "duplicate-fingerprint")
                    .put("sourceConversationId", "").put("sourceMessageId", "")
                    .put("confidence", 0.9).put("createdAt", 1L).put("updatedAt", 1L)
                root.put("user_memories", JSONArray()
                    .put(JSONObject(memory.toString()).put("id", "memory-a"))
                    .put(JSONObject(memory.toString()).put("id", "memory-b")))
            }
            assertTrue("恢复不得让重复记忆指纹触发 Room REPLACE 并静默覆盖", runCatching { repository.restoreBackup(prefs, duplicateMemoryFingerprint) }.isFailure)

            val wrongLegacyMessagesType = signedBackup { root ->
                root.put("messages", JSONArray())
            }
            assertTrue("恢复不得把类型错误的旧消息字段强制转成文本", runCatching { repository.restoreBackup(prefs, wrongLegacyMessagesType) }.isFailure)

            val wrongConversationCollectionType = signedBackup { root ->
                root.put("conversations_v1", JSONObject().put("unexpected", true))
            }
            assertTrue("恢复不得把类型错误的会话清单当成空清单", runCatching { repository.restoreBackup(prefs, wrongConversationCollectionType) }.isFailure)

            assertEquals("有效标题", dao.loadConversations().single().conversation.title)
            assertTrue(!File(context.filesDir, "backup-restore/restore-journal.json").exists())
        } finally {
            database.close()
            prefs.edit().clear().commit()
            File(context.filesDir, "artifacts").deleteRecursively()
            File(context.filesDir, "backup-restore").deleteRecursively()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun backupRestorePreservesConversationHistoryBeyondFiveHundredMessages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "artifacts").deleteRecursively()
        File(context.filesDir, "backup-restore").deleteRecursively()
        val databaseName = "nestro_backup_long_conversation.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, NestroDatabase::class.java, databaseName)
            .addMigrations(*NestroDatabase.ALL_MIGRATIONS)
            .build()
        val prefs = context.getSharedPreferences("nestro_backup_long_conversation", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putString("messages", "[]").commit()
            val dao = database.dao()
            dao.putProjects(listOf(ProjectEntity("default", "默认项目", "", 1L)))
            dao.putConversation(ConversationEntity("long-backup-c1", "长会话", 1L, "default"))
            dao.putMessages((0..500).map { index ->
                MessageEntity(
                    id = "long-backup-m$index",
                    conversationId = "long-backup-c1",
                    position = index,
                    role = if (index % 2 == 0) "user" else "assistant",
                    content = "历史消息内容-$index"
                )
            })
            val repository = LocalBackupRepository(context, database)
            val backup = repository.createBackup(prefs)
            val backedUpMessages = JSONObject(backup).getJSONArray("conversations_v1")
                .getJSONObject(0).getJSONArray("messages")
            assertEquals(501, backedUpMessages.length())

            dao.deleteAllConversations()
            repository.restoreBackup(prefs, backup)

            val restored = dao.loadConversations().single().messages.sortedBy { it.position }
            assertEquals(501, restored.size)
            assertEquals("历史消息内容-0", restored.first().content)
            assertEquals("历史消息内容-500", restored.last().content)
        } finally {
            database.close()
            prefs.edit().clear().commit()
            File(context.filesDir, "artifacts").deleteRecursively()
            File(context.filesDir, "backup-restore").deleteRecursively()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun backupExportRejectsCorruptLegacyConversationSnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "nestro_backup_corrupt_legacy_snapshot.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, NestroDatabase::class.java, databaseName)
            .addMigrations(*NestroDatabase.ALL_MIGRATIONS)
            .build()
        val prefs = context.getSharedPreferences("nestro_backup_corrupt_legacy_snapshot", Context.MODE_PRIVATE)
        try {
            val invalidMessage = JSONObject().put("id", "legacy-bad-m1").put("role", "assistant")
                .put("content", "回答").put("versions", JSONArray().put(9)).put("active_version", 0)
            val invalidConversation = JSONObject().put("id", "legacy-bad-c1").put("title", "旧会话")
                .put("updated_at", 1L).put("project_id", "default").put("messages", JSONArray().put(invalidMessage))
            val rawSnapshot = JSONArray().put(invalidConversation).toString()
            prefs.edit().clear().putString("messages", "[]").putString("conversations_v1", rawSnapshot).commit()

            val repository = LocalBackupRepository(context, database)
            assertTrue("导出不得生成包含损坏回答版本的备份", runCatching { repository.createBackup(prefs) }.isFailure)
            assertEquals(rawSnapshot, prefs.getString("conversations_v1", null))

            val invalidLegacyMessage = JSONObject().put("id", "legacy-bad-m2").put("role", "assistant")
                .put("content", "回答").put("versions", JSONObject().put("not", "an array")).put("active_version", 0)
            prefs.edit().putString("conversations_v1", "[]")
                .putString("messages", JSONArray().put(invalidLegacyMessage).toString()).commit()
            assertTrue("导出不得生成包含错误版本字段类型的旧消息备份", runCatching { repository.createBackup(prefs) }.isFailure)

            val invalidLegacyProject = JSONArray().put(
                JSONObject().put("id", "legacy-project").put("name", "旧项目")
                    .put("instructions", "x".repeat(4_001)).put("created_at", 1L).put("archived_at", 0L)
            )
            prefs.edit().putString("messages", "[]").putString("conversations_v1", "[]")
                .putString("projects", invalidLegacyProject.toString()).commit()
            assertTrue("导出不得静默截断旧项目空间指令", runCatching { repository.createBackup(prefs) }.isFailure)
        } finally {
            database.close()
            prefs.edit().clear().commit()
            File(context.filesDir, "artifacts").deleteRecursively()
            File(context.filesDir, "backup-restore").deleteRecursively()
            context.deleteDatabase(databaseName)
        }
    }

}
