# NESTRO 当前代码地图

> 快照基线：`integration/19-modules`，Git tree `df32fd86f269f10ce2699def3e0c9a0eb976eda2`（2026-11-22）。
> 当前树：354 个文件，其中 317 个 Kotlin 文件。本文按实际源码路径记录模块落点和跨模块调用链；它是代码导航图，不代表构建或验收通过。
> 功能是否完整以 `FUNCTION_AUDIT.md` 为准；十九模块需求范围以 `NESTRO_19_MODULES.md` 为准；未完成事项以 `IMPLEMENTATION_ROADMAP.md` 为准。

## 十九模块源码落点

| 模块 | 当前主要实现 | 主要测试/验证源码 |
|---|---|---|
| 1 专家系统 | `data/ExpertTeamEngine.kt`、`data/ExpertTeamExecutionCoordinator.kt`、`data/ExpertProjectPlanExecutor.kt`、`data/ExpertTemplateCatalog.kt`、`data/ModelLedToolDispatcher.kt` | `test/.../ExpertTeamEngineTest.kt`、`ExpertTeamExecutionCoordinatorTest.kt`、`ExpertProjectPlanExecutorTest.kt` |
| 2 模型管理 | `model/ModelRegistry.kt`、`model/ModelRegistryStore.kt`、`model/ModelConfigurationRepository.kt`、`model/ModelConnectionService.kt`、`model/ModelDiscoveryService.kt`、`ui/ModelManagerPage.kt`、`ui/ModelManagementUiState.kt` | `test/.../ModelProfileCatalogTest.kt`、`ModelToolDecisionParserTest.kt` |
| 3 聊天系统 | `data/ChatMessage.kt`、`data/ChatRequestContextAssembler.kt`、`data/ChatRequestExecutionCoordinator.kt`、`data/ChatRequestRecoveryCoordinator.kt`、`data/ModelLedToolDispatcher.kt`、`data/OpenAiClient.kt`、`model/ChatExecutionService.kt`、`ui/NestroApp.kt` | `test/.../ChatRequestContextAssemblerTest.kt`、`ChatRequestExecutionCoordinatorTest.kt`、`ChatRequestRecoveryCoordinatorTest.kt`、`model/ChatStreamingCoordinatorTest.kt` |
| 4 数据与备份 | `data/NestroDatabase.kt`、`data/ConversationPersistenceService.kt`、`data/LocalBackupRepository.kt`、`data/LocalBackupCompatibility.kt`、`data/ProjectPackageCodec.kt` | `androidTest/.../NestroDatabaseMigrationTest.kt`、`ui/LocalBackupRoundTripTest.kt`、`test/.../ProjectPackageCodecTest.kt` |
| 5 项目空间 | `data/ProjectSpaceRepository.kt`、`data/ProjectAccessControlStore.kt`、`data/ProjectResourceScopeStore.kt`、`data/ProjectSpaceRules.kt` | `androidTest/.../ProjectSpaceLifecycleTest.kt`、`ProjectAccessControlStoreTest.kt`、`test/.../ProjectResourceScopeStoreTest.kt` |
| 6 知识库 | `data/KnowledgeRepository.kt`、`data/KnowledgeSearchEngine.kt`、`data/KnowledgeVectorSearch.kt`、`data/KnowledgeEmbeddingModelManager.kt`、`data/KnowledgePdfOcr.kt`、`data/KnowledgeChunker.kt` | `test/.../KnowledgeSearchEngineTest.kt`、`KnowledgeEmbeddingPoolingTest.kt`、`androidTest/.../KnowledgeVectorIndexDatabaseTest.kt`、`KnowledgePdfImportTest.kt` |
| 7 我的记忆 | `data/UserMemoryStore.kt`、`data/UserMemoryRepository.kt`、`data/AutomaticMemoryExtractionCoordinator.kt`、`data/ConversationMemoryStore.kt`、`data/ProjectMemorySettingsStore.kt`、`ui/MemoryUiState.kt` | `test/.../UserMemoryStoreTest.kt`、`AutomaticMemoryExtractionCoordinatorTest.kt`、`androidTest/.../UserMemoryRepositoryInstrumentedTest.kt` |
| 8 Phone Agent | `data/PhoneAgentTaskStore.kt`、`phone/PhoneAgentTaskService.kt`、`phone/PhoneAgentWorkScheduler.kt`、`phone/PhoneAgentWorker.kt`、`phone/PhoneAgentExecutor.kt`、`phone/PhoneAgentService.kt`、`phone/virtualdisplay/mika/MikaVirtualDisplayController.kt`、`phone/virtualdisplay/mika/MikaVirtualDisplayService.kt` | `test/.../PhoneAgentTaskStoreTest.kt`、各 PhoneAgent policy tests |
| 9 语音系统 | `speech/AndroidSpeechRecognitionController.kt`、`speech/AndroidTextToSpeechController.kt`、`speech/OpenAiCompatibleSpeechProviders.kt`、`speech/OpenAiRealtimeVoiceProvider.kt`、`ui/VoiceInputButton.kt`、`ui/VoiceSystemPage.kt` | `test/.../SpeechProvidersTest.kt`、`SpeechHttpPolicyTest.kt`、`androidTest/.../VoiceInputButtonTest.kt` |
| 10 Artifacts | `data/ArtifactStore.kt`、`ui/ArtifactLibraryPage.kt` | `test/.../ArtifactStoreBackupTest.kt`、`ArtifactStoreReferenceTest.kt`、`androidTest/.../ArtifactStoreTest.kt` |
| 11 专家模板 | `data/ExpertTemplateCatalog.kt`、`data/ExpertProfileCodec.kt`、`data/ExpertRevisionAndTemplate.kt` | `test/.../ExpertTemplateCatalogTest.kt`、`ExpertProfileCodecTest.kt` |
| 12 模型对比 | `model/ModelComparisonCoordinator.kt`、`ui/ModelComparisonPage.kt` | `test/.../ModelComparisonPolicyTest.kt` |
| 13 MCP 工具中心 | `mcp/McpStreamableHttpClient.kt`、`mcp/McpToolCenter.kt`、`mcp/McpToolCenterPage.kt` | `test/.../McpToolCenterTest.kt` |
| 14 GitHub 工作台 | `git/GitWorkbench.kt`、`git/GitWorkbenchPage.kt` | `test/.../GitWorkbenchPolicyTest.kt`、`androidTest/.../GitAccountStoreTest.kt` |
| 15 AI 共建工作台 | `coworkbench/AiCoworkbenchPage.kt`、`coworkbench/AiCoworkbenchService.kt`、`coworkbench/CoworkbenchDataFlowAnalyzer.kt`、`coworkbench/GitHubActionsCoworkbenchRunner.kt` | `test/.../CoworkbenchPolicyTest.kt`、`CoworkbenchStaticAnalyzerTest.kt`、`CoworkbenchReportParserTest.kt` |
| 16 统一能力中心 | `agent/AgentRuntime.kt`、`agent/AgentTaskCoordinator.kt`、`agent/RoomAgentTaskStateStore.kt`、`agent/RoomAgentTaskTraceStore.kt`、`agent/AgentTaskTrace.kt` | `test/.../AgentRuntimeTest.kt`、`AgentTaskCoordinatorTest.kt` |
| 17 智能剪辑 | `media/MediaEditorPage.kt`、`media/MediaProjectStore.kt`、`media/MediaRenderEngine.kt`、`media/MediaRenderTaskStore.kt`、`media/MediaRenderWorker.kt`、`media/MediaVideoGenerationClient.kt` | `test/.../MediaProjectCodecTest.kt`、`androidTest/.../MediaProjectArchiveTest.kt` |
| 18 日历调度 | `calendar/CalendarDatabase.kt`、`calendar/CalendarRepository.kt`、`calendar/CalendarRules.kt`、`calendar/CalendarProviderBridge.kt`、`calendar/CalendarReminderScheduler.kt`、`calendar/CalendarPage.kt` | `test/.../CalendarRulesTest.kt` |
| 19 多模态与社区生态 | `media/MediaIntelligenceProvider.kt`、`media/MediaProviderConfigStore.kt`、`media/MediaAiTaskStore.kt`、`media/MediaVideoGenerationClient.kt`、`data/ModelLedToolDispatcher.kt`、`NestroApplication.kt` | `test/.../MediaProviderConfigTest.kt`、`androidTest/.../MediaAiTaskStoreTest.kt`、`MediaProviderConfigStoreTest.kt` |

## 关键跨模块调用链

### 主模型统一调度

`NestroApp` → `ChatRequestExecutionCoordinator` → `ChatRequestContextAssembler` → `ModelLedToolDispatcher` → `AgentRuntime/AgentToolCatalog` → 当前项目工具 handler。

主模型可使用同一工具目录调度专家团、Phone Agent、项目素材、MCP、GitHub 工作台、媒体分析和视频生成；工具权限由 AgentRuntime/PermissionRouter 执行。

### 创作与执行混合调度

- Phone Agent：`PhoneAgentTaskService` → `PhoneAgentWorkScheduler` → `PhoneAgentWorker` → `PhoneAgentExecutor`。
- 媒体分析：`media.ai.run` → `MediaAiTaskStore` → `MediaAiTaskScheduler/Worker` → `MediaIntelligenceProvider`。
- 视频生成：`media.video.generate` → 同一 Media AI 持久任务队列 → `MediaVideoGenerationClient` → 项目素材导入。
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

当前 `CalendarReminderScheduler` / Worker 负责按时间与重复规则触发提醒、通知和后续重复排程；当前源码尚未把到期日历事件连接到 AgentTask/Expert DAG/Phone Agent/Workflow 自动执行，也没有执行结果回写。日历是任务调度辅助能力，最终 UI 不应做成独立主页面。

## 当前结构性缺口

- 统一能力中心尚未提供完整的能力总览、项目授权和任务审计 UI；旧 PR #1 的 CapabilityTaskEngine 不应整套恢复，需把必要的幂等、重试、元数据能力补入当前 AgentTaskCoordinator。
- 插件工作流目前整体确认后，内部 EXTERNAL_WRITE 步骤直接 Allow；审批 UI 是否展示完整步骤和参数仍需核验；插件执行缺少共享 AgentTaskCoordinator 的步骤 checkpoint/resume。
- 日历到期触发仍以通知为主，尚未调度实际 Agent/Workflow 任务。
- ChatMessage 原生多模态 parts、通用视频问答、非语音音频理解仍缺。
- 记忆全局/项目/会话上下文缓存仍未实现；embeddingCache 只是向量计算缓存。
- 当前所有新代码与新增测试仍待统一 Kotlin 编译、JVM/Android 测试、模拟器/真机及真实 API 联调；本代码地图不代表验收通过。
