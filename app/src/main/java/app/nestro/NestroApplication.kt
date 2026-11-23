package app.nestro

import android.app.Application
import app.nestro.calendar.CalendarRepository
import app.nestro.mcp.McpToolCenterRepository
import app.nestro.mcp.McpToolCenterService
import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentApprovalCoordinator
import app.nestro.agent.AgentApprovalPendingException
import app.nestro.agent.AgentToolCatalog
import app.nestro.agent.InMemoryAgentToolCatalog
import app.nestro.agent.AgentToolLeaseRegistry
import app.nestro.agent.DefaultAgentRuntime
import app.nestro.agent.AgentToolRequest
import app.nestro.data.GeneratedPluginStore
import app.nestro.data.GeneratedPluginWorkflowExecutor
import app.nestro.data.GeneratedPluginRunCheckpointCodec
import app.nestro.data.GeneratedPluginPackageCodec
import app.nestro.data.GeneratedPluginAuthorizationPolicy
import app.nestro.data.ModelLedToolDispatcher
import app.nestro.media.MediaAiTask
import app.nestro.media.MediaAiTaskStore
import app.nestro.media.MediaAiTaskStatus
import app.nestro.media.MediaAiTaskScheduler
import app.nestro.media.MediaProviderConfigStore
import app.nestro.media.MediaProjectStore
import app.nestro.media.MediaRenderWorker
import app.nestro.media.MediaVideoGeneration
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.coroutines.resume
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
import app.nestro.data.UserMemoryStore
import app.nestro.data.ConversationContextRepository
import app.nestro.data.ProjectSpaceRepository
import app.nestro.data.ProjectAccessControlStore
import app.nestro.data.CapabilityRegistryStore
import app.nestro.data.CapabilityDescriptor
import app.nestro.data.ProjectPreferencesStore
import app.nestro.data.KnowledgeRepository
import app.nestro.data.LocalBackupRepository
import app.nestro.data.ExpertTeamExecutionCoordinator
import app.nestro.data.ChatRequestContextAssembler
import app.nestro.data.ChatRequestExecutionCoordinator
import app.nestro.data.ChatRequestExecutionRequest
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
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY,
                    parallelSafe = true
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
                    id = "agent_task.wait",
                    description = "等待当前项目中的任意统一任务完成，再继续当前创作或执行流程。参数：task_id（必填，可传完整统一任务 ID 或工具返回的原始 ID）；timeout_seconds（可选，1 到 600 秒）。子任务运行时父任务会进入等待状态，子任务完成后自动恢复。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) { arguments ->
                val rawTaskId = arguments["task_id"]?.trim()?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "等待子任务缺少 task_id")
                val projectId = arguments["__nestro_project_id"]?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "缺少当前项目范围，已阻止跨项目等待任务")
                val candidates = listOf(
                    rawTaskId,
                    "phone-agent:$rawTaskId",
                    "media-ai:$rawTaskId",
                    "media-render:$rawTaskId"
                ).distinct()
                val child = candidates.firstNotNullOfOrNull { agentTaskCoordinator.state(it) }
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "统一任务机中不存在该任务：$rawTaskId")
                if (child.projectId != projectId) {
                    return@register app.nestro.agent.AgentToolResult(false, error = "子任务不属于当前项目，已阻止跨项目等待")
                }
                val timeoutSeconds = arguments["timeout_seconds"]?.toIntOrNull()?.coerceIn(1, 600) ?: 180
                val parentTaskId = arguments["__nestro_request_id"]?.trim()?.takeIf(String::isNotBlank)
                    ?: "detached-wait-${java.util.UUID.randomUUID()}"
                val completed = runCatching {
                    agentTaskCoordinator.awaitTaskCompletion(
                        parentTaskId = parentTaskId,
                        childTaskId = child.taskId,
                        timeoutMillis = timeoutSeconds * 1_000L
                    )
                }.getOrElse {
                    return@register app.nestro.agent.AgentToolResult(false, error = it.message ?: "等待子任务失败")
                }
                app.nestro.agent.AgentToolResult(
                    true,
                    output = "子任务已结束。task_id=${completed.taskId}; kind=${completed.kind}; status=${completed.status}; title=${completed.title}; result=${completed.resultSummary.ifBlank { completed.lastError }.take(2_000)}"
                )
            }
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "media.project.assets",
                    description = "列出当前项目素材库中的可用素材，供创作工作引用。可选参数：kind（VIDEO/AUDIO/IMAGE）；limit（1 到 100）。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY,
                    parallelSafe = true
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
                    description = "调用专家团队完成多专业协作。若用户已选定团队，优先使用该团队；否则主模型可根据项目目标从当前项目已启用专家目录中自主选择 2–4 位专家，拆分工作包、并行执行并汇总。仅在当前任务模式启用且至少有 2 位可用专家时可用。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) {
                app.nestro.agent.AgentToolResult(false, error = "专家团队必须经由聊天统一调度器调用")
            }
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "workflow.list",
                    description = "列出当前日历自动任务项目范围内可见的已保存工作流，返回 workflow_id、名称、步骤类型和说明。仅日历自动任务调度器可调用。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) { arguments ->
                val requestId = arguments["__nestro_request_id"].orEmpty()
                val parent = requestId.takeIf { it.isNotBlank() }?.let { agentTaskCoordinator.state(it) }
                if (parent?.owner != "calendar_automation") {
                    app.nestro.agent.AgentToolResult(false, error = "工作流列表仅对日历自动任务开放")
                } else {
                    try {
                        val projectId = arguments["__nestro_project_id"].orEmpty()
                        val preferences = getSharedPreferences("nestro_local", MODE_PRIVATE)
                        val scopes = app.nestro.data.ProjectResourceScopeStore.load(preferences)
                        val workflows = app.nestro.data.WorkflowDefinitionCodec.decode(
                            preferences.getString("workflows_v1", "[]") ?: "[]"
                        ).filter {
                            app.nestro.data.ProjectResourceScopeStore.isVisible(
                                scopes, app.nestro.data.ProjectResourceKind.WORKFLOW, it.id, projectId
                            )
                        }
                        val output = if (workflows.isEmpty()) {
                            "当前项目没有可用的已保存工作流。"
                        } else workflows.joinToString("\n") { workflow ->
                            "workflow_id=${workflow.id}; name=${workflow.name}; description=${workflow.description}; steps=" +
                                workflow.steps.filter { it.enabled }.joinToString(",") { "${it.kind.name}:${it.title}" }
                        }
                        app.nestro.agent.AgentToolResult(true, output = output.take(12_000))
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        app.nestro.agent.AgentToolResult(false, error = error.message ?: "读取工作流列表失败")
                    }
                }
            }
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "workflow.run",
                    description = "运行 workflow.list 返回的当前项目已保存工作流。只执行 PROMPT、EXPERT、SKILL 步骤；标记审批的步骤必须等待用户确认。成员不直接调用外部工具，外部能力仍由顶层调度器处理。仅日历自动任务调度器可调用。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) { arguments ->
                val requestId = arguments["__nestro_request_id"].orEmpty()
                val parent = requestId.takeIf { it.isNotBlank() }?.let { agentTaskCoordinator.state(it) }
                if (parent?.owner != "calendar_automation") {
                    app.nestro.agent.AgentToolResult(false, error = "工作流执行仅对日历自动任务开放")
                } else {
                    try {
                        val preferences = getSharedPreferences("nestro_local", MODE_PRIVATE)
                        val profiles = ModelProfileCatalogCodec.decode(
                            preferences.getString("model_profiles_v1", "[]").orEmpty()
                        )
                        val registry = modelConfigurationRepository.loadRegistry(profiles)
                        val activeModelId = preferences.getString("active_model_registry_id", "").orEmpty()
                        val failoverEnabled = preferences.getBoolean("model_failover_enabled", false)
                        val reasoningEffort = preferences.getString("reasoning_effort_v1", "low")
                        val configs = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
                            registry = registry,
                            activeModelId = activeModelId,
                            failoverEnabled = failoverEnabled,
                            reasoningEffort = reasoningEffort,
                            readKey = modelConfigurationRepository::readApiKey
                        ).ifEmpty {
                            val profileId = preferences.getString("active_model_profile_id", "").orEmpty()
                            val profile = profiles.firstOrNull { it.id == profileId }
                            val key = profile?.let { modelConfigurationRepository.readProfileKey(it.id) }.orEmpty()
                            if (profile != null && key.isNotBlank()) {
                                listOf(ModelConfig(profile.baseUrl, profile.model, key))
                            } else emptyList()
                        }
                        val runner = app.nestro.data.CalendarWorkflowRunner(
                            preferences = preferences,
                            coordinator = workflowExecutionCoordinator,
                            approvalCoordinator = agentApprovalCoordinator,
                            chatExecutionService = chatExecutionService,
                            modelConfigs = configs,
                            projectId = arguments["__nestro_project_id"].orEmpty(),
                            conversationId = arguments["__nestro_conversation_id"].orEmpty(),
                            requestId = requestId,
                            onUsage = { config, inputTokens, outputTokens ->
                                recordBackgroundModelUsage(preferences, registry, config, inputTokens, outputTokens, providerReported = false)
                            },
                            onProviderUsage = { config, inputTokens, outputTokens ->
                                recordBackgroundModelUsage(preferences, registry, config, inputTokens, outputTokens, providerReported = true)
                            }
                        )
                        val output = runner.run(
                            arguments["workflow_id"].orEmpty(),
                            arguments["input"].orEmpty()
                        )
                        app.nestro.agent.AgentToolResult(true, output = output.take(12_000))
                    } catch (pending: app.nestro.agent.AgentApprovalPendingException) {
                        throw pending
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        app.nestro.agent.AgentToolResult(false, error = error.message ?: "工作流执行失败")
                    }
                }
            }
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "media.provider.list",
                    description = "列出当前用户已配置的媒体服务商及配置字段声明的媒体能力：视觉模型、音频转写模型、视频场景接口、音频处理接口、视频生成模型。无需参数；配置存在不代表第三方接口已完成联调验证。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) {
                val providerStore = MediaProviderConfigStore(this@NestroApplication)
                val providers = providerStore.list().mapNotNull { providerStore.read(it.providerId) }
                if (providers.isEmpty()) {
                    app.nestro.agent.AgentToolResult(true, output = "当前尚未配置媒体服务商。")
                } else {
                    app.nestro.agent.AgentToolResult(
                        true,
                        output = providers.joinToString("\n") {
                            "provider_id=${it.providerId}; name=${it.displayName}; image_model_configured=${it.model.isNotBlank()}; audio_transcription_model_configured=${it.transcriptionModel.isNotBlank()}; video_scene_endpoint_configured=${it.model.isNotBlank() && it.scenePath.isNotBlank() && it.scenePath != "media/scenes"}; audio_processing_endpoint_configured=${it.model.isNotBlank() && it.audioProcessPath.isNotBlank() && it.audioProcessPath != "audio/process"}; video_generation_model_configured=${it.videoGenerationModel.isNotBlank()}; video_model=${it.videoGenerationModel.ifBlank { "未配置" }}"
                        }
                    )
                }
            }
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "media.video.generate",
                    description = "按用户当前项目和提示词调用已配置的视频生成模型。参数：prompt（必填）；provider_id（可选，指定已配置服务商）；previous_task_id（可选，用户要求修改上一版时填写）。异步提交视频任务并立即返回 task_id，后台继续生成；主模型可穿插执行其他独立任务，再通过 media.video.status 查询结果。会产生第三方 API 费用。",
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
                app.nestro.agent.AgentToolResult(
                    true,
                    output = "视频生成任务已提交并在后台执行，当前尚未完成。任务 ID：${task.id}；状态：${task.status}；前序版本任务 ID：${task.parentTaskId.ifBlank { "无" }}。任务完成后会保存到当前项目素材库；可继续执行其他独立任务，再调用 media.video.status 查询。生成提示词：$prompt"
                )
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
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "media.ai.run",
                    description = "对当前项目已导入素材执行媒体理解或处理。参数：asset_id（必填）；action（必填，image/transcribe/scenes/audio-clean）；prompt（图片理解时可选）；language（转写语言，默认 auto 自动识别）；provider_id（可选）。image=图片内容理解；transcribe=音频/视频中的语音转写；scenes=视频场景分段与描述，不等于任意视频问答；audio-clean=降噪并生成新音频素材。会把素材发送到所选第三方服务商，可能产生费用。",
                    permission = app.nestro.agent.AgentToolPermission.EXTERNAL_WRITE,
                    requiresConfirmation = true
                )
            ) { arguments ->
                val projectId = arguments["__nestro_project_id"]?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "缺少当前项目范围，已阻止跨项目媒体访问")
                val assetId = arguments["asset_id"]?.trim()?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "媒体分析缺少 asset_id")
                val action = arguments["action"]?.trim()?.lowercase()
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "媒体分析缺少 action")
                if (action !in setOf("image", "transcribe", "scenes", "audio-clean")) {
                    return@register app.nestro.agent.AgentToolResult(false, error = "不支持的媒体操作；可选 image/transcribe/scenes/audio-clean")
                }
                val asset = runCatching {
                    MediaProjectStore.listAssets(this@NestroApplication, projectId).firstOrNull { it.id == assetId }
                }.getOrElse {
                    return@register app.nestro.agent.AgentToolResult(false, error = it.message ?: "读取当前项目素材失败")
                } ?: return@register app.nestro.agent.AgentToolResult(false, error = "素材不存在或不属于当前项目")
                val kindAllowed = when (action) {
                    "image" -> asset.kind == app.nestro.media.MediaAssetKind.IMAGE
                    "transcribe" -> asset.kind in setOf(app.nestro.media.MediaAssetKind.AUDIO, app.nestro.media.MediaAssetKind.VIDEO)
                    "scenes" -> asset.kind == app.nestro.media.MediaAssetKind.VIDEO
                    "audio-clean" -> asset.kind in setOf(app.nestro.media.MediaAssetKind.AUDIO, app.nestro.media.MediaAssetKind.VIDEO)
                    else -> false
                }
                if (!kindAllowed) return@register app.nestro.agent.AgentToolResult(false, error = "素材类型与所选媒体操作不匹配")
                val providerStore = MediaProviderConfigStore(this@NestroApplication)
                val providers = providerStore.list().mapNotNull { providerStore.read(it.providerId) }
                val supportsAction: (app.nestro.media.MediaProviderConfig) -> Boolean = { config ->
                    when (action) {
                        "image" -> config.model.isNotBlank()
                        "transcribe" -> config.transcriptionModel.isNotBlank()
                        "scenes" -> config.model.isNotBlank() && config.scenePath.isNotBlank() && config.scenePath != "media/scenes"
                        "audio-clean" -> config.model.isNotBlank() && config.audioProcessPath.isNotBlank() && config.audioProcessPath != "audio/process"
                        else -> false
                    }
                }
                val requestedProviderId = arguments["provider_id"]?.trim()?.takeIf(String::isNotBlank)
                val provider = if (requestedProviderId != null) {
                    providers.firstOrNull { it.providerId == requestedProviderId && supportsAction(it) }
                } else {
                    providers.firstOrNull { it.providerId == providerStore.activeProviderId() && supportsAction(it) }
                        ?: providers.firstOrNull(supportsAction)
                } ?: return@register app.nestro.agent.AgentToolResult(
                    false,
                    error = if (requestedProviderId != null) "指定服务商未配置所需的 $action 能力" else "当前没有已配置且支持 $action 的媒体服务商；请先配置对应模型/API 路径"
                )
                val prompt = arguments["prompt"]?.trim().orEmpty().take(2_000)
                val languageTag = arguments["language"]?.trim()?.takeIf(String::isNotBlank) ?: "auto"
                val task = runCatching {
                    MediaAiTaskStore.create(
                        this@NestroApplication, projectId, asset.id, provider.providerId, action,
                        prompt = prompt, languageTag = languageTag
                    )
                }.getOrElse {
                    return@register app.nestro.agent.AgentToolResult(false, error = it.message ?: "媒体任务创建失败")
                }
                runCatching { MediaAiTaskScheduler.enqueue(this@NestroApplication, projectId, task.id) }
                    .getOrElse {
                        return@register app.nestro.agent.AgentToolResult(false, error = it.message ?: "媒体任务排队失败；任务 ID：${task.id}")
                    }
                app.nestro.agent.AgentToolResult(
                    true,
                    output = "媒体分析任务已提交并在后台执行，当前尚未完成。task_id=${task.id}; action=$action; asset_id=$assetId; provider_id=${provider.providerId}; status=${task.status}。可继续执行其他独立任务，再调用 media.ai.status 查询真实结果。"
                )
            }
            register(
                app.nestro.agent.AgentToolSpec(
                    id = "media.ai.status",
                    description = "查询当前项目媒体理解/处理任务。参数：task_id（必填）。返回真实状态、进度及结果/新素材 ID。",
                    permission = app.nestro.agent.AgentToolPermission.READ_ONLY
                )
            ) { arguments ->
                val projectId = arguments["__nestro_project_id"]?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "缺少当前项目范围")
                val taskId = arguments["task_id"]?.trim()?.takeIf(String::isNotBlank)
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "查询媒体任务缺少 task_id")
                val task = runCatching { MediaAiTaskStore.read(this@NestroApplication, projectId, taskId) }.getOrNull()
                    ?: return@register app.nestro.agent.AgentToolResult(false, error = "当前项目不存在该媒体任务")
                app.nestro.agent.AgentToolResult(true, output = formatMediaAiTask(task))
            }
            registerInstalledGeneratedPlugins(this)
        }.also { catalog ->
            val capabilityPrefs = getSharedPreferences("nestro_local", MODE_PRIVATE)
            catalog.list().forEach { spec ->
                CapabilityRegistryStore.registerIfAbsent(
                    capabilityPrefs,
                    CapabilityDescriptor(
                        id = spec.id,
                        title = spec.id,
                        category = spec.permission.name.lowercase(),
                        description = spec.description.take(500),
                        source = when {
                            spec.id.startsWith("plugin.") -> "plugin"
                            spec.id.startsWith("mcp_") -> "mcp"
                            else -> "builtin"
                        },
                        requiresConfirmation = spec.requiresConfirmation ||
                            spec.permission != app.nestro.agent.AgentToolPermission.READ_ONLY,
                        defaultGranted = !spec.id.startsWith("plugin.")
                    )
                )
            }
            CapabilityRegistryStore.registerIfAbsent(
                capabilityPrefs,
                CapabilityDescriptor(
                    id = "memory",
                    title = "项目记忆读取与自动提取",
                    category = "data",
                    description = "控制当前项目的长期记忆召回与自动候选提取",
                    source = "builtin",
                    requiresConfirmation = false,
                    defaultGranted = true
                )
            )
        }
    }

    fun refreshGeneratedPluginTools() {
        val catalog = agentToolCatalog
        val capabilityPrefs = getSharedPreferences("nestro_local", MODE_PRIVATE)
        catalog.list().filter { it.id.startsWith("plugin.") }.forEach {
            catalog.remove(it.id)
            CapabilityRegistryStore.unregister(capabilityPrefs, it.id)
        }
        registerInstalledGeneratedPlugins(catalog)
    }

    private fun registerInstalledGeneratedPlugins(catalog: AgentToolCatalog) {
        val capabilityPrefs = getSharedPreferences("nestro_local", MODE_PRIVATE)
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
                val requestId = rawArguments["__nestro_request_id"]?.trim()?.take(120).orEmpty()
                    .ifBlank { java.util.UUID.randomUUID().toString() }
                val inputFingerprint = GeneratedPluginRunCheckpointCodec.fingerprintInputs(inputs)
                val idempotencyKey = "plugin:" + java.security.MessageDigest.getInstance("SHA-256")
                    .digest("$projectId|${plugin.id}|$requestId|$inputFingerprint".toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val taskDigest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(idempotencyKey.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val taskId = "plugin-${taskDigest.take(48)}"
                val states = RoomAgentTaskStateStore(database.dao())
                val existingTask = states.getByIdempotencyKey(idempotencyKey)
                if (existingTask != null) {
                    if (existingTask.taskId != taskId) {
                        return@register app.nestro.agent.AgentToolResult(
                            false,
                            error = "该插件请求存在旧版任务记录，不能安全自动恢复。请核对执行审计后重新发起。task_id=${existingTask.taskId}"
                        )
                    }
                    when (existingTask.status) {
                        app.nestro.agent.AgentTaskStatus.COMPLETED -> return@register app.nestro.agent.AgentToolResult(
                            true,
                            output = existingTask.resultSummary.ifBlank { "该插件请求此前已完成。" } +
                                "\ntask_id=${existingTask.taskId}"
                        )
                        app.nestro.agent.AgentTaskStatus.QUEUED,
                        app.nestro.agent.AgentTaskStatus.RUNNING,
                        app.nestro.agent.AgentTaskStatus.RETRY_WAIT -> return@register app.nestro.agent.AgentToolResult(
                            false,
                            error = "该插件请求已在执行或等待重试，已阻止重复启动。task_id=${existingTask.taskId}; status=${existingTask.status.name}"
                        )
                        app.nestro.agent.AgentTaskStatus.FAILED,
                        app.nestro.agent.AgentTaskStatus.CANCELLED -> return@register app.nestro.agent.AgentToolResult(
                            false,
                            error = "该插件请求此前以不确定状态结束。为避免重复外部写入，未自动重放；请核对执行审计后重新发起。task_id=${existingTask.taskId}"
                        )
                        else -> Unit
                    }
                }
                val conversationId = rawArguments["__nestro_conversation_id"].orEmpty().take(120)
                val recoveryPayload = "request_id=$requestId\n" + JSONObject().apply {
                    inputs.forEach { (name, value) -> put(name, value) }
                }.toString()
                require(recoveryPayload.length <= 48_000) { "插件恢复输入超过统一任务记录限制" }
                val coordinator = agentTaskCoordinator
                val execution = try {
                    coordinator.runInline(
                        taskId = taskId,
                        owner = "generated-plugin:${plugin.id}".take(80),
                        initialCheckpoint = GeneratedPluginRunCheckpointCodec.encode(plugin, inputs, emptyList()),
                        metadata = app.nestro.agent.AgentTaskMetadata(
                            projectId = projectId,
                            conversationId = conversationId,
                            kind = "generated_plugin",
                            capabilityId = plugin.id,
                            title = plugin.name.take(160),
                            input = recoveryPayload
                        ),
                        idempotencyKey = idempotencyKey,
                        // Plugin tool failures may have an unknown external-write outcome.
                        // Retry policy is centralized separately after per-step idempotency is complete.
                        maxRetries = 0
                    ) { taskContext ->
                        val savedCheckpoint = coordinator.state(taskId)?.checkpoint.orEmpty()
                        val result = GeneratedPluginWorkflowExecutor.execute(
                            plugin = plugin,
                            inputs = inputs,
                            discovery = catalog,
                            resumeCheckpoint = savedCheckpoint,
                            onStepCheckpoint = { currentStep, checkpoint ->
                                taskContext.checkpoint(checkpoint, currentStep)
                            },
                            invokeThroughRuntime = { stepToolId, stepArguments ->
                            val lease = AgentToolLeaseRegistry.open(taskId, catalog, setOf(stepToolId))
                            val permissionRouter = app.nestro.agent.AgentPermissionRouter { task, tool ->
                                val baseAuthorization = if (!CapabilityRegistryStore.authorize(capabilityPrefs, task.projectId, tool.id)) {
                                    app.nestro.agent.AgentAuthorization.Deny("当前项目未授权该能力，或能力授权审计无法保存")
                                } else if (tool.id.startsWith("mcp_")) {
                                    val descriptor = mcpToolCenterRepository.findTool(tool.id)
                                    val globallyGranted = mcpToolCenterRepository.isToolGranted(tool.id)
                                    val projectGranted = descriptor?.let {
                                        CapabilityRegistryStore.authorizeToolCall(
                                            capabilityPrefs, task.projectId, it.serverId, it.name,
                                            defaultGranted = globallyGranted,
                                            globallyEnabled = globallyGranted
                                        )
                                    } == true
                                    if (globallyGranted && projectGranted) GeneratedPluginAuthorizationPolicy.authorize(tool)
                                    else app.nestro.agent.AgentAuthorization.Deny("MCP 工具未获当前项目授权，或服务授权已撤销")
                                } else GeneratedPluginAuthorizationPolicy.authorize(tool)

                                if (baseAuthorization is app.nestro.agent.AgentAuthorization.RequireConfirmation) {
                                    taskContext.waitForUser(baseAuthorization.reason)
                                    when (agentApprovalCoordinator.authorize(requestId, tool.id, baseAuthorization.reason)) {
                                        app.nestro.agent.AgentApprovalDecision.AUTHORIZE_ONCE,
                                        app.nestro.agent.AgentApprovalDecision.AUTHORIZE_TASK -> {
                                            taskContext.transition(
                                                AgentTaskStatus.RUNNING,
                                                "用户已批准插件步骤：${tool.id}"
                                            )
                                            app.nestro.agent.AgentAuthorization.Allow
                                        }
                                        app.nestro.agent.AgentApprovalDecision.REJECT ->
                                            app.nestro.agent.AgentAuthorization.Deny("用户未批准本次插件步骤：${tool.id}")
                                        null -> throw app.nestro.agent.AgentApprovalPendingException(
                                            "插件步骤等待用户审批：${tool.id}"
                                        )
                                    }
                                } else baseAuthorization
                            }
                            val runtime = DefaultAgentRuntime(
                                catalog = lease.access,
                                permissions = permissionRouter,
                                states = states,
                                traces = RoomAgentTaskTraceStore(database.dao()),
                                executionContext = taskContext
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
                        )
                        when {
                            result.requiresUser -> taskContext.waitForUser(result.output.ifBlank { "插件步骤需要用户确认" })
                            !result.success -> taskContext.fail(result.output.take(2_000).ifBlank { "插件步骤执行失败" })
                            else -> taskContext.transition(
                                AgentTaskStatus.COMPLETED,
                                resultSummary = result.output.take(8_000)
                            )
                        }
                        result
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return@register app.nestro.agent.AgentToolResult(
                        false,
                        error = error.message?.take(2_000) ?: "插件执行失败"
                    )
                }
                app.nestro.agent.AgentToolResult(
                    success = execution.success,
                    output = execution.output,
                    error = if (execution.success) "" else execution.output,
                    requiresUser = execution.requiresUser
                )
            }
            CapabilityRegistryStore.registerIfAbsent(
                capabilityPrefs,
                CapabilityDescriptor(
                    id = toolId,
                    title = plugin.name.take(80),
                    category = "plugin",
                    description = plugin.description.take(500),
                    source = "plugin",
                    requiresConfirmation = true,
                    defaultGranted = false
                )
            )
        }
    }

    val mcpToolCenterRepository: McpToolCenterRepository by lazy { McpToolCenterRepository(this) }
    val mcpToolCenterService: McpToolCenterService by lazy { McpToolCenterService(mcpToolCenterRepository, agentToolCatalog, RoomAgentTaskTraceStore(database.dao()), agentTaskCoordinator, agentApprovalCoordinator) }
    val expertTeamExecutionCoordinator: ExpertTeamExecutionCoordinator by lazy {
        ExpertTeamExecutionCoordinator(agentTaskCoordinator)
    }
    val workflowExecutionCoordinator: WorkflowExecutionCoordinator by lazy {
        WorkflowExecutionCoordinator(agentTaskCoordinator)
    }
    internal val automaticMemoryExtractionCoordinator: AutomaticMemoryExtractionCoordinator by lazy {
        AutomaticMemoryExtractionCoordinator(
            extractCandidates = { configs, messages, existingMemories ->
                val preferences = getSharedPreferences("nestro_local", MODE_PRIVATE)
                val profiles = ModelProfileCatalogCodec.decode(
                    preferences.getString("model_profiles_v1", "[]").orEmpty()
                )
                val registry = modelConfigurationRepository.loadRegistry(profiles)
                extractMemoryCandidates(
                    chatExecutionService,
                    configs,
                    messages,
                    existingMemories = existingMemories,
                    onUsage = { used, input, output ->
                        recordBackgroundModelUsage(preferences, registry, used, input, output, providerReported = false)
                    },
                    onProviderUsage = { used, input, output ->
                        recordBackgroundModelUsage(preferences, registry, used, input, output, providerReported = true)
                    }
                )
            },
            saveCandidates = { projectId, conversationId, messageId, candidates, mutationVersion ->
                userMemoryRepository.saveCandidates(projectId, conversationId, messageId, candidates, mutationVersion)
            },
            loadExistingMemories = { projectId -> userMemoryRepository.list(projectId) }
        )
    }
    internal val chatRequestContextAssembler: ChatRequestContextAssembler by lazy {
        val preferences = getSharedPreferences("nestro_local", MODE_PRIVATE)
        fun readableProjectsFor(targetProjectId: String): List<String> {
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
                if (!CapabilityRegistryStore.authorize(preferences, projectId, "memory")) {
                    ""
                } else {
                    val readableSources = readableProjectsFor(projectId).filterNot { it == projectId }.toSet()
                    userMemoryRepository.recall(projectId, prompt, readableSources).context
                }
            },
            memoryVersion = { projectId ->
                val memoryAuthorized = CapabilityRegistryStore.authorize(preferences, projectId, "memory")
                "authorized=$memoryAuthorized|" + readableProjectsFor(projectId).joinToString("|") { sourceId ->
                    val token = UserMemoryStore.mutationVersion(sourceId)
                    "$sourceId:${token.projectVersion}:${token.globalVersion}"
                }
            },
            conversationRecallVersion = conversationContextRepository::recallVersion
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
                capabilityPreferences = getSharedPreferences("nestro_local", MODE_PRIVATE),
                approvalCoordinator = agentApprovalCoordinator,
                taskCoordinator = agentTaskCoordinator,
                permissionRouter = app.nestro.agent.AgentPermissionRouter { task, tool ->
                    if (tool.id.startsWith("mcp_")) {
                        val descriptor = mcpToolCenterRepository.findTool(tool.id)
                        val globallyGranted = mcpToolCenterRepository.isToolGranted(tool.id)
                        val projectGranted = descriptor?.let {
                            CapabilityRegistryStore.authorizeToolCall(
                                getSharedPreferences("nestro_local", MODE_PRIVATE),
                                task.projectId,
                                it.serverId,
                                it.name,
                                defaultGranted = globallyGranted,
                                globallyEnabled = globallyGranted
                            )
                        } == true
                        if (globallyGranted && projectGranted) {
                            // Persistent MCP grant exposes the capability; it does not replace
                            // per-task confirmation for tools declared as external writes.
                            app.nestro.agent.DefaultAgentPermissionRouter().authorize(task, tool)
                        } else {
                            app.nestro.agent.AgentAuthorization.Deny("MCP 工具未获当前项目授权，或服务授权已撤销")
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
                    taskContext = taskContext,
                    afterUserHandoff = preferences.getBoolean(PhoneAgentWorkScheduler.afterHandoffKey(taskContext.taskId), false),
                    allowRetryCurrentAction = preferences.getBoolean(PhoneAgentWorkScheduler.allowRetryKey(taskContext.taskId), false),
                    toolCatalog = agentToolCatalog
                )
                val currentStep = dao.phoneAgentTask(taskContext.taskId)?.currentStep ?: task.currentStep
                when (result.outcome) {
                    PhoneAgentExecutor.Outcome.COMPLETED ->
                        taskContext.transition(AgentTaskStatus.COMPLETED, currentStep = currentStep)
                    PhoneAgentExecutor.Outcome.PAUSED -> taskContext.pause(result.message.take(2_000))
                    PhoneAgentExecutor.Outcome.WAITING_USER -> taskContext.waitForUser(result.message.take(2_000))
                    PhoneAgentExecutor.Outcome.FAILED -> taskContext.fail(result.message.take(2_000))
                    PhoneAgentExecutor.Outcome.CANCELLED -> taskContext.cancel()
                }
            }
            register("calendar_automation") { taskContext ->
                val state = requireNotNull(agentTaskCoordinator.state(taskContext.taskId)) {
                    "日历自动任务状态不存在"
                }
                val payload = org.json.JSONObject(state.input)
                val prompt = payload.optString("prompt").trim().take(12_000)
                val eventId = payload.optString("eventId")
                val automationRepository = app.nestro.calendar.CalendarRepository(this@NestroApplication)
                automationRepository.recordAutomationResult(eventId, "RUNNING")
                try {
                require(prompt.isNotBlank() && state.projectId.isNotBlank()) {
                    "日历自动任务缺少执行目标或项目范围"
                }
                val preferences = getSharedPreferences("nestro_local", MODE_PRIVATE)
                val profiles = ModelProfileCatalogCodec.decode(
                    preferences.getString("model_profiles_v1", "[]").orEmpty()
                )
                val registry = modelConfigurationRepository.loadRegistry(profiles)
                val activeModelId = preferences.getString("active_model_registry_id", "").orEmpty()
                val failoverEnabled = preferences.getBoolean("model_failover_enabled", false)
                val reasoningEffort = preferences.getString("reasoning_effort_v1", "low")
                val configs = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
                    registry = registry,
                    activeModelId = activeModelId,
                    failoverEnabled = failoverEnabled,
                    reasoningEffort = reasoningEffort,
                    readKey = modelConfigurationRepository::readApiKey
                ).ifEmpty {
                    val profileId = preferences.getString("active_model_profile_id", "").orEmpty()
                    val profile = profiles.firstOrNull { it.id == profileId }
                    val key = profile?.let { modelConfigurationRepository.readProfileKey(it.id) }.orEmpty()
                    if (profile != null && key.isNotBlank()) {
                        listOf(ModelConfig(profile.baseUrl, profile.model, key))
                    } else emptyList()
                }
                require(configs.isNotEmpty()) { "日历自动任务没有可用的已选模型或 API Key" }
                val conversationId = "calendar-$eventId".take(120)
                val requestId = taskContext.taskId
                val recordUsage: (ModelConfig, Int, Int) -> Unit = { config, inputTokens, outputTokens ->
                    recordBackgroundModelUsage(preferences, registry, config, inputTokens, outputTokens, providerReported = false)
                }
                val recordProviderUsage: (ModelConfig, Int, Int) -> Unit = { config, inputTokens, outputTokens ->
                    recordBackgroundModelUsage(preferences, registry, config, inputTokens, outputTokens, providerReported = true)
                }
                val expertRunner = app.nestro.data.CalendarExpertTeamRunner(
                    preferences = preferences,
                    coordinator = expertTeamExecutionCoordinator,
                    chatExecutionService = chatExecutionService,
                    modelConfigs = configs,
                    projectId = state.projectId,
                    conversationId = conversationId,
                    parentTaskId = taskContext.taskId,
                    onUsage = recordUsage,
                    onProviderUsage = recordProviderUsage
                )
                var latestPartialAnswer = ""
                val execution = chatRequestExecutionCoordinator.execute(
                    request = ChatRequestExecutionRequest(
                        requestId = requestId,
                        conversationId = conversationId,
                        projectId = state.projectId,
                        prompt = prompt,
                        startedAt = System.currentTimeMillis(),
                        history = emptyList(),
                        modelConfigs = configs,
                        baseSystemContext = "这是用户在日历中明确设置的定时自动任务。请按目标完成工作；普通回答直接回答，需要外部能力时由主模型按权限和审批规则调用已接入工具。当前项目为：${state.projectId}。事件 ID：$eventId。",
                        taskModeEnabled = true,
                        expertTeamEnabled = expertRunner.hasAvailableTeam(),
                        parentTaskId = taskContext.taskId,
                        temperature = 0.0,
                        maxTokens = null,
                        previousAssistant = null,
                        shouldStop = { false }
                    ),
                    onUsage = recordUsage,
                    onProviderUsage = recordProviderUsage,
                    planPhoneAgent = { arguments ->
                        val goal = arguments["goal"]?.trim()?.takeIf(String::isNotBlank) ?: prompt
                        val plan = app.nestro.phone.PhoneAgentPlanner.plan(goal)
                        val created = phoneAgentTaskService.createAndScheduleIfAuthorized(
                            state.projectId,
                            conversationId,
                            plan
                        )
                        app.nestro.agent.AgentToolResult(
                            true,
                            output = "已创建 Phone Agent 任务：${created.id}；当前状态：${created.status}。"
                        )
                    },
                    runExpertTeam = { systemContext, arguments ->
                        expertRunner.run(systemContext, arguments)
                    },
                    onPartialAnswer = { partial ->
                        latestPartialAnswer = partial.take(24_000)
                    }
                    )
                val finalText = execution.finalization.finalText.take(8_000)
                val executionFailure = execution.failure
                if (execution.finalization.status.name == "FAILURE" &&
                    executionFailure != null &&
                    app.nestro.agent.AgentTaskRetryPolicy.isRetryable(executionFailure) &&
                    state.attempts < state.maxRetries
                ) {
                    throw executionFailure
                }
                taskContext.checkpoint("calendar_output:" + finalText.ifBlank { latestPartialAnswer }.take(24_000))
                val child = agentTaskCoordinator.state("chat-tools-$requestId".take(120))
                when {
                    child?.status == AgentTaskStatus.WAITING_FOR_USER -> {
                        val reason = child.lastError.ifBlank { "日历自动任务等待工具审批" }
                        automationRepository.recordAutomationResult(eventId, "WAITING_FOR_USER", error = reason)
                        taskContext.waitForUser(reason)
                    }
                    child?.status == AgentTaskStatus.WAITING_FOR_TASK -> {
                        val reason = child.lastError.ifBlank { "日历自动任务等待子任务完成" }
                        automationRepository.recordAutomationResult(eventId, "WAITING_FOR_TASK", error = reason)
                        taskContext.transition(AgentTaskStatus.WAITING_FOR_TASK, reason)
                    }
                    child?.status == AgentTaskStatus.PAUSED -> {
                        val reason = child.lastError.ifBlank { "日历自动任务已暂停" }
                        automationRepository.recordAutomationResult(eventId, "PAUSED", error = reason)
                        taskContext.pause(reason)
                    }
                    child?.status == AgentTaskStatus.FAILED && child.lastError.contains("未批准") -> {
                        automationRepository.recordAutomationResult(eventId, "FAILED", error = child.lastError)
                        taskContext.fail(child.lastError)
                    }
                    execution.finalization.status.name == "SUCCESS" -> {
                        automationRepository.recordAutomationResult(eventId, "COMPLETED", result = finalText)
                        taskContext.transition(AgentTaskStatus.COMPLETED, resultSummary = finalText)
                    }
                    execution.finalization.status.name == "STOPPED" -> {
                        automationRepository.recordAutomationResult(eventId, "CANCELLED", error = "日历自动任务已停止")
                        taskContext.cancel("日历自动任务已停止")
                    }
                    else -> {
                        val error = finalText.ifBlank { "日历自动任务执行失败" }
                        automationRepository.recordAutomationResult(eventId, "FAILED", error = error)
                        taskContext.fail(error)
                    }
                }
                } catch (pending: AgentApprovalPendingException) {
                    val reason = pending.message.orEmpty().take(2_000)
                    automationRepository.recordAutomationResult(eventId, "WAITING_FOR_USER", error = reason)
                    taskContext.waitForUser(reason)
                    throw pending
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    automationRepository.recordAutomationResult(eventId, "CANCELLED", error = "日历自动任务已取消")
                    throw cancelled
                } catch (error: Exception) {
                    val reason = (error.message ?: error.javaClass.simpleName).take(2_000)
                    val retryableBeforeSideEffects = app.nestro.agent.AgentTaskRetryPolicy.isRetryable(error) &&
                        state.attempts < state.maxRetries
                    automationRepository.recordAutomationResult(
                        eventId,
                        if (retryableBeforeSideEffects) "QUEUED" else "FAILED",
                        error = reason
                    )
                    taskContext.fail(reason)
                    throw error
                }
            }
        }
    }

    val agentApprovalCoordinator: AgentApprovalCoordinator by lazy {
        fun app.nestro.data.AgentApprovalAuditEntity.toRecord() =
            app.nestro.agent.AgentApprovalAuditRecord(id, scopeId, toolId, reason, status, createdAt, updatedAt)

        AgentApprovalCoordinator(
            persistAudit = { record ->
                database.dao().putAgentApprovalAudit(
                    app.nestro.data.AgentApprovalAuditEntity(
                        id = record.id,
                        scopeId = record.scopeId,
                        toolId = record.toolId,
                        reason = record.reason,
                        status = record.status,
                        createdAt = record.createdAt,
                        updatedAt = record.updatedAt
                    )
                )
            },
            closeActiveAudits = { scopeId, status, updatedAt ->
                database.dao().closeActiveAgentApprovalAudits(scopeId, status, updatedAt)
            },
            loadPendingAudits = {
                database.dao().pendingAgentApprovalAudits().map { it.toRecord() }
            },
            loadAudit = { id ->
                database.dao().agentApprovalAudit(id)?.toRecord()
            },
            loadAuditsForScope = { scopeId ->
                database.dao().agentApprovalAuditsForScope(scopeId).map { it.toRecord() }
            },
            loadResumableAudits = {
                database.dao().resumableAgentApprovalAudits().map { it.toRecord() }
            },
            resumeTask = { scopeId ->
                val state = agentTaskCoordinator.state(scopeId)
                if (state != null) {
                    val restartReason = "应用曾在任务执行期间退出；请核对当前状态后继续"
                    val resumable = state.status == AgentTaskStatus.WAITING_FOR_USER ||
                        (state.status == AgentTaskStatus.PAUSED && state.lastError == restartReason)
                    if (resumable) {
                        when (state.kind) {
                            "mcp_tool" -> applicationScope.launch {
                                runCatching { mcpToolCenterService.resume(scopeId) }
                            }
                            "media_ai" -> MediaAiTaskScheduler.resume(this@NestroApplication, state.projectId, state.input)
                            "media_render" -> MediaRenderWorker.resume(this@NestroApplication, state.projectId, state.input)
                            "calendar_automation" -> agentTaskCoordinator.resume(scopeId)
                            "phone_agent" -> phoneAgentTaskService.enqueue(scopeId)
                            // Workflow, expert-team and chat/plugin runInline handlers are
                            // re-entered by their owning coordinator or restored UI. Enqueuing
                            // them here without re-registering their handler would fail closed.
                            else -> Unit
                        }
                    }
                }
                Unit
            }
        )
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
            val recoveredTasks = agentTaskCoordinator.recoverAfterProcessRestart(
                externallyScheduledOwners = setOf("phone_agent")
            )
            val processRestartReason = "应用曾在任务执行期间退出；请核对当前状态后继续"
            recoveredTasks
                .filter {
                    it.owner == "calendar_automation" &&
                        it.status == AgentTaskStatus.PAUSED &&
                        it.lastError == processRestartReason
                }
                .forEach { task ->
                    runCatching { agentTaskCoordinator.resume(task.taskId) }
                }
            agentApprovalCoordinator.resumeResolvedTasks()
            val activePhoneTaskIds = database.dao().allAgentTaskStates()
                .filter {
                    it.owner == "phone_agent" &&
                        it.status in setOf(
                            AgentTaskStatus.QUEUED.name,
                            AgentTaskStatus.RUNNING.name,
                            AgentTaskStatus.RETRY_WAIT.name
                        )
                }
                .map { it.taskId }
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



    private fun formatMediaAiTask(task: MediaAiTask): String {
        val prefix = "任务 ID：${task.id}；操作：${task.action}；状态：${task.status}；进度：${task.progress}%"
        if (task.status != MediaAiTaskStatus.COMPLETED) {
            return (prefix + if (task.error.isNotBlank()) "；错误：${task.error}" else "；任务仍在后台执行").take(12_000)
        }
        val result = runCatching { org.json.JSONObject(task.resultJson) }.getOrNull()
            ?: return "$prefix；任务完成，但结果数据格式无效。"
        val output = when (result.optString("type")) {
            "image" -> result.optString("text")
            "transcribe" -> {
                val cues = result.optJSONArray("cues") ?: org.json.JSONArray()
                (0 until cues.length()).joinToString("\n") { index ->
                    val cue = cues.optJSONObject(index) ?: return@joinToString ""
                    "[${cue.optLong("startMs") / 1000}s-${cue.optLong("endMs") / 1000}s] ${cue.optString("text")}"
                }
            }
            "scenes" -> {
                val scenes = result.optJSONArray("scenes") ?: org.json.JSONArray()
                (0 until scenes.length()).joinToString("\n") { index ->
                    val scene = scenes.optJSONObject(index) ?: return@joinToString ""
                    "[${scene.optLong("startMs") / 1000}s-${scene.optLong("endMs") / 1000}s] ${scene.optString("title")}：${scene.optString("description")}；建议字幕：${scene.optString("caption")}"
                }
            }
            "audio" -> "处理后音频已导入当前项目素材库。素材 ID：${result.optString("assetId")}；名称：${result.optString("displayName")}"
            else -> task.resultJson
        }
        return (prefix + "；结果：\n" + output).take(12_000)
    }
}
