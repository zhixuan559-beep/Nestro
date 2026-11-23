# NESTRO 当前代码地图

> 当前唯一开发与交付基线：`main`，本轮业务源码与测试源码回读基线 `6e94cfb5a5b13172876b0777108ccbe733081cee`（2026-11-24）。
> 本轮已重新统计 `main` recursive tree：459 entries、392 files、48 directories，其中 211 个 main Kotlin 源文件、135 个 JVM/AndroidTest Kotlin 文件、16 个 docs 文件。功能状态按当前 main 源码、调用点和数据流核验；历史分支提交数不等于有效功能缺口。
> 防断档中控：GitHub Issue #21（构建前总审计与十九模块收口）。
>
> 本文按实际源码路径记录模块落点和跨模块调用链；它是代码导航图，不代表构建或验收通过。
> 功能是否完整以 `FUNCTION_AUDIT.md` 为准；十九模块需求范围以 `NESTRO_19_MODULES.md` 为准；未完成事项以 `IMPLEMENTATION_ROADMAP.md` 为准。

## 十九模块源码落点

| 模块 | 当前主要实现 | 主要测试/验证源码 |
|---|---|---|
| 1 专家系统 | `data/ExpertTeamEngine.kt`、`data/ExpertTeamExecutionCoordinator.kt`、`data/ExpertProjectPlanExecutor.kt`、`data/ExpertTemplateCatalog.kt`、`data/ModelLedToolDispatcher.kt`、`ui/ExpertWorkspaceUiState.kt`（协作历史分页/暂停控制） | `test/.../ExpertTeamEngineTest.kt`、`ExpertTeamExecutionCoordinatorTest.kt`、`ExpertProjectPlanExecutorTest.kt`、`ui/ExpertTeamEventPaginationTest.kt`、`ui/ExpertTeamPauseControllerTest.kt` |
| 2 模型管理 | `model/ModelRegistry.kt`、`model/ModelRegistryStore.kt`、`model/ModelConfigurationRepository.kt`、`model/ModelConnectionService.kt`、`model/ModelDiscoveryService.kt`、`ui/ModelManagerPage.kt`、`ui/ModelManagementUiState.kt` | `test/.../ModelProfileCatalogTest.kt`、`ModelToolDecisionParserTest.kt` |
| 3 聊天系统 | `data/ChatMessage.kt`、`data/ChatMessageContentSerializer.kt`、`data/ChatRequestContextAssembler.kt`、`data/ChatRequestExecutionCoordinator.kt`、`data/ChatRequestRecoveryCoordinator.kt`、`data/ModelLedToolDispatcher.kt`、`data/OpenAiClient.kt`、`media/ChatMediaAttachmentResolver.kt`、`model/ChatExecutionService.kt`、`ui/NestroApp.kt` | `test/.../ChatRequestContextAssemblerTest.kt`、`ChatRequestExecutionCoordinatorTest.kt`、`ChatRequestRecoveryCoordinatorTest.kt`、`ChatMessageContentSerializerTest.kt`、`ChatMediaAttachmentResolverTest.kt`、`model/ChatStreamingCoordinatorTest.kt` |
| 4 数据与备份 | `data/NestroDatabase.kt`、`data/ConversationPersistenceService.kt`、`data/LocalBackupRepository.kt`、`data/LocalBackupCompatibility.kt`、`data/ProjectPackageCodec.kt` | `androidTest/.../NestroDatabaseMigrationTest.kt`、`ui/LocalBackupRoundTripTest.kt`、`test/.../ProjectPackageCodecTest.kt` |
| 5 项目空间 | `data/ProjectSpaceRepository.kt`、`data/ProjectAccessControlStore.kt`、`data/ProjectResourceScopeStore.kt`、`data/ProjectSpaceRules.kt` | `androidTest/.../ProjectSpaceLifecycleTest.kt`、`ProjectAccessControlStoreTest.kt`、`test/.../ProjectResourceScopeStoreTest.kt` |
| 6 知识库 | `data/KnowledgeRepository.kt`、`data/KnowledgeSearchEngine.kt`、`data/KnowledgeVectorSearch.kt`、`data/KnowledgeEmbeddingModelManager.kt`、`data/KnowledgePdfOcr.kt`、`data/KnowledgeChunker.kt` | `test/.../KnowledgeSearchEngineTest.kt`、`KnowledgeEmbeddingPoolingTest.kt`、`androidTest/.../KnowledgeVectorIndexDatabaseTest.kt`、`KnowledgePdfImportTest.kt` |
| 7 我的记忆/技能库 | `data/UserMemoryStore.kt`、`data/UserMemoryRepository.kt`、`data/AutomaticMemoryExtractionCoordinator.kt`、`data/ConversationMemoryStore.kt`、`data/ProjectMemorySettingsStore.kt`、`data/SkillBundleParser.kt`、`data/SkillDependencyResolver.kt`、`data/SkillCommunityLifecycleStore.kt`、`ui/MemoryUiState.kt` | `test/.../UserMemoryStoreTest.kt`、`AutomaticMemoryExtractionCoordinatorTest.kt`、`SkillBundleParserTest.kt`、`androidTest/.../UserMemoryRepositoryInstrumentedTest.kt` |
| 8 Phone Agent | `data/PhoneAgentTaskStore.kt`、`phone/PhoneAgentTaskService.kt`、`phone/PhoneAgentWorkScheduler.kt`、`phone/PhoneAgentWorker.kt`、`phone/PhoneAgentExecutor.kt`、`phone/PhoneAgentService.kt`、`phone/virtualdisplay/mika/MikaVirtualDisplayController.kt`、`phone/virtualdisplay/mika/MikaVirtualDisplayService.kt`、`ui/PhoneAgentPage.kt`（浮动预览交互） | `test/.../PhoneAgentTaskStoreTest.kt`、各 PhoneAgent policy tests |
| 9 语音系统 | `speech/AndroidSpeechRecognitionController.kt`、`speech/AndroidTextToSpeechController.kt`、`speech/OpenAiCompatibleSpeechProviders.kt`、`speech/OpenAiRealtimeVoiceProvider.kt`、`ui/VoiceInputButton.kt`、`ui/VoiceSystemPage.kt` | `test/.../SpeechProvidersTest.kt`、`SpeechHttpPolicyTest.kt`、`androidTest/.../VoiceInputButtonTest.kt` |
| 10 Artifacts | `data/ArtifactStore.kt`、`ui/ArtifactLibraryPage.kt` | `test/.../ArtifactStoreBackupTest.kt`、`ArtifactStoreReferenceTest.kt`、`androidTest/.../ArtifactStoreTest.kt` |
| 11 专家模板 | `data/ExpertTemplateCatalog.kt`、`data/ExpertProfileCodec.kt`、`data/ExpertRevisionAndTemplate.kt` | `test/.../ExpertTemplateCatalogTest.kt`、`ExpertProfileCodecTest.kt` |
| 12 模型对比 | `model/ModelComparisonCoordinator.kt`、`ui/ModelComparisonPage.kt` | `test/.../ModelComparisonPolicyTest.kt` |
| 13 MCP 工具中心 | `mcp/McpStreamableHttpClient.kt`、`mcp/McpToolCenter.kt`、`mcp/McpToolCenterPage.kt` | `test/.../McpToolCenterTest.kt` |
| 14 GitHub 工作台 | `git/GitWorkbench.kt`、`git/GitWorkbenchPage.kt` | `test/.../GitWorkbenchPolicyTest.kt`、`androidTest/.../GitAccountStoreTest.kt` |
| 15 AI 共建工作台 | `coworkbench/AiCoworkbenchPage.kt`、`coworkbench/AiCoworkbenchService.kt`、`coworkbench/CoworkbenchDataFlowAnalyzer.kt`、`coworkbench/GitHubActionsCoworkbenchRunner.kt` | `test/.../CoworkbenchPolicyTest.kt`、`CoworkbenchStaticAnalyzerTest.kt`、`CoworkbenchReportParserTest.kt` |
| 16 统一能力中心/任务总览 | `data/CapabilityRegistryStore.kt`、`agent/AgentRuntime.kt`、`agent/AgentTaskCoordinator.kt`、`agent/RoomAgentTaskStateStore.kt`、`agent/RoomAgentTaskTraceStore.kt`、`agent/AgentTaskTrace.kt`、`data/NestroDatabase.kt`、`ui/AgentTaskCenterPage.kt`、`ui/ToolsDirectoryPage.kt`、`ui/NestroApp.kt` | `test/.../AgentRuntimeTest.kt`、`AgentTaskCoordinatorTest.kt`、`androidTest/.../CapabilityRegistryStoreTest.kt` |
| 17 智能剪辑 | `media/MediaEditorPage.kt`、`media/MediaProjectStore.kt`、`media/MediaTimelinePolicy.kt`、`media/MediaProjectCodec.kt`、`media/MediaRenderEngine.kt`、`media/MediaRenderTaskStore.kt`、`media/MediaRenderWorker.kt`、`media/MediaVideoGenerationClient.kt` | `test/.../MediaTimelinePolicyTest.kt`、`MediaProjectCodecTest.kt`、`androidTest/.../MediaProjectArchiveTest.kt` |
| 18 日历调度 | `calendar/CalendarDatabase.kt`、`calendar/CalendarRepository.kt`、`calendar/CalendarRules.kt`、`calendar/CalendarProviderBridge.kt`、`calendar/CalendarReminderScheduler.kt`、`calendar/CalendarPage.kt`、`data/CalendarExpertTeamRunner.kt`、`data/CalendarWorkflowRunner.kt`、`data/ChatRequestExecutionCoordinator.kt`、`data/ModelLedToolDispatcher.kt`、`data/LocalBackupRepository.kt` | `test/.../CalendarRulesTest.kt`、`CalendarRulesAutomationTest.kt`、`androidTest/.../CalendarDatabaseMigrationTest.kt`、`ui/LocalBackupRoundTripTest.kt` |
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

聊天输入区多选图片/音频/视频 → `MediaProjectStore.importAsset` → 当前项目素材库 → 草稿附加真实 asset_id。`ChatMediaAttachmentResolver` 在请求期解析图片/音频/视频：图片必要时缩放/旋转/压缩；音视频仅读取小文件。`ChatMessageContentSerializer` 分别输出 OpenAI image_url（模型 ID 含 `audio` 时附加 WAV/MP3 input_audio）、Anthropic base64 image、Gemini inlineData image/audio/video；Gemini 媒体块先于文本指令。`ChatRequestPolicy` 保留 parts 并按估算 token 成本计入预算。

不新增 Room 附件字段、独立文件目录或第二套媒体仓库。大文件继续走 `media.ai.run/status` 专业 Provider；模型能力声明/兼容性门禁、真实 API 联调、长视频/大文件直传及跨 Provider 行为一致性仍未完成。

### 审批与工作流

`AgentRuntime` / 工作流执行器 → `WorkflowUiState` / `NestroApp` 审批 UI。当前工作流审批选择为“授权一次 / 当前任务全部授权 / 拒绝”；未选择时保持等待，关闭弹窗不会自动拒绝或授权。

### 日历

当前 `CalendarReminderScheduler` / Worker 按 occurrence + 有符号 automationDelayMinutes 安排触发，入队 `AgentTaskCoordinator`；负数表示事件前提前最多 7 天，0 表示开始时，正数表示事件后延迟；配置经 UI、Room、Repository、Worker 和备份/恢复贯通，不新增 schema。定义修改会递增 automationGeneration，任务 ID/幂等键与 WorkManager 唯一任务名按 generation 隔离；DAO dispatch/result 更新以 generation 条件写入，旧 Worker 不能覆盖新定义；Worker 回读最新持久化事件后再排程。错过提前触发点只立即执行当前 occurrence，lastTaskId/lastRunAt 防止后续已错过触发点批量回填。generation=0 保留旧任务标识兼容。待执行的延迟 occurrence 即使事件开始后进程重启仍会被重新排程。`NestroApplication` 注册 `calendar_automation` handler，经 `ChatRequestExecutionCoordinator` 进入主模型工具链，结果/错误/状态回写 `CalendarEvent`。仍缺 cron/条件表达式触发模型、日历专属语义型业务错误映射；通用 AgentTaskState.attempts/maxRetries、AgentTaskRetryPolicy 退避与外部副作用重试保护已存在。HTTP 408/425/429/5xx 已映射为可重试异常。截至 2026-11-24 main，calendar handler 已按项目可用专家启用 expert_team.run，并通过 workflow.list/workflow.run 调度项目可见工作流。新增测试源码尚未运行。日历是辅助自动调度功能，不作为独立页面；当前 CalendarPage 仅承载既有配置入口，后续 UI 阶段应把日程/延迟配置并入任务或工作流编辑入口，不另建独立日历产品页。

## 当前结构性缺口（按最新源码回读，仍在总审计中）

- 统一任务机的通用 retry 默认值为 2；`WorkflowExecutionCoordinator` 与 `ExpertTeamExecutionCoordinator` 的 `runInline` 已显式设置 `maxRetries=0`，避免整段工作流/专家团队在结果不确定时重放。日历父任务仍允许有限重试；`ModelLedToolDispatcher` 在外部副作用工具开始后将后续失败包装为 `NonRetryableAgentTaskException`。MCP、媒体 AI、媒体渲染和生成式插件路径也显式禁用整段自动重试。
- 模块16已有生成式插件 runInline、步骤 checkpoint、恢复 UI、统一审批与项目能力授权/调用审计；新增项目级统一任务中心，直接读取 AgentTaskCoordinator 状态与 trace，支持查看、暂停、恢复、取消。仍缺更完整的能力权限总览、任意代码插件沙箱和完整 Android VoiceInteractionService。
- 模块7自动记忆候选写入已新增 `NestroDao.saveUserMemoryCandidateIfSourceValid`：项目存在性、来源会话项目归属、来源 user message 校验与对应 upsert/replace 位于同一个 Room `@Transaction`；`UserMemoryRepositoryInstrumentedTest` 已增加 DAO 边界回归测试，但尚未运行。来源 ID 仍是普通字段、没有外键级联约束，删除源数据后的记忆清理策略仍需单独收口；独立全局/项目/会话记忆上下文缓存仍未实现。手动 global_memory_v1 与自动结构化记忆是不同能力。
- 模块8 `PhoneAgentPlanner` 已有 NAVIGATION 与 TRAVEL 分类；单点导航和多日旅行规划均会要求使用 `open_navigation` 打开 Google Maps Directions 预览、读取路线并经 `confirm_sensitive` 交用户确认后再主屏交接。当前顺序由规划步骤/模型指令驱动，硬状态门禁是否能阻断模型绕过仍待补强与验证；路线链路仍待真机验收。`PhoneAgentFloatingPreview` 现已增加独立拖动条、屏幕边界钳制和位置状态，窗口移动代码及边界单元测试源码已提交，尚未运行测试。窗口内屏幕滑动、点击与角部缩放继续保留。
- 模块17现支持 1 条主视频轨 + 最多 8 条音频混音轨，音频轨可独立静音和设置 0–200% 音量；仍只有单视频轨，PIP/多视频轨、转场、速度曲线、关键帧、字幕烧录和高级混音未完成。
- 模块18已接统一任务机与主模型工具链；延迟触发已贯通 UI/持久化/WorkManager/Worker/备份，并具备定义版本隔离与重启补排程。日历自动任务现可调用项目可见专家 DAG 与已保存 PROMPT/EXPERT/SKILL 工作流，工作流审批复用 AgentApprovalCoordinator；工作流步骤内部暂不开放外部工具调用。事件相对提前/准点/延后偏移已接通；generation 条件写入与错过触发点防回填风暴已接通；仍缺 cron/条件表达式触发及日历专属语义型业务错误映射。
- 模块3主聊天历史与工具决策轮已按候选模型最小 `contextWindowTokens` 动态裁剪，并按 `maxTokens`/默认值计算输出 reserve；系统提示词自身超窗压缩/截断保护仍待补。原生工具并发只覆盖显式声明的安全只读工具。
- 原生 image/audio/video parts 已按 Provider 接通：OpenAI 图片+（模型 ID 含 `audio` 时的）WAV/MP3 音频、Anthropic 图片、Gemini 图片/音频/视频；大文件仍走媒体任务。模型能力声明/兼容性门禁、真实 API 联调、长视频/大文件直传和跨 Provider 一致性仍未完成。
- 模块1专家项目与模块3工作流的工具感知调用入口现已接通：`NestroApp` 分别提供 `invokeMemberWithTools` / `invokeModelWithTools`，将操作级 `AgentToolAccess` 原始租约和精确工具 allow-list 交给 `ChatRequestExecutionCoordinator`；`ModelLedToolDispatcher` 在原生工具调用及兼容文本协议两条路径都复用同一租约处理器，并继续执行项目能力授权、AgentRuntime 权限判断、审批和 MCP 重放检查点。专家项目 `runInline` 与工作流 `runInline` 现在写入 projectId/conversationId 等 `AgentTaskMetadata`。新增 allow-list 单元测试源码，尚未运行编译或测试。模块5资源 scope 覆盖边界、模块1团队协作 UI/生命周期、模块4媒体 ZIP 与通用备份边界仍需完成静态审计。
- 模块17多视频轨/PIP/转场/速度曲线/关键帧/字幕烧录等高级视频编辑仍未完成；模块19原生多模态 parts 已按 Provider 接通，但模型能力门禁、真实 API 联调、大文件视频问答和跨 Provider 一致性仍待完成。
- 全部新代码与新增测试仍待统一 Kotlin 编译、JVM/Android 测试、模拟器/真机及真实 API 联调；本代码地图不代表验收通过。


## 2026-11-24 主线基线切换说明

- 当前唯一开发与交付基线已切换为 `main`，本次回读时 HEAD：`f33f67f8625ed8d2992b683358da055de5848775`。
- 本文此前以 `integration/19-modules` 为基线的记录均视为历史审计线索，不自动代表当前 `main` 状态；后续功能状态必须按 `main` 当前源码、调用点和数据流重新核实。
- 2026-11-24 后续增补：新增项目级统一任务中心；媒体时间线支持多音频轨与独立音量；日历自动任务接入专家 DAG 与已保存工作流调度。详细边界见本文件结构性缺口和 `FUNCTION_AUDIT.md` 的 2026-11-24 增补。
- 原生 image/audio/video parts 已按 Provider 接通；模型能力门禁、真实 API 联调、大文件直传与跨 Provider 一致性仍待完成。PIP/多视频轨与转场、cron/条件表达式触发及工作流步骤内部工具调用仍未完成。未运行编译/测试/构建。
