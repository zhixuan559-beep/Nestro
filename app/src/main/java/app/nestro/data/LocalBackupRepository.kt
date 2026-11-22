package app.nestro.data

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import android.net.Uri
import androidx.room.withTransaction
import app.nestro.model.ModelConfigurationRepository
import app.nestro.model.ModelRegistryCodec
import app.nestro.phone.PhoneAgentPlanner
import app.nestro.calendar.CalendarDatabase
import app.nestro.calendar.CalendarEvent
import app.nestro.calendar.CalendarRules
import app.nestro.calendar.CalendarReminderScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


private const val MAX_BACKUP_MESSAGES_PER_CONVERSATION = 2_000

private val nonTerminalPhoneAgentStatuses = setOf(
    PhoneAgentTaskStore.STATUS_PLANNED,
    PhoneAgentTaskStore.STATUS_RUNNING,
    PhoneAgentTaskStore.STATUS_PAUSED,
    PhoneAgentTaskStore.STATUS_WAITING_USER
)

private suspend fun requireNoActivePhoneAgentTasks(database: NestroDatabase) {
    if (database.dao().allPhoneAgentTasks().any { it.status in nonTerminalPhoneAgentStatuses }) {
        throw ActivePhoneAgentTasksException()
    }
}

private fun JSONObject.requiredString(key: String): String =
    opt(key) as? String ?: error("备份字段 $key 必须是文本")

private fun JSONObject.stringOrDefault(key: String, default: String): String =
    if (!has(key)) default else requiredString(key)

private fun JSONObject.intOrDefault(key: String, default: Int): Int {
    if (!has(key)) return default
    val value = opt(key) as? Number ?: error("备份字段 $key 必须是整数")
    val number = value.toDouble()
    require(number.isFinite() && number % 1.0 == 0.0 && number in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
        "备份字段 $key 整数值无效"
    }
    return number.toInt()
}

private fun JSONObject.longOrDefault(key: String, default: Long): Long {
    if (!has(key)) return default
    val value = opt(key) as? Number ?: error("备份字段 $key 必须是整数")
    val number = value.toDouble()
    require(number.isFinite() && number % 1.0 == 0.0 && number >= Long.MIN_VALUE.toDouble() && number <= Long.MAX_VALUE.toDouble()) {
        "备份字段 $key 整数值无效"
    }
    return value.toLong()
}

private fun JSONObject.booleanOrDefault(key: String, default: Boolean): Boolean =
    if (!has(key)) default else opt(key) as? Boolean ?: error("备份字段 $key 必须是布尔值")

private fun JSONObject.doubleOrDefault(key: String, default: Double): Double =
    if (!has(key)) default else (opt(key) as? Number)?.toDouble() ?: error("备份字段 $key 必须是数字")

private fun JSONObject.arrayOrEmpty(key: String): JSONArray =
    if (!has(key)) JSONArray() else opt(key) as? JSONArray ?: error("备份字段 $key 格式无效")

private fun decodeBackupMessageVersions(item: JSONObject): Pair<String, Int> {
    val rawVersions = item.opt("versions")
    val versions = when {
        !item.has("versions") -> JSONArray()
        rawVersions is JSONArray -> rawVersions
        else -> error("回答版本字段格式无效")
    }
    val activeVersionValue = if (item.has("active_version")) item.opt("active_version") else 0
    val activeVersion = (activeVersionValue as? Number)?.toDouble()?.let { value ->
        if (value.isFinite() && value % 1.0 == 0.0 && value in 0.0..Int.MAX_VALUE.toDouble()) value.toInt() else null
    } ?: error("回答版本索引类型无效")
    val decoded = decodeMessageVersions(versions.toString(), activeVersion)
    return JSONArray(decoded).toString() to activeVersion
}

private fun parseProjectsForBackup(array: JSONArray): List<ProjectRecord> {
    require(array.length() <= 100) { "项目空间数量超过备份上限" }
    val parsed = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        val id = item.requiredString("id")
        val name = item.stringOrDefault("name", "项目")
        val instructions = item.stringOrDefault("instructions", "")
        require(id.isNotBlank() && name.isNotBlank() && name.length <= 40 && name == name.trim() &&
            instructions.length <= 4_000) { "项目空间字段无效，已中止备份或恢复" }
        ProjectRecord(
            id = id,
            name = name,
            instructions = instructions,
            createdAt = item.longOrDefault("created_at", System.currentTimeMillis()),
            archivedAt = item.longOrDefault("archived_at", 0L).also {
                require(it >= 0L) { "项目归档时间无效" }
            }
        )
    }
    val withDefault = if (parsed.any { it.id == "default" }) parsed
        else listOf(ProjectRecord("default", "默认空间", "", System.currentTimeMillis())) + parsed
    require(withDefault.size <= 100 && withDefault.map { it.id }.distinct().size == withDefault.size) {
        "项目空间数量超限或 ID 重复"
    }
    val validation = ProjectSpaceRules.validateCollection(withDefault.map { ProjectSpaceRules.Entry(it.id, it.name) })
    require(validation == null) { validation ?: "项目空间数据无效" }
    return withDefault
}

private fun loadProjectsForBackup(prefs: android.content.SharedPreferences): List<ProjectRecord> =
    parseProjectsForBackup(JSONArray(prefs.getString("projects", "[]") ?: "[]"))


private fun validateLegacyConversationBackup(conversations: JSONArray, allowedProjectIds: Set<String>) {
    val conversationIds = mutableSetOf<String>()
    val messageIds = mutableSetOf<String>()
    for (index in 0 until conversations.length()) {
        val conversation = conversations.getJSONObject(index)
        val id = conversation.opt("id") as? String ?: error("旧会话 ID 字段无效")
        val title = if (conversation.has("title")) conversation.opt("title") as? String
            ?: error("旧会话标题字段无效") else "新对话"
        val projectId = if (conversation.has("project_id")) conversation.opt("project_id") as? String
            ?: error("旧会话项目字段无效") else "default"
        require(id.isNotBlank() && id.length <= 120 && conversationIds.add(id)) { "旧会话 ID 缺失、过长或重复" }
        require(title.length <= 80 && projectId in allowedProjectIds) { "旧会话标题或项目归属无效" }
        val messages = conversation.arrayOrEmpty("messages")
        require(messages.length() <= MAX_BACKUP_MESSAGES_PER_CONVERSATION) { "旧会话消息数量超出限制" }
        for (messageIndex in 0 until messages.length()) {
            val message = messages.getJSONObject(messageIndex)
            val messageId = message.opt("id") as? String ?: error("旧消息 ID 字段无效")
            val role = message.opt("role") as? String ?: error("旧消息角色字段无效")
            val content = message.opt("content") as? String ?: error("旧消息内容字段无效")
            require(messageId.isNotBlank() && messageIds.add(messageId)) { "旧消息 ID 缺失或重复" }
            require(role in setOf("user", "assistant") && content.length <= 200_000) { "旧消息字段无效" }
            decodeBackupMessageVersions(message)
        }
    }
}

internal class ActivePhoneAgentTasksException : IllegalStateException(
    "Phone Agent 仍有未结束的任务，请先完成或取消任务后再恢复备份"
)

/** Application-facing backup boundary; UI never owns Room access. */
class LocalBackupRepository(context: Context, private val database: NestroDatabase) {
    private val appContext = context.applicationContext
    private val operationMutex = Mutex()

    fun readText(uri: Uri): String = readBackupText(appContext, uri)

    suspend fun createBackup(prefs: SharedPreferences): String = operationMutex.withLock {
        // Never export a partially applied restore if the process previously died mid-restore.
        recoverInterruptedLocalBackupRestore(appContext, prefs, database)
        buildLocalBackup(appContext, prefs, database)
    }

    suspend fun restoreBackup(prefs: SharedPreferences, raw: String) = operationMutex.withLock {
        recoverInterruptedLocalBackupRestore(appContext, prefs, database)
        requireNoActivePhoneAgentTasks(database)
        restoreLocalBackup(appContext, prefs, raw, database)
    }

    suspend fun createProjectPackage(
        prefs: SharedPreferences,
        projectId: String,
        readableProjectIds: Set<String>
    ): String = operationMutex.withLock {
        recoverInterruptedLocalBackupRestore(appContext, prefs, database)
        ProjectPackageCodec.export(buildLocalBackup(appContext, prefs, database), projectId, readableProjectIds)
    }

    suspend fun importProjectPackage(prefs: SharedPreferences, raw: String): String = operationMutex.withLock {
        recoverInterruptedLocalBackupRestore(appContext, prefs, database)
        requireNoActivePhoneAgentTasks(database)
        val currentBackup = buildLocalBackup(appContext, prefs, database)
        val mergedBackup = ProjectPackageCodec.mergeIntoFullBackup(currentBackup, raw)
        val importedProjectId = JSONObject(mergedBackup).getString("active_project_id")
        restoreLocalBackup(appContext, prefs, mergedBackup, database, preserveModelPreferences = true)
        importedProjectId
    }

    suspend fun recoverInterruptedRestore(prefs: SharedPreferences) = operationMutex.withLock {
        recoverInterruptedLocalBackupRestore(appContext, prefs, database)
    }

    fun hasPendingRestoreJournal(): Boolean = localBackupRestoreJournalFile(appContext).isFile
}


internal fun readBackupText(context: Context, uri: Uri): String {
    val input = context.contentResolver.openInputStream(uri) ?: error("无法读取文件")
    val output = java.io.ByteArrayOutputStream()
    input.use { stream ->
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= 10_000_000) { "备份文件超过 10 MB" }
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}

private data class LocalBackupDatabaseSnapshot(
    val projects: List<ProjectEntity>,
    val conversations: List<app.nestro.data.ConversationWithMessages>,
    val documents: List<KnowledgeDocumentEntity>,
    val chunks: List<KnowledgeChunkEntity>,
    val memories: List<UserMemoryEntity>,
    val tasks: List<PhoneAgentTaskEntity>,
    val taskLogs: List<PhoneAgentTaskLogEntity>,
    val agentTaskStates: List<AgentTaskStateEntity>,
    val agentTaskTraces: List<AgentTaskTraceEntity>
)

internal suspend fun buildLocalBackup(context: Context, prefs: android.content.SharedPreferences, database: NestroDatabase, forRestoreJournal: Boolean = false): String {
    val dao = database.dao()
    val snapshot = database.withTransaction {
        val tasks = dao.allPhoneAgentTasks()
        LocalBackupDatabaseSnapshot(
            projects = dao.loadProjects(), conversations = dao.loadConversations(),
            documents = dao.allKnowledgeDocuments(), chunks = dao.allKnowledgeChunks(),
            memories = dao.allUserMemories(), tasks = tasks,
            taskLogs = tasks.flatMap { dao.allPhoneAgentTaskLogs(it.id) },
            agentTaskStates = dao.allAgentTaskStates(),
            agentTaskTraces = dao.allAgentTaskTraces()
        )
    }
    val calendarEvents = CalendarDatabase.get(context).calendarDao().allEvents()
    require(calendarEvents.size <= 2_000) { "日历日程数量超过备份上限" }
    calendarEvents.forEach { event ->
        require(CalendarRules.validate(event) == null) { "日历包含无效日程，已中止备份" }
    }
    val root = JSONObject()
    root.put("format", "nestro-local-backup")
    root.put("version", LocalBackupCompatibility.CURRENT_VERSION)
    root.put("created_at", System.currentTimeMillis())
    val calendarArray = JSONArray()
    calendarEvents.forEach { event ->
        calendarArray.put(JSONObject()
            .put("id", event.id).put("title", event.title).put("description", event.description)
            .put("location", event.location).put("startAt", event.startAt).put("endAt", event.endAt)
            .put("allDay", event.allDay).put("recurrence", event.recurrence)
            .put("reminderMinutes", event.reminderMinutes).put("completed", event.completed)
            .put("createdAt", event.createdAt).put("updatedAt", event.updatedAt)
            .put("sourceSystemEventId", JSONObject.NULL)
            .put("sourceSystemCalendarId", JSONObject.NULL)
            .put("sourceSystemImported", event.sourceSystemImported))
    }
    root.put("calendar_events_v1", calendarArray)
    root.put("base_url", prefs.getString("base_url", "") ?: "")
    root.put("model", prefs.getString("model", "") ?: "")
    root.put("model_profiles_v1", prefs.getString("model_profiles_v1", "[]") ?: "[]")
    root.put("model_registry_v1", prefs.getString("model_registry_v1", "") ?: "")
    root.put("active_model_profile_id", prefs.getString("active_model_profile_id", "") ?: "")
    root.put("active_model_registry_id", prefs.getString("active_model_registry_id", "") ?: "")
    root.put("chat_task_mode_enabled", prefs.getBoolean("chat_task_mode_enabled", false))
    root.put("model_usage_v1", prefs.getString("model_usage_v1", "{}") ?: "{}")
    root.put("model_failover_enabled", prefs.getBoolean("model_failover_enabled", false))
    val reasoningEffort = prefs.getString("reasoning_effort_v1", "low") ?: "low"
    require(reasoningEffort in setOf("none", "low", "medium", "high")) { "思考强度设置无效，已中止备份" }
    val ttsSpeechRate = prefs.getFloat("tts_speech_rate", 1.0f)
    require(ttsSpeechRate.isFinite() && ttsSpeechRate in 0.7f..1.3f) { "语音语速设置无效，已中止备份" }
    root.put("reasoning_effort_v1", reasoningEffort)
    root.put("tts_speech_rate", ttsSpeechRate.toDouble())
    root.put("automatic_memory_enabled", prefs.getBoolean("automatic_memory_enabled", false))
    val projectMemorySettingsRaw = prefs.getString("project_memory_settings_v1", "{}") ?: "{}"
    ProjectMemorySettingsStore.decode(projectMemorySettingsRaw)
    root.put("project_memory_settings_v1", projectMemorySettingsRaw)
    val phoneAuditRaw = prefs.getString("phone_audit", "[]") ?: "[]"
    val phoneAudit = runCatching { JSONArray(phoneAuditRaw) }
        .getOrElse { error("Phone Agent 安全审计记录格式损坏，已中止备份") }
    require(phoneAudit.length() <= 100) { "Phone Agent 安全审计记录超过备份容量，拒绝静默丢弃旧记录" }
    val auditSnapshot = JSONArray()
    for (index in 0 until phoneAudit.length()) {
        val item = phoneAudit.optJSONObject(index) ?: error("Phone Agent 安全审计记录包含无效条目，已中止备份")
        val action = item.requiredString("action")
        val detail = item.requiredString("detail")
        require(action.length in 1..60 && detail.length <= 180) { "Phone Agent 安全审计记录字段无效，已中止备份" }
        auditSnapshot.put(JSONObject().put("at", item.longOrDefault("at", 0L)).put("action", action).put("detail", detail))
    }
    root.put("phone_audit_v1", auditSnapshot)
    val legacyMessagesRaw = if (snapshot.conversations.isEmpty()) prefs.getString("messages", "[]") ?: "[]" else "[]"
    val legacyMessages = JSONArray(legacyMessagesRaw)
    require(legacyMessages.length() <= 200) { "旧版消息数量超过备份上限" }
    for (index in 0 until legacyMessages.length()) {
        val message = legacyMessages.getJSONObject(index)
        val id = message.opt("id") as? String ?: error("旧版消息 ID 字段无效")
        val role = message.opt("role") as? String ?: error("旧版消息角色字段无效")
        val content = message.opt("content") as? String ?: error("旧版消息内容字段无效")
        require(id.isNotBlank() && role in setOf("user", "assistant") && content.length <= 200_000) {
            "旧版消息字段无效，已中止备份"
        }
        decodeBackupMessageVersions(message)
    }
    root.put("messages", legacyMessagesRaw)
    root.put("conversations_v1", prefs.getString("conversations_v1", "[]") ?: "[]")
    root.put("active_conversation_id", prefs.getString("active_conversation_id", "") ?: "")
    root.put("global_memory_v1", prefs.getString("global_memory_v1", "") ?: "")
    root.put("experts", prefs.getString("experts", """["文案官","选题狐","代码佬"]""") ?: "[]")
    root.put("expert_prompts", prefs.getString("expert_prompts", "{}") ?: "{}")
    root.put("selected_expert", prefs.getString("selected_expert", "通用助手") ?: "通用助手")
    root.put("expert_profiles_v1", prefs.getString("expert_profiles_v1", "{}") ?: "{}")
    root.put("expert_versions_v1", prefs.getString("expert_versions_v1", "{}") ?: "{}")
    root.put("expert_templates_v1", prefs.getString("expert_templates_v1", "[]") ?: "[]")
    root.put("expert_teams_v1", prefs.getString("expert_teams_v1", "[]") ?: "[]")
    root.put("selected_team_id", prefs.getString("selected_team_id", "") ?: "")
    root.put("skills", prefs.getString("skills", "{}") ?: "{}")
    root.put("skill_metadata_v1", prefs.getString("skill_metadata_v1", "{}") ?: "{}")
    root.put("disabled_skills_v1", prefs.getString("disabled_skills_v1", "[]") ?: "[]")
    root.put("workflows_v1", prefs.getString("workflows_v1", "[]") ?: "[]")
    root.put("workflow_runs_v1", prefs.getString("workflow_runs_v1", "[]") ?: "[]")
    root.put("expert_team_runs_v1", prefs.getString("expert_team_runs_v1", "[]") ?: "[]")
    val resourceScopesRaw = prefs.getString("project_resource_scopes_v1", "{}") ?: "{}"
    ProjectResourceScopeStore.decode(resourceScopesRaw)
    root.put("project_resource_scopes_v1", resourceScopesRaw)
    val roomProjects = snapshot.projects.map { ProjectRecord(it.id, it.name, it.instructions, it.createdAt, it.archivedAt) }
    val backupProjects = if (roomProjects.isEmpty()) loadProjectsForBackup(prefs) else parseProjectsForBackup(
        JSONArray(ProjectPreferencesStore.projectsToJson(roomProjects))
    )
    val backupProjectIds = (backupProjects.map { it.id } + "default").toSet()
    val backupResourceScopes = ProjectResourceScopeStore.decode(resourceScopesRaw)
    require(backupResourceScopes.values.flatten().all { it == "*" || it in backupProjectIds }) {
        "项目资源关联引用了不存在的项目，已中止备份"
    }
    val backupProjectMemorySettings = ProjectMemorySettingsStore.decode(projectMemorySettingsRaw)
    require(backupProjectMemorySettings.keys.all { it in backupProjectIds }) {
        "项目记忆设置引用了不存在的项目，已中止备份"
    }
    val activeProjectId = prefs.getString("active_project_id", "default") ?: "default"
    // A restore journal is insurance for the restore itself, so it must stay writable
    // even when the current state is inconsistent — that is exactly when a restore is
    // needed. In journal mode, dangling references are neutralised in the journal copy
    // only; the journal's verbatim preferences snapshot still restores the original
    // values on rollback. User-facing exports stay strict.
    val effectiveActiveProjectId = if (forRestoreJournal) {
        if (backupProjects.any { it.id == activeProjectId && it.archivedAt == 0L }) activeProjectId
        else backupProjects.firstOrNull { it.archivedAt == 0L }?.id ?: "default"
    } else {
        require(backupProjects.any { it.id == activeProjectId && it.archivedAt == 0L }) {
            "当前活动项目不存在或已归档，已中止备份"
        }
        activeProjectId
    }
    root.put("projects", ProjectPreferencesStore.projectsToJson(backupProjects))
    root.put("active_project_id", effectiveActiveProjectId)
    val roomConversationRows = snapshot.conversations
    val legacyConversationRaw = prefs.getString("conversations_v1", "[]") ?: "[]"
    val conversationArray = if (roomConversationRows.isEmpty()) JSONArray(legacyConversationRaw) else JSONArray()
    if (roomConversationRows.isEmpty()) {
        validateLegacyConversationBackup(conversationArray, backupProjects.map { it.id }.toSet() + "default")
    }
    require(roomConversationRows.size <= 200 && conversationArray.length() <= 200) {
        "会话数量超过备份上限 200，请先清理旧会话"
    }
    val conversationPayloadChars = if (roomConversationRows.isEmpty()) {
        legacyConversationRaw.length.toLong()
    } else {
        roomConversationRows.sumOf { row ->
            row.conversation.title.length.toLong() + row.messages.sumOf { message ->
                message.content.length.toLong() + message.versionsJson.length.toLong()
            }
        }
    }
    require(conversationPayloadChars <= 3_500_000L) { "会话历史内容超过备份安全容量，请先清理旧会话" }
    roomConversationRows.forEach { row -> require(row.messages.size <= MAX_BACKUP_MESSAGES_PER_CONVERSATION) { "单个会话消息超过备份上限 $MAX_BACKUP_MESSAGES_PER_CONVERSATION" } }
    roomConversationRows.forEach { row ->
        val messages = JSONArray()
        row.messages.sortedBy { it.position }.forEach { message ->
            val versions = runCatching { JSONArray(message.versionsJson) }
                .getOrElse { error("会话回答版本记录损坏，已中止备份") }
            require(message.id.isNotBlank() && message.role in setOf("user", "assistant") && message.content.length <= 200_000) {
                "会话消息字段无效，已中止备份"
            }
            require(versions.length() <= 20) { "回答版本数量超过备份上限，已中止备份" }
            val versionValues = (0 until versions.length()).map { versionIndex ->
                require(versions.opt(versionIndex) is String) { "回答版本格式无效，已中止备份" }
                versions.getString(versionIndex).also { require(it.length <= 200_000) { "回答版本内容过长，已中止备份" } }
            }
            require(message.activeVersion >= 0 && (versionValues.isEmpty() || message.activeVersion < versionValues.size)) {
                "回答版本索引无效，已中止备份"
            }
            messages.put(JSONObject().put("id", message.id).put("role", message.role).put("content", message.content)
                .put("versions", JSONArray(versionValues)).put("active_version", message.activeVersion))
        }
        require(row.conversation.title.length <= 80) { "会话标题超过备份上限，已中止备份" }
        conversationArray.put(JSONObject().put("id", row.conversation.id).put("title", row.conversation.title)
            .put("updated_at", row.conversation.updatedAt).put("project_id", row.conversation.projectId).put("messages", messages))
    }
    root.put("conversations_v1", conversationArray)
    val activeId = prefs.getString("active_conversation_id", "") ?: ""
    val backedUpConversationIdsForActive = (0 until conversationArray.length())
        .map { conversationArray.getJSONObject(it).requiredString("id") }.toSet()
    val effectiveActiveId = if (forRestoreJournal) {
        if (activeId in backedUpConversationIdsForActive) activeId else ""
    } else {
        require(activeId.isBlank() || activeId in backedUpConversationIdsForActive) {
            "当前活动会话不存在于备份数据中，已中止备份"
        }
        activeId
    }
    root.put("active_conversation_id", effectiveActiveId)
    val drafts = JSONObject()
    val backedUpConversationIds = (0 until conversationArray.length())
        .map { conversationArray.getJSONObject(it).requiredString("id") }.toSet()
    prefs.all.forEach { (key, value) ->
        if (key.startsWith("draft_")) {
            if (forRestoreJournal) {
                // Journal copy only: skip drafts that would fail export validation;
                // the journal's verbatim preferences snapshot keeps the originals.
                val draft = value as? String
                val conversationId = key.removePrefix("draft_")
                if (draft != null && draft.isNotBlank() && draft.length <= 100_000 &&
                    conversationId in backedUpConversationIds
                ) {
                    drafts.put(conversationId, draft)
                }
            } else {
                val draft = value as? String ?: error("会话草稿字段类型无效，已中止备份")
                if (draft.isNotBlank()) {
                    val conversationId = key.removePrefix("draft_")
                    require(conversationId in backedUpConversationIds) { "草稿引用了不存在的会话，已中止备份" }
                    require(draft.length <= 100_000) { "会话草稿超过备份上限，已中止备份" }
                    drafts.put(conversationId, draft)
                }
            }
        }
    }
    root.put("conversation_drafts_v1", drafts)
    val roomKnowledgeDocuments = snapshot.documents
    val roomKnowledgeChunks = snapshot.chunks
    require(roomKnowledgeDocuments.size <= 500 && roomKnowledgeChunks.size <= 50_000) { "知识库数据超过备份上限，请先清理文档" }
    val knowledgePayloadChars = roomKnowledgeChunks.sumOf { it.content.length.toLong() } + roomKnowledgeDocuments.sumOf { it.name.length.toLong() }
    require(knowledgePayloadChars <= 3_000_000L) { "知识库文本超过备份安全容量，请减少文档后重试" }
    val docs = JSONArray()
    roomKnowledgeDocuments.forEach { doc -> docs.put(JSONObject().put("id", doc.id).put("projectId", doc.projectId).put("name", doc.name).put("mimeType", doc.mimeType).put("sizeBytes", doc.sizeBytes).put("importedAt", doc.importedAt).put("contentHash", doc.contentHash)) }
    val chunks = JSONArray()
    roomKnowledgeChunks.forEach { chunk -> chunks.put(JSONObject().put("id", chunk.id).put("documentId", chunk.documentId).put("projectId", chunk.projectId).put("position", chunk.position).put("content", chunk.content)) }
    root.put("knowledge_documents_v1", docs)
    root.put("knowledge_chunks_v1", chunks)
    val memories = JSONArray()
    snapshot.memories.forEach { memory ->
        memories.put(JSONObject().put("id", memory.id).put("projectId", memory.projectId).put("category", memory.category)
            .put("content", memory.content).put("fingerprint", memory.fingerprint).put("sourceConversationId", memory.sourceConversationId)
            .put("sourceMessageId", memory.sourceMessageId).put("confidence", memory.confidence)
            .put("createdAt", memory.createdAt).put("updatedAt", memory.updatedAt)
            .put("shareAcrossProjects", memory.shareAcrossProjects))
    }
    root.put("user_memories", memories)
    val taskArray = JSONArray()
    val taskLogArray = JSONArray()
    require(snapshot.tasks.size <= 500 && snapshot.taskLogs.size <= 5000) { "Phone Agent 任务或日志数量超过备份上限，未导出任何不完整备份" }
    val taskPayloadChars = snapshot.tasks.sumOf { it.goal.length.toLong() + it.planJson.length.toLong() } + snapshot.taskLogs.sumOf { it.action.length.toLong() + it.detail.length.toLong() }
    require(taskPayloadChars <= 500_000L) { "Phone Agent 任务与日志内容超过备份安全容量" }
    snapshot.tasks.forEach { task ->
        taskArray.put(JSONObject().put("id", task.id).put("projectId", task.projectId).put("conversationId", task.conversationId)
            .put("goal", task.goal).put("category", task.category).put("status", task.status).put("planJson", task.planJson)
            .put("currentStep", task.currentStep).put("createdAt", task.createdAt).put("updatedAt", task.updatedAt))
    }
    snapshot.taskLogs.forEach { log ->
        taskLogArray.put(JSONObject().put("id", log.id).put("taskId", log.taskId).put("actionIndex", log.actionIndex)
            .put("action", log.action).put("success", log.success).put("detail", log.detail).put("createdAt", log.createdAt))
    }
    root.put("phone_agent_tasks", taskArray)
    root.put("phone_agent_task_logs", taskLogArray)

    val validAgentTaskStatuses = app.nestro.agent.AgentTaskStatus.values().map { it.name }.toSet()
    require(snapshot.agentTaskStates.size <= 1_000 && snapshot.agentTaskTraces.size <= 20_000) {
        "Agent 任务状态或追踪记录数量超过备份上限"
    }
    val agentStateIds = mutableSetOf<String>()
    val agentStateArray = JSONArray()
    var agentPayloadChars = 0L
    snapshot.agentTaskStates.forEach { state ->
        require(state.taskId.isNotBlank() && state.taskId.length <= 120 && agentStateIds.add(state.taskId)) {
            "Agent 任务状态 ID 缺失、过长或重复"
        }
        require(state.owner.isNotBlank() && state.owner.length <= 80 && state.status in validAgentTaskStatuses &&
            state.currentStep >= 0 && state.checkpoint.length <= 64_000 && state.lastError.length <= 2_000) {
            "Agent 任务状态字段无效"
        }
        agentPayloadChars += state.checkpoint.length.toLong() + state.lastError.length.toLong()
        agentStateArray.put(JSONObject().put("taskId", state.taskId).put("owner", state.owner)
            .put("status", state.status).put("currentStep", state.currentStep)
            .put("checkpoint", state.checkpoint).put("lastError", state.lastError).put("updatedAt", state.updatedAt))
    }
    val agentTraceIds = mutableSetOf<String>()
    val agentTraceArray = JSONArray()
    snapshot.agentTaskTraces.forEach { trace ->
        require(trace.id.isNotBlank() && trace.id.length <= 120 && agentTraceIds.add(trace.id) &&
            trace.taskId.isNotBlank() && trace.taskId.length <= 120 &&
            trace.event.isNotBlank() && trace.event.length <= 80 && trace.detail.length <= 2_000) {
            "Agent 任务追踪记录字段无效"
        }
        agentPayloadChars += trace.event.length.toLong() + trace.detail.length.toLong()
        agentTraceArray.put(JSONObject().put("id", trace.id).put("taskId", trace.taskId)
            .put("event", trace.event).put("detail", trace.detail).put("createdAt", trace.createdAt))
    }
    require(agentPayloadChars <= 2_000_000L) { "Agent 任务状态与追踪记录超过备份安全容量" }
    root.put("agent_task_states_v1", agentStateArray)
    root.put("agent_task_traces_v1", agentTraceArray)
    root.put("artifacts_v1", app.nestro.data.ArtifactStore.backupAll(context, backupProjects.map { it.id }.toSet() + "default"))
    root.put("checksum_sha256", LocalBackupCompatibility.sha256(LocalBackupCompatibility.canonicalJson(root)))
    val encoded = root.toString(2)
    LocalBackupCompatibility.requireUtf8SizeWithin(encoded, 10_000_000, "备份超过 10 MB，请减少历史数据或知识库文档后重试")
    return encoded
}

internal suspend fun restoreLocalBackup(
    context: Context,
    prefs: SharedPreferences,
    raw: String,
    database: NestroDatabase,
    preserveModelPreferences: Boolean = false
) {
    recoverInterruptedLocalBackupRestore(context, prefs, database)
    persistLocalBackupRestoreJournal(context, prefs, database)
    try {
        applyLocalBackup(
            context, prefs, raw, database,
            refuseActivePhoneTasks = true,
            preserveModelPreferences = preserveModelPreferences
        )
        deleteLocalBackupRestoreJournal(context)
    } catch (failure: Throwable) {
        if (failure is ActivePhoneAgentTasksException) {
            // No database rows were mutated: discard the journal instead of replaying
            // its earlier snapshot, which could erase a task created after journaling.
            deleteLocalBackupRestoreJournal(context)
            throw failure
        }
        try {
            withContext(NonCancellable) {
                recoverInterruptedLocalBackupRestore(context, prefs, database)
            }
        } catch (rollbackFailure: Throwable) {
            rollbackFailure.addSuppressed(failure)
            throw IllegalStateException(
                "备份恢复失败，且自动回滚未完成；本机恢复日志已保留，请勿清理应用数据",
                rollbackFailure
            )
        }
        throw failure
    }
}

internal suspend fun persistLocalBackupRestoreJournal(context: Context, prefs: SharedPreferences, database: NestroDatabase) {
    val vaultPreferences = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
    val previousBackup = buildLocalBackup(context, prefs, database, forRestoreJournal = true)
    val journal = JSONObject()
        .put("format", "nestro-restore-journal")
        .put("version", 1)
        .put("created_at", System.currentTimeMillis())
        .put("previous_backup", previousBackup)
        .put("preferences", encodeSharedPreferencesSnapshot(prefs))
        .put("vault_preferences", encodeSharedPreferencesSnapshot(vaultPreferences))
    journal.put("checksum_sha256", LocalBackupCompatibility.sha256(LocalBackupCompatibility.canonicalJson(journal)))
    val file = localBackupRestoreJournalFile(context)
    file.parentFile?.mkdirs()
    val atomic = AtomicFile(file)
    var output: FileOutputStream? = null
    try {
        output = atomic.startWrite()
        output.write(journal.toString().toByteArray(Charsets.UTF_8))
        output.fd.sync()
        atomic.finishWrite(output)
    } catch (failure: Throwable) {
        output?.let { atomic.failWrite(it) }
        throw failure
    }
}

internal suspend fun recoverInterruptedLocalBackupRestore(context: Context, prefs: SharedPreferences, database: NestroDatabase) {
    val file = localBackupRestoreJournalFile(context)
    if (!file.isFile) return
    val journal = JSONObject(file.readText(Charsets.UTF_8))
    require(journal.optString("format") == "nestro-restore-journal" && journal.optInt("version") == 1) {
        "本机备份恢复日志格式无效"
    }
    val checksum = journal.optString("checksum_sha256")
    val payload = JSONObject(journal.toString()).apply { remove("checksum_sha256") }
    require(checksum.isNotBlank() && checksum.equals(
        LocalBackupCompatibility.sha256(LocalBackupCompatibility.canonicalJson(payload)),
        ignoreCase = true
    )) { "本机备份恢复日志校验失败，已停止自动恢复以保护数据" }
    // Replaying the old backup is idempotent. If the process dies again, the journal remains.
    applyLocalBackup(context, prefs, journal.getString("previous_backup"), database)
    restoreSharedPreferencesSnapshot(prefs, journal.getJSONObject("preferences"))
    restoreSharedPreferencesSnapshot(
        context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE),
        journal.getJSONObject("vault_preferences")
    )
    deleteLocalBackupRestoreJournal(context)
}

private fun localBackupRestoreJournalFile(context: Context): File =
    File(context.filesDir, "backup-restore/restore-journal.json")

private fun encodeSharedPreferencesSnapshot(prefs: SharedPreferences): JSONObject {
    val rows = JSONObject()
    prefs.all.forEach { (key, value) ->
        val entry = JSONObject()
        when (value) {
            is String -> entry.put("type", "string").put("value", value)
            is Boolean -> entry.put("type", "boolean").put("value", value)
            is Int -> entry.put("type", "int").put("value", value)
            is Long -> entry.put("type", "long").put("value", value)
            is Float -> entry.put("type", "float").put("value", value.toDouble())
            is Set<*> -> {
                require(value.all { it is String }) { "本机设置包含无法安全快照的数据类型" }
                entry.put("type", "string_set").put("value", JSONArray(value.map { it as String }.sorted()))
            }
            else -> error("本机设置包含无法安全快照的数据类型：" + key)
        }
        rows.put(key, entry)
    }
    return rows
}

private fun restoreSharedPreferencesSnapshot(prefs: SharedPreferences, snapshot: JSONObject) {
    val editor = prefs.edit().clear()
    val keys = snapshot.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val entry = snapshot.getJSONObject(key)
        when (entry.getString("type")) {
            "string" -> editor.putString(key, entry.getString("value"))
            "boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
            "int" -> editor.putInt(key, entry.getInt("value"))
            "long" -> editor.putLong(key, entry.getLong("value"))
            "float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
            "string_set" -> {
                val values = entry.getJSONArray("value")
                editor.putStringSet(key, (0 until values.length()).map { values.getString(it) }.toSet())
            }
            else -> error("本机恢复日志包含未知设置类型：" + key)
        }
    }
    check(editor.commit()) { "本机设置回滚失败" }
}

private fun deleteLocalBackupRestoreJournal(context: Context) {
    val file = localBackupRestoreJournalFile(context)
    AtomicFile(file).delete()
    check(!file.exists()) { "本机恢复日志清理失败" }
}

private suspend fun applyLocalBackup(
    context: Context,
    prefs: SharedPreferences,
    raw: String,
    database: NestroDatabase,
    refuseActivePhoneTasks: Boolean = false,
    preserveModelPreferences: Boolean = false
) {
    LocalBackupCompatibility.requireUtf8SizeWithin(raw, 10_000_000, "备份文件过大")
    val root = JSONObject(raw)
    require(root.requiredString("format") == "nestro-local-backup") { "不是 Nestro 备份文件" }
    require(LocalBackupCompatibility.isSupported(root.intOrDefault("version", 0))) { "暂不支持此备份版本" }
    require(LocalBackupCompatibility.verifyChecksum(root)) { "备份完整性校验失败，文件可能已损坏或被修改" }
    val rawMessages = root.opt("messages")
    val messages = when {
        rawMessages is String -> JSONArray(rawMessages)
        !root.has("messages") -> JSONArray()
        else -> error("备份旧版消息字段格式无效")
    }
    require(messages.length() <= 200) { "对话记录数量超出限制" }
    val conversations = when (val rawConversations = root.opt("conversations_v1")) {
        null -> JSONArray()
        is JSONArray -> rawConversations
        is String -> JSONArray(rawConversations)
        else -> error("备份会话列表字段格式无效")
    }
    require(conversations.length() <= 200) { "会话数量超出限制" }
    for (i in 0 until conversations.length()) {
        val conversation = conversations.getJSONObject(i)
        require(conversation.requiredString("id").isNotBlank()) { "会话 ID 缺失" }
        require(conversation.stringOrDefault("title", "新对话").length <= 80) { "会话标题过长" }
        val items = conversation.arrayOrEmpty("messages")
        require(items.length() <= MAX_BACKUP_MESSAGES_PER_CONVERSATION) { "单个会话消息数量超出限制（最多 $MAX_BACKUP_MESSAGES_PER_CONVERSATION 条）" }
        for (j in 0 until items.length()) {
            val item = items.getJSONObject(j)
            require(item.requiredString("role") in setOf("user", "assistant")) { "消息角色无效" }
            require(item.has("id") && item.has("content")) { "消息字段缺失" }
        }
    }
    val workflowRaw = root.stringOrDefault("workflows_v1", "[]")
    val workflowArray = JSONArray(workflowRaw)
    require(workflowArray.length() <= 100) { "工作流数量超出限制" }
    val restoredWorkflows = WorkflowDefinitionCodec.decode(workflowRaw)
    require(restoredWorkflows.size == workflowArray.length()) { "工作流备份包含无效定义" }
    restoredWorkflows.forEach { WorkflowDefinitionCodec.validate(it) }
    val workflowHistoryRaw = root.stringOrDefault("workflow_runs_v1", "[]")
    val workflowHistoryArray = JSONArray(workflowHistoryRaw)
    require(workflowHistoryArray.length() <= 50) { "工作流运行记录数量超出限制" }
    val restoredWorkflowHistory = WorkflowRunHistoryCodec.decode(workflowHistoryRaw)
    require(restoredWorkflowHistory.size == workflowHistoryArray.length()) { "工作流运行记录包含无效数据" }
    val teamHistoryRaw = root.stringOrDefault("expert_team_runs_v1", "[]")
    require(teamHistoryRaw.length<=500_000) { "专家团队运行记录过大" }
    val teamHistoryArray = JSONArray(teamHistoryRaw)
    require(teamHistoryArray.length()<=30) { "专家团队运行记录数量超出限制" }
    val restoredTeamHistory = ExpertTeamRunHistoryCodec.decode(teamHistoryRaw)
    require(restoredTeamHistory.size==teamHistoryArray.length()) { "专家团队运行记录包含无效数据" }
    val globalMemory = root.stringOrDefault("global_memory_v1", "")
    require(globalMemory.length <= 12000) { "全局记忆超出限制" }
    val restoredReasoningEffort = root.stringOrDefault("reasoning_effort_v1", "low")
    require(restoredReasoningEffort in setOf("none", "low", "medium", "high")) { "思考强度设置无效" }
    val restoredTtsSpeechRate = root.doubleOrDefault("tts_speech_rate", 1.0).toFloat()
    require(restoredTtsSpeechRate.isFinite() && restoredTtsSpeechRate in 0.7f..1.3f) { "语音语速设置无效" }
    val auditRows = root.arrayOrEmpty("phone_audit_v1")
    require(auditRows.length() <= 100) { "Phone Agent 安全审计记录数量超出限制" }
    val restoredPhoneAudit = JSONArray()
    for (index in 0 until auditRows.length()) {
        val item = auditRows.getJSONObject(index)
        val action = item.requiredString("action")
        val detail = item.requiredString("detail")
        require(action.length in 1..60 && detail.length <= 180) { "Phone Agent 审计记录字段无效" }
        restoredPhoneAudit.put(JSONObject().put("at", item.longOrDefault("at", 0L)).put("action", action).put("detail", detail))
    }
    val memoryRows = root.arrayOrEmpty("user_memories")
    require(memoryRows.length() <= 1000) { "自动记忆数量超出限制" }
    val restoredProjects = when (val projectsValue = root.opt("projects")) {
        is JSONArray -> parseProjectsForBackup(projectsValue)
        is String -> parseProjectsForBackup(JSONArray(projectsValue))
        else -> if (!root.has("projects")) parseProjectsForBackup(JSONArray())
            else error("备份项目空间字段格式无效")
    }
    require(restoredProjects.size <= 100 && restoredProjects.map { it.id }.distinct().size == restoredProjects.size) { "项目空间数据无效" }
    val restoredProjectIds = (restoredProjects.map { it.id } + "default").toSet()
    val restoredResourceScopesRaw = root.stringOrDefault("project_resource_scopes_v1", "{}")
    val restoredResourceScopes = ProjectResourceScopeStore.decode(restoredResourceScopesRaw)
    val restoredProjectMemorySettings = ProjectMemorySettingsStore.decode(root.stringOrDefault("project_memory_settings_v1", "{}"))
    require(restoredProjectMemorySettings.keys.all { it in restoredProjectIds }) {
        "项目记忆设置引用了不存在的项目，拒绝恢复"
    }
    require(restoredResourceScopes.values.flatten().all { it == "*" || it in restoredProjectIds }) {
        "项目资源关联引用了不存在的项目，拒绝恢复"
    }
    val hasArtifactBackup = root.has("artifacts_v1")
    val restoredArtifactRows = if (hasArtifactBackup) app.nestro.data.ArtifactStore.validateBackup(root.arrayOrEmpty("artifacts_v1"), restoredProjectIds) else JSONArray()
    val calendarRows = root.arrayOrEmpty("calendar_events_v1")
    require(calendarRows.length() <= 2_000) { "日历日程数量超过恢复上限" }
    val restoredCalendarEvents = (0 until calendarRows.length()).map { index ->
        val item = calendarRows.getJSONObject(index)
        val event = CalendarEvent(
            id = item.requiredString("id"),
            title = item.requiredString("title"),
            description = item.stringOrDefault("description", ""),
            location = item.stringOrDefault("location", ""),
            startAt = item.longOrDefault("startAt", 0L),
            endAt = item.longOrDefault("endAt", 0L),
            allDay = item.booleanOrDefault("allDay", false),
            recurrence = item.stringOrDefault("recurrence", CalendarRules.NONE),
            reminderMinutes = item.intOrDefault("reminderMinutes", -1),
            completed = item.booleanOrDefault("completed", false),
            createdAt = item.longOrDefault("createdAt", 0L),
            updatedAt = item.longOrDefault("updatedAt", 0L),
            sourceSystemEventId = null,
            sourceSystemCalendarId = null,
            sourceSystemImported = item.booleanOrDefault("sourceSystemImported", item.requiredString("id").startsWith("system-"))
        )
        require(CalendarRules.validate(event) == null && event.createdAt >= 0L && event.updatedAt >= 0L) {
            "日历日程字段无效，拒绝恢复"
        }
        event
    }
    LocalBackupCompatibility.requireUniqueIds(restoredCalendarEvents.map { it.id }, "日历日程")
    // The legacy format stored this field as a JSON string; current backups store an array.
    // Reuse the already parsed value so both supported representations remain readable.
    val conversationRows = conversations
    require(conversationRows.length() <= 200) { "会话数量超出限制" }
    val restoredConversations = (0 until conversationRows.length()).map { index ->
        val item = conversationRows.getJSONObject(index)
        val id = item.requiredString("id"); val projectId = item.stringOrDefault("project_id", "default"); val title = item.stringOrDefault("title", "新对话")
        require(id.isNotBlank() && id.length <= 120 && projectId in restoredProjectIds && title.length <= 80) { "会话标识或项目归属无效" }
        val rows = item.arrayOrEmpty("messages")
        require(rows.length() <= MAX_BACKUP_MESSAGES_PER_CONVERSATION) { "单个会话消息数量超出限制" }
        val restoredMessages = (0 until rows.length()).map { messageIndex ->
            val message = rows.getJSONObject(messageIndex); val messageId = message.requiredString("id"); val role = message.requiredString("role"); val content = message.requiredString("content")
            require(messageId.isNotBlank() && role in setOf("user", "assistant") && content.length <= 200_000) { "会话消息字段无效" }
            val (versionsJson, activeVersion) = decodeBackupMessageVersions(message)
            MessageEntity(messageId, id, messageIndex, role, content, versionsJson, activeVersion)
        }
        ConversationEntity(id, title, item.longOrDefault("updated_at", System.currentTimeMillis()), projectId) to restoredMessages
    }
    val completeRestoredConversations = if (restoredConversations.isNotEmpty()) restoredConversations else if (messages.length() > 0) {
        val legacyMessages = (0 until messages.length()).mapIndexed { index, messageIndex ->
            val item = messages.getJSONObject(messageIndex)
            val (versionsJson, activeVersion) = decodeBackupMessageVersions(item)
            MessageEntity(item.getString("id"), "legacy-restored", index, item.getString("role"), item.getString("content"),
                versionsJson, activeVersion)
        }
        listOf(ConversationEntity("legacy-restored", "恢复的旧对话", System.currentTimeMillis(), "default") to legacyMessages)
    } else emptyList()
    require(completeRestoredConversations.map { it.first.id }.distinct().size == completeRestoredConversations.size) { "备份中存在重复会话 ID" }
    val conversationProjectById = completeRestoredConversations.associate { it.first.id to it.first.projectId }
    val messageProjectById = completeRestoredConversations.flatMap { (conversation, rows) ->
        rows.map { it.id to conversation.projectId }
    }.toMap()
    val messageIds = completeRestoredConversations.flatMap { it.second.map { row -> row.id } }
    require(messageIds.distinct().size == messageIds.size) { "备份中存在重复消息 ID" }
    val draftObject = when (val value = root.opt("conversation_drafts_v1")) {
        null -> JSONObject()
        is JSONObject -> value
        else -> error("备份会话草稿字段格式无效")
    }
    require(draftObject.length() <= 200) { "草稿数量超出限制" }
    val restoredActiveConversationId = root.stringOrDefault("active_conversation_id", "")
    require(restoredActiveConversationId.isBlank() ||
        completeRestoredConversations.any { it.first.id == restoredActiveConversationId }) {
        "备份中的活动会话不存在，拒绝恢复"
    }
    val restoredDrafts = linkedMapOf<String, String>()
    val draftKeys = draftObject.keys()
    while (draftKeys.hasNext()) {
        val id = draftKeys.next(); val value = draftObject.requiredString(id)
        require(completeRestoredConversations.any { it.first.id == id } && value.length <= 100_000) { "会话草稿引用无效或内容过长" }
        restoredDrafts[id] = value
    }
    val documentRows = root.arrayOrEmpty("knowledge_documents_v1")
    val chunkRows = root.arrayOrEmpty("knowledge_chunks_v1")
    require(documentRows.length() <= 500 && chunkRows.length() <= 50_000) { "知识库备份数量超出限制" }
    val restoredKnowledgeDocuments = (0 until documentRows.length()).map { index ->
        val item = documentRows.getJSONObject(index); val id = item.requiredString("id"); val projectId = item.requiredString("projectId")
        val name = item.requiredString("name"); val hash = item.requiredString("contentHash"); val size = item.longOrDefault("sizeBytes", 0L)
        require(id.isNotBlank() && projectId in restoredProjectIds && name.isNotBlank() && name.length <= 180 && hash.matches(Regex("[a-fA-F0-9]{64}")) && size in 0..2_097_152) { "知识库文档元数据无效" }
        val mimeType = item.stringOrDefault("mimeType", "text/plain")
        require(mimeType.length <= 120) { "知识库文档 MIME 类型过长" }
        KnowledgeDocumentEntity(id, projectId, name, mimeType, size, item.longOrDefault("importedAt", System.currentTimeMillis()), hash.lowercase())
    }
    require(
        restoredKnowledgeDocuments.map { it.id }.distinct().size == restoredKnowledgeDocuments.size &&
            restoredKnowledgeDocuments.map { it.projectId to it.contentHash }.distinct().size == restoredKnowledgeDocuments.size
    ) { "知识库文档 ID 重复，或同一项目内存在重复内容哈希" }
    val docsById = restoredKnowledgeDocuments.associateBy { it.id }
    val restoredKnowledgeChunks = (0 until chunkRows.length()).map { index ->
        val item = chunkRows.getJSONObject(index); val id = item.requiredString("id"); val documentId = item.requiredString("documentId")
        val projectId = item.requiredString("projectId"); val position = item.intOrDefault("position", -1); val content = item.requiredString("content")
        require(id.isNotBlank() && docsById[documentId]?.projectId == projectId && position >= 0 && content.isNotBlank() && content.length <= 6000) { "知识库文本片段无效或缺少所属文档" }
        KnowledgeChunkEntity(id, documentId, projectId, position, content)
    }
    require(restoredKnowledgeChunks.map { it.id }.distinct().size == restoredKnowledgeChunks.size) { "知识库片段 ID 重复" }
    val restoredMemories = (0 until memoryRows.length()).map { index ->
        val item = memoryRows.getJSONObject(index)
        val category = item.requiredString("category")
        val content = item.requiredString("content")
        val projectId = item.requiredString("projectId")
        val sourceConversationId = item.requiredString("sourceConversationId")
        val sourceMessageId = item.requiredString("sourceMessageId")
        require(item.requiredString("id").isNotBlank() && projectId in restoredProjectIds) { "记忆标识或项目归属无效" }
        require(conversationProjectById[sourceConversationId]?.let { it == projectId } != false) { "记忆引用了其他项目空间的会话" }
        require(messageProjectById[sourceMessageId]?.let { it == projectId } != false) { "记忆引用了其他项目空间的消息" }
        require(category in setOf("preference", "workflow", "project", "constraint")) { "记忆分类无效" }
        require(content.length in 8..180 && item.requiredString("fingerprint").isNotBlank() && UserMemoryStore.isSafeContent(content)) { "记忆内容无效或包含敏感信息" }
        val confidence = item.doubleOrDefault("confidence", 0.0)
        require(confidence in 0.8..1.0) { "记忆置信度无效" }
        UserMemoryEntity(
            id = item.getString("id"),
            projectId = projectId,
            category = category,
            content = content,
            fingerprint = item.getString("fingerprint"),
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            confidence = confidence,
            createdAt = item.longOrDefault("createdAt", System.currentTimeMillis()),
            updatedAt = item.longOrDefault("updatedAt", System.currentTimeMillis()),
            shareAcrossProjects = item.optBoolean("shareAcrossProjects", false)
        )
    }
    LocalBackupCompatibility.requireUniqueIds(restoredMemories.map { it.id }, "记忆")
    require(restoredMemories.map { it.projectId to it.fingerprint }.distinct().size == restoredMemories.size) {
        "同一项目空间内存在重复的记忆指纹"
    }
    val taskRows = root.arrayOrEmpty("phone_agent_tasks")
    require(taskRows.length() <= 500) { "Phone Agent 任务数量超出限制" }
    val validTaskStatuses = setOf(
        PhoneAgentTaskStore.STATUS_PLANNED, PhoneAgentTaskStore.STATUS_RUNNING,
        PhoneAgentTaskStore.STATUS_WAITING_USER, PhoneAgentTaskStore.STATUS_PAUSED,
        PhoneAgentTaskStore.STATUS_COMPLETED, PhoneAgentTaskStore.STATUS_FAILED,
        PhoneAgentTaskStore.STATUS_CANCELLED
    )
    val validTaskCategories = PhoneAgentPlanner.Category.values().map { it.name }.toSet()
    val restoredTasks = (0 until taskRows.length()).map { index ->
        val item = taskRows.getJSONObject(index)
        val id = item.requiredString("id")
        val projectId = item.requiredString("projectId")
        val conversationId = item.requiredString("conversationId")
        val goal = item.requiredString("goal")
        require(conversationId.length <= 120) { "Phone Agent 任务会话 ID 过长" }
        val category = item.requiredString("category")
        val sourceStatus = item.requiredString("status")
        val status = if (sourceStatus in setOf(PhoneAgentTaskStore.STATUS_PLANNED, PhoneAgentTaskStore.STATUS_RUNNING)) {
            PhoneAgentTaskStore.STATUS_PAUSED
        } else sourceStatus
        val planJson = item.stringOrDefault("planJson", "[]")
        val currentStep = item.intOrDefault("currentStep", 0)
        require(id.isNotBlank() && projectId in restoredProjectIds && goal.length in 1..2000) { "Phone Agent 任务字段无效" }
        require(conversationProjectById[conversationId]?.let { it == projectId } != false) { "Phone Agent 任务引用了其他项目空间的会话" }
        require(category in validTaskCategories && sourceStatus in validTaskStatuses) { "Phone Agent 任务状态或类型无效" }
        val planCount = PhoneAgentTaskStore.validatePlanJson(planJson)
        require(currentStep in 0..planCount) { "Phone Agent 任务步骤位置无效" }
        PhoneAgentTaskEntity(
            id = id,
            projectId = projectId,
            conversationId = conversationId,
            goal = goal,
            category = category,
            status = status,
            planJson = planJson,
            currentStep = currentStep,
            createdAt = item.longOrDefault("createdAt", System.currentTimeMillis()),
            updatedAt = item.longOrDefault("updatedAt", System.currentTimeMillis())
        )
    }
    val restoredTaskIds = restoredTasks.map { it.id }.toSet()
    require(restoredTaskIds.size == restoredTasks.size) { "Phone Agent 任务 ID 重复" }
    val taskLogRows = root.arrayOrEmpty("phone_agent_task_logs")
    require(taskLogRows.length() <= 5000) { "Phone Agent 审计记录数量超出限制" }
    val restoredTaskLogs = (0 until taskLogRows.length()).map { index ->
        val item = taskLogRows.getJSONObject(index)
        val id = item.requiredString("id")
        val taskId = item.requiredString("taskId")
        val actionIndex = item.intOrDefault("actionIndex", 0)
        val action = item.requiredString("action")
        val detail = item.requiredString("detail")
        require(taskId in restoredTaskIds) { "任务审计记录缺少所属任务" }
        require(actionIndex in 0..100 && action.length in 1..60 && detail.length <= 240) {
            "任务审计记录字段无效"
        }
        PhoneAgentTaskLogEntity(
            id = id,
            taskId = taskId,
            actionIndex = actionIndex,
            action = action,
            success = item.booleanOrDefault("success", false),
            detail = detail,
            createdAt = item.longOrDefault("createdAt", System.currentTimeMillis())
        )
    }
    LocalBackupCompatibility.requireUniqueIds(restoredTaskLogs.map { it.id }, "任务日志")

    val agentStateRows = root.arrayOrEmpty("agent_task_states_v1")
    val agentTraceRows = root.arrayOrEmpty("agent_task_traces_v1")
    require(agentStateRows.length() <= 1_000 && agentTraceRows.length() <= 20_000) {
        "Agent 任务状态或追踪记录数量超过恢复上限"
    }
    val validAgentTaskStatuses = app.nestro.agent.AgentTaskStatus.values().map { it.name }.toSet()
    val restoredAgentTaskStates = (0 until agentStateRows.length()).map { index ->
        val item = agentStateRows.getJSONObject(index)
        val taskId = item.requiredString("taskId")
        val owner = item.requiredString("owner")
        val sourceStatus = item.requiredString("status")
        val status = if (sourceStatus in setOf("QUEUED", "RUNNING")) "PAUSED" else sourceStatus
        val currentStep = item.intOrDefault("currentStep", 0)
        val checkpoint = item.stringOrDefault("checkpoint", "")
        val sourceError = item.stringOrDefault("lastError", "")
        val lastError = if (status != sourceStatus && sourceError.isBlank()) {
            "备份恢复后任务已暂停，请核对状态后手动继续"
        } else sourceError
        require(taskId.isNotBlank() && taskId.length <= 120 && owner.isNotBlank() && owner.length <= 80 &&
            sourceStatus in validAgentTaskStatuses && currentStep >= 0 && checkpoint.length <= 64_000 && sourceError.length <= 2_000) {
            "Agent 任务状态字段无效"
        }
        AgentTaskStateEntity(taskId, owner, status, currentStep, checkpoint, lastError,
            item.longOrDefault("updatedAt", System.currentTimeMillis()))
    }
    LocalBackupCompatibility.requireUniqueIds(restoredAgentTaskStates.map { it.taskId }, "Agent 任务状态")
    val restoredAgentTaskTraces = (0 until agentTraceRows.length()).map { index ->
        val item = agentTraceRows.getJSONObject(index)
        val id = item.requiredString("id")
        val taskId = item.requiredString("taskId")
        val event = item.requiredString("event")
        val detail = item.stringOrDefault("detail", "")
        require(id.isNotBlank() && id.length <= 120 && taskId.isNotBlank() && taskId.length <= 120 &&
            event.isNotBlank() && event.length <= 80 && detail.length <= 2_000) {
            "Agent 任务追踪记录字段无效"
        }
        AgentTaskTraceEntity(id, taskId, event, detail,
            item.longOrDefault("createdAt", System.currentTimeMillis()))
    }
    LocalBackupCompatibility.requireUniqueIds(restoredAgentTaskTraces.map { it.id }, "Agent 任务追踪")

    for (i in 0 until messages.length()) {
        val item = messages.getJSONObject(i)
        require(item.requiredString("role") in setOf("user", "assistant")) { "消息角色无效" }
        require(item.has("content") && item.has("id") && item.requiredString("id").isNotBlank() && item.requiredString("content").length <= 200_000) { "消息字段缺失或内容过长" }
    }
    val experts = JSONArray(root.getString("experts"))
    require(experts.length() <= 100) { "专家数量超出限制" }
    for (i in 0 until experts.length()) require(experts.getString(i).length <= 40) { "专家名称过长" }
    val expertNames = (0 until experts.length()).map { experts.getString(it) }.toSet()
    require(expertNames.size == experts.length()) { "专家名称重复" }
    val prompts = JSONObject(root.getString("expert_prompts"))
    val profileRaw = root.stringOrDefault("expert_profiles_v1", "{}")
    require(profileRaw.length <= 100_000) { "专家配置元数据过大" }
    val restoredExpertProfiles = ExpertProfileCodec.decode(profileRaw)
    require(profileRaw == "{}" || restoredExpertProfiles.isNotEmpty()) { "专家配置元数据格式无效" }
    require(restoredExpertProfiles.size <= 100 && restoredExpertProfiles.keys.all { it in expertNames }) { "专家配置元数据引用无效" }
    val restoredSelectedExpertRaw = root.stringOrDefault("selected_expert", "通用助手")
    val revisionRaw = root.stringOrDefault("expert_versions_v1", "{}")
    require(revisionRaw.length <= 500_000) { "专家版本历史过大" }
    val restoredExpertRevisions = ExpertRevisionCodec.decode(revisionRaw)
    require(revisionRaw == "{}" || restoredExpertRevisions.isNotEmpty()) { "专家版本历史格式无效" }
    require(restoredExpertRevisions.size <= 100 && restoredExpertRevisions.keys.all { it in expertNames }) { "专家版本历史引用无效" }
    val templateRaw = root.stringOrDefault("expert_templates_v1", "[]")
    require(templateRaw.length <= 300_000) { "专家模板数据过大" }
    val restoredExpertTemplates = ExpertTemplateCodec.decode(templateRaw)
    require(templateRaw == "[]" || restoredExpertTemplates.isNotEmpty()) { "专家模板格式无效" }
    require(restoredExpertTemplates.size <= 100) { "专家模板数量超出限制" }
    val teamArray = JSONArray(root.stringOrDefault("expert_teams_v1", "[]"))
    require(teamArray.length() <= 50) { "专家团队数量超出限制" }
    val cleanTeams = JSONArray()
    for (i in 0 until teamArray.length()) {
        val item = teamArray.getJSONObject(i)
        val members = item.arrayOrEmpty("members")
        val teamId = item.requiredString("id")
        val teamName = item.requiredString("name")
        val teamDescription = item.stringOrDefault("description", "")
        val memberNames = (0 until members.length()).map { members.getString(it) }
        require(teamId.isNotBlank() && teamName.isNotBlank() && teamName.length <= 40 && teamDescription.length <= 300) {
            "专家团队标识或文本字段无效"
        }
        require(memberNames.size in 2..4 && memberNames.distinct().size == memberNames.size && memberNames.all { it in expertNames }) {
            "专家团队成员缺失、重复或引用了不存在的专家"
        }
        cleanTeams.put(
            JSONObject().put("id", teamId).put("name", teamName)
                .put("description", teamDescription).put("members", JSONArray(memberNames))
        )
    }
    LocalBackupCompatibility.requireUniqueIds(
        (0 until cleanTeams.length()).map { cleanTeams.getJSONObject(it).getString("id") },
        "专家团队"
    )
    val selectedTeam = root.stringOrDefault("selected_team_id", "")
    val selectedTeamExists = (0 until cleanTeams.length())
        .any { cleanTeams.getJSONObject(it).requiredString("id") == selectedTeam }
    require(selectedTeam.isBlank() || selectedTeamExists) {
        "当前选中的专家团队不存在，拒绝恢复"
    }
    val validSelectedTeam = selectedTeam
    val skills = JSONObject(root.stringOrDefault("skills", "{}"))
    require(skills.length() <= 100) { "技能数量超出限制" }
    val skillMetadata = JSONObject(root.stringOrDefault("skill_metadata_v1", "{}"))
    require(skillMetadata.length() <= 100) { "技能元数据数量超出限制" }
    val disabledSkillArray = JSONArray(root.stringOrDefault("disabled_skills_v1", "[]"))
    require(disabledSkillArray.length()<=100) { "停用技能数量超出限制" }
    val disabledSkillNames = (0 until disabledSkillArray.length()).map { disabledSkillArray.getString(it) }.toSet()
    val cleanSkillMetadata = JSONObject()
    val metadataKeys = skillMetadata.keys()
    while (metadataKeys.hasNext()) {
        val name = metadataKeys.next()
        val metadata = skillMetadata.requiredString(name)
        require(name.length <= 40 && metadata.length <= 4000) { "技能元数据无效" }
        runCatching { JSONObject(metadata) }.getOrElse { error("技能元数据格式无效") }
        cleanSkillMetadata.put(name, metadata)
    }
    val cleanSkills = JSONObject()
    val skillKeys = skills.keys()
    while (skillKeys.hasNext()) {
        val name = skillKeys.next()
        val prompt = skills.requiredString(name)
        require(name.length <= 40 && prompt.length <= 8000) { "技能内容超出限制" }
        cleanSkills.put(name, prompt)
    }
    require(disabledSkillNames.all { cleanSkills.has(it) }) { "停用技能引用不存在的技能" }
    val availableSkillNames = buildSet { val names = cleanSkills.keys(); while (names.hasNext()) add(names.next()) }
    val restoredSelectedExpert = ExpertCatalogRules.resolveSelection(restoredSelectedExpertRaw, expertNames.toList(), restoredExpertProfiles)
    restoredExpertProfiles.forEach { (name, profile) ->
        val profileError = ExpertProfileCodec.validate(profile, availableSkillNames)
        require(profileError == null) { "专家「$name」配置无效：$profileError" }
    }
    restoredExpertRevisions.values.flatten().forEach { revision ->
        require(revision.prompt.length <= 8000) { "专家历史提示词过长" }
        val revisionError = ExpertProfileCodec.validate(revision.profile, availableSkillNames)
        require(revisionError == null) { "专家历史版本配置无效：$revisionError" }
    }
    restoredExpertTemplates.forEach { template ->
        val templateError = ExpertProfileCodec.validate(template.profile, availableSkillNames)
        require(templateError == null) { "专家模板「${template.name}」配置无效：$templateError" }
    }
    require(prompts.length() <= 100) { "专家提示词数量超出限制" }
    val cleanPrompts = JSONObject()
    val keys = prompts.keys()
    while (keys.hasNext()) {
        val name = keys.next()
        val prompt = prompts.requiredString(name)
        require(prompt.length <= 8_000) { "专家提示词过长" }
        cleanPrompts.put(name, prompt)
    }
    val normalizedConversationBackup = JSONArray()
    completeRestoredConversations.forEach { (conversation, rows) ->
        val messageArray = JSONArray()
        rows.forEach { message ->
            messageArray.put(JSONObject().put("id", message.id).put("role", message.role).put("content", message.content)
                .put("versions", JSONArray(message.versionsJson)).put("active_version", message.activeVersion))
        }
        normalizedConversationBackup.put(JSONObject().put("id", conversation.id).put("title", conversation.title)
            .put("updated_at", conversation.updatedAt).put("project_id", conversation.projectId).put("messages", messageArray))
    }
    val restoreSyncTimestamp = System.currentTimeMillis()
    val editor = prefs.edit()
        .putString("messages", messages.toString())
        .putString("conversations_v1", normalizedConversationBackup.toString())
        .putString("active_conversation_id", restoredActiveConversationId)
        .putString("global_memory_v1", globalMemory)
        .putBoolean("automatic_memory_enabled", root.booleanOrDefault("automatic_memory_enabled", false))
        .putBoolean("chat_task_mode_enabled", root.booleanOrDefault("chat_task_mode_enabled", false))
        .putString("reasoning_effort_v1", restoredReasoningEffort)
        .putFloat("tts_speech_rate", restoredTtsSpeechRate)
        .putString("phone_audit", restoredPhoneAudit.toString())
        .putString("experts", experts.toString())
        .putString("expert_prompts", cleanPrompts.toString())
        .putString("selected_expert", restoredSelectedExpert)
        .putString("expert_profiles_v1", ExpertProfileCodec.encode(restoredExpertProfiles))
        .putString("expert_versions_v1", ExpertRevisionCodec.encode(restoredExpertRevisions))
        .putString("expert_templates_v1", ExpertTemplateCodec.encode(restoredExpertTemplates))
        .putString("expert_teams_v1", cleanTeams.toString())
        .putString("selected_team_id", validSelectedTeam)
        .putString("skills", cleanSkills.toString())
        .putString("skill_metadata_v1", cleanSkillMetadata.toString())
        .putString("disabled_skills_v1", JSONArray(disabledSkillNames).toString())
        .putString("workflows_v1", WorkflowDefinitionCodec.encode(restoredWorkflows))
        .putString("workflow_runs_v1", WorkflowRunHistoryCodec.encode(restoredWorkflowHistory))
        .putString("expert_team_runs_v1", ExpertTeamRunHistoryCodec.encode(restoredTeamHistory))
        .putString("project_resource_scopes_v1", ProjectResourceScopeStore.encode(restoredResourceScopes))
        .putString("project_memory_settings_v1", ProjectMemorySettingsStore.encode(restoredProjectMemorySettings))
        .putString("projects", ProjectPreferencesStore.projectsToJson(restoredProjects))
        .putString("active_project_id", (if (root.has("active_project_id")) root.requiredString("active_project_id") else "default").also { id ->
            require(restoredProjects.any { it.id == id && it.archivedAt == 0L }) {
                "备份中的活动项目不存在或已归档，拒绝恢复"
            }
        })
        .putLong("local_snapshot_saved_at", restoreSyncTimestamp)
        .putLong("room_synced_at", restoreSyncTimestamp)
    // A project package is not a device backup: keep the user's local model catalog and routing settings.
    if (!preserveModelPreferences) {
        editor.putString("active_model_registry_id", "")
            .remove("base_url").remove("model").remove("model_profiles_v1")
            .remove("model_registry_v1").remove("active_model_profile_id")
            .remove("model_usage_v1").putBoolean("model_failover_enabled", false)
    }
    // In-flight UI operations belong to the device/process being restored, not the backup.
    editor.remove("chat_request_state_v1")
        .remove("pending_project_delete_id")
        .remove("phone_task_grant_until")
        .remove("phone_single_grant")
        .remove("phone_task_stop_requested")
    prefs.all.keys.filter { key ->
        key.startsWith("phone_agent_work_pending_") ||
            key.startsWith("phone_agent_cancel_requested_") ||
            key.startsWith("phone_agent_after_handoff_") ||
            key.startsWith("phone_agent_allow_retry_")
    }.forEach { editor.remove(it) }
    if (root.has("base_url") && root.has("model")) {
        val restoredBaseUrl = root.requiredString("base_url")
        val restoredModel = root.requiredString("model")
        require(restoredBaseUrl.length <= 2048 && restoredModel.length <= 200) { "模型配置字段过长" }
        val restoredProfilesRaw = root.stringOrDefault("model_profiles_v1", "[]")
        val restoredProfiles = ModelProfileCatalogCodec.decode(restoredProfilesRaw)
        require(restoredProfilesRaw.length <= 100_000 && (restoredProfilesRaw == "[]" || restoredProfiles.isNotEmpty())) { "模型配置清单无效" }
        val restoredActiveProfileId = root.stringOrDefault("active_model_profile_id", "")
        require(restoredActiveProfileId.isBlank() || restoredProfiles.any { it.id == restoredActiveProfileId }) {
            "当前选中的模型配置不存在，拒绝恢复"
        }
        val restoredModelUsageRaw = root.stringOrDefault("model_usage_v1", "{}")
        val restoredModelUsage = ModelUsageStatsCodec.decode(restoredModelUsageRaw)
        require(restoredModelUsageRaw.length <= 100_000 && (restoredModelUsageRaw == "{}" || restoredModelUsage.isNotEmpty())) { "模型用量记录无效" }
        val restoredRegistryRaw = root.stringOrDefault("model_registry_v1", "")
        val restoredRegistry = if (restoredRegistryRaw.isBlank()) null else {
            require(restoredRegistryRaw.length <= 100_000) { "模型目录备份过大" }
            (ModelRegistryCodec.decodeOrNull(restoredRegistryRaw)
                ?: error("模型目录备份格式无效")).also { registry ->
                require(registry.validate() == null && (registry.providers.isNotEmpty() || restoredProfiles.isEmpty())) { "模型目录备份无效" }
            }
        }
        val restoredActiveRegistryId = root.stringOrDefault("active_model_registry_id", "")
        require(restoredActiveRegistryId.isBlank() || restoredRegistry?.models?.any { it.id == restoredActiveRegistryId } == true) {
            "当前模型选择引用了不存在的模型目录项"
        }
        val restoredFailoverEnabled = root.booleanOrDefault("model_failover_enabled", false)
        editor.putString("base_url", restoredBaseUrl).putString("model", restoredModel)
            .putString("model_profiles_v1", ModelProfileCatalogCodec.encode(restoredProfiles))
            .putString("active_model_profile_id", restoredActiveProfileId)
            .putString("active_model_registry_id", restoredActiveRegistryId)
            .putString("model_usage_v1", ModelUsageStatsCodec.encode(restoredModelUsage))
            .putBoolean("model_failover_enabled", restoredFailoverEnabled)
        if (restoredRegistry != null) {
            editor.putString("model_registry_v1", ModelRegistryCodec.encode(restoredRegistry))
        } else {
            editor.remove("model_registry_v1")
        }
    }
    // Invalidate any memory extraction started against the data being replaced.
    // The version epoch is advanced only after the backup has been fully parsed/validated.
    UserMemoryStore.invalidateAndAwaitPendingExtractions()
    // A queued worker belongs to the device state being replaced. Stop the old queue
    // before swapping task rows so a stale worker cannot execute against restored data.
    app.nestro.phone.PhoneAgentWorkScheduler.cancelAll(context)
    val dao = database.dao()
    database.withTransaction {
        if (refuseActivePhoneTasks) requireNoActivePhoneAgentTasks(database)
        dao.deleteAllConversations()
        dao.deleteAllKnowledgeChunkFts()
        dao.deleteAllKnowledgeDocuments()
        dao.deleteAllUserMemories()
        dao.deleteAllPhoneAgentTasks()
        dao.deleteAllAgentTaskStates()
        dao.deleteAllAgentTaskTraces()
        dao.deleteAllProjects()
        if (restoredProjects.isNotEmpty()) dao.putProjects(restoredProjects.map { ProjectEntity(it.id, it.name, it.instructions, it.createdAt, it.archivedAt) })
        completeRestoredConversations.forEach { (conversation, rows) ->
            dao.putConversation(conversation)
            if (rows.isNotEmpty()) dao.putMessages(rows)
        }
        if (restoredKnowledgeDocuments.isNotEmpty()) dao.insertKnowledgeDocuments(restoredKnowledgeDocuments)
        if (restoredKnowledgeChunks.isNotEmpty()) {
            dao.insertKnowledgeChunks(restoredKnowledgeChunks)
            dao.insertKnowledgeChunkFts(restoredKnowledgeChunks.map { chunk ->
                KnowledgeChunkFtsEntity(chunk.id.stableFtsRowId(), chunk.id, chunk.projectId, chunk.content)
            })
        }
        restoredMemories.forEach { dao.putUserMemory(it) }
        restoredTasks.forEach { dao.putPhoneAgentTask(it) }
        restoredTaskLogs.forEach { dao.insertPhoneAgentTaskLog(it) }
        restoredAgentTaskStates.forEach { dao.putAgentTaskState(it) }
        restoredAgentTaskTraces.forEach { dao.insertAgentTaskTrace(it) }
        prefs.all.keys.filter { it.startsWith("draft_") }.forEach { editor.remove(it) }
        restoredDrafts.forEach { (id, value) -> editor.putString("draft_$id", value) }
        check(editor.commit()) { "本机设置写入失败，恢复未完成" }
    }
    // Calendar data is stored in its own Room database; restore it after the main database
    // transaction. The restore journal contains the previous calendar snapshot as well.
    CalendarReminderScheduler.cancelAll(context)
    val calendarDatabase = CalendarDatabase.get(context)
    calendarDatabase.withTransaction {
        val calendarDao = calendarDatabase.calendarDao()
        calendarDao.deleteAll()
        if (restoredCalendarEvents.isNotEmpty()) calendarDao.putAll(restoredCalendarEvents)
    }
    restoredCalendarEvents.forEach { CalendarReminderScheduler.schedule(context, it) }

    // Credentials are intentionally excluded from backups. Never retain secrets from the
    // pre-restore installation, even when a restored model happens to reuse the same ID.
    ModelConfigurationRepository(context).clearAllApiKeys()
    if (hasArtifactBackup) app.nestro.data.ArtifactStore.restoreBackup(context, restoredArtifactRows, restoredProjectIds)
}
