package app.nestro

import android.app.Application
import app.nestro.calendar.CalendarRepository
import app.nestro.mcp.McpToolCenterRepository
import app.nestro.mcp.McpToolCenterService
import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentToolCatalog
import app.nestro.agent.InMemoryAgentToolCatalog
import app.nestro.agent.AgentToolLeaseRegistry
import app.nestro.agent.DefaultAgentRuntime
import app.nestro.agent.AgentToolRequest
import app.nestro.data.GeneratedPluginStore
import app.nestro.data.GeneratedPluginWorkflowExecutor
import app.nestro.data.GeneratedPluginPackageCodec
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
import app.nestro.phone.virtualdisplay.mika.MikaVirtualDisplayController
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
                    id = "phone_agent.execute",
                    description = "在用户授权的手机执行环境中完成多步应用/网页任务；公开媒体素材下载后自动导入当前项目。仅当目标确实需要手机操作时调用。",
                    permission = app.nestro.agent.AgentToolPermission.DEVICE_ACTION
                )
            ) {
                app.nestro.agent.AgentToolResult(false, error = "Phone Agent 执行必须经由聊天统一调度器调用")
            }
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "phone_agent.wait",
                    description = "等待同一项目中的 Phone Agent 任务完成或进入需要用户接手的状态。参数：task_id（必填）；timeout_seconds（可选，1 到 180 秒）。返回任务状态、执行记录及本任务已导入的素材 ID。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) { arguments ->
                val taskId = arguments["task_id"]?.trim()?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "等待 Phone Agent 任务缺少 task_id")
                val projectId = arguments["__nestro_project_id"]?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "缺少当前项目范围，已阻止跨项目读取任务")
                var task = database.dao().phoneAgentTask(taskId)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "Phone Agent 任务不存在或已删除")
                if (task.projectId != projectId) return@register app.nestro.agent.AgentToolResult(false, error = "任务不属于当前项目，已阻止读取")
                val timeoutSeconds = arguments["timeout_seconds"]?.toIntOrNull()?.coerceIn(1, 180) ?: 90
                val terminal = setOf(PhoneAgentTaskStore.STATUS_COMPLETED, PhoneAgentTaskStore.STATUS_FAILED, PhoneAgentTaskStore.STATUS_WAITING_USER, PhoneAgentTaskStore.STATUS_PAUSED, PhoneAgentTaskStore.STATUS_CANCELLED)
                kotlinx.coroutines.withTimeoutOrNull(timeoutSeconds * 1_000L) {
                    while (task.status !in terminal) {
                        delay(750L)
                        task = database.dao().phoneAgentTask(taskId) ?: task
                    }
                }
                val logs = database.dao().phoneAgentTaskLogs(taskId, 50).asReversed()
                val importedIds = logs.mapNotNull { log -> Regex("素材 ID：([A-Fa-f0-9-]{36})").find(log.detail)?.groupValues?.getOrNull(1) }.toSet()
                val importedAssets = runCatching { MediaProjectStore.listAssets(this@NestroApplication, projectId).filter { it.id in importedIds } }.getOrDefault(emptyList())
                val output = buildString {
                    append("任务 ID：").append(task.id).append("\n目标：").append(task.goal)
                    append("\n状态：").append(task.status).append("\n当前步骤：").append(task.currentStep)
                    append("\n最近执行记录：\n")
                    logs.takeLast(12).forEach { append("- ").append(it.action).append(" | ").append(if (it.success) "成功" else "待处理/失败").append(" | ").append(it.detail.take(700)).append("\n") }
                    if (importedAssets.isNotEmpty()) {
                        append("已导入当前项目的素材：\n")
                        importedAssets.forEach { asset -> append("- asset_id=").append(asset.id).append("; name=").append(asset.displayName).append("; kind=").append(asset.kind).append("; bytes=").append(asset.byteSize).append("; duration_ms=").append(asset.durationMs).append("; resolution=").append(asset.width).append("x").append(asset.height).append("\n") }
                    }
                    if (task.status !in terminal) append("等待窗口结束，任务仍在执行或排队；不要把它报告为完成。")
                    if (task.status == PhoneAgentTaskStore.STATUS_WAITING_USER) append("任务需要用户接手验证；暂停后续自动执行。")
                }.take(12_000)
                app.nestro.agent.AgentToolResult(true, output = output)
            }
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "media.project.assets",
                    description = "列出当前项目素材库中的可用素材，供创作工作引用。可选参数：kind（VIDEO/AUDIO/IMAGE）；limit（1 到 100）。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) { arguments ->
                val projectId = arguments["__nestro_project_id"]?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "缺少当前项目范围，已阻止跨项目读取素材")
                val kind = arguments["kind"]?.trim()?.uppercase().orEmpty()
                val limit = arguments["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 40
                val assets = runCatching { MediaProjectStore.listAssets(this@NestroApplication, projectId) }
                    .getOrElse { return@register app.nestro.agent.AgentToolResult(false, error = it.message ?: "读取项目素材失败") }
                    .filter { kind.isBlank() || it.kind.name == kind }
                    .take(limit)
                val output = if (assets.isEmpty()) "当前项目素材库没有符合条件的素材。" else assets.joinToString("\n") { asset ->
                    "asset_id=${asset.id}; name=${asset.displayName}; kind=${asset.kind}; mime=${asset.mimeType}; bytes=${asset.byteSize}; duration_ms=${asset.durationMs}; resolution=${asset.width}x${asset.height}"
                }
                app.nestro.agent.AgentToolResult(true, output = output.take(12_000))
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
                        output = providers.joinToString("\n") {
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
                    description = "查询当前项目的视频生成任务。参数：task_id（可选；省略时查询最近任务）。返回任务状态、进度和生成素材 ID。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) { arguments ->
                val projectId = arguments["__nestro_project_id"]?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "缺少当前项目范围")
                val taskId = arguments["task_id"]?.trim().orEmpty()
                val task = if (taskId.isBlank()) {
                    MediaAiTaskStore.list(this@NestroApplication, projectId).filter { it.action == "video-generate" }.maxByOrNull { it.createdAt }
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
            registerInstalledGeneratedPlugins(this)
        }
    }

    fun refreshGeneratedPluginTools() {
        val catalog = agentToolCatalog
        catalog.list().filter { it.id.startsWith("plugin.") }.forEach { catalog.remove(it.id) }
        registerInstalledGeneratedPlugins(catalog)
    }

    private fun registerInstalledGeneratedPlugins(catalog: AgentToolCatalog) {
        val store = GeneratedPluginStore(this@NestroApplication)
        val installed = store.list(catalog).filter { it.enabled }
        installed.forEach { plugin ->
            val toolId = "plugin.${plugin.id}"
            if (catalog.find(toolId) != null) return@forEach
            catalog.register(
                app.nestro.agent.AgentToolSpec(
                    id = toolId,
                    description = buildString {
                        append("执行本地插件「").append(plugin.name).append("」v").append(plugin.version)
                        append("：").append(plugin.description)
                        append("。将调用：")
                        append(plugin.steps.joinToString("、") { step ->
                            val spec = catalog.find(step.toolId)
                            "${step.toolId}（${spec?.permission?.name ?: "权限未知"}" +
                                if (spec?.requiresConfirmation == true) "，工具自身需确认）" else "）"
                        })
                    }.take(500),
                    permission = app.nestro.agent.AgentToolPermission.EXTERNAL_WRITE,
                    requiresConfirmation = true
                )
            ) { rawArguments ->
                val projectId = rawArguments["__nestro_project_id"]?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "缺少当前项目范围，插件执行已阻止")
                val inputNames = plugin.inputs.map { it.name }.toSet()
                val inputs = rawArguments.filterKeys { it in inputNames }
                val taskId = "plugin-${plugin.id}-${java.util.UUID.randomUUID()}".take(120)
                val states = RoomAgentTaskStateStore(database.dao())
                states.put(app.nestro.agent.AgentTaskState(taskId = taskId, owner = "generated-plugin:${plugin.id}", status = app.nestro.agent.AgentTaskStatus.RUNNING))
                val execution = try {
                    GeneratedPluginWorkflowExecutor.execute(plugin, inputs, catalog) { stepToolId, stepArguments ->
                        val lease = AgentToolLeaseRegistry.open(taskId, catalog, setOf(stepToolId))
                        val permissionRouter = app.nestro.agent.AgentPermissionRouter { task, tool ->
                            if (tool.id.startsWith("mcp_")) {
                                if (mcpToolCenterRepository.isToolGranted(tool.id)) app.nestro.agent.AgentAuthorization.Allow
                                else app.nestro.agent.AgentAuthorization.Deny("MCP 工具授权已撤销或服务已停用")
                            } else if (tool.permission in setOf(
                                app.nestro.agent.AgentToolPermission.READ_ONLY,
                                app.nestro.agent.AgentToolPermission.EXTERNAL_WRITE
                            )) app.nestro.agent.AgentAuthorization.Allow
                            else app.nestro.agent.AgentAuthorization.Deny("该高风险能力不能由生成插件执行")
                        }
                        val runtime = DefaultAgentRuntime(
                            catalog = lease.access,
                            permissions = permissionRouter,
                            states = states,
                            traces = RoomAgentTaskTraceStore(database.dao())
                        )
                        try {
                            runtime.execute(
                                app.nestro.agent.AgentToolRequest(
                                    taskId,
                                    stepToolId,
                                    if (stepToolId.startsWith("mcp_")) stepArguments
                                    else stepArguments + ("__nestro_project_id" to projectId)
                                )
                            )
                        } finally {
                            AgentToolLeaseRegistry.release(lease.id)
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    states.put(app.nestro.agent.AgentTaskState(taskId = taskId, owner = "generated-plugin", status = app.nestro.agent.AgentTaskStatus.PAUSED))
                    throw cancelled
                } catch (error: Exception) {
                    states.put(app.nestro.agent.AgentTaskState(taskId = taskId, owner = "generated-plugin", status = app.nestro.agent.AgentTaskStatus.FAILED, lastError = error.message.orEmpty().take(2_000)))
                    return@register app.nestro.agent.AgentToolResult(false, error = error.message ?: "插件执行失败")
                }
                states.put(app.nestro.agent.AgentTaskState(
                    taskId = taskId,
                    owner = "generated-plugin",
                    status = if (execution.success) app.nestro.agent.AgentTaskStatus.COMPLETED
                        else if (execution.requiresUser) app.nestro.agent.AgentTaskStatus.WAITING_FOR_USER
                        else app.nestro.agent.AgentTaskStatus.FAILED,
                    lastError = if (execution.success) "" else execution.output.take(2_000)
                ))
                app.nestro.agent.AgentToolResult(
                    success = execution.success,
                    output = execution.output,
                    error = if (execution.success) "" else execution.output,
                    requiresUser = execution.requiresUser
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
                    } ?: if (toolId.startsWith("plugin.")) {
                        GeneratedPluginStore(this@NestroApplication)
                            .find(toolId.removePrefix("plugin."), agentToolCatalog)
                            ?.let { plugin ->
                                "name=${plugin.name}; description=${plugin.description}; input_schema=${GeneratedPluginPackageCodec.inputSchema(plugin)}"
                            }
                    } else null
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

    /** One shared wireless-debugging helper and isolated-display session for Phone Agent. */
    val mikaVirtualDisplayController: MikaVirtualDisplayController by lazy {
        MikaVirtualDisplayController(this)
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
