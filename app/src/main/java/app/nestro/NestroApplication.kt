package app.nestro

import android.app.Application
import app.nestro.calendar.CalendarRepository
import app.nestro.mcp.McpToolCenterRepository
import app.nestro.mcp.McpToolCenterService
import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentToolCatalog
import app.nestro.agent.InMemoryAgentToolCatalog
import app.nestro.media.MediaAiTaskStore
import app.nestro.media.MediaAiTaskStatus
import app.nestro.media.MediaProviderConfigStore
import app.nestro.media.MediaVideoGeneration
import kotlinx.coroutines.delay
import app.nestro.agent.AgentTaskHandlerRegistry
import app.nestro.agent.RoomAgentTaskStateStore
import app.nestro.agent.RoomAgentTaskTraceStore
import app.nestro.agent.AgentTaskState
import app.nestro.agent.AgentTaskStatus
import app.nestro.phone.PhoneAgentAgent
import app.nestro.phone.PhoneAgentExecutor
import app.nestro.phone.PhoneAgentWorkScheduler
import app.nestro.phone.PhoneAgentTaskService
import app.nestro.data.NestroDatabase
import app.nestro.data.PhoneAgentTaskStore
import app.nestro.data.ConversationPersistenceService
import app.nestro.data.ChatRequestRecoveryCoordinator
import app.nestro.data.ConversationStartupCoordinator
import app.nestro.data.UserMemoryRepository
import app.nestro.data.ConversationContextRepository
import app.nestro.data.ProjectSpaceRepository
import app.nestro.data.ProjectAccessControlStore
import app.nestro.data.ProjectPreferencesStore
import app.nestro.data.KnowledgeRepository
import app.nestro.data.LocalBackupRepository
import app.nestro.data.ExpertTeamExecutionCoordinator
import app.nestro.data.ChatRequestContextAssembler
import app.nestro.data.ChatRequestExecutionCoordinator
import app.nestro.data.AutomaticMemoryExtractionCoordinator
import app.nestro.data.CapabilityDraftCoordinator
import app.nestro.data.CapabilityDraftTransport
import app.nestro.data.extractMemoryCandidates
import app.nestro.data.ModelProfileCatalogCodec
import app.nestro.data.ModelUsageStatsCodec
import app.nestro.data.ModelConfig
import app.nestro.model.ModelRequestConfigurationSelector
import app.nestro.model.ModelRegistry
import app.nestro.data.WorkflowExecutionCoordinator
import app.nestro.model.ModelConfigurationRepository
import app.nestro.model.ModelConnectionService
import app.nestro.model.ChatExecutionService
import app.nestro.model.ChatStreamingCoordinator
import app.nestro.model.ChatStreamingTransport
import app.nestro.model.SharedPreferencesChatPartialStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns process-wide agent infrastructure so task execution is not tied to a
 * Compose screen or Activity lifecycle.
 */
class NestroApplication : Application() {
    val database: NestroDatabase by lazy { NestroDatabase.get(this) }
    val calendarRepository: CalendarRepository by lazy { CalendarRepository(this) }
    val conversationPersistenceService: ConversationPersistenceService by lazy {
        ConversationPersistenceService(database, applicationContext)
    }
    internal val chatRequestRecoveryCoordinator: ChatRequestRecoveryCoordinator by lazy {
        ChatRequestRecoveryCoordinator(conversationPersistenceService)
    }
    internal val conversationStartupCoordinator: ConversationStartupCoordinator by lazy {
        ConversationStartupCoordinator(conversationPersistenceService)
    }
    val userMemoryRepository: UserMemoryRepository by lazy { UserMemoryRepository(database, this) }
    val conversationContextRepository: ConversationContextRepository by lazy { ConversationContextRepository(database, this) }
    val projectSpaceRepository: ProjectSpaceRepository by lazy { ProjectSpaceRepository(database) }
    val knowledgeRepository: KnowledgeRepository by lazy { KnowledgeRepository(database, this) }
    val localBackupRepository: LocalBackupRepository by lazy { LocalBackupRepository(this, database) }
    val modelConfigurationRepository: ModelConfigurationRepository by lazy {
        ModelConfigurationRepository(this)
    }
    val modelConnectionService: ModelConnectionService by lazy { ModelConnectionService() }
    val chatExecutionService: ChatExecutionService by lazy { ChatExecutionService() }
    internal val capabilityDraftCoordinator: CapabilityDraftCoordinator by lazy {
        CapabilityDraftCoordinator(
            CapabilityDraftTransport { configs, history, systemPrompt, onUsage ->
                chatExecutionService.completeWithFallback(configs, history, systemPrompt, onUsage = onUsage)
            }
        )
    }
    internal val chatStreamingCoordinator: ChatStreamingCoordinator by lazy {
        ChatStreamingCoordinator(
            transport = ChatStreamingTransport { configs, history, systemPrompt, temperature, maxTokens, onUsage, onProviderUsage, requestId, shouldStop, onChunk ->
                chatExecutionService.streamWithFallback(
                    configs, history, systemPrompt, temperature, maxTokens, onUsage, onProviderUsage, requestId, shouldStop, onChunk
                )
            },
            partialStateStore = SharedPreferencesChatPartialStateStore(
                getSharedPreferences("nestro_local", MODE_PRIVATE)
            )
        )
    }
    /** One process-wide tool catalog shared by chat, Phone Agent, experts, workflows and MCP. */
    val agentToolCatalog: AgentToolCatalog by lazy {
        InMemoryAgentToolCatalog().apply {
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "knowledge.search",
                    description = "按当前项目搜索知识库资料。参数：query（必填，检索问题）；limit（可选，1 到 8）。只读，不会修改知识库。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) { arguments ->
                val projectId = arguments["__nestro_project_id"]?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "缺少当前项目范围，已阻止知识库检索")
                val query = arguments["query"]?.trim()?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "知识库检索缺少 query 参数")
                val limit = arguments["limit"]?.toIntOrNull()?.coerceIn(1, 8) ?: 6
                val matches = knowledgeRepository.search(projectId, query, limit)
                if (matches.isEmpty()) {
                    app.nestro.agent.AgentToolResult(true, output = "当前项目知识库没有找到相关资料。")
                } else {
                    val output = matches.joinToString("\n\n") { match ->
                        "【${match.documentName}】\n${match.content}"
                    }.take(12_000)
                    app.nestro.agent.AgentToolResult(true, output = output)
                }
            }
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "phone_agent.plan",
                    description = "在明确的任务模式下，为用户目标创建并调度 Phone Agent 任务。仅用于需要在手机应用或网页中执行多步操作的目标；不执行付款或最终提交。",
                    permission = app.nestro.agent.AgentToolPermission.DEVICE_ACTION
                )
            ) {
                app.nestro.agent.AgentToolResult(false, error = "Phone Agent 规划必须经由聊天统一调度器调用")
            }
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "expert_team.run",
                    description = "调用当前会话中用户明确选择的专家团队，分工分析并汇总结果。仅在团队已启用且当前任务模式获得授权时可用。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) {
                app.nestro.agent.AgentToolResult(false, error = "专家团队必须经由聊天统一调度器调用")
            }
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "media.provider.list",
                    description = "列出当前用户已配置的媒体服务商及聊天/视频模型能力。无需参数。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) {
                val providers = MediaProviderConfigStore(this@NestroApplication).list()
                if (providers.isEmpty()) {
                    app.nestro.agent.AgentToolResult(true, output = "当前尚未配置媒体服务商。")
                } else {
                    app.nestro.agent.AgentToolResult(
                        true,
                        output = providers.joinToString("\\n") {
                            "provider_id=${it.providerId}; name=${it.displayName}; chat_model=${it.model}; video_model=${it.videoGenerationModel.ifBlank { "未配置" }}"
                        }
                    )
                }
            }
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "media.video.generate",
                    description = "按用户当前项目和提示词调用已配置的视频生成模型。参数：prompt（必填）；provider_id（可选，指定已配置服务商）；previous_task_id（可选，用户要求修改上一版时填写）。异步提交视频任务并等待结果；若超出等待窗口则返回任务 ID，用户可继续查询。会产生第三方 API 费用。",
                    permission = app.nestro.agent.AgentToolPermission.EXTERNAL_WRITE,
                    requiresConfirmation = true
                )
            ) { arguments ->
                val projectId = arguments["__nestro_project_id"]?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "缺少当前项目范围，无法保存生成视频")
                val prompt = arguments["prompt"]?.trim()?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "视频生成缺少 prompt")
                val providerStore = MediaProviderConfigStore(this@NestroApplication)
                val selectedProviderId = arguments["provider_id"]?.trim()?.takeIf(String::isNotBlank)
                    ?: providerStore.activeProviderId()
                val config = providerStore.read(selectedProviderId)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "指定媒体服务商不存在或 API Key 无法读取；请先配置服务商或调用 media.provider.list 查看可用项")
                if (config.videoGenerationModel.isBlank()) {
                    return@register app.nestro.agent.AgentToolResult(false, error = "当前媒体服务商没有配置视频生成模型 ID")
                }
                val parentTaskId = arguments["previous_task_id"]?.trim().orEmpty()
                val task = runCatching {
                    MediaVideoGeneration.enqueue(this@NestroApplication, projectId, config.providerId, prompt, parentTaskId)
                }.getOrElse {
                    return@register app.nestro.agent.AgentToolResult(false, error = it.message ?: "视频任务提交失败")
                }
                var latest = task
                for (attempt in 0 until 75) {
                    delay(2_000)
                    latest = MediaAiTaskStore.read(this@NestroApplication, projectId, task.id) ?: latest
                    if (latest.status in setOf(MediaAiTaskStatus.COMPLETED, MediaAiTaskStatus.FAILED, MediaAiTaskStatus.CANCELLED)) break
                }
                when (latest.status) {
                    MediaAiTaskStatus.COMPLETED -> app.nestro.agent.AgentToolResult(
                        true,
                        output = "视频生成完成并已保存到当前项目素材库。任务 ID：${task.id}；素材 ID：${runCatching { org.json.JSONObject(latest.resultJson).optString("assetId") }.getOrDefault("")}；前序版本任务 ID：${task.parentTaskId.ifBlank { "无" }}。生成提示词：$prompt"
                    )
                    MediaAiTaskStatus.FAILED -> app.nestro.agent.AgentToolResult(false, error = latest.error.ifBlank { "视频生成失败" })
                    MediaAiTaskStatus.CANCELLED -> app.nestro.agent.AgentToolResult(false, error = "视频生成任务已取消")
                    else -> app.nestro.agent.AgentToolResult(
                        true,
                        output = "视频仍在后台生成。任务 ID：${task.id}；当前进度：${latest.progress}%。任务完成后会保存到当前项目素材库。"
                    )
                }
            }
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "media.video.status",
                    description = "查询当前项目的视频生成任务。参数：task_id（必填）。返回任务状态、进度和生成素材 ID。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) { arguments ->
                val projectId = arguments["__nestro_project_id"]?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "缺少当前项目范围")
                val taskId = arguments["task_id"]?.trim().orEmpty()
                val task = if (taskId.isBlank()) {
                    MediaAiTaskStore.list(this@NestroApplication, projectId).firstOrNull { it.action == "video-generate" }
                } else {
                    runCatching { MediaAiTaskStore.read(this@NestroApplication, projectId, taskId) }.getOrNull()
                } ?: return@register app.nestro.agent.AgentToolResult(false, error = "当前项目不存在该视频任务")
                val resultAssetId = if (task.resultJson.isNotBlank()) runCatching {
                    org.json.JSONObject(task.resultJson).optString("assetId")
                }.getOrDefault("") else ""
                app.nestro.agent.AgentToolResult(
                    true,
                    output = "任务 ID：${task.id}；状态：${task.status}；进度：${task.progress}%；素材 ID：${resultAssetId.ifBlank { "尚未生成" }}；前序版本任务 ID：${task.parentTaskId.ifBlank { "无" }}；提示词：${task.prompt}；错误：${task.error}"
                )
            }
        }
    }
    val mcpToolCenterRepository: McpToolCenterRepository by lazy { McpToolCenterRepository(this) }
    val mcpToolCenterService: McpToolCenterService by lazy { McpToolCenterService(mcpToolCenterRepository, agentToolCatalog, RoomAgentTaskTraceStore(database.dao())) }
    val expertTeamExecutionCoordinator: ExpertTeamExecutionCoordinator by lazy {
        ExpertTeamExecutionCoordinator(agentTaskCoordinator)
    }
    val workflowExecutionCoordinator: WorkflowExecutionCoordinator by lazy {
        WorkflowExecutionCoordinator(agentTaskCoordinator)
    }
    internal val automaticMemoryExtractionCoordinator: AutomaticMemoryExtractionCoordinator by lazy {
        AutomaticMemoryExtractionCoordinator(
            extractCandidates = { configs, messages ->
                val preferences = getSharedPreferences("nestro_local", MODE_PRIVATE)
                val profiles = ModelProfileCatalogCodec.decode(
                    preferences.getString("model_profiles_v1", "[]").orEmpty()
                )
                val registry = modelConfigurationRepository.loadRegistry(profiles)
                extractMemoryCandidates(
                    chatExecutionService,
                    configs,
                    messages,
                    onUsage = { used, input, output ->
                        recordBackgroundModelUsage(preferences, registry, used, input, output, providerReported = false)
                    },
                    onProviderUsage = { used, input, output ->
                        recordBackgroundModelUsage(preferences, registry, used, input, output, providerReported = true)
                    }
                )
            },
            saveCandidates = { projectId, conversationId, messageId, candidates ->
                userMemoryRepository.saveCandidates(projectId, conversationId, messageId, candidates)
            }
        )
    }
    internal val chatRequestContextAssembler: ChatRequestContextAssembler by lazy {
        val preferences = getSharedPreferences("nestro_local", MODE_PRIVATE)
        suspend fun readableProjectsFor(targetProjectId: String): List<String> {
            val candidates = runCatching {
                ProjectPreferencesStore.loadProjects(preferences)
                    .filter { it.archivedAt == 0L }
                    .map { it.id }
                    .toSet()
            }.getOrDefault(emptySet())
            val granted = runCatching {
                ProjectAccessControlStore.readableSourceProjects(preferences, targetProjectId, candidates)
            }.getOrDefault(emptySet())
            return (listOf(targetProjectId) + granted.sorted()).distinct()
        }
        ChatRequestContextAssembler(
            retrieveMemory = conversationContextRepository::retrieveMemory,
            retrieveUserMemory = { projectId, prompt ->
                readableProjectsFor(projectId).mapNotNull { sourceProjectId ->
                    userMemoryRepository.recall(sourceProjectId, prompt).context.takeIf { it.isNotBlank() }
                }.joinToString("\n\n")
            }
        )
    }
    internal val chatRequestExecutionCoordinator: ChatRequestExecutionCoordinator by lazy {
        ChatRequestExecutionCoordinator(
            contextAssembler = chatRequestContextAssembler,
            streamingCoordinator = chatStreamingCoordinator,
            modelToolDispatcher = ModelLedToolDispatcher(
                chat = chatExecutionService,
                catalog = agentToolCatalog,
                database = database,
                permissionRouter = app.nestro.agent.AgentPermissionRouter { task, tool ->
                    if (tool.id.startsWith("mcp_")) {
                        if (mcpToolCenterRepository.isToolGranted(tool.id)) {
                            app.nestro.agent.AgentAuthorization.Allow
                        } else {
                            app.nestro.agent.AgentAuthorization.Deny("MCP 工具授权已撤销或服务已停用")
                        }
                    } else {
                        app.nestro.agent.DefaultAgentPermissionRouter().authorize(task, tool)
                    }
                },
                toolDefinitionProvider = { toolId ->
                    mcpToolCenterRepository.findTool(toolId)?.let { descriptor ->
                        "name=${descriptor.name}; description=${descriptor.description}; input_schema=${descriptor.inputSchemaJson}"
                    }
                },
                visibleToolFilter = { tool ->
                    !tool.id.startsWith("mcp_") || mcpToolCenterRepository.isToolGranted(tool.id)
                }
            )
        )
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restoreRecoveryMutex = Mutex()
    @Volatile private var restoreRecoveryComplete = false

    suspend fun ensureBackupRestoreRecovered() {
        restoreRecoveryMutex.withLock {
            if (!restoreRecoveryComplete || localBackupRepository.hasPendingRestoreJournal()) {
                localBackupRepository.recoverInterruptedRestore(getSharedPreferences("nestro_local", MODE_PRIVATE))
                restoreRecoveryComplete = !localBackupRepository.hasPendingRestoreJournal()
            }
        }
    }

    val agentTaskHandlers: AgentTaskHandlerRegistry by lazy {
        AgentTaskHandlerRegistry().apply {
            register("phone_agent") { taskContext ->
                val dao = database.dao()
                val task = dao.phoneAgentTask(taskContext.taskId) ?: error("Phone Agent 任务不存在")
                val preferences = getSharedPreferences("nestro_local", MODE_PRIVATE)
                val result = PhoneAgentAgent.run(
                    this@NestroApplication,
                    dao,
                    taskContext.taskId,
                    preferences.getBoolean(PhoneAgentWorkScheduler.afterHandoffKey(taskContext.taskId), false),
                    preferences.getBoolean(PhoneAgentWorkScheduler.allowRetryKey(taskContext.taskId), false),
                    toolCatalog = agentToolCatalog
                )
                val store = RoomAgentTaskStateStore(dao)
                val current = store.get(taskContext.taskId)
                val status = when (result.outcome) {
                    PhoneAgentExecutor.Outcome.COMPLETED -> AgentTaskStatus.COMPLETED
                    PhoneAgentExecutor.Outcome.PAUSED -> AgentTaskStatus.PAUSED
                    PhoneAgentExecutor.Outcome.WAITING_USER -> AgentTaskStatus.WAITING_FOR_USER
                    PhoneAgentExecutor.Outcome.FAILED -> AgentTaskStatus.FAILED
                    PhoneAgentExecutor.Outcome.CANCELLED -> AgentTaskStatus.CANCELLED
                }
                if (current != null) {
                    store.put(current.copy(
                        status = status,
                        currentStep = dao.phoneAgentTask(taskContext.taskId)?.currentStep ?: task.currentStep,
                        lastError = if (status in setOf(AgentTaskStatus.PAUSED, AgentTaskStatus.WAITING_FOR_USER, AgentTaskStatus.FAILED)) result.message.take(2_000) else "",
                        updatedAt = System.currentTimeMillis()
                    ))
                }
            }
        }
    }

    val agentTaskCoordinator: AgentTaskCoordinator by lazy {
        val dao = database.dao()
        AgentTaskCoordinator(
            states = RoomAgentTaskStateStore(dao),
            handlers = agentTaskHandlers,
            scope = applicationScope,
            traces = RoomAgentTaskTraceStore(dao)
        )
    }

    val phoneAgentTaskService: PhoneAgentTaskService by lazy {
        PhoneAgentTaskService(this, database, agentTaskCoordinator)
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            ensureBackupRestoreRecovered()
            // Restore persisted calendar reminders whenever the app process starts.
            runCatching { calendarRepository.reconcileSchedules() }
            mcpToolCenterService.restoreEnabledTools()
            agentTaskCoordinator.recoverAfterProcessRestart(externallyScheduledOwners = setOf("phone_agent"))
            val activePhoneTaskIds = database.dao().allPhoneAgentTasks()
                .filter { it.status in setOf(PhoneAgentTaskStore.STATUS_PLANNED, PhoneAgentTaskStore.STATUS_RUNNING) }
                .map { it.id }
                .toSet()
            PhoneAgentWorkScheduler.reconcilePending(this@NestroApplication, activePhoneTaskIds)
        }
    }
    private fun recordBackgroundModelUsage(
        preferences: android.content.SharedPreferences,
        registry: ModelRegistry,
        config: ModelConfig,
        inputTokens: Int,
        outputTokens: Int,
        providerReported: Boolean
    ) {
        val usageId = ModelRequestConfigurationSelector.usageStatsId(registry, config) ?: return
        val current = ModelUsageStatsCodec.decode(
            preferences.getString("model_usage_v1", "{}").orEmpty()
        )
        val updated = if (providerReported) {
            ModelUsageStatsCodec.recordProviderUsage(current, usageId, inputTokens, outputTokens)
        } else {
            ModelUsageStatsCodec.recordEstimatedTokens(current, usageId, inputTokens, outputTokens)
        }
        preferences.edit().putString("model_usage_v1", ModelUsageStatsCodec.encode(updated)).apply()
    }


}
