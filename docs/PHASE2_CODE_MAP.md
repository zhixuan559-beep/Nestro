# 第二阶段代码地图与依赖盘点（初版）

> 基线：`refactor/phase-2-architecture`，起点提交 `8bd25b210488cc1935c4afffbccba9a994018dbc`。该提交来自 Nestro 自身的 `feature/voice-system` 分支；PR #2 仍为 Draft、未合并。
>
> ETA 处理边界：现有 Nestro 架构文档记录 ETA 为 GPL-3.0，并明确要求未经许可证兼容审查不得直接复用其源代码。本分支没有引入 ETA 源码子树或独立 Gradle 模块；本阶段只把其 Agent Runtime、工具目录、权限和任务生命周期设计作为架构参考，按 Nestro 现有代码独立整合。不能把本分支称为“已合并 ETA 源码”。

## 1. 仓库现状

- Gradle 工程当前只有一个 Android app module（`settings.gradle.kts` 仅 include `:app`）；十九项是产品模块，不是十九个独立 Gradle module。
- 当前树共 132 个文件，其中主源码 74 个 Kotlin 文件。
- 主源码分布：`data` 35、`ui` 13、`phone` 11、`model` 6、`agent` 5、`speech` 2，根目录 2。
- 自动化测试共 44 个文件：JVM 单元测试与 Android instrumentation/UI 测试均已有；CI 当前只证明本轮 workflow 配置执行的编译、JVM tests、Lint 与 Android test source 编译通过，不等于 instrumentation tests 已在设备运行。
- 主要入口：`MainActivity` → `NestroApp()`；进程级服务组合根为 `NestroApplication`。

## 2. 已核验的核心结构

| 区域 | 现有实现 | 当前边界/问题 |
|---|---|---|
| UI 根 | `ui/NestroApp.kt`，2,997 行、127 处 `remember`、6 处 `LaunchedEffect`、24 处协程启动 | 同时持有大量跨功能状态、导航和业务编排；是第一重构目标，不做机械拆文件 |
| 进程组合根 | `NestroApplication.kt`，106 行 | 已持有 Room、模型配置/连接、聊天执行、记忆/知识/项目/备份仓库及 AgentTaskCoordinator；优先复用 |
| Agent 运行时 | `agent/AgentRuntime.kt`、`AgentTaskCoordinator.kt`、Room state/trace store | 已有工具目录、权限决策、任务状态/队列/追踪基础；要核对各业务入口是否统一走此边界 |
| 模型 | `ModelRegistry`、`ModelRegistryStore`、`ModelConfigurationRepository`、`ModelConnectionService`、`ModelRequestConfigurationSelector`、`LocalVault` | 新 Registry 与旧 Profile UI 并存；需要收敛唯一数据源，Key 删除后所有依赖模型必须不可选 |
| 聊天/网络 | `ChatExecutionService`、`OpenAiClient`、`ChatEndpointPolicy`、`ChatRequestPolicy`、`OpenAiSseDecoder`、Conversation repositories | 执行服务与策略已抽出；UI 根仍有请求状态和业务流程 |
| 数据 | `NestroDatabase.kt` 599 行；`LocalBackupRepository.kt` 568 行；多个 Store/Repository | 数据实体、DAO、迁移及备份职责需要按所有权梳理，不得破坏迁移和备份兼容 |
| Phone Agent | `phone/` 11 个文件 + `agent/` 协调器 + WorkManager/Accessibility | 任务记录、队列、状态/trace、设备动作必须保持同一生命周期；禁止不确定动作自动重放 |
| 语音 | `speech/` 2 个文件、`VoiceInputButton` | Android 语音输入已有；输出和实时语音目前是 Provider 契约，不是完整功能 |
| 产物/社区 | `ArtifactStore`、`ArtifactLibraryPage`、`SkillCommunityClient`、签名/解析规则 | 已有产物库与技能社区校验基础；正式社区地址/密钥分发仍有外部依赖 |

## 3. 十九个产品模块代码落点初盘

| 编号 | 模块 | 当前代码证据 | 初步判断 |
|---:|---|---|---|
| 1 | 专家系统 | ExpertProfileCodec、ExpertTeamEngine、ExpertBundleParser、NestroApp 专家/团队界面 | 主体已有；UI 状态仍集中在根页面 |
| 2 | 模型管理 | model/ 全部核心类、LocalVault、ModelManagerPage | 主体已有；新旧配置模型需统一 |
| 3 | 聊天系统 | ChatExecutionService、OpenAiClient、SSE/请求策略、会话持久化 | 主体已有；UI 与业务编排耦合 |
| 4 | 数据与备份 | NestroDatabase、LocalBackupRepository、LocalBackupCompatibility、LocalVault | 主体已有；需维持迁移/恢复安全边界 |
| 5 | 项目空间 | ProjectSpaceRepository、ProjectSpaceRules、ProjectPreferencesStore | 主体已有；需统一项目级资源归属 |
| 6 | 知识库 | KnowledgeRepository、KnowledgeStore、Chunker、SearchEngine、KnowledgePage | 主体已有；独立页面和仓库边界较清楚 |
| 7 | 我的记忆 | UserMemoryRepository/Store、ConversationMemoryStore | 初版已有；记忆提取/召回/授权边界需继续核对 |
| 8 | Phone Agent | PhoneAgentAgent/Planner/Executor/Worker/WorkScheduler/TaskService + AgentTaskCoordinator | 主体已有；需重点核对任务状态与动作幂等 |
| 9 | 语音系统 | AndroidSpeechRecognitionController、SpeechProviders、VoiceInputButton | 输入已有；输出/实时会话未形成闭环 |
| 10 | 我的作品 | ArtifactStore、ArtifactLibraryPage、聊天保存入口 | 主体已有；需核对项目隔离、版本与备份 |
| 11 | 专家模板 | ExpertTemplateCatalog、ExpertRevisionAndTemplate、NestroApp 模板流程 | 主体已有；对应测试已有，完整 UI 回归仍需核验 |
| 12 | 双模型对比 | 当前命中 compare 的主要代码是专家版本差异比较 | 未找到并行双模型请求与并排结果的明确实现 |
| 13 | MCP 工具中心 | Agent 工具目录/权限契约可复用；未找到 MCP transport/server discovery 实现 | 尚未形成 MCP 模块 |
| 14 | GitHub 工作台 | 仓库未见 GitHub 工作台专属 package、页面或 Git 操作服务 | 尚未形成模块 |
| 15 | AI 共建工作台 | WorkflowEngine、专家、产物、Agent 底座可复用；未见独立共建工作台 | 只有可复用基础，不算模块完成 |
| 16 | 统一能力中心 | CapabilityDirectoryPage、能力目录/技能目录相关能力 | 已按既定决策锁定；只做适配，不重构其核心 |
| 17 | 智能剪辑 | 当前主源码树未发现媒体编辑服务、页面或任务实现 | 未发现实现，后续再核验 |
| 18 | 日历 | 当前主源码树未发现日历数据/页面/调度实现 | 未发现实现，后续再核验 |
| 19 | 多模态服务与社区生态 | SpeechProviders、SkillCommunityClient、ArtifactStore | 局部基础已有；不能等同完整多模态服务生态 |

> “未发现”是对当前源码树的初步检索结论，不代表产品设计中不存在需求；后续仍需对资源、配置和隐藏入口做补充核验。

## 4. 主要耦合与重复风险

1. **UI 根过载**：NestroApp 同时承担页面路由、跨模块状态、模型/聊天/专家/工作流/记忆/项目/Phone Agent 的部分业务流程。应按业务用例提取状态持有者和应用服务，避免把业务搬到另一个超大文件。
2. **模型双数据源风险**：Registry 已管理 provider/key/model，而 UI 仍保留 legacy profile 流程。应建立单一事实源与兼容迁移，不允许两个列表各自决定模型是否可选。
3. **Agent 生命周期一致性**：AgentTaskCoordinator、PhoneAgentTaskService、Room 状态/trace、WorkManager 与 Accessibility 执行必须有清晰状态转换；不能再出现平行队列或重复动作。
4. **数据访问边界**：页面不应直接拼装数据库查询、备份事务、密钥加解密或网络传输；通过既有 Repository/Service 访问。
5. **模块并非 Gradle 多模块**：先把 package/接口/状态所有权理顺，再依据真实隔离收益决定是否拆 Gradle modules；不为“模块化”而增加构建复杂度。

## 5. 第二阶段重构批次建议

- **批次 A：应用状态与页面协调层** — 将 NestroApp 的跨功能状态和副作用按业务边界提取；根页面只保留导航、组合和页面协调。先覆盖聊天/模型/项目/记忆的共享状态，不改变用户可见行为。
- **批次 B：模型与聊天统一调用链** — 收敛 Registry/Profile、API Key 生命周期、模型选择、故障切换与 ChatExecutionService；确保 Phone Agent 复用同一模型调用链。
- **批次 C：Agent/Phone Agent 任务生命周期** — 统一任务状态、暂停/恢复/取消、trace、WorkManager 和幂等策略；保留已有人工核验边界。
- **批次 D：数据/备份/项目/知识/记忆边界** — 统一项目资源归属、Repository 责任与事务；保持 Room migration、旧数据兼容和备份格式。
- **批次 E：专家/团队/工作流/技能/产物/语音集成边界** — 消除重复状态与传输实现，复用共享能力；模块 16 仅适配。
- 每批完成后执行 Kotlin compile、JVM unit tests、Lint 和 Android test source compile；不生成 APK，不提前做真机验收。

## 6. 本轮阶段门槛

- 不删除现有功能来换取更干净的架构。
- 不复制 ETA GPL-3.0 源代码，除非后续完成许可证兼容性审查并明确批准。
- 不合并 PR #2，不把 PR 转 Ready。
- 不修改十九模块名称、编号、顺序。
- 不重做模块 16。
- 不以文件数、代码行数下降作为重构完成标准；以单一状态所有者、稳定接口、数据兼容和测试通过为准。

## 2026-11-18 批次 A 进展补充

- 新增 `ui/NestroAppEffects.kt`，承接三个根页面副作用：
  - `MemoryPageLoadEffect`：仅在记忆页激活时加载当前项目自动记忆，并重置编辑态。
  - `ConversationPageLoadEffect`：统一会话历史分页的 loading、列表清空、滚动复位、250ms 防抖与首屏加载。
  - `ConversationSnapshotPersistenceEffect`：数据库就绪后，随会话/项目状态变化持久化一致快照。
- `NestroApp.kt` 已改为调用上述 effect composables；对应三个内联 `LaunchedEffect` 已移除，数据读取/持久化仍复用既有 Repository/Service，没有引入第二套数据通道。
- 该改动属于页面副作用边界抽离，不代表 Batch A 完成；根页面仍保留启动恢复、模型初始化、发送编排、专家/工作流操作等较大业务流程，后续继续按业务域抽离。
- 本批次 CI：等待当前 PR head 对应 Android CI 验证；不得把旧 SHA 的成功结果套用到新提交。
- PR #3 保持 Draft / 未合并；未构建安装 APK，未进行真机验收。

## 2026-11-18 批次 A 进展补充（二）

- `ModelManagementUiState.initialize()` 现在负责模型启动恢复：读取活动配置、迁移旧版单 Profile 配置、重建 Registry、校验当前模型与加密 Key、恢复统一 active model ID，并将恢复后的状态写回 state holder。
- `NestroApp` 的启动 `LaunchedEffect(Unit)` 不再直接处理模型迁移和 Registry 活动模型校验，改为调用 `modelUiState.initialize()`；会话恢复、项目/会话选择、聊天中断恢复和 Agent 任务恢复仍留在启动流程中。
- 该拆分把模型域初始化归还模型状态所有者，但启动编排尚未整体拆完；后续需继续处理会话恢复与聊天请求恢复的边界。
- 当前分支最新提交 `2754d0f3b6d5a698e4bf3f0c3b5adf3c43c4c002` 的 CI 待核验。

- 会话历史加载 effect 增加取消安全与异常收口：新查询/切页取消旧加载时不会由旧协程覆盖新 loading 状态；真实加载异常会回传根页面状态提示，并确保当前协程结束时关闭 loading。
- 上述错误处理属于本批次副作用边界的一部分，避免页面因 DAO/存储异常永久停留在加载态。


## 2026-11-18 批次 A 进展补充（三）

- `ModelManagementUiState.initialize()` 在 Registry 活跃模型有效时同步清除旧 `active_model_profile_id`，避免旧 Profile ID 残留并在后续模型失效时触发错误回退。
- 新增 `data/ChatRequestRecoveryCoordinator.kt`，集中处理进程重建后的聊天请求恢复：读取中断标记、恢复部分回复、避免重复插入同一 requestId、恢复当前会话草稿并清理已处理标记。`NestroApp` 只负责把恢复结果应用到 Compose 状态。
- `NestroApplication` 注册聊天恢复协调器；备份恢复路径改为复用 `ModelManagementUiState.initialize()`，不再维护第二份 Registry/旧 Profile 恢复逻辑。
- 移除 `NestroApp` 中重复调用 `AgentTaskCoordinator.recoverAfterProcessRestart()` 的 UI 启动分支。进程级任务恢复由 `NestroApplication.onCreate()` 统一拥有，避免应用层和页面层并发执行恢复扫描。
- `NestroApp` 仍负责 Room/旧存储会话与项目的首轮恢复、活动项目/会话选择和页面状态应用；Batch A 未完成，下一步继续拆分会话启动恢复与发送编排。
- 前一提交 `d0968acd667edc0aca95a7f69f46a44018194e47` 的 Android CI 已通过。上述本轮新改动尚待对应最新 head 的 CI 验证；未构建 APK、未进行真机验收，PR #3 仍保持 Draft / 未合并。


## 2026-11-18 批次 A 编译反馈与修复

- 最新 head `a8bc5d5b4115818acc293caca322ce5788925977` 的 CI 在 Kotlin 编译阶段失败，唯一报告错误为 `NestroApplication` 的 public 属性暴露 internal 类型 `ChatRequestRecoveryCoordinator`。这是可见性边界问题，不是恢复逻辑或测试失败。
- 已将 `NestroApplication.chatRequestRecoveryCoordinator` 收紧为 `internal`，保持协调器和其内部 Result 类型不泄漏到应用公开 API。
- 修复提交 `4e631ca4045f819a00e1e25a1f677828d0347310`；等待该修复及本次代码地图更新后的最新 head CI。PR #3 保持 Draft / 未合并。
