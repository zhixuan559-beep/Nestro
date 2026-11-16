package app.nestro.ui

import app.nestro.data.ConversationEntity
import app.nestro.data.MessageEntity
import app.nestro.data.ProjectEntity
import app.nestro.data.NestroDatabase
import app.nestro.data.KnowledgeStore
import app.nestro.data.ConversationMemoryStore
import app.nestro.data.ConversationRecallSource
import androidx.room.withTransaction

import app.nestro.phone.PhoneAgentService
import app.nestro.phone.PhoneAgentPlanner
import app.nestro.phone.PhoneAgentExecutor
import app.nestro.phone.PhoneAgentWorkScheduler
import app.nestro.data.PhoneAgentTaskStore
import app.nestro.data.UserMemoryStore
import app.nestro.data.MemoryCandidate
import app.nestro.data.UserMemoryEntity
import app.nestro.ui.VoiceInputButton

import android.content.Context
import android.net.Uri
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Bg = androidx.compose.ui.graphics.Color(0xFF0F1115)
private val Card = androidx.compose.ui.graphics.Color(0xFF1A1C20)
private val Card2 = androidx.compose.ui.graphics.Color(0xFF232529)
private val Mint = androidx.compose.ui.graphics.Color(0xFF34D399)
private val Ink = androidx.compose.ui.graphics.Color(0xFFE8EAED)
private val Muted = androidx.compose.ui.graphics.Color(0xFF8A8F98)
private val Line = androidx.compose.ui.graphics.Color(0xFF2A2D33)
private val ColorStop = androidx.compose.ui.graphics.Color(0xFFFF7777)

private data class ChatMessage(val id: String, val role: String, val content: String)
private data class ConversationRecord(val id: String, val title: String, val updatedAt: Long, val messages: List<ChatMessage>, val projectId: String = "default")
private data class ProjectRecord(val id: String, val name: String, val instructions: String, val createdAt: Long)
internal data class ModelConfig(val baseUrl: String, val model: String, val apiKey: String)

@Composable
fun NestroApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ttsReady by remember { mutableStateOf(false) }
    val speechEngine = remember(context) {
        TextToSpeech(context) { result ->
            if (result == TextToSpeech.SUCCESS) {
                ttsReady = true
            }
        }
    }
    DisposableEffect(speechEngine) {
        onDispose {
            speechEngine.stop()
            speechEngine.shutdown()
            ttsReady = false
        }
    }
    LaunchedEffect(speechEngine, ttsReady) {
        if (ttsReady) {
            speechEngine.language = Locale.SIMPLIFIED_CHINESE
            speechEngine.setSpeechRate(1.0f)
        }
    }
    val speakMessage: (String) -> Unit = { text ->
        if (ttsReady && text.isNotBlank()) {
            speechEngine.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }
    val stopSpeaking: () -> Unit = { speechEngine.stop() }
    val prefs = remember { context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE) }
    val database = remember { NestroDatabase.get(context) }
    var databaseReady by remember { mutableStateOf(false) }
    val persistedConversationVersions = remember { mutableStateMapOf<String, Int>() }
    var page by remember { mutableStateOf("home") }
    var showModelDialog by remember { mutableStateOf(false) }
    var showDrawer by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(ModelConfig("", "", "")) }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var conversations by remember { mutableStateOf(listOf<ConversationRecord>()) }
    var projects by remember { mutableStateOf(listOf(ProjectRecord("default", "默认空间", "", System.currentTimeMillis()))) }
    var activeProjectId by remember { mutableStateOf("default") }
    var showProjectDialog by remember { mutableStateOf(false) }
    var projectDraftName by remember { mutableStateOf("") }
    var projectDraftInstructions by remember { mutableStateOf("") }
    var editingProjectId by remember { mutableStateOf("") }
    var activeConversationId by remember { mutableStateOf("") }
    var conversationSearch by remember { mutableStateOf("") }
    var showRenameConversation by remember { mutableStateOf(false) }
    var renameConversationId by remember { mutableStateOf("") }
    var renameConversationDraft by remember { mutableStateOf("") }
    var globalMemory by remember { mutableStateOf("") }
    var memoryDraft by remember { mutableStateOf("") }
    var automaticMemories by remember { mutableStateOf(emptyList<UserMemoryEntity>()) }
    var memoryEdits by remember { mutableStateOf(emptyMap<String, String>()) }
    var memoryStatus by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("尚未配置模型") }
    var recallSources by remember { mutableStateOf(emptyList<ConversationRecallSource>()) }
    var showRecallDialog by remember { mutableStateOf(false) }
    var experts by remember { mutableStateOf(listOf("文案官", "选题狐", "代码佬")) }
    var expertPrompts by remember { mutableStateOf(loadExpertPrompts(prefs)) }
    var skills by remember { mutableStateOf(loadSkills(prefs)) }
    var selectedSkill by remember { mutableStateOf("") }
    var skillDraftName by remember { mutableStateOf("") }
    var skillDraftPrompt by remember { mutableStateOf("") }
    var showSkillDialog by remember { mutableStateOf(false) }
    var selectedExpert by remember { mutableStateOf("通用助手") }
    var expertDraft by remember { mutableStateOf("") }
    var expertPromptDraft by remember { mutableStateOf("") }
    var showExpertDialog by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf("备份不包含 API Key，恢复后仍需在本机配置模型。") }
    var voiceStatus by remember { mutableStateOf("语音由系统识别服务转写后发送；API Key 与对话记录保存在本机。") }
    val chatListState = rememberLazyListState()

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            backupStatus = runCatching {
                val json = buildLocalBackup(prefs)
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("无法创建备份文件")
                "备份已导出"
            }.getOrElse { "导出失败：${it.message ?: "未知错误"}" }
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            backupStatus = runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("无法读取文件")
                require(raw.length <= 10_000_000) { "备份文件过大" }
                restoreLocalBackup(prefs, raw)
                persistedConversationVersions.clear()
                conversations = loadConversations(prefs)
                projects = loadProjects(prefs)
                activeProjectId = prefs.getString("active_project_id", "default") ?: "default"
                activeConversationId = (prefs.getString("active_conversation_id", "") ?: "").takeIf { id -> conversations.any { it.id == id } } ?: conversations.firstOrNull()?.id.orEmpty()
                messages = conversations.firstOrNull { it.id == activeConversationId }?.messages ?: loadMessages(prefs)
                globalMemory = prefs.getString("global_memory_v1", "") ?: ""
                memoryDraft = globalMemory
                experts = loadExperts(prefs)
                expertPrompts = loadExpertPrompts(prefs)
                skills = loadSkills(prefs)
                "恢复完成。模型 Key 未被覆盖，请检查模型管理。"
            }.getOrElse { "恢复失败：${it.message ?: "文件格式无效"}" }
        }
    }

    LaunchedEffect(page, activeProjectId) {
        if (page == "memory") {
            automaticMemories = withContext(Dispatchers.IO) {
                runCatching { UserMemoryStore.list(database.dao(), activeProjectId) }.getOrDefault(emptyList())
            }
            memoryEdits = emptyMap()
        }
    }

    LaunchedEffect(activeConversationId) {
        recallSources = emptyList()
        draft = if (activeConversationId.isBlank()) "" else prefs.getString("draft_$activeConversationId", "") ?: ""
    }

    LaunchedEffect(Unit) {
        config = LocalVault.read(context)
        val loaded = runCatching {
            withContext(Dispatchers.IO) {
                val dao = database.dao()
                val stored = dao.loadConversations()
                val hasPendingLocalSnapshot = prefs.getLong("local_snapshot_saved_at", 0L) >
                    prefs.getLong("room_synced_at", 0L)
                if (stored.isEmpty() || hasPendingLocalSnapshot) {
                    val legacyConversations = loadConversations(prefs)
                    val legacyProjects = loadProjects(prefs)
                    database.withTransaction {
                        val conversationIds = legacyConversations.map { it.id }
                        val staleConversationIds = dao.conversationIds().filterNot { it in conversationIds }
                        if (staleConversationIds.isNotEmpty()) dao.deleteConversations(staleConversationIds)
                        legacyConversations.forEach { record ->
                            dao.putConversation(ConversationEntity(record.id, record.title, record.updatedAt, record.projectId))
                            dao.clearMessages(record.id)
                            val rows = record.messages.mapIndexed { index, message ->
                                MessageEntity(message.id, record.id, index, message.role, message.content)
                            }
                            if (rows.isNotEmpty()) dao.putMessages(rows)
                        }
                        dao.putProjects(legacyProjects.map { ProjectEntity(it.id, it.name, it.instructions, it.createdAt) })
                        val projectIds = legacyProjects.map { it.id }
                        val staleProjectIds = dao.projectIds().filterNot { it in projectIds }
                        if (staleProjectIds.isNotEmpty()) {
                            staleProjectIds.forEach { dao.deleteProjectAndKnowledge(it) }
                        }
                    }
                    prefs.edit().putLong("room_synced_at", prefs.getLong("local_snapshot_saved_at", 0L)).apply()
                    legacyConversations to legacyProjects
                } else {
                    val restoredConversations = stored.map { row ->
                        val entity = row.conversation
                        ConversationRecord(
                            entity.id, entity.title, entity.updatedAt,
                            row.messages.sortedBy { it.position }.map { message ->
                                ChatMessage(message.id, message.role, message.content)
                            },
                            entity.projectId
                        )
                    }
                    val storedProjects = dao.loadProjects().map { ProjectRecord(it.id, it.name, it.instructions, it.createdAt) }
                    val restoredProjects = if (storedProjects.isEmpty()) loadProjects(prefs) else storedProjects
                    if (storedProjects.isEmpty()) dao.putProjects(restoredProjects.map { ProjectEntity(it.id, it.name, it.instructions, it.createdAt) })
                    restoredConversations to restoredProjects
                }
            }
        }
        if (loaded.isSuccess) {
            val data = loaded.getOrThrow()
            conversations = data.first
            projects = data.second
            persistedConversationVersions.clear()
            data.first.forEach { persistedConversationVersions[it.id] = it.hashCode() }
            databaseReady = true
        } else {
            conversations = loadConversations(prefs)
            projects = loadProjects(prefs)
            status = "本地数据库暂不可用，已回退到本机旧存储"
        }
        activeProjectId = prefs.getString("active_project_id", "default") ?: "default"
        activeConversationId = (prefs.getString("active_conversation_id", "") ?: "").takeIf { id -> conversations.any { it.id == id } } ?: conversations.firstOrNull()?.id.orEmpty()
        messages = conversations.firstOrNull { it.id == activeConversationId }?.messages ?: emptyList()
        activeProjectId = conversations.firstOrNull { it.id == activeConversationId }?.projectId ?: activeProjectId
        prefs.edit().putString("active_project_id", activeProjectId).apply()
        globalMemory = prefs.getString("global_memory_v1", "") ?: ""
        memoryDraft = globalMemory
        experts = loadExperts(prefs)
        expertPrompts = loadExpertPrompts(prefs)
        status = if (config.apiKey.isNotBlank() && config.model.isNotBlank()) "模型已就绪" else status
    }

    LaunchedEffect(conversations, projects, databaseReady) {
        if (!databaseReady) return@LaunchedEffect
        val conversationSnapshot = conversations.toList()
        val projectSnapshot = projects.toList()
        val knownVersions = persistedConversationVersions.toMap()
        val snapshotTimestamp = prefs.getLong("local_snapshot_saved_at", 0L)
        runCatching {
            withContext(Dispatchers.IO) {
                val dao = database.dao()
                database.withTransaction {
                    val currentIds = conversationSnapshot.map { it.id }
                    val removedIds = dao.conversationIds().filterNot { it in currentIds }
                    if (removedIds.isNotEmpty()) dao.deleteConversations(removedIds)
                    conversationSnapshot.filter { knownVersions[it.id] != it.hashCode() }.forEach { record ->
                        dao.putConversation(ConversationEntity(record.id, record.title, record.updatedAt, record.projectId))
                        dao.clearMessages(record.id)
                        val rows = record.messages.mapIndexed { index, message ->
                            MessageEntity(message.id, record.id, index, message.role, message.content)
                        }
                        if (rows.isNotEmpty()) dao.putMessages(rows)
                    }
                    dao.putProjects(projectSnapshot.map { ProjectEntity(it.id, it.name, it.instructions, it.createdAt) })
                    val currentProjectIds = projectSnapshot.map { it.id }
                    val removedProjectIds = dao.projectIds().filterNot { it in currentProjectIds }
                    if (removedProjectIds.isNotEmpty()) {
                        removedProjectIds.forEach { dao.deleteProjectAndKnowledge(it) }
                    }
                }
            }
            persistedConversationVersions.clear()
            conversationSnapshot.forEach { persistedConversationVersions[it.id] = it.hashCode() }
            saveConversationList(prefs, conversationSnapshot, markDirty = false)
            saveProjects(prefs, projectSnapshot, markDirty = false)
            prefs.edit().putLong("room_synced_at", snapshotTimestamp).apply()
        }.onFailure {
            status = "本地数据保存失败：${it.message ?: "未知错误"}"
        }
    }


    val sendPrompt: (String) -> Unit = send@{ rawPrompt ->
        val prompt = rawPrompt.trim()
        if (prompt.isBlank() || busy) return@send
        draft = ""
        prefs.edit().remove("draft_$activeConversationId").apply()
        val user = ChatMessage(UUID.randomUUID().toString(), "user", prompt)
        messages = messages + user
        conversations = saveConversationMessages(prefs, activeConversationId, messages, activeProjectId)
        val creationKind = when {
            isCreationIntent(prompt, "专家") -> "expert"
            isCreationIntent(prompt, "技能") -> "skill"
            else -> ""
        }
        if (creationKind.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()) {
            busy = true
            stopRequested = false
            status = if (creationKind == "expert") "正在设计专家…" else "正在设计技能…"
            val requestConversationId = activeConversationId
            val requestProjectId = activeProjectId
            val requestHistory = messages.toList()
            val existingNames = if (creationKind == "expert") experts.joinToString("、") else skills.keys.joinToString("、")
            val generatorPrompt = buildString {
                append("你是 Nestro 的能力设计器。根据用户需求设计一个可直接使用的")
                append(if (creationKind == "expert") "AI 专家" else "工作技能")
                append("。只返回一个 JSON 对象，不要 Markdown 代码围栏或解释。字段必须是 name 和 prompt。name 是简短中文名称，prompt 是完整、具体、可执行的角色指令。不要要求扩大手机权限，不要包含可执行代码，不要虚构外部工具。现有名称：")
                append(existingNames.ifBlank { "无" })
                append("。名称必须与现有名称不同。")
            }
            scope.launch {
                val generated = withContext(Dispatchers.IO) {
                    runCatching {
                        val raw = OpenAiClient.complete(
                            config,
                            listOf(ChatMessage(UUID.randomUUID().toString(), "user", prompt)),
                            generatorPrompt
                        )
                        parseGeneratedDefinition(raw)
                    }
                }
                val assistantText = when {
                    stopRequested -> "能力设计已停止。"
                    generated.isSuccess -> "草案已生成，已打开预览。你可以修改指令，确认后才会保存。"
                    else -> "生成失败：${generated.exceptionOrNull()?.message ?: "请检查模型是否支持正常文本回复"}"
                }
                val completedHistory = requestHistory + ChatMessage(UUID.randomUUID().toString(), "assistant", assistantText)
                conversations = saveConversationMessages(prefs, requestConversationId, completedHistory, requestProjectId)
                if (activeConversationId == requestConversationId) messages = completedHistory
                if (!stopRequested && generated.isSuccess) {
                    val definition = generated.getOrThrow()
                    if (creationKind == "expert") {
                        expertDraft = definition.first
                        expertPromptDraft = definition.second
                        showExpertDialog = true
                    } else {
                        skillDraftName = definition.first
                        skillDraftPrompt = definition.second
                        showSkillDialog = true
                    }
                    status = "草案待你确认"
                } else {
                    status = if (stopRequested) "已停止生成" else "能力设计失败"
                }
                busy = false
                stopRequested = false
            }
            return@send
        }
        if (config.apiKey.isBlank() || config.model.isBlank()) {
            status = "请先配置模型 API"
            messages = messages + ChatMessage(UUID.randomUUID().toString(), "assistant", "还没有配置模型。进入「模型管理」，填写兼容 OpenAI Chat Completions 的 API 地址、模型名称和 Key。")
            conversations = saveConversationMessages(prefs, activeConversationId, messages, activeProjectId)
        } else {
            busy = true
            stopRequested = false
            val requestConversationId = activeConversationId
            val requestHistory = messages.toList()
            val assistantId = UUID.randomUUID().toString()
            val requestProjectId = activeProjectId
            val baseSystemContext = listOf(
                "全局记忆（跨会话共享，仅作为用户背景参考）：\n" + globalMemory,
                expertPrompts[selectedExpert].orEmpty(),
                projects.firstOrNull { it.id == requestProjectId }?.instructions.orEmpty(),
                skills[selectedSkill].orEmpty()
            ).filter { it.isNotBlank() }.joinToString("\n\n").take(12000)
            scope.launch {
                val phonePlan = PhoneAgentPlanner.plan(prompt)
                if (phonePlan.category != PhoneAgentPlanner.Category.GENERAL) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val task = PhoneAgentTaskStore.create(database.dao(), requestProjectId, requestConversationId, phonePlan)
                            if (PhoneAgentService.activeService?.hasValidGrant() == true) {
                                PhoneAgentWorkScheduler.enqueue(context, task.id)
                            }
                        }
                    }.onFailure { status = "任务计划保存失败：${it.message ?: "未知错误"}" }
                }
                val partialAnswer = StringBuilder()
                var recalledCount = 0
                val result = runCatching {
                    val recallQuery = requestHistory.takeLast(8)
                        .joinToString("\n") { it.content.take(500) }.take(2400)
                    val recall = ConversationMemoryStore.retrieve(database.dao(), requestProjectId, requestConversationId, recallQuery)
                    recalledCount = recall.hitCount
                    if (activeConversationId == requestConversationId) recallSources = recall.sources
                    val knowledgeContext = KnowledgeStore.retrieve(database.dao(), requestProjectId, prompt)
                    val memoryContext = UserMemoryStore.recall(database.dao(), requestProjectId, prompt).context
                    val requestSystemContext = listOf(baseSystemContext, PhoneAgentPlanner.contextFor(prompt), recall.context, knowledgeContext, memoryContext)
                        .filter { it.isNotBlank() }.joinToString("\n\n").take(16000)
                    OpenAiClient.stream(config, requestHistory, requestSystemContext) { chunk ->
                        partialAnswer.append(chunk)
                        withContext(Dispatchers.Main) {
                            if (activeConversationId == requestConversationId) {
                                messages = requestHistory + ChatMessage(assistantId, "assistant", partialAnswer.toString())
                            }
                        }
                    }
                }
                val finalText = when {
                    result.isSuccess -> result.getOrThrow()
                    partialAnswer.isNotEmpty() -> partialAnswer.toString()
                    else -> "请求失败：${result.exceptionOrNull()?.message ?: "请检查网络、地址和 Key"}"
                }
                val completedHistory = requestHistory + ChatMessage(assistantId, "assistant", finalText)
                conversations = saveConversationMessages(prefs, requestConversationId, completedHistory, requestProjectId)
                if (activeConversationId == requestConversationId) messages = completedHistory
                status = when {
                    stopRequested -> "已停止生成"
                    result.isSuccess -> if (recalledCount > 0) "已连接 · 参考历史 ${recalledCount} 条 · 本地保存" else "已连接 · 本地保存"
                    else -> "连接失败"
                }
                busy = false
                if (result.isSuccess && !stopRequested && shouldExtractAutomaticMemory(prompt)) {
                    val userFacts = requestHistory.filter { it.role == "user" }.takeLast(5).map { it.content } + prompt
                    scope.launch(Dispatchers.IO) {
                        val candidates = runCatching { extractMemoryCandidates(config, userFacts) }.getOrDefault(emptyList())
                        if (candidates.isNotEmpty()) {
                            runCatching {
                                UserMemoryStore.saveCandidates(
                                    database.dao(), requestProjectId, requestConversationId, user.id, candidates
                                )
                            }
                        }
                    }
                }
                stopRequested = false
            }
        }
    }

        LaunchedEffect(messages.size, busy) {
        val lastIndex = messages.lastIndex + if (busy) 1 else 0
        if (lastIndex >= 0) chatListState.animateScrollToItem(lastIndex)
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg, surface = Card, primary = Mint, onPrimary = Bg,
            onBackground = Ink, onSurface = Ink, secondary = Muted
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Row(Modifier.fillMaxSize()) {
                if (showDrawer) {
                    Column(
                        Modifier.width(286.dp).fillMaxHeight().background(Card).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Nestro", style = MaterialTheme.typography.headlineSmall, color = Mint)
                        Text("你的本机 AI 工作站", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        DrawerItem("⌂", "工作台") { page = "home"; showDrawer = false }
                        DrawerItem("☷", "对话历史") { page = "conversations"; showDrawer = false }
                        DrawerItem("✧", "全局记忆") { page = "memory"; showDrawer = false }
                        DrawerItem("▧", "本地知识库") { page = "knowledge"; showDrawer = false }
                        DrawerItem("▦", "项目空间") { page = "spaces"; showDrawer = false }
                        DrawerItem("⌁", "手机自动化") { page = "phone"; showDrawer = false }
                        DrawerItem("✦", "我的专家") { page = "experts"; showDrawer = false }
                        DrawerItem("◇", "能力中心") { page = "skills"; showDrawer = false }
                        DrawerItem("⚙", "模型管理") { page = "models"; showDrawer = false }
                        DrawerItem("▤", "数据与设置") { page = "settings"; showDrawer = false }
                        Spacer(Modifier.weight(1f))
                        Text("数据保存在本机", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showDrawer = !showDrawer }) {
                                Icon(Icons.Outlined.Menu, contentDescription = "菜单", tint = Ink)
                            }
                            Column {
                                Text("Nestro", color = Ink, style = MaterialTheme.typography.titleLarge)
                                Text(status, color = Muted, style = MaterialTheme.typography.labelSmall)
                                if (recallSources.isNotEmpty() && (page == "home" || page == "chat")) {
                                    TextButton(onClick = { showRecallDialog = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                                        Text("查看历史引用 · ${recallSources.size}", color = Mint, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { page = "models" }) {
                            Icon(Icons.Outlined.Tune, contentDescription = "模型设置", tint = Mint)
                        }
                    }

                    when (page) {
                        "home", "chat" -> {
                            if (messages.isEmpty()) {
                                Column(
                                    Modifier.weight(1f).fillMaxWidth().padding(22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        Modifier.size(76.dp).background(Mint, RoundedCornerShape(24.dp)),
                                        contentAlignment = Alignment.Center
                                    ) { Text("✦", color = Bg, style = MaterialTheme.typography.headlineLarge) }
                                    Spacer(Modifier.height(20.dp))
                                    Text("今天想创造点什么？", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                    Spacer(Modifier.height(8.dp))
                                    Text("连接自己的模型，组合专家、技能与知识", color = Muted)
                                    Spacer(Modifier.height(24.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Suggestion("写作创作") { draft = "帮我完成一项写作任务" }
                                        Suggestion("分析资料") { draft = "帮我分析这份资料" }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Suggestion("开发代码") { draft = "帮我规划并编写代码" }
                                        Suggestion("创建专家") { showExpertDialog = true }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    state = chatListState,
                                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(messages, key = { it.id }) { msg ->
                                        MessageBubble(msg, onSpeak = speakMessage, onStopSpeak = stopSpeaking, onRetry = {
                                            if (messages.lastOrNull()?.id == msg.id) {
                                                val lastUserIndex = messages.indexOfLast { it.role == "user" }
                                                if (lastUserIndex >= 0) {
                                                    val retryPrompt = messages[lastUserIndex].content
                                                    messages = messages.take(lastUserIndex)
                                                    conversations = saveConversationMessages(
                                                        prefs, activeConversationId, messages, activeProjectId
                                                    )
                                                    sendPrompt(retryPrompt)
                                                }
                                            }
                                        })
                                    }
                                    if (busy) item { Text("正在思考…", color = Mint, modifier = Modifier.padding(10.dp)) }
                                }
                            }
                            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AssistChip(onClick = { page = "spaces" }, label = { Text(projects.firstOrNull { it.id == activeProjectId }?.name ?: "默认空间") })
                                    AssistChip(onClick = { page = "models" }, label = { Text(config.model.ifBlank { "配置模型" }) })
                                    AssistChip(onClick = { page = "experts" }, label = { Text(selectedExpert) })
                                    AssistChip(onClick = { page = "skills" }, label = { Text(selectedSkill.ifBlank { "选择技能" }) })
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    VoiceInputButton(
                                        enabled = !busy,
                                        onPartialResult = { spoken ->
                                            draft = spoken
                                            if (activeConversationId.isNotBlank()) prefs.edit().putString("draft_$activeConversationId", spoken).apply()
                                        },
                                        onResult = { spoken -> draft = ""; sendPrompt(spoken) },
                                        onStatus = { voiceStatus = it }
                                    )
                                    OutlinedTextField(
                                        value = draft, onValueChange = { value ->
                                            draft = value
                                            if (activeConversationId.isNotBlank()) prefs.edit().putString("draft_$activeConversationId", value).apply()
                                        },
                                        modifier = Modifier.weight(1f),
                                        placeholder = { Text("输入或按住说话…", color = Muted) },
                                        trailingIcon = {
                                            if (busy) {
                                                IconButton(onClick = {
                                                    stopRequested = true
                                                    status = "正在停止…"
                                                    OpenAiClient.cancel()
                                                }) {
                                                    Icon(Icons.Outlined.Stop, contentDescription = "停止生成", tint = ColorStop)
                                                }
                                            } else {
                                                IconButton(enabled = draft.isNotBlank(), onClick = { sendPrompt(draft) }) {
                                                    Icon(Icons.Outlined.Send, contentDescription = "发送", tint = Mint)
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(22.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Mint, unfocusedBorderColor = Line,
                                            focusedTextColor = Ink, unfocusedTextColor = Ink,
                                            cursorColor = Mint
                                        )
                                    )
                                }
                                Text(voiceStatus, color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 7.dp))
                            }
                        }
                        "models" -> ModelManager(config, onSave = { newConfig ->
                            config = newConfig
                            LocalVault.write(context, newConfig)
                            status = if (newConfig.apiKey.isNotBlank()) "模型配置已保存" else "尚未配置模型"
                            page = "home"
                        })
                        "experts" -> {
                            Column(Modifier.weight(1f).padding(18.dp)) {
                                Text("我的专家", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("专家配置保存在本机。选择专家后可用于组织会话。", color = Muted)
                                Spacer(Modifier.height(14.dp))
                                experts.forEach { name ->
                                    Surface(
                                        color = if (selectedExpert == name) Card2 else Card,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedExpert = name; page = "home" }
                                    ) { Text("✦  $name", color = if (selectedExpert == name) Mint else Ink, modifier = Modifier.padding(16.dp)) }
                                }
                                Button(onClick = { showExpertDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                                    Text("＋ 创建专家")
                                }
                            }
                        }
                        "settings" -> {
                            Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("数据与设置", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("本地备份可迁移会话与专家配置。API Key 由当前设备的 Android Keystore 保护，不会写入备份。", color = Muted)
                                Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("本地数据备份", color = Ink, style = MaterialTheme.typography.titleMedium)
                                        Text("包含本机对话、全局记忆、专家配置与技能。API Key 和知识库原文暂不进入备份，迁移设备时需重新导入知识库文件。导出文件请自行保存到安全位置。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                        Button(onClick = {
                                            val name = "Nestro-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
                                            exportBackupLauncher.launch(name)
                                        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                                            Text("导出本地备份")
                                        }
                                        OutlinedButton(onClick = { importBackupLauncher.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.fillMaxWidth()) {
                                            Text("从备份恢复")
                                        }
                                        Text(backupStatus, color = if (backupStatus.startsWith("恢复失败") || backupStatus.startsWith("导出失败")) androidx.compose.ui.graphics.Color(0xFFF87171) else Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("隐私与安全", color = Ink, style = MaterialTheme.typography.titleMedium)
                                        Text("会话与专家数据保存在应用私有存储。API Key 使用 Android Keystore 的 AES-GCM 加密。备份不包含 Key，也不会上传到 Nestro 服务器。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        "conversations" -> {
                            Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("对话历史", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                OutlinedTextField(
                                    value = conversationSearch, onValueChange = { conversationSearch = it },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    label = { Text("搜索会话标题与内容") }
                                )
                                Button(onClick = {
                                    val id = UUID.randomUUID().toString()
                                    val record = ConversationRecord(id, "新对话", System.currentTimeMillis(), emptyList(), activeProjectId)
                                    conversations = listOf(record) + conversations
                                    activeConversationId = id
                                    messages = emptyList()
                                    prefs.edit().putString("active_conversation_id", id).putString("active_project_id", activeProjectId).apply()
                                    saveConversationList(prefs, conversations)
                                    page = "home"
                                }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg), modifier = Modifier.fillMaxWidth()) {
                                    Text("＋ 新建对话")
                                }
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(conversations.filter { record ->
                                        record.projectId == activeProjectId && (conversationSearch.isBlank() || record.title.contains(conversationSearch, true) ||
                                            record.messages.any { it.content.contains(conversationSearch, true) })
                                    }, key = { it.id }) { record ->
                                        Surface(color = if (record.id == activeConversationId) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f).clickable {
                                                    activeConversationId = record.id
                                                    activeProjectId = record.projectId
                                                    messages = record.messages
                                                    prefs.edit().putString("active_conversation_id", record.id).putString("active_project_id", record.projectId).apply()
                                                    page = "home"
                                                }) {
                                                    Text(record.title, color = if (record.id == activeConversationId) Mint else Ink)
                                                    Text(record.messages.lastOrNull()?.content?.take(72) ?: "空白对话", color = Muted, style = MaterialTheme.typography.bodySmall)
                                                }
                                                TextButton(onClick = {
                                                    renameConversationId = record.id
                                                    renameConversationDraft = record.title
                                                    showRenameConversation = true
                                                }) { Text("重命名", color = Mint) }
                                                TextButton(onClick = {
                                                    val remaining = conversations.filterNot { it.id == record.id }
                                                    if (activeConversationId == record.id) {
                                                        val next = remaining.firstOrNull { it.projectId == record.projectId }
                                                            ?: ConversationRecord(UUID.randomUUID().toString(), "新对话", System.currentTimeMillis(), emptyList(), record.projectId)
                                                        conversations = if (remaining.any { it.id == next.id }) remaining else listOf(next) + remaining
                                                        activeConversationId = next.id
                                                        activeProjectId = record.projectId
                                                        messages = next.messages
                                                        prefs.edit().putString("active_conversation_id", next.id).putString("active_project_id", record.projectId).apply()
                                                    } else {
                                                        conversations = remaining
                                                    }
                                                    saveConversationList(prefs, conversations)
                                                }) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "memory" -> {
                            Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("全局记忆", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("手动全局记忆会跨项目共享；自动记忆按项目隔离，只提取明确表达的稳定偏好、工作方式和项目约束。敏感信息不会自动保存。", color = Muted)
                                OutlinedTextField(
                                    value = memoryDraft, onValueChange = { memoryDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("写入希望所有会话都能参考的信息…") },
                                    minLines = 5
                                )
                                Button(onClick = {
                                    globalMemory = memoryDraft.trim().take(12000)
                                    prefs.edit().putString("global_memory_v1", globalMemory).apply()
                                    memoryStatus = "全局记忆已保存"
                                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                                    Text("保存全局记忆")
                                }
                                Text("自动记忆 · 当前项目", color = Ink, style = MaterialTheme.typography.titleMedium)
                                Text("来源仅限用户明确说过的内容，可逐条修改或删除。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                if (automaticMemories.isEmpty()) {
                                    Text("当前项目还没有自动记忆。明确表达长期偏好或项目约束后，会在后台尝试提取。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                } else {
                                    automaticMemories.forEach { memory ->
                                        Surface(color = Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("${memory.category} · 置信度 ${"%.0f".format(memory.confidence * 100)}%", color = Mint, style = MaterialTheme.typography.labelSmall)
                                                OutlinedTextField(
                                                    value = memoryEdits[memory.id] ?: memory.content,
                                                    onValueChange = { memoryEdits = memoryEdits + (memory.id to it) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    minLines = 2
                                                )
                                                Text("来源会话：${memory.sourceConversationId.take(8)} · 消息：${memory.sourceMessageId.take(8)}", color = Muted, style = MaterialTheme.typography.labelSmall)
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Button(onClick = {
                                                        scope.launch {
                                                            val result = withContext(Dispatchers.IO) {
                                                                runCatching {
                                                                    UserMemoryStore.edit(
                                                                        database.dao(), memory.id, activeProjectId,
                                                                        memoryEdits[memory.id] ?: memory.content, memory.category
                                                                    )
                                                                }
                                                            }
                                                            memoryStatus = result.fold({ "记忆已更新" }, { "更新失败：${it.message ?: "内容无效"}" })
                                                            if (result.isSuccess) {
                                                                automaticMemories = withContext(Dispatchers.IO) { UserMemoryStore.list(database.dao(), activeProjectId) }
                                                                memoryEdits = memoryEdits - memory.id
                                                            }
                                                        }
                                                    }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) { Text("保存修改") }
                                                    TextButton(onClick = {
                                                        scope.launch {
                                                            withContext(Dispatchers.IO) { UserMemoryStore.delete(database.dao(), memory.id, activeProjectId) }
                                                            automaticMemories = automaticMemories.filterNot { it.id == memory.id }
                                                            memoryEdits = memoryEdits - memory.id
                                                            memoryStatus = "记忆已删除"
                                                        }
                                                    }) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (memoryStatus.isNotBlank()) Text(memoryStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        "spaces" -> {
                            Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("项目空间", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("每个空间拥有独立会话与项目指令。切换空间不会把其他项目的聊天记录注入当前请求。", color = Muted)
                                Button(onClick = {
                                    editingProjectId = ""
                                    projectDraftName = ""
                                    projectDraftInstructions = ""
                                    showProjectDialog = true
                                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                                    Text("＋ 创建项目空间")
                                }
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(projects, key = { it.id }) { project ->
                                        val count = conversations.count { it.projectId == project.id }
                                        Surface(color = if (project.id == activeProjectId) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Column(Modifier.weight(1f).clickable {
                                                        activeProjectId = project.id
                                                        prefs.edit().putString("active_project_id", project.id).apply()
                                                        val target = conversations.firstOrNull { it.projectId == project.id }
                                                        if (target == null) {
                                                            val created = ConversationRecord(UUID.randomUUID().toString(), "新对话", System.currentTimeMillis(), emptyList(), project.id)
                                                            conversations = listOf(created) + conversations
                                                            saveConversationList(prefs, conversations)
                                                            activeConversationId = created.id
                                                            messages = emptyList()
                                                        } else {
                                                            activeConversationId = target.id
                                                            messages = target.messages
                                                        }
                                                        prefs.edit().putString("active_conversation_id", activeConversationId).apply()
                                                        page = "home"
                                                    }) {
                                                        Text(project.name, color = if (project.id == activeProjectId) Mint else Ink, style = MaterialTheme.typography.titleMedium)
                                                        Text("$count 个对话 · 点击切换", color = Muted, style = MaterialTheme.typography.bodySmall)
                                                    }
                                                    TextButton(onClick = {
                                                        editingProjectId = project.id
                                                        projectDraftName = project.name
                                                        projectDraftInstructions = project.instructions
                                                        showProjectDialog = true
                                                    }) { Text("编辑", color = Mint) }
                                                    if (project.id != "default") {
                                                        TextButton(onClick = {
                                                            if (count == 0) {
                                                                scope.launch {
                                                                    runCatching {
                                                                        database.dao().deleteProjectAndKnowledge(project.id)
                                                                    }.onSuccess {
                                                                        projects = projects.filterNot { it.id == project.id }
                                                                        saveProjects(prefs, projects)
                                                                        status = "项目空间及其知识库已删除"
                                                                    }.onFailure {
                                                                        status = "删除失败，项目空间已保留"
                                                                    }
                                                                }
                                                            } else status = "该空间还有对话，暂时不能删除"
                                                        }) { Text("删除", color = ColorStop) }
                                                    }
                                                }
                                                Text(project.instructions.ifBlank { "尚未设置项目指令" }.take(180), color = Muted, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "phone" -> PhoneAgentPage(context, database, activeProjectId)
                        "knowledge" -> KnowledgePage(database, activeProjectId)
                        "skills" -> {
                            Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("本地技能库", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("技能是可复用的工作指令。选择后会与当前专家提示词组合，仅影响新发送的请求。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                skills.forEach { (name, prompt) ->
                                    Surface(color = if (selectedSkill == name) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f).clickable { selectedSkill = if (selectedSkill == name) "" else name; page = "home" }) {
                                                Text(name, color = if (selectedSkill == name) Mint else Ink, style = MaterialTheme.typography.titleMedium)
                                                Text(prompt.take(100), color = Muted, style = MaterialTheme.typography.bodySmall)
                                            }
                                            if (name !in DEFAULT_SKILLS.keys) {
                                                TextButton(onClick = {
                                                    skills = skills - name
                                                    prefs.edit().putString("skills", skillMapToJson(skills)).apply()
                                                    if (selectedSkill == name) selectedSkill = ""
                                                }) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                                            }
                                        }
                                    }
                                }
                                Button(onClick = { showSkillDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                                    Text("＋ 创建本地技能")
                                }
                                OutlinedButton(onClick = { selectedSkill = ""; page = "home" }, modifier = Modifier.fillMaxWidth()) { Text("本轮不使用技能") }
                                Text("外部 Skill / MCP 仓库导入仍未开放。导入功能上线前不会执行第三方代码。", color = Muted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        else -> SimplePage("Nestro", "本地优先的开放式 AI 工作站")
                    }
                }
            }
        }
    }

    if (showProjectDialog) {
        AlertDialog(
            onDismissRequest = { showProjectDialog = false },
            title = { Text(if (editingProjectId.isBlank()) "创建项目空间" else "编辑项目空间") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(projectDraftName, { projectDraftName = it.take(40) }, singleLine = true, label = { Text("项目名称") })
                    OutlinedTextField(projectDraftInstructions, { projectDraftInstructions = it.take(4000) }, minLines = 4, label = { Text("项目指令（可选）") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = projectDraftName.trim()
                    if (name.isNotBlank()) {
                        if (editingProjectId.isBlank()) {
                            val project = ProjectRecord(UUID.randomUUID().toString(), name, projectDraftInstructions.trim(), System.currentTimeMillis())
                            projects = listOf(project) + projects
                            activeProjectId = project.id
                            val conversation = ConversationRecord(UUID.randomUUID().toString(), "新对话", System.currentTimeMillis(), emptyList(), project.id)
                            conversations = listOf(conversation) + conversations
                            activeConversationId = conversation.id
                            messages = emptyList()
                            prefs.edit().putString("active_project_id", project.id).putString("active_conversation_id", conversation.id).apply()
                            saveConversationList(prefs, conversations)
                            page = "home"
                        } else {
                            projects = projects.map { if (it.id == editingProjectId) it.copy(name = name, instructions = projectDraftInstructions.trim()) else it }
                        }
                        saveProjects(prefs, projects)
                    }
                    showProjectDialog = false
                }) { Text("保存", color = Mint) }
            },
            dismissButton = { TextButton(onClick = { showProjectDialog = false }) { Text("取消") } }
        )
    }

    if (showRecallDialog) {
        AlertDialog(
            onDismissRequest = { showRecallDialog = false },
            title = { Text("本次回答参考的历史") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    recallSources.forEach { source ->
                        Column(Modifier.fillMaxWidth().clickable {
                            val target = conversations.firstOrNull { it.id == source.conversationId }
                            if (target != null) {
                                activeConversationId = target.id
                                activeProjectId = target.projectId
                                messages = target.messages
                                prefs.edit().putString("active_conversation_id", target.id).putString("active_project_id", target.projectId).apply()
                                page = "home"
                            }
                            showRecallDialog = false
                        }.padding(vertical = 9.dp)) {
                            Text(source.title, color = Mint, style = MaterialTheme.typography.titleSmall)
                            Text("${if (source.role == "user") "用户" else "Nestro"} · 消息 ${source.messageId.take(8)}", color = Muted, style = MaterialTheme.typography.labelSmall)
                            Text(source.excerpt, color = Ink, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                        }
                        HorizontalDivider(color = Line)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRecallDialog = false }) { Text("关闭", color = Mint) } }
        )
    }

    if (showRenameConversation) {
        AlertDialog(
            onDismissRequest = { showRenameConversation = false },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = renameConversationDraft,
                    onValueChange = { renameConversationDraft = it.take(80) },
                    singleLine = true,
                    label = { Text("对话名称") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = renameConversationDraft.trim()
                    if (title.isNotBlank()) {
                        conversations = conversations.map { record ->
                            if (record.id == renameConversationId) record.copy(title = title, updatedAt = System.currentTimeMillis()) else record
                        }
                        saveConversationList(prefs, conversations)
                    }
                    showRenameConversation = false
                }) { Text("保存", color = Mint) }
            },
            dismissButton = { TextButton(onClick = { showRenameConversation = false }) { Text("取消") } }
        )
    }

    if (showSkillDialog) {
        AlertDialog(
            onDismissRequest = { showSkillDialog = false },
            title = { Text("创建本地技能") },
            text = {
                Column {
                    OutlinedTextField(value = skillDraftName, onValueChange = { skillDraftName = it }, label = { Text("技能名称") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = skillDraftPrompt, onValueChange = { skillDraftPrompt = it }, label = { Text("工作指令") }, placeholder = { Text("描述技能要如何处理任务、输出格式和注意事项。") }, minLines = 4)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = skillDraftName.trim()
                    val prompt = skillDraftPrompt.trim()
                    if (name.isNotBlank() && prompt.isNotBlank() && name.length <= 40 && prompt.length <= 8000) {
                        skills = skills + (name to prompt)
                        prefs.edit().putString("skills", skillMapToJson(skills)).apply()
                        selectedSkill = name
                    }
                    skillDraftName = ""
                    skillDraftPrompt = ""
                    showSkillDialog = false
                }) { Text("保存", color = Mint) }
            },
            dismissButton = { TextButton(onClick = { showSkillDialog = false }) { Text("取消") } }
        )
    }

    if (showExpertDialog) {
        AlertDialog(
            onDismissRequest = { showExpertDialog = false },
            title = { Text("创建专家") },
            text = {
                Column {
                    OutlinedTextField(value = expertDraft, onValueChange = { expertDraft = it }, label = { Text("专家名称") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = expertPromptDraft, onValueChange = { expertPromptDraft = it }, label = { Text("专家角色与工作要求") }, placeholder = { Text("例如：你是一位严谨的中文文案编辑，先判断目标读者，再给出可直接发布的文案。") }, minLines = 4)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = expertDraft.trim()
                    if (name.isNotEmpty() && name !in experts) {
                        experts = experts + name
                        expertPrompts = expertPrompts + (name to expertPromptDraft.trim())
                        prefs.edit().putString("experts", JSONArray(experts).toString())
                            .putString("expert_prompts", expertPromptsToJson(expertPrompts)).apply()
                        selectedExpert = name
                    }
                    expertDraft = ""
                    expertPromptDraft = ""
                    showExpertDialog = false
                    page = "experts"
                }) { Text("创建", color = Mint) }
            },
            dismissButton = { TextButton(onClick = { showExpertDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun DrawerItem(icon: String, label: String, onClick: () -> Unit) {
    Surface(color = Card2, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text("$icon   $label", color = Ink, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun Suggestion(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Card, shape = RoundedCornerShape(50)) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Ink)
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onSpeak: ((String) -> Unit)? = null, onStopSpeak: (() -> Unit)? = null, onRetry: (() -> Unit)? = null) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Surface(
                color = if (user) androidx.compose.ui.graphics.Color(0xFF173B30) else Card,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(message.content, color = Ink, modifier = Modifier.padding(13.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (!user && message.content.isNotBlank() && onSpeak != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onSpeak.invoke(message.content) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = null, tint = Mint, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("朗读", color = Mint, style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { onStopSpeak?.invoke() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Icon(Icons.Outlined.Stop, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp))
                        Text("停止", color = Muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (!user && message.content.startsWith("请求失败：") && onRetry != null) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, tint = Mint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("重试这条消息", color = Mint, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ModelManager(config: ModelConfig, onSave: (ModelConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf("") }
    var endpoint by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    var key by remember { mutableStateOf(config.apiKey) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模型管理", color = Ink, style = MaterialTheme.typography.headlineSmall)
        Text("支持 OpenAI Chat Completions 兼容接口。服务商可自行选择。", color = Muted)
        OutlinedTextField(endpoint, { endpoint = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API 地址") }, placeholder = { Text("https://api.example.com/v1") }, singleLine = true)
        OutlinedTextField(model, { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text("模型名称") }, placeholder = { Text("例如 deepseek-chat") }, singleLine = true)
        OutlinedTextField(key, { key = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
        Text("Key 只保存在本机加密存储，不会写入仓库。", color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onSave(ModelConfig(endpoint.trim().trimEnd('/'), model.trim(), key.trim())) },
            enabled = endpoint.startsWith("https://") && model.isNotBlank() && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
        ) { Text("保存模型配置") }
        OutlinedButton(
            onClick = {
                testing = true
                testStatus = "正在测试连接…"
                val candidate = ModelConfig(endpoint.trim().trimEnd('/'), model.trim(), key.trim())
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { OpenAiClient.complete(candidate, listOf(ChatMessage(UUID.randomUUID().toString(), "user", "Reply with OK.")), "") }
                    }
                    testStatus = result.fold({ "连接成功：模型已返回内容。" }, { "连接失败：${it.message ?: "请检查地址、Key 与网络"}" })
                    testing = false
                }
            },
            enabled = !testing && endpoint.startsWith("https://") && model.isNotBlank() && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (testing) "测试中…" else "测试连接") }
        if (testStatus.isNotBlank()) Text(testStatus, color = if (testStatus.startsWith("连接失败")) androidx.compose.ui.graphics.Color(0xFFF87171) else Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { onSave(ModelConfig(endpoint.trim().trimEnd('/'), model.trim(), "")) },
            enabled = config.apiKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("清除本机 API Key") }
    }
}

@Composable
private fun SimplePage(title: String, description: String) {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Ink, style = MaterialTheme.typography.headlineSmall)
        Text(description, color = Muted, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun shouldExtractAutomaticMemory(prompt: String): Boolean {
    val signals = listOf("记住", "以后", "长期", "我喜欢", "我不喜欢", "我偏好", "我习惯", "我的工作", "我负责", "我正在做", "这个项目", "不要再", "我希望你", "我更倾向", "默认")
    return prompt.length in 8..4000 && signals.any { prompt.contains(it) }
}

private fun extractMemoryCandidates(config: ModelConfig, userMessages: List<String>): List<MemoryCandidate> {
    val system = """
你是本地记忆候选提取器。只返回 JSON 对象：{"memories":[{"category":"preference","content":"...","confidence":0.9}]}。
只从用户消息中提取明确陈述、可长期复用的偏好、工作方式、项目事实或约束。
category 只能是 preference、workflow、project、constraint。
禁止推断；禁止提取助手说过但用户未确认的内容；不要把提问、一次性任务、假设当作长期事实。
绝不提取或复述健康、医疗、财务/债务、政治、宗教、种族、性取向、身份凭据、精确住址、电话号码、邮箱、证件或银行卡等敏感信息。
置信度低于 0.8 的内容不要返回。最多 5 条，每条 8 到 180 个字符。
没有合格内容时返回 {"memories":[]}。
""".trimIndent()
    val user = userMessages.takeLast(6).joinToString("\n").take(6000)
    val raw = OpenAiClient.complete(
        config,
        listOf(ChatMessage(UUID.randomUUID().toString(), "user", user)),
        system
    )
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return emptyList()
    val rows = JSONObject(raw.substring(start, end + 1)).optJSONArray("memories") ?: return emptyList()
    return (0 until rows.length()).mapNotNull { index ->
        val item = rows.optJSONObject(index) ?: return@mapNotNull null
        MemoryCandidate(
            category = item.optString("category"),
            content = item.optString("content"),
            confidence = item.optDouble("confidence", 0.0)
        )
    }.take(5)
}

private fun isCreationIntent(prompt: String, target: String): Boolean {
    val text = prompt.trim()
    val hasCreationVerb = listOf("创建", "新建", "设计", "生成", "做一个", "帮我做").any { text.contains(it) }
    val hasNegation = listOf("不要", "不需要", "取消", "别创建").any { text.contains(it) }
    return text.contains(target) && hasCreationVerb && !hasNegation
}

private fun parseGeneratedDefinition(raw: String): Pair<String, String> {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    require(start >= 0 && end > start) { "模型没有返回有效 JSON" }
    val json = JSONObject(raw.substring(start, end + 1))
    val name = json.optString("name").trim()
    val prompt = json.optString("prompt").trim()
    require(name.isNotBlank() && name.length <= 40) { "能力名称为空或过长" }
    require(prompt.length in 80..8000) { "能力指令长度不符合要求" }
    return name to prompt
}

private fun boundedRequestHistory(history: List<ChatMessage>, maxChars: Int = 48000): List<ChatMessage> {
    var remaining = maxChars
    val selected = ArrayList<ChatMessage>(minOf(history.size, 40))
    for (message in history.asReversed().take(40)) {
        if (remaining <= 0) break
        val content = if (message.content.length > remaining) message.content.takeLast(remaining) else message.content
        selected += message.copy(content = content)
        remaining -= content.length
    }
    return selected.asReversed()
}

private object OpenAiClient {
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun cancel() { activeConnection?.disconnect() }

    fun complete(config: ModelConfig, history: List<ChatMessage>, systemPrompt: String): String {
        val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + config.apiKey)
        }
        activeConnection = connection
        try {
            val body = JSONObject().apply {
                put("model", config.model)
                put("messages", JSONArray().apply {
                    if (systemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", systemPrompt))
                    boundedRequestHistory(history).forEach { msg ->
                        put(JSONObject().put("role", msg.role).put("content", msg.content))
                    }
                })
                put("stream", false)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "HTTP " + code)
            }
            return JSONObject(response).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    suspend fun stream(config: ModelConfig, history: List<ChatMessage>, systemPrompt: String, onChunk: suspend (String) -> Unit): String = withContext(Dispatchers.IO) {
        val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + config.apiKey)
            setRequestProperty("Accept", "text/event-stream")
        }
        activeConnection = connection
        val answer = StringBuilder()
        val pending = StringBuilder()
        try {
            val body = JSONObject().apply {
                put("model", config.model)
                put("messages", JSONArray().apply {
                    if (systemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", systemPrompt))
                    boundedRequestHistory(history).forEach { msg -> put(JSONObject().put("role", msg.role).put("content", msg.content)) }
                })
                put("stream", true)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val response = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "HTTP $code")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data:")) {
                        val data = line.substringAfter("data:").trim()
                        if (data == "[DONE]") break
                        val delta = runCatching {
                            JSONObject(data).getJSONArray("choices").getJSONObject(0).optJSONObject("delta")?.optString("content").orEmpty()
                        }.getOrDefault("")
                        if (delta.isNotEmpty()) {
                            answer.append(delta)
                            pending.append(delta)
                            if (pending.length >= 24) {
                                val chunk = pending.toString()
                                pending.setLength(0)
                                onChunk(chunk)
                            }
                        }
                    } else if (line.trimStart().startsWith("{")) {
                        val fallback = runCatching { JSONObject(line).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content") }.getOrNull()
                        if (!fallback.isNullOrEmpty()) { answer.append(fallback); pending.append(fallback) }
                    }
                }
            }
            if (pending.isNotEmpty()) onChunk(pending.toString())
            answer.toString()
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }
}
internal object LocalVault {
    private const val ALIAS = "nestro_api_key_v1"
    fun write(context: Context, config: ModelConfig) {
        val prefs = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(config.apiKey.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString("base_url", config.baseUrl).putString("model", config.model)
            .putString("api_key_enc", packed).apply()
    }
    fun read(context: Context): ModelConfig {
        val prefs = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val packed = prefs.getString("api_key_enc", null)
        val key = runCatching {
            if (packed.isNullOrBlank()) "" else {
                val parts = packed.split(":")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
            }
        }.getOrDefault("")
        return ModelConfig(prefs.getString("base_url", "") ?: "", prefs.getString("model", "") ?: "", key)
    }
    private fun getKey(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}

private fun loadConversations(prefs: android.content.SharedPreferences): List<ConversationRecord> {
    val stored = runCatching {
        val array = JSONArray(prefs.getString("conversations_v1", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val messagesJson = item.optJSONArray("messages") ?: JSONArray()
            val parsed = (0 until messagesJson.length()).map { i ->
                val message = messagesJson.getJSONObject(i)
                ChatMessage(message.getString("id"), message.getString("role"), message.getString("content"))
            }
            ConversationRecord(
                item.getString("id"), item.optString("title", "对话"),
                item.optLong("updated_at", System.currentTimeMillis()), parsed, item.optString("project_id", "default").ifBlank { "default" }
            )
        }
    }.getOrDefault(emptyList())
    if (stored.isNotEmpty()) return stored
    val legacy = loadMessages(prefs)
    val migrated = ConversationRecord(
        UUID.randomUUID().toString(),
        legacy.firstOrNull { it.role == "user" }?.content?.take(24)?.ifBlank { "旧对话" } ?: "旧对话",
        System.currentTimeMillis(), legacy
    )
    val result = listOf(migrated)
    saveConversationList(prefs, result)
    prefs.edit().putString("active_conversation_id", migrated.id).apply()
    return result
}

private fun saveConversationList(prefs: android.content.SharedPreferences, conversations: List<ConversationRecord>, markDirty: Boolean = true) {
    val array = JSONArray()
    conversations.take(100).forEach { conversation ->
        val messages = JSONArray()
        conversation.messages.takeLast(500).forEach { message ->
            messages.put(JSONObject().put("id", message.id).put("role", message.role).put("content", message.content))
        }
        array.put(JSONObject().put("id", conversation.id).put("title", conversation.title.take(80))
            .put("updated_at", conversation.updatedAt).put("project_id", conversation.projectId).put("messages", messages))
    }
    val editor = prefs.edit().putString("conversations_v1", array.toString())
    if (markDirty) editor.putLong("local_snapshot_saved_at", System.currentTimeMillis())
    editor.apply()
}

private fun saveConversationMessages(prefs: android.content.SharedPreferences, activeId: String, messages: List<ChatMessage>, projectId: String = "default"): List<ConversationRecord> {
    val existing = loadConversations(prefs)
    val record = existing.firstOrNull { it.id == activeId }
        ?: ConversationRecord(activeId.ifBlank { UUID.randomUUID().toString() }, "新对话", System.currentTimeMillis(), emptyList(), projectId)
    val firstPrompt = messages.firstOrNull { it.role == "user" }?.content?.take(32)?.ifBlank { "新对话" } ?: record.title
    val updated = record.copy(title = if (record.title == "新对话" || record.title == "旧对话") firstPrompt else record.title,
        updatedAt = System.currentTimeMillis(), messages = messages.takeLast(500))
    val next = listOf(updated) + existing.filterNot { it.id == updated.id }
    saveConversationList(prefs, next)
    prefs.edit().putString("active_conversation_id", updated.id).apply()
    return next
}


private fun loadProjects(prefs: android.content.SharedPreferences): List<ProjectRecord> {
    val parsed = runCatching {
        val array = JSONArray(prefs.getString("projects", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ProjectRecord(item.getString("id"), item.optString("name", "项目").take(40),
                item.optString("instructions", "").take(4000), item.optLong("created_at", System.currentTimeMillis()))
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }.getOrDefault(emptyList())
    val all = if (parsed.any { it.id == "default" }) parsed else listOf(ProjectRecord("default", "默认空间", "", System.currentTimeMillis())) + parsed
    return all.distinctBy { it.id }.take(100)
}

private fun parseProjects(array: JSONArray?): List<ProjectRecord> {
    val parsed = if (array == null) emptyList() else runCatching {
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ProjectRecord(item.getString("id"), item.optString("name", "项目"), item.optString("instructions", ""), item.optLong("created_at", System.currentTimeMillis()))
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }.getOrDefault(emptyList())
    return (if (parsed.any { it.id == "default" }) parsed else listOf(ProjectRecord("default", "默认空间", "", System.currentTimeMillis())) + parsed).take(100)
}

private fun projectsToJson(projects: List<ProjectRecord>): String {
    val array = JSONArray()
    projects.take(100).forEach { project ->
        array.put(JSONObject().put("id", project.id).put("name", project.name.take(40))
            .put("instructions", project.instructions.take(4000)).put("created_at", project.createdAt))
    }
    return array.toString()
}

private fun saveProjects(prefs: android.content.SharedPreferences, projects: List<ProjectRecord>, markDirty: Boolean = true) {
    val editor = prefs.edit().putString("projects", projectsToJson(projects))
    if (markDirty) editor.putLong("local_snapshot_saved_at", System.currentTimeMillis())
    editor.apply()
}

private fun loadMessages(prefs: android.content.SharedPreferences): List<ChatMessage> = runCatching {
    val array = JSONArray(prefs.getString("messages", "[]"))
    (0 until array.length()).map { i ->
        val item = array.getJSONObject(i)
        ChatMessage(item.getString("id"), item.getString("role"), item.getString("content"))
    }
}.getOrDefault(emptyList())

private fun saveMessages(prefs: android.content.SharedPreferences, messages: List<ChatMessage>) {
    val array = JSONArray()
    messages.takeLast(200).forEach { msg ->
        array.put(JSONObject().put("id", msg.id).put("role", msg.role).put("content", msg.content))
    }
    prefs.edit().putString("messages", array.toString()).apply()
}

private fun loadExperts(prefs: android.content.SharedPreferences): List<String> = runCatching {
    val array = JSONArray(prefs.getString("experts", """["文案官","选题狐","代码佬"]"""))
    (0 until array.length()).map { array.getString(it) }
}.getOrDefault(listOf("文案官", "选题狐", "代码佬"))


private fun loadExpertPrompts(prefs: android.content.SharedPreferences): Map<String, String> = runCatching {
    val raw = prefs.getString("expert_prompts", "{}") ?: "{}"
    val json = JSONObject(raw)
    buildMap { val keys = json.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, json.optString(key)) } }
}.getOrDefault(emptyMap())


private fun expertPromptsToJson(prompts: Map<String, String>): String {
    val json = JSONObject()
    prompts.forEach { (name, prompt) -> json.put(name, prompt) }
    return json.toString()
}

private fun buildLocalBackup(prefs: android.content.SharedPreferences): String {
    val root = JSONObject()
    root.put("format", "nestro-local-backup")
    root.put("version", 1)
    root.put("created_at", System.currentTimeMillis())
    root.put("base_url", prefs.getString("base_url", "") ?: "")
    root.put("model", prefs.getString("model", "") ?: "")
    root.put("messages", prefs.getString("messages", "[]") ?: "[]")
    root.put("conversations_v1", prefs.getString("conversations_v1", "[]") ?: "[]")
    root.put("active_conversation_id", prefs.getString("active_conversation_id", "") ?: "")
    root.put("global_memory_v1", prefs.getString("global_memory_v1", "") ?: "")
    root.put("experts", prefs.getString("experts", """["文案官","选题狐","代码佬"]""") ?: "[]")
    root.put("expert_prompts", prefs.getString("expert_prompts", "{}") ?: "{}")
    root.put("skills", prefs.getString("skills", "{}") ?: "{}")
    root.put("projects", projectsToJson(loadProjects(prefs)))
    root.put("active_project_id", prefs.getString("active_project_id", "default") ?: "default")
    return root.toString(2)
}

private fun restoreLocalBackup(prefs: android.content.SharedPreferences, raw: String) {
    val root = JSONObject(raw)
    require(root.optString("format") == "nestro-local-backup") { "不是 Nestro 备份文件" }
    require(root.optInt("version") == 1) { "暂不支持此备份版本" }
    val messages = JSONArray(root.getString("messages"))
    require(messages.length() <= 200) { "对话记录数量超出限制" }
    val conversationsRaw = root.optString("conversations_v1", "[]")
    val conversations = JSONArray(conversationsRaw)
    require(conversations.length() <= 100) { "会话数量超出限制" }
    for (i in 0 until conversations.length()) {
        val conversation = conversations.getJSONObject(i)
        require(conversation.optString("id").isNotBlank()) { "会话 ID 缺失" }
        require(conversation.optString("title").length <= 80) { "会话标题过长" }
        val items = conversation.optJSONArray("messages") ?: JSONArray()
        require(items.length() <= 500) { "单个会话消息数量超出限制" }
        for (j in 0 until items.length()) {
            val item = items.getJSONObject(j)
            require(item.optString("role") in setOf("user", "assistant")) { "消息角色无效" }
            require(item.has("id") && item.has("content")) { "消息字段缺失" }
        }
    }
    val globalMemory = root.optString("global_memory_v1", "")
    require(globalMemory.length <= 12000) { "全局记忆超出限制" }
    for (i in 0 until messages.length()) {
        val item = messages.getJSONObject(i)
        require(item.optString("role") in setOf("user", "assistant")) { "消息角色无效" }
        require(item.has("content") && item.has("id")) { "消息字段缺失" }
    }
    val experts = JSONArray(root.getString("experts"))
    require(experts.length() <= 100) { "专家数量超出限制" }
    for (i in 0 until experts.length()) require(experts.getString(i).length <= 80) { "专家名称过长" }
    val prompts = JSONObject(root.getString("expert_prompts"))
    val skills = JSONObject(root.optString("skills", "{}"))
    require(skills.length() <= 100) { "技能数量超出限制" }
    val cleanSkills = JSONObject()
    val skillKeys = skills.keys()
    while (skillKeys.hasNext()) {
        val name = skillKeys.next()
        val prompt = skills.optString(name)
        require(name.length <= 40 && prompt.length <= 8000) { "技能内容超出限制" }
        cleanSkills.put(name, prompt)
    }
    require(prompts.length() <= 100) { "专家提示词数量超出限制" }
    val cleanPrompts = JSONObject()
    val keys = prompts.keys()
    while (keys.hasNext()) {
        val name = keys.next()
        val prompt = prompts.optString(name)
        require(prompt.length <= 20000) { "专家提示词过长" }
        cleanPrompts.put(name, prompt)
    }
    val editor = prefs.edit()
        .putString("messages", messages.toString())
        .putString("conversations_v1", conversations.toString())
        .putString("active_conversation_id", root.optString("active_conversation_id", ""))
        .putString("global_memory_v1", globalMemory)
        .putString("experts", experts.toString())
        .putString("expert_prompts", cleanPrompts.toString())
        .putString("skills", cleanSkills.toString())
        .putString("projects", projectsToJson(parseProjects(root.optJSONArray("projects"))))
        .putString("active_project_id", root.optString("active_project_id", "default"))
        .putLong("local_snapshot_saved_at", System.currentTimeMillis())
    if (root.has("base_url") && root.has("model")) {
        val restoredBaseUrl = root.optString("base_url")
        val restoredModel = root.optString("model")
        require(restoredBaseUrl.length <= 2048 && restoredModel.length <= 200) { "模型配置字段过长" }
        val previousBaseUrl = prefs.getString("base_url", "") ?: ""
        val previousModel = prefs.getString("model", "") ?: ""
        editor.putString("base_url", restoredBaseUrl).putString("model", restoredModel)
        if (previousBaseUrl != restoredBaseUrl || previousModel != restoredModel) {
            editor.remove("api_key_enc")
        }
    }
    editor.apply()
}


private val DEFAULT_SKILLS = linkedMapOf(
    "结构化总结" to "请提炼核心结论、关键事实、行动项与未解决问题。区分原文事实和你的推断，避免遗漏重要限制。",
    "精简改写" to "请在不改变原意的前提下压缩冗余表达。保留关键事实与语气，优先给出可直接使用的成稿。",
    "代码审查" to "请从正确性、安全性、边界条件、性能和可维护性审查代码。先指出确定的问题，再给最小修复建议，不要臆测未提供的上下文。"
)

private fun loadSkills(prefs: android.content.SharedPreferences): Map<String, String> = runCatching {
    val json = JSONObject(prefs.getString("skills", "{}") ?: "{}")
    val loaded = linkedMapOf<String, String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val name = keys.next()
        loaded[name] = json.optString(name)
    }
    DEFAULT_SKILLS + loaded
}.getOrDefault(DEFAULT_SKILLS)

private fun skillMapToJson(skills: Map<String, String>): String {
    val json = JSONObject()
    skills.forEach { (name, prompt) -> json.put(name, prompt) }
    return json.toString()
}


@Composable
private fun PhoneAgentPage(context: Context, database: NestroDatabase, projectId: String) {
    val prefs = remember { context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf("尚未读取手机界面") }
    var tasks by remember { mutableStateOf(emptyList<app.nestro.data.PhoneAgentTaskEntity>()) }
    var taskLogs by remember { mutableStateOf(emptyMap<String, List<app.nestro.data.PhoneAgentTaskLogEntity>>()) }
    var taskMessage by remember { mutableStateOf("") }
    val enabledServices = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    val serviceEnabled = enabledServices.contains(
        context.packageName + "/app.nestro.phone.PhoneAgentService",
        ignoreCase = true
    )
    val grantUntil = prefs.getLong("phone_task_grant_until", 0L)
    val hasTaskGrant = grantUntil > System.currentTimeMillis()
    val hasSingleGrant = prefs.getBoolean("phone_single_grant", false)

    LaunchedEffect(refresh, projectId) {
        tasks = withContext(Dispatchers.IO) {
            runCatching { database.dao().phoneAgentTasks(projectId, 30) }.getOrDefault(emptyList())
        }
        taskLogs = withContext(Dispatchers.IO) {
            tasks.associate { task ->
                task.id to runCatching { PhoneAgentTaskStore.recentLogs(database.dao(), task.id, 12) }.getOrDefault(emptyList())
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("手机自动化", color = Ink, style = MaterialTheme.typography.headlineSmall)
        Text("Phone Agent 会先建立任务计划，再在授权范围内执行可验证的低风险动作。支付、验证码和最终提交始终由你接手。", color = Muted)
        Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (serviceEnabled) "无障碍服务已开启" else "尚未开启无障碍服务", color = if (serviceEnabled) Mint else Ink)
                Text("需要你在 Android 系统设置中手动开启。Nestro 不能代替你授予权限。", color = Muted, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
                ) { Text("前往系统无障碍设置") }
            }
        }
        Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本次授权", color = Ink, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        hasTaskGrant -> "任务持续授权有效，剩余约 ${((grantUntil - System.currentTimeMillis()) / 60000).coerceAtLeast(1)} 分钟"
                        hasSingleGrant -> "单次操作授权有效"
                        else -> "当前没有操作授权"
                    },
                    color = if (hasTaskGrant || hasSingleGrant) Mint else Muted
                )
                Button(
                    onClick = { prefs.edit().putBoolean("phone_single_grant", true).remove("phone_task_stop_requested").apply(); refresh++ },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("授予单次操作") }
                OutlinedButton(
                    onClick = { prefs.edit().putLong("phone_task_grant_until", System.currentTimeMillis() + 2 * 60 * 60 * 1000L).remove("phone_task_stop_requested").apply(); refresh++ },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("授予本次任务持续授权（最长 2 小时）") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            tasks.firstOrNull { it.status in setOf(PhoneAgentTaskStore.STATUS_PLANNED, PhoneAgentTaskStore.STATUS_RUNNING) }?.let { PhoneAgentWorkScheduler.cancel(context, it.id) }
                            PhoneAgentService.activeService?.requestTaskStop()
                            taskMessage = "已请求停止当前任务"
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("停止当前任务") }
                    TextButton(
                        onClick = { prefs.edit().remove("phone_single_grant").remove("phone_task_grant_until").putBoolean("phone_task_stop_requested", true).apply(); refresh++ },
                        modifier = Modifier.weight(1f)
                    ) { Text("撤销全部授权", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                }
            }
        }
        OutlinedButton(
            onClick = { snapshot = PhoneAgentService.activeService?.captureVisibleText() ?: "无障碍服务尚未连接，请先在系统设置中开启。" },
            enabled = serviceEnabled,
            modifier = Modifier.fillMaxWidth()
        ) { Text("读取当前界面（仅本次主动触发）") }
        Text(snapshot, color = Muted, style = MaterialTheme.typography.bodySmall)
        if (taskMessage.isNotBlank()) Text(taskMessage, color = Mint, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("任务记录", color = Ink, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh++ }) { Text("刷新", color = Mint) }
        }
        if (tasks.isEmpty()) {
            Text("当前项目还没有 Phone Agent 任务。", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            tasks.forEach { task ->
                val canResume = runCatching { PhoneAgentTaskStore.decodeActions(task.planJson) }.isSuccess
                val canStartAgent = !canResume && task.category != PhoneAgentPlanner.Category.GENERAL.name && task.status in setOf(PhoneAgentTaskStore.STATUS_PLANNED, PhoneAgentTaskStore.STATUS_PAUSED, PhoneAgentTaskStore.STATUS_RUNNING)
                Surface(color = Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(task.goal, color = Ink, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "状态：${task.status} · 进度：${task.currentStep} · ${task.category}",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        taskLogs[task.id].orEmpty().takeLast(3).forEach { log ->
                            Text(
                                "${if (log.success) "✓" else "!"} ${log.action} · ${log.detail}",
                                color = if (log.success) Muted else androidx.compose.ui.graphics.Color(0xFFF87171),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if ((canResume && task.status in setOf(
                                    PhoneAgentTaskStore.STATUS_PLANNED,
                                    PhoneAgentTaskStore.STATUS_PAUSED,
                                    PhoneAgentTaskStore.STATUS_RUNNING,
                                    PhoneAgentTaskStore.STATUS_WAITING_USER
                                )) || canStartAgent
                            ) {
                                Button(
                                    onClick = {
                                        PhoneAgentWorkScheduler.enqueue(
                                            context,
                                            task.id,
                                            afterUserHandoff = task.status == PhoneAgentTaskStore.STATUS_WAITING_USER,
                                            allowRetryCurrentAction = task.status == PhoneAgentTaskStore.STATUS_RUNNING
                                        )
                                        taskMessage = "已提交后台任务，切换会话不会中断任务记录。"
                                    },
                                    enabled = serviceEnabled && (hasTaskGrant || hasSingleGrant),
                                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
                                ) { Text(when { canStartAgent -> "启动 Phone Agent"; task.status == PhoneAgentTaskStore.STATUS_WAITING_USER -> "我已处理，继续"; task.status == PhoneAgentTaskStore.STATUS_RUNNING -> "核对页面后继续"; else -> "继续任务" }) }
                            }
                            if (task.status !in setOf(PhoneAgentTaskStore.STATUS_COMPLETED, PhoneAgentTaskStore.STATUS_CANCELLED)) {
                                TextButton(onClick = {
                                    PhoneAgentWorkScheduler.cancel(context, task.id)
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            PhoneAgentTaskStore.updateProgress(database.dao(), task.id, PhoneAgentTaskStore.STATUS_CANCELLED, task.currentStep)
                                        }
                                        refresh++
                                    }
                                }) { Text("取消任务", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                            }
                        }
                    }
                }
            }
        }
        Text("安全边界：不自动填写密码或验证码，不绕过登录/风控，不替你完成付款或不可逆提交。操作记录保存在本机。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}