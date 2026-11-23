# NESTRO 当前代码地图

> 静态审计基线：`integration/19-modules`，当前业务源码修复基线 `e06c42d31beef78e0ad75059349c69cbd3d6b3d8`（2026-11-24；后续审计以该分支最新 HEAD 为准）。
> 防断档中控：GitHub Issue #21（构建前总审计与十九模块收口）。本地图正在按新基线重新核对；不得将旧代码地图当作当前完整审计结果。
>
> 当前树：450 个 Git tree 条目，其中 Kotlin 源码/测试文件 337 个（主源码 205 个、JVM 测试 98 个、Android instrumentation 测试 34 个）。本文按实际源码路径记录模块落点和跨模块调用链；它是代码导航图，不代表构建或验收通过。
> 功能是否完整以 `FUNCTION_AUDIT.md` 为准；十九模块需求范围以 `NESTRO_19_MODULES.md` 为准；未完成事项以 `IMPLEMENTATION_ROADMAP.md` 为准。

## 十九模块源码落点

| 模块 | 当前主要实现 | 主要测试/验证源码 |
|---|---|---|
| 1 专家系统 | `data/ExpertTeamEngine.kt`、`data/ExpertTeamExecutionCoordinator.kt`、`data/ExpertProjectPlanExecutor.kt`、`data/ExpertTemplateCatalog.kt`、`data/ModelLedToolDispatcher.kt`、`ui/ExpertWorkspaceUiState.kt`（协作历史分页/暂停控制） | `test/.../ExpertTeamEngineTest.kt`、`ExpertTeamExecutionCoordinatorTest.kt`、`ExpertProjectPlanExecutorTest.kt`、`ui/ExpertTeamEventPaginationTest.kt`、`ui/ExpertTeamPauseControllerTest.kt` |
| 2 模型管理 | `model/ModelRegistry.kt`、`model/ModelRegistryStore.kt`、`model/ModelConfigurationRepository.kt`、`model/ModelConnectionService.kt`、`model/ModelDiscoveryService.kt`、`ui/ModelManagerPage.kt`、`ui/ModelManagementUiState.kt` | `test/.../ModelProfileCatalogTest.kt`、`ModelToolDecisionParserTest.kt` |
| 3 聊天系统 | `data/ChatMessage.kt`、`data/ChatRequestContextAssembler.kt`、`data/ChatRequestExecutionCoordinator.kt`、`data/ChatRequestRecoveryCoordinator.kt`、`data/ModelLedToolDispatcher.kt`、`data/OpenAiClient.kt`、`model/ChatExecutionService.kt`、`ui/NestroApp.kt` | `test/.../ChatRequestContextAssemblerTest.kt`、`ChatRequestExecutionCoordinatorTest.kt`、`ChatRequestRecoveryCoordinatorTest.kt`、`model/ChatStreamingCoordinatorTest.kt` |
| 4 数据与备份 | `data/NestroDatabase.kt`、`data/ConversationPersistenceService.kt`、`data/LocalBackupRepository.kt`、`data/LocalBackupCompatibility.kt`、`data/ProjectPackageCodec.kt` | `androidTest/.../NestroDatabaseMigrationTest.kt`、`ui/LocalBackupRoundTripTest.kt`、`test/.../ProjectPackageCodecTest.kt` |
| 5 项目空间 | `data/ProjectSpaceRepository.kt`、`data/ProjectAccessControlStore.kt`、`data/ProjectResourceScopeStore.kt`、`data/ProjectSpaceRules.kt` | `androidTest/.../ProjectSpaceLifecycleTest.kt`、`ProjectAccessControlStoreTest.kt`、`test/.../ProjectResourceScopeStoreTest.kt` |
| 6 知识库 | `data/KnowledgeRepository.kt`、`data/KnowledgeSearchEngine.kt`、`data/KnowledgeVectorSearch.kt`、`data/KnowledgeEmbeddingModelManager.kt`、`data/KnowledgePdfOcr.kt`、`data/KnowledgeChunker.kt` | `test/.../KnowledgeSearchEngineTest.kt`、`KnowledgeEmbeddingPoolingTest.kt`、`androidTest/.../KnowledgeVectorIndexDatabaseTest.kt`、`KnowledgePdfImportTest.kt` |
| 7 我的记忆 | `data/UserMemoryStore.kt`、`data/UserMemoryRepository.kt`、`data/AutomaticMemoryExtractionCoordinator.kt`、`data/ConversationMemoryStore.kt`、`data/ProjectMemorySettingsStore.kt`、`ui/MemoryUiState.kt` | `test/.../UserMemoryStoreTest.kt`、`AutomaticMemoryExtractionCoordinatorTest.kt`、`androidTest/.../UserMemoryRepositoryInstrumentedTest.kt` |
| 8 Phone Agent | `data/PhoneAgentTaskStore.kt`、`phone/PhoneAgentTaskService.kt`、`phone/PhoneAgentWorkScheduler.kt`、`phone/PhoneAgentWorker.kt`、`phone/PhoneAgentExecutor.kt`、`phone/PhoneAgentService.kt`、`phone/virtualdisplay/mika/MikaVirtualDisplayController.kt`、`phone/virtualdisplay/mika/MikaVirtualDisplayService.kt`、`ui/PhoneAgentPage.kt`（浮动预览交互） | `test/.../PhoneAgentTaskStoreTest.kt`、各 PhoneAgent policy tests |
| 9 语音系统 | `speech/AndroidSpeechRecognitionController.kt`、`speech/AndroidTextToSpeechController.kt`、`speech/OpenAiCompatibleSpeechProviders.kt`、`speech/OpenAiRealtimeVoiceProvider.kt`、`ui/VoiceInputButton.kt`、`ui/VoiceSystemPage.kt` | `test/.../SpeechProvidersTest.kt`、`SpeechHttpPolicyTest.kt`、`androidTest/.../VoiceInputButtonTest.kt` |
| 10 Artifacts | `data/ArtifactStore.kt`、`ui/ArtifactLibraryPage.kt` | `test/.../ArtifactStoreBackupTest.kt`、`ArtifactStoreReferenceTest.kt`、`androidTest/.../ArtifactStoreTest.kt` |
| 11 专家模板 | `data/ExpertTemplateCatalog.kt`、`data/ExpertProfileCodec.kt`、`data/ExpertRevisionAndTemplate.kt` | `test/.../ExpertTemplateCatalogTest.kt`、`ExpertProfileCodecTest.kt` |
| 12 模型对比 | `model/ModelComparisonCoordinator.kt`、`ui/ModelComparisonPage.kt` | `test/.../ModelComparisonPolicyTest.kt` |
| 13 MCP 工具中心 | `mcp/McpStreamableHttpClient.kt`、`mcp/McpToolCenter.kt`、`mcp/McpToolCenterPage.kt` | `test/.../McpToolCenterTest.kt` |
| 14 GitHub 工作台 | `git/GitWorkbench.kt`、`git/GitWorkbenchPage.kt` | `test/.../GitWorkbenchPolicyTest.kt`、`androidTest/.../GitAccountStoreTest.kt` |
| 15 AI 共建工作台 | `coworkbench/AiCoworkbenchPage.kt`、`coworkbench/AiCoworkbenchService.kt`、`coworkbench/CoworkbenchDataFlowAnalyzer.kt`、`coworkbench/GitHubActionsCoworkbenchRunner.kt` | `test/.../CoworkbenchPolicyTest.kt`、`CoworkbenchStaticAnalyzerTest.kt`、`CoworkbenchReportParserTest.kt` |
| 16 统一能力中心 | `data/CapabilityRegistryStore.kt`、`agent/AgentRuntime.kt`、`agent/AgentTaskCoordinator.kt`、`agent/RoomAgentTaskStateStore.kt`、`agent/RoomAgentTaskTraceStore.kt`、`agent/AgentTaskTrace.kt` | `test/.../AgentRuntimeTest.kt`、`AgentTaskCoordinatorTest.kt`、`androidTest/.../CapabilityRegistryStoreTest.kt` |
| 17 智能剪辑 | `media/MediaEditorPage.kt`、`media/MediaProjectStore.kt`、`media/MediaRenderEngine.kt`、`media/MediaRenderTaskStore.kt`、`media/MediaRenderWorker.kt`、`media/MediaVideoGenerationClient.kt` | `test/.../MediaProjectCodecTest.kt`、`androidTest/.../MediaProjectArchiveTest.kt` |
| 18 日历调度 | `calendar/CalendarDatabase.kt`、`calendar/CalendarRepository.kt`、`calendar/CalendarRules.kt`、`calendar/CalendarProviderBridge.kt`、`calendar/CalendarReminderScheduler.kt`、`calendar/CalendarPage.kt`、`data/LocalBackupRepository.kt` | `test/.../CalendarRulesTest.kt`、`CalendarRulesAutomationTest.kt`、`androidTest/.../CalendarDatabaseMigrationTest.kt`、`ui/LocalBackupRoundTripTest.kt` |
| 19 多模态与社区生态 | `media/MediaIntelligenceProvider.kt`、`media/MediaProviderConfigStore.kt`、`media/MediaAiTaskStore.kt`、`media/MediaVideoGenerationClient.kt`、`data/ModelLedToolDispatcher.kt`、`NestroApplication.kt` | `test/.../MediaProviderConfigTest.kt`、`androidTest/.../MediaAiTaskStoreTest.kt`、`MediaProviderConfigStoreTest.kt` |

## 关键跨模块调用链

### 主模型统一调度

`NestroApp` → `ChatRequestExecutionCoordinator` → `ChatRequestContextAssembler` → `ModelLedToolDispatcher` → `AgentRuntime/AgentToolCatalog` → 当前项目工具 handler。

主模型可使用同一工具目录调度专家团、Phone Agent、项目素材、MCP、GitHub 工作台、媒体分析和视频生成；工具权限由 AgentRuntime/PermissionRouter 执行。

### 创作与执行混合调度

- Phone Agent：`PhoneAgentTaskService` → `PhoneAgentWorkScheduler` → `PhoneAgentWorker` → `PhoneAgentExecutor`。
- 媒体分析：`media.ai.run/status` → `MediaAiTaskStore` → `MediaAiTaskScheduler/Worker` → `MediaIntelligenceProvider`；执行状态另由 `AgentTaskCoordinator.runInline` 统一管理，领域任务结果保存在 MediaAiTaskStore。
- 视频生成：`media.video.generate/status` → MediaAiTaskStore/Worker → `MediaVideoGenerationClient` → 项目素材导入；任务创建后立即返回 task_id。
- 专家创作：`expert_team.run` → `ExpertTeamExecutionCoordinator` → `ExpertProjectPlanExecutor`，按 DAG 并行工作包执行。
- 主模型提示要求先启动可并行的后台任务，再运行独立专家工作；依赖素材的下游任务必须等待任务终态并核实素材已导入。

### 记忆与项目授权

`ChatRequestContextAssembler` → `NestroApplication.readableProjectsFor` → `ProjectAccessControlStore` → `UserMemoryRepository.recall` → DAO。

跨项目记忆召回必须同时满足：记忆所属者逐条开启 `shareAcrossProjects`，且目标项目持有来源项目 READ 授权。默认不共享；来源项目 ID 会随召回上下文返回。

### 聊天附件与多模态

聊天输入区多选图片/音频/视频 → `MediaProjectStore.importAsset` → 当前项目素材库 → 草稿附加真实 asset_id → 主模型查询素材 → `media.ai.run` → 图片理解/语音转写/视频场景分析/音频处理 Provider。

当前仍未实现 ChatMessage/OpenAI/Anthropic/Gemini 原生多模态 content parts；此桥接依赖项目素材导入。视频场景分析不等于通用视频问答，语音转写不等于非语音声音事件理解。

### 审批与工作流

`AgentRuntime` / 工作流执行器 → `WorkflowUiState` / `NestroApp` 审批 UI。当前工作流审批选择为“授权一次 / 当前任务全部授权 / 拒绝”；未选择时保持等待，关闭弹窗不会自动拒绝或授权。

### 日历

当前 `CalendarReminderScheduler` / Worker 按 occurrence + automationDelayMinutes 安排触发，入队 `AgentTaskCoordinator`；延迟配置经 UI、Room、Repository、Worker 和备份/恢复贯通。定义修改会递增 automationGeneration，任务 ID/幂等键与 WorkManager 唯一任务名按 generation 隔离；旧 generation 的 Worker 会被拒绝，generation=0 保留旧任务标识兼容。待执行的延迟 occurrence 即使事件开始后进程重启仍会被重新排程。`NestroApplication` 注册 `calendar_automation` handler，经 `ChatRequestExecutionCoordinator` 进入主模型工具链，结果/错误/状态回写 `CalendarEvent`。仍缺任意时间条件模型、日历专属语义型业务错误映射；通用 AgentTaskState.attempts/maxRetries、AgentTaskRetryPolicy 退避与外部副作用重试保护已存在。HTTP 408/425/429/5xx 已映射为可重试异常。calendar handler 当前不直接启用 expertTeam 或 workflow.run。新增测试源码尚未运行。日历是辅助自动调度功能，不作为独立页面；当前 CalendarPage 仅承载既有配置入口，后续 UI 阶段应把日程/延迟配置并入任务或工作流编辑入口，不另建独立日历产品页。

## 当前结构性缺口（按最新源码回读，仍在总审计中）

- 统一任务机的通用 retry 默认值为 2；WorkflowExecutionCoordinator 与 ExpertTeamExecutionCoordinator 的 runInline 调用沿用默认值。Workflow 工具感知步骤可能包含外部副作用，需补 task/step retry-safety 契约，避免不确定结果后整段重放。MCP、媒体 AI、媒体渲染、日历和生成式插件路径已显式将统一任务重试设为 0。
- 模块16已有生成式插件 runInline、步骤 checkpoint、恢复 UI、统一审批与项目能力授权/调用审计；仍缺统一跨模块任务总览收敛、任意代码插件沙箱和完整 Android VoiceInteractionService。
- 模块7自动记忆候选写入已新增 `NestroDao.saveUserMemoryCandidateIfSourceValid`：项目存在性、来源会话项目归属、来源 user message 校验与对应 upsert/replace 位于同一个 Room `@Transaction`；`UserMemoryRepositoryInstrumentedTest` 已增加 DAO 边界回归测试，但尚未运行。来源 ID 仍是普通字段、没有外键级联约束，删除源数据后的记忆清理策略仍需单独收口；独立全局/项目/会话记忆上下文缓存仍未实现。手动 global_memory_v1 与自动结构化记忆是不同能力。
- 模块8已有 `PhoneAgentPlanner` 的 NAVIGATION 分类、`open_navigation` Google Maps Directions 动作、路线读取核验、`confirm_sensitive` 用户确认和主屏导航交接代码；路线链路仍待真机验证。`PhoneAgentFloatingPreview` 现已增加独立拖动条、屏幕边界钳制和位置状态，窗口移动代码及边界单元测试源码已提交，尚未运行测试。窗口内屏幕滑动、点击与角部缩放继续保留。
- 模块17仍限单视频轨/最多单音频轨；多轨/PIP、转场、速度曲线、关键帧、字幕烧录和高级混音缺失。
- 模块18已接统一任务机与主模型工具链；延迟触发已贯通 UI/持久化/WorkManager/Worker/备份，并具备定义版本隔离与重启补排程。通用任务机已有 attempts/maxRetries、瞬时网络/HTTP 错误退避和副作用后禁止重放；仍缺任意时间条件触发及日历专属语义型业务错误映射，目前不直接调度 expert DAG/workflow。
- 模块3仍缺按真实模型 context-window 动态预算；原生工具并发只覆盖显式声明的安全只读工具。
- ChatMessage 原生多模态 parts、通用视频问答、非语音音频事件理解仍未实现；现有媒体素材导入桥接与 media.ai.run 能力不可夸大。
- 模块1专家项目与模块3工作流的工具感知调用入口现已接通：`NestroApp` 分别提供 `invokeMemberWithTools` / `invokeModelWithTools`，将操作级 `AgentToolAccess` 原始租约和精确工具 allow-list 交给 `ChatRequestExecutionCoordinator`；`ModelLedToolDispatcher` 在原生工具调用及兼容文本协议两条路径都复用同一租约处理器，并继续执行项目能力授权、AgentRuntime 权限判断、审批和 MCP 重放检查点。专家项目 `runInline` 与工作流 `runInline` 现在写入 projectId/conversationId 等 `AgentTaskMetadata`。新增 allow-list 单元测试源码，尚未运行编译或测试。模块5资源 scope 覆盖边界、模块1团队协作 UI/生命周期、模块4媒体 ZIP 与通用备份边界仍需完成静态审计。
- 模块17多视频轨/PIP/多音轨、模块19原生聊天多模态 content parts/通用视频问答/非语音音频事件理解仍未完成；现有素材导入与专业媒体任务不能代替原生多模态传输层。
- 全部新代码与新增测试仍待统一 Kotlin 编译、JVM/Android 测试、模拟器/真机及真实 API 联调；本代码地图不代表验收通过。
