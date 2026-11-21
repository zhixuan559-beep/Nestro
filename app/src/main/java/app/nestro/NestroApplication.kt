package app.nestro

import android.app.Application
import app.nestro.calendar.CalendarRepository
import app.nestro.mcp.McpToolCenterRepository
import app.nestro.mcp.McpToolCenterService
import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentToolCatalog
import app.nestro.agent.InMemoryAgentToolCatalog
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
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) {
                app.nestro.agent.AgentToolResult(false, error = "Phone Agent 规划必须经由聊天统一调度器调用")
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
            retrieveKnowledge = { projectId, prompt ->
                readableProjectsFor(projectId).mapNotNull { sourceProjectId ->
                    conversationContextRepository.retrieveKnowledge(sourceProjectId, prompt)
                        .takeIf { it.isNotBlank() }
                }.joinToString("\n\n")
            },
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
