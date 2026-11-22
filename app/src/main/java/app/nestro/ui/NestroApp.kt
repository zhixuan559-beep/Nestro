package app.nestro.ui

import app.nestro.NestroApplication
import app.nestro.model.ModelRegistry
import app.nestro.mcp.McpToolCenterPage
import app.nestro.git.GitWorkbenchPage
import app.nestro.coworkbench.AiCoworkbenchPage
import app.nestro.model.ModelRegistryCodec
import app.nestro.model.ModelRequestConfigurationSelector
import app.nestro.data.runCatchingCancellable
import app.nestro.data.ModelConfig
import app.nestro.data.ChatMessage
import app.nestro.data.allVersions
import app.nestro.data.ChatRequestExecutionRequest
import app.nestro.data.ChatResponseStatus
import app.nestro.data.isCreationIntent
import app.nestro.data.ConversationRecord
import app.nestro.data.ConversationPreferencesStore
import app.nestro.data.ProjectRecord
import app.nestro.data.ProjectPreferencesStore
import app.nestro.data.ConversationListItem
import app.nestro.data.ProjectSpaceRules
import app.nestro.data.ProjectResourceScopeStore
import app.nestro.data.ProjectAccessControlStore
import app.nestro.data.ProjectResourceKind
import app.nestro.data.ProjectMemorySettingsStore
import app.nestro.data.ArtifactStore
import app.nestro.calendar.CalendarPage
import app.nestro.media.MediaProjectStore
import app.nestro.media.MediaEditorPage
import app.nestro.data.KnowledgeDocumentEntity
import app.nestro.data.KnowledgeChunkEntity
import app.nestro.data.ConversationRecallSource

import app.nestro.phone.PhoneAgentPlanner
import app.nestro.phone.PhoneAgentExecutor
import app.nestro.phone.PhoneAgentNotifications
import app.nestro.data.PhoneAgentTaskEntity
import app.nestro.data.PhoneAgentTaskLogEntity
import app.nestro.data.SkillBundle
import app.nestro.data.SkillBundleParser
import app.nestro.data.ExpertBundle
import app.nestro.data.ExpertBundleParser
import app.nestro.data.LocalBackupCompatibility
import app.nestro.data.ModelProfileCatalogCodec
import app.nestro.data.ModelProfileLifecycle
import app.nestro.data.ChatRequestStateStore
import app.nestro.data.ModelUsageStats
import app.nestro.data.ModelUsageStatsCodec
import app.nestro.data.ChatRequestPolicy
import app.nestro.data.ChatContextRetrievalPolicy
import app.nestro.data.ChatExecutionAuthorization
import app.nestro.data.ChatEndpointPolicy
import app.nestro.data.ChatHttpTransport
import app.nestro.data.ChatHistoryItem
import app.nestro.data.ExpertCatalogRules
import app.nestro.data.ExpertProfile
import app.nestro.data.ExpertProfileCodec
import app.nestro.data.ExpertRevision
import app.nestro.data.ExpertRevisionCodec
import app.nestro.data.ExpertTemplate
import app.nestro.data.ExpertTemplateCatalog
import app.nestro.data.ExpertTemplateBundleCodec
import app.nestro.data.ExpertTemplateCodec
import app.nestro.data.ExpertTeamBundle
import app.nestro.data.ExpertTeamMemberBundle
import app.nestro.data.ExpertTeamBundleParser
import app.nestro.data.ExpertTeamDefaults
import app.nestro.data.ExpertTeamMemberTask
import app.nestro.data.ExpertProjectPlan
import app.nestro.data.ExpertWorkPackage
import app.nestro.data.ExpertTeamRunResult
import app.nestro.data.ExpertTeamRunHistoryCodec
import app.nestro.data.WorkflowDefinition
import app.nestro.data.WorkflowStep
import app.nestro.data.WorkflowStepKind
import app.nestro.data.WorkflowDefinitionCodec
import app.nestro.data.WorkflowRunHistoryCodec
import app.nestro.data.WorkflowRunRecord
import app.nestro.data.WorkflowExecutionException
import app.nestro.data.UserMemoryEntity
import app.nestro.data.UserMemoryExportCodec
import app.nestro.data.KnowledgeEmbeddingModelManager
import app.nestro.ui.VoiceInputButton

import android.content.Context
import android.net.Uri
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.zIndex
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale



internal fun adaptiveDrawerWidthDp(screenWidthDp: Int): Int = when {
    screenWidthDp < 360 -> (screenWidthDp - 56).coerceIn(224, 248)
    screenWidthDp < 600 -> (screenWidthDp - 64).coerceIn(256, 280)
    screenWidthDp < 840 -> (screenWidthDp * 40 / 100).coerceIn(240, 320)
    else -> (screenWidthDp * 30 / 100).coerceIn(320, 380)
}

internal fun adaptiveContentPaddingDp(screenWidthDp: Int): Int = when {
    screenWidthDp < 360 -> 12
    screenWidthDp < 600 -> 16
    screenWidthDp < 840 -> 24
    else -> 32
}

@Composable
fun NestroApp(initialPage: String = "home") {
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val adaptiveDrawerWidth = adaptiveDrawerWidthDp(screenWidthDp).dp
    val adaptiveContentPadding = adaptiveContentPaddingDp(screenWidthDp).dp
    val scope = rememberCoroutineScope()
    val appRuntime = context.applicationContext as NestroApplication
    val prefs = remember { context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE) }
    var databaseReady by remember { mutableStateOf(false) }
    val persistedConversationVersions = remember { mutableStateMapOf<String, Int>() }
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    var pendingOpenMediaAssetId by remember { mutableStateOf("") }
    var showModelDialog by remember { mutableStateOf(false) }
    var showDrawer by remember { mutableStateOf(false) }
    val modelRepository = remember { appRuntime.modelConfigurationRepository }
    val modelUiState = remember(prefs, modelRepository) {
        ModelManagementUiState(prefs, modelRepository)
    }
    var config by modelUiState.config
    var taskModeEnabled by remember(prefs) { mutableStateOf(prefs.getBoolean("chat_task_mode_enabled", false)) }
    var reasoningEffort by remember(prefs) { mutableStateOf(prefs.getString("reasoning_effort_v1", "low") ?: "low") }
    var showReasoningMenu by remember { mutableStateOf(false) }
    var modelProfiles by modelUiState.modelProfiles
    var modelRegistry by modelUiState.modelRegistry
    var activeModelProfileId by modelUiState.activeModelProfileId
    var activeRegistryModelId by modelUiState.activeRegistryModelId
    var modelFailoverEnabled by modelUiState.modelFailoverEnabled
    var modelUsage by modelUiState.modelUsage
    val conversationUiState = remember { ConversationUiState() }
    var messages by conversationUiState.messages
    var conversations by conversationUiState.conversations
    val projectSpaceUiState = remember { ProjectSpaceUiState() }
    var projects by projectSpaceUiState.projects
    var projectStorageReadOnly by remember { mutableStateOf(false) }
    val initialProjectResourceScopes = remember(prefs) { runCatching { ProjectResourceScopeStore.load(prefs) } }
    var projectResourceScopes by remember(prefs) {
        mutableStateOf(initialProjectResourceScopes.getOrDefault(emptyMap()))
    }
    var projectResourceScopesReadOnly by remember(prefs) {
        mutableStateOf(initialProjectResourceScopes.isFailure)
    }
    var activeProjectId by projectSpaceUiState.activeProjectId
    fun readableSourcesFor(targetProjectId: String): Set<String> {
        val eligible = projects.filter { it.archivedAt == 0L && it.id != targetProjectId }.map { it.id }.toSet()
        return ProjectAccessControlStore.grantedReadableSourceProjects(prefs, targetProjectId).intersect(eligible)
    }
    fun writableSourcesFor(targetProjectId: String): Set<String> {
        val eligible = projects.filter { it.archivedAt == 0L && it.id != targetProjectId }.map { it.id }.toSet()
        return ProjectAccessControlStore.grantedWritableSourceProjects(prefs, targetProjectId).intersect(eligible)
    }
    var readableProjectIds by remember(activeProjectId, projects) {
        mutableStateOf(readableSourcesFor(activeProjectId))
    }
    var writableProjectIds by remember(activeProjectId, projects) {
        mutableStateOf(writableSourcesFor(activeProjectId))
    }
    var pendingAccessSourceProjectId by remember { mutableStateOf("") }
    var showProjectAccessDialog by remember { mutableStateOf(false) }
    var deletingProjectId by projectSpaceUiState.deletingProjectId
    var showProjectDialog by projectSpaceUiState.showProjectDialog
    var projectDraftName by projectSpaceUiState.projectDraftName
    var projectDraftInstructions by projectSpaceUiState.projectDraftInstructions
    var editingProjectId by projectSpaceUiState.editingProjectId
    var activeConversationId by conversationUiState.activeConversationId
    var conversationSearch by conversationUiState.conversationSearch
    var conversationPageItems by conversationUiState.conversationPageItems
    var conversationPageLoading by conversationUiState.conversationPageLoading
    var conversationPageHasMore by conversationUiState.conversationPageHasMore
    var conversationPageRefreshKey by conversationUiState.conversationPageRefreshKey
    var showRenameConversation by conversationUiState.showRenameConversation
    var showEditMessageDialog by conversationUiState.showEditMessageDialog
    var editMessageId by conversationUiState.editMessageId
    var editMessageDraft by conversationUiState.editMessageDraft
    var renameConversationId by conversationUiState.renameConversationId
    var renameConversationDraft by conversationUiState.renameConversationDraft
    val memoryUiState = remember(prefs) { MemoryUiState(prefs) }
    var globalMemory by memoryUiState.globalMemory
    var memoryDraft by memoryUiState.memoryDraft
    var automaticMemories by memoryUiState.automaticMemories
    var automaticMemoryEnabled by memoryUiState.automaticMemoryEnabled
    var memoryEdits by memoryUiState.memoryEdits
    var memoryStatus by memoryUiState.memoryStatus
    var showClearAutomaticMemoryConfirm by remember { mutableStateOf(false) }
    var embeddingModelInstalled by remember { mutableStateOf(false) }
    var embeddingModelInstalling by remember { mutableStateOf(false) }
    var embeddingModelStatus by remember { mutableStateOf("正在检查本地语义模型…") }
    LaunchedEffect(context) {
        val installed = withContext(Dispatchers.IO) {
            KnowledgeEmbeddingModelManager.isInstalled(context)
        }
        embeddingModelInstalled = installed
        embeddingModelStatus = if (installed) "本地语义模型已安装，语义检索可用。" else "尚未安装；当前使用关键词检索。"
    }
    val chatExecutionUiState = remember { ChatExecutionUiState() }
    var draft by chatExecutionUiState.draft
    var busy by chatExecutionUiState.busy
    var pendingCoworkbenchPrompt by remember { mutableStateOf("") }
    var coworkbenchGeneratedPatch by remember { mutableStateOf("") }
    var stopRequested by chatExecutionUiState.stopRequested
    var activeRequestId by chatExecutionUiState.activeRequestId
    var activeRequestConversationId by chatExecutionUiState.activeRequestConversationId
    var reuseLastUserOnSend by chatExecutionUiState.reuseLastUserOnSend
    var pendingAssistantRevision by chatExecutionUiState.pendingAssistantRevision
    var status by chatExecutionUiState.status
    val assistantRoleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        status = if (app.nestro.phone.AndroidAssistantRoleSupport.isHeld(context)) {
            "Nestro 已成为系统默认助手入口。"
        } else {
            "系统助手设置未更改。"
        }
    }
    fun canWriteProjectResource(kind: ProjectResourceKind, resourceId: String): Boolean {
        if (projectResourceScopesReadOnly) {
            status = "项目资源范围数据校验失败，已阻止修改。"
            return false
        }
        val owners = ProjectResourceScopeStore.ownerProjectIds(projectResourceScopes, kind, resourceId)
        val writableSources = owners.orEmpty().filter { source ->
            source != "*" && source != activeProjectId && ProjectAccessControlStore.authorize(prefs, source, activeProjectId, ProjectAccessControlStore.Access.WRITE)
        }.toSet()
        val allowed = ProjectResourceScopeStore.canWriteFromProject(projectResourceScopes, kind, resourceId, activeProjectId, writableSources)
        if (!allowed) status = "当前项目只有读取权限，未获得资源所属项目的写入授权。"
        return allowed
    }
    val updateProjectResourceScope: (ProjectResourceKind, String, Boolean) -> Boolean = { kind, resourceId, makeGlobal ->
        if (!canWriteProjectResource(kind, resourceId)) {
            false
        } else {
            runCatching {
                val next = if (makeGlobal) {
                    ProjectResourceScopeStore.setGlobal(projectResourceScopes, kind, resourceId)
                } else {
                    ProjectResourceScopeStore.setProjectScope(projectResourceScopes, kind, resourceId, setOf(activeProjectId))
                }
                ProjectResourceScopeStore.save(prefs, next)
                projectResourceScopes = next
                status = if (makeGlobal) "资源已设为全局共享" else "资源已限定在当前项目"
                true
            }.getOrElse {
                status = "资源范围更新失败：" + (it.message ?: "数据无效")
                false
            }
        }
    }
    var recallSources by chatExecutionUiState.recallSources
    var showRecallDialog by chatExecutionUiState.showRecallDialog
    val expertCatalogUiState = remember(prefs) { ExpertCatalogUiState(prefs) }
    var experts by expertCatalogUiState.experts
    var expertPrompts by expertCatalogUiState.expertPrompts
    var expertProfiles by expertCatalogUiState.expertProfiles
    var expertRevisions by expertCatalogUiState.expertRevisions
    var expertTemplates by expertCatalogUiState.expertTemplates
    val expertAssetsUiState = remember(prefs) {
        ExpertAssetsUiState(loadSkills(prefs), loadSkillMetadata(prefs), loadDisabledSkills(prefs))
    }
    var showExpertTemplatesDialog by expertAssetsUiState.showExpertTemplatesDialog
    var templateNameDraft by expertAssetsUiState.templateNameDraft
    var templateSearchDraft by expertAssetsUiState.templateSearchDraft
    var templateCategoryFilter by expertAssetsUiState.templateCategoryFilter
    var templateStatus by expertAssetsUiState.templateStatus
    var templateExportJson by expertAssetsUiState.templateExportJson
    var pendingTemplateBundle by expertAssetsUiState.pendingTemplateBundle
    var editingTemplate by expertAssetsUiState.editingTemplate
    var templateToDelete by expertAssetsUiState.templateToDelete
    var templateEditName by expertAssetsUiState.templateEditName
    var templateEditDescription by expertAssetsUiState.templateEditDescription
    var templateEditCategory by expertAssetsUiState.templateEditCategory
    var templateEditPrompt by expertAssetsUiState.templateEditPrompt
    var templateEditorStatus by expertAssetsUiState.templateEditorStatus
    var showExpertHistoryDialog by expertAssetsUiState.showExpertHistoryDialog
    var historyExpertName by expertAssetsUiState.historyExpertName
    var historyCompareVersion by expertAssetsUiState.historyCompareVersion
    var skills by expertAssetsUiState.skills
    var skillMetadata by expertAssetsUiState.skillMetadata
    var disabledSkills by expertAssetsUiState.disabledSkills
    var editingSkillName by expertAssetsUiState.editingSkillName
    var skillDialogError by expertAssetsUiState.skillDialogError
    var pendingSkillBundle by expertAssetsUiState.pendingSkillBundle
    var showSkillImportDialog by expertAssetsUiState.showSkillImportDialog
    var skillImportStatus by expertAssetsUiState.skillImportStatus
    var skillExportStatus by expertAssetsUiState.skillExportStatus
    var skillExportJson by expertAssetsUiState.skillExportJson
    var selectedSkill by expertAssetsUiState.selectedSkill
    var skillDraftName by expertAssetsUiState.skillDraftName
    var skillDraftPrompt by expertAssetsUiState.skillDraftPrompt
    var showSkillDialog by expertAssetsUiState.showSkillDialog
    var selectedExpert by expertCatalogUiState.selectedExpert
    val expertWorkspaceUiState = remember(prefs) { ExpertWorkspaceUiState(prefs) }
    var expertDraft by expertWorkspaceUiState.expertDraft
    var editingExpertName by expertWorkspaceUiState.editingExpertName
    var expertPromptDraft by expertWorkspaceUiState.expertPromptDraft
    var expertGroupDraft by expertWorkspaceUiState.expertGroupDraft
    var expertAvatarDraft by expertWorkspaceUiState.expertAvatarDraft
    var expertEnabledDraft by expertWorkspaceUiState.expertEnabledDraft
    var expertSkillDraft by expertWorkspaceUiState.expertSkillDraft
    var expertTemperatureDraft by expertWorkspaceUiState.expertTemperatureDraft
    var expertMaxTokensDraft by expertWorkspaceUiState.expertMaxTokensDraft
    var expertDescriptionDraft by expertWorkspaceUiState.expertDescriptionDraft
    var showExpertDialog by expertWorkspaceUiState.showExpertDialog
    var expertDialogError by expertWorkspaceUiState.expertDialogError
    var expertTeams by expertWorkspaceUiState.expertTeams
    var selectedTeamId by expertWorkspaceUiState.selectedTeamId
    var manualExpertSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showTeamDialog by expertWorkspaceUiState.showTeamDialog
    var editingTeamId by expertWorkspaceUiState.editingTeamId
    var teamDraftName by expertWorkspaceUiState.teamDraftName
    var teamDraftDescription by expertWorkspaceUiState.teamDraftDescription
    var teamDraftMembers by expertWorkspaceUiState.teamDraftMembers
    var pendingExpertBundle by expertWorkspaceUiState.pendingExpertBundle
    var expertImportStatus by expertWorkspaceUiState.expertImportStatus
    var expertExportStatus by expertWorkspaceUiState.expertExportStatus
    var expertExportJson by expertWorkspaceUiState.expertExportJson
    var pendingTeamBundle by expertWorkspaceUiState.pendingTeamBundle
    var teamImportStatus by expertWorkspaceUiState.teamImportStatus
    var teamExportStatus by expertWorkspaceUiState.teamExportStatus
    var teamExportJson by expertWorkspaceUiState.teamExportJson
    var teamRunStatus by expertWorkspaceUiState.teamRunStatus
    var expertTeamRunHistory by expertWorkspaceUiState.expertTeamRunHistory
    var expertTeamEvents by remember(prefs, activeProjectId) {
        mutableStateOf(
            app.nestro.data.ExpertTeamCollaborationEventCodec.decode(
                prefs.getString("expert_team_events_v1", "[]") ?: "[]"
            ).filter { it.projectId == activeProjectId }.takeLast(100)
        )
    }
    var teamMemberStatuses by expertWorkspaceUiState.teamMemberStatuses
    val workflowUiState = remember(prefs) { WorkflowUiState(prefs) }
    var workflowHistory by workflowUiState.workflowHistory
    var workflows by workflowUiState.workflows
    var showWorkflowDialog by workflowUiState.showWorkflowDialog
    var editingWorkflowId by workflowUiState.editingWorkflowId
    var workflowDraftName by workflowUiState.workflowDraftName
    var workflowDraftDescription by workflowUiState.workflowDraftDescription
    var workflowDraftSteps by workflowUiState.workflowDraftSteps
    var workflowTargetPickerIndex by workflowUiState.workflowTargetPickerIndex
    var showWorkflowTargetPicker by workflowUiState.showWorkflowTargetPicker
    var workflowInputDraft by workflowUiState.workflowInputDraft
    var workflowRunStatus by workflowUiState.workflowRunStatus
    var runningWorkflowId by workflowUiState.runningWorkflowId
    var workflowPaused by workflowUiState.workflowPaused
    var workflowPauseGate by workflowUiState.workflowPauseGate
    var workflowApprovalPrompt by workflowUiState.workflowApprovalPrompt
    val dataTransferUiState = remember { DataTransferUiState() }
    var backupStatus by dataTransferUiState.backupStatus
    var pendingBackupUri by dataTransferUiState.pendingBackupUri
    var showBackupRestoreConfirm by dataTransferUiState.showBackupRestoreConfirm
    var projectPackageExportProjectId by remember { mutableStateOf("") }
    var pendingProjectPackageUri by remember { mutableStateOf<Uri?>(null) }
    var showProjectPackageImportConfirm by remember { mutableStateOf(false) }
    var projectPackageStatus by remember { mutableStateOf("") }
    var voiceStatus by dataTransferUiState.voiceStatus
    fun canWriteExpertDependents(expertName: String): Boolean {
        val teamsAllowed = expertTeams.filter { expertName in it.members }
            .all { canWriteProjectResource(ProjectResourceKind.TEAM, it.id) }
        val workflowsAllowed = workflows.filter { workflow ->
            workflow.steps.any { it.kind == WorkflowStepKind.EXPERT && it.targetId.ifBlank { it.title } == expertName }
        }.all { canWriteProjectResource(ProjectResourceKind.WORKFLOW, it.id) }
        return teamsAllowed && workflowsAllowed
    }
    fun canWriteSkillDependents(skillName: String): Boolean {
        val expertsAllowed = expertProfiles.filterValues { skillName in it.skillNames }.keys
            .all { canWriteProjectResource(ProjectResourceKind.EXPERT, it) }
        val workflowsAllowed = workflows.filter { workflow ->
            workflow.steps.any { it.kind == WorkflowStepKind.SKILL && it.targetId.ifBlank { it.title } == skillName }
        }.all { canWriteProjectResource(ProjectResourceKind.WORKFLOW, it.id) }
        val owners = ProjectResourceScopeStore.ownerProjectIds(projectResourceScopes, ProjectResourceKind.SKILL, skillName)
        val templatesAllowed = owners == null || "*" in owners || expertTemplates.none { skillName in it.profile.skillNames }
        if (!templatesAllowed) status = "该技能被全局专家模板引用，不能在项目空间内单独重命名或删除。"
        return expertsAllowed && workflowsAllowed && templatesAllowed
    }
    val chatListState = rememberLazyListState()
    val conversationHistoryListState = rememberLazyListState()
    var initialChatPositionRestored by remember { mutableStateOf(false) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            backupStatus = "正在导出本机数据…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val json = appRuntime.localBackupRepository.createBackup(prefs)
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
    val exportProjectPackageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null && projectPackageExportProjectId.isNotBlank()) {
            val exportProjectId = projectPackageExportProjectId
            val availableProjectIds = projects.filter { it.archivedAt == 0L }.map { it.id }.toSet()
            projectPackageStatus = "正在导出项目包…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val readable = ProjectAccessControlStore.readableSourceProjects(prefs, exportProjectId, availableProjectIds)
                        val json = appRuntime.localBackupRepository.createProjectPackage(prefs, exportProjectId, readable)
                        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                            ?: error("无法创建项目包文件")
                    }
                }
                projectPackageStatus = result.fold({ "项目包已导出" }, { "导出失败：" + (it.message ?: "未知错误") })
            }
        }
    }
    val importProjectPackageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingProjectPackageUri = uri
            showProjectPackageImportConfirm = true
        }
    }
    val performBackupRestore: (Uri) -> Unit = { uri ->
        backupStatus = "正在恢复本机数据…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = appRuntime.localBackupRepository.readText(uri)
                    appRuntime.localBackupRepository.restoreBackup(prefs, raw)
                }
            }
            if (result.isSuccess) {
                automaticMemoryEnabled = prefs.getBoolean("automatic_memory_enabled", false)
                // Re-resolve below after the restored active project ID is applied.
                reasoningEffort = prefs.getString("reasoning_effort_v1", "low") ?: "low"
                modelUiState.initialize()
                persistedConversationVersions.clear()
                conversations = ConversationPreferencesStore.loadConversations(prefs)
                projects = ProjectPreferencesStore.loadProjects(prefs)
                projectStorageReadOnly = false
                projectResourceScopes = ProjectResourceScopeStore.load(prefs)
                projectResourceScopesReadOnly = false
                activeProjectId = (prefs.getString("active_project_id", "default") ?: "default").takeIf { id -> projects.any { it.id == id && it.archivedAt == 0L } } ?: "default"
                automaticMemoryEnabled = ProjectMemorySettingsStore.isEnabled(prefs, activeProjectId)
                activeConversationId = (prefs.getString("active_conversation_id", "") ?: "").takeIf { id -> conversations.any { it.id == id } } ?: conversations.firstOrNull()?.id.orEmpty()
                messages = conversations.firstOrNull { it.id == activeConversationId }?.messages ?: ConversationPreferencesStore.loadMessages(prefs)
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
                automaticMemories = withContext(Dispatchers.IO) { appRuntime.userMemoryRepository.list(activeProjectId) }
            }
            backupStatus = result.fold({ "恢复完成。备份不含 API Key；仅保留本机地址与型号匹配的密钥。" }, { "恢复失败：${it.message ?: "文件格式无效"}" })
        }
    }

    val performProjectPackageImport: (Uri) -> Unit = { uri ->
        projectPackageStatus = "正在导入项目包…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = appRuntime.localBackupRepository.readText(uri)
                    appRuntime.localBackupRepository.importProjectPackage(prefs, raw)
                }
            }
            if (result.isSuccess) {
                val importedProjectId = result.getOrThrow()
                persistedConversationVersions.clear()
                conversations = ConversationPreferencesStore.loadConversations(prefs)
                projects = ProjectPreferencesStore.loadProjects(prefs)
                projectStorageReadOnly = false
                projectResourceScopes = ProjectResourceScopeStore.load(prefs)
                projectResourceScopesReadOnly = false
                activeProjectId = importedProjectId
                prefs.edit().putLong("project_last_opened_$importedProjectId", System.currentTimeMillis()).apply()
                readableProjectIds = readableSourcesFor(activeProjectId)
                writableProjectIds = writableSourcesFor(activeProjectId)
                automaticMemoryEnabled = ProjectMemorySettingsStore.isEnabled(prefs, activeProjectId)
                activeConversationId = (prefs.getString("active_conversation_id", "") ?: "")
                    .takeIf { id -> conversations.any { it.id == id } }
                    ?: conversations.firstOrNull { it.projectId == importedProjectId }?.id.orEmpty()
                messages = if (activeConversationId.isBlank()) emptyList() else withContext(Dispatchers.IO) {
                    appRuntime.conversationPersistenceService.loadMessages(activeConversationId).orEmpty()
                }
                conversations = conversations.map { row ->
                    if (row.id == activeConversationId) row.copy(messages = messages, messagesLoaded = true) else row
                }
                draft = prefs.getString("draft_" + activeConversationId, "") ?: ""
                experts = loadExperts(prefs)
                expertPrompts = loadExpertPrompts(prefs)
                expertProfiles = ExpertProfileCodec.decode(prefs.getString("expert_profiles_v1", "{}") ?: "{}")
                expertRevisions = ExpertRevisionCodec.decode(prefs.getString("expert_versions_v1", "{}") ?: "{}")
                expertTemplates = ExpertTemplateCodec.decode(prefs.getString("expert_templates_v1", "[]") ?: "[]")
                selectedExpert = ExpertCatalogRules.resolveSelection(prefs.getString("selected_expert", "通用助手") ?: "通用助手", experts, expertProfiles)
                expertTeams = loadExpertTeams(prefs)
                selectedTeamId = (prefs.getString("selected_team_id", "") ?: "").takeIf { id -> expertTeams.any { it.id == id } } ?: ""
                skills = loadSkills(prefs)
                skillMetadata = loadSkillMetadata(prefs)
                disabledSkills = loadDisabledSkills(prefs).intersect(skills.keys)
                selectedSkill = ""
                workflows = WorkflowDefinitionCodec.decode(prefs.getString("workflows_v1", "[]") ?: "[]")
                workflowHistory = WorkflowRunHistoryCodec.decode(prefs.getString("workflow_runs_v1", "[]") ?: "[]")
                expertTeamRunHistory = ExpertTeamRunHistoryCodec.decode(prefs.getString("expert_team_runs_v1", "[]") ?: "[]")
                automaticMemories = withContext(Dispatchers.IO) { appRuntime.userMemoryRepository.list(activeProjectId) }
                page = "spaces"
            }
            projectPackageStatus = result.fold(
                { "项目包已导入：" + (projects.firstOrNull { it.id == activeProjectId }?.name ?: "新项目") },
                { "导入失败：" + (it.message ?: "项目包格式无效") }
            )
        }
    }

    val importMemoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            memoryStatus = "正在导入单条记忆…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 32_768) { "记忆文件超过 32 KB，拒绝导入" }
                            output.write(buffer, 0, count)
                        }
                        String(output.toByteArray(), Charsets.UTF_8)
                    } ?: error("无法读取记忆文件")
                    appRuntime.userMemoryRepository.importOne(activeProjectId, raw)
                }
            }
            if (result.isSuccess) {
                automaticMemories = withContext(Dispatchers.IO) { appRuntime.userMemoryRepository.list(activeProjectId) }
                memoryStatus = if (result.getOrThrow()) "单条记忆已导入当前项目。" else "当前项目已存在相同记忆，未重复导入。"
            } else {
                memoryStatus = "导入失败：${result.exceptionOrNull()?.message ?: "文件格式无效"}"
            }
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

    val importTemplateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    var totalBytes = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        require(totalBytes <= 300_000) { "模板文件超过 300 KB" }
                        output.write(buffer, 0, count)
                    }
                    String(output.toByteArray(), Charsets.UTF_8)
                } ?: error("无法读取模板文件")
                ExpertTemplateBundleCodec.decode(raw)
            } }
            result.fold({ pendingTemplateBundle = it; templateStatus = "" }, { templateStatus = "模板导入失败：" + (it.message ?: "文件格式无效") })
        }
    }
    val exportTemplateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) templateStatus = "已取消模板导出。"
        else scope.launch {
            val content = templateExportJson
            val result = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    ?: error("无法创建模板文件")
            } }
            templateStatus = result.fold({ "专家模板已导出。" }, { "模板导出失败：" + (it.message ?: "未知错误") })
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

    MemoryPageLoadEffect(
        page = page,
        activeProjectId = activeProjectId,
        loadMemories = { projectId ->
            withContext(Dispatchers.IO) {
                runCatchingCancellable { appRuntime.userMemoryRepository.list(projectId) }.getOrDefault(emptyList())
            }
        },
        onMemoriesLoaded = { automaticMemories = it },
        onResetEdits = { memoryEdits = emptyMap() }
    )

    LaunchedEffect(activeConversationId) {
        recallSources = emptyList()
        draft = if (activeConversationId.isBlank()) "" else prefs.getString("draft_$activeConversationId", "") ?: ""
    }

    ConversationPageLoadEffect(
        page = page,
        activeProjectId = activeProjectId,
        query = conversationSearch,
        refreshKey = conversationPageRefreshKey,
        listState = conversationHistoryListState,
        onLoadingChanged = { conversationPageLoading = it },
        onItemsLoaded = { conversationPageItems = it },
        onHasMoreChanged = { conversationPageHasMore = it },
        onLoadFailure = { status = "会话历史加载失败：${it.message ?: "未知错误"}" },
        loadPage = { projectId, query, limit, offset ->
            withContext(Dispatchers.IO) {
                appRuntime.conversationPersistenceService.loadConversationPage(
                    projectId = projectId,
                    query = query,
                    limit = limit,
                    offset = offset
                )
            }
        }
    )

    LaunchedEffect(Unit) {
        try {
            appRuntime.ensureBackupRestoreRecovered()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            status = "检测到未完成的备份恢复，自动回滚失败：" + (error.message ?: "未知错误")
            return@LaunchedEffect
        }
        modelUiState.initialize()
        val startup = appRuntime.conversationStartupCoordinator.restore(prefs)
        conversations = startup.conversations
        projects = startup.projects
        projectStorageReadOnly = startup.projectStorageReadOnly
        activeProjectId = startup.activeProjectId
        automaticMemoryEnabled = ProjectMemorySettingsStore.isEnabled(prefs, activeProjectId)
        activeConversationId = startup.activeConversationId
        messages = startup.messages
        persistedConversationVersions.clear()
        persistedConversationVersions.putAll(startup.persistedConversationVersions)
        databaseReady = startup.databaseReady
        startup.statusMessage?.let { status = it }
        globalMemory = prefs.getString("global_memory_v1", "") ?: ""
        memoryDraft = globalMemory
        experts = loadExperts(prefs)
        expertPrompts = loadExpertPrompts(prefs)
        val recoveredChat = withContext(Dispatchers.IO) {
            appRuntime.chatRequestRecoveryCoordinator.recover(
                preferences = prefs,
                conversations = conversations,
                databaseReady = databaseReady,
                activeConversationId = activeConversationId
            )
        }
        conversations = recoveredChat.conversations
        recoveredChat.activeMessages?.let { messages = it }
        recoveredChat.restoredPrompt?.let { draft = it }
        if (recoveredChat.interrupted) {
            status = "上次请求中断，已恢复输入和已接收内容，可重新发送"
        }
        // Process-level AgentTaskCoordinator recovery is owned by NestroApplication.onCreate().
        if (config.apiKey.isNotBlank() && config.model.isNotBlank() && status == "尚未配置模型") status = "模型已就绪"
    }

    ConversationSnapshotPersistenceEffect(
        databaseReady = databaseReady,
        conversations = conversations,
        projects = projects,
        persistSnapshot = { conversationSnapshot, projectSnapshot ->
            appRuntime.conversationPersistenceService.persist(
                prefs = prefs,
                conversations = conversationSnapshot,
                projects = projectSnapshot,
                knownVersions = persistedConversationVersions.toMap(),
                snapshotTimestamp = prefs.getLong("local_snapshot_saved_at", 0L)
            )
        },
        onFailure = { status = "本地数据保存失败：${it.message ?: "未知错误"}" }
    )


    fun refreshActiveRegistryConfig(updatedRegistry: ModelRegistry) {
        if (activeRegistryModelId.isBlank()) return
        val activeConfig = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
            registry = updatedRegistry,
            activeModelId = activeRegistryModelId,
            failoverEnabled = false,
            readKey = modelRepository::readApiKey
        ).firstOrNull()
        if (activeConfig != null) {
            config = activeConfig
            modelRepository.writeActiveConfig(config)
        } else {
            activeRegistryModelId = ""
            activeModelProfileId = ""
            config = ModelConfig("", "", "")
            modelRepository.clearActiveConfig()
            prefs.edit().remove("active_model_registry_id").remove("active_model_profile_id").remove("base_url").remove("model").apply()
        }
    }

    val selectableModelProfileIds =
        ModelRequestConfigurationSelector.selectableLegacyProfileIds(modelRegistry)
    val activeReasoningModelId = activeRegistryModelId.ifBlank {
        activeModelProfileId.takeIf { it.isNotBlank() }?.let { "legacy-model-$it" }.orEmpty()
    }
    val activeModelSupportsReasoningEffort = modelRegistry.models
        .firstOrNull { it.id == activeReasoningModelId }?.supportsReasoningEffort == true
    val requestModelConfigs = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
        registry = modelRegistry,
        activeModelId = activeReasoningModelId,
        failoverEnabled = modelFailoverEnabled,
        reasoningEffort = reasoningEffort,
        readKey = modelRepository::readApiKey
    )

    val requestUsageModelIds = requestModelConfigs.associateWith { usedConfig ->
        ModelRequestConfigurationSelector.usageStatsId(modelRegistry, usedConfig)
    }

    fun usageProfileId(usedConfig: ModelConfig): String? =
        requestUsageModelIds[usedConfig]
            ?: modelProfiles.firstOrNull { profile ->
                profile.baseUrl == usedConfig.baseUrl && profile.model == usedConfig.model &&
                    modelRepository.readProfileKey(profile.id) == usedConfig.apiKey
            }?.id

    val recordModelUsage: (ModelConfig, Int, Int) -> Unit = { usedConfig, inputTokens, outputTokens ->
        scope.launch {
            val usageId = usageProfileId(usedConfig)
            if (usageId != null) {
                val latest = ModelUsageStatsCodec.decode(prefs.getString("model_usage_v1", "{}").orEmpty())
                modelUsage = ModelUsageStatsCodec.recordEstimatedTokens(
                    latest, usageId, inputTokens, outputTokens
                )
                prefs.edit().putString("model_usage_v1", ModelUsageStatsCodec.encode(modelUsage)).apply()
            }
        }
    }

    val recordProviderModelUsage: (ModelConfig, Int, Int) -> Unit = { usedConfig, inputTokens, outputTokens ->
        scope.launch {
            val usageId = usageProfileId(usedConfig)
            if (usageId != null) {
                val latest = ModelUsageStatsCodec.decode(prefs.getString("model_usage_v1", "{}").orEmpty())
                modelUsage = ModelUsageStatsCodec.recordProviderUsage(
                    latest, usageId, inputTokens, outputTokens
                )
                prefs.edit().putString("model_usage_v1", ModelUsageStatsCodec.encode(modelUsage)).apply()
            }
        }
    }

    val sendPrompt: (String) -> Unit = send@{ rawPrompt ->
        val prompt = rawPrompt.trim()
        if (prompt.isBlank() || busy) return@send
        val isCoworkbenchRequest = pendingCoworkbenchPrompt.isNotBlank() && prompt.startsWith(pendingCoworkbenchPrompt)
        pendingCoworkbenchPrompt = ""
        draft = ""
        prefs.edit().remove("draft_$activeConversationId").apply()
        val user = if (reuseLastUserOnSend && messages.lastOrNull()?.role == "user") messages.last() else ChatMessage(UUID.randomUUID().toString(), "user", prompt)
        reuseLastUserOnSend = false
        if (messages.lastOrNull()?.id != user.id) messages = messages + user
        conversations = ConversationPreferencesStore.saveConversationMessages(prefs, activeConversationId, messages, activeProjectId, conversations)
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
            scope.launch {
                try {
                val generated = runCatchingCancellable {
                    withContext(Dispatchers.IO) {
                        appRuntime.capabilityDraftCoordinator.generate(
                            kind = creationKind,
                            prompt = prompt,
                            existingNames = existingNames,
                            modelConfigs = requestModelConfigs,
                            onUsage = recordModelUsage
                        )
                    }
                }
                val assistantText = when {
                    stopRequested -> "能力设计已停止。"
                    generated.isSuccess -> "草案已生成，已打开预览。你可以修改指令，确认后才会保存。"
                    else -> "生成失败：${generated.exceptionOrNull()?.message ?: "请检查模型是否支持正常文本回复"}"
                }
                val completedHistory = requestHistory + ChatMessage(UUID.randomUUID().toString(), "assistant", assistantText)
                conversations = ConversationPreferencesStore.saveConversationMessages(prefs, requestConversationId, completedHistory, requestProjectId, conversations)
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
                } finally {
                    busy = false
                    stopRequested = false
                }
            }
            return@send
        }
        if (config.apiKey.isBlank() || config.model.isBlank()) {
            status = "请先配置模型 API"
            messages = messages + ChatMessage(UUID.randomUUID().toString(), "assistant", "还没有配置模型。进入「模型管理」，填写兼容 OpenAI Chat Completions 的 API 地址、模型名称和 Key。")
            conversations = ConversationPreferencesStore.saveConversationMessages(prefs, activeConversationId, messages, activeProjectId, conversations)
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
            activeRequestConversationId = requestConversationId
            val requestProjectId = activeProjectId
            val requestReadableProjectIds = if (!projectResourceScopesReadOnly) ProjectAccessControlStore.readableSourceProjects(
                prefs, requestProjectId, projects.filter { it.archivedAt == 0L }.map { it.id }.toSet()
            ) else emptySet()
            val requestSelectedTeamId = selectedTeamId.takeIf { teamId ->
                val team = expertTeams.firstOrNull { it.id == teamId }
                !projectResourceScopesReadOnly && team != null &&
                    ProjectResourceScopeStore.isTeamVisibleFromProjects(
                        projectResourceScopes, teamId, team.members, requestProjectId, requestReadableProjectIds
                    )
            }.orEmpty()
            val requestSelectedExpert = selectedExpert.takeIf {
                !projectResourceScopesReadOnly && ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.EXPERT, it, requestProjectId, requestReadableProjectIds)
            } ?: "通用助手"
            val requestSelectedSkill = selectedSkill.takeIf {
                !projectResourceScopesReadOnly && ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.SKILL, it, requestProjectId, requestReadableProjectIds)
            }.orEmpty()
            val requestExecutionAuthorization = ChatExecutionAuthorization(taskModeEnabled, requestSelectedTeamId)
            val requestExpertTeams = expertTeams.filter { team ->
                !projectResourceScopesReadOnly &&
                    ProjectResourceScopeStore.isTeamVisibleFromProjects(
                        projectResourceScopes, team.id, team.members, requestProjectId, requestReadableProjectIds
                    )
            }
            val requestTeamMemberNames = requestExpertTeams.flatMap { it.members }.toSet()
            val requestExpertProfiles = expertProfiles.filterKeys {
                !projectResourceScopesReadOnly && (it in requestTeamMemberNames ||
                    ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.EXPERT, it, requestProjectId, requestReadableProjectIds))
            }
            val requestExpertPrompts = expertPrompts.filterKeys {
                !projectResourceScopesReadOnly && (it in requestTeamMemberNames ||
                    ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.EXPERT, it, requestProjectId, requestReadableProjectIds))
            }
            val requestSkills = skills.filterKeys {
                !projectResourceScopesReadOnly &&
                    ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.SKILL, it, requestProjectId, requestReadableProjectIds)
            }
            val requestDisabledSkills = disabledSkills.toSet()
            val requestExperts = experts.filter {
                !projectResourceScopesReadOnly && (it in requestTeamMemberNames ||
                    ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.EXPERT, it, requestProjectId, requestReadableProjectIds))
            }.toSet()
            val requestProjectInstructions = projects.firstOrNull { it.id == requestProjectId }?.instructions.orEmpty()
            val requestAutomaticMemoryEnabled = automaticMemoryEnabled
            val requestStartedAt = System.currentTimeMillis()
            ChatRequestStateStore.save(prefs, assistantId, requestConversationId, requestProjectId, prompt, "", requestStartedAt)
            val requestExpertProfile = requestExpertProfiles[requestSelectedExpert]?.takeIf { requestExecutionAuthorization.taskModeEnabled && it.enabled && requestExecutionAuthorization.selectedTeamId.isBlank() }
            val baseSystemContext = listOf(
                if (ChatContextRetrievalPolicy.shouldRetrieveUserMemory(prompt)) "全局记忆（按需召回，仅作为用户背景参考）：\n" + globalMemory.take(1600) else "",
                if (requestExecutionAuthorization.taskModeEnabled && requestExecutionAuthorization.selectedTeamId.isBlank() && requestExpertProfiles[requestSelectedExpert]?.enabled != false) requestExpertPrompts[requestSelectedExpert].orEmpty().ifBlank { ExpertTeamDefaults.rolePrompt(selectedExpert) } else "",
                if (requestExecutionAuthorization.taskModeEnabled && requestExecutionAuthorization.selectedTeamId.isBlank()) ExpertProfileCodec.skillContext(requestExpertProfiles[requestSelectedExpert], requestSkills, requestDisabledSkills) else "",
                requestProjectInstructions,
                if (manualExpertSelection.size in 2..4) {
                    "用户已在专家中心主动选择以下专家，本轮若任务需要专家协作，应优先使用这些成员：" + manualExpertSelection.filter { it in requestExperts }.joinToString("、")
                } else "",
                if (requestExperts.count { name -> requestExpertProfiles[name]?.enabled != false } >= 2) {
                    "当前项目可用专家目录（主模型可按任务需要自动组队）：\\n" + requestExperts
                        .filter { requestExpertProfiles[it]?.enabled != false }
                        .sorted()
                        .take(24)
                        .joinToString("\\n") { name ->
                            val role = requestExpertPrompts[name].orEmpty().ifBlank { ExpertTeamDefaults.rolePrompt(name) }
                            val skillContext = ExpertProfileCodec.skillContext(requestExpertProfiles[name], requestSkills, requestDisabledSkills)
                            "- 专家：$name\\n  角色：${role.take(900)}\\n  专属技能：${skillContext.take(700)}"
                        }.take(8_000)
                } else "",
                requestSkills[requestSelectedSkill].orEmpty().takeIf { requestExecutionAuthorization.taskModeEnabled && requestSelectedSkill !in requestDisabledSkills }.orEmpty()
            ).filter { it.isNotBlank() }.joinToString("\n\n").take(12000)
            scope.launch {
                try {
                if (stopRequested) {
                    val stoppedHistory = requestHistory + ChatMessage(assistantId, "assistant", "已停止生成。")
                    conversations = ConversationPreferencesStore.saveConversationMessages(prefs, requestConversationId, stoppedHistory, requestProjectId, conversations)
                    if (activeConversationId == requestConversationId) messages = stoppedHistory
                    ChatRequestStateStore.clear(prefs)
                    status = "已停止生成"
                    busy = false
                    activeRequestId = ""
                    activeRequestConversationId = ""
                    stopRequested = false
                    return@launch
                }
                val execution = appRuntime.chatRequestExecutionCoordinator.execute(
                    request = ChatRequestExecutionRequest(
                        requestId = assistantId,
                        conversationId = requestConversationId,
                        projectId = requestProjectId,
                        prompt = prompt,
                        startedAt = requestStartedAt,
                        history = requestHistory,
                        modelConfigs = requestModelConfigs,
                        baseSystemContext = baseSystemContext,
                        taskModeEnabled = requestExecutionAuthorization.taskModeEnabled,
                        expertTeamEnabled = requestExperts.count { name -> requestExpertProfiles[name]?.enabled != false } >= 2,
                        temperature = requestExpertProfile?.temperature,
                        maxTokens = requestExpertProfile?.maxTokens,
                        previousAssistant = previousAssistant,
                        shouldStop = { stopRequested }
                    ),
                    onUsage = recordModelUsage,
                    onProviderUsage = recordProviderModelUsage,
                    planPhoneAgent = { toolArguments ->
                        if (!requestExecutionAuthorization.shouldExecutePhoneAgent()) {
                            app.nestro.agent.AgentToolResult(false, error = "当前请求没有获得 Phone Agent 任务模式授权，未创建设备任务。")
                        } else {
                            val phoneGoal = toolArguments["goal"]?.trim()?.takeIf(String::isNotBlank) ?: prompt
                            val phonePlan = PhoneAgentPlanner.plan(phoneGoal)
                            if (!PhoneAgentPlanner.shouldCreateTask(requestExecutionAuthorization.taskModeEnabled, phonePlan)) {
                                app.nestro.agent.AgentToolResult(false, error = "当前目标不需要 Phone Agent 后台设备任务，未创建任务。")
                            } else {
                                val canSchedule = app.nestro.phone.PhoneAgentExecutionEnvironmentProvider.active?.hasValidGrant() == true
                                val created = withContext(Dispatchers.IO) {
                                    appRuntime.phoneAgentTaskService.createAndScheduleIfAuthorized(
                                        requestProjectId,
                                        requestConversationId,
                                        phonePlan
                                    )
                                }
                                val message = if (canSchedule) {
                                    "已创建并加入后台队列的 Phone Agent 任务（${created.id}），当前状态：${created.status}。实际设备操作仍按任务权限与风险步骤执行。"
                                } else {
                                    "已创建 Phone Agent 任务（${created.id}），但设备服务尚未处于有效授权状态，暂未加入队列；实际设备操作尚未开始。"
                                }
                                app.nestro.agent.AgentToolResult(true, output = message)
                            }
                        }
                    },
                    onTaskPlanningFailure = { status = "任务计划保存失败：${it.message ?: "未知错误"}" },
                    runExpertTeam = { requestSystemContext, toolArguments ->
                    val selectedTeam = requestExpertTeams.firstOrNull {
                        it.id == requestExecutionAuthorization.selectedTeamId
                    }
                    val memberSelection = toolArguments["member_names"].orEmpty()
                    val requestedMembers = runCatching {
                        val array = JSONArray(memberSelection)
                        (0 until array.length()).map { array.getString(it).trim() }.filter { it.isNotBlank() }
                    }.getOrElse {
                        memberSelection.split(Regex("[,，、;；]")).map { it.trim() }.filter { it.isNotBlank() }
                    }
                    val chosenNames = (selectedTeam?.members
                        ?: manualExpertSelection.takeIf { it.size in 2..4 }?.toList()
                        ?: requestedMembers)
                        .distinct()
                        .filter { it in requestExperts && requestExpertProfiles[it]?.enabled != false }
                        .take(4)
                    check(chosenNames.size in 2..4) {
                        if (selectedTeam != null) "当前专家团队至少需要 2 位已启用专家。"
                        else "主模型没有从当前项目的专家目录中选出至少 2 位可用专家，已阻止启动空团队。"
                    }
                    val teamId = selectedTeam?.id ?: "auto-" + assistantId.take(48)
                    val teamName = selectedTeam?.name ?: toolArguments["team_name"]?.take(40)?.ifBlank { null } ?: "临时专家团"
                    val teamDescription = selectedTeam?.description
                        ?: toolArguments["team_description"]?.take(300)?.ifBlank { null }
                        ?: prompt.take(300)
                    val assignmentJson = runCatching { JSONObject(toolArguments["assignments"].orEmpty()) }.getOrNull()
                    val memberTasks = chosenNames.associateWith { member ->
                        val role = requestExpertPrompts[member].orEmpty().ifBlank { ExpertTeamDefaults.rolePrompt(member) }
                        val attached = ExpertProfileCodec.skillContext(requestExpertProfiles[member], requestSkills, requestDisabledSkills)
                        ExpertTeamMemberTask(
                            name = member,
                            rolePrompt = listOf(role, attached).filter { it.isNotBlank() }.joinToString("\n\n"),
                            assignedTask = assignmentJson?.optString(member).orEmpty().take(8_000)
                        )
                    }
                    val plan = runCatching {
                        val json = JSONObject(toolArguments["plan"] ?: error("模型未提供项目工作包计划"))
                        val packageArray = json.getJSONArray("packages")
                        val packages = (0 until packageArray.length()).map { index ->
                            val item = packageArray.getJSONObject(index)
                            val dependencyArray = item.optJSONArray("depends_on") ?: JSONArray()
                            ExpertWorkPackage(
                                id = item.optString("id"),
                                memberName = item.optString("member_name"),
                                instruction = item.optString("instruction"),
                                dependsOn = (0 until dependencyArray.length()).map { dependencyArray.getString(it) },
                                acceptanceCriteria = item.optString("acceptance_criteria")
                            )
                        }
                        ExpertProjectPlan(
                            projectGoal = json.optString("project_goal", prompt),
                            packages = packages,
                            finalAcceptanceCriteria = json.optString("final_acceptance_criteria", "所有工作包完成，主模型完成交叉审查并整合交付。")
                        )
                    }.getOrElse {
                        ExpertProjectPlan(
                            projectGoal = prompt,
                            packages = chosenNames.mapIndexed { index, member ->
                                ExpertWorkPackage(
                                    id = "work-${index + 1}",
                                    memberName = member,
                                    instruction = memberTasks.getValue(member).assignedTask.ifBlank { prompt },
                                    acceptanceCriteria = "提交可核验的工作成果、依据、风险和未解决项。"
                                )
                            },
                            finalAcceptanceCriteria = "所有工作包完成，主模型完成交叉审查并整合交付。"
                        )
                    }
                    teamRunStatus = "专家团“$teamName”正在拆解并执行项目…"
                    teamMemberStatuses = plan.packages.associate { it.id to "waiting" }
                    val eventPrefsKey = "expert_team_events_v1"
                    val projectPlanResult = appRuntime.expertTeamExecutionCoordinator.executeProjectPlan(
                        teamId = teamId,
                        teamName = teamName,
                        teamDescription = teamDescription,
                        plan = plan,
                        members = memberTasks,
                        projectId = requestProjectId,
                        taskId = assistantId + "-team-" + UUID.randomUUID().toString().take(8),
                        shouldStop = { stopRequested },
                        eventSink = app.nestro.data.ExpertTeamEventSink { event ->
                            withContext(Dispatchers.IO) {
                                val existing = app.nestro.data.ExpertTeamCollaborationEventCodec.decode(
                                    prefs.getString(eventPrefsKey, "[]") ?: "[]"
                                )
                                check(prefs.edit().putString(
                                    eventPrefsKey,
                                    app.nestro.data.ExpertTeamCollaborationEventCodec.encode(existing + event)
                                ).commit()) { "专家协作事件保存失败" }
                                withContext(Dispatchers.Main) {
                                    if (activeProjectId == requestProjectId) {
                                        expertTeamEvents = (expertTeamEvents + event).takeLast(100)
                                    }
                                }
                            }
                        },
                        onPackage = { packageId, state, detail ->
                            withContext(Dispatchers.Main) {
                                teamMemberStatuses = teamMemberStatuses + (packageId to state)
                                teamRunStatus = when (state) {
                                    "running" -> "专家团执行中：$packageId 正在执行"
                                    "success" -> "专家团执行中：$packageId 已提交成果"
                                    "failed" -> "专家团执行中：$packageId 执行失败"
                                    "blocked" -> "专家团执行中：$packageId 等待依赖"
                                    else -> teamRunStatus
                                }
                            }
                        },
                        invokeMember = { member, memberPrompt ->
                            withContext(Dispatchers.IO) {
                                val memberSystemContext = listOf(requestSystemContext, memberPrompt)
                                    .filter { it.isNotBlank() }.joinToString("\n\n").take(18_000)
                                appRuntime.chatExecutionService.completeWithFallback(
                                    requestModelConfigs,
                                    ChatRequestPolicy.boundedChatHistory(requestHistory, memberSystemContext),
                                    memberSystemContext,
                                    connectTimeoutMillis = 15_000,
                                    readTimeoutMillis = 25_000,
                                    temperature = requestExpertProfiles[member.name]?.temperature,
                                    maxTokens = requestExpertProfiles[member.name]?.maxTokens,
                                    onUsage = recordModelUsage,
                                    onProviderUsage = recordProviderModelUsage
                                ).take(12_000)
                            }
                        },
                        toolCatalog = appRuntime.agentToolCatalog
                    )
                    val projectResultContext = buildString {
                        append("专家项目：").append(plan.projectGoal).append("\n")
                        append("最终验收标准：").append(plan.finalAcceptanceCriteria).append("\n\n")
                        projectPlanResult.packageResults.forEach { item ->
                            append("【").append(item.memberName).append(" / ").append(item.packageId).append(" / ").append(item.status).append("】\n")
                            append(item.output.ifBlank { item.error }.take(5_000)).append("\n\n")
                        }
                        append("项目执行状态：").append(if (projectPlanResult.isSuccessful) "全部工作包成功" else "存在失败或阻塞工作包")
                    }.take(18_000)
                    teamRunStatus = if (projectPlanResult.isSuccessful) "专家项目已完成，等待主模型审核" else "专家项目部分工作包未完成，交由主模型检查"
                    projectResultContext
                    },

                    onPartialAnswer = { partial ->
                        withContext(Dispatchers.Main) {
                            if (activeConversationId == requestConversationId) {
                                messages = requestHistory + ChatMessage(assistantId, "assistant", partial, previousAssistant?.versions.orEmpty(), previousAssistant?.activeVersion ?: 0)
                            }
                        }
                    }
                )
                val recalledCount = execution.recalledCount
                if (activeConversationId == requestConversationId) recallSources = execution.recallSources
                val finalized = execution.finalization
                val finalText = finalized.finalText
                val completedHistory = finalized.completedHistory
                conversations = ConversationPreferencesStore.saveConversationMessages(prefs, requestConversationId, completedHistory, requestProjectId, conversations)
                prefs.edit().remove("chat_request_state_v1").apply()
                if (activeConversationId == requestConversationId) messages = completedHistory
                if (isCoworkbenchRequest && finalized.status == ChatResponseStatus.SUCCESS && !stopRequested && activeConversationId == requestConversationId) {
                    coworkbenchGeneratedPatch = finalText
                    page = "coworkbench"
                }
                status = when (finalized.status) {
                    ChatResponseStatus.STOPPED -> "已停止生成"
                    ChatResponseStatus.SUCCESS -> when {
                        execution.taskPlanningFailure != null -> "已连接 · 任务计划保存失败：${execution.taskPlanningFailure.message ?: "未知错误"}"
                        recalledCount > 0 -> "已连接 · 参考历史 ${recalledCount} 条 · 本地保存"
                        else -> "已连接 · 本地保存"
                    }
                    ChatResponseStatus.FAILURE -> "连接失败"
                }
                busy = false
                activeRequestId = ""
                activeRequestConversationId = ""
                if (finalized.status == ChatResponseStatus.SUCCESS && !stopRequested) {
                    scope.launch(Dispatchers.IO) {
                        appRuntime.automaticMemoryExtractionCoordinator.extractAndSave(
                            enabled = requestAutomaticMemoryEnabled,
                            prompt = prompt,
                            modelConfigs = requestModelConfigs,
                            projectId = requestProjectId,
                            conversationId = requestConversationId,
                            messageId = requestHistory.lastOrNull { it.role == "user" }?.id.orEmpty()
                        )
                    }
                }
                stopRequested = false
                } finally {
                    if (activeRequestId == assistantId) {
                        busy = false
                        activeRequestId = ""
                        activeRequestConversationId = ""
                        stopRequested = false
                    }
                }
            }
        }
    }

    LaunchedEffect(messages.size, busy, activeConversationId) {
        if (page != "home" && page != "chat") return@LaunchedEffect
        val lastIndex = messages.lastIndex + if (busy) 1 else 0
        if (lastIndex >= 0) {
            if (!initialChatPositionRestored) {
                chatListState.scrollToItem(lastIndex)
                initialChatPositionRestored = true
            } else {
                chatListState.animateScrollToItem(lastIndex)
            }
        }
    }

    LaunchedEffect(page) {
        if (page == "home" || page == "chat") {
            val lastIndex = messages.lastIndex + if (busy) 1 else 0
            if (lastIndex >= 0) chatListState.scrollToItem(lastIndex)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg, surface = Card, primary = Mint, onPrimary = Bg,
            onBackground = Ink, onSurface = Ink, secondary = Muted
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Box(Modifier.fillMaxSize()) {
                if (showDrawer && screenWidthDp < 600) {
                    NavigationDrawerContent(
                        modifier = Modifier.fillMaxSize(),
                        onNavigate = { destination -> page = destination; showDrawer = false },
                        onClose = { showDrawer = false }
                    )
                } else {
                Row(Modifier.fillMaxSize()) {
                    if (showDrawer && screenWidthDp >= 600) {
                        NavigationDrawerContent(
                            modifier = Modifier.width(adaptiveDrawerWidth).fillMaxHeight(),
                            onNavigate = { destination -> page = destination; showDrawer = false },
                            onClose = { showDrawer = false }
                        )
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = adaptiveContentPadding, vertical = 14.dp),
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
                        TextButton(onClick = { page = "comparison" }) { Text("双模型", color = Mint, style = MaterialTheme.typography.labelMedium) }
                        TextButton(onClick = { page = "coworkbench" }) { Text("AI共建", color = Mint, style = MaterialTheme.typography.labelMedium) }
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
                            conversations = ConversationPreferencesStore.saveConversationMessages(prefs, activeConversationId, messages, activeProjectId, conversations)
                            sendPrompt(retryPrompt)
                        }
                    }) else null,
                    onVersionChange = { version ->
                        val index = messages.indexOfFirst { it.id == msg.id }
                        if (index >= 0) {
                            val allVersions = msg.allVersions()
                            val chosen = version.coerceIn(0, allVersions.lastIndex)
                            messages = messages.toMutableList().also { it[index] = msg.copy(content = allVersions[chosen], versions = allVersions, activeVersion = chosen) }
                            conversations = ConversationPreferencesStore.saveConversationMessages(prefs, activeConversationId, messages, activeProjectId, conversations)
                        }
                    },
                    onOpenVideo = { assetId ->
                        pendingOpenMediaAssetId = assetId
                        page = "media-editor"
                    },
                    onOpenMediaSetup = {
                        pendingOpenMediaAssetId = ""
                        page = "media-editor"
                    },
                    onRetry = {
                                            if (messages.lastOrNull()?.id == msg.id) {
                                                val lastUserIndex = messages.indexOfLast { it.role == "user" }
                                                if (lastUserIndex >= 0) {
                                                    val retryPrompt = messages[lastUserIndex].content
                                                    messages = messages.take(lastUserIndex)
                                                    conversations = ConversationPreferencesStore.saveConversationMessages(
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
                                    AssistChip(onClick = { taskModeEnabled = !taskModeEnabled; prefs.edit().putBoolean("chat_task_mode_enabled", taskModeEnabled).apply() }, label = { Text(if (taskModeEnabled) "任务执行：开启" else "普通聊天") })
                                    Box {
                                        AssistChip(onClick = { showReasoningMenu = true }, label = { Text("思考：${when (reasoningEffort) { "none" -> "关闭"; "low" -> "轻量"; "medium" -> "中度"; else -> "重度" }}${if (activeModelSupportsReasoningEffort) "" else "（当前模型不支持）"}") })
                                        DropdownMenu(expanded = showReasoningMenu, onDismissRequest = { showReasoningMenu = false }) {
                                            listOf("none" to "关闭思考", "low" to "轻量思考", "medium" to "中度思考", "high" to "重度思考").forEach { (value, label) ->
                                                DropdownMenuItem(text = { Text(label) }, onClick = { reasoningEffort = value; prefs.edit().putString("reasoning_effort_v1", value).apply(); showReasoningMenu = false })
                                            }
                                        }
                                    }
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
                                                    appRuntime.chatExecutionService.cancel(activeRequestId)
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
                        "comparison" -> ModelComparisonPage(
                            registry = modelRegistry,
                            repository = modelRepository,
                            reasoningEffort = reasoningEffort,
                            onBack = { page = "home" },
                            onManageModels = { page = "models" }
                        )
                        "models" -> ModelManager(
                            profiles = modelProfiles,
                            activeProfileId = activeModelProfileId,
                            selectableProfileIds = selectableModelProfileIds,
                            activeConfig = config,
                            usage = modelUsage,
                            failoverEnabled = modelFailoverEnabled,
                            onFailoverChange = { enabled ->
                                modelFailoverEnabled = enabled
                                prefs.edit().putBoolean("model_failover_enabled", enabled).apply()
                            },
                            loadKey = { id -> modelRepository.readProfileKey(id) },
                            onActivate = { id ->
                                val metadata = modelProfiles.firstOrNull { it.id == id }
                                if (metadata != null) {
                                    val key = modelRepository.readProfileKey(id)
                                    if (key.isNotBlank() && modelRegistry.selectableModels().any { it.id == "legacy-model-$id" }) {
                                        activeModelProfileId = id
                                        activeRegistryModelId = "legacy-model-" + id
                                        prefs.edit().putString("active_model_registry_id", activeRegistryModelId).apply()
                                        config = ModelConfig(metadata.baseUrl, metadata.model, key)
                                        prefs.edit().putString("active_model_profile_id", id)
                                            .putString("base_url", metadata.baseUrl).putString("model", metadata.model).apply()
                                        modelRepository.writeActiveConfig(config)
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
                                    modelProfiles = ModelProfileLifecycle.selectable(modelProfiles.filterNot { it.id == metadata.id } + metadata)
                                    val existingKey = modelRepository.readProfileKey(metadata.id)
                                    val effectiveKey = apiKey.trim().ifBlank { existingKey }
                                    if (apiKey.isNotBlank()) modelRepository.writeProfileKey(metadata.id, effectiveKey)
                                    modelRegistry = modelRegistry.upsertLegacyProfile(
                                        metadata,
                                        keyAvailable = effectiveKey.isNotBlank(),
                                        enableKey = apiKey.isNotBlank()
                                    )
                                    modelRepository.saveRegistry(modelRegistry)
                                    prefs.edit().putString("model_profiles_v1", ModelProfileCatalogCodec.encode(modelProfiles)).apply()
                                    if ((activate || metadata.id == activeModelProfileId) && effectiveKey.isNotBlank()) {
                                        activeModelProfileId = metadata.id
                                        activeRegistryModelId = "legacy-model-" + metadata.id
                                        prefs.edit().putString("active_model_registry_id", activeRegistryModelId).apply()
                                        config = ModelConfig(metadata.baseUrl, metadata.model, effectiveKey)
                                        modelRepository.writeActiveConfig(config)
                                        prefs.edit().putString("active_model_profile_id", metadata.id).apply()
                                        status = "模型配置已保存并启用：${metadata.name}"
                                    } else {
                                        if (metadata.id == activeModelProfileId && effectiveKey.isBlank()) {
                                            activeModelProfileId = ""
                                            config = ModelConfig("", "", "")
                                            modelRepository.clearActiveConfig()
                                        }
                                        status = if (effectiveKey.isBlank()) "模型配置已保存；请填写 API Key 后再启用" else "模型配置已保存：${metadata.name}"
                                    }
                                }
                            },
                            registry = modelRegistry,
                            activeRegistryModelId = activeRegistryModelId,
                            loadApiKey = { id -> modelRepository.readApiKey(id) },
                            onSaveProvider = { provider ->
                                runCatching {
                                    if (provider.id.startsWith("legacy-provider-")) {
                                        val existing = modelRegistry.providers.firstOrNull { it.id == provider.id }
                                        require(existing?.baseUrl == provider.baseUrl && existing.name == provider.name) {
                                            "旧版服务商信息由 Profile 迁移生成，请在下方旧模型配置中管理"
                                        }
                                    }
                                    val updated = modelRegistry.upsertProvider(provider)
                                    modelRepository.saveRegistry(updated)
                                    modelRegistry = updated
                                    refreshActiveRegistryConfig(updated)
                                    status = "服务商已保存"
                                }.onFailure { status = "服务商保存失败：" + (it.message ?: "未知错误") }
                            },
                            onDeleteProvider = { providerId ->
                                runCatching {
                                    val keyIds = modelRegistry.apiKeys.filter { it.providerId == providerId }.map { it.id }
                                    val legacyProfileIds = modelProfiles
                                        .filter { ModelRegistryCodec.legacyProviderId(it.baseUrl) == providerId }
                                        .map { it.id }
                                    val updated = modelRegistry.deleteProvider(providerId)
                                    modelRepository.saveRegistry(updated)
                                    modelRegistry = updated
                                    if (legacyProfileIds.isNotEmpty()) {
                                        modelProfiles = modelProfiles.filterNot { it.id in legacyProfileIds }
                                        prefs.edit().putString(
                                            "model_profiles_v1",
                                            ModelProfileCatalogCodec.encode(modelProfiles)
                                        ).apply()
                                    }
                                    // Remove legacy profile ownership as well; otherwise startup migration
                                    // would recreate a provider that the user explicitly deleted.
                                    if (activeModelProfileId in legacyProfileIds) {
                                        activeModelProfileId = ""
                                        activeRegistryModelId = ""
                                        config = ModelConfig("", "", "")
                                        modelRepository.clearActiveConfig()
                                        prefs.edit()
                                            .remove("active_model_profile_id")
                                            .remove("active_model_registry_id")
                                            .remove("base_url")
                                            .remove("model")
                                            .apply()
                                    } else {
                                        // Clear a removed active model before secret cleanup can fail.
                                        refreshActiveRegistryConfig(updated)
                                    }
                                    legacyProfileIds.forEach(modelRepository::deleteProfileKey)
                                    keyIds.forEach(modelRepository::deleteApiKey)
                                    status = "服务商、关联模型及 API Key 已删除"
                                }.onFailure { status = "服务商删除失败：" + (it.message ?: "未知错误") }
                            },
                            onSaveApiKey = { item, secret ->
                                val keyAlreadyExists = modelRegistry.apiKeys.any { it.id == item.id }
                                runCatching {
                                    modelRepository.writeApiKey(item.id, secret)
                                    val updated = modelRegistry.upsertApiKey(item)
                                    modelRepository.saveRegistry(updated)
                                    modelRegistry = updated
                                    refreshActiveRegistryConfig(updated)
                                    status = "API Key 已加密保存"
                                }.onFailure {
                                    if (!keyAlreadyExists) modelRepository.deleteApiKey(item.id)
                                    status = "API Key 保存失败：" + (it.message ?: "未知错误")
                                }
                            },
                            onSaveRegistryModel = { item ->
                                runCatching {
                                    val updated = modelRegistry.upsertModel(item)
                                    modelRepository.saveRegistry(updated)
                                    modelRegistry = updated
                                    refreshActiveRegistryConfig(updated)
                                    status = "模型已保存：" + item.name
                                }.onFailure { status = "模型保存失败：" + (it.message ?: "未知错误") }
                            },
                            onDeleteRegistryApiKey = { id ->
                                modelRepository.deleteApiKey(id)
                                val updated = modelRegistry.deleteApiKey(id)
                                modelRepository.saveRegistry(updated)
                                modelRegistry = updated
                                refreshActiveRegistryConfig(updated)
                                status = "API Key 已删除，关联模型已重新计算可用性"
                            },
                            onDeleteRegistryModel = { id ->
                                val updated = modelRegistry.deleteModel(id)
                                modelRepository.saveRegistry(updated)
                                modelRegistry = updated
                                refreshActiveRegistryConfig(updated)
                                status = "模型已删除"
                            },
                            onActivateRegistryModel = { id ->
                                val candidate = modelRegistry.models.firstOrNull { it.id == id }
                                val selectedConfig = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
                                    registry = modelRegistry,
                                    activeModelId = id,
                                    failoverEnabled = false,
                                    readKey = modelRepository::readApiKey
                                ).firstOrNull()
                                if (candidate != null && selectedConfig != null) {
                                    activeRegistryModelId = id
                                    activeModelProfileId = ""
                                    config = selectedConfig
                                    modelRepository.writeActiveConfig(config)
                                    prefs.edit().putString("active_model_registry_id", id).remove("active_model_profile_id").apply()
                                    status = "已切换模型：" + candidate.name
                                } else status = "该模型没有可用的 API Key，无法启用"
                            },
                            onTestConnection = { candidate -> appRuntime.modelConnectionService.test(candidate) },
                            onDiscoverModels = { provider, apiKey ->
                                app.nestro.model.ModelDiscoveryService().discover(provider, apiKey)
                            },
                            onDeleteKey = { id ->
                                modelRepository.deleteProfileKey(id)
                                modelRegistry = modelRegistry.deleteApiKey("legacy-key-$id")
                                modelRepository.saveRegistry(modelRegistry)
                                if (activeModelProfileId == id || activeRegistryModelId == "legacy-model-$id") {
                                    activeModelProfileId = ""
                                    activeRegistryModelId = ""
                                    config = ModelConfig("", "", "")
                                    modelRepository.clearActiveConfig()
                                    prefs.edit().remove("active_model_profile_id").remove("active_model_registry_id")
                                        .remove("base_url").remove("model").apply()
                                }
                                status = "API Key 已删除；对应模型已从可用模型中排除"
                            },
                            onDelete = { id ->
                                val wasActive = activeModelProfileId == id || activeRegistryModelId == "legacy-model-$id"
                                modelProfiles = modelProfiles.filterNot { it.id == id }
                                val updatedRegistry = modelRegistry.deleteLegacyProfile(id)
                                modelRegistry = updatedRegistry
                                modelRepository.saveRegistry(updatedRegistry)
                                modelRepository.deleteProfileKey(id)
                                prefs.edit().putString("model_profiles_v1", ModelProfileCatalogCodec.encode(modelProfiles)).apply()
                                if (wasActive) {
                                    val availableIds = updatedRegistry.selectableModels()
                                        .mapNotNull { model -> model.id.removePrefix("legacy-model-").takeIf { model.id.startsWith("legacy-model-") } }
                                        .toSet()
                                    val next = modelProfiles.firstOrNull {
                                        it.id in availableIds && modelRepository.readProfileKey(it.id).isNotBlank()
                                    }
                                    if (next != null) {
                                        val key = modelRepository.readProfileKey(next.id)
                                        activeModelProfileId = next.id
                                        activeRegistryModelId = "legacy-model-" + next.id
                                        config = ModelConfig(next.baseUrl, next.model, key)
                                        modelRepository.writeActiveConfig(config)
                                        prefs.edit().putString("active_model_profile_id", next.id)
                                            .putString("active_model_registry_id", activeRegistryModelId).apply()
                                        status = "已切换到：${next.name}"
                                    } else {
                                        activeModelProfileId = ""
                                        activeRegistryModelId = ""
                                        config = ModelConfig("", "", "")
                                        modelRepository.clearActiveConfig()
                                        prefs.edit().remove("active_model_profile_id").remove("active_model_registry_id")
                                            .remove("base_url").remove("model").apply()
                                        status = "尚未配置可用模型"
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
                                             appRuntime.chatExecutionService.cancel()
                                             workflowRunStatus = "正在停止工作流…"
                                         }) { Text("停止", color = ColorStop) }
                                     }
                                 }
                                 workflows.filter { !projectResourceScopesReadOnly && ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.WORKFLOW, it.id, activeProjectId, readableProjectIds) }.forEach { workflow ->
                                     Surface(color = Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                         Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                             Text(workflow.name, color = Ink, style = MaterialTheme.typography.titleMedium)
                                             Text(workflow.description, color = Muted, style = MaterialTheme.typography.bodySmall)
                                             val workflowIsGlobal = projectResourceScopes[ProjectResourceScopeStore.resourceKey(ProjectResourceKind.WORKFLOW, workflow.id)]?.contains("*") ?: true
                                             TextButton(onClick = { updateProjectResourceScope(ProjectResourceKind.WORKFLOW, workflow.id, workflowIsGlobal.not()) }) {
                                                 Text(if (workflowIsGlobal) "限定到当前项目" else "设为全局共享", color = Mint)
                                             }
                                             workflow.steps.forEachIndexed { index, step -> Text("${index + 1}. ${step.title} · ${when (step.kind) { WorkflowStepKind.PROMPT -> "提示词"; WorkflowStepKind.EXPERT -> "专家"; WorkflowStepKind.SKILL -> "技能" }}", color = Mint, style = MaterialTheme.typography.bodySmall) }
                                             Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                 Button(enabled = runningWorkflowId.isBlank() && !busy && workflowInputDraft.isNotBlank() && config.apiKey.isNotBlank(), onClick = {
                                                     val input = workflowInputDraft.trim()
                                                     val conversationId = activeConversationId
                                                     val projectId = activeProjectId
                                                     val workflowReadableProjectIds = if (!projectResourceScopesReadOnly) ProjectAccessControlStore.readableSourceProjects(
                                                         prefs, projectId, projects.filter { it.archivedAt == 0L }.map { it.id }.toSet()
                                                     ) else emptySet()
                                                     val runHistory = messages.toList() + ChatMessage(UUID.randomUUID().toString(), "user", input)
                                                     val startedAt = System.currentTimeMillis()
                                                     runningWorkflowId = workflow.id
                                                     workflowPaused = false
                                                     workflowPauseGate = null
                                                     busy = true
                                                     stopRequested = false
                                                     workflowRunStatus = "正在启动工作流…"
                                                     scope.launch {
                                                         try {
                                                         val result = runCatchingCancellable {
                                                             appRuntime.workflowExecutionCoordinator.execute(
                                                                 workflow = workflow,
                                                                 input = input,
                                                                 invokeModel = { step, stepPrompt ->
                                                                     val expertOptions = if (step.kind == WorkflowStepKind.EXPERT) expertProfiles[step.targetId.ifBlank { step.title }] else null
                                                                     val roleContext = when (step.kind) {
                                                                         WorkflowStepKind.EXPERT -> {
                                                                             val expertName = step.targetId.ifBlank { step.title }
                                                                             check(!projectResourceScopesReadOnly &&
                                                                                 ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.EXPERT, expertName, projectId, workflowReadableProjectIds)) {
                                                                                 "工作流引用的专家「$expertName」不属于当前项目，已阻止跨项目调用"
                                                                             }
                                                                             val profile = expertProfiles[expertName]
                                                                             check(profile?.enabled != false) { "工作流引用的专家「$expertName」已停用" }
                                                                             val workflowSkills = skills.filterKeys { skillName ->
                                                                                 !projectResourceScopesReadOnly &&
                                                                                     ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.SKILL, skillName, projectId, workflowReadableProjectIds)
                                                                             }
                                                                             listOf(expertPrompts[expertName].orEmpty().ifBlank { ExpertTeamDefaults.rolePrompt(expertName) }, ExpertProfileCodec.skillContext(profile, workflowSkills, disabledSkills)).filter { it.isNotBlank() }.joinToString("\n\n")
                                                                         }
                                                                         WorkflowStepKind.SKILL -> {
                                                                             val skillName = step.targetId.ifBlank { step.title }
                                                                             check(!projectResourceScopesReadOnly &&
                                                                                 ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.SKILL, skillName, projectId, workflowReadableProjectIds)) {
                                                                                 "工作流引用的技能「$skillName」不属于当前项目，已阻止跨项目调用"
                                                                             }
                                                                             check(skillName !in disabledSkills) { "工作流引用的技能「$skillName」已停用" }
                                                                             skills[skillName]?.takeIf { it.isNotBlank() } ?: error("工作流引用的技能「$skillName」不存在")
                                                                         }
                                                                         WorkflowStepKind.PROMPT -> ""
                                                                     }
                                                                     withContext(Dispatchers.IO) {
                                                                         appRuntime.chatExecutionService.completeWithFallback(
                                                                             requestModelConfigs,
                                                                             listOf(ChatMessage(UUID.randomUUID().toString(), "user", stepPrompt)),
                                                                             listOf(roleContext, projects.firstOrNull { it.id == projectId }?.instructions.orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n").take(12000),
                                                                             temperature = expertOptions?.temperature,
                                                                             maxTokens = expertOptions?.maxTokens,
                                                                             onUsage = recordModelUsage,
                                                                             onProviderUsage = recordProviderModelUsage
                                                                         )
                                                                     }
                                                                 },
                                                                 onStep = { index, step ->
                                                                     if (stopRequested) throw IllegalStateException("用户停止工作流")
                                                                     workflowPauseGate?.await()
                                                                     if (stopRequested) throw IllegalStateException("用户停止工作流")
                                                                     workflowRunStatus = if (workflowPaused) "已暂停，等待继续…" else "执行第 ${index}/${workflow.steps.count { it.enabled }} 步：${step.title}"
                                                                 },
                                                                 requestApproval = { step, previousOutput ->
                                                                     val decision = CompletableDeferred<Boolean>()
                                                                     workflowApprovalPrompt = WorkflowApprovalPrompt(
                                                                         stepTitle = step.title,
                                                                         context = previousOutput.take(2_000),
                                                                         decision = decision
                                                                     )
                                                                     try {
                                                                         decision.await()
                                                                     } finally {
                                                                         workflowApprovalPrompt = null
                                                                     }
                                                                 },
                                                                 toolCatalog = appRuntime.agentToolCatalog
                                                             )
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
                                                                 ?: emptyList(),
                                                             projectId = projectId
                                                         )
                                                         workflowHistory = (
                                                             listOf(runRecord) +
                                                                 workflowHistory.filter { it.projectId == projectId }.take(49) +
                                                                 workflowHistory.filterNot { it.projectId == projectId }
                                                             ).take(200)
                                                         prefs.edit().putString("workflow_runs_v1", WorkflowRunHistoryCodec.encode(workflowHistory)).apply()
                                                         val completed = runHistory + ChatMessage(UUID.randomUUID().toString(), "assistant", assistantText)
                                                         conversations = ConversationPreferencesStore.saveConversationMessages(prefs, conversationId, completed, projectId, conversations)
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
                                                         } finally {
                                                             workflowPauseGate?.complete(Unit)
                                                             workflowPauseGate = null
                                                             workflowPaused = false
                                                             runningWorkflowId = ""
                                                             busy = false
                                                             stopRequested = false
                                                         }
                                                     }
                                                 }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) { Text(if (runningWorkflowId == workflow.id) "执行中…" else "运行") }
                                                 TextButton(onClick = {
                                                     if (!canWriteProjectResource(ProjectResourceKind.WORKFLOW, workflow.id)) return@TextButton
                                                      editingWorkflowId = workflow.id
                                                     workflowDraftName = workflow.name
                                                     workflowDraftDescription = workflow.description
                                                     workflowDraftSteps = workflow.steps.toList()
                                                     showWorkflowDialog = true
                                                 }) { Text("编辑", color = Mint) }
                                                 TextButton(onClick = {
                                                     if (!canWriteProjectResource(ProjectResourceKind.WORKFLOW, workflow.id)) return@TextButton
                                                      workflows = workflows.filterNot { it.id == workflow.id }
                                                     val remainingWorkflowScopes = ProjectResourceScopeStore.removeResourceScope(
                                                         projectResourceScopes, ProjectResourceKind.WORKFLOW, workflow.id
                                                     )
                                                     check(prefs.edit().putString("workflows_v1", WorkflowDefinitionCodec.encode(workflows))
                                                         .putString("project_resource_scopes_v1", ProjectResourceScopeStore.encode(remainingWorkflowScopes))
                                                         .commit()) { "工作流及项目范围删除失败" }
                                                     projectResourceScopes = remainingWorkflowScopes
                                                 }) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                                             }
                                         }
                                     }
                                 }
                                 val activeWorkflowHistory = workflowHistory.filter { it.projectId == activeProjectId }
                                 if (activeWorkflowHistory.isNotEmpty()) {
                                     Text("最近运行记录", color = Ink, style = MaterialTheme.typography.titleMedium)
                                     activeWorkflowHistory.take(5).forEach { record ->
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
                                     OutlinedButton(onClick = {
                                        templateNameDraft = selectedExpert.takeIf { it != "通用助手" } ?: ""
                                        templateSearchDraft = ""
                                        templateCategoryFilter = "全部"
                                        templateStatus = ""
                                        showExpertTemplatesDialog = true
                                    }) { Text("模板库") }
                                }
                                Text("已选专家：${manualExpertSelection.size}/4。可直接临时组队，不必先创建固定团队。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                Button(
                                    enabled = manualExpertSelection.size in 2..4,
                                    onClick = {
                                        selectedTeamId = ""
                                        prefs.edit().remove("selected_team_id").apply()
                                        page = "home"
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
                                ) { Text("用所选专家开始协作（${manualExpertSelection.size}）") }
                                if (expertImportStatus.isNotBlank()) Text(expertImportStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                                if (expertExportStatus.isNotBlank()) Text(expertExportStatus, color = Muted, style = MaterialTheme.typography.bodySmall)

                                experts.filter { !projectResourceScopesReadOnly && ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.EXPERT, it, activeProjectId, readableProjectIds) }.sortedWith(compareBy<String> { expertProfiles[it]?.group ?: "默认" }.thenBy { experts.indexOf(it) }).forEach { name ->
                                    Surface(color = if (selectedExpert == name) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = name in manualExpertSelection,
                                                enabled = expertProfiles[name]?.enabled != false,
                                                onCheckedChange = { checked ->
                                                    manualExpertSelection = if (checked) {
                                                        (manualExpertSelection + name).take(4).toSet()
                                                    } else {
                                                        manualExpertSelection - name
                                                    }
                                                }
                                            )
                                            Column(Modifier.weight(1f).clickable(enabled = expertProfiles[name]?.enabled != false) {
                                                selectedExpert = name
                                                prefs.edit().putString("selected_expert", name).apply()
                                                page = "home"
                                            }) {
                                                Text("${expertProfiles[name]?.avatar ?: "✦"}  $name", color = if (selectedExpert == name) Mint else Ink, style = MaterialTheme.typography.titleMedium)
                                                Text("分组：${expertProfiles[name]?.group ?: "默认"} · ${if (expertProfiles[name]?.enabled == false) "已停用" else "已启用"}", color = Muted, style = MaterialTheme.typography.labelSmall)
                                                expertProfiles[name]?.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                                                val expertIsGlobal = projectResourceScopes[ProjectResourceScopeStore.resourceKey(ProjectResourceKind.EXPERT, name)]?.contains("*") ?: true
                                                TextButton(onClick = { updateProjectResourceScope(ProjectResourceKind.EXPERT, name, expertIsGlobal.not()) }) {
                                                    Text(if (expertIsGlobal) "限定到当前项目" else "设为全局共享", color = Mint)
                                                }
                                                Text(expertPrompts[name]?.take(100)?.takeIf { it.isNotBlank() } ?: ExpertTeamDefaults.rolePrompt(name).ifBlank { "尚未设置角色指令" }.take(100), color = Muted, style = MaterialTheme.typography.bodySmall)
                                            }
                                            TextButton(onClick = {
                                                if (!canWriteProjectResource(ProjectResourceKind.EXPERT, name)) return@TextButton
                                                editingExpertName = name
                                                expertDraft = name
                                                expertPromptDraft = expertPrompts[name].orEmpty()
                                                if (!canWriteProjectResource(ProjectResourceKind.EXPERT, name)) return@TextButton
                                                val profile = expertProfiles[name] ?: ExpertProfile()
                                                expertGroupDraft = profile.group
                                                expertAvatarDraft = profile.avatar
                                                expertEnabledDraft = profile.enabled
                                                expertSkillDraft = profile.skillNames.filter { skillName ->
                                                    !projectResourceScopesReadOnly && ProjectResourceScopeStore.isVisibleFromProjects(
                                                        projectResourceScopes, ProjectResourceKind.SKILL, skillName, activeProjectId, readableProjectIds
                                                    )
                                                }.toSet()
                                                expertTemperatureDraft = profile.temperature?.toString().orEmpty()
                                                expertMaxTokensDraft = profile.maxTokens?.toString().orEmpty()
                                                expertDescriptionDraft = profile.description
                                                expertDialogError = ""
                                                showExpertDialog = true
                                            }) { Text("编辑", color = Mint) }
                                            TextButton(onClick = {
                                                if (!canWriteProjectResource(ProjectResourceKind.EXPERT, name)) return@TextButton
                                                val profile = expertProfiles[name] ?: ExpertProfile()
                                                expertProfiles = expertProfiles + (name to profile.copy(enabled = !profile.enabled))
                                                prefs.edit().putString("expert_profiles_v1", ExpertProfileCodec.encode(expertProfiles)).apply()
                                                if (profile.enabled && selectedExpert == name) {
                                                    selectedExpert = "通用助手"
                                                    prefs.edit().putString("selected_expert", selectedExpert).apply()
                                                }
                                            }) { Text(if (expertProfiles[name]?.enabled == false) "启用" else "停用", color = if (expertProfiles[name]?.enabled == false) Mint else Muted) }
                                            TextButton(onClick = {
                                                if (!canWriteProjectResource(ProjectResourceKind.EXPERT, name)) return@TextButton
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
                                                if (!canWriteProjectResource(ProjectResourceKind.EXPERT, name)) return@TextButton
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
                                                    if (!canWriteProjectResource(ProjectResourceKind.EXPERT, name)) return@TextButton
                                                     if (!canWriteExpertDependents(name)) return@TextButton
                                                     experts = experts - name
                                                    expertPrompts = expertPrompts - name
                                                    expertProfiles = expertProfiles - name
                                                     workflows = workflows.map { workflow -> workflow.copy(steps = workflow.steps.map { step -> if (step.kind == WorkflowStepKind.EXPERT && step.targetId.ifBlank { step.title } == name) step.copy(enabled = false) else step }) }
                                                    val previousTeamIds = expertTeams.map { it.id }.toSet()
                                                    expertTeams = expertTeams.map { it.copy(members = it.members.filterNot { member -> member == name }) }.filter { it.members.size >= 2 }
                                                    val removedTeamIds = previousTeamIds - expertTeams.map { it.id }.toSet()
                                                    if (expertTeams.none { it.id == selectedTeamId }) selectedTeamId = ""
                                                    val remainingExpertScopes = removedTeamIds.fold(
                                                        ProjectResourceScopeStore.removeResourceScope(projectResourceScopes, ProjectResourceKind.EXPERT, name)
                                                    ) { scopes, teamId ->
                                                        ProjectResourceScopeStore.removeResourceScope(scopes, ProjectResourceKind.TEAM, teamId)
                                                    }
                                                    prefs.edit().putString("experts", JSONArray(experts).toString())
                                                        .putString("project_resource_scopes_v1", ProjectResourceScopeStore.encode(remainingExpertScopes))
                                                        .putString("expert_prompts", expertPromptsToJson(expertPrompts))
                                                        .putString("expert_profiles_v1", ExpertProfileCodec.encode(expertProfiles))
                                                        .putString("expert_teams_v1", expertTeamsToJson(expertTeams))
                                                         .putString("workflows_v1", WorkflowDefinitionCodec.encode(workflows))
                                                        .putString("selected_team_id", selectedTeamId).commit()
                                                    projectResourceScopes = remainingExpertScopes
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
                                val activeTeamRunHistory = expertTeamRunHistory.filter { it.projectId == activeProjectId }
                                if (activeTeamRunHistory.isNotEmpty()) {
                                    val latestTeamRun=activeTeamRunHistory.first()
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
                                if (expertTeamEvents.isNotEmpty()) {
                                    Surface(color = Card, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("最近协作过程", color = Ink, style = MaterialTheme.typography.titleSmall)
                                            expertTeamEvents.takeLast(16).asReversed().forEach { event ->
                                                val who = listOf(event.memberName, event.relatedMemberName).filter { it.isNotBlank() }.joinToString(" → ")
                                                Text(
                                                    "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.createdAt))} · ${event.type} · $who · ${event.status}",
                                                    color = Mint, style = MaterialTheme.typography.labelSmall
                                                )
                                                if (event.message.isNotBlank()) {
                                                    Text(event.message.take(240), color = Muted, style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { importTeamLauncher.launch(arrayOf("application/json")) }) { Text("导入团队") }
                                }
                                if (teamImportStatus.isNotBlank()) Text(teamImportStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                                if (teamExportStatus.isNotBlank()) Text(teamExportStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                                expertTeams.filter { !projectResourceScopesReadOnly && ProjectResourceScopeStore.isTeamVisibleFromProjects(projectResourceScopes, it.id, it.members, activeProjectId, readableProjectIds) }.forEach { team ->
                                    Surface(color = if (team.id == selectedTeamId) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(team.name, color = if (team.id == selectedTeamId) Mint else Ink, style = MaterialTheme.typography.titleMedium)
                                            Text(team.description, color = Muted, style = MaterialTheme.typography.bodySmall)
                                            Text("成员：${team.members.joinToString("、")}", color = Muted, style = MaterialTheme.typography.bodySmall)
                                            val teamIsGlobal = projectResourceScopes[ProjectResourceScopeStore.resourceKey(ProjectResourceKind.TEAM, team.id)]?.contains("*") ?: true
                                            TextButton(onClick = { updateProjectResourceScope(ProjectResourceKind.TEAM, team.id, teamIsGlobal.not()) }) {
                                                Text(if (teamIsGlobal) "限定到当前项目" else "设为全局共享", color = Mint)
                                            }
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
                                                    if (!canWriteProjectResource(ProjectResourceKind.TEAM, team.id)) return@TextButton
                                                    editingTeamId = team.id
                                                    teamDraftName = team.name
                                                    teamDraftDescription = team.description
                                                    teamDraftMembers = team.members.toSet()
                                                    showTeamDialog = true
                                                }) { Text("编辑", color = Mint) }
                                                TextButton(onClick = {
                                                    if (!canWriteProjectResource(ProjectResourceKind.TEAM, team.id)) return@TextButton
                                                    expertTeams = expertTeams.filterNot { it.id == team.id }
                                                    if (selectedTeamId == team.id) selectedTeamId = ""
                                                    val remainingTeamScopes = ProjectResourceScopeStore.removeResourceScope(
                                                        projectResourceScopes, ProjectResourceKind.TEAM, team.id
                                                    )
                                                    prefs.edit().putString("expert_teams_v1", expertTeamsToJson(expertTeams))
                                                        .putString("project_resource_scopes_v1", ProjectResourceScopeStore.encode(remainingTeamScopes))
                                                        .putString("selected_team_id", selectedTeamId).commit()
                                                    projectResourceScopes = remainingTeamScopes
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
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("系统默认助手", color = Ink, style = MaterialTheme.typography.titleMedium)
                                        val assistantAvailable = app.nestro.phone.AndroidAssistantRoleSupport.isAvailable(context)
                                        val assistantHeld = app.nestro.phone.AndroidAssistantRoleSupport.isHeld(context)
                                        Text(
                                            when {
                                                assistantHeld -> "Nestro 已获得系统助手角色。具体唤起方式取决于 Android 版本和设备厂商。"
                                                assistantAvailable -> "可向 Android 请求系统助手角色；是否可用由系统角色管理器和设备厂商决定。"
                                                else -> "当前系统未开放系统助手角色，仍可通过 Nestro 应用内聊天入口使用。"
                                            },
                                            color = Muted, style = MaterialTheme.typography.bodySmall
                                        )
                                        Button(
                                            enabled = assistantAvailable && !assistantHeld,
                                            onClick = {
                                                val requestIntent = app.nestro.phone.AndroidAssistantRoleSupport.createRequestIntent(context)
                                                if (requestIntent != null) assistantRoleLauncher.launch(requestIntent)
                                                else status = "当前设备不支持申请系统助手角色，或 Nestro 已经是默认助手。"
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
                                        ) { Text(if (assistantHeld) "已设为系统助手" else "设为系统默认助手") }
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
                                    ConversationPreferencesStore.saveConversationList(prefs, conversations)
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
                                                        val loadedMessages = appRuntime.conversationPersistenceService.loadMessages(record.id)
                                                            ?: conversations.firstOrNull { it.id == record.id }?.messages.orEmpty()
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
                                                        if (!canDeleteConversation(record.id, activeRequestConversationId, busy)) {
                                                            status = "该会话正在生成，请先停止生成后再删除"
                                                            return@launch
                                                        }
                                                        val remaining = conversations.filterNot { it.id == record.id }
                                                        if (activeConversationId == record.id) {
                                                            val next = remaining.firstOrNull { it.projectId == record.projectId }
                                                                ?: ConversationRecord(UUID.randomUUID().toString(), "新对话", System.currentTimeMillis(), emptyList(), record.projectId)
                                                            conversations = if (remaining.any { it.id == next.id }) remaining else listOf(next) + remaining
                                                            activeConversationId = next.id
                                                            activeProjectId = record.projectId
                                                            val nextMessages = if (databaseReady && remaining.any { it.id == next.id }) {
                                                                withContext(Dispatchers.IO) {
                                                                    appRuntime.conversationPersistenceService.loadMessages(next.id).orEmpty()
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
                                                        ConversationPreferencesStore.saveConversationList(prefs, conversations)
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
                                                            appRuntime.conversationPersistenceService.loadConversationPage(
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
                                TextButton(
                                    onClick = {
                                        globalMemory = ""
                                        memoryDraft = ""
                                        check(prefs.edit().remove("global_memory_v1").commit())
                                        memoryStatus = "全局记忆已清空"
                                    },
                                    enabled = globalMemory.isNotBlank() || memoryDraft.isNotBlank()
                                ) { Text("清空全局记忆", color = ColorStop) }
                                Text("自动记忆 · 当前项目", color = Ink, style = MaterialTheme.typography.titleMedium)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { importMemoryLauncher.launch(arrayOf("application/json", "text/plain")) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("导入单条记忆 JSON") }
                                    TextButton(
                                        onClick = { showClearAutomaticMemoryConfirm = true },
                                        enabled = automaticMemories.isNotEmpty()
                                    ) { Text("清空本项目", color = ColorStop) }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.weight(1f)) {
                                        Text("允许自动提取记忆", color = Ink)
                                        Text("关闭时不会额外发送记忆提取请求；开启后，符合条件的消息会再次发送给你配置的模型，候选结果经过本地过滤后保存。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(
                                        checked = automaticMemoryEnabled,
                                        onCheckedChange = { enabled ->
                                            automaticMemoryEnabled = enabled
                                            ProjectMemorySettingsStore.setEnabled(prefs, activeProjectId, enabled)
                                        }
                                    )
                                }
                                Text("自动记忆仅保存在本机。敏感内容会在本地进行基础规则预检，但规则无法保证识别所有敏感信息。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                Text("本地语义检索", color = Ink, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "可选下载 BGE-small-zh-v1.5 量化模型（下载文件限制 35 MB）到本机。安装后，向量在本机生成和缓存，不会单独上传；被召回的记忆文本会随聊天请求发送给当前配置的模型。不安装时仍可使用关键词检索。",
                                    color = Muted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                embeddingModelInstalling = true
                                                embeddingModelStatus = "正在下载并校验本地语义模型…"
                                                try {
                                                    val result = runCatchingCancellable {
                                                        withContext(Dispatchers.IO) {
                                                            KnowledgeEmbeddingModelManager.install(context)
                                                        }
                                                    }
                                                    embeddingModelInstalled = withContext(Dispatchers.IO) {
                                                        KnowledgeEmbeddingModelManager.isInstalled(context)
                                                    }
                                                    embeddingModelStatus = result.fold(
                                                        { "模型安装完成，语义检索已启用。" },
                                                        { "模型安装失败：${it.message ?: "未知错误"}；关键词检索仍可使用。" }
                                                    )
                                                } finally {
                                                    embeddingModelInstalling = false
                                                }
                                            }
                                        },
                                        enabled = !embeddingModelInstalling && !embeddingModelInstalled,
                                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
                                    ) {
                                        Text(if (embeddingModelInstalling) "正在安装…" else if (embeddingModelInstalled) "已安装" else "下载并安装模型")
                                    }
                                    if (embeddingModelInstalling) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    }
                                }
                                Text(embeddingModelStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                                if (automaticMemories.isEmpty()) {
                                    Text("当前项目还没有自动记忆。明确表达长期偏好或项目约束后，会在后台尝试提取。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                } else {
                                    automaticMemories.forEach { memory ->
                                        Surface(color = Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                val sourceConversation = conversations.firstOrNull { it.id == memory.sourceConversationId }
                                                val canOpenSource = sourceConversation != null &&
                                                    (sourceConversation.projectId == activeProjectId || sourceConversation.projectId in readableProjectIds)
                                                var categoryMenuExpanded by remember(memory.id) { mutableStateOf(false) }
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("分类：${memory.category} · 置信度 ${"%.0f".format(memory.confidence * 100)}%", color = Mint, style = MaterialTheme.typography.labelSmall)
                                                    Spacer(Modifier.weight(1f))
                                                    TextButton(onClick = { categoryMenuExpanded = true }) { Text("修改分类") }
                                                    DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                                                        listOf("preference", "workflow", "project", "constraint").forEach { categoryOption ->
                                                            DropdownMenuItem(
                                                                text = { Text(categoryOption) },
                                                                onClick = {
                                                                    categoryMenuExpanded = false
                                                                    scope.launch {
                                                                        val result = withContext(Dispatchers.IO) {
                                                                            runCatching {
                                                                                appRuntime.userMemoryRepository.edit(
                                                                                    memory.id, activeProjectId,
                                                                                    memoryEdits[memory.id] ?: memory.content, categoryOption
                                                                                )
                                                                            }
                                                                        }
                                                                        if (result.isSuccess) {
                                                                            automaticMemories = withContext(Dispatchers.IO) { appRuntime.userMemoryRepository.list(activeProjectId) }
                                                                            memoryEdits = memoryEdits - memory.id
                                                                            memoryStatus = "记忆分类已更新"
                                                                        } else {
                                                                            memoryStatus = "分类更新失败：${result.exceptionOrNull()?.message ?: "分类无效"}"
                                                                        }
                                                                    }
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                                OutlinedTextField(
                                                    value = memoryEdits[memory.id] ?: memory.content,
                                                    onValueChange = { memoryEdits = memoryEdits + (memory.id to it) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    minLines = 2
                                                )
                                                Text("来源会话：${memory.sourceConversationId.take(8)} · 消息：${memory.sourceMessageId.take(8)}", color = Muted, style = MaterialTheme.typography.labelSmall)
                                                Text("创建：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(memory.createdAt))} · 更新：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(memory.updatedAt))}", color = Muted, style = MaterialTheme.typography.labelSmall)
                                                TextButton(
                                                    onClick = {
                                                        val source = conversations.firstOrNull { it.id == memory.sourceConversationId }
                                                            ?.takeIf { it.projectId == activeProjectId || it.projectId in readableProjectIds }
                                                        if (source != null) {
                                                            scope.launch {
                                                                val sourceMessages = if (databaseReady) {
                                                                    withContext(Dispatchers.IO) {
                                                                        appRuntime.conversationPersistenceService.loadMessages(source.id).orEmpty()
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
                                                    enabled = canOpenSource
                                                ) { Text("打开来源会话", color = Mint) }
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Button(onClick = {
                                                        scope.launch {
                                                            val result = withContext(Dispatchers.IO) {
                                                                runCatching {
                                                                    appRuntime.userMemoryRepository.edit(
                                                                        memory.id, activeProjectId,
                                                                        memoryEdits[memory.id] ?: memory.content, memory.category
                                                                    )
                                                                }
                                                            }
                                                            memoryStatus = result.fold({ "记忆已更新" }, { "更新失败：${it.message ?: "内容无效"}" })
                                                            if (result.isSuccess) {
                                                                automaticMemories = withContext(Dispatchers.IO) { appRuntime.userMemoryRepository.list(activeProjectId) }
                                                                memoryEdits = memoryEdits - memory.id
                                                            }
                                                        }
                                                    }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) { Text("保存修改") }
                                                    TextButton(onClick = {
                                                        runCatching {
                                                            val send = Intent(Intent.ACTION_SEND).apply {
                                                                type = "application/json"
                                                                putExtra(Intent.EXTRA_TEXT, UserMemoryExportCodec.encode(memory))
                                                            }
                                                            context.startActivity(Intent.createChooser(send, "导出单条记忆"))
                                                        }.onFailure { memoryStatus = "导出失败：${it.message ?: "数据无效"}" }
                                                    }) { Text("导出", color = Mint) }
                                                    TextButton(onClick = {
                                                        scope.launch {
                                                            val result = runCatchingCancellable {
                                                                withContext(Dispatchers.IO) {
                                                                    appRuntime.userMemoryRepository.delete(memory.id, activeProjectId)
                                                                }
                                                            }
                                                            if (result.isSuccess) {
                                                                automaticMemories = automaticMemories.filterNot { it.id == memory.id }
                                                                memoryEdits = memoryEdits - memory.id
                                                                memoryStatus = "记忆已删除"
                                                            } else {
                                                                memoryStatus = "删除失败：${result.exceptionOrNull()?.message ?: "记忆可能已被删除"}"
                                                            }
                                                        }
                                                    }) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (memoryStatus.isNotBlank()) Text(memoryStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                                if (showClearAutomaticMemoryConfirm) {
                                    AlertDialog(
                                        onDismissRequest = { showClearAutomaticMemoryConfirm = false },
                                        title = { Text("清空当前项目自动记忆？") },
                                        text = { Text("这会删除当前项目的全部自动记忆，不影响其他项目和手动全局记忆。此操作无法撤销。") },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                showClearAutomaticMemoryConfirm = false
                                                scope.launch {
                                                    val result = withContext(Dispatchers.IO) {
                                                        runCatching { appRuntime.userMemoryRepository.clearProject(activeProjectId) }
                                                    }
                                                    if (result.isSuccess) {
                                                        automaticMemories = emptyList()
                                                        memoryEdits = emptyMap()
                                                        memoryStatus = "当前项目自动记忆已全部清空"
                                                    } else {
                                                        memoryStatus = "清空失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                                                    }
                                                }
                                            }) { Text("确认清空", color = ColorStop) }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showClearAutomaticMemoryConfirm = false }) { Text("取消") }
                                        }
                                    )
                                }
                            }
                        }
                        "spaces" -> {
                            Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("项目空间", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("每个空间拥有独立会话与项目指令。切换空间不会把其他项目的聊天记录注入当前请求。", color = Muted)
                                if (projectStorageReadOnly) {
                                    Text("项目空间数据校验失败：原始数据已保留，当前仅允许查看和切换默认空间。请先导出备份并修复数据。", color = ColorStop)
                                }
                                if (projectResourceScopesReadOnly) {
                                    Text("专家、团队、技能和工作流的项目归属数据校验失败，已停止加载这些资源以防跨项目泄漏。请恢复有效备份后再使用。", color = ColorStop)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(enabled = deletingProjectId.isBlank() && !projectStorageReadOnly, onClick = {
                                        editingProjectId = ""
                                        projectDraftName = ""
                                        projectDraftInstructions = ""
                                        showProjectDialog = true
                                    }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                                        Text("＋ 创建项目")
                                    }
                                    OutlinedButton(enabled = deletingProjectId.isBlank(), onClick = {
                                        importProjectPackageLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                                    }, modifier = Modifier.weight(1f)) { Text("导入项目包") }
                                }
                                if (projectPackageStatus.isNotBlank()) Text(projectPackageStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(projects.filter { it.archivedAt == 0L }.sortedByDescending { prefs.getLong("project_last_opened_${it.id}", it.createdAt) }, key = { it.id }) { project ->
                                        val count = conversations.count { it.projectId == project.id }
                                        Surface(color = if (project.id == activeProjectId) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Column(Modifier.weight(1f).clickable(enabled = deletingProjectId.isBlank()) {
                                                        scope.launch {
                                                            activeProjectId = project.id
                                                            readableProjectIds = readableSourcesFor(project.id)
                                                            writableProjectIds = writableSourcesFor(project.id)
                                                            automaticMemoryEnabled = ProjectMemorySettingsStore.isEnabled(prefs, project.id)
                                                            if (!projectResourceScopesReadOnly &&
                                                                !ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.EXPERT, selectedExpert, project.id, readableProjectIds)) {
                                                                selectedExpert = "通用助手"
                                                                prefs.edit().putString("selected_expert", selectedExpert).apply()
                                                            }
                                                            if (!projectResourceScopesReadOnly &&
                                                                !ProjectResourceScopeStore.isTeamVisibleFromProjects(projectResourceScopes, selectedTeamId,
                                                                    expertTeams.firstOrNull { it.id == selectedTeamId }?.members.orEmpty(), project.id, readableProjectIds)) {
                                                                selectedTeamId = ""
                                                                prefs.edit().putString("selected_team_id", "").apply()
                                                            }
                                                            if (!projectResourceScopesReadOnly &&
                                                                !ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.SKILL, selectedSkill, project.id, readableProjectIds)) {
                                                                selectedSkill = ""
                                                            }
                                                            prefs.edit().putString("active_project_id", project.id)
                                                                .putLong("project_last_opened_${project.id}", System.currentTimeMillis()).apply()
                                                            val target = conversations.firstOrNull { it.projectId == project.id }
                                                            if (target == null) {
                                                                val created = ConversationRecord(UUID.randomUUID().toString(), "新对话", System.currentTimeMillis(), emptyList(), project.id)
                                                                conversations = listOf(created) + conversations
                                                                ConversationPreferencesStore.saveConversationList(prefs, conversations)
                                                                activeConversationId = created.id
                                                                messages = emptyList()
                                                            } else {
                                                                activeConversationId = target.id
                                                                val targetMessages = if (databaseReady) {
                                                                    withContext(Dispatchers.IO) {
                                                                        appRuntime.conversationPersistenceService.loadMessages(target.id).orEmpty()
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
                                                    TextButton(enabled = deletingProjectId.isBlank() && !projectStorageReadOnly, onClick = {
                                                        editingProjectId = project.id
                                                        projectDraftName = project.name
                                                        projectDraftInstructions = project.instructions
                                                        showProjectDialog = true
                                                    }) { Text("编辑", color = Mint) }
                                                    TextButton(enabled = deletingProjectId.isBlank(), onClick = {
                                                        projectPackageExportProjectId = project.id
                                                        val safeName = project.name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40).ifBlank { "project" }
                                                        exportProjectPackageLauncher.launch(safeName + ".nestro-project.json")
                                                    }) { Text("导出项目包", color = Mint) }
                                                    TextButton(
                                                        enabled = deletingProjectId.isBlank() && !projectStorageReadOnly && !projectResourceScopesReadOnly && projects.size < 100,
                                                        onClick = {
                                                            val baseName = "${project.name} 副本".take(40)
                                                            val existingNames = projects.map { it.name.trim().lowercase() }.toSet()
                                                            var copyName = baseName
                                                            var suffix = 2
                                                            while (copyName.trim().lowercase() in existingNames && suffix <= 100) {
                                                                val tail = " 副本$suffix"
                                                                copyName = project.name.take((40 - tail.length).coerceAtLeast(1)) + tail
                                                                suffix++
                                                            }
                                                            val copyId = UUID.randomUUID().toString()
                                                            scope.launch {
                                                                val artifactCopy = runCatching {
                                                                    withContext(Dispatchers.IO) {
                                                                        ArtifactStore.duplicateProject(context, project.id, copyId)
                                                                        MediaProjectStore.duplicateProject(context, project.id, copyId)
                                                                    }
                                                                }
                                                                if (artifactCopy.isFailure) {
                                                                    withContext(Dispatchers.IO) {
                                                                        runCatching { ArtifactStore.deleteProject(context, copyId) }
                                                                        runCatching { MediaProjectStore.deleteProject(context, copyId) }
                                                                    }
                                                                    status = "项目复制中止：项目产物或媒体素材复制失败，未创建新项目。"
                                                                    return@launch
                                                                }
                                                                val copyAttempt = runCatching {
                                                                    withContext(Dispatchers.IO) {
                                                                        appRuntime.projectSpaceRepository.duplicate(
                                                                            project.id, copyId, copyName, System.currentTimeMillis()
                                                                        )
                                                                    }
                                                                }
                                                                val copied = copyAttempt.getOrDefault(false)
                                                                if (!copied) {
                                                                    withContext(Dispatchers.IO) {
                                                                        runCatching { ArtifactStore.deleteProject(context, copyId) }
                                                                        runCatching { MediaProjectStore.deleteProject(context, copyId) }
                                                                    }
                                                                }
                                                                if (copied) {
                                                                    val originalScopes = projectResourceScopes
                                                                    val finalizeCopy = runCatching {
                                                                        val copiedScopes = ProjectResourceScopeStore.duplicateProjectScope(
                                                                            originalScopes, project.id, copyId
                                                                        )
                                                                        ProjectResourceScopeStore.save(prefs, copiedScopes)
                                                                        projectResourceScopes = copiedScopes
                                                                        ProjectMemorySettingsStore.duplicateProject(prefs, project.id, copyId)
                                                                        val restored = withContext(Dispatchers.IO) {
                                                                            appRuntime.conversationPersistenceService.restore(prefs)
                                                                        }
                                                                        restored
                                                                    }
                                                                    finalizeCopy.onSuccess { restored ->
                                                                        projects = restored.projects
                                                                        conversations = restored.conversations
                                                                        status = "已复制「${project.name}」及其会话、知识库、项目记忆和媒体素材。"
                                                                    }.onFailure { error ->
                                                                        withContext(Dispatchers.IO) {
                                                                            runCatching { appRuntime.projectSpaceRepository.rollbackDuplicate(copyId) }
                                                                            runCatching { ArtifactStore.deleteProject(context, copyId) }
                                                                            runCatching { MediaProjectStore.deleteProject(context, copyId) }
                                                                        }
                                                                        runCatching { ProjectResourceScopeStore.save(prefs, originalScopes) }
                                                                        projectResourceScopes = originalScopes
                                                                        runCatching { ProjectMemorySettingsStore.removeProject(prefs, copyId) }
                                                                        status = "项目复制后续保存失败，已执行回滚：" + (error.message ?: "未知错误")
                                                                    }
                                                                } else {
                                                                    status = "复制失败：项目不存在、名称重复或项目空间数量已达上限。"
                                                                }
                                                            }
                                                        }
                                                    ) { Text("复制", color = Mint) }
                                                    if (project.id != "default") {
                                                        TextButton(
                                                            enabled = deletingProjectId.isBlank() && !projectStorageReadOnly && ProjectSpaceRules.canArchive(project.id, activeProjectId),
                                                            onClick = {
                                                                val archivedAt = System.currentTimeMillis()
                                                                projects = projects.map { if (it.id == project.id) it.copy(archivedAt = archivedAt) else it }
                                                                ProjectPreferencesStore.saveProjects(prefs, projects)
                                                                status = "项目已归档，原有会话、知识库、记忆和任务均保留；恢复后可继续使用。"
                                                            }
                                                        ) { Text("归档", color = Muted) }
                                                        TextButton(
                                                            enabled = deletingProjectId.isBlank() && !projectStorageReadOnly && !projectResourceScopesReadOnly,
                                                            onClick = {
                                                            if (projectStorageReadOnly || projectResourceScopesReadOnly) {
                                                                status = "项目数据或资源范围校验失败，已锁定删除操作。"
                                                            } else if (project.id == activeProjectId) {
                                                                status = "当前空间正在使用，请先切换到其他空间再删除"
                                                            } else if (!prefs.edit().putString("pending_project_delete_id", project.id).commit()) {
                                                                status = "删除保护标记写入失败，未执行删除"
                                                            } else {
                                                                deletingProjectId = project.id
                                                                scope.launch {
                                                                    var projectConversationIds = emptySet<String>()
                                                                    runCatching {
                                                                        withContext(Dispatchers.IO) {
                                                                            projectConversationIds = (conversations.filter { it.projectId == project.id }.map { it.id } +
                                                                                appRuntime.projectSpaceRepository.conversationIds(project.id)).toSet()
                                                                            val latestActiveProjectId = prefs.getString("active_project_id", activeProjectId) ?: activeProjectId
                                                                            val hasDraft = projectConversationIds.any { id -> (prefs.getString("draft_$id", "") ?: "").isNotBlank() }
                                                                            val hasLocalConversationData = conversations.any {
                                                                                it.projectId == project.id && (it.messages.isNotEmpty() || it.title != "新对话")
                                                                            }
                                                                            check(ArtifactStore.deleteProjectIfEmpty(context, project.id)) {
                                                                                "该空间还有项目产物或未登记文件，请先导出、清理后再删除项目"
                                                                            }
                                                                            check(!MediaProjectStore.hasProjectMedia(context, project.id)) {
                                                                                "该空间还有媒体素材、时间线或导出文件，请先导出并清理媒体项目后再删除"
                                                                            }
                                                                            check(ProjectResourceScopeStore.resourcesOwnedOnlyByProject(projectResourceScopes, project.id).isEmpty()) {
                                                                                "该空间仍有专属专家、团队、技能或工作流，请先转为全局共享或删除这些资源"
                                                                            }
                                                                            val hasLinkedData = appRuntime.projectSpaceRepository.hasLinkedData(project.id)
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
                                                                            MediaProjectStore.deleteProject(context, project.id)
                                                                            check(appRuntime.projectSpaceRepository.deleteIfEmpty(project.id, latestActiveProjectId)) {
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
                                                                        ConversationPreferencesStore.saveConversationList(prefs, conversations)
                                                                        projects = projects.filterNot { it.id == project.id }
                                                                        ProjectPreferencesStore.saveProjects(prefs, projects)
                                                                        val remainingScopes = ProjectResourceScopeStore.removeProjectScope(projectResourceScopes, project.id)
                                                                        ProjectResourceScopeStore.save(prefs, remainingScopes)
                                                                        projectResourceScopes = remainingScopes
                                                                        ProjectMemorySettingsStore.removeProject(prefs, project.id)
                                                                        ProjectAccessControlStore.removeProject(prefs, project.id)
                                                                        workflowHistory = workflowHistory.filterNot { it.projectId == project.id }
                                                                        expertTeamRunHistory = expertTeamRunHistory.filterNot { it.projectId == project.id }
                                                                        val editor = prefs.edit()
                                                                        projectConversationIds.forEach { id -> editor.remove("draft_$id") }
                                                                        editor.putString("workflow_runs_v1", WorkflowRunHistoryCodec.encode(workflowHistory))
                                                                            .putString("expert_team_runs_v1", ExpertTeamRunHistoryCodec.encode(expertTeamRunHistory))
                                                                        editor.remove("project_last_opened_${project.id}")
                                                                        val markerCleared = editor.remove("pending_project_delete_id").commit()
                                                                        status = if (markerCleared) "空项目空间已删除" else "项目已删除，启动时会自动清理残留标记"
                                                                    }.onFailure {
                                                                        if (it is java.util.concurrent.CancellationException) {
                                                                            status = "删除操作中断，下次启动会自动核对删除状态"
                                                                        } else {
                                                                            val projectStillExists = withContext(Dispatchers.IO) {
                                                                                appRuntime.projectSpaceRepository.exists(project.id)
                                                                            }
                                                                            if (projectStillExists) {
                                                                                prefs.edit().remove("pending_project_delete_id").commit()
                                                                                status = it.message ?: "删除失败，项目空间已保留"
                                                                            } else {
                                                                                status = "项目主体已删除，剩余设置清理未完成；启动时会继续清理"
                                                                            }
                                                                        }
                                                                    }.also {
                                                                        deletingProjectId = ""
                                                                    }
                                                                }
                                                            }
                                                        }) {
                                                            Text(if (deletingProjectId == project.id) "删除中…" else "删除", color = ColorStop)
                                                        }
                                                    }
                                                }
                                                if (project.id != activeProjectId) {
                                                    val currentGrant = runCatching {
                                                        ProjectAccessControlStore.loadGrants(prefs).firstOrNull {
                                                            it.sourceProjectId == project.id && it.targetProjectId == activeProjectId
                                                        }
                                                    }.getOrNull()
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Text(
                                                            if (currentGrant == null) "当前项目尚未获得此空间的访问权限"
                                                            else "当前项目权限：" + currentGrant.access.joinToString("、") { if (it == ProjectAccessControlStore.Access.READ) "读取" else "写入" },
                                                            color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                                                        )
                                                        TextButton(enabled = !projectResourceScopesReadOnly, onClick = {
                                                            pendingAccessSourceProjectId = project.id
                                                            showProjectAccessDialog = true
                                                        }) { Text("管理授权", color = Mint) }
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
                                                        enabled = deletingProjectId.isBlank() && !projectStorageReadOnly && ProjectSpaceRules.canRestore(project.id),
                                                        onClick = {
                                                            projects = projects.map { if (it.id == project.id) it.copy(archivedAt = 0L) else it }
                                                            ProjectPreferencesStore.saveProjects(prefs, projects)
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
                        "calendar" -> CalendarPage(appRuntime.calendarRepository)
                        "phone" -> PhoneAgentPage(context, activeProjectId)
                        "artifacts" -> ArtifactLibraryPage(activeProjectId)
                        "media-editor" -> MediaEditorPage(context, activeProjectId, pendingOpenMediaAssetId) { prompt ->
                            draft = prompt
                            page = "home"
                        }
                        "capabilities" -> CapabilityDirectoryPage()
                        "mcp" -> McpToolCenterPage(appRuntime)
                        "plugins" -> GeneratedPluginCenterPage(
                            application = appRuntime,
                            configs = requestModelConfigs,
                            onUsage = recordModelUsage,
                            onProviderUsage = recordProviderModelUsage,
                            onChanged = { }
                        )
                        "git" -> GitWorkbenchPage(context)
                        "coworkbench" -> AiCoworkbenchPage(
                            onBack = { page = "home" },
                            initialPatchJson = coworkbenchGeneratedPatch,
                            onPatchConsumed = { coworkbenchGeneratedPatch = "" },
                            onUseChatPrompt = { prompt -> pendingCoworkbenchPrompt = prompt; draft = prompt; page = "home" }
                        )
                        "voice" -> VoiceSystemPage()
                        "knowledge" -> KnowledgePage(appRuntime.knowledgeRepository, activeProjectId, readableProjectIds, writableProjectIds)
                        "skills" -> {
                            Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("本地技能库", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("技能是可复用的工作指令。导入包只读取文本和元数据，不执行其中代码，也不会自动授予权限。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                skills.filter { (name, _) -> !projectResourceScopesReadOnly && ProjectResourceScopeStore.isVisibleFromProjects(projectResourceScopes, ProjectResourceKind.SKILL, name, activeProjectId, readableProjectIds) }.forEach { (name, prompt) ->
                                    val isDisabled = name in disabledSkills
                                    Surface(color = if (selectedSkill == name && !isDisabled) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(name, color = if (isDisabled) Muted else if (selectedSkill == name) Mint else Ink, style = MaterialTheme.typography.titleMedium)
                                                    Text(prompt.take(100), color = Muted, style = MaterialTheme.typography.bodySmall)
                                                    val skillIsGlobal = projectResourceScopes[ProjectResourceScopeStore.resourceKey(ProjectResourceKind.SKILL, name)]?.contains("*") ?: true
                                                    TextButton(onClick = { updateProjectResourceScope(ProjectResourceKind.SKILL, name, skillIsGlobal.not()) }) {
                                                        Text(if (skillIsGlobal) "限定到当前项目" else "设为全局共享", color = Mint)
                                                    }
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
                                                    if (!canWriteProjectResource(ProjectResourceKind.SKILL, name)) return@TextButton
                                                    editingSkillName = name
                                                    skillDraftName = name
                                                    skillDraftPrompt = prompt
                                                    skillDialogError = ""
                                                    showSkillDialog = true
                                                }) { Text("编辑", color = Mint) }
                                                TextButton(onClick = {
                                                    if (!canWriteProjectResource(ProjectResourceKind.SKILL, name)) return@TextButton
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
                                                    if (!canWriteProjectResource(ProjectResourceKind.SKILL, name)) return@TextButton
                                                     if (!canWriteSkillDependents(name)) return@TextButton
                                                     skills = skills - name
                                                    skillMetadata = skillMetadata - name
                                                    disabledSkills = disabledSkills - name
                                                    expertProfiles = expertProfiles.mapValues { (_, profile) -> profile.copy(skillNames = profile.skillNames - name) }
                                                    expertRevisions = expertRevisions.mapValues { (_, rows) -> rows.map { it.copy(profile = it.profile.copy(skillNames = it.profile.skillNames - name)) } }
                                                    expertTemplates = expertTemplates.map { it.copy(profile = it.profile.copy(skillNames = it.profile.skillNames - name)) }
                                                    workflows = workflows.map { workflow -> workflow.copy(steps = workflow.steps.map { step -> if (step.kind == WorkflowStepKind.SKILL && step.targetId.ifBlank { step.title } == name) step.copy(enabled = false) else step }) }
                                                    val remainingSkillScopes = ProjectResourceScopeStore.removeResourceScope(
                                                        projectResourceScopes, ProjectResourceKind.SKILL, name
                                                    )
                                                    prefs.edit().putString("skills",skillMapToJson(skills))
                                                        .putString("project_resource_scopes_v1", ProjectResourceScopeStore.encode(remainingSkillScopes))
                                                        .putString("skill_metadata_v1",skillMetadataToJson(skillMetadata))
                                                        .putString("disabled_skills_v1",disabledSkillsToJson(disabledSkills))
                                                        .putString("expert_profiles_v1", ExpertProfileCodec.encode(expertProfiles))
                                                        .putString("expert_versions_v1", ExpertRevisionCodec.encode(expertRevisions))
                                                        .putString("expert_templates_v1", ExpertTemplateCodec.encode(expertTemplates))
                                                        .putString("workflows_v1", WorkflowDefinitionCodec.encode(workflows)).commit()
                                                    projectResourceScopes = remainingSkillScopes
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

    if (showProjectPackageImportConfirm && pendingProjectPackageUri != null) {
        AlertDialog(
            onDismissRequest = { showProjectPackageImportConfirm = false; pendingProjectPackageUri = null },
            title = { Text("导入项目包？") },
            text = { Text("导入会新建一个独立项目，不覆盖现有项目。会复制项目会话、知识、记忆、产物和项目资源；本机模型/API 设置与跨项目授权保持不变，不随项目包导入。") },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingProjectPackageUri
                    showProjectPackageImportConfirm = false
                    pendingProjectPackageUri = null
                    if (uri != null) performProjectPackageImport(uri)
                }) { Text("导入项目", color = Mint) }
            },
            dismissButton = {
                TextButton(onClick = { showProjectPackageImportConfirm = false; pendingProjectPackageUri = null }) { Text("取消") }
            }
        )
    }

    if (showProjectAccessDialog && pendingAccessSourceProjectId.isNotBlank()) {
        val sourceProject = projects.firstOrNull { it.id == pendingAccessSourceProjectId }
        AlertDialog(
            onDismissRequest = { showProjectAccessDialog = false; pendingAccessSourceProjectId = "" },
            title = { Text("跨项目访问授权") },
            text = {
                Text(
                    "允许当前项目「" + (projects.firstOrNull { it.id == activeProjectId }?.name ?: activeProjectId) +
                        "」访问「" + (sourceProject?.name ?: pendingAccessSourceProjectId) +
                        "」中的资源。读取权限用于检索该项目的知识、记忆及调用可见专家/技能/工作流；写入权限用于管理该项目知识文档，并允许修改由该项目拥有的共享资源。授权仅对这两个项目生效。",
                    color = Muted
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        runCatching {
                            ProjectAccessControlStore.grant(
                                prefs, pendingAccessSourceProjectId, activeProjectId,
                                setOf(ProjectAccessControlStore.Access.READ)
                            )
                            readableProjectIds = readableSourcesFor(activeProjectId)
                            writableProjectIds = writableSourcesFor(activeProjectId)
                            status = "已授权当前项目读取「" + (sourceProject?.name ?: pendingAccessSourceProjectId) + "」的资源"
                        }.onFailure { status = "授权失败：" + (it.message ?: "数据无效") }
                        showProjectAccessDialog = false
                        pendingAccessSourceProjectId = ""
                    }) { Text("仅读取", color = Mint) }
                    TextButton(onClick = {
                        runCatching {
                            ProjectAccessControlStore.grant(
                                prefs, pendingAccessSourceProjectId, activeProjectId,
                                setOf(ProjectAccessControlStore.Access.READ, ProjectAccessControlStore.Access.WRITE)
                            )
                            readableProjectIds = readableSourcesFor(activeProjectId)
                            writableProjectIds = writableSourcesFor(activeProjectId)
                            status = "已授权当前项目读取和写入「" + (sourceProject?.name ?: pendingAccessSourceProjectId) + "」"
                        }.onFailure { status = "授权失败：" + (it.message ?: "数据无效") }
                        showProjectAccessDialog = false
                        pendingAccessSourceProjectId = ""
                    }) { Text("读取和写入", color = Mint) }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        runCatching { ProjectAccessControlStore.revoke(prefs, pendingAccessSourceProjectId, activeProjectId) }
                            .onSuccess {
                                readableProjectIds = readableSourcesFor(activeProjectId)
                                writableProjectIds = writableSourcesFor(activeProjectId)
                                status = "已撤销跨项目授权"
                            }
                            .onFailure { status = "撤销失败：" + (it.message ?: "数据无效") }
                        showProjectAccessDialog = false
                        pendingAccessSourceProjectId = ""
                    }) { Text("撤销授权", color = ColorStop) }
                    TextButton(onClick = { showProjectAccessDialog = false; pendingAccessSourceProjectId = "" }) { Text("取消") }
                }
            }
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
                    experts.filter { expert ->
                        !projectResourceScopesReadOnly && ProjectResourceScopeStore.isVisibleFromProjects(
                            projectResourceScopes, ProjectResourceKind.EXPERT, expert, activeProjectId, readableProjectIds
                        )
                    }.forEach { expert ->
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
                    if (projectResourceScopesReadOnly) {
                        status = "项目资源范围数据校验失败，已锁定资源创建与修改。"
                        return@TextButton
                    }
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
                        if (team.members.size in 2..4 && team.members.distinct().size == team.members.size) {
                            if (editingTeamId.isBlank() && !updateProjectResourceScope(ProjectResourceKind.TEAM, team.id, false)) return@TextButton
                            if (editingTeamId.isNotBlank() && !canWriteProjectResource(ProjectResourceKind.TEAM, editingTeamId)) return@TextButton
                            expertTeams = if (editingTeamId.isBlank()) expertTeams + team else expertTeams.map { if (it.id == editingTeamId) team else it }
                            check(prefs.edit().putString("expert_teams_v1", expertTeamsToJson(expertTeams)).commit()) { "专家团队保存失败" }
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
                        conversations = ConversationPreferencesStore.saveConversationMessages(prefs, activeConversationId, messages, activeProjectId, conversations)
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
                    if (projectResourceScopesReadOnly) {
                        skillImportStatus = "项目资源范围数据校验失败，已锁定资源导入。"
                        return@TextButton
                    }
                    val replacing = bundle.name in skills
                    if (replacing && !canWriteProjectResource(ProjectResourceKind.SKILL, bundle.name)) return@TextButton
                    if (!replacing && !updateProjectResourceScope(ProjectResourceKind.SKILL, bundle.name, false)) return@TextButton
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
                            automaticMemoryEnabled = ProjectMemorySettingsStore.isEnabled(prefs, project.id)
                            prefs.edit().putLong("project_last_opened_${project.id}", System.currentTimeMillis()).apply()
                            val conversation = ConversationRecord(UUID.randomUUID().toString(), "新对话", System.currentTimeMillis(), emptyList(), project.id)
                            conversations = listOf(conversation) + conversations
                            activeConversationId = conversation.id
                            messages = emptyList()
                            prefs.edit().putString("active_project_id", project.id).putString("active_conversation_id", conversation.id).apply()
                            ConversationPreferencesStore.saveConversationList(prefs, conversations)
                            page = "home"
                        } else {
                            projects = projects.map { if (it.id == editingProjectId) it.copy(name = name, instructions = projectDraftInstructions.trim()) else it }
                        }
                        ProjectPreferencesStore.saveProjects(prefs, projects)
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
                                            appRuntime.conversationPersistenceService.loadMessages(target.id).orEmpty()
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
                        ConversationPreferencesStore.saveConversationList(prefs, conversations)
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
                    if (projectResourceScopesReadOnly) {
                        skillDialogError = "项目资源范围数据校验失败，已锁定资源创建与修改。"
                        return@TextButton
                    }
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
                        if (oldName.isNotBlank() && !canWriteProjectResource(ProjectResourceKind.SKILL, oldName)) return@TextButton
                        if (oldName.isNotBlank() && oldName != name && !canWriteSkillDependents(oldName)) return@TextButton
                        if (oldName.isBlank() && !updateProjectResourceScope(ProjectResourceKind.SKILL, name, false)) return@TextButton
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
                        val renamedSkillScopes = if (oldName.isNotBlank() && oldName != name) {
                            ProjectResourceScopeStore.renameResourceScope(
                                projectResourceScopes, ProjectResourceKind.SKILL, oldName, name
                            )
                        } else null
                        val skillEditor = prefs.edit().putString("skills",skillMapToJson(skills))
                            .putString("skill_metadata_v1",skillMetadataToJson(skillMetadata))
                            .putString("disabled_skills_v1",disabledSkillsToJson(disabledSkills))
                            .putString("expert_profiles_v1", ExpertProfileCodec.encode(expertProfiles))
                             .putString("expert_versions_v1", ExpertRevisionCodec.encode(expertRevisions))
                             .putString("expert_templates_v1", ExpertTemplateCodec.encode(expertTemplates))
                             .putString("workflows_v1", WorkflowDefinitionCodec.encode(workflows))
                        if (renamedSkillScopes != null) {
                            skillEditor.putString("project_resource_scopes_v1", ProjectResourceScopeStore.encode(renamedSkillScopes))
                        }
                        check(skillEditor.commit()) { "技能及项目范围保存失败" }
                        if (renamedSkillScopes != null) projectResourceScopes = renamedSkillScopes
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
                if (projectResourceScopesReadOnly) {
                    teamImportStatus = "项目资源范围数据校验失败，已锁定资源导入。"
                    return@TextButton
                }
                if (bundle.members.size !in 2..4 || bundle.members.map { it.name }.distinct().size != bundle.members.size) {
                    teamImportStatus = "团队成员必须为 2–4 位且不能重复。"
                    pendingTeamBundle = null
                    return@TextButton
                }
                val previousTeam=expertTeams.firstOrNull { it.name==bundle.name }
                if (previousTeam != null && !canWriteProjectResource(ProjectResourceKind.TEAM, previousTeam.id)) return@TextButton
                val existingExpertNames = experts.toSet()
                if (bundle.members.any { it.name in experts && !canWriteProjectResource(ProjectResourceKind.EXPERT, it.name) }) return@TextButton
                val team = ExpertTeam(previousTeam?.id ?: UUID.randomUUID().toString(), bundle.name, bundle.description, bundle.members.map { it.name })
                bundle.members.filter { it.name !in existingExpertNames }.forEach { member ->
                    if (!updateProjectResourceScope(ProjectResourceKind.EXPERT, member.name, false)) return@TextButton
                }
                if (previousTeam == null && !updateProjectResourceScope(ProjectResourceKind.TEAM, team.id, false)) return@TextButton
                bundle.members.forEach { member ->
                    if (member.name !in experts) experts = experts + member.name
                    expertPrompts = expertPrompts + (member.name to member.prompt)
                }
                expertTeams = expertTeams.filterNot { it.name == bundle.name } + team
                if(selectedTeamId==previousTeam?.id) selectedTeamId=team.id
                prefs.edit().putString("experts", JSONArray(experts).toString())
                    .putString("expert_prompts", expertPromptsToJson(expertPrompts))
                    .putString("expert_teams_v1", expertTeamsToJson(expertTeams))
                    .putString("selected_team_id",selectedTeamId).apply()
                teamImportStatus = "已导入团队：" + bundle.name
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
                if (projectResourceScopesReadOnly) {
                    expertImportStatus = "项目资源范围数据校验失败，已锁定资源导入。"
                    return@TextButton
                }
                val isNewExpert = bundle.name !in experts
                if (!isNewExpert && !canWriteProjectResource(ProjectResourceKind.EXPERT, bundle.name)) return@TextButton
                if (isNewExpert && !updateProjectResourceScope(ProjectResourceKind.EXPERT, bundle.name, false)) return@TextButton
                val missingSkills = bundle.skillNames.filter { skillName ->
                    skillName !in skills || skillName in disabledSkills || projectResourceScopesReadOnly ||
                        !ProjectResourceScopeStore.isVisibleFromProjects(
                            projectResourceScopes, ProjectResourceKind.SKILL, skillName, activeProjectId, readableProjectIds
                        )
                }
                if (missingSkills.isNotEmpty()) {
                    expertImportStatus = "导入未完成，以下挂载技能不存在、已停用或当前项目无读取权限：${missingSkills.joinToString()}"
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
                expertImportStatus = "已导入专家：" + bundle.name
                pendingExpertBundle = null
            }) { Text("导入", color = Mint) } },
            dismissButton = { TextButton(onClick = { pendingExpertBundle = null }) { Text("取消") } }
        )
    }

    if (showWorkflowTargetPicker && workflowTargetPickerIndex in workflowDraftSteps.indices) {
        val targetStep = workflowDraftSteps[workflowTargetPickerIndex]
        val workflowReadableProjectIds = if (projectResourceScopesReadOnly) emptySet() else
            ProjectAccessControlStore.readableSourceProjects(prefs, activeProjectId, projects.filter { it.archivedAt == 0L }.map { it.id }.toSet())
        val targets = when (targetStep.kind) {
            WorkflowStepKind.EXPERT -> experts.filter { expert ->
                !projectResourceScopesReadOnly && ProjectResourceScopeStore.isVisibleFromProjects(
                    projectResourceScopes, ProjectResourceKind.EXPERT, expert, activeProjectId, workflowReadableProjectIds
                )
            }
            WorkflowStepKind.SKILL -> skills.keys.filter { skill ->
                skill !in disabledSkills && !projectResourceScopesReadOnly &&
                    ProjectResourceScopeStore.isVisibleFromProjects(
                        projectResourceScopes, ProjectResourceKind.SKILL, skill, activeProjectId, workflowReadableProjectIds
                    )
            }
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
                            OutlinedTextField(
                                value = step.condition,
                                onValueChange = { value ->
                                    workflowDraftSteps = workflowDraftSteps.mapIndexed { stepIndex, item ->
                                        if (stepIndex == index) item.copy(condition = value.take(200)) else item
                                    }
                                },
                                label = { Text("执行条件：always / nonblank / contains:关键词 / not_contains:关键词") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = step.parallelGroup,
                                onValueChange = { value ->
                                    workflowDraftSteps = workflowDraftSteps.mapIndexed { stepIndex, item ->
                                        if (stepIndex == index) item.copy(parallelGroup = value.trim().take(40)) else item
                                    }
                                },
                                label = { Text("并行组名称（连续相同步骤并行；留空为顺序执行）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextButton(onClick = {
                                workflowDraftSteps = workflowDraftSteps.mapIndexed { stepIndex, item ->
                                    if (stepIndex == index) item.copy(approvalRequired = !item.approvalRequired) else item
                                }
                            }) {
                                Text(if (step.approvalRequired) "执行前审批：开启" else "执行前审批：关闭", color = if (step.approvalRequired) Mint else Muted)
                            }
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
                    if (projectResourceScopesReadOnly) {
                        workflowRunStatus = "项目资源范围数据校验失败，已锁定资源创建与修改。"
                        return@TextButton
                    }
                    val workflow = WorkflowDefinition(
                        id = editingWorkflowId.ifBlank { UUID.randomUUID().toString() },
                        name = workflowDraftName.trim(),
                        description = workflowDraftDescription.trim(),
                        steps = workflowDraftSteps.map { step ->
                            step.copy(title = step.title.trim(), instruction = step.instruction.trim())
                        }
                    )
                    if (editingWorkflowId.isNotBlank() && !canWriteProjectResource(ProjectResourceKind.WORKFLOW, editingWorkflowId)) return@TextButton
                    runCatching { WorkflowDefinitionCodec.validate(workflow) }.onSuccess {
                        if (editingWorkflowId.isBlank() && !updateProjectResourceScope(ProjectResourceKind.WORKFLOW, workflow.id, false)) return@onSuccess
                        workflows = if (editingWorkflowId.isBlank()) workflows + workflow else workflows.map { if (it.id == editingWorkflowId) workflow else it }
                        prefs.edit().putString("workflows_v1", WorkflowDefinitionCodec.encode(workflows)).apply()
                        showWorkflowDialog = false
                    }.onFailure { workflowRunStatus = it.message ?: "工作流内容无效" }
                }) { Text("保存工作流", color = Mint) }
            },
            dismissButton = { TextButton(onClick = { showWorkflowDialog = false }) { Text("取消") } }
        )
    }

    workflowApprovalPrompt?.let { approval ->
        AlertDialog(
            onDismissRequest = {
                approval.decision.complete(false)
                workflowApprovalPrompt = null
            },
            title = { Text("工作流步骤需要确认") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("即将执行：${approval.stepTitle}", color = Ink)
                    Text("前序结果（供判断）：", color = Muted)
                    Text(approval.context.ifBlank { "暂无前序输出" }, color = Ink)
                    Text("批准后才会继续执行此步骤。", color = Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    approval.decision.complete(true)
                    workflowApprovalPrompt = null
                }) { Text("批准执行", color = Mint) }
            },
            dismissButton = {
                TextButton(onClick = {
                    approval.decision.complete(false)
                    workflowApprovalPrompt = null
                }) { Text("拒绝", color = Muted) }
            }
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
                    skills.filterKeys { skillName ->
                        !projectResourceScopesReadOnly && ProjectResourceScopeStore.isVisibleFromProjects(
                            projectResourceScopes, ProjectResourceKind.SKILL, skillName, activeProjectId, readableProjectIds
                        )
                    }.forEach { (skillName, _) ->
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
                    if (projectResourceScopesReadOnly) {
                        expertDialogError = "项目资源范围数据校验失败，已锁定资源创建与修改。"
                        return@TextButton
                    }
                    val name = expertDraft.trim()
                    val oldName = editingExpertName
                    val validationError = ExpertCatalogRules.validateDefinition(name, expertPromptDraft, oldName, experts)
                    if (validationError != null) {
                        expertDialogError = validationError
                        return@TextButton
                    }
                    if (oldName.isNotBlank() && !canWriteProjectResource(ProjectResourceKind.EXPERT, oldName)) {
                        expertDialogError = "当前项目没有该专家的写入权限。"
                        return@TextButton
                    }
                    if (oldName.isNotBlank() && oldName != name && !canWriteExpertDependents(oldName)) {
                        expertDialogError = "该专家被其他只读团队或工作流引用，不能在当前项目重命名。"
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
                    if (oldName.isBlank() && !updateProjectResourceScope(ProjectResourceKind.EXPERT, name, false)) return@TextButton
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
                    val renamedExpertScopes = if (oldName.isNotBlank() && oldName != name) {
                        ProjectResourceScopeStore.renameResourceScope(
                            projectResourceScopes, ProjectResourceKind.EXPERT, oldName, name
                        )
                    } else null
                    val expertEditor = prefs.edit().putString("experts", JSONArray(experts).toString())
                        .putString("expert_prompts", expertPromptsToJson(expertPrompts))
                        .putString("expert_profiles_v1", ExpertProfileCodec.encode(expertProfiles))
                        .putString("expert_versions_v1", ExpertRevisionCodec.encode(expertRevisions))
                        .putString("expert_teams_v1", expertTeamsToJson(expertTeams))
                        .putString("workflows_v1", WorkflowDefinitionCodec.encode(workflows))
                        .putString("selected_expert", selectedExpert)
                    if (renamedExpertScopes != null) {
                        expertEditor.putString("project_resource_scopes_v1", ProjectResourceScopeStore.encode(renamedExpertScopes))
                    }
                    check(expertEditor.commit()) { "专家及项目范围保存失败" }
                    if (renamedExpertScopes != null) projectResourceScopes = renamedExpertScopes
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
                                if (!canWriteProjectResource(ProjectResourceKind.EXPERT, name)) return@TextButton
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
        val builtInTemplates = ExpertTemplateCatalog.builtIns()
        val allTemplates = builtInTemplates + expertTemplates
        val templateCategories = listOf("全部") + allTemplates.map { it.category }.distinct().sorted()
        val visibleTemplates = allTemplates.filter { template ->
            (templateCategoryFilter == "全部" || template.category == templateCategoryFilter) &&
                (templateSearchDraft.isBlank() || listOf(template.name, template.description, template.prompt, template.category)
                    .any { it.contains(templateSearchDraft, ignoreCase = true) })
        }
        AlertDialog(
            onDismissRequest = { showExpertTemplatesDialog = false },
            title = { Text("专家模板库") },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = templateNameDraft, onValueChange = { templateNameDraft = it.take(40) }, label = { Text("模板名称") }, singleLine = true)
                    Button(onClick = {
                        val name = templateNameDraft.trim()
                        val sourceName = selectedExpert
                        val prompt = expertPrompts[sourceName].orEmpty().ifBlank { ExpertTeamDefaults.rolePrompt(sourceName) }
                        val profile = expertProfiles[sourceName] ?: ExpertProfile()
                        val builtInName = builtInTemplates.any { it.name.equals(name, ignoreCase = true) }
                        val expertOwners = ProjectResourceScopeStore.ownerProjectIds(projectResourceScopes, ProjectResourceKind.EXPERT, sourceName)
                        val projectOnlyExpert = expertOwners != null && "*" !in expertOwners
                        val projectOnlySkills = profile.skillNames.filter { skill ->
                            val owners = ProjectResourceScopeStore.ownerProjectIds(projectResourceScopes, ProjectResourceKind.SKILL, skill)
                            owners != null && "*" !in owners
                        }
                        if (projectResourceScopesReadOnly) {
                            templateStatus = "项目资源范围数据校验失败，已锁定全局模板创建。"
                        } else if (projectOnlyExpert) {
                            templateStatus = "项目专属专家不能直接保存为全局模板，请先将专家设为全局共享。"
                        } else if (projectOnlySkills.isNotEmpty()) {
                            templateStatus = "全局专家模板不能引用项目专属技能：" + projectOnlySkills.joinToString("、")
                        } else if (name.isBlank() || prompt.isBlank()) {
                            templateStatus = "模板名称或当前专家指令为空，无法保存。"
                        } else if (builtInName) {
                            templateStatus = "该名称属于内置模板，请换一个名称保存自定义副本。"
                        } else if (ExpertProfileCodec.validate(profile, skills.keys) != null) {
                            templateStatus = "当前专家配置无效：" + (ExpertProfileCodec.validate(profile, skills.keys) ?: "")
                        } else {
                            val existing = expertTemplates.firstOrNull { it.name.equals(name, ignoreCase = true) }
                            if (existing == null && expertTemplates.size >= 100) {
                                templateStatus = "自定义模板库已达到 100 个上限。"
                            } else {
                                val template = existing?.copy(
                                    name = name,
                                    description = profile.description,
                                    prompt = prompt,
                                    profile = profile,
                                    category = profile.group.ifBlank { "自定义" }
                                ) ?: ExpertTemplate(
                                    name = name,
                                    description = profile.description,
                                    prompt = prompt,
                                    profile = profile,
                                    category = profile.group.ifBlank { "自定义" }
                                )
                                val next = expertTemplates.filterNot { it.id == template.id } + template
                                if (runCatching {
                                    prefs.edit().putString("expert_templates_v1", ExpertTemplateCodec.encode(next)).commit()
                                }.getOrDefault(false)) {
                                    expertTemplates = next
                                    templateStatus = "模板「$name」已保存。"
                                } else {
                                    templateStatus = "模板保存失败：本机存储未确认写入。"
                                }
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) { Text("保存当前专家为模板") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { importTemplateLauncher.launch(arrayOf("application/json", "text/plain")) }, modifier = Modifier.weight(1f)) { Text("导入模板") }
                        OutlinedButton(
                            enabled = expertTemplates.isNotEmpty(),
                            onClick = {
                                runCatching { ExpertTemplateBundleCodec.encode(expertTemplates) }
                                    .onSuccess { encoded ->
                                        templateExportJson = encoded
                                        exportTemplateLauncher.launch("nestro-expert-templates.json")
                                    }
                                    .onFailure { error ->
                                        templateStatus = "模板导出失败：" + (error.message ?: "模板数据无效")
                                    }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("导出自定义模板") }
                    }
                    OutlinedTextField(value = templateSearchDraft, onValueChange = { templateSearchDraft = it }, label = { Text("搜索模板名称、说明或指令") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        templateCategories.forEach { category ->
                            FilterChip(selected = templateCategoryFilter == category, onClick = { templateCategoryFilter = category }, label = { Text(category) })
                        }
                    }
                    Text("内置模板 ${builtInTemplates.size} 个 · 自定义模板 ${expertTemplates.size} 个", color = Muted, style = MaterialTheme.typography.labelSmall)
                    if (visibleTemplates.isEmpty()) Text("没有匹配的模板。", color = Muted)
                    visibleTemplates.forEach { template ->
                        Surface(color = Card, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${template.profile.avatar}  ${template.name}", color = Ink, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    Text(if (template.builtIn) "内置" else "自定义", color = Mint, style = MaterialTheme.typography.labelSmall)
                                }
                                Text("${template.category} · ${template.description.ifBlank { "无描述" }}", color = Muted, style = MaterialTheme.typography.bodySmall)
                                Text(template.prompt.take(180), color = Muted, style = MaterialTheme.typography.bodySmall)
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
                                    }) { Text("使用模板", color = Mint) }
                                    TextButton(onClick = {
                                        editingTemplate = template
                                        templateEditName = if (template.builtIn) template.name.take(35) + "副本" else template.name
                                        templateEditDescription = template.description
                                        templateEditCategory = template.category
                                        templateEditPrompt = template.prompt
                                        templateEditorStatus = ""
                                        showExpertTemplatesDialog = false
                                    }) { Text(if (template.builtIn) "复制为自定义" else "编辑", color = Mint) }
                                    if (!template.builtIn) TextButton(onClick = {
                                        templateToDelete = template
                                    }) { Text("删除", color = ColorStop) }
                                }
                            }
                        }
                    }
                    if (templateStatus.isNotBlank()) Text(templateStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showExpertTemplatesDialog = false }) { Text("关闭") } }
        )
    }

    templateToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { templateToDelete = null },
            title = { Text("删除自定义模板？") },
            text = { Text("将删除模板「${target.name}」。已创建的专家不会受到影响，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val next = expertTemplates.filterNot { it.id == target.id }
                    val persisted = runCatching {
                        prefs.edit().putString("expert_templates_v1", ExpertTemplateCodec.encode(next)).commit()
                    }.getOrDefault(false)
                    if (persisted) {
                        expertTemplates = next
                        templateStatus = "已删除模板「${target.name}」。"
                    } else {
                        templateStatus = "删除失败：本机存储未确认写入。"
                    }
                    templateToDelete = null
                }) { Text("确认删除", color = ColorStop) }
            },
            dismissButton = { TextButton(onClick = { templateToDelete = null }) { Text("取消") } }
        )
    }
    editingTemplate?.let { source ->
        AlertDialog(
            onDismissRequest = {
                editingTemplate = null
                templateEditorStatus = ""
                showExpertTemplatesDialog = true
            },
            title = { Text(if (source.builtIn) "复制内置模板" else "编辑专家模板") },
            text = {
                Column(
                    Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(value = templateEditName, onValueChange = { templateEditName = it.take(40) }, label = { Text("模板名称") }, singleLine = true)
                    OutlinedTextField(value = templateEditCategory, onValueChange = { templateEditCategory = it.take(30) }, label = { Text("分类") }, singleLine = true)
                    OutlinedTextField(value = templateEditDescription, onValueChange = { templateEditDescription = it.take(300) }, label = { Text("模板说明") }, minLines = 2)
                    OutlinedTextField(value = templateEditPrompt, onValueChange = { templateEditPrompt = it.take(8000) }, label = { Text("角色指令") }, minLines = 6)
                    if (templateEditorStatus.isNotBlank()) Text(templateEditorStatus, color = ColorStop, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = templateEditName.trim()
                    val category = templateEditCategory.trim()
                    val description = templateEditDescription.trim()
                    val prompt = templateEditPrompt.trim()
                    val builtInConflict = ExpertTemplateCatalog.builtIns().any { it.name.equals(name, ignoreCase = true) }
                    val existingConflict = expertTemplates.firstOrNull {
                        it.name.equals(name, ignoreCase = true) && it.id != source.id
                    }
                    val invalidSkills = source.profile.skillNames.filter { skill ->
                        val owners = ProjectResourceScopeStore.ownerProjectIds(projectResourceScopes, ProjectResourceKind.SKILL, skill)
                        skill !in skills || (owners != null && "*" !in owners)
                    }
                    val profileError = ExpertProfileCodec.validate(source.profile, skills.keys)
                    when {
                        projectResourceScopesReadOnly -> templateEditorStatus = "项目资源范围数据校验失败，已锁定模板修改。"
                        name.isBlank() || name.length > 40 -> templateEditorStatus = "模板名称必须为 1 到 40 个字符。"
                        category.isBlank() || category.length > 30 -> templateEditorStatus = "模板分类必须为 1 到 30 个字符。"
                        description.length > 300 -> templateEditorStatus = "模板说明不能超过 300 个字符。"
                        prompt.isBlank() || prompt.length > 8000 -> templateEditorStatus = "角色指令必须为 1 到 8000 个字符。"
                        builtInConflict -> templateEditorStatus = "该名称属于内置模板，请更换名称。"
                        existingConflict != null -> templateEditorStatus = "已存在同名自定义模板，请更换名称。"
                        invalidSkills.isNotEmpty() -> templateEditorStatus = "模板引用了不存在或项目专属技能：" + invalidSkills.joinToString("、")
                        profileError != null -> templateEditorStatus = "模板专家配置无效：" + profileError
                        source.builtIn && expertTemplates.size >= 100 -> templateEditorStatus = "自定义模板库已达到 100 个上限。"
                        else -> {
                            val updated = if (source.builtIn) {
                                ExpertTemplate(
                                    name = name, description = description, prompt = prompt,
                                    profile = source.profile, category = category
                                )
                            } else {
                                source.copy(name = name, description = description, prompt = prompt, category = category, builtIn = false)
                            }
                            val next = if (source.builtIn) expertTemplates + updated
                                else expertTemplates.filterNot { it.id == source.id } + updated
                            val persisted = runCatching {
                                prefs.edit().putString("expert_templates_v1", ExpertTemplateCodec.encode(next)).commit()
                            }.getOrDefault(false)
                            if (persisted) {
                                expertTemplates = next
                                templateStatus = if (source.builtIn) "已复制为自定义模板：$name" else "模板已更新：$name"
                                editingTemplate = null
                                templateEditorStatus = ""
                                showExpertTemplatesDialog = true
                            } else {
                                templateEditorStatus = "保存失败：本机存储未确认写入。"
                            }
                        }
                    }
                }) { Text("保存模板") }
            },
            dismissButton = {
                TextButton(onClick = {
                    editingTemplate = null
                    templateEditorStatus = ""
                    showExpertTemplatesDialog = true
                }) { Text("取消") }
            }
        )
    }
    pendingTemplateBundle?.let { importedTemplates ->
        AlertDialog(
            onDismissRequest = { pendingTemplateBundle = null },
            title = { Text("导入专家模板") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("共 ${importedTemplates.size} 个模板")
                    importedTemplates.take(8).forEach { Text("• ${it.name} · ${it.category}") }
                    if (importedTemplates.size > 8) Text("另有 ${importedTemplates.size - 8} 个模板")
                    Text("同名自定义模板将被更新；内置模板不会被覆盖。", color = Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (projectResourceScopesReadOnly) {
                        templateStatus = "项目资源范围数据校验失败，已锁定模板导入。"
                        pendingTemplateBundle = null
                        return@TextButton
                    }
                    val builtInNames = ExpertTemplateCatalog.builtIns().map { it.name.lowercase() }.toSet()
                    val conflicts = importedTemplates.filter { it.name.lowercase() in builtInNames }
                    val unavailableSkills = importedTemplates.flatMap { it.profile.skillNames }.distinct().filter { skill ->
                        val owners = ProjectResourceScopeStore.ownerProjectIds(projectResourceScopes, ProjectResourceKind.SKILL, skill)
                        skill !in skills || (owners != null && "*" !in owners)
                    }
                    val mergedCount = expertTemplates.count { existing ->
                        importedTemplates.none { it.name.equals(existing.name, ignoreCase = true) }
                    } + importedTemplates.size
                    if (unavailableSkills.isNotEmpty()) {
                        templateStatus = "导入失败：模板引用了不存在或项目专属技能：" + unavailableSkills.joinToString("、")
                    } else if (conflicts.isNotEmpty()) {
                        templateStatus = "导入失败：模板名称与内置模板冲突，请先修改文件名称。"
                    } else if (mergedCount > 100) {
                        templateStatus = "导入失败：自定义模板总数不能超过 100 个。"
                    } else {
                        val next = expertTemplates.filterNot { old ->
                            importedTemplates.any { it.name.equals(old.name, ignoreCase = true) }
                        } + importedTemplates.map { incoming ->
                            val existing = expertTemplates.firstOrNull { it.name.equals(incoming.name, ignoreCase = true) }
                            incoming.copy(
                                id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                                builtIn = false
                            )
                        }
                        val persisted = runCatching {
                            prefs.edit().putString("expert_templates_v1", ExpertTemplateCodec.encode(next)).commit()
                        }.getOrDefault(false)
                        if (persisted) {
                            expertTemplates = next
                            templateStatus = "已导入 ${importedTemplates.size} 个专家模板。"
                        } else {
                            templateStatus = "导入失败：本机存储未确认写入。"
                        }
                    }
                    pendingTemplateBundle = null
                }) { Text("确认导入") }
            },
            dismissButton = { TextButton(onClick = { pendingTemplateBundle = null }) { Text("取消") } }
        )
    }
}
}

private fun jsonStringList(array: JSONArray?): List<String> = array?.let { value -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.take(20) }.orEmpty()

