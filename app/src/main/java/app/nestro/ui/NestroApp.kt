package app.nestro.ui

import app.nestro.data.ConversationEntity
import app.nestro.data.ConversationListItem
import app.nestro.data.MessageEntity
import app.nestro.data.ProjectEntity
import app.nestro.data.ProjectSpaceRules
import app.nestro.data.KnowledgeDocumentEntity
import app.nestro.data.KnowledgeChunkEntity
import app.nestro.data.NestroDatabase
import app.nestro.data.KnowledgeStore
import app.nestro.data.ConversationMemoryStore
import app.nestro.data.ConversationRecallSource
import androidx.room.withTransaction

import app.nestro.phone.PhoneAgentService
import app.nestro.phone.PhoneAgentPlanner
import app.nestro.phone.PhoneAgentExecutor
import app.nestro.phone.PhoneAgentWorkScheduler
import app.nestro.phone.PhoneAgentNotifications
import app.nestro.data.PhoneAgentTaskStore
import app.nestro.data.PhoneAgentTaskEntity
import app.nestro.data.PhoneAgentTaskLogEntity
import app.nestro.data.UserMemoryStore
import app.nestro.data.MemoryCandidate
import app.nestro.data.SkillBundle
import app.nestro.data.SkillBundleParser
import app.nestro.data.ExpertBundle
import app.nestro.data.ExpertBundleParser
import app.nestro.data.LocalBackupCompatibility
import app.nestro.data.ModelProfileMetadata
import app.nestro.data.ModelProfileCatalogCodec
import app.nestro.data.ModelUsageStats
import app.nestro.data.ModelUsageStatsCodec
import app.nestro.data.ChatRequestPolicy
import app.nestro.data.ChatHistoryItem
import app.nestro.data.ExpertCatalogRules
import app.nestro.data.ExpertProfile
import app.nestro.data.ExpertProfileCodec
import app.nestro.data.ExpertRevision
import app.nestro.data.ExpertRevisionCodec
import app.nestro.data.ExpertTemplate
import app.nestro.data.ExpertTemplateCodec
import app.nestro.data.ExpertTeamBundle
import app.nestro.data.ExpertTeamMemberBundle
import app.nestro.data.ExpertTeamBundleParser
import app.nestro.data.ExpertTeamEngine
import app.nestro.data.ExpertTeamDefaults
import app.nestro.data.ExpertTeamMemberTask
import app.nestro.data.ExpertTeamRunResult
import app.nestro.data.ExpertTeamRunHistoryCodec
import app.nestro.data.WorkflowDefinition
import app.nestro.data.WorkflowStep
import app.nestro.data.WorkflowStepKind
import app.nestro.data.WorkflowDefinitionCodec
import app.nestro.data.WorkflowEngine
import app.nestro.data.WorkflowRunHistoryCodec
import app.nestro.data.WorkflowRunRecord
import app.nestro.data.WorkflowExecutionException
import app.nestro.data.UserMemoryEntity
import app.nestro.ui.VoiceInputButton

import android.content.Context
import android.net.Uri
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
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

private data class ChatMessage(val id: String, val role: String, val content: String, val versions: List<String> = emptyList(), val activeVersion: Int = 0)

private fun ChatMessage.allVersions(): List<String> = when {
    versions.isEmpty() -> listOf(content)
    activeVersion >= versions.size -> (versions + content).distinct().takeLast(20)
    else -> versions.distinct().takeLast(20)
}
private data class ConversationRecord(val id: String, val title: String, val updatedAt: Long, val messages: List<ChatMessage>, val projectId: String = "default", val messagesLoaded: Boolean = true)

internal fun adaptiveDrawerWidthDp(screenWidthDp: Int): Int = when {
    screenWidthDp < 360 -> 252
    screenWidthDp >= 840 -> 320
    else -> 286
}
private data class ProjectRecord(val id: String, val name: String, val instructions: String, val createdAt: Long, val archivedAt: Long = 0L)
private data class ExpertTeam(val id: String, val name: String, val description: String, val members: List<String>)
internal data class ModelConfig(val baseUrl: String, val model: String, val apiKey: String)

@Composable
fun NestroApp() {
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val adaptiveDrawerWidth = adaptiveDrawerWidthDp(screenWidthDp).dp
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE) }
    val database = remember { NestroDatabase.get(context) }
    var databaseReady by remember { mutableStateOf(false) }
    val persistedConversationVersions = remember { mutableStateMapOf<String, Int>() }
    var page by remember { mutableStateOf("home") }
    var showModelDialog by remember { mutableStateOf(false) }
    var showDrawer by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(ModelConfig("", "", "")) }
    var modelProfiles by remember { mutableStateOf(ModelProfileCatalogCodec.decode(prefs.getString("model_profiles_v1", "[]") ?: "[]")) }
    var activeModelProfileId by remember { mutableStateOf(prefs.getString("active_model_profile_id", "") ?: "") }
    var modelFailoverEnabled by remember { mutableStateOf(prefs.getBoolean("model_failover_enabled", false)) }
    var modelUsage by remember { mutableStateOf(ModelUsageStatsCodec.decode(prefs.getString("model_usage_v1", "{}") ?: "{}")) }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var conversations by remember { mutableStateOf(listOf<ConversationRecord>()) }
    var projects by remember { mutableStateOf(listOf(ProjectRecord("default", "默认空间", "", System.currentTimeMillis()))) }
    var activeProjectId by remember { mutableStateOf("default") }
    var deletingProjectId by remember { mutableStateOf("") }
    var showProjectDialog by remember { mutableStateOf(false) }
    var projectDraftName by remember { mutableStateOf("") }
    var projectDraftInstructions by remember { mutableStateOf("") }
    var editingProjectId by remember { mutableStateOf("") }
    var activeConversationId by remember { mutableStateOf("") }
    var conversationSearch by remember { mutableStateOf("") }
    var conversationPageItems by remember { mutableStateOf(emptyList<ConversationListItem>()) }
    var conversationPageLoading by remember { mutableStateOf(false) }
    var conversationPageHasMore by remember { mutableStateOf(false) }
    var conversationPageRefreshKey by remember { mutableIntStateOf(0) }
    var showRenameConversation by remember { mutableStateOf(false) }
    var showEditMessageDialog by remember { mutableStateOf(false) }
    var editMessageId by remember { mutableStateOf("") }
    var editMessageDraft by remember { mutableStateOf("") }
    var renameConversationId by remember { mutableStateOf("") }
    var renameConversationDraft by remember { mutableStateOf("") }
    var globalMemory by remember { mutableStateOf("") }
    var memoryDraft by remember { mutableStateOf("") }
    var automaticMemories by remember { mutableStateOf(emptyList<UserMemoryEntity>()) }
    var automaticMemoryEnabled by remember { mutableStateOf(prefs.getBoolean("automatic_memory_enabled", false)) }
    var memoryEdits by remember { mutableStateOf(emptyMap<String, String>()) }
    var memoryStatus by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var activeRequestId by remember { mutableStateOf("") }
    var reuseLastUserOnSend by remember { mutableStateOf(false) }
    var pendingAssistantRevision by remember { mutableStateOf<ChatMessage?>(null) }
    var status by remember { mutableStateOf("尚未配置模型") }
    var recallSources by remember { mutableStateOf(emptyList<ConversationRecallSource>()) }
    var showRecallDialog by remember { mutableStateOf(false) }
    var experts by remember { mutableStateOf(listOf("文案官", "选题狐", "代码佬")) }
    var expertPrompts by remember { mutableStateOf(loadExpertPrompts(prefs)) }
    var expertProfiles by remember { mutableStateOf(ExpertProfileCodec.decode(prefs.getString("expert_profiles_v1", "{}") ?: "{}")) }
    var expertRevisions by remember { mutableStateOf(ExpertRevisionCodec.decode(prefs.getString("expert_versions_v1", "{}") ?: "{}")) }
    var expertTemplates by remember { mutableStateOf(ExpertTemplateCodec.decode(prefs.getString("expert_templates_v1", "[]") ?: "[]")) }
    var showExpertTemplatesDialog by remember { mutableStateOf(false) }
    var templateNameDraft by remember { mutableStateOf("") }
    var showExpertHistoryDialog by remember { mutableStateOf(false) }
    var historyExpertName by remember { mutableStateOf("") }
    var historyCompareVersion by remember { mutableStateOf("") }
    var skills by remember { mutableStateOf(loadSkills(prefs)) }
    var skillMetadata by remember { mutableStateOf(loadSkillMetadata(prefs)) }
    var disabledSkills by remember { mutableStateOf(loadDisabledSkills(prefs)) }
    var editingSkillName by remember { mutableStateOf("") }
    var skillDialogError by remember { mutableStateOf("") }
    var pendingSkillBundle by remember { mutableStateOf<SkillBundle?>(null) }
    var showSkillImportDialog by remember { mutableStateOf(false) }
    var skillImportStatus by remember { mutableStateOf("") }
     var skillExportStatus by remember { mutableStateOf("") }
     var skillExportJson by remember { mutableStateOf("") }
    var selectedSkill by remember { mutableStateOf("") }
    var skillDraftName by remember { mutableStateOf("") }
    var skillDraftPrompt by remember { mutableStateOf("") }
    var showSkillDialog by remember { mutableStateOf(false) }
    var selectedExpert by remember {
        mutableStateOf(ExpertCatalogRules.resolveSelection(prefs.getString("selected_expert", "通用助手") ?: "通用助手", experts, expertProfiles))
    }
    var expertDraft by remember { mutableStateOf("") }
    var editingExpertName by remember { mutableStateOf("") }
    var expertPromptDraft by remember { mutableStateOf("") }
    var expertGroupDraft by remember { mutableStateOf("默认") }
    var expertAvatarDraft by remember { mutableStateOf("✦") }
    var expertEnabledDraft by remember { mutableStateOf(true) }
    var expertSkillDraft by remember { mutableStateOf(emptySet<String>()) }
    var expertTemperatureDraft by remember { mutableStateOf("") }
    var expertMaxTokensDraft by remember { mutableStateOf("") }
    var expertDescriptionDraft by remember { mutableStateOf("") }
    var showExpertDialog by remember { mutableStateOf(false) }
    var expertDialogError by remember { mutableStateOf("") }
    var expertTeams by remember { mutableStateOf(loadExpertTeams(prefs)) }
    var selectedTeamId by remember { mutableStateOf((prefs.getString("selected_team_id", "") ?: "").takeIf { id -> expertTeams.any { it.id == id } } ?: "") }
    var showTeamDialog by remember { mutableStateOf(false) }
    var editingTeamId by remember { mutableStateOf("") }
    var teamDraftName by remember { mutableStateOf("") }
    var teamDraftDescription by remember { mutableStateOf("") }
    var teamDraftMembers by remember { mutableStateOf(emptySet<String>()) }
    var pendingExpertBundle by remember { mutableStateOf<ExpertBundle?>(null) }
    var expertImportStatus by remember { mutableStateOf("") }
    var expertExportStatus by remember { mutableStateOf("") }
    var expertExportJson by remember { mutableStateOf("") }
    var pendingTeamBundle by remember { mutableStateOf<ExpertTeamBundle?>(null) }
    var teamImportStatus by remember { mutableStateOf("") }
    var teamExportStatus by remember { mutableStateOf("") }
    var teamExportJson by remember { mutableStateOf("") }
    var teamRunStatus by remember { mutableStateOf("") }
    var expertTeamRunHistory by remember {
        mutableStateOf(ExpertTeamRunHistoryCodec.decode(prefs.getString("expert_team_runs_v1", "[]") ?: "[]"))
    }
    var teamMemberStatuses by remember { mutableStateOf(emptyMap<String,String>()) }
    var workflowHistory by remember {
        mutableStateOf(WorkflowRunHistoryCodec.decode(prefs.getString("workflow_runs_v1", "[]") ?: "[]"))
    }
    var workflows by remember {
        mutableStateOf(
            WorkflowDefinitionCodec.decode(prefs.getString("workflows_v1", "[]") ?: "[]").ifEmpty {
                listOf(
                    WorkflowDefinition(
                        name = "研究到方案",
                        description = "先拆解问题，再形成可执行方案",
                        steps = listOf(
                            WorkflowStep(kind = WorkflowStepKind.PROMPT, title = "拆解与研究", instruction = "识别目标、关键事实、约束和风险，形成结构化分析。"),
                            WorkflowStep(kind = WorkflowStepKind.PROMPT, title = "形成方案", instruction = "基于上一步结果，形成清晰、可执行、带优先级的方案。")
                        )
                    )
                )
            }
        )
    }
    var showWorkflowDialog by remember { mutableStateOf(false) }
    var editingWorkflowId by remember { mutableStateOf("") }
    var workflowDraftName by remember { mutableStateOf("") }
    var workflowDraftDescription by remember { mutableStateOf("") }
    var workflowDraftSteps by remember {
        mutableStateOf(
            listOf(
                WorkflowStep(kind = WorkflowStepKind.PROMPT, title = "分析需求", instruction = "拆解用户目标，列出关键事实、约束和待解决问题。"),
                WorkflowStep(kind = WorkflowStepKind.PROMPT, title = "形成结果", instruction = "根据上一步分析，形成可直接交付的结果，并检查完整性。")
            )
        )
    }
    var workflowTargetPickerIndex by remember { mutableIntStateOf(-1) }
    var showWorkflowTargetPicker by remember { mutableStateOf(false) }
    var workflowInputDraft by remember { mutableStateOf("") }
    var workflowRunStatus by remember { mutableStateOf("") }
    var runningWorkflowId by remember { mutableStateOf("") }
    var workflowPaused by remember { mutableStateOf(false) }
    var workflowPauseGate by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var backupStatus by remember { mutableStateOf("备份不包含 API Key，恢复后仍需在本机配置模型。") }
    var pendingBackupUri by remember { mutableStateOf<Uri?>(null) }
    var showBackupRestoreConfirm by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("语音由系统识别服务转写后发送；API Key 与对话记录保存在本机。") }
    val chatListState = rememberLazyListState()
    val conversationHistoryListState = rememberLazyListState()

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            backupStatus = "正在导出本机数据…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val json = buildLocalBackup(context, prefs, database)
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("无法创建备份文件")
                    }
                }
                backupStatus = result.fold({ "备份已导出" }, { "导出失败：${it.message ?: "未知错误"}" })
            }
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingBackupUri = uri
            showBackupRestoreConfirm = true
        }
    }
    val performBackupRestore: (Uri) -> Unit = { uri ->
        backupStatus = "正在恢复本机数据…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = readBackupText(context, uri)
                    restoreLocalBackup(context, prefs, raw, database)
                }
            }
            if (result.isSuccess) {
                automaticMemoryEnabled = prefs.getBoolean("automatic_memory_enabled", false)
                modelProfiles = ModelProfileCatalogCodec.decode(prefs.getString("model_profiles_v1", "[]") ?: "[]")
                activeModelProfileId = prefs.getString("active_model_profile_id", "") ?: ""
                modelUsage = ModelUsageStatsCodec.decode(prefs.getString("model_usage_v1", "{}") ?: "{}")
                modelFailoverEnabled = prefs.getBoolean("model_failover_enabled", false)
                val restoredActiveProfile = modelProfiles.firstOrNull { it.id == activeModelProfileId }
                config = if (restoredActiveProfile != null) {
                    ModelConfig(restoredActiveProfile.baseUrl, restoredActiveProfile.model, LocalVault.readProfile(context, restoredActiveProfile.id))
                } else LocalVault.read(context)
                persistedConversationVersions.clear()
                conversations = loadConversations(prefs)
                projects = loadProjects(prefs)
                activeProjectId = (prefs.getString("active_project_id", "default") ?: "default").takeIf { id -> projects.any { it.id == id && it.archivedAt == 0L } } ?: "default"
                activeConversationId = (prefs.getString("active_conversation_id", "") ?: "").takeIf { id -> conversations.any { it.id == id } } ?: conversations.firstOrNull()?.id.orEmpty()
                messages = conversations.firstOrNull { it.id == activeConversationId }?.messages ?: loadMessages(prefs)
                draft = prefs.getString("draft_$activeConversationId", "") ?: ""
                globalMemory = prefs.getString("global_memory_v1", "") ?: ""
                memoryDraft = globalMemory
                experts = loadExperts(prefs)
                expertPrompts = loadExpertPrompts(prefs)
                expertProfiles = ExpertProfileCodec.decode(prefs.getString("expert_profiles_v1", "{}") ?: "{}")
                expertRevisions = ExpertRevisionCodec.decode(prefs.getString("expert_versions_v1", "{}") ?: "{}")
                expertTemplates = ExpertTemplateCodec.decode(prefs.getString("expert_templates_v1", "[]") ?: "[]")
                selectedExpert = ExpertCatalogRules.resolveSelection(prefs.getString("selected_expert", "通用助手") ?: "通用助手", experts, expertProfiles)
                prefs.edit().putString("selected_expert", selectedExpert).apply()
                expertTeams = loadExpertTeams(prefs)
                selectedTeamId = (prefs.getString("selected_team_id", "") ?: "").takeIf { id -> expertTeams.any { it.id == id } } ?: ""
                skills = loadSkills(prefs)
                skillMetadata = loadSkillMetadata(prefs)
                disabledSkills = loadDisabledSkills(prefs).intersect(skills.keys)
                if (selectedSkill in disabledSkills) selectedSkill = ""
                workflows = WorkflowDefinitionCodec.decode(prefs.getString("workflows_v1", "[]") ?: "[]")
                workflowHistory = WorkflowRunHistoryCodec.decode(prefs.getString("workflow_runs_v1", "[]") ?: "[]")
                expertTeamRunHistory = ExpertTeamRunHistoryCodec.decode(prefs.getString("expert_team_runs_v1", "[]") ?: "[]")
                automaticMemories = withContext(Dispatchers.IO) { UserMemoryStore.list(database.dao(), activeProjectId) }
            }
            backupStatus = result.fold({ "恢复完成。备份不含 API Key；仅保留本机地址与型号匹配的密钥。" }, { "恢复失败：${it.message ?: "文件格式无效"}" })
        }
    }

    val importTeamLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val result=withContext(Dispatchers.IO) { runCatching {
                val raw=context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("无法读取团队文件")
                ExpertTeamBundleParser.parse(raw)
            } }
            result.fold({ pendingTeamBundle=it; teamImportStatus="" },{ teamImportStatus="导入失败：${it.message ?: "文件格式无效"}" })
        }
    }
    val exportTeamLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri==null) teamExportStatus="已取消团队导出。"
        else scope.launch {
            val content=teamExportJson
            val result=withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("无法创建团队文件")
            } }
            teamExportStatus=result.fold({ "专家团队及成员角色已导出。" },{ "导出失败：${it.message ?: "未知错误"}" })
        }
    }

    val importExpertLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val result=withContext(Dispatchers.IO) { runCatching {
                val raw=context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("无法读取专家文件")
                ExpertBundleParser.parse(raw,uri.lastPathSegment ?: "本地专家")
            } }
            result.fold({ pendingExpertBundle=it; expertImportStatus="" },{ expertImportStatus="导入失败：${it.message ?: "文件格式无效"}" })
        }
    }
    val exportExpertLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri==null) expertExportStatus="已取消专家导出。"
        else scope.launch {
            val content=expertExportJson
            val result=withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("无法创建专家文件")
            } }
            expertExportStatus=result.fold({ "专家配置已导出。" },{ "导出失败：${it.message ?: "未知错误"}" })
        }
    }

    val importSkillLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: error("无法读取技能文件")
                        SkillBundleParser.parse(raw, uri.lastPathSegment ?: "本地技能")
                    }
                }
                result.fold(
                    { pendingSkillBundle = it; showSkillImportDialog = true; skillImportStatus = "" },
                    { skillImportStatus = "导入失败：${it.message ?: "文件格式无效"}" }
                )
            }
        }
    }

    val exportSkillLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) {
            skillExportStatus = "已取消技能导出。"
        } else {
            val content = skillExportJson
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(content.toByteArray(Charsets.UTF_8))
                        } ?: error("无法创建技能文件")
                    }
                }
                skillExportStatus = result.fold(
                    { "技能包已导出，可在另一台设备导入。" },
                    { "导出失败：${it.message ?: "未知错误"}" }
                )
            }
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

    LaunchedEffect(page, activeProjectId, conversationSearch, conversationPageRefreshKey) {
        if (page != "conversations") return@LaunchedEffect
        conversationPageLoading = true
        conversationPageItems = emptyList()
        conversationPageHasMore = false
        conversationHistoryListState.scrollToItem(0)
        delay(250)
        val result = withContext(Dispatchers.IO) {
            database.dao().conversationListPage(
                projectId = activeProjectId,
                query = conversationSearch.trim().take(120),
                limit = 30,
                offset = 0
            )
        }
        conversationPageItems = result
        conversationPageHasMore = result.size == 30
        conversationPageLoading = false
    }

    LaunchedEffect(Unit) {
        config = LocalVault.read(context)
        modelProfiles = ModelProfileCatalogCodec.decode(prefs.getString("model_profiles_v1", "[]") ?: "[]")
        activeModelProfileId = prefs.getString("active_model_profile_id", "") ?: ""
        if (modelProfiles.isEmpty() && config.baseUrl.isNotBlank() && config.model.isNotBlank()) {
            val migrated = ModelProfileMetadata("legacy-default", "默认模型", config.baseUrl, config.model)
            modelProfiles = listOf(migrated)
            activeModelProfileId = migrated.id
            LocalVault.writeProfile(context, migrated.id, config.apiKey)
            prefs.edit().putString("model_profiles_v1", ModelProfileCatalogCodec.encode(modelProfiles))
                .putString("active_model_profile_id", activeModelProfileId).apply()
        }
        val activeMetadata = modelProfiles.firstOrNull { it.id == activeModelProfileId }
        if (activeMetadata != null) {
            config = ModelConfig(activeMetadata.baseUrl, activeMetadata.model, LocalVault.readProfile(context, activeMetadata.id))
        }
        val loaded = runCatching {
            withContext(Dispatchers.IO) {
                val dao = database.dao()
                val pendingProjectDeleteId = prefs.getString("pending_project_delete_id", "").orEmpty()
                if (pendingProjectDeleteId.isNotBlank()) {
                    val localRecords = loadConversations(prefs)
                    val roomConversationIds = dao.projectConversationIds(pendingProjectDeleteId)
                    val activeId = prefs.getString("active_project_id", "default") ?: "default"
                    val pendingRequest = runCatching {
                        JSONObject(prefs.getString("chat_request_state_v1", "{}") ?: "{}")
                    }.getOrNull()
                    val hasRunningRequest = pendingRequest?.optString("status") == "running" &&
                        pendingRequest?.optString("project_id") == pendingProjectDeleteId
                    val pendingConversationIds = (
                        roomConversationIds + localRecords.filter { it.projectId == pendingProjectDeleteId }.map { it.id }
                    ).toSet()
                    val hasDraft = pendingConversationIds.any { id ->
                        (prefs.getString("draft_$id", "") ?: "").isNotBlank()
                    }
                    val hasLocalConversationData = localRecords.any {
                        it.projectId == pendingProjectDeleteId && (it.messages.isNotEmpty() || it.title != "新对话")
                    }
                    val projectExists = dao.projectExists(pendingProjectDeleteId)
                    val hasLinkedData = dao.projectHasLinkedData(pendingProjectDeleteId)
                    val canRecover = ProjectSpaceRules.canRecoverPendingDeletion(
                        projectId = pendingProjectDeleteId,
                        activeProjectId = activeId,
                        hasRunningRequest = hasRunningRequest,
                        hasDraft = hasDraft,
                        hasLocalConversationData = hasLocalConversationData,
                        hasLinkedData = hasLinkedData
                    )
                    val deleted = canRecover &&
                        dao.recoverPendingProjectDeletionIfEmpty(pendingProjectDeleteId, activeId)
                    if (deleted) {
                        saveConversationList(
                            prefs,
                            localRecords.filterNot { it.projectId == pendingProjectDeleteId },
                            markDirty = false
                        )
                        saveProjects(
                            prefs,
                            loadProjects(prefs).filterNot { it.id == pendingProjectDeleteId },
                            markDirty = false
                        )
                        val editor = prefs.edit()
                        (roomConversationIds + localRecords.filter { it.projectId == pendingProjectDeleteId }.map { it.id })
                            .distinct().forEach { editor.remove("draft_$it") }
                        editor.remove("pending_project_delete_id").commit()
                    } else {
                        prefs.edit().remove("pending_project_delete_id").commit()
                    }
                }
                val storedConversationCount = dao.conversationCount()
                val hasPendingLocalSnapshot = prefs.getLong("local_snapshot_saved_at", 0L) >
                    prefs.getLong("room_synced_at", 0L)
                if (storedConversationCount == 0 || hasPendingLocalSnapshot) {
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
                                MessageEntity(message.id, record.id, index, message.role, message.content, JSONArray(message.versions.take(20)).toString(), message.activeVersion)
                            }
                            if (rows.isNotEmpty()) dao.putMessages(rows)
                        }
                        dao.putProjects(legacyProjects.map { ProjectEntity(it.id, it.name, it.instructions, it.createdAt, it.archivedAt) })
                        val projectIds = legacyProjects.map { it.id }
                        val staleProjectIds = dao.projectIds().filterNot { it in projectIds }
                        if (staleProjectIds.isNotEmpty()) {
                            staleProjectIds.forEach { dao.deleteProjectAndKnowledge(it) }
                        }
                    }
                    prefs.edit().putLong("room_synced_at", prefs.getLong("local_snapshot_saved_at", 0L)).apply()
                    dao.allConversationSummaries().map { ConversationRecord(it.id, it.title, it.updatedAt, emptyList(), it.projectId, messagesLoaded = false) } to legacyProjects
                } else {
                    val restoredConversations = dao.allConversationSummaries().map { ConversationRecord(it.id, it.title, it.updatedAt, emptyList(), it.projectId, messagesLoaded = false) }
                    val storedProjects = dao.loadProjects().map { ProjectRecord(it.id, it.name, it.instructions, it.createdAt, it.archivedAt) }
                    val restoredProjects = if (storedProjects.isEmpty()) loadProjects(prefs) else storedProjects
                    if (storedProjects.isEmpty()) dao.putProjects(restoredProjects.map { ProjectEntity(it.id, it.name, it.instructions, it.createdAt, it.archivedAt) })
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
            prefs.edit().putBoolean("room_storage_initialized", true).apply()
            databaseReady = true
        } else {
            conversations = loadConversations(prefs)
            projects = loadProjects(prefs)
            status = "本地数据库暂不可用，已回退到本机旧存储"
        }
        val savedProjectId = prefs.getString("active_project_id", "default") ?: "default"
        activeProjectId = savedProjectId.takeIf { id -> projects.any { it.id == id && it.archivedAt == 0L } } ?: "default"
        val savedConversationId = prefs.getString("active_conversation_id", "") ?: ""
        activeConversationId = conversations.firstOrNull {
            it.id == savedConversationId && it.projectId == activeProjectId
        }?.id ?: conversations.firstOrNull { it.projectId == activeProjectId }?.id.orEmpty()
        val activeConversationMessages = if (databaseReady && activeConversationId.isNotBlank()) {
            withContext(Dispatchers.IO) {
                database.dao().conversationWithMessages(activeConversationId)?.messages.orEmpty()
                    .sortedBy { it.position }
                    .map { message ->
                        ChatMessage(message.id, message.role, message.content, jsonStringList(JSONArray(message.versionsJson)), message.activeVersion)
                    }
            }
        } else {
            conversations.firstOrNull { it.id == activeConversationId }?.messages.orEmpty()
        }
        messages = activeConversationMessages
        if (activeConversationId.isNotBlank()) {
            conversations = conversations.map { record ->
                if (record.id == activeConversationId) record.copy(messages = activeConversationMessages, messagesLoaded = true) else record
            }
            if (databaseReady) {
                conversations.firstOrNull { it.id == activeConversationId }?.let { record ->
                    persistedConversationVersions[record.id] = record.hashCode()
                }
            }
        }
        prefs.edit().putString("active_project_id", activeProjectId).putString("active_conversation_id", activeConversationId).apply()
        globalMemory = prefs.getString("global_memory_v1", "") ?: ""
        memoryDraft = globalMemory
        experts = loadExperts(prefs)
        expertPrompts = loadExpertPrompts(prefs)
        val pendingRequest = runCatching { JSONObject(prefs.getString("chat_request_state_v1", "{}") ?: "{}") }.getOrNull()
        if (pendingRequest?.optString("status") == "running") {
            val requestId = pendingRequest.optString("request_id")
            val conversationId = pendingRequest.optString("conversation_id")
            val projectId = pendingRequest.optString("project_id", "default")
            val restoredPrompt = pendingRequest.optString("prompt")
            val partial = pendingRequest.optString("partial_answer")
            val record = conversations.firstOrNull { it.id == conversationId }
            if (record != null) {
                val existingMessages = if (databaseReady) {
                    withContext(Dispatchers.IO) {
                        database.dao().conversationWithMessages(conversationId)?.messages.orEmpty()
                            .sortedBy { it.position }
                            .map { message ->
                                ChatMessage(message.id, message.role, message.content, jsonStringList(JSONArray(message.versionsJson)), message.activeVersion)
                            }
                    }
                } else record.messages
                if (existingMessages.none { it.id == requestId }) {
                    val content = if (partial.isBlank()) "请求在应用重建时中断，原始输入已恢复，可重新发送。" else partial + "\\n\\n[请求中断，已保留已接收内容]"
                    val recovered = existingMessages + ChatMessage(requestId, "assistant", content)
                    conversations = saveConversationMessages(prefs, conversationId, recovered, projectId, conversations)
                    if (activeConversationId == conversationId) messages = recovered
                }
            }
            if (restoredPrompt.isNotBlank() && activeConversationId == conversationId) {
                draft = restoredPrompt
                prefs.edit().putString("draft_$conversationId", restoredPrompt).apply()
            }
            status = "上次请求中断，已恢复输入和已接收内容，可重新发送"
        }
        prefs.edit().remove("chat_request_state_v1").apply()
        if (config.apiKey.isNotBlank() && config.model.isNotBlank() && status == "尚未配置模型") status = "模型已就绪"
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
                        if (record.messagesLoaded) {
                            dao.clearMessages(record.id)
                            val rows = record.messages.mapIndexed { index, message ->
                                MessageEntity(message.id, record.id, index, message.role, message.content, JSONArray(message.versions.take(20)).toString(), message.activeVersion)
                            }
                            if (rows.isNotEmpty()) dao.putMessages(rows)
                        }
                    }
                    dao.putProjects(projectSnapshot.map { ProjectEntity(it.id, it.name, it.instructions, it.createdAt, it.archivedAt) })
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


    val requestModelConfigs = buildList {
        if (config.baseUrl.isNotBlank() && config.model.isNotBlank() && config.apiKey.isNotBlank()) add(config)
        if (modelFailoverEnabled) modelProfiles.filterNot { it.id == activeModelProfileId }.forEach { profile ->
            val key = LocalVault.readProfile(context, profile.id)
            if (key.isNotBlank()) add(ModelConfig(profile.baseUrl, profile.model, key))
        }
    }.distinctBy { "${it.baseUrl}|${it.model}" }

    val recordModelUsage: (ModelConfig, Int, Int) -> Unit = { usedConfig, inputChars, outputChars ->
        scope.launch {
            val profileId = modelProfiles.firstOrNull {
                it.baseUrl == usedConfig.baseUrl && it.model == usedConfig.model &&
                    LocalVault.readProfile(context, it.id) == usedConfig.apiKey
            }?.id
                ?: activeModelProfileId.takeIf { it.isNotBlank() }
            if (profileId != null) {
                modelUsage = ModelUsageStatsCodec.record(modelUsage, profileId, inputChars, outputChars)
                prefs.edit().putString("model_usage_v1", ModelUsageStatsCodec.encode(modelUsage)).apply()
            }
        }
    }

    val sendPrompt: (String) -> Unit = send@{ rawPrompt ->
        val prompt = rawPrompt.trim()
        if (prompt.isBlank() || busy) return@send
        draft = ""
        prefs.edit().remove("draft_$activeConversationId").apply()
        val user = if (reuseLastUserOnSend && messages.lastOrNull()?.role == "user") messages.last() else ChatMessage(UUID.randomUUID().toString(), "user", prompt)
        reuseLastUserOnSend = false
        if (messages.lastOrNull()?.id != user.id) messages = messages + user
        conversations = saveConversationMessages(prefs, activeConversationId, messages, activeProjectId, conversations)
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
                        val raw = OpenAiClient.completeWithFallback(
                            requestModelConfigs,
                            listOf(ChatMessage(UUID.randomUUID().toString(), "user", prompt)),
                            generatorPrompt,
                            onUsage = recordModelUsage
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
                conversations = saveConversationMessages(prefs, requestConversationId, completedHistory, requestProjectId, conversations)
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
            conversations = saveConversationMessages(prefs, activeConversationId, messages, activeProjectId, conversations)
        } else {
            busy = true
            stopRequested = false
            teamRunStatus = ""
            teamMemberStatuses = emptyMap()
            val requestConversationId = activeConversationId
            val requestHistory = messages.toList()
            val previousAssistant = pendingAssistantRevision
            pendingAssistantRevision = null
            val assistantId = previousAssistant?.id ?: UUID.randomUUID().toString()
            activeRequestId = assistantId
            val requestProjectId = activeProjectId
            prefs.edit().putString("chat_request_state_v1", JSONObject().put("status", "running")
                .put("request_id", assistantId).put("conversation_id", requestConversationId)
                .put("project_id", requestProjectId).put("prompt", prompt).put("partial_answer", "")
                .put("started_at", System.currentTimeMillis()).toString()).apply()
            val requestExpertProfile = expertProfiles[selectedExpert]?.takeIf { it.enabled && selectedTeamId.isBlank() }
            val baseSystemContext = listOf(
                "全局记忆（跨会话共享，仅作为用户背景参考）：\n" + globalMemory,
                if (selectedTeamId.isBlank() && expertProfiles[selectedExpert]?.enabled != false) expertPrompts[selectedExpert].orEmpty().ifBlank { ExpertTeamDefaults.rolePrompt(selectedExpert) } else "",
                if (selectedTeamId.isBlank()) ExpertProfileCodec.skillContext(expertProfiles[selectedExpert], skills, disabledSkills) else "",
                projects.firstOrNull { it.id == requestProjectId }?.instructions.orEmpty(),
                skills[selectedSkill].orEmpty().takeIf { selectedSkill !in disabledSkills }.orEmpty()
            ).filter { it.isNotBlank() }.joinToString("\n\n").take(12000)
            scope.launch {
                val phonePlan = PhoneAgentPlanner.plan(prompt)
                if (stopRequested) {
                    val stoppedHistory = requestHistory + ChatMessage(assistantId, "assistant", "已停止生成。")
                    conversations = saveConversationMessages(prefs, requestConversationId, stoppedHistory, requestProjectId, conversations)
                    if (activeConversationId == requestConversationId) messages = stoppedHistory
                    status = "已停止生成"
                    busy = false
                    activeRequestId = ""
                    stopRequested = false
                    return@launch
                }
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
                    val selectedTeam = expertTeams.firstOrNull { it.id == selectedTeamId }
                    val teamContext = if (selectedTeam != null) {
                        teamRunStatus = "专家团“${selectedTeam.name}”正在启动…"
                        teamMemberStatuses = selectedTeam.members.associateWith { "waiting" }
                        val memberTasks = selectedTeam.members.filter { it in experts && expertProfiles[it]?.enabled != false }.take(4).map { member ->
                            val role = expertPrompts[member].orEmpty().ifBlank { ExpertTeamDefaults.rolePrompt(member) }
                            val attached = ExpertProfileCodec.skillContext(expertProfiles[member], skills, disabledSkills)
                            ExpertTeamMemberTask(member, listOf(role, attached).filter { it.isNotBlank() }.joinToString("\n\n"))
                        }
                        check(memberTasks.size in 2..4) { "专家团至少需要 2 位已启用专家，请检查团队成员状态。" }
                        val teamRun = ExpertTeamEngine.run(
                            teamName = selectedTeam.name,
                            teamDescription = selectedTeam.description,
                            task = prompt,
                            members = memberTasks,
                            maxRetries = 1,
                            timeoutMillis = 45_000,
                            maxParallel = 4,
                            shouldStop = { stopRequested },
                            onMember = { member, state ->
                                withContext(Dispatchers.Main) {
                                    teamMemberStatuses = teamMemberStatuses + (member to state)
                                    teamRunStatus = when(state) {
                                        "running" -> "专家团执行中：$member 正在分析"
                                        "retrying" -> "专家团执行中：$member 正在重试"
                                        "success" -> "专家团执行中：$member 已完成"
                                        "failed" -> "专家团执行中：$member 调用失败"
                                        else -> teamRunStatus
                                    }
                                }
                            }
                        ) { member, memberPrompt ->
                            withContext(Dispatchers.IO) {
                                OpenAiClient.completeWithFallback(
                                    requestModelConfigs,
                                    requestHistory,
                                    listOf(requestSystemContext,memberPrompt).filter { it.isNotBlank() }
                                        .joinToString("\n\n").take(18000),
                                    connectTimeoutMillis = 15_000,
                                    readTimeoutMillis = 25_000,
                                    temperature = expertProfiles[member.name]?.temperature,
                                    maxTokens = expertProfiles[member.name]?.maxTokens,
                                    onUsage = recordModelUsage
                                ).take(2200)
                            }
                        }
                        expertTeamRunHistory = (listOf(teamRun) + expertTeamRunHistory).take(30)
                        prefs.edit().putString("expert_team_runs_v1",ExpertTeamRunHistoryCodec.encode(expertTeamRunHistory)).apply()
                        teamRunStatus = "专家团完成：成功 ${teamRun.successfulMembers}/${teamRun.members.size}，失败 ${teamRun.failedMembers}"
                        if (!teamRun.isUsable) {
                            throw IllegalStateException("专家团所有成员均未能完成分析，请检查模型配置、网络和成员角色指令。")
                        }
                        teamRun.toContext(10_000)
                    } else ""
                    val finalSystemContext = listOf(requestSystemContext, teamContext)
                        .filter { it.isNotBlank() }.joinToString("\n\n").take(24000)
                    if (stopRequested) throw java.util.concurrent.CancellationException("用户停止生成")
                    OpenAiClient.streamWithFallback(requestModelConfigs, requestHistory, finalSystemContext, requestExpertProfile?.temperature, requestExpertProfile?.maxTokens, onUsage = recordModelUsage, requestId = assistantId) { chunk ->
                        partialAnswer.append(chunk)
                        prefs.edit().putString("chat_request_state_v1", JSONObject().put("status", "running")
                            .put("request_id", assistantId).put("conversation_id", requestConversationId)
                            .put("project_id", requestProjectId).put("prompt", prompt)
                            .put("partial_answer", partialAnswer.toString().take(24000))
                            .put("started_at", System.currentTimeMillis()).toString()).apply()
                        withContext(Dispatchers.Main) {
                            if (activeConversationId == requestConversationId) {
                                messages = requestHistory + ChatMessage(assistantId, "assistant", partialAnswer.toString(), previousAssistant?.versions.orEmpty(), previousAssistant?.activeVersion ?: 0)
                            }
                        }
                    }
                }
                val finalText = when {
                    result.isSuccess -> result.getOrThrow()
                    partialAnswer.isNotEmpty() -> partialAnswer.toString()
                    stopRequested -> "已停止生成。"
                    result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException -> "请求失败：本次请求超时，请重试或缩短内容。"
                    result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException -> "请求失败：本次请求超时，请重试或缩短内容。"
                    else -> "请求失败：${result.exceptionOrNull()?.message ?: "请检查网络、地址和 Key"}"
                }
                val priorVersions = previousAssistant?.allVersions().orEmpty()
                val nextVersions = (priorVersions + finalText).filter(String::isNotBlank).distinct().takeLast(20)
                val completedHistory = requestHistory + ChatMessage(assistantId, "assistant", finalText, nextVersions, nextVersions.lastIndex)
                conversations = saveConversationMessages(prefs, requestConversationId, completedHistory, requestProjectId, conversations)
                prefs.edit().remove("chat_request_state_v1").apply()
                if (activeConversationId == requestConversationId) messages = completedHistory
                status = when {
                    stopRequested -> "已停止生成"
                    result.isSuccess -> if (recalledCount > 0) "已连接 · 参考历史 ${recalledCount} 条 · 本地保存" else "已连接 · 本地保存"
                    else -> "连接失败"
                }
                busy = false
                activeRequestId = ""
                if (result.isSuccess && !stopRequested && automaticMemoryEnabled &&
                    shouldExtractAutomaticMemory(prompt) && UserMemoryStore.isSafeContent(prompt)
                ) {
                    val userFacts = listOf(prompt)
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
                        Modifier.width(adaptiveDrawerWidth).fillMaxHeight().background(Card).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Nestro", style = MaterialTheme.typography.headlineSmall, color = Mint)
                        Text("你的本机 AI 工作站", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        DrawerItem("⌂", "工作台") { page = "home"; showDrawer = false }
                        DrawerItem("☷", "对话历史") { page = "conversations"; showDrawer = false }
                        DrawerItem("✧", "全局记忆") { page = "memory"; showDrawer = false }
                        DrawerItem("▧", "本地知识库") { page = "knowledge"; showDrawer = false }
                         DrawerItem("⇢", "工作流") { page = "workflows"; showDrawer = false }
                        DrawerItem("▦", "项目空间") { page = "spaces"; showDrawer = false }
                        DrawerItem("⌁", "手机自动化") { page = "phone"; showDrawer = false }
                        DrawerItem("麦", "语音输入") { page = "voice"; showDrawer = false }
                        DrawerItem("▣", "产物库") { page = "artifacts"; showDrawer = false }
                        DrawerItem("⌘", "能力目录") { page = "capabilities"; showDrawer = false }
                        DrawerItem("✦", "我的专家") { page = "experts"; showDrawer = false }
                        DrawerItem("♧", "专家团队") { page = "teams"; showDrawer = false }
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
                                if (busy && teamRunStatus.isNotBlank()) Text(teamRunStatus,color=Mint,style=MaterialTheme.typography.labelSmall,maxLines=1)
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
                                        MessageBubble(
                    msg,
                    onSaveArtifact = { text ->
                        scope.launch {
                            val title = text.lineSequence().firstOrNull()?.trim()?.take(60).orEmpty().ifBlank { "对话产物" }
                            val result = withContext(Dispatchers.IO) {
                                runCatching { app.nestro.data.ArtifactStore.save(context, activeProjectId, title, text) }
                            }
                            status = result.fold({ "已保存到产物库：${it.title} v${it.version}" }, { "产物保存失败：${it.message ?: "未知错误"}" })
                        }
                    },
                    onEdit = if (msg.role == "user") ({
                        editMessageId = msg.id
                        editMessageDraft = msg.content
                        showEditMessageDialog = true
                    }) else null,
                    onRegenerate = if (msg.role == "assistant" && !msg.content.startsWith("请求失败：")) ({
                        val targetIndex = messages.indexOfFirst { it.id == msg.id }
                        val userIndex = if (targetIndex >= 0) messages.subList(0, targetIndex + 1).indexOfLast { it.role == "user" } else -1
                        if (targetIndex >= 0 && userIndex >= 0) {
                            val retryPrompt = messages[userIndex].content
                            pendingAssistantRevision = msg
                            reuseLastUserOnSend = true
                            messages = messages.take(userIndex + 1)
                            conversations = saveConversationMessages(prefs, activeConversationId, messages, activeProjectId, conversations)
                            sendPrompt(retryPrompt)
                        }
                    }) else null,
                    onVersionChange = { version ->
                        val index = messages.indexOfFirst { it.id == msg.id }
                        if (index >= 0) {
                            val allVersions = msg.allVersions()
                            val chosen = version.coerceIn(0, allVersions.lastIndex)
                            messages = messages.toMutableList().also { it[index] = msg.copy(content = allVersions[chosen], versions = allVersions, activeVersion = chosen) }
                            conversations = saveConversationMessages(prefs, activeConversationId, messages, activeProjectId, conversations)
                        }
                    },
                    onRetry = {
                                            if (messages.lastOrNull()?.id == msg.id) {
                                                val lastUserIndex = messages.indexOfLast { it.role == "user" }
                                                if (lastUserIndex >= 0) {
                                                    val retryPrompt = messages[lastUserIndex].content
                                                    messages = messages.take(lastUserIndex)
                                                    conversations = saveConversationMessages(
                                                        prefs, activeConversationId, messages, activeProjectId, conversations
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
                                    AssistChip(onClick = { page = "teams" }, label = { Text(expertTeams.firstOrNull { it.id == selectedTeamId }?.name ?: "专家团队") })
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
                                                    OpenAiClient.cancel(activeRequestId)
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
                        "models" -> ModelManager(
                            profiles = modelProfiles,
                            activeProfileId = activeModelProfileId,
                            activeConfig = config,
                            usage = modelUsage,
                            failoverEnabled = modelFailoverEnabled,
                            onFailoverChange = { enabled ->
                                modelFailoverEnabled = enabled
                                prefs.edit().putBoolean("model_failover_enabled", enabled).apply()
                            },
                            loadKey = { id -> LocalVault.readProfile(context, id) },
                            onActivate = { id ->
                                val metadata = modelProfiles.firstOrNull { it.id == id }
                                if (metadata != null) {
                                    val key = LocalVault.readProfile(context, id)
                                    if (key.isNotBlank()) {
                                        activeModelProfileId = id
                                        config = ModelConfig(metadata.baseUrl, metadata.model, key)
                                        prefs.edit().putString("active_model_profile_id", id)
                                            .putString("base_url", metadata.baseUrl).putString("model", metadata.model).apply()
                                        LocalVault.write(context, config)
                                        status = "已切换模型：${metadata.name}"
                                    } else status = "该配置尚未填写 API Key"
                                }
                            },
                            onSave = { metadata, apiKey, activate ->
                                val error = ModelProfileCatalogCodec.validateCatalog(
                                    (modelProfiles.filterNot { it.id == metadata.id } + metadata)
                                )
                                if (error != null) {
                                    status = error
                                } else {
                                    modelProfiles = (modelProfiles.filterNot { it.id == metadata.id } + metadata)
                                    LocalVault.writeProfile(context, metadata.id, apiKey)
                                    prefs.edit().putString("model_profiles_v1", ModelProfileCatalogCodec.encode(modelProfiles)).apply()
                                    if (activate || metadata.id == activeModelProfileId) {
                                        activeModelProfileId = metadata.id
                                        config = ModelConfig(metadata.baseUrl, metadata.model, apiKey)
                                        LocalVault.write(context, config)
                                        prefs.edit().putString("active_model_profile_id", metadata.id).apply()
                                        status = "模型配置已保存并启用：${metadata.name}"
                                    } else status = "模型配置已保存：${metadata.name}"
                                }
                            },
                            onDelete = { id ->
                                modelProfiles = modelProfiles.filterNot { it.id == id }
                                LocalVault.deleteProfile(context, id)
                                prefs.edit().putString("model_profiles_v1", ModelProfileCatalogCodec.encode(modelProfiles)).apply()
                                if (activeModelProfileId == id) {
                                    val next = modelProfiles.firstOrNull()
                                    if (next != null) {
                                        val key = LocalVault.readProfile(context, next.id)
                                        activeModelProfileId = next.id
                                        config = ModelConfig(next.baseUrl, next.model, key)
                                        LocalVault.write(context, config)
                                        prefs.edit().putString("active_model_profile_id", next.id).apply()
                                        status = if (key.isBlank()) "已切换到备用配置，请补充 API Key" else "已切换到：${next.name}"
                                    } else {
                                        activeModelProfileId = ""
                                        config = ModelConfig("", "", "")
                                        prefs.edit().remove("active_model_profile_id").putString("base_url", "").putString("model", "").remove("api_key_enc").apply()
                                        status = "尚未配置模型"
                                    }
                                }
                            }
                        )
                        "workflows" -> {
                             Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                 Text("工作流", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                 Text("把复杂任务拆成连续步骤。每一步接收上一步结果，支持提示词、专家角色和技能指令。", color = Muted)
                                 OutlinedTextField(value = workflowInputDraft, onValueChange = { workflowInputDraft = it.take(12000) }, label = { Text("本次运行目标") }, placeholder = { Text("例如：研究一个市场，最后形成可执行分析。") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                                 if (workflowRunStatus.isNotBlank()) Text(workflowRunStatus, color = Mint, style = MaterialTheme.typography.bodySmall)
                                 if (runningWorkflowId.isNotBlank()) {
                                     Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                         OutlinedButton(
                                             onClick = {
                                                 if (workflowPaused) {
                                                     workflowPauseGate?.complete(Unit)
                                                     workflowPauseGate = null
                                                     workflowPaused = false
                                                     workflowRunStatus = "正在继续工作流…"
                                                 } else {
                                                     workflowPauseGate = CompletableDeferred()
                                                     workflowPaused = true
                                                     workflowRunStatus = "已请求暂停，将在当前步骤结束后暂停。"
                                                 }
                                             },
                                             modifier = Modifier.weight(1f)
                                         ) { Text(if (workflowPaused) "继续执行" else "暂停") }
                                         TextButton(onClick = {
                                             stopRequested = true
                                             workflowPauseGate?.complete(Unit)
                                             workflowPauseGate = null
                                             OpenAiClient.cancel()
                                             workflowRunStatus = "正在停止工作流…"
                                         }) { Text("停止", color = ColorStop) }
                                     }
                                 }
                                 workflows.forEach { workflow ->
                                     Surface(color = Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                         Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                             Text(workflow.name, color = Ink, style = MaterialTheme.typography.titleMedium)
                                             Text(workflow.description, color = Muted, style = MaterialTheme.typography.bodySmall)
                                             workflow.steps.forEachIndexed { index, step -> Text("${index + 1}. ${step.title} · ${when (step.kind) { WorkflowStepKind.PROMPT -> "提示词"; WorkflowStepKind.EXPERT -> "专家"; WorkflowStepKind.SKILL -> "技能" }}", color = Mint, style = MaterialTheme.typography.bodySmall) }
                                             Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                 Button(enabled = runningWorkflowId.isBlank() && !busy && workflowInputDraft.isNotBlank() && config.apiKey.isNotBlank(), onClick = {
                                                     val input = workflowInputDraft.trim()
                                                     val conversationId = activeConversationId
                                                     val projectId = activeProjectId
                                                     val runHistory = messages.toList() + ChatMessage(UUID.randomUUID().toString(), "user", input)
                                                     val startedAt = System.currentTimeMillis()
                                                     runningWorkflowId = workflow.id
                                                     workflowPaused = false
                                                     workflowPauseGate = null
                                                     busy = true
                                                     stopRequested = false
                                                     workflowRunStatus = "正在启动工作流…"
                                                     scope.launch {
                                                         val result = runCatching {
                                                             WorkflowEngine.run(workflow, input, invokeModel = { step, stepPrompt ->
                                                                 val expertOptions = if (step.kind == WorkflowStepKind.EXPERT) expertProfiles[step.targetId.ifBlank { step.title }] else null
                                                                 val roleContext = when (step.kind) {
                                                                     WorkflowStepKind.EXPERT -> {
                                                                          val expertName = step.targetId.ifBlank { step.title }
                                                                          val profile = expertProfiles[expertName]
                                                                          check(profile?.enabled != false) { "工作流引用的专家「$expertName」已停用" }
                                                                          listOf(expertPrompts[expertName].orEmpty().ifBlank { ExpertTeamDefaults.rolePrompt(expertName) }, ExpertProfileCodec.skillContext(profile, skills, disabledSkills)).filter { it.isNotBlank() }.joinToString("\n\n")
                                                                      }
                                                                      WorkflowStepKind.SKILL -> {
                                                                          val skillName = step.targetId.ifBlank { step.title }
                                                                          check(skillName !in disabledSkills) { "工作流引用的技能「$skillName」已停用" }
                                                                          skills[skillName]?.takeIf { it.isNotBlank() } ?: error("工作流引用的技能「$skillName」不存在")
                                                                      }
                                                                     WorkflowStepKind.PROMPT -> ""
                                                                 }
                                                                 withContext(Dispatchers.IO) {
                                                                     OpenAiClient.completeWithFallback(requestModelConfigs, listOf(ChatMessage(UUID.randomUUID().toString(), "user", stepPrompt)),
                                                                         listOf(roleContext, projects.firstOrNull { it.id == projectId }?.instructions.orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n").take(12000),
                                                                         temperature = expertOptions?.temperature,
                                                                         maxTokens = expertOptions?.maxTokens,
                                                                         onUsage = recordModelUsage)
                                                                 }
                                                             }, onStep = { index, step ->
                                                                 if (stopRequested) throw java.util.concurrent.CancellationException("用户停止工作流")
                                                                 workflowPauseGate?.await()
                                                                 if (stopRequested) throw java.util.concurrent.CancellationException("用户停止工作流")
                                                                 workflowRunStatus = if (workflowPaused) "已暂停，等待继续…" else "执行第 ${index}/${workflow.steps.count { it.enabled }} 步：${step.title}"
                                                             })
                                                         }
                                                         val assistantText = when {
                                                             stopRequested -> "工作流已停止。"
                                                             result.isSuccess -> {
                                                                 val completed = result.getOrThrow()
                                                                 "工作流「${workflow.name}」已完成（${completed.completedSteps} 步成功）。\n" +
                                                                     completed.stepRuns.joinToString("\n") { run ->
                                                                         "${if (run.status == "success") "✓" else "⚠"} ${run.title} · ${run.attempts} 次尝试"
                                                                     } + "\n\n" + completed.output
                                                             }
                                                             else -> "工作流执行失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                                                         }
                                                         val runRecord = WorkflowRunRecord(
                                                             workflowId = workflow.id,
                                                             workflowName = workflow.name,
                                                             input = input,
                                                             startedAt = startedAt,
                                                             status = when {
                                                                 stopRequested -> "stopped"
                                                                 result.isSuccess -> "success"
                                                                 else -> "failed"
                                                             },
                                                             output = assistantText.take(20000),
                                                             stepRuns = result.getOrNull()?.stepRuns
                                                                 ?: (result.exceptionOrNull() as? WorkflowExecutionException)?.stepRuns
                                                                 ?: emptyList()
                                                         )
                                                         workflowHistory = (listOf(runRecord) + workflowHistory).take(50)
                                                         prefs.edit().putString("workflow_runs_v1", WorkflowRunHistoryCodec.encode(workflowHistory)).apply()
                                                         val completed = runHistory + ChatMessage(UUID.randomUUID().toString(), "assistant", assistantText)
                                                         conversations = saveConversationMessages(prefs, conversationId, completed, projectId, conversations)
                                                         if (activeConversationId == conversationId) messages = completed
                                                         workflowRunStatus = if (stopRequested) "工作流已停止。" else if (result.isSuccess) "工作流已完成，结果已写入当前对话。" else assistantText
                                                         workflowPauseGate?.complete(Unit)
                                                         workflowPauseGate = null
                                                         workflowPaused = false
                                                         runningWorkflowId = ""
                                                         busy = false
                                                         stopRequested = false
                                                         workflowInputDraft = ""
                                                         page = "home"
                                                     }
                                                 }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) { Text(if (runningWorkflowId == workflow.id) "执行中…" else "运行") }
                                                 TextButton(onClick = {
                                                     editingWorkflowId = workflow.id
                                                     workflowDraftName = workflow.name
                                                     workflowDraftDescription = workflow.description
                                                     workflowDraftSteps = workflow.steps.toList()
                                                     showWorkflowDialog = true
                                                 }) { Text("编辑", color = Mint) }
                                                 TextButton(onClick = {
                                                     workflows = workflows.filterNot { it.id == workflow.id }
                                                     prefs.edit().putString("workflows_v1", WorkflowDefinitionCodec.encode(workflows)).apply()
                                                 }) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                                             }
                                         }
                                     }
                                 }
                                 if (workflowHistory.isNotEmpty()) {
                                     Text("最近运行记录", color = Ink, style = MaterialTheme.typography.titleMedium)
                                     workflowHistory.take(5).forEach { record ->
                                         Surface(color = Card, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                             Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                 Text(record.workflowName, color = Ink)
                                                 Text(
                                                     "${when (record.status) { "success" -> "成功"; "stopped" -> "已停止"; else -> "失败" }} · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(record.startedAt))}",
                                                     color = Muted,
                                                     style = MaterialTheme.typography.bodySmall
                                                 )
                                                 Text(record.input.take(100), color = Muted, style = MaterialTheme.typography.bodySmall)
                                                 record.stepRuns.take(5).forEach { stepRun ->
                                                     Text(
                                                         "${when (stepRun.status) { "success" -> "✓"; "skipped" -> "↷"; else -> "!" }} ${stepRun.title} · ${stepRun.attempts} 次尝试 · ${stepRun.iterations} 轮",
                                                         color = Muted,
                                                         style = MaterialTheme.typography.bodySmall
                                                     )
                                                 }
                                             }
                                         }
                                     }
                                 }
                                 Button(onClick = {
                                     editingWorkflowId = ""
                                     workflowDraftName = ""
                                     workflowDraftDescription = ""
                                     workflowDraftSteps = listOf(
                                         WorkflowStep(kind = WorkflowStepKind.PROMPT, title = "分析需求", instruction = "拆解用户目标，列出关键事实、约束和待解决问题。"),
                                         WorkflowStep(kind = WorkflowStepKind.PROMPT, title = "形成结果", instruction = "根据上一步分析，形成可直接交付的结果，并检查完整性。")
                                     )
                                     showWorkflowDialog = true
                                 }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) { Text("＋ 新建工作流") }
                             }
                         }
                         "experts" -> {
                            Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("我的专家", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("专家角色与工作要求保存在本机。可以编辑自定义专家，或删除不再使用的配置。", color = Muted)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { importExpertLauncher.launch(arrayOf("application/json", "text/plain")) }) { Text("导入专家") }
                                     OutlinedButton(onClick = { templateNameDraft = selectedExpert.takeIf { it != "通用助手" } ?: ""; showExpertTemplatesDialog = true }) { Text("模板库") }
                                }
                                if (expertImportStatus.isNotBlank()) Text(expertImportStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                                if (expertExportStatus.isNotBlank()) Text(expertExportStatus, color = Muted, style = MaterialTheme.typography.bodySmall)

                                experts.sortedWith(compareBy<String> { expertProfiles[it]?.group ?: "默认" }.thenBy { experts.indexOf(it) }).forEach { name ->
                                    Surface(color = if (selectedExpert == name) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f).clickable(enabled = expertProfiles[name]?.enabled != false) {
                                                selectedExpert = name
                                                prefs.edit().putString("selected_expert", name).apply()
                                                page = "home"
                                            }) {
                                                Text("${expertProfiles[name]?.avatar ?: "✦"}  $name", color = if (selectedExpert == name) Mint else Ink, style = MaterialTheme.typography.titleMedium)
                                                Text("分组：${expertProfiles[name]?.group ?: "默认"} · ${if (expertProfiles[name]?.enabled == false) "已停用" else "已启用"}", color = Muted, style = MaterialTheme.typography.labelSmall)
                                                expertProfiles[name]?.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                                                Text(expertPrompts[name]?.take(100)?.takeIf { it.isNotBlank() } ?: ExpertTeamDefaults.rolePrompt(name).ifBlank { "尚未设置角色指令" }.take(100), color = Muted, style = MaterialTheme.typography.bodySmall)
                                            }
                                            TextButton(onClick = {
                                                editingExpertName = name
                                                expertDraft = name
                                                expertPromptDraft = expertPrompts[name].orEmpty()
                                                val profile = expertProfiles[name] ?: ExpertProfile()
                                                expertGroupDraft = profile.group
                                                expertAvatarDraft = profile.avatar
                                                expertEnabledDraft = profile.enabled
                                                expertSkillDraft = profile.skillNames.toSet()
                                                expertTemperatureDraft = profile.temperature?.toString().orEmpty()
                                                expertMaxTokensDraft = profile.maxTokens?.toString().orEmpty()
                                                expertDescriptionDraft = profile.description
                                                expertDialogError = ""
                                                showExpertDialog = true
                                            }) { Text("编辑", color = Mint) }
                                            TextButton(onClick = {
                                                val profile = expertProfiles[name] ?: ExpertProfile()
                                                expertProfiles = expertProfiles + (name to profile.copy(enabled = !profile.enabled))
                                                prefs.edit().putString("expert_profiles_v1", ExpertProfileCodec.encode(expertProfiles)).apply()
                                                if (profile.enabled && selectedExpert == name) {
                                                    selectedExpert = "通用助手"
                                                    prefs.edit().putString("selected_expert", selectedExpert).apply()
                                                }
                                            }) { Text(if (expertProfiles[name]?.enabled == false) "启用" else "停用", color = if (expertProfiles[name]?.enabled == false) Mint else Muted) }
                                            TextButton(onClick = {
                                                val index = experts.indexOf(name)
                                                if (index > 0) {
                                                    val reordered = experts.toMutableList()
                                                    val item = reordered.removeAt(index)
                                                    reordered.add(index - 1, item)
                                                    experts = reordered
                                                    prefs.edit().putString("experts", JSONArray(experts).toString()).apply()
                                                }
                                            }) { Text("↑", color = Mint) }
                                            TextButton(onClick = {
                                                val index = experts.indexOf(name)
                                                if (index >= 0 && index < experts.lastIndex) {
                                                    val reordered = experts.toMutableList()
                                                    val item = reordered.removeAt(index)
                                                    reordered.add(index + 1, item)
                                                    experts = reordered
                                                    prefs.edit().putString("experts", JSONArray(experts).toString()).apply()
                                                }
                                            }) { Text("↓", color = Mint) }
                                            TextButton(onClick = { historyExpertName = name; historyCompareVersion = ""; showExpertHistoryDialog = true }) { Text("版本", color = Mint) }
                                            if (name !in setOf("文案官", "选题狐", "代码佬")) {
                                                TextButton(onClick = {
                                                 expertExportJson = ExpertBundleParser.exportJson(name, expertPrompts[name].orEmpty(), expertProfiles[name]?.description.orEmpty(), expertProfiles[name] ?: ExpertProfile())
                                                 val safeName = name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40).ifBlank { "expert" }
                                                 exportExpertLauncher.launch("$safeName.nestro-expert.json")
                                             }) { Text("导出", color = Mint) }
                                             TextButton(onClick = {
                                                    experts = experts - name
                                                    expertPrompts = expertPrompts - name
                                                    expertProfiles = expertProfiles - name
                                                     workflows = workflows.map { workflow -> workflow.copy(steps = workflow.steps.map { step -> if (step.kind == WorkflowStepKind.EXPERT && step.targetId.ifBlank { step.title } == name) step.copy(enabled = false) else step }) }
                                                    expertTeams = expertTeams.map { it.copy(members = it.members.filterNot { member -> member == name }) }.filter { it.members.size >= 2 }
                                                    if (expertTeams.none { it.id == selectedTeamId }) selectedTeamId = ""
                                                    prefs.edit().putString("experts", JSONArray(experts).toString())
                                                        .putString("expert_prompts", expertPromptsToJson(expertPrompts))
                                                        .putString("expert_profiles_v1", ExpertProfileCodec.encode(expertProfiles))
                                                        .putString("expert_teams_v1", expertTeamsToJson(expertTeams))
                                                         .putString("workflows_v1", WorkflowDefinitionCodec.encode(workflows))
                                                        .putString("selected_team_id", selectedTeamId).apply()
                                                    if (selectedExpert == name) {
                                                        selectedExpert = "通用助手"
                                                        prefs.edit().putString("selected_expert", selectedExpert).apply()
                                                    }
                                                    expertRevisions = expertRevisions - name
                                                    prefs.edit().putString("expert_versions_v1", ExpertRevisionCodec.encode(expertRevisions)).apply()
                                                }) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                                            }
                                        }
                                    }
                                }
                                Button(onClick = {
                                    editingExpertName = ""
                                    expertDraft = ""
                                    expertPromptDraft = ""
                                    expertGroupDraft = "默认"
                                    expertAvatarDraft = "✦"
                                    expertEnabledDraft = true
                                    expertSkillDraft = emptySet()
                                    expertTemperatureDraft = ""
                                    expertMaxTokensDraft = ""
                                    expertDescriptionDraft = ""
                                    expertDialogError = ""
                                    showExpertDialog = true
                                }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                                    Text("＋ 创建专家")
                                }
                            }
                        }
                        "teams" -> {
                            Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("专家团队", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("把多个专家组合成协作组。成员并行分析，单成员失败隔离并有限重试，最后由主模型识别分歧并汇总。", color = Muted)
                                if (teamRunStatus.isNotBlank()) Text(teamRunStatus,color=Mint,style=MaterialTheme.typography.bodySmall)
                                if (expertTeamRunHistory.isNotEmpty()) {
                                    val latestTeamRun=expertTeamRunHistory.first()
                                    Surface(color=Card,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                            Text("最近一次协作 · ${latestTeamRun.teamName}",color=Ink,style=MaterialTheme.typography.titleSmall)
                                            Text("成功 ${latestTeamRun.successfulMembers} / ${latestTeamRun.members.size} · 失败 ${latestTeamRun.failedMembers}",color=Muted,style=MaterialTheme.typography.bodySmall)
                                            latestTeamRun.members.forEach { run ->
                                                Text(
                                                    "${if(run.status=="success") "✓" else "!"} ${run.name} · ${run.attempts} 次尝试 · ${run.elapsedMillis/1000}s${if(run.error.isNotBlank()) " · "+run.error else ""}",
                                                    color=if(run.status=="success") Mint else ColorStop,
                                                    style=MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { importTeamLauncher.launch(arrayOf("application/json")) }) { Text("导入团队") }
                                }
                                if (teamImportStatus.isNotBlank()) Text(teamImportStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                                if (teamExportStatus.isNotBlank()) Text(teamExportStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                                expertTeams.forEach { team ->
                                    Surface(color = if (team.id == selectedTeamId) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(team.name, color = if (team.id == selectedTeamId) Mint else Ink, style = MaterialTheme.typography.titleMedium)
                                            Text(team.description, color = Muted, style = MaterialTheme.typography.bodySmall)
                                            Text("成员：${team.members.joinToString("、")}", color = Muted, style = MaterialTheme.typography.bodySmall)
                                            TextButton(onClick = {
                                                val memberBundles = team.members.mapNotNull { member ->
                                                    (expertPrompts[member]?.takeIf { it.isNotBlank() } ?: ExpertTeamDefaults.rolePrompt(member))
                                                        .takeIf { it.isNotBlank() }?.let { ExpertTeamMemberBundle(member, it) }
                                                }
                                                if (memberBundles.size >= 2) {
                                                    teamExportJson = ExpertTeamBundleParser.exportJson(team.name, team.description, memberBundles)
                                                    val safeName = team.name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40).ifBlank { "team" }
                                                    exportTeamLauncher.launch("$safeName.nestro-team.json")
                                                } else teamExportStatus = "团队成员缺少角色指令，无法完整导出。"
                                            }) { Text("导出团队", color = Mint) }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(onClick = {
                                                    selectedTeamId = team.id
                                                    prefs.edit().putString("selected_team_id", team.id).apply()
                                                    page = "home"
                                                }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) { Text(if (selectedTeamId == team.id) "当前团队" else "使用团队") }
                                                TextButton(onClick = {
                                                    editingTeamId = team.id
                                                    teamDraftName = team.name
                                                    teamDraftDescription = team.description
                                                    teamDraftMembers = team.members.toSet()
                                                    showTeamDialog = true
                                                }) { Text("编辑", color = Mint) }
                                                TextButton(onClick = {
                                                    expertTeams = expertTeams.filterNot { it.id == team.id }
                                                    if (selectedTeamId == team.id) selectedTeamId = ""
                                                    prefs.edit().putString("expert_teams_v1", expertTeamsToJson(expertTeams))
                                                        .putString("selected_team_id", selectedTeamId).apply()
                                                }) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                                            }
                                        }
                                    }
                                }
                                Button(onClick = {
                                    editingTeamId = ""
                                    teamDraftName = ""
                                    teamDraftDescription = ""
                                    teamDraftMembers = emptySet()
                                    showTeamDialog = true
                                }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) { Text("＋ 创建专家团队") }
                                if (selectedTeamId.isNotBlank()) {
                                    OutlinedButton(onClick = {
                                        selectedTeamId = ""
                                        prefs.edit().remove("selected_team_id").apply()
                                    }, modifier = Modifier.fillMaxWidth()) { Text("本轮不使用团队") }
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
                                        Text("包含本机完整会话与消息、项目空间、知识库文档索引与文本片段、全局及项目记忆、模型配置元数据、专家/团队、技能、工作流和 Phone Agent 记录。API Key 不进入备份，迁移设备后需重新配置。备份带完整性校验，请保存到安全位置。", color = Muted, style = MaterialTheme.typography.bodySmall)
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
                                LazyColumn(
                                    state = conversationHistoryListState,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(conversationPageItems, key = { it.id }) { record ->
                                        Surface(color = if (record.id == activeConversationId) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f).clickable {
                                                    scope.launch {
                                                        val loaded = withContext(Dispatchers.IO) {
                                                            database.dao().conversationWithMessages(record.id)
                                                        }
                                                        val loadedMessages = loaded?.messages?.sortedBy { it.position }?.map { message ->
                                                            ChatMessage(message.id, message.role, message.content, jsonStringList(JSONArray(message.versionsJson)), message.activeVersion)
                                                        } ?: conversations.firstOrNull { it.id == record.id }?.messages.orEmpty()
                                                        activeConversationId = record.id
                                                        activeProjectId = record.projectId
                                                        messages = loadedMessages
                                                        conversations = conversations.map { item ->
                                                            if (item.id == record.id) item.copy(messages = loadedMessages, messagesLoaded = true) else item
                                                        }
                                                        conversations.firstOrNull { it.id == record.id }?.let { persistedConversationVersions[record.id] = it.hashCode() }
                                                        prefs.edit().putString("active_conversation_id", record.id).putString("active_project_id", record.projectId).apply()
                                                        page = "home"
                                                    }
                                                }) {
                                                    Text(record.title, color = if (record.id == activeConversationId) Mint else Ink)
                                                    Text(record.lastMessagePreview.take(72).ifBlank { "空白对话" }, color = Muted, style = MaterialTheme.typography.bodySmall)
                                                    Text("${record.messageCount} 条消息", color = Muted, style = MaterialTheme.typography.labelSmall)
                                                }
                                                TextButton(onClick = {
                                                    renameConversationId = record.id
                                                    renameConversationDraft = record.title
                                                    showRenameConversation = true
                                                }) { Text("重命名", color = Mint) }
                                                TextButton(onClick = {
                                                    scope.launch {
                                                        val remaining = conversations.filterNot { it.id == record.id }
                                                        if (activeConversationId == record.id) {
                                                            val next = remaining.firstOrNull { it.projectId == record.projectId }
                                                                ?: ConversationRecord(UUID.randomUUID().toString(), "新对话", System.currentTimeMillis(), emptyList(), record.projectId)
                                                            conversations = if (remaining.any { it.id == next.id }) remaining else listOf(next) + remaining
                                                            activeConversationId = next.id
                                                            activeProjectId = record.projectId
                                                            val nextMessages = if (databaseReady && remaining.any { it.id == next.id }) {
                                                                withContext(Dispatchers.IO) {
                                                                    database.dao().conversationWithMessages(next.id)?.messages.orEmpty()
                                                                        .sortedBy { it.position }
                                                                        .map { message ->
                                                                            ChatMessage(message.id, message.role, message.content, jsonStringList(JSONArray(message.versionsJson)), message.activeVersion)
                                                                        }
                                                                }
                                                            } else next.messages
                                                            messages = nextMessages
                                                            conversations = conversations.map { item ->
                                                                if (item.id == next.id) item.copy(messages = nextMessages, messagesLoaded = true) else item
                                                            }
                                                            if (remaining.any { it.id == next.id }) {
                                                                conversations.firstOrNull { it.id == next.id }?.let { persistedConversationVersions[next.id] = it.hashCode() }
                                                            }
                                                            prefs.edit().putString("active_conversation_id", next.id).putString("active_project_id", record.projectId).apply()
                                                        } else {
                                                            conversations = remaining
                                                        }
                                                        saveConversationList(prefs, conversations)
                                                        conversationPageItems = conversationPageItems.filterNot { it.id == record.id }
                                                        conversationPageRefreshKey++
                                                    }
                                                }) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                                            }
                                        }
                                    }
                                    if (conversationPageLoading) {
                                        item { Text("正在加载会话…", color = Muted, modifier = Modifier.padding(12.dp)) }
                                    }
                                    if (conversationPageHasMore) {
                                        item {
                                            TextButton(
                                                enabled = !conversationPageLoading,
                                                onClick = {
                                                    scope.launch {
                                                        conversationPageLoading = true
                                                        val nextPage = withContext(Dispatchers.IO) {
                                                            database.dao().conversationListPage(
                                                                projectId = activeProjectId,
                                                                query = conversationSearch.trim().take(120),
                                                                limit = 30,
                                                                offset = conversationPageItems.size
                                                            )
                                                        }
                                                        val existingIds = conversationPageItems.mapTo(mutableSetOf()) { it.id }
                                                        conversationPageItems = conversationPageItems + nextPage.filterNot { it.id in existingIds }
                                                        conversationPageHasMore = nextPage.size == 30
                                                        conversationPageLoading = false
                                                    }
                                                }
                                            ) { Text("加载更多会话") }
                                        }
                                    }
                                    if (conversationPageItems.isEmpty() && !conversationPageLoading) {
                                        item { Text("暂无匹配的对话", color = Muted, modifier = Modifier.padding(12.dp)) }
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
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.weight(1f)) {
                                        Text("允许自动提取记忆", color = Ink)
                                        Text("关闭时不会额外发送记忆提取请求；开启后，符合条件的消息会再次发送给你配置的模型，候选结果经过本地过滤后保存。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(
                                        checked = automaticMemoryEnabled,
                                        onCheckedChange = { enabled ->
                                            automaticMemoryEnabled = enabled
                                            prefs.edit().putBoolean("automatic_memory_enabled", enabled).apply()
                                        }
                                    )
                                }
                                Text("自动记忆仅保存在本机。敏感内容会在本地进行基础规则预检，但规则无法保证识别所有敏感信息。", color = Muted, style = MaterialTheme.typography.bodySmall)
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
                                                TextButton(
                                                    onClick = {
                                                        val source = conversations.firstOrNull { it.id == memory.sourceConversationId }
                                                        if (source != null) {
                                                            scope.launch {
                                                                val sourceMessages = if (databaseReady) {
                                                                    withContext(Dispatchers.IO) {
                                                                        database.dao().conversationWithMessages(source.id)?.messages.orEmpty()
                                                                            .sortedBy { it.position }
                                                                            .map { message ->
                                                                                ChatMessage(message.id, message.role, message.content, jsonStringList(JSONArray(message.versionsJson)), message.activeVersion)
                                                                            }
                                                                    }
                                                                } else source.messages
                                                                activeConversationId = source.id
                                                                messages = sourceMessages
                                                                conversations = conversations.map { record ->
                                                                    if (record.id == source.id) record.copy(messages = sourceMessages, messagesLoaded = true) else record
                                                                }
                                                                conversations.firstOrNull { it.id == source.id }?.let { persistedConversationVersions[source.id] = it.hashCode() }
                                                                page = "home"
                                                            }
                                                        }
                                                    },
                                                    enabled = conversations.any { it.id == memory.sourceConversationId }
                                                ) { Text("打开来源会话", color = Mint) }
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
                                Button(enabled = deletingProjectId.isBlank(), onClick = {
                                    editingProjectId = ""
                                    projectDraftName = ""
                                    projectDraftInstructions = ""
                                    showProjectDialog = true
                                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                                    Text("＋ 创建项目空间")
                                }
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(projects.filter { it.archivedAt == 0L }, key = { it.id }) { project ->
                                        val count = conversations.count { it.projectId == project.id }
                                        Surface(color = if (project.id == activeProjectId) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Column(Modifier.weight(1f).clickable(enabled = deletingProjectId.isBlank()) {
                                                        scope.launch {
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
                                                                val targetMessages = if (databaseReady) {
                                                                    withContext(Dispatchers.IO) {
                                                                        database.dao().conversationWithMessages(target.id)?.messages.orEmpty()
                                                                            .sortedBy { it.position }
                                                                            .map { message ->
                                                                                ChatMessage(message.id, message.role, message.content, jsonStringList(JSONArray(message.versionsJson)), message.activeVersion)
                                                                            }
                                                                    }
                                                                } else target.messages
                                                                messages = targetMessages
                                                                conversations = conversations.map { item ->
                                                                    if (item.id == target.id) item.copy(messages = targetMessages, messagesLoaded = true) else item
                                                                }
                                                                conversations.firstOrNull { it.id == target.id }?.let { persistedConversationVersions[target.id] = it.hashCode() }
                                                            }
                                                            prefs.edit().putString("active_conversation_id", activeConversationId).apply()
                                                            page = "home"
                                                        }
                                                    }) {
                                                        Text(project.name, color = if (project.id == activeProjectId) Mint else Ink, style = MaterialTheme.typography.titleMedium)
                                                        Text("$count 个对话 · 点击切换", color = Muted, style = MaterialTheme.typography.bodySmall)
                                                    }
                                                    TextButton(enabled = deletingProjectId.isBlank(), onClick = {
                                                        editingProjectId = project.id
                                                        projectDraftName = project.name
                                                        projectDraftInstructions = project.instructions
                                                        showProjectDialog = true
                                                    }) { Text("编辑", color = Mint) }
                                                    if (project.id != "default") {
                                                        TextButton(
                                                            enabled = deletingProjectId.isBlank() && ProjectSpaceRules.canArchive(project.id, activeProjectId),
                                                            onClick = {
                                                                val archivedAt = System.currentTimeMillis()
                                                                projects = projects.map { if (it.id == project.id) it.copy(archivedAt = archivedAt) else it }
                                                                saveProjects(prefs, projects)
                                                                status = "项目已归档，原有会话、知识库、记忆和任务均保留；恢复后可继续使用。"
                                                            }
                                                        ) { Text("归档", color = Muted) }
                                                        TextButton(onClick = {
                                                            if (project.id == activeProjectId) {
                                                                status = "当前空间正在使用，请先切换到其他空间再删除"
                                                            } else if (!prefs.edit().putString("pending_project_delete_id", project.id).commit()) {
                                                                status = "删除保护标记写入失败，未执行删除"
                                                            } else {
                                                                deletingProjectId = project.id
                                                                scope.launch {
                                                                    var projectConversationIds = emptySet<String>()
                                                                    runCatching {
                                                                        withContext(Dispatchers.IO) {
                                                                            val dao = database.dao()
                                                                            projectConversationIds = (conversations.filter { it.projectId == project.id }.map { it.id } +
                                                                                dao.projectConversationIds(project.id)).toSet()
                                                                            val latestActiveProjectId = prefs.getString("active_project_id", activeProjectId) ?: activeProjectId
                                                                            val hasDraft = projectConversationIds.any { id -> (prefs.getString("draft_$id", "") ?: "").isNotBlank() }
                                                                            val hasLocalConversationData = conversations.any {
                                                                                it.projectId == project.id && (it.messages.isNotEmpty() || it.title != "新对话")
                                                                            }
                                                                            val hasLinkedData = dao.projectHasLinkedData(project.id)
                                                                            val pendingRequest = runCatching { JSONObject(prefs.getString("chat_request_state_v1", "{}") ?: "{}") }.getOrNull()
                                                                            check(!hasDraft) { "该空间还有未保存的对话草稿，请先处理草稿后再删除" }
                                                                            check(!hasLocalConversationData) { "该空间还有尚未同步的会话内容，请等待保存完成后再删除" }
                                                                            check(pendingRequest?.optString("status") != "running" || pendingRequest?.optString("project_id") != project.id) {
                                                                                "该空间仍有正在执行的请求，请等待请求结束后再删除"
                                                                            }
                                                                            check(ProjectSpaceRules.canDelete(
                                                                                project.id, latestActiveProjectId,
                                                                                if (hasLinkedData) setOf(project.id) else emptySet()
                                                                            )) { "默认空间、当前空间或含有关联数据的空间不可删除" }
                                                                            check(dao.deleteProjectIfEmpty(project.id, latestActiveProjectId)) {
                                                                                "该空间仍有关联数据（有效会话、知识文档、记忆或 Agent 任务），或空间状态已变化，请先处理后再删除"
                                                                            }
                                                                        }
                                                                    }.onSuccess {
                                                                        val removedPlaceholderIds = conversations.filter {
                                                                            it.id in projectConversationIds && it.title == "新对话" && it.messages.isEmpty()
                                                                        }.map { it.id }.toSet()
                                                                        if (removedPlaceholderIds.isNotEmpty()) {
                                                                            conversations = conversations.filterNot { it.id in removedPlaceholderIds }
                                                                        }
                                                                        saveConversationList(prefs, conversations)
                                                                        projects = projects.filterNot { it.id == project.id }
                                                                        saveProjects(prefs, projects)
                                                                        val editor = prefs.edit()
                                                                        projectConversationIds.forEach { id -> editor.remove("draft_$id") }
                                                                        val markerCleared = editor.remove("pending_project_delete_id").commit()
                                                                        status = if (markerCleared) "空项目空间已删除" else "项目已删除，启动时会自动清理残留标记"
                                                                    }.onFailure {
                                                                        if (it is java.util.concurrent.CancellationException) {
                                                                            status = "删除操作中断，下次启动会自动核对删除状态"
                                                                        } else {
                                                                            prefs.edit().remove("pending_project_delete_id").commit()
                                                                            status = it.message ?: "删除失败，项目空间已保留"
                                                                        }
                                                                    }.also {
                                                                        deletingProjectId = ""
                                                                    }
                                                                }
                                                            }
                                                        }, enabled = deletingProjectId.isBlank()) {
                                                            Text(if (deletingProjectId == project.id) "删除中…" else "删除", color = ColorStop)
                                                        }
                                                    }
                                                }
                                                Text(project.instructions.ifBlank { "尚未设置项目指令" }.take(180), color = Muted, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                    if (projects.any { it.archivedAt > 0L }) {
                                        item { Text("已归档项目", color = Ink, style = MaterialTheme.typography.titleMedium) }
                                        items(projects.filter { it.archivedAt > 0L }, key = { it.id }) { project ->
                                            Surface(color = Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Text(project.name, color = Ink, style = MaterialTheme.typography.titleMedium)
                                                        Text("已归档 · ${conversations.count { it.projectId == project.id }} 个对话 · 数据保留在本机", color = Muted, style = MaterialTheme.typography.bodySmall)
                                                    }
                                                    TextButton(
                                                        enabled = deletingProjectId.isBlank() && ProjectSpaceRules.canRestore(project.id),
                                                        onClick = {
                                                            projects = projects.map { if (it.id == project.id) it.copy(archivedAt = 0L) else it }
                                                            saveProjects(prefs, projects)
                                                            status = "项目已恢复，可以切换进入。"
                                                        }
                                                    ) { Text("恢复", color = Mint) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "phone" -> PhoneAgentPage(context, database, activeProjectId)
                        "artifacts" -> ArtifactLibraryPage(activeProjectId)
                        "capabilities" -> CapabilityDirectoryPage()
                        "voice" -> {
                            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("语音输入", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("在聊天输入框按住麦克风说话，松开后由 Android 系统识别服务转成文字并发送。识别服务是否联网、数据如何处理，取决于设备当前启用的系统语音服务。", color = Muted)
                                Text("当前不内置语音合成或第三方语音插件。后续可通过独立 Provider 接口接入用户自行选择的 API 服务。", color = Muted)
                            }
                        }
                        "knowledge" -> KnowledgePage(database, activeProjectId)
                        "skills" -> {
                            Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("本地技能库", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("技能是可复用的工作指令。导入包只读取文本和元数据，不执行其中代码，也不会自动授予权限。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                skills.forEach { (name, prompt) ->
                                    val isDisabled = name in disabledSkills
                                    Surface(color = if (selectedSkill == name && !isDisabled) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(name, color = if (isDisabled) Muted else if (selectedSkill == name) Mint else Ink, style = MaterialTheme.typography.titleMedium)
                                                    Text(prompt.take(100), color = Muted, style = MaterialTheme.typography.bodySmall)
                                                    skillMetadata[name]?.let { raw ->
                                                        val metadata = runCatching { JSONObject(raw) }.getOrNull()
                                                        if (metadata != null) Text("v${metadata.optString("version")} · ${metadata.optString("source")}", color = Muted, style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                                Text(if(isDisabled) "已停用" else "已启用", color = if(isDisabled) Muted else Mint, style = MaterialTheme.typography.labelSmall)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                                TextButton(enabled = !isDisabled, onClick = {
                                                    selectedSkill = name
                                                    page = "home"
                                                    if(draft.isBlank()) draft = "请使用「$name」处理：\n"
                                                }) { Text("试用", color = if(isDisabled) Muted else Mint) }
                                                TextButton(onClick = {
                                                    editingSkillName = name
                                                    skillDraftName = name
                                                    skillDraftPrompt = prompt
                                                    skillDialogError = ""
                                                    showSkillDialog = true
                                                }) { Text("编辑", color = Mint) }
                                                TextButton(onClick = {
                                                    disabledSkills = if(isDisabled) disabledSkills - name else disabledSkills + name
                                                    if(!isDisabled && selectedSkill==name) selectedSkill = ""
                                                    prefs.edit().putString("disabled_skills_v1", disabledSkillsToJson(disabledSkills)).apply()
                                                }) { Text(if(isDisabled) "启用" else "停用", color = if(isDisabled) Mint else Muted) }
                                                TextButton(onClick = {
                                                    skillExportJson = SkillBundleParser.exportJson(name, prompt, skillMetadata[name])
                                                    val safeName = name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40).ifBlank { "skill" }
                                                    exportSkillLauncher.launch("$safeName.nestro-skill.json")
                                                }) { Text("导出", color = Mint) }
                                                if(name !in DEFAULT_SKILLS.keys) TextButton(onClick = {
                                                    skills = skills - name
                                                    skillMetadata = skillMetadata - name
                                                    disabledSkills = disabledSkills - name
                                                    expertProfiles = expertProfiles.mapValues { (_, profile) -> profile.copy(skillNames = profile.skillNames - name) }
                                                    expertRevisions = expertRevisions.mapValues { (_, rows) -> rows.map { it.copy(profile = it.profile.copy(skillNames = it.profile.skillNames - name)) } }
                                                    expertTemplates = expertTemplates.map { it.copy(profile = it.profile.copy(skillNames = it.profile.skillNames - name)) }
                                                    workflows = workflows.map { workflow -> workflow.copy(steps = workflow.steps.map { step -> if (step.kind == WorkflowStepKind.SKILL && step.targetId.ifBlank { step.title } == name) step.copy(enabled = false) else step }) }
                                                    prefs.edit().putString("skills",skillMapToJson(skills))
                                                        .putString("skill_metadata_v1",skillMetadataToJson(skillMetadata))
                                                        .putString("disabled_skills_v1",disabledSkillsToJson(disabledSkills))
                                                        .putString("expert_profiles_v1", ExpertProfileCodec.encode(expertProfiles))
                                                        .putString("expert_versions_v1", ExpertRevisionCodec.encode(expertRevisions))
                                                        .putString("expert_templates_v1", ExpertTemplateCodec.encode(expertTemplates))
                                                        .putString("workflows_v1", WorkflowDefinitionCodec.encode(workflows)).apply()
                                                    if(selectedSkill==name) selectedSkill=""
                                                }) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                                            }
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(onClick = {
                                        editingSkillName = ""
                                        skillDraftName = ""
                                        skillDraftPrompt = ""
                                        skillDialogError = ""
                                        showSkillDialog = true
                                    }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                                        Text("＋ 创建技能")
                                    }
                                    OutlinedButton(onClick = { importSkillLauncher.launch(arrayOf("application/json", "text/markdown", "text/plain")) }, modifier = Modifier.weight(1f)) {
                                        Text("导入技能包")
                                    }
                                }
                                OutlinedButton(onClick = { selectedSkill = ""; page = "home" }, modifier = Modifier.fillMaxWidth()) { Text("本轮不使用技能") }
                                if (skillImportStatus.isNotBlank()) Text(skillImportStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                                 if (skillExportStatus.isNotBlank()) Text(skillExportStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        else -> SimplePage("Nestro", "本地优先的开放式 AI 工作站")
                    }
                }
            }
        }
    }

    if (showBackupRestoreConfirm && pendingBackupUri != null) {
        AlertDialog(
            onDismissRequest = { showBackupRestoreConfirm = false; pendingBackupUri = null },
            title = { Text("确认恢复本地备份？") },
            text = { Text("恢复会覆盖当前设备中的会话、项目、知识库、记忆和配置。API Key 不会从备份导入。建议先导出当前数据，再继续。") },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingBackupUri
                    showBackupRestoreConfirm = false
                    pendingBackupUri = null
                    if (uri != null) performBackupRestore(uri)
                }) { Text("覆盖并恢复", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
            },
            dismissButton = { TextButton(onClick = { showBackupRestoreConfirm = false; pendingBackupUri = null }) { Text("取消") } }
        )
    }

    if (showTeamDialog) {
        AlertDialog(
            onDismissRequest = { showTeamDialog = false },
            title = { Text(if (editingTeamId.isBlank()) "创建专家团队" else "编辑专家团队") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(teamDraftName, { teamDraftName = it }, label = { Text("团队名称") }, singleLine = true)
                    OutlinedTextField(teamDraftDescription, { teamDraftDescription = it }, label = { Text("团队分工") }, minLines = 2)
                    Text("选择成员，至少 2 位", color = Ink, style = MaterialTheme.typography.labelLarge)
                    experts.forEach { expert ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = expert in teamDraftMembers, enabled = expertProfiles[expert]?.enabled != false, onCheckedChange = { checked ->
                                teamDraftMembers = if (checked) teamDraftMembers + expert else teamDraftMembers - expert
                            })
                            Text(expert, color = Ink)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = teamDraftName.trim()
                    if (name.isNotBlank() && name.length <= 40 && teamDraftDescription.length <= 300 && teamDraftMembers.size in 2..4 &&
                        expertTeams.none { it.name == name && it.id != editingTeamId }
                    ) {
                        val team = ExpertTeam(
                            id = editingTeamId.ifBlank { UUID.randomUUID().toString() },
                            name = name,
                            description = teamDraftDescription.trim().take(300),
                            members = teamDraftMembers.filter { it in experts }.take(4)
                        )
                        if (team.members.size >= 2) {
                            expertTeams = if (editingTeamId.isBlank()) expertTeams + team else expertTeams.map { if (it.id == editingTeamId) team else it }
                            prefs.edit().putString("expert_teams_v1", expertTeamsToJson(expertTeams)).apply()
                        }
                        showTeamDialog = false
                    }
                }) { Text("保存团队", color = Mint) }
            },
            dismissButton = { TextButton(onClick = { showTeamDialog = false }) { Text("取消") } }
        )
    }

    if (showEditMessageDialog) {
        AlertDialog(
            onDismissRequest = { showEditMessageDialog = false },
            title = { Text("编辑用户消息") },
            text = {
                OutlinedTextField(
                    value = editMessageDraft,
                    onValueChange = { editMessageDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val edited = editMessageDraft.trim()
                    val index = messages.indexOfFirst { it.id == editMessageId }
                    if (edited.isNotBlank() && index >= 0) {
                        messages = messages.take(index)
                        conversations = saveConversationMessages(prefs, activeConversationId, messages, activeProjectId, conversations)
                        sendPrompt(edited)
                    }
                    showEditMessageDialog = false
                }) { Text("保存并重新发送", color = Mint) }
            },
            dismissButton = { TextButton(onClick = { showEditMessageDialog = false }) { Text("取消") } }
        )
    }

    if (showSkillImportDialog && pendingSkillBundle != null) {
        val bundle = pendingSkillBundle!!
        AlertDialog(
            onDismissRequest = { showSkillImportDialog = false; pendingSkillBundle = null },
            title = { Text("审核技能导入") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(bundle.name, color = Ink, style = MaterialTheme.typography.titleMedium)
                    Text("版本：${bundle.version} · 来源：${bundle.source}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (bundle.description.isNotBlank()) Text(bundle.description, color = Muted)
                    Text("声明权限：${bundle.permissions.joinToString().ifBlank { "无" }}", color = if (bundle.permissions.isEmpty()) Muted else androidx.compose.ui.graphics.Color(0xFFFBBF24))
                    Text("依赖：${bundle.dependencies.joinToString { it.name + "@" + it.version }.ifBlank { "无" }}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text("指令预览", color = Ink, style = MaterialTheme.typography.labelLarge)
                    Text(bundle.prompt.take(900), color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text("安装只保存提示词和元数据。权限声明不会自动开启无障碍、文件访问或 MCP，也不会执行第三方代码。", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val replacing = bundle.name in skills
                    skills = skills + (bundle.name to bundle.prompt)
                    skillMetadata = skillMetadata + (bundle.name to SkillBundleParser.metadataJson(bundle))
                    disabledSkills = disabledSkills - bundle.name
                    prefs.edit()
                        .putString("skills", skillMapToJson(skills))
                        .putString("skill_metadata_v1", skillMetadataToJson(skillMetadata))
                        .putString("disabled_skills_v1", disabledSkillsToJson(disabledSkills))
                        .apply()
                    skillImportStatus = if(replacing) "同名技能已更新并启用" else "技能已安装到本地"
                    showSkillImportDialog = false
                    pendingSkillBundle = null
                }) { Text("确认安装", color = Mint) }
            },
            dismissButton = {
                TextButton(onClick = { showSkillImportDialog = false; pendingSkillBundle = null }) { Text("取消") }
            }
        )
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
                    val validation = ProjectSpaceRules.validate(
                        name = name,
                        currentId = editingProjectId,
                        existing = projects.map { ProjectSpaceRules.Entry(it.id, it.name) }
                    )
                    if (validation != null) {
                        status = validation
                    } else {
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
                        showProjectDialog = false
                    }
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
                                scope.launch {
                                    val targetMessages = if (databaseReady) {
                                        withContext(Dispatchers.IO) {
                                            database.dao().conversationWithMessages(target.id)?.messages.orEmpty()
                                                .sortedBy { it.position }
                                                .map { message ->
                                                    ChatMessage(message.id, message.role, message.content, jsonStringList(JSONArray(message.versionsJson)), message.activeVersion)
                                                }
                                        }
                                    } else target.messages
                                    activeConversationId = target.id
                                    activeProjectId = target.projectId
                                    messages = targetMessages
                                    conversations = conversations.map { record ->
                                        if (record.id == target.id) record.copy(messages = targetMessages, messagesLoaded = true) else record
                                    }
                                    conversations.firstOrNull { it.id == target.id }?.let { persistedConversationVersions[target.id] = it.hashCode() }
                                    prefs.edit().putString("active_conversation_id", target.id).putString("active_project_id", target.projectId).apply()
                                    page = "home"
                                }
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
                        conversationPageItems = conversationPageItems.map { item ->
                            if (item.id == renameConversationId) item.copy(title = title) else item
                        }
                        conversationPageRefreshKey++
                    }
                    showRenameConversation = false
                }) { Text("保存", color = Mint) }
            },
            dismissButton = { TextButton(onClick = { showRenameConversation = false }) { Text("取消") } }
        )
    }

    if (showSkillDialog) {
        AlertDialog(
            onDismissRequest = { showSkillDialog = false; editingSkillName=""; skillDialogError="" },
            title = { Text(if(editingSkillName.isBlank()) "创建本地技能" else "编辑本地技能") },
            text = {
                Column {
                    OutlinedTextField(value = skillDraftName, onValueChange = { skillDraftName = it.take(40) }, label = { Text("技能名称") }, singleLine = true, enabled = editingSkillName !in DEFAULT_SKILLS.keys)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = skillDraftPrompt, onValueChange = { skillDraftPrompt = it.take(8000) }, label = { Text("工作指令") }, placeholder = { Text("描述技能要如何处理任务、输出格式和注意事项。") }, minLines = 4)
                    if(skillDialogError.isNotBlank()) Text(skillDialogError,color=androidx.compose.ui.graphics.Color(0xFFF87171),style=MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = skillDraftName.trim()
                    val prompt = skillDraftPrompt.trim()
                    val duplicate = name in skills && name != editingSkillName
                    if(name.isBlank() || prompt.isBlank()) {
                        skillDialogError = "名称和工作指令不能为空。"
                    } else if(name.length>40 || prompt.length>8000) {
                        skillDialogError = "名称最多 40 字，指令最多 8000 字。"
                    } else if(duplicate) {
                        skillDialogError = "已有同名技能，请更换名称。"
                    } else {
                        val oldName = editingSkillName
                        val updated = if(oldName.isNotBlank() && oldName!=name) skills - oldName else skills
                        skills = updated + (name to prompt)
                        if(oldName.isNotBlank() && oldName!=name) {
                            expertProfiles = expertProfiles.mapValues { (_, profile) -> profile.copy(skillNames = profile.skillNames.map { if (it == oldName) name else it }) }
                            expertRevisions = expertRevisions.mapValues { (_, rows) -> rows.map { it.copy(profile = it.profile.copy(skillNames = it.profile.skillNames.map { skill -> if (skill == oldName) name else skill })) } }
                            expertTemplates = expertTemplates.map { it.copy(profile = it.profile.copy(skillNames = it.profile.skillNames.map { skill -> if (skill == oldName) name else skill })) }
                            workflows = workflows.map { workflow -> workflow.copy(steps = workflow.steps.map { step -> if (step.kind == WorkflowStepKind.SKILL && step.targetId.ifBlank { step.title } == oldName) step.copy(targetId = name) else step }) }
                            val oldMetadata=skillMetadata[oldName]
                            skillMetadata = skillMetadata - oldName
                            if(oldMetadata!=null) skillMetadata = skillMetadata + (name to oldMetadata)
                            val wasDisabled = oldName in disabledSkills
                            if(wasDisabled) disabledSkills = (disabledSkills - oldName) + name
                            if(selectedSkill==oldName) selectedSkill=if(wasDisabled) "" else name
                        }
                        prefs.edit().putString("skills",skillMapToJson(skills))
                            .putString("skill_metadata_v1",skillMetadataToJson(skillMetadata))
                            .putString("disabled_skills_v1",disabledSkillsToJson(disabledSkills))
                            .putString("expert_profiles_v1", ExpertProfileCodec.encode(expertProfiles))
                             .putString("expert_versions_v1", ExpertRevisionCodec.encode(expertRevisions))
                             .putString("expert_templates_v1", ExpertTemplateCodec.encode(expertTemplates))
                             .putString("workflows_v1", WorkflowDefinitionCodec.encode(workflows)).apply()
                        if(name !in disabledSkills) selectedSkill=name
                        skillDraftName = ""
                        skillDraftPrompt = ""
                        editingSkillName = ""
                        skillDialogError = ""
                        showSkillDialog = false
                    }
                }) { Text("保存", color = Mint) }
            },
            dismissButton = { TextButton(onClick = { showSkillDialog = false; editingSkillName=""; skillDialogError="" }) { Text("取消") } }
        )
    }

    if (pendingTeamBundle != null) {
        val bundle = pendingTeamBundle!!
        AlertDialog(
            onDismissRequest = { pendingTeamBundle = null },
            title = { Text("导入团队预览") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bundle.name, color = Ink, style = MaterialTheme.typography.titleMedium)
                Text(bundle.description, color = Muted)
                Text("成员：${bundle.members.joinToString("、") { it.name }}", color = Mint)
                Text("导入后会一并安装成员专家配置；同名专家的角色指令将被更新。", color = Muted)
            } },
            confirmButton = { TextButton(onClick = {
                bundle.members.forEach { member ->
                    if (member.name !in experts) experts = experts + member.name
                    expertPrompts = expertPrompts + (member.name to member.prompt)
                }
                val previousTeam=expertTeams.firstOrNull { it.name==bundle.name }
                val team = ExpertTeam(previousTeam?.id ?: UUID.randomUUID().toString(), bundle.name, bundle.description, bundle.members.map { it.name })
                expertTeams = expertTeams.filterNot { it.name == bundle.name } + team
                if(selectedTeamId==previousTeam?.id) selectedTeamId=team.id
                prefs.edit().putString("experts", JSONArray(experts).toString())
                    .putString("expert_prompts", expertPromptsToJson(expertPrompts))
                    .putString("expert_teams_v1", expertTeamsToJson(expertTeams))
                    .putString("selected_team_id",selectedTeamId).apply()
                teamImportStatus = "已导入团队：${bundle.name}"
                pendingTeamBundle = null
            }) { Text("导入团队", color = Mint) } },
            dismissButton = { TextButton(onClick = { pendingTeamBundle = null }) { Text("取消") } }
        )
    }

    if (pendingExpertBundle != null) {
        val bundle = pendingExpertBundle!!
        AlertDialog(
            onDismissRequest = { pendingExpertBundle = null },
            title = { Text("导入专家预览") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bundle.name, color = Ink, style = MaterialTheme.typography.titleMedium)
                Text("版本：${bundle.version} · ${bundle.group} 组 · ${bundle.avatar}", color = Muted)
                Text(bundle.description.ifBlank { "无描述" }, color = Muted)
                Text("挂载技能：${bundle.skillNames.joinToString().ifBlank { "无" }}", color = Muted)
                Text("状态：${if (bundle.enabled) "启用" else "停用"} · Temperature：${bundle.temperature ?: "模型默认"} · Max tokens：${bundle.maxTokens ?: "模型默认"}", color = Muted)
                Text("角色指令", color = Mint)
                Text(bundle.prompt, color = Ink)
                if (bundle.name in experts) Text("同名专家已存在，导入将更新角色指令。", color = androidx.compose.ui.graphics.Color(0xFFFBBF24))
            } },
            confirmButton = { TextButton(onClick = {
                val missingSkills = bundle.skillNames.filter { it !in skills || it in disabledSkills }
                if (missingSkills.isNotEmpty()) {
                    expertImportStatus = "导入未完成，以下挂载技能不存在或已停用：${missingSkills.joinToString()}"
                    return@TextButton
                }
                val previousProfile = expertProfiles[bundle.name] ?: if (bundle.name in experts) ExpertProfile() else null
                if (previousProfile != null) {
                    expertRevisions = expertRevisions + (bundle.name to (expertRevisions[bundle.name].orEmpty() + ExpertRevision(previousProfile.version, expertPrompts[bundle.name].orEmpty(), previousProfile, System.currentTimeMillis())).takeLast(20))
                }
                experts = if (bundle.name in experts) experts else experts + bundle.name
                expertPrompts = expertPrompts + (bundle.name to bundle.prompt)
                expertProfiles = expertProfiles + (bundle.name to bundle.profile())
                if (!bundle.enabled && selectedExpert == bundle.name) {
                    selectedExpert = "通用助手"
                    prefs.edit().putString("selected_expert", selectedExpert).apply()
                }
                prefs.edit().putString("experts", JSONArray(experts).toString())
                    .putString("expert_prompts", expertPromptsToJson(expertPrompts))
                    .putString("expert_profiles_v1", ExpertProfileCodec.encode(expertProfiles))
                    .putString("expert_versions_v1", ExpertRevisionCodec.encode(expertRevisions)).apply()
                expertImportStatus = "已导入专家：${bundle.name}"
                pendingExpertBundle = null
            }) { Text("导入", color = Mint) } },
            dismissButton = { TextButton(onClick = { pendingExpertBundle = null }) { Text("取消") } }
        )
    }

    if (showWorkflowTargetPicker && workflowTargetPickerIndex in workflowDraftSteps.indices) {
        val targetStep = workflowDraftSteps[workflowTargetPickerIndex]
        val targets = when (targetStep.kind) {
            WorkflowStepKind.EXPERT -> experts
            WorkflowStepKind.SKILL -> skills.keys.filterNot { it in disabledSkills }
            WorkflowStepKind.PROMPT -> emptyList()
        }
        AlertDialog(
            onDismissRequest = { showWorkflowTargetPicker = false },
            title = { Text(if (targetStep.kind == WorkflowStepKind.EXPERT) "选择专家" else "选择技能") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (targets.isEmpty()) {
                        Text(if (targetStep.kind == WorkflowStepKind.EXPERT) "专家列表为空，请先创建专家。" else "技能库为空，请先创建或导入技能。", color = Muted)
                    }
                    targets.forEach { target ->
                        TextButton(
                            onClick = {
                                workflowDraftSteps = workflowDraftSteps.mapIndexed { index, step ->
                                    if (index == workflowTargetPickerIndex) step.copy(targetId = target) else step
                                }
                                showWorkflowTargetPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(target, color = Ink) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWorkflowTargetPicker = false }) { Text("关闭") }
            }
        )
    }

    if (showWorkflowDialog) {
        AlertDialog(
            onDismissRequest = { showWorkflowDialog = false },
            title = { Text(if (editingWorkflowId.isBlank()) "新建工作流" else "编辑工作流") },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(workflowDraftName, { workflowDraftName = it.take(40) }, label = { Text("工作流名称") }, singleLine = true)
                    OutlinedTextField(workflowDraftDescription, { workflowDraftDescription = it.take(300) }, label = { Text("用途说明") }, minLines = 2)
                    workflowDraftSteps.forEachIndexed { index, step ->
                        Column(
                            modifier = Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("步骤 ${index + 1}", color = Mint, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    workflowDraftSteps = workflowDraftSteps.mapIndexed { stepIndex, item ->
                                        if (stepIndex == index) item.copy(enabled = !item.enabled) else item
                                    }
                                }) { Text(if (step.enabled) "已启用" else "已停用", color = if (step.enabled) Mint else Muted) }
                                TextButton(
                                    enabled = index > 0,
                                    onClick = {
                                        val updated = workflowDraftSteps.toMutableList()
                                        val current = updated[index]
                                        updated[index] = updated[index - 1]
                                        updated[index - 1] = current
                                        workflowDraftSteps = updated
                                    }
                                ) { Text("上移") }
                                TextButton(
                                    enabled = index < workflowDraftSteps.lastIndex,
                                    onClick = {
                                        val updated = workflowDraftSteps.toMutableList()
                                        val current = updated[index]
                                        updated[index] = updated[index + 1]
                                        updated[index + 1] = current
                                        workflowDraftSteps = updated
                                    }
                                ) { Text("下移") }
                                TextButton(
                                    enabled = workflowDraftSteps.size > 1,
                                    onClick = {
                                        workflowDraftSteps = workflowDraftSteps.filterIndexed { stepIndex, _ -> stepIndex != index }
                                    }
                                ) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                            }
                            OutlinedTextField(
                                value = step.title,
                                onValueChange = { value ->
                                    workflowDraftSteps = workflowDraftSteps.mapIndexed { stepIndex, item ->
                                        if (stepIndex == index) item.copy(title = value.take(40)) else item
                                    }
                                },
                                label = { Text("步骤名称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextButton(
                                onClick = {
                                    workflowDraftSteps = workflowDraftSteps.mapIndexed { stepIndex, item ->
                                        if (stepIndex == index) item.copy(
                                            kind = WorkflowStepKind.values()[(item.kind.ordinal + 1) % WorkflowStepKind.values().size]
                                        ) else item
                                    }
                                }
                            ) {
                                Text(
                                    "节点类型：${when (step.kind) { WorkflowStepKind.PROMPT -> "提示词"; WorkflowStepKind.EXPERT -> "专家"; WorkflowStepKind.SKILL -> "技能" }}（点击切换）",
                                    color = Mint
                                )
                            }
                            if (step.kind != WorkflowStepKind.PROMPT) {
                                TextButton(onClick = {
                                    workflowTargetPickerIndex = index
                                    showWorkflowTargetPicker = true
                                }) {
                                    Text(
                                        when (step.kind) {
                                            WorkflowStepKind.EXPERT -> "选择专家：${step.targetId.ifBlank { step.title }}"
                                            WorkflowStepKind.SKILL -> "选择技能：${step.targetId.ifBlank { step.title }}"
                                            WorkflowStepKind.PROMPT -> ""
                                        },
                                        color = Mint
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = step.instruction,
                                onValueChange = { value ->
                                    workflowDraftSteps = workflowDraftSteps.mapIndexed { stepIndex, item ->
                                        if (stepIndex == index) item.copy(instruction = value.take(4000)) else item
                                    }
                                },
                                label = { Text("执行指令") },
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    workflowDraftSteps = workflowDraftSteps.mapIndexed { stepIndex, item ->
                                        if (stepIndex == index) item.copy(retries = (item.retries + 1) % 4) else item
                                    }
                                }) { Text("失败重试：${step.retries} 次", color = Mint) }
                                TextButton(onClick = {
                                    val limits = listOf(30, 60, 120, 180, 300)
                                    workflowDraftSteps = workflowDraftSteps.mapIndexed { stepIndex, item ->
                                        if (stepIndex == index) {
                                            val next = limits.indexOf(item.timeoutSeconds).let { (it + 1).mod(limits.size) }
                                            item.copy(timeoutSeconds = limits[next])
                                        } else item
                                    }
                                }) { Text("超时：${step.timeoutSeconds} 秒", color = Mint) }
                            }
                            TextButton(onClick = {
                                workflowDraftSteps = workflowDraftSteps.mapIndexed { stepIndex, item ->
                                    if (stepIndex == index) item.copy(continueOnError = !item.continueOnError) else item
                                }
                            }) {
                                Text(
                                    if (step.continueOnError) "失败后：继续执行" else "失败后：停止工作流",
                                    color = if (step.continueOnError) Mint else Muted
                                )
                            }
                            OutlinedTextField(
                                value = step.skipIfPreviousContains,
                                onValueChange = { value ->
                                    workflowDraftSteps = workflowDraftSteps.mapIndexed { stepIndex, item ->
                                        if (stepIndex == index) item.copy(skipIfPreviousContains = value.take(120)) else item
                                    }
                                },
                                label = { Text("条件跳过：前一步包含此词时跳过（可留空）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextButton(onClick = {
                                workflowDraftSteps = workflowDraftSteps.mapIndexed { stepIndex, item ->
                                    if (stepIndex == index) item.copy(repeatCount = item.repeatCount % 3 + 1) else item
                                }
                            }) { Text("本步骤循环：${step.repeatCount} 次", color = Mint) }
                        }
                    }
                    OutlinedButton(
                        enabled = workflowDraftSteps.size < 20,
                        onClick = {
                            workflowDraftSteps = workflowDraftSteps + WorkflowStep(
                                kind = WorkflowStepKind.PROMPT,
                                title = "新步骤 ${workflowDraftSteps.size + 1}",
                                instruction = "描述这一步需要完成的任务。"
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (workflowDraftSteps.size >= 20) "已达到 20 步上限" else "＋ 添加步骤") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val workflow = WorkflowDefinition(
                        id = editingWorkflowId.ifBlank { UUID.randomUUID().toString() },
                        name = workflowDraftName.trim(),
                        description = workflowDraftDescription.trim(),
                        steps = workflowDraftSteps.map { step ->
                            step.copy(title = step.title.trim(), instruction = step.instruction.trim())
                        }
                    )
                    runCatching { WorkflowDefinitionCodec.validate(workflow) }.onSuccess {
                        workflows = if (editingWorkflowId.isBlank()) workflows + workflow else workflows.map { if (it.id == editingWorkflowId) workflow else it }
                        prefs.edit().putString("workflows_v1", WorkflowDefinitionCodec.encode(workflows)).apply()
                        showWorkflowDialog = false
                    }.onFailure { workflowRunStatus = it.message ?: "工作流内容无效" }
                }) { Text("保存工作流", color = Mint) }
            },
            dismissButton = { TextButton(onClick = { showWorkflowDialog = false }) { Text("取消") } }
        )
    }

    if (showExpertDialog) {
        AlertDialog(
            onDismissRequest = { showExpertDialog = false; expertDialogError = "" },
            title = { Text(if (editingExpertName.isBlank()) "创建专家" else "编辑专家") },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = expertDraft, onValueChange = { expertDraft = it; expertDialogError = "" }, label = { Text("专家名称") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = expertPromptDraft, onValueChange = { expertPromptDraft = it }, label = { Text("专家角色与工作要求") }, placeholder = { Text("例如：你是一位严谨的中文文案编辑，先判断目标读者，再给出可直接发布的文案。") }, minLines = 4)
                    OutlinedTextField(value = expertGroupDraft, onValueChange = { expertGroupDraft = it.take(30) }, label = { Text("分组") }, singleLine = true)
                    OutlinedTextField(value = expertAvatarDraft, onValueChange = { expertAvatarDraft = it.take(8) }, label = { Text("头像 Emoji") }, singleLine = true)
                    OutlinedTextField(value = expertDescriptionDraft, onValueChange = { expertDescriptionDraft = it.take(300) }, label = { Text("专家简介") }, singleLine = false, minLines = 2)
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = expertEnabledDraft, onCheckedChange = { expertEnabledDraft = it }); Text("启用此专家", color = Ink) }
                    Text("挂载技能（仅启用技能可新挂载）", color = Mint, style = MaterialTheme.typography.titleSmall)
                    skills.forEach { (skillName, _) ->
                        val attached = skillName in expertSkillDraft
                        val disabled = skillName in disabledSkills
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = attached, enabled = !disabled || attached, onCheckedChange = { checked -> expertSkillDraft = if (checked) expertSkillDraft + skillName else expertSkillDraft - skillName })
                            Text(skillName + if (disabled) "（已停用，执行时跳过）" else "", color = if (disabled) Muted else Ink)
                        }
                    }
                    OutlinedTextField(value = expertTemperatureDraft, onValueChange = { expertTemperatureDraft = it.take(8) }, label = { Text("Temperature（留空使用模型默认）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(value = expertMaxTokensDraft, onValueChange = { expertMaxTokensDraft = it.filter(Char::isDigit).take(5) }, label = { Text("最大输出 Token（留空使用模型默认）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (expertDialogError.isNotBlank()) Text(expertDialogError, color = androidx.compose.ui.graphics.Color(0xFFF87171), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = expertDraft.trim()
                    val oldName = editingExpertName
                    val validationError = ExpertCatalogRules.validateDefinition(name, expertPromptDraft, oldName, experts)
                    if (validationError != null) {
                        expertDialogError = validationError
                        return@TextButton
                    }
                    val temperature = expertTemperatureDraft.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
                    val maxTokens = expertMaxTokensDraft.trim().takeIf { it.isNotBlank() }?.toIntOrNull()
                    if ((expertTemperatureDraft.isNotBlank() && temperature == null) || (expertMaxTokensDraft.isNotBlank() && maxTokens == null)) {
                        expertDialogError = "模型参数格式无效，请检查 Temperature 和最大 Token。"
                        return@TextButton
                    }
                    var profile = ExpertProfile(
                        group = expertGroupDraft.trim(),
                        avatar = expertAvatarDraft.trim(),
                        enabled = expertEnabledDraft,
                        skillNames = expertSkillDraft.toList(),
                        temperature = temperature,
                        maxTokens = maxTokens,
                        description = expertDescriptionDraft.trim(),
                        version = expertProfiles[oldName]?.version ?: "1.0.0",
                        source = expertProfiles[oldName]?.source ?: "Nestro 本地专家"
                    )
                    val profileError = ExpertProfileCodec.validate(profile, skills.keys)
                    if (profileError != null) {
                        expertDialogError = profileError
                        return@TextButton
                    }
                    val previousProfile = expertProfiles[oldName] ?: if (oldName in experts) ExpertProfile() else null
                    val previousPrompt = expertPrompts[oldName].orEmpty()
                    if (oldName.isNotBlank() && previousProfile != null && (previousPrompt != expertPromptDraft.trim() || previousProfile.copy(version = profile.version) != profile)) {
                        expertRevisions = expertRevisions + (oldName to (expertRevisions[oldName].orEmpty() + ExpertRevision(previousProfile.version, previousPrompt, previousProfile, System.currentTimeMillis())).takeLast(20))
                        profile = profile.copy(version = ExpertRevisionCodec.bumpVersion(previousProfile.version))
                    }
                    if (oldName.isNotBlank() && oldName != name) {
                        expertRevisions = (expertRevisions - oldName) + (name to expertRevisions[oldName].orEmpty())
                    }
                    experts = if (oldName.isBlank()) experts + name else experts.map { if (it == oldName) name else it }
                    expertPrompts = (expertPrompts - oldName) + (name to expertPromptDraft.trim())
                    expertProfiles = (expertProfiles - oldName) + (name to profile)
                    if (oldName.isNotBlank() && oldName != name) {
                        expertTeams = expertTeams.map { team -> team.copy(members = team.members.map { if (it == oldName) name else it }) }
                        workflows = ExpertCatalogRules.renameWorkflowReferences(workflows, oldName, name)
                    }
                    prefs.edit().putString("experts", JSONArray(experts).toString())
                        .putString("expert_prompts", expertPromptsToJson(expertPrompts))
                        .putString("expert_profiles_v1", ExpertProfileCodec.encode(expertProfiles))
                        .putString("expert_versions_v1", ExpertRevisionCodec.encode(expertRevisions))
                        .putString("expert_teams_v1", expertTeamsToJson(expertTeams))
                        .putString("workflows_v1", WorkflowDefinitionCodec.encode(workflows))
                        .putString("selected_expert", selectedExpert).apply()
                    if (selectedExpert == oldName || selectedExpert == name) {
                        selectedExpert = if (profile.enabled) name else "通用助手"
                    }
                    prefs.edit().putString("selected_expert", selectedExpert).apply()
                    editingExpertName = ""
                    expertDraft = ""
                    expertPromptDraft = ""
                    showExpertDialog = false
                    page = "experts"
                }) { Text("保存专家", color = Mint) }
            },
            dismissButton = { TextButton(onClick = { showExpertDialog = false; expertDialogError = "" }) { Text("取消") } }
        )
    }

    if (showExpertHistoryDialog) {
        val name = historyExpertName
        val revisions = expertRevisions[name].orEmpty()
        val compared = revisions.lastOrNull { it.version == historyCompareVersion }
        AlertDialog(
            onDismissRequest = { showExpertHistoryDialog = false; historyCompareVersion = "" },
            title = { Text("$name · 版本历史") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前版本 v${expertProfiles[name]?.version ?: "1.0.0"}", color = Mint)
                    if (revisions.isEmpty()) Text("暂无历史版本。修改并保存专家后，会自动留下上一版。", color = Muted)
                    revisions.asReversed().forEach { revision ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("v${revision.version}", color = Ink)
                                Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(revision.savedAt)), color = Muted, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { historyCompareVersion = revision.version }) { Text("对比", color = Mint) }
                            TextButton(onClick = {
                                val currentProfile = expertProfiles[name] ?: ExpertProfile()
                                expertRevisions = expertRevisions + (name to (expertRevisions[name].orEmpty() + ExpertRevision(currentProfile.version, expertPrompts[name].orEmpty(), currentProfile, System.currentTimeMillis())).takeLast(20))
                                expertPrompts = expertPrompts + (name to revision.prompt)
                                expertProfiles = expertProfiles + (name to revision.profile.copy(version = ExpertRevisionCodec.bumpVersion(currentProfile.version)))
                                prefs.edit().putString("expert_prompts", expertPromptsToJson(expertPrompts))
                                    .putString("expert_profiles_v1", ExpertProfileCodec.encode(expertProfiles))
                                    .putString("expert_versions_v1", ExpertRevisionCodec.encode(expertRevisions)).apply()
                                historyCompareVersion = ""
                                showExpertHistoryDialog = false
                            }) { Text("恢复", color = Mint) }
                        }
                    }
                    if (compared != null) {
                        Text("与当前版本的差异 · v${compared.version}", color = Mint, style = MaterialTheme.typography.titleSmall)
                        val currentProfile = expertProfiles[name] ?: ExpertProfile()
                        val metadataDiff = buildList {
                            if (compared.profile.group != currentProfile.group) add("分组：${compared.profile.group} → ${currentProfile.group}")
                            if (compared.profile.avatar != currentProfile.avatar) add("头像：${compared.profile.avatar} → ${currentProfile.avatar}")
                            if (compared.profile.skillNames != currentProfile.skillNames) add("挂载技能：${compared.profile.skillNames.joinToString()} → ${currentProfile.skillNames.joinToString()}")
                            if (compared.profile.temperature != currentProfile.temperature) add("Temperature：${compared.profile.temperature} → ${currentProfile.temperature}")
                            if (compared.profile.maxTokens != currentProfile.maxTokens) add("Max tokens：${compared.profile.maxTokens} → ${currentProfile.maxTokens}")
                            if (compared.profile.enabled != currentProfile.enabled) add("启用状态：${compared.profile.enabled} → ${currentProfile.enabled}")
                        }
                        Text((ExpertRevisionCodec.diff(compared.prompt, expertPrompts[name].orEmpty()) + metadataDiff).joinToString("\n").ifBlank { "内容没有变化" }.take(7000), color = Ink, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExpertHistoryDialog = false; historyCompareVersion = "" }) { Text("关闭") } }
        )
    }

    if (showExpertTemplatesDialog) {
        AlertDialog(
            onDismissRequest = { showExpertTemplatesDialog = false },
            title = { Text("专家模板库") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = templateNameDraft, onValueChange = { templateNameDraft = it.take(40) }, label = { Text("模板名称") }, singleLine = true)
                    Button(onClick = {
                        val name = templateNameDraft.trim()
                        val sourceName = selectedExpert
                        val prompt = expertPrompts[sourceName].orEmpty().ifBlank { ExpertTeamDefaults.rolePrompt(sourceName) }
                        val profile = expertProfiles[sourceName] ?: ExpertProfile()
                        if (name.isBlank() || prompt.isBlank()) {
                            expertImportStatus = "模板名称或当前专家指令为空，无法保存。"
                        } else if (name !in expertTemplates.map { it.name } && expertTemplates.size >= 100) {
                            expertImportStatus = "模板库已达到 100 个上限。"
                        } else {
                            val template = ExpertTemplate(name = name, description = profile.description, prompt = prompt, profile = profile)
                            expertTemplates = expertTemplates.filterNot { it.name == name } + template
                            prefs.edit().putString("expert_templates_v1", ExpertTemplateCodec.encode(expertTemplates)).apply()
                            expertImportStatus = "模板「$name」已保存。"
                        }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) { Text("保存当前专家为模板") }
                    if (expertTemplates.isEmpty()) Text("还没有模板。可以先选中一个专家，再保存为模板。", color = Muted)
                    expertTemplates.forEach { template ->
                        Surface(color = Card, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("${template.profile.avatar}  ${template.name}", color = Ink, style = MaterialTheme.typography.titleSmall)
                                Text(template.description.ifBlank { "无描述" }, color = Muted, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = {
                                        editingExpertName = template.name.takeIf { it in experts }.orEmpty()
                                        expertDraft = template.name
                                        expertPromptDraft = template.prompt
                                        expertGroupDraft = template.profile.group
                                        expertAvatarDraft = template.profile.avatar
                                        expertEnabledDraft = template.profile.enabled
                                        expertSkillDraft = template.profile.skillNames.filter { it in skills && it !in disabledSkills }.toSet()
                                        expertTemperatureDraft = template.profile.temperature?.toString().orEmpty()
                                        expertMaxTokensDraft = template.profile.maxTokens?.toString().orEmpty()
                                        expertDescriptionDraft = template.profile.description
                                        expertDialogError = ""
                                        showExpertTemplatesDialog = false
                                        showExpertDialog = true
                                    }) { Text("应用", color = Mint) }
                                    TextButton(onClick = {
                                        expertTemplates = expertTemplates.filterNot { it.id == template.id }
                                        prefs.edit().putString("expert_templates_v1", ExpertTemplateCodec.encode(expertTemplates)).apply()
                                    }) { Text("删除", color = ColorStop) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExpertTemplatesDialog = false }) { Text("关闭") } }
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
private fun MessageBubble(message: ChatMessage, onSaveArtifact: ((String) -> Unit)? = null, onEdit: (() -> Unit)? = null, onRegenerate: (() -> Unit)? = null, onRetry: (() -> Unit)? = null, onVersionChange: ((Int) -> Unit)? = null) {
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
            if (!user && message.content.isNotBlank() && onSaveArtifact != null) {
                TextButton(onClick = { onSaveArtifact.invoke(message.content) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(Icons.Outlined.Bookmark, contentDescription = null, tint = Mint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("存入产物库", color = Mint, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!user && message.content.startsWith("请求失败：") && onRetry != null) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, tint = Mint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("重试这条消息", color = Mint, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (user && onEdit != null) {
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, tint = Muted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑", color = Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!user && message.versions.isNotEmpty() && onVersionChange != null) {
                val versionCount = message.allVersions().size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = message.activeVersion > 0, onClick = { onVersionChange(message.activeVersion - 1) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("‹", color = Mint) }
                    Text("版本 ${message.activeVersion + 1}/$versionCount", color = Muted, style = MaterialTheme.typography.labelSmall)
                    TextButton(enabled = message.activeVersion < versionCount - 1, onClick = { onVersionChange(message.activeVersion + 1) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("›", color = Mint) }
                }
            }
            if (!user && onRegenerate != null) {
                TextButton(onClick = onRegenerate, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, tint = Muted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重新生成", color = Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ModelManager(
    profiles: List<ModelProfileMetadata>,
    activeProfileId: String,
    activeConfig: ModelConfig,
    usage: Map<String, ModelUsageStats>,
    failoverEnabled: Boolean,
    onFailoverChange: (Boolean) -> Unit,
    loadKey: (String) -> String,
    onActivate: (String) -> Unit,
    onSave: (ModelProfileMetadata, String, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var editingId by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var activateAfterSave by remember { mutableStateOf(true) }
    var testing by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf("") }

    fun beginNew() {
        editingId = java.util.UUID.randomUUID().toString()
        name = ""
        endpoint = "https://"
        model = ""
        key = ""
        activateAfterSave = true
        testStatus = ""
        formError = ""
    }
    fun edit(profile: ModelProfileMetadata) {
        editingId = profile.id
        name = profile.name
        endpoint = profile.baseUrl
        model = profile.model
        key = loadKey(profile.id)
        activateAfterSave = profile.id == activeProfileId
        testStatus = ""
        formError = ""
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模型管理", color = Ink, style = MaterialTheme.typography.headlineSmall)
        Text("最多保存 20 组模型配置，支持不同服务商与模型切换。所有配置共用 OpenAI Chat Completions 兼容协议。", color = Muted)
        Surface(color = Card, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动故障切换", color = Ink)
                    Text("主模型连接失败时，按配置列表顺序尝试其他已保存 Key。开启后可能产生备用服务商费用。", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = failoverEnabled, onCheckedChange = onFailoverChange)
            }
        }
        if (profiles.isEmpty()) {
            Text("还没有模型配置。先添加一组 API 地址、模型名称和 Key。", color = Muted)
        } else {
            profiles.forEach { profile ->
                val active = profile.id == activeProfileId
                Surface(color = if (active) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name + if (active) " · 当前使用" else "", color = if (active) Mint else Ink, style = MaterialTheme.typography.titleMedium)
                                Text("${profile.model} · ${profile.baseUrl}", color = Muted, style = MaterialTheme.typography.bodySmall)
                                Text(if (loadKey(profile.id).isNotBlank()) "API Key 已加密保存" else "缺少 API Key", color = if (loadKey(profile.id).isNotBlank()) Mint else ColorStop, style = MaterialTheme.typography.labelSmall)
                                usage[profile.id]?.let { stats ->
                                    Text("成功请求 ${stats.requests} 次 · 估算 Token：输入 ${stats.estimatedInputTokens} / 输出 ${stats.estimatedOutputTokens}", color = Muted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { edit(profile) }) { Text("编辑", color = Mint) }
                            TextButton(onClick = { onActivate(profile.id) }, enabled = !active && loadKey(profile.id).isNotBlank()) { Text("启用", color = Mint) }
                            TextButton(onClick = { onDelete(profile.id); if (editingId == profile.id) beginNew() }) { Text("删除", color = ColorStop) }
                        }
                    }
                }
            }
        }
        Text(if (editingId in profiles.map { it.id }) "编辑模型配置" else "新增模型配置", color = Ink, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it.take(40) }, modifier = Modifier.fillMaxWidth(), label = { Text("配置名称") }, singleLine = true)
        OutlinedTextField(endpoint, { endpoint = it.take(2048) }, modifier = Modifier.fillMaxWidth(), label = { Text("API 地址") }, placeholder = { Text("https://api.example.com/v1") }, singleLine = true)
        OutlinedTextField(model, { model = it.take(200) }, modifier = Modifier.fillMaxWidth(), label = { Text("模型名称") }, placeholder = { Text("例如 deepseek-chat") }, singleLine = true)
        OutlinedTextField(key, { key = it.take(4096) }, modifier = Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
        Text("每组 Key 独立使用 Android Keystore AES-GCM 加密。备份只包含配置名称、地址和模型名，不包含 Key。", color = Muted, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = activateAfterSave, onCheckedChange = { activateAfterSave = it })
            Text("保存后立即设为当前模型", color = Ink)
        }
        Button(
            onClick = {
                val candidate = ModelProfileMetadata(editingId, name.trim(), endpoint.trim().trimEnd('/'), model.trim())
                formError = ModelProfileCatalogCodec.validate(candidate)
                    ?: ModelProfileCatalogCodec.validateCatalog(profiles.filterNot { it.id == editingId } + candidate).orEmpty()
                if (formError.isBlank() && key.isBlank() && editingId !in profiles.map { it.id }) formError = "新配置必须填写 API Key"
                if (formError.isBlank()) onSave(candidate, key.trim(), activateAfterSave)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
        ) { Text("保存模型配置") }
        OutlinedButton(
            onClick = {
                val candidate = ModelConfig(endpoint.trim().trimEnd('/'), model.trim(), key.trim())
                testing = true
                testStatus = "正在测试连接…"
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            require(candidate.baseUrl.startsWith("https://") && candidate.model.isNotBlank() && candidate.apiKey.isNotBlank()) { "请填写 HTTPS 地址、模型名称和 API Key" }
                            OpenAiClient.complete(candidate, listOf(ChatMessage(UUID.randomUUID().toString(), "user", "Reply with OK.")), "")
                        }
                    }
                    testStatus = result.fold({ response ->
                        if (response.isBlank()) "连接成功，但模型返回了空内容。" else "连接成功：模型已返回内容。"
                    }, { "连接失败：${it.message ?: "请检查地址、Key 与网络"}" })
                    testing = false
                }
            },
            enabled = !testing && endpoint.startsWith("https://") && model.isNotBlank() && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (testing) "测试中…" else "测试当前编辑配置") }
        if (formError.isNotBlank()) Text(formError, color = ColorStop, style = MaterialTheme.typography.bodySmall)
        if (testStatus.isNotBlank()) Text(testStatus, color = if (testStatus.startsWith("连接失败")) ColorStop else Muted, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { beginNew() }, modifier = Modifier.fillMaxWidth()) { Text("＋ 新建另一组配置", color = Mint) }
        Text("当前实际调用的模型：${activeConfig.model.ifBlank { "未配置" }}", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SimplePage(title: String, description: String) {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Ink, style = MaterialTheme.typography.headlineSmall)
        Text(description, color = Muted, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun loadSkillMetadata(prefs: android.content.SharedPreferences): Map<String, String> {
    val raw = prefs.getString("skill_metadata_v1", "{}") ?: "{}"
    return runCatching {
        val json = JSONObject(raw)
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optString(key)
            if (key.length <= 40 && value.length <= 4000) result[key] = value
        }
        result
    }.getOrDefault(emptyMap())
}

private fun skillMetadataToJson(metadata: Map<String, String>): String =
    JSONObject().apply { metadata.forEach { (name, value) -> put(name, value) } }.toString()

private fun loadDisabledSkills(prefs: android.content.SharedPreferences): Set<String> =
    runCatching {
        val array=JSONArray(prefs.getString("disabled_skills_v1","[]") ?: "[]")
        (0 until array.length()).map { array.getString(it) }.filter { it.length<=40 }.toSet()
    }.getOrDefault(emptySet())

private fun disabledSkillsToJson(names:Set<String>):String = JSONArray(names.sorted()).toString()

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

private fun boundedRequestHistory(history: List<ChatMessage>, systemPrompt: String = "", maxTokens: Int = 2048): List<ChatMessage> {
    val source = history.takeLast(40)
    val bounded = ChatRequestPolicy.boundedHistory(source.map { ChatHistoryItem(it.role, it.content) }, systemPrompt, outputReserveTokens = maxTokens)
    return bounded.mapIndexed { index, item -> source[source.size - bounded.size + index].copy(content = item.content) }
}

private object OpenAiClient {
    private val activeConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val requestConnections = java.util.concurrent.ConcurrentHashMap<String, HttpURLConnection>()

    fun cancel(requestId: String? = null) {
        val targets = if (requestId.isNullOrBlank()) activeConnections.toList() else listOfNotNull(requestConnections[requestId])
        targets.forEach { connection -> runCatching { connection.disconnect() } }
    }

    fun completeWithFallback(
        configs: List<ModelConfig>,
        history: List<ChatMessage>,
        systemPrompt: String,
        connectTimeoutMillis: Int = 20000,
        readTimeoutMillis: Int = 90000,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onUsage: (ModelConfig, Int, Int) -> Unit = { _, _, _ -> }
    ): String {
        require(configs.isNotEmpty()) { "没有可用的模型配置" }
        var lastError: Throwable? = null
        configs.distinctBy { "${it.baseUrl}|${it.model}|${it.apiKey}" }.forEach { candidate ->
            try {
                val response = complete(candidate, history, systemPrompt, connectTimeoutMillis, readTimeoutMillis, temperature, maxTokens)
                onUsage(candidate, systemPrompt.length + history.sumOf { it.content.length }, response.length)
                return response
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                lastError = error
            }
        }
        throw IllegalStateException("主模型与备用模型均调用失败：${lastError?.message ?: "未知错误"}", lastError)
    }

    suspend fun streamWithFallback(
        configs: List<ModelConfig>,
        history: List<ChatMessage>,
        systemPrompt: String,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onUsage: (ModelConfig, Int, Int) -> Unit = { _, _, _ -> },
        requestId: String = UUID.randomUUID().toString(),
        timeoutMillis: Long = 180_000,
        onChunk: suspend (String) -> Unit
    ): String {
        require(configs.isNotEmpty()) { "没有可用的模型配置" }
        var lastError: Throwable? = null
        return try {
            withTimeout(timeoutMillis.coerceIn(15_000, 300_000)) {
            configs.distinctBy { "${it.baseUrl}|${it.model}|${it.apiKey}" }.forEach { candidate ->
                var attempt = 0
                while (attempt < 2) {
                    var emitted = false
                    try {
                        val response = stream(candidate, history, systemPrompt, temperature, maxTokens, requestId) { chunk ->
                            emitted = true
                            onChunk(chunk)
                        }
                        onUsage(candidate, systemPrompt.length + history.sumOf { it.content.length }, response.length)
                        return@withTimeout response
                    } catch (error: Throwable) {
                        if (error is kotlinx.coroutines.CancellationException || emitted) throw error
                        lastError = error
                        if (attempt == 0 && ChatRequestPolicy.shouldRetry(error)) {
                            attempt++
                            delay(600)
                            continue
                        }
                        break
                    }
                }
            }
            throw IllegalStateException("主模型与备用模型均调用失败：${lastError?.message ?: "未知错误"}", lastError)
            }
        } finally {
            cancel(requestId)
        }
    }

    fun complete(config: ModelConfig, history: List<ChatMessage>, systemPrompt: String, connectTimeoutMillis:Int=20000, readTimeoutMillis:Int=90000, temperature:Double?=null, maxTokens:Int?=null): String {
        val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMillis.coerceIn(5000,60000)
            readTimeout = readTimeoutMillis.coerceIn(5000,120000)
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + config.apiKey)
        }
        activeConnections.add(connection)
        try {
            val body = JSONObject().apply {
                put("model", config.model)
                if (temperature != null) put("temperature", temperature.coerceIn(0.0, 2.0))
                if (maxTokens != null) put("max_tokens", maxTokens.coerceIn(1, 32768))
                put("messages", JSONArray().apply {
                    if (systemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", systemPrompt))
                    boundedRequestHistory(history, systemPrompt, maxTokens ?: 2048).forEach { msg ->
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
            activeConnections.remove(connection)
            connection.disconnect()
        }
    }

    suspend fun stream(config: ModelConfig, history: List<ChatMessage>, systemPrompt: String, temperature: Double? = null, maxTokens: Int? = null, requestId: String = UUID.randomUUID().toString(), onChunk: suspend (String) -> Unit): String = withContext(Dispatchers.IO) {
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
        activeConnections.add(connection)
        requestConnections[requestId] = connection
        val answer = StringBuilder()
        val pending = StringBuilder()
        try {
            val body = JSONObject().apply {
                put("model", config.model)
                if (temperature != null) put("temperature", temperature.coerceIn(0.0, 2.0))
                if (maxTokens != null) put("max_tokens", maxTokens.coerceIn(1, 32768))
                put("messages", JSONArray().apply {
                    if (systemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", systemPrompt))
                    boundedRequestHistory(history, systemPrompt, maxTokens ?: 2048).forEach { msg -> put(JSONObject().put("role", msg.role).put("content", msg.content)) }
                })
                put("stream", true)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val response = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException("HTTP $code: " + (detail?.takeIf { it.isNotBlank() } ?: response.take(300)))
            }
            val decoder = app.nestro.data.OpenAiSseDecoder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val delta = decoder.acceptLine(line)
                    if (!delta.isNullOrEmpty()) {
                        answer.append(delta)
                        pending.append(delta)
                        if (pending.length >= 24) {
                            val chunk = pending.toString()
                            pending.setLength(0)
                            onChunk(chunk)
                        }
                    }
                    if (decoder.isDone) break
                    // Compatibility with providers that return a single JSON object instead of SSE.
                    if (line.trimStart().startsWith("{")) {
                        val fallback = runCatching {
                            JSONObject(line).getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").getString("content")
                        }.getOrNull()
                        if (!fallback.isNullOrEmpty()) {
                            answer.append(fallback)
                            pending.append(fallback)
                        }
                    }
                }
            }
            decoder.finish()?.let { delta ->
                answer.append(delta)
                pending.append(delta)
            }
            if (pending.isNotEmpty()) onChunk(pending.toString())
            answer.toString()
        } finally {
            activeConnections.remove(connection)
            requestConnections.remove(requestId, connection)
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
    fun writeProfile(context: Context, id: String, apiKey: String) {
        require(id.isNotBlank() && id.length <= 80)
        val prefs = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        if (apiKey.isBlank()) {
            prefs.edit().remove("model_api_key_enc_$id").apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString("model_api_key_enc_$id", packed).apply()
    }

    fun readProfile(context: Context, id: String): String {
        val prefs = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val packed = prefs.getString("model_api_key_enc_$id", null) ?: return ""
        return runCatching {
            val parts = packed.split(":")
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun deleteProfile(context: Context, id: String) {
        context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            .edit().remove("model_api_key_enc_$id").apply()
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
                ChatMessage(message.getString("id"), message.getString("role"), message.getString("content"), jsonStringList(message.optJSONArray("versions")), message.optInt("active_version", 0))
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
    // Room is authoritative after the one-time migration. Do not overwrite the complete
    // legacy fallback snapshot with the in-memory summary cache.
    if (prefs.getBoolean("room_storage_initialized", false)) return
    val array = JSONArray()
    conversations.take(100).forEach { conversation ->
        val messages = JSONArray()
        conversation.messages.takeLast(500).forEach { message ->
            messages.put(JSONObject().put("id", message.id).put("role", message.role).put("content", message.content)
                .put("versions", JSONArray(message.versions.take(20))).put("active_version", message.activeVersion.coerceAtLeast(0)))
        }
        array.put(JSONObject().put("id", conversation.id).put("title", conversation.title.take(80))
            .put("updated_at", conversation.updatedAt).put("project_id", conversation.projectId).put("messages", messages))
    }
    val editor = prefs.edit().putString("conversations_v1", array.toString())
    if (markDirty) editor.putLong("local_snapshot_saved_at", System.currentTimeMillis())
    editor.apply()
}

private fun saveConversationMessages(
    prefs: android.content.SharedPreferences,
    activeId: String,
    messages: List<ChatMessage>,
    projectId: String = "default",
    currentConversations: List<ConversationRecord> = emptyList()
): List<ConversationRecord> {
    val existing = if (prefs.getBoolean("room_storage_initialized", false)) {
        currentConversations
    } else {
        loadConversations(prefs)
    }
    val record = existing.firstOrNull { it.id == activeId }
        ?: ConversationRecord(activeId.ifBlank { UUID.randomUUID().toString() }, "新对话", System.currentTimeMillis(), emptyList(), projectId)
    val firstPrompt = messages.firstOrNull { it.role == "user" }?.content?.take(32)?.ifBlank { "新对话" } ?: record.title
    val updated = record.copy(title = if (record.title == "新对话" || record.title == "旧对话") firstPrompt else record.title,
        updatedAt = System.currentTimeMillis(), messages = messages.takeLast(500), messagesLoaded = true)
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
                item.optString("instructions", "").take(4000), item.optLong("created_at", System.currentTimeMillis()), item.optLong("archived_at", 0L).coerceAtLeast(0L))
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }.getOrDefault(emptyList())
    val all = if (parsed.any { it.id == "default" }) parsed else listOf(ProjectRecord("default", "默认空间", "", System.currentTimeMillis())) + parsed
    return all.distinctBy { it.id }.take(100)
}

private fun parseProjects(array: JSONArray?): List<ProjectRecord> {
    val parsed = if (array == null) emptyList() else (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        ProjectRecord(
            item.getString("id"),
            item.optString("name", "项目"),
            item.optString("instructions", ""),
            item.optLong("created_at", System.currentTimeMillis()),
            item.optLong("archived_at", 0L).coerceAtLeast(0L)
        )
    }
    val withDefault = if (parsed.any { it.id == "default" }) parsed
        else listOf(ProjectRecord("default", "默认空间", "", System.currentTimeMillis())) + parsed
    require(withDefault.all { it.instructions.length <= 4000 }) { "项目空间指令过长" }
    require(ProjectSpaceRules.validateCollection(withDefault.map { ProjectSpaceRules.Entry(it.id, it.name) }) == null) {
        ProjectSpaceRules.validateCollection(withDefault.map { ProjectSpaceRules.Entry(it.id, it.name) }) ?: "项目空间数据无效"
    }
    return withDefault
}

private fun projectsToJson(projects: List<ProjectRecord>): String {
    val array = JSONArray()
    projects.take(100).forEach { project ->
        array.put(JSONObject().put("id", project.id).put("name", project.name.take(40))
            .put("instructions", project.instructions.take(4000)).put("created_at", project.createdAt)
            .put("archived_at", project.archivedAt.coerceAtLeast(0L)))
    }
    return array.toString()
}

private fun saveProjects(prefs: android.content.SharedPreferences, projects: List<ProjectRecord>, markDirty: Boolean = true) {
    val editor = prefs.edit().putString("projects", projectsToJson(projects))
    if (markDirty) editor.putLong("local_snapshot_saved_at", System.currentTimeMillis())
    editor.apply()
}

private fun jsonStringList(array: JSONArray?): List<String> = array?.let { value -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.take(20) }.orEmpty()

private fun loadMessages(prefs: android.content.SharedPreferences): List<ChatMessage> = runCatching {
    val array = JSONArray(prefs.getString("messages", "[]"))
    (0 until array.length()).map { i ->
        val item = array.getJSONObject(i)
        ChatMessage(item.getString("id"), item.getString("role"), item.getString("content"), jsonStringList(item.optJSONArray("versions")), item.optInt("active_version", 0))
    }
}.getOrDefault(emptyList())

private fun saveMessages(prefs: android.content.SharedPreferences, messages: List<ChatMessage>) {
    val array = JSONArray()
    messages.takeLast(200).forEach { msg ->
        array.put(JSONObject().put("id", msg.id).put("role", msg.role).put("content", msg.content).put("versions", JSONArray(msg.versions.take(20))).put("active_version", msg.activeVersion.coerceAtLeast(0)))
    }
    prefs.edit().putString("messages", array.toString()).apply()
}

private fun loadExpertTeams(prefs: android.content.SharedPreferences): List<ExpertTeam> = runCatching {
    val array = JSONArray(prefs.getString("expert_teams_v1", "[]"))
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val members = item.optJSONArray("members") ?: JSONArray()
        ExpertTeam(
            id = item.optString("id"),
            name = item.optString("name"),
            description = item.optString("description"),
            members = (0 until members.length()).map { members.getString(it) }.distinct()
        ).takeIf { it.id.isNotBlank() && it.name.isNotBlank() && it.members.size >= 2 }
    }
}.getOrDefault(emptyList())

private fun expertTeamsToJson(teams: List<ExpertTeam>): String =
    JSONArray().apply {
        teams.forEach { team ->
            put(JSONObject().put("id", team.id).put("name", team.name).put("description", team.description)
                .put("members", JSONArray(team.members)))
        }
    }.toString()

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

private fun readBackupText(context: Context, uri: Uri): String {
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
    val taskLogs: List<PhoneAgentTaskLogEntity>
)

internal suspend fun buildLocalBackup(context: Context, prefs: android.content.SharedPreferences, database: NestroDatabase): String {
    val dao = database.dao()
    val snapshot = database.withTransaction {
        val tasks = dao.allPhoneAgentTasks()
        LocalBackupDatabaseSnapshot(
            projects = dao.loadProjects(), conversations = dao.loadConversations(),
            documents = dao.allKnowledgeDocuments(), chunks = dao.allKnowledgeChunks(),
            memories = dao.allUserMemories(), tasks = tasks,
            taskLogs = tasks.flatMap { dao.allPhoneAgentTaskLogs(it.id) }
        )
    }
    val root = JSONObject()
    root.put("format", "nestro-local-backup")
    root.put("version", LocalBackupCompatibility.CURRENT_VERSION)
    root.put("created_at", System.currentTimeMillis())
    root.put("base_url", prefs.getString("base_url", "") ?: "")
    root.put("model", prefs.getString("model", "") ?: "")
    root.put("model_profiles_v1", prefs.getString("model_profiles_v1", "[]") ?: "[]")
    root.put("active_model_profile_id", prefs.getString("active_model_profile_id", "") ?: "")
    root.put("model_usage_v1", prefs.getString("model_usage_v1", "{}") ?: "{}")
    root.put("model_failover_enabled", prefs.getBoolean("model_failover_enabled", false))
    root.put("automatic_memory_enabled", prefs.getBoolean("automatic_memory_enabled", false))
    val phoneAudit = runCatching { JSONArray(prefs.getString("phone_audit", "[]") ?: "[]") }.getOrDefault(JSONArray())
    val auditSnapshot = JSONArray()
    for (index in (phoneAudit.length() - 100).coerceAtLeast(0) until phoneAudit.length()) {
        val item = phoneAudit.optJSONObject(index) ?: continue
        auditSnapshot.put(JSONObject().put("at", item.optLong("at", 0L)).put("action", item.optString("action").take(60)).put("detail", item.optString("detail").take(180)))
    }
    root.put("phone_audit_v1", auditSnapshot)
    root.put("messages", if (snapshot.conversations.isEmpty()) prefs.getString("messages", "[]") ?: "[]" else "[]")
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
    val roomProjects = snapshot.projects.map { ProjectRecord(it.id, it.name, it.instructions, it.createdAt, it.archivedAt) }
    val backupProjects = if (roomProjects.isEmpty()) loadProjects(prefs) else roomProjects
    root.put("projects", projectsToJson(backupProjects))
    root.put("active_project_id", prefs.getString("active_project_id", "default") ?: "default")
    val roomConversationRows = snapshot.conversations
    require(roomConversationRows.size <= 200) { "会话数量超过备份上限 200，请先清理旧会话" }
    val conversationPayloadChars = roomConversationRows.sumOf { row ->
        row.conversation.title.length.toLong() + row.messages.sumOf { message ->
            message.content.length.toLong() + message.versionsJson.length.toLong()
        }
    }
    require(conversationPayloadChars <= 3_500_000L) { "会话历史内容超过备份安全容量，请先清理旧会话" }
    roomConversationRows.forEach { row -> require(row.messages.size <= 500) { "单个会话消息超过备份上限 500" } }
    val conversationArray = JSONArray()
    roomConversationRows.forEach { row ->
        val messages = JSONArray()
        row.messages.sortedBy { it.position }.forEach { message ->
            val versions = runCatching { JSONArray(message.versionsJson) }.getOrDefault(JSONArray())
            messages.put(JSONObject().put("id", message.id).put("role", message.role).put("content", message.content)
                .put("versions", versions).put("active_version", message.activeVersion.coerceAtLeast(0)))
        }
        conversationArray.put(JSONObject().put("id", row.conversation.id).put("title", row.conversation.title.take(80))
            .put("updated_at", row.conversation.updatedAt).put("project_id", row.conversation.projectId).put("messages", messages))
    }
    root.put("conversations_v1", conversationArray)
    val activeId = prefs.getString("active_conversation_id", "") ?: ""
    root.put("active_conversation_id", activeId.takeIf { id -> (0 until conversationArray.length()).any { conversationArray.getJSONObject(it).optString("id") == id } } ?: conversationArray.optJSONObject(0)?.optString("id").orEmpty())
    val drafts = JSONObject()
    prefs.all.forEach { (key, value) -> if (key.startsWith("draft_") && value is String && value.isNotBlank()) drafts.put(key.removePrefix("draft_"), value.take(100_000)) }
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
            .put("createdAt", memory.createdAt).put("updatedAt", memory.updatedAt))
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
    root.put("artifacts_v1", app.nestro.data.ArtifactStore.backupAll(context, backupProjects.map { it.id }.toSet() + "default"))
    root.put("checksum_sha256", LocalBackupCompatibility.sha256(LocalBackupCompatibility.canonicalJson(root)))
    val encoded = root.toString(2)
    require(encoded.length <= 10_000_000) { "备份超过 10 MB，请减少历史数据或知识库文档后重试" }
    return encoded
}

internal suspend fun restoreLocalBackup(context: Context, prefs: android.content.SharedPreferences, raw: String, database: NestroDatabase) {
    require(raw.length <= 10_000_000) { "备份文件过大" }
    val root = JSONObject(raw)
    require(root.optString("format") == "nestro-local-backup") { "不是 Nestro 备份文件" }
    require(LocalBackupCompatibility.isSupported(root.optInt("version"))) { "暂不支持此备份版本" }
    require(LocalBackupCompatibility.verifyChecksum(root)) { "备份完整性校验失败，文件可能已损坏或被修改" }
    val messages = JSONArray(root.getString("messages"))
    require(messages.length() <= 200) { "对话记录数量超出限制" }
    val conversationsRaw = root.optString("conversations_v1", "[]")
    val conversations = JSONArray(conversationsRaw)
    require(conversations.length() <= 200) { "会话数量超出限制" }
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
    val workflowRaw = root.optString("workflows_v1", "[]")
    val workflowArray = JSONArray(workflowRaw)
    require(workflowArray.length() <= 100) { "工作流数量超出限制" }
    val restoredWorkflows = WorkflowDefinitionCodec.decode(workflowRaw)
    require(restoredWorkflows.size == workflowArray.length()) { "工作流备份包含无效定义" }
    restoredWorkflows.forEach { WorkflowDefinitionCodec.validate(it) }
    val workflowHistoryRaw = root.optString("workflow_runs_v1", "[]")
    val workflowHistoryArray = JSONArray(workflowHistoryRaw)
    require(workflowHistoryArray.length() <= 50) { "工作流运行记录数量超出限制" }
    val restoredWorkflowHistory = WorkflowRunHistoryCodec.decode(workflowHistoryRaw)
    require(restoredWorkflowHistory.size == workflowHistoryArray.length()) { "工作流运行记录包含无效数据" }
    val teamHistoryRaw = root.optString("expert_team_runs_v1", "[]")
    require(teamHistoryRaw.length<=500_000) { "专家团队运行记录过大" }
    val teamHistoryArray = JSONArray(teamHistoryRaw)
    require(teamHistoryArray.length()<=30) { "专家团队运行记录数量超出限制" }
    val restoredTeamHistory = ExpertTeamRunHistoryCodec.decode(teamHistoryRaw)
    require(restoredTeamHistory.size==teamHistoryArray.length()) { "专家团队运行记录包含无效数据" }
    val globalMemory = root.optString("global_memory_v1", "")
    require(globalMemory.length <= 12000) { "全局记忆超出限制" }
    val auditRows = root.optJSONArray("phone_audit_v1") ?: JSONArray()
    require(auditRows.length() <= 100) { "Phone Agent 安全审计记录数量超出限制" }
    val restoredPhoneAudit = JSONArray()
    for (index in 0 until auditRows.length()) {
        val item = auditRows.getJSONObject(index)
        val action = item.optString("action")
        val detail = item.optString("detail")
        require(action.length in 1..60 && detail.length <= 180) { "Phone Agent 审计记录字段无效" }
        restoredPhoneAudit.put(JSONObject().put("at", item.optLong("at", 0L)).put("action", action).put("detail", detail))
    }
    val memoryRows = root.optJSONArray("user_memories") ?: JSONArray()
    require(memoryRows.length() <= 1000) { "自动记忆数量超出限制" }
    val restoredProjects = when (val projectsValue = root.opt("projects")) {
        is JSONArray -> parseProjects(projectsValue)
        is String -> parseProjects(JSONArray(projectsValue))
        else -> parseProjects(null)
    }
    require(restoredProjects.size <= 100 && restoredProjects.map { it.id }.distinct().size == restoredProjects.size) { "项目空间数据无效" }
    val restoredProjectIds = (restoredProjects.map { it.id } + "default").toSet()
    val hasArtifactBackup = root.has("artifacts_v1")
    val restoredArtifactRows = if (hasArtifactBackup) app.nestro.data.ArtifactStore.validateBackup(root.optJSONArray("artifacts_v1") ?: JSONArray(), restoredProjectIds) else JSONArray()
    val conversationRows = root.optJSONArray("conversations_v1") ?: JSONArray()
    require(conversationRows.length() <= 200) { "会话数量超出限制" }
    val restoredConversations = (0 until conversationRows.length()).map { index ->
        val item = conversationRows.getJSONObject(index)
        val id = item.optString("id"); val projectId = item.optString("project_id", "default"); val title = item.optString("title", "新对话")
        require(id.isNotBlank() && id.length <= 120 && projectId in restoredProjectIds && title.length <= 80) { "会话标识或项目归属无效" }
        val rows = item.optJSONArray("messages") ?: JSONArray()
        require(rows.length() <= 500) { "单个会话消息数量超出限制" }
        val restoredMessages = (0 until rows.length()).map { messageIndex ->
            val message = rows.getJSONObject(messageIndex); val messageId = message.optString("id"); val role = message.optString("role"); val content = message.optString("content")
            val versions = message.optJSONArray("versions") ?: JSONArray()
            require(messageId.isNotBlank() && role in setOf("user", "assistant") && content.length <= 200_000 && versions.length() <= 20) { "会话消息字段无效" }
            val versionList = (0 until versions.length()).map { versions.getString(it) }
            require(versionList.all { it.length <= 200_000 }) { "回答版本内容过长" }
            val activeVersion = message.optInt("active_version", 0)
            require(activeVersion >= 0 && (versionList.isEmpty() || activeVersion < versionList.size)) { "回答版本索引无效" }
            MessageEntity(messageId, id, messageIndex, role, content, JSONArray(versionList).toString(), activeVersion)
        }
        ConversationEntity(id, title, item.optLong("updated_at", System.currentTimeMillis()), projectId) to restoredMessages
    }
    val completeRestoredConversations = if (restoredConversations.isNotEmpty()) restoredConversations else if (messages.length() > 0) {
        val legacyMessages = (0 until messages.length()).mapIndexed { index, messageIndex ->
            val item = messages.getJSONObject(messageIndex)
            MessageEntity(item.getString("id"), "legacy-restored", index, item.getString("role"), item.getString("content"),
                (item.optJSONArray("versions") ?: JSONArray()).toString(), item.optInt("active_version", 0))
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
    val draftObject = root.optJSONObject("conversation_drafts_v1") ?: JSONObject()
    require(draftObject.length() <= 200) { "草稿数量超出限制" }
    val restoredDrafts = linkedMapOf<String, String>()
    val draftKeys = draftObject.keys()
    while (draftKeys.hasNext()) {
        val id = draftKeys.next(); val value = draftObject.optString(id)
        require(completeRestoredConversations.any { it.first.id == id } && value.length <= 100_000) { "会话草稿引用无效或内容过长" }
        restoredDrafts[id] = value
    }
    val documentRows = root.optJSONArray("knowledge_documents_v1") ?: JSONArray()
    val chunkRows = root.optJSONArray("knowledge_chunks_v1") ?: JSONArray()
    require(documentRows.length() <= 500 && chunkRows.length() <= 50_000) { "知识库备份数量超出限制" }
    val restoredKnowledgeDocuments = (0 until documentRows.length()).map { index ->
        val item = documentRows.getJSONObject(index); val id = item.optString("id"); val projectId = item.optString("projectId")
        val name = item.optString("name"); val hash = item.optString("contentHash"); val size = item.optLong("sizeBytes", 0L)
        require(id.isNotBlank() && projectId in restoredProjectIds && name.isNotBlank() && name.length <= 180 && hash.matches(Regex("[a-fA-F0-9]{64}")) && size in 0..2_097_152) { "知识库文档元数据无效" }
        KnowledgeDocumentEntity(id, projectId, name, item.optString("mimeType", "text/plain").take(120), size, item.optLong("importedAt", System.currentTimeMillis()), hash.lowercase())
    }
    require(restoredKnowledgeDocuments.map { it.id }.distinct().size == restoredKnowledgeDocuments.size && restoredKnowledgeDocuments.map { it.contentHash }.distinct().size == restoredKnowledgeDocuments.size) { "知识库文档 ID 或哈希重复" }
    val docsById = restoredKnowledgeDocuments.associateBy { it.id }
    val restoredKnowledgeChunks = (0 until chunkRows.length()).map { index ->
        val item = chunkRows.getJSONObject(index); val id = item.optString("id"); val documentId = item.optString("documentId")
        val projectId = item.optString("projectId"); val position = item.optInt("position", -1); val content = item.optString("content")
        require(id.isNotBlank() && docsById[documentId]?.projectId == projectId && position >= 0 && content.isNotBlank() && content.length <= 6000) { "知识库文本片段无效或缺少所属文档" }
        KnowledgeChunkEntity(id, documentId, projectId, position, content)
    }
    require(restoredKnowledgeChunks.map { it.id }.distinct().size == restoredKnowledgeChunks.size) { "知识库片段 ID 重复" }
    val restoredMemories = (0 until memoryRows.length()).map { index ->
        val item = memoryRows.getJSONObject(index)
        val category = item.optString("category")
        val content = item.optString("content")
        val projectId = item.optString("projectId")
        val sourceConversationId = item.optString("sourceConversationId")
        val sourceMessageId = item.optString("sourceMessageId")
        require(item.optString("id").isNotBlank() && projectId in restoredProjectIds) { "记忆标识或项目归属无效" }
        require(conversationProjectById[sourceConversationId]?.let { it == projectId } != false) { "记忆引用了其他项目空间的会话" }
        require(messageProjectById[sourceMessageId]?.let { it == projectId } != false) { "记忆引用了其他项目空间的消息" }
        require(category in setOf("preference", "workflow", "project", "constraint")) { "记忆分类无效" }
        require(content.length in 8..180 && item.optString("fingerprint").isNotBlank() && UserMemoryStore.isSafeContent(content)) { "记忆内容无效或包含敏感信息" }
        val confidence = item.optDouble("confidence", 0.0)
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
            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = item.optLong("updatedAt", System.currentTimeMillis())
        )
    }
    val taskRows = root.optJSONArray("phone_agent_tasks") ?: JSONArray()
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
        val id = item.optString("id")
        val projectId = item.optString("projectId")
        val conversationId = item.optString("conversationId").take(120)
        val goal = item.optString("goal")
        val category = item.optString("category")
        val status = item.optString("status")
        val planJson = item.optString("planJson", "[]")
        val currentStep = item.optInt("currentStep", 0)
        require(id.isNotBlank() && projectId in restoredProjectIds && goal.length in 1..2000) { "Phone Agent 任务字段无效" }
        require(conversationProjectById[conversationId]?.let { it == projectId } != false) { "Phone Agent 任务引用了其他项目空间的会话" }
        require(category in validTaskCategories && status in validTaskStatuses) { "Phone Agent 任务状态或类型无效" }
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
            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = item.optLong("updatedAt", System.currentTimeMillis())
        )
    }
    val restoredTaskIds = restoredTasks.map { it.id }.toSet()
    require(restoredTaskIds.size == restoredTasks.size) { "Phone Agent 任务 ID 重复" }
    val taskLogRows = root.optJSONArray("phone_agent_task_logs") ?: JSONArray()
    require(taskLogRows.length() <= 5000) { "Phone Agent 审计记录数量超出限制" }
    val restoredTaskLogs = (0 until taskLogRows.length()).map { index ->
        val item = taskLogRows.getJSONObject(index)
        val taskId = item.optString("taskId")
        require(taskId in restoredTaskIds) { "任务审计记录缺少所属任务" }
        PhoneAgentTaskLogEntity(
            id = item.getString("id"),
            taskId = taskId,
            actionIndex = item.optInt("actionIndex", 0).coerceIn(0, 100),
            action = item.optString("action").take(60),
            success = item.optBoolean("success", false),
            detail = item.optString("detail").take(240),
            createdAt = item.optLong("createdAt", System.currentTimeMillis())
        )
    }
    for (i in 0 until messages.length()) {
        val item = messages.getJSONObject(i)
        require(item.optString("role") in setOf("user", "assistant")) { "消息角色无效" }
        require(item.has("content") && item.has("id") && item.optString("id").isNotBlank() && item.optString("content").length <= 200_000) { "消息字段缺失或内容过长" }
    }
    val experts = JSONArray(root.getString("experts"))
    require(experts.length() <= 100) { "专家数量超出限制" }
    for (i in 0 until experts.length()) require(experts.getString(i).length <= 40) { "专家名称过长" }
    val expertNames = (0 until experts.length()).map { experts.getString(it) }.toSet()
    require(expertNames.size == experts.length()) { "专家名称重复" }
    val prompts = JSONObject(root.getString("expert_prompts"))
    val profileRaw = root.optString("expert_profiles_v1", "{}")
    require(profileRaw.length <= 100_000) { "专家配置元数据过大" }
    val restoredExpertProfiles = ExpertProfileCodec.decode(profileRaw)
    require(profileRaw == "{}" || restoredExpertProfiles.isNotEmpty()) { "专家配置元数据格式无效" }
    require(restoredExpertProfiles.size <= 100 && restoredExpertProfiles.keys.all { it in expertNames }) { "专家配置元数据引用无效" }
    val restoredSelectedExpertRaw = root.optString("selected_expert", "通用助手")
    val revisionRaw = root.optString("expert_versions_v1", "{}")
    require(revisionRaw.length <= 500_000) { "专家版本历史过大" }
    val restoredExpertRevisions = ExpertRevisionCodec.decode(revisionRaw)
    require(revisionRaw == "{}" || restoredExpertRevisions.isNotEmpty()) { "专家版本历史格式无效" }
    require(restoredExpertRevisions.size <= 100 && restoredExpertRevisions.keys.all { it in expertNames }) { "专家版本历史引用无效" }
    val templateRaw = root.optString("expert_templates_v1", "[]")
    require(templateRaw.length <= 300_000) { "专家模板数据过大" }
    val restoredExpertTemplates = ExpertTemplateCodec.decode(templateRaw)
    require(templateRaw == "[]" || restoredExpertTemplates.isNotEmpty()) { "专家模板格式无效" }
    require(restoredExpertTemplates.size <= 100) { "专家模板数量超出限制" }
    val teamArray = JSONArray(root.optString("expert_teams_v1", "[]"))
    require(teamArray.length() <= 50) { "专家团队数量超出限制" }
    val cleanTeams = JSONArray()
    for (i in 0 until teamArray.length()) {
        val item = teamArray.getJSONObject(i)
        val members = item.optJSONArray("members") ?: JSONArray()
        val validMembers = (0 until members.length()).map { members.getString(it) }.filter { it in expertNames }.distinct().take(4)
        require(item.optString("id").isNotBlank() && item.optString("name").isNotBlank() && validMembers.size >= 2) { "专家团队配置无效" }
        cleanTeams.put(
            JSONObject().put("id", item.getString("id")).put("name", item.getString("name").take(40))
                .put("description", item.optString("description").take(300)).put("members", JSONArray(validMembers))
        )
    }
    val selectedTeam = root.optString("selected_team_id", "")
    val validSelectedTeam = selectedTeam.takeIf { id ->
        (0 until cleanTeams.length()).any { cleanTeams.getJSONObject(it).optString("id") == id }
    } ?: ""
    val skills = JSONObject(root.optString("skills", "{}"))
    require(skills.length() <= 100) { "技能数量超出限制" }
    val skillMetadata = JSONObject(root.optString("skill_metadata_v1", "{}"))
    require(skillMetadata.length() <= 100) { "技能元数据数量超出限制" }
    val disabledSkillArray = JSONArray(root.optString("disabled_skills_v1", "[]"))
    require(disabledSkillArray.length()<=100) { "停用技能数量超出限制" }
    val disabledSkillNames = (0 until disabledSkillArray.length()).map { disabledSkillArray.getString(it) }.toSet()
    val cleanSkillMetadata = JSONObject()
    val metadataKeys = skillMetadata.keys()
    while (metadataKeys.hasNext()) {
        val name = metadataKeys.next()
        val metadata = skillMetadata.optString(name)
        require(name.length <= 40 && metadata.length <= 4000) { "技能元数据无效" }
        runCatching { JSONObject(metadata) }.getOrElse { error("技能元数据格式无效") }
        cleanSkillMetadata.put(name, metadata)
    }
    val cleanSkills = JSONObject()
    val skillKeys = skills.keys()
    while (skillKeys.hasNext()) {
        val name = skillKeys.next()
        val prompt = skills.optString(name)
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
        val prompt = prompts.optString(name)
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
    val previousProfileMetadata = ModelProfileCatalogCodec.decode(prefs.getString("model_profiles_v1", "[]") ?: "[]")
    var profileKeysToClear = emptyList<String>()
    val editor = prefs.edit()
        .putString("messages", messages.toString())
        .putString("conversations_v1", normalizedConversationBackup.toString())
        .putString("active_conversation_id", root.optString("active_conversation_id", "").takeIf { id -> completeRestoredConversations.any { it.first.id == id } } ?: completeRestoredConversations.firstOrNull()?.first?.id.orEmpty())
        .putString("global_memory_v1", globalMemory)
        .putBoolean("automatic_memory_enabled", root.optBoolean("automatic_memory_enabled", false))
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
        .putString("projects", projectsToJson(restoredProjects))
        .putString("active_project_id", root.optString("active_project_id", "default").takeIf { id -> restoredProjects.any { it.id == id && it.archivedAt == 0L } } ?: "default")
        .putLong("local_snapshot_saved_at", restoreSyncTimestamp)
        .putLong("room_synced_at", restoreSyncTimestamp)
    if (root.has("base_url") && root.has("model")) {
        val restoredBaseUrl = root.optString("base_url")
        val restoredModel = root.optString("model")
        require(restoredBaseUrl.length <= 2048 && restoredModel.length <= 200) { "模型配置字段过长" }
        val restoredProfilesRaw = root.optString("model_profiles_v1", "[]")
        val restoredProfiles = ModelProfileCatalogCodec.decode(restoredProfilesRaw)
        require(restoredProfilesRaw.length <= 100_000 && (restoredProfilesRaw == "[]" || restoredProfiles.isNotEmpty())) { "模型配置清单无效" }
        profileKeysToClear = previousProfileMetadata.filter { old -> restoredProfiles.none { it.id == old.id && it.baseUrl == old.baseUrl && it.model == old.model } }.map { it.id }
        val restoredActiveProfileId = root.optString("active_model_profile_id", "")
            .takeIf { id -> restoredProfiles.any { it.id == id } }.orEmpty()
        val restoredModelUsageRaw = root.optString("model_usage_v1", "{}")
        val restoredModelUsage = ModelUsageStatsCodec.decode(restoredModelUsageRaw)
        require(restoredModelUsageRaw.length <= 100_000 && (restoredModelUsageRaw == "{}" || restoredModelUsage.isNotEmpty())) { "模型用量记录无效" }
        val restoredFailoverEnabled = root.optBoolean("model_failover_enabled", false)
        val previousBaseUrl = prefs.getString("base_url", "") ?: ""
        val previousModel = prefs.getString("model", "") ?: ""
        editor.putString("base_url", restoredBaseUrl).putString("model", restoredModel)
            .putString("model_profiles_v1", ModelProfileCatalogCodec.encode(restoredProfiles))
            .putString("active_model_profile_id", restoredActiveProfileId)
            .putString("model_usage_v1", ModelUsageStatsCodec.encode(restoredModelUsage))
            .putBoolean("model_failover_enabled", restoredFailoverEnabled)
        if (previousBaseUrl != restoredBaseUrl || previousModel != restoredModel) {
            editor.remove("api_key_enc")
        }
    }
    val dao = database.dao()
    database.withTransaction {
        dao.deleteAllConversations()
        dao.deleteAllKnowledgeDocuments()
        dao.deleteAllUserMemories()
        dao.deleteAllPhoneAgentTasks()
        dao.deleteAllProjects()
        if (restoredProjects.isNotEmpty()) dao.putProjects(restoredProjects.map { ProjectEntity(it.id, it.name, it.instructions, it.createdAt, it.archivedAt) })
        completeRestoredConversations.forEach { (conversation, rows) ->
            dao.putConversation(conversation)
            if (rows.isNotEmpty()) dao.putMessages(rows)
        }
        if (restoredKnowledgeDocuments.isNotEmpty()) dao.insertKnowledgeDocuments(restoredKnowledgeDocuments)
        if (restoredKnowledgeChunks.isNotEmpty()) dao.insertKnowledgeChunks(restoredKnowledgeChunks)
        restoredMemories.forEach { dao.putUserMemory(it) }
        restoredTasks.forEach { dao.putPhoneAgentTask(it) }
        restoredTaskLogs.forEach { dao.insertPhoneAgentTaskLog(it) }
        prefs.all.keys.filter { it.startsWith("draft_") }.forEach { editor.remove(it) }
        restoredDrafts.forEach { (id, value) -> editor.putString("draft_$id", value) }
        check(editor.commit()) { "本机设置写入失败，恢复未完成" }
    }
    if (hasArtifactBackup) app.nestro.data.ArtifactStore.restoreBackup(context, restoredArtifactRows, restoredProjectIds)
    profileKeysToClear.forEach { key -> runCatching { LocalVault.deleteProfile(context, key) } }
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
    var taskClarifications by remember { mutableStateOf(emptyMap<String, String>()) }
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
        val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted -> taskMessage = if (granted) "任务通知已开启" else "未授予通知权限，任务仍可运行但不会显示系统通知" }
        val notificationsAllowed = android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!notificationsAllowed) {
            OutlinedButton(onClick = { notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) {
                Text("开启后台任务通知")
            }
        }
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
                val canStartAgent = !canResume && task.category != PhoneAgentPlanner.Category.GENERAL.name && task.status in setOf(PhoneAgentTaskStore.STATUS_PLANNED, PhoneAgentTaskStore.STATUS_PAUSED, PhoneAgentTaskStore.STATUS_RUNNING, PhoneAgentTaskStore.STATUS_FAILED)
                Surface(color = Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(task.goal, color = Ink, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "状态：${task.status} · 进度：${task.currentStep} · ${task.category}",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (task.status in setOf(PhoneAgentTaskStore.STATUS_WAITING_USER, PhoneAgentTaskStore.STATUS_PAUSED)) {
                            OutlinedTextField(
                                value = taskClarifications[task.id] ?: "",
                                onValueChange = { taskClarifications = taskClarifications + (task.id to it) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("补充预算、规格或其他非敏感要求…") },
                                minLines = 2
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    val clarification = taskClarifications[task.id].orEmpty()
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { PhoneAgentTaskStore.appendClarification(database.dao(), task.id, clarification) }
                                    }
                                    taskMessage = result.fold({ "补充信息已保存，任务已重新规划" }, { "补充失败：${it.message ?: "内容无效"}" })
                                    if (result.isSuccess && PhoneAgentService.activeService?.hasValidGrant() == true) {
                                        PhoneAgentWorkScheduler.enqueue(context, task.id)
                                    }
                                    if (result.isSuccess) taskClarifications = taskClarifications - task.id
                                    refresh++
                                }
                            }) { Text("补充信息并重新规划", color = Mint) }
                        }
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
                                    PhoneAgentTaskStore.STATUS_WAITING_USER,
                                    PhoneAgentTaskStore.STATUS_FAILED
                                )) || canStartAgent
                            ) {
                                Button(
                                    onClick = {
                                        PhoneAgentWorkScheduler.enqueue(
                                            context,
                                            task.id,
                                            afterUserHandoff = task.status == PhoneAgentTaskStore.STATUS_WAITING_USER,
                                            allowRetryCurrentAction = task.status in setOf(PhoneAgentTaskStore.STATUS_RUNNING, PhoneAgentTaskStore.STATUS_FAILED)
                                        )
                                        taskMessage = "已提交后台任务，切换会话不会中断任务记录。"
                                    },
                                    enabled = serviceEnabled && (hasTaskGrant || hasSingleGrant),
                                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
                                ) { Text(when { canStartAgent -> "启动 Phone Agent"; task.status == PhoneAgentTaskStore.STATUS_WAITING_USER -> "我已处理，继续"; task.status == PhoneAgentTaskStore.STATUS_RUNNING -> "核对页面后继续"; task.status == PhoneAgentTaskStore.STATUS_FAILED -> "核对页面后重试"; else -> "继续任务" }) }
                            }
                            if (task.status !in setOf(PhoneAgentTaskStore.STATUS_COMPLETED, PhoneAgentTaskStore.STATUS_CANCELLED)) {
                                TextButton(onClick = {
                                    PhoneAgentWorkScheduler.cancel(context, task.id, stopCurrent = task.status == PhoneAgentTaskStore.STATUS_RUNNING)
                                    PhoneAgentNotifications.cancel(context, task.id)
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