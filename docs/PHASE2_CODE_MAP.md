# 第二阶段代码地图与依赖盘点（初版）

> 基线：`refactor/phase-2-architecture`，起点提交 `8bd25b210488cc1935c4afffbccba9a994018dbc`。该提交来自 Nestro 自身的 `feature/voice-system` 分支；PR #2 仍为 Draft、未合并。
>
> ETA 处理边界：现有 Nestro 架构文档记录 ETA 为 GPL-3.0，并明确要求未经许可证兼容审查不得直接复用其源代码。本分支没有引入 ETA 源码子树或独立 Gradle 模块；本阶段只把其 Agent Runtime、工具目录、权限和任务生命周期设计作为架构参考，按 Nestro 现有代码独立整合。不能把本分支称为“已合并 ETA 源码”。

## 1. 仓库现状

- Gradle 工程当前只有一个 Android app module（`settings.gradle.kts` 仅 include `:app`）；十九项是产品模块，不是十九个独立 Gradle module。
- 当前树共 149 个文件，其中主源码 88 个 Kotlin 文件。
- 主源码分布：`data` 38、`ui` 24、`phone` 11、`model` 6、`agent` 5、`speech` 2，根目录 2。
- 自动化测试共 47 个 Kotlin 文件：JVM 单元测试 33、Android instrumentation/UI tests 14；CI 只证明编译、JVM tests、Lint 与 Android test source 编译通过，不等于 instrumentation tests 已在设备运行。
- 主要入口：`MainActivity` → `NestroApp()`；进程级服务组合根为 `NestroApplication`。

## 2. 已核验的核心结构

| 区域 | 现有实现 | 当前边界/问题 |
|---|---|---|
| UI 根 | `ui/NestroApp.kt`，2,995 行、11 处 `remember`、3 处 `LaunchedEffect`、24 处协程启动 | 多个跨功能状态已转移到 state holders，根页面仍承担导航、发送与业务编排；继续按业务边界拆分，不做机械拆文件 |
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


## 2026-11-18 批次 A 进展补充（会话启动恢复）

- 新增 `data/ConversationStartupCoordinator.kt`，集中负责 Room/旧 SharedPreferences 快照恢复、活动项目校验、活动会话选择、首屏消息加载，以及恢复后活动 ID 持久化。
- `NestroApplication` 注册该协调器；`NestroApp` 的启动 effect 改为接收 `StartupState` 并应用到 Compose 状态，不再直接编排会话/项目恢复和首屏消息读取。
- 会话中断请求恢复仍由 `ChatRequestRecoveryCoordinator` 负责；进程级 Agent 任务恢复仍由 `NestroApplication.onCreate()` 负责，三个恢复职责保持分离。
- `NestroApp` 仍保留全局记忆/专家配置加载、聊天请求恢复结果应用、页面状态初始化及发送流程；Batch A 未完成。
- 本次启动恢复拆分尚待最新 head 的 Android CI 核验；不构建 APK、不进行真机验收，PR #3 保持 Draft / 未合并。

## 2026-11-18 批次 A 进展补充（恢复取消安全）

- `ConversationStartupCoordinator.restore()` 对挂起恢复操作显式传播 `CancellationException`，避免 `runCatching` 将协程取消误判为 Room 故障并回退旧存储。
- 当前 head `8d9bbf0` 的 Android CI #797 已通过；取消安全修复随后提交，必须等待新 head 对应 CI 才能视为验证通过。
- 聊天发送编排仍集中在 `NestroApp`：Phone Agent 意图识别、上下文召回、知识/记忆注入、专家团执行、流式输出、部分回复持久化、会话保存及自动记忆提取仍在同一发送流程。下一步按执行阶段拆分，不把整个流程机械搬入单一巨型协调器。
- 当前未发现 `ConversationStartupCoordinator` 与 `ChatRequestRecoveryCoordinator` 的专门单元测试；后续补充恢复边界测试。PR #3 保持 Draft / 未合并；不构建 APK、不做真机验收。


## 2026-11-17 第一轮审查后的集中修复

- `ModelRequestConfigurationSelector.buildRegistryRequestConfigs()` 现在要求存在有效的显式 active model ID；ID 为空或已失效时返回空候选，不再因 failover 开启而静默选择 Registry 中任意模型。
- 删除当前 legacy Profile 时，先识别活动选择是否来自 `active_model_profile_id` 或 `active_model_registry_id`；切换备用 Profile 时同时更新两个活动 ID，找不到备用项时同时清理，避免 Chat 与 Phone Agent 仍指向已删除模型。
- 删除当前 legacy API Key 时，同时清理对应 Registry 活动 ID 和旧 Profile ID，防止保留失效选择。
- `ConversationSnapshotPersistenceEffect` 显式传播 `CancellationException`，仅将真实持久化故障交给 UI 错误回调。
- `ModelRequestConfigurationSelectorTest` 增加 active ID 为空和 active ID 已失效时不隐式选择模型的回归测试。
- 上述为源码修复与测试补充；必须以最新 head 的 Android CI 结果确认编译与测试通过。未构建 APK、未做真机验收；PR #3 保持 Draft / 未合并。


## 第二轮凭据选择审查（2026-11-17）

- 发现活动 Registry 模型恢复、配置刷新和手动激活三处仍直接读取首个非空密钥；旧密钥即使已在 Registry 中停用，仍可能被选中。
- 统一改为 `ModelRequestConfigurationSelector.buildRegistryRequestConfigs(..., failoverEnabled = false)`，确保仅使用启用且归属当前服务商的 Key。
- 新增回归测试：已停用 Key 即使仍能读出旧 secret，也必须跳过并选择后续启用 Key。
- 修复同时清理 `NestroApp` 中重复的超时异常判断。
- 本次修复提交待 PR head 更新并由新 CI 验证；不得把旧 CI #803 的成功结果套用到本次提交。

- 后续补强：能力草案生成协程也增加 `finally` 状态收尾；自动记忆提取/保存改用取消安全包装。普通任务错误仍返回失败结果，父协程取消不再被误判为普通业务失败。

- 同批补齐工作流执行的取消语义：父协程取消继续向上传播，用户主动停止保留为可记录的业务失败结果；`finally` 统一释放暂停闸门并清理运行态，避免工作流异常/取消后界面永久 busy。

- 恢复链专项测试补齐：将中断请求标记解析提取为纯函数，拒绝 malformed/non-running/缺少稳定 ID 的记录，并验证草稿只恢复到原会话；新增 ChatRequestRecoveryCoordinatorTest。


## 2026-11-18 当前树规模复核

- 对本轮待推送的完整 Git tree 重新统计：149 个文件；主源码 88 个 Kotlin 文件；JVM tests 33；Android tests 14。
- `NestroApp.kt` 为 2,995 行，`remember` 11 处、`LaunchedEffect` 3 处、`scope.launch` 24 处。状态所有权拆分已明显减少根页面本地 `remember`，但协程/业务编排仍是后续重构重点。
- 统计基于当前待推送 tree `4b9f207`，最终以推送后 head tree 为准。

- 会话启动恢复进一步解耦：活动项目/会话选择提取为纯函数 `resolveActiveConversationSelection`，覆盖已归档项目回退、跨项目会话拒绝和缺失会话回退的单元测试。

- 补齐 Chat 用户停止的重试边界：`OpenAiClient.streamWithFallback` 接收请求级 `shouldStop`，停止请求在候选模型切换/重试前都会终止，不会因断开当前连接后又自动重试备用请求；父协程 `CancellationException` 仍独立传播。

- 记忆页按需加载也改为取消安全包装：切换页面/项目时，旧记忆查询不再把协程取消吞成空列表并回写过期状态。


## 2026-11-18 模型目录损坏恢复保护

- `ModelRegistryCodec.decodeOrNull()` 新增严格解码入口，将“合法空目录”与“损坏/不兼容目录”区分；原 `decode()` 保留宽容兼容行为，避免破坏既有调用方。
- `ModelRegistryStore` 在 Registry 无法解码、需要从旧 Profile 重建前，先将原始 JSON 原样隔离保存到 `model_registry_v1_unreadable_backup`，并记录隔离时间；只保留首份隔离副本，避免后续错误覆盖。该保护不声称已恢复自定义目录，只确保原始数据不会被静默覆盖丢失。
- 新增严格解码回归测试，覆盖 malformed JSON 与合法空 Registry 的区别。
- 修复提交 `c12f8350cd4127b6bafc64d674beb2e383cafc03` 已推送；CI 触发状态待核验。

## 2026-11-18 — Chat timeout cancellation boundary

- `OpenAiClient.streamWithFallback` owns a bounded `withTimeout` around the streaming request and propagates coroutine cancellation after disconnecting the request connection.
- `runCatchingCancellable` now converts a nested `TimeoutCancellationException` into a recoverable failed `Result` only when the caller coroutine remains active; parent/user cancellation still propagates.
- Added JVM tests for nested timeout recovery and caller cancellation propagation.
- This preserves the existing chat completion path so the timeout message and completed-history persistence branch can execute for request timeouts.
- No APK build, device acceptance, PR merge, or Ready transition.


### Model Registry provider lifecycle follow-up
- Added provider metadata editing while retaining stable provider IDs.
- Added explicit confirmation before provider deletion; deletion removes associated model/key metadata and then deletes each associated encrypted API Key through `ModelConfigurationRepository`.
- Refreshes the active model after provider deletion so an active model owned by the removed provider is cleared rather than left configured.
- Provider deletion is performed as one UI action; no provider ID is regenerated during edit.

- Added provider edit/delete controls with stable IDs and explicit deletion confirmation; provider deletion also clears associated encrypted API Key material and refreshes active model state.
- Added model editing for display name, model code, ordered API Key associations, input/output per-million-token prices, and currency; existing enabled state and other metadata are retained when editing.
- Added a JVM regression test proving model identity, enabled state, key associations, prices, and currency survive a metadata edit.
- Added JVM coverage for stable provider identity during edits and for disabled providers becoming unselectable without deleting their keys/models.


## 2026-11-18 — Agent trace cancellation boundary

- Agent task execution context and coordinator previously wrapped trace persistence in `runCatching`, which could swallow coroutine cancellation while writing a trace.
- Trace persistence failures remain non-fatal, but `CancellationException` is now explicitly rethrown in both trace paths.
- Added a JVM regression test proving checkpoint state persists while cancellation from trace storage still propagates.
- This is a lifecycle correctness fix; task state transitions, human handoff, and WorkManager ownership remain unchanged.

- CI #810 exposed a JUnit test-discovery error: the new Model Registry metadata-preservation test had been appended outside `ModelRegistryTest`, so JUnit discovered a top-level file facade with no valid test methods. The test is now inside the test class; no production-code compile error was reported. This correction is included in the next staged head and must be revalidated by CI.

- Phone Agent task log loading now uses the shared cancellation-safe result wrapper: ordinary log-store errors still degrade to an empty log list, but page/worker coroutine cancellation propagates instead of being swallowed.

- Backup restore now uses `ModelRegistryCodec.decodeOrNull()` for a non-empty model registry payload and rejects malformed/incompatible registry JSON instead of silently accepting the legacy decoder's empty-registry fallback. This protects user-defined provider/model metadata during restore; valid empty registries remain distinct from invalid payloads.
- Phone Agent cancellation now completes WorkManager cancellation, notification cleanup, and shared coordinator cancellation in a `NonCancellable` section after the durable Room terminal-state write. This prevents caller/screen coroutine cancellation from leaving an already-cancelled task executing in the shared coordinator.
- Follow-up UI review found and fixed a destructive targeting edge case: the provider delete confirmation now captures the exact row's provider ID rather than deleting whichever provider happened to be selected. Deleting a non-selected provider no longer changes the current selection; switching provider also cancels an in-progress model edit to prevent accidental cross-provider reassignment.
- Provider URL validation now parses HTTPS endpoints and requires a host, rejecting embedded credentials, query strings, and fragments; regression coverage includes malformed/unsafe endpoint examples. Provider deletion refreshes/clears an active model immediately after registry persistence and before encrypted-secret cleanup, so a secret-store cleanup error cannot leave the removed model active.
- Provider deletion now also removes matching legacy Profile records and their active selection, not just Registry rows. This prevents `ModelManagementUiState.initialize()` / legacy reconciliation from recreating a provider immediately after the user deletes it. Active legacy config is cleared before secret cleanup; the operation remains compatible with non-legacy Registry providers.
- Model Registry UI no longer displays local success messages immediately after callbacks that may fail inside the application boundary. Persistence errors are reported by the owning app status; the page keeps local messages for validation and non-persistence feedback only.
- Legacy provider IDs are derived from their endpoint. The Registry provider editor now prevents changing the URL of a legacy provider and directs users to the legacy Profile editor, which performs the supported migration path; the application callback also enforces this guard. This prevents startup reconciliation from recreating a second provider or orphaning model references.
- Legacy provider metadata is entirely migration-derived (both label and endpoint), so the Registry editor now hides its edit action and the application boundary rejects changes to either field. Legacy Profile editing remains the supported path; non-legacy providers retain full edit capability.

## 2026-11-18 — Phone Agent fatal-error boundary

- `PhoneAgentWorker.executeTask()` previously caught `Throwable`, which also includes JVM fatal `Error` subclasses (for example `OutOfMemoryError` and linkage failures), then converted them into a successful WorkManager result after marking the task paused.
- The handler now catches ordinary `Exception` only. `CancellationException` remains handled by its dedicated branch; fatal JVM errors are allowed to propagate to WorkManager/runtime instead of being disguised as recoverable task failures.
- This does not automatically retry an ambiguous device action. The existing interruption policy remains conservative: the task is paused for manual state verification before resuming.
- CI must validate the current PR head; no APK build, device acceptance, PR merge, or Ready transition.

## 2026-11-18 — Phone Agent cancellation must preserve the shared WorkManager queue

- `PhoneAgentWorkScheduler` appends tasks to one unique WorkManager chain. Cancelling a tagged WorkRequest inside that chain risks cancelling dependent queued requests, while their per-task pending markers would remain set.
- Scheduler cancellation now persists the per-task cancel flag and optionally requests the active Accessibility service to stop, but does not cancel the WorkRequest by tag. `PhoneAgentTaskService` first persists the Room terminal state and then cancels the shared coordinator; the worker observes the terminal state/cancel flag and exits successfully, allowing later queued work to proceed.
- This preserves the global queue order and avoids cancelling unrelated tasks. It complements the recovery fix that keeps WorkManager-owned `phone_agent` tasks out of direct application-scope dispatch.

## 2026-11-18 — Phone Agent fatal-error and cancellation cleanup boundaries

- `runCatchingCancellable` now captures ordinary `Exception` failures only; fatal JVM `Error` subclasses propagate instead of being converted into recoverable `Result.failure`. Added a JVM regression test using `AssertionError`.
- Phone Agent planning and typed-action execution now use `Exception`/cancellation-safe handling. The suspend action wrapper propagates coroutine cancellation and no longer turns fatal runtime errors into an ordinary failed device action.
- `PhoneAgentAgent.pause()` now uses the cancellation-safe result helper for best-effort audit/state writes; cancellation is not swallowed.
- `PhoneAgentWorker` cancellation cleanup now runs Room/state-store reconciliation in `NonCancellable`, then rethrows the original worker cancellation unless the persisted user-cancel request requires a successful terminal result. This avoids losing cleanup immediately because the worker coroutine is already cancelled.

## 2026-11-18 — WorkManager reattachment and shared trace cancellation

- The first reattachment draft exposed a missed call site: `PhoneAgentWorker` still treated persisted `RUNNING` as a passive wait. It now calls `requeueRunningTaskIfIdle()` so a WorkManager worker can reattach the task after process recreation; an already-active coordinator handler remains untouched.
- `DefaultAgentRuntime.trace()` no longer uses `runCatching` around a suspending trace write. Ordinary trace-storage exceptions remain non-fatal, but `CancellationException` propagates; a JVM regression test verifies that cancellation during the first trace prevents tool execution.
## 2026-11-18 — Durable cross-store backup restore journal

- Backup restore now snapshots the pre-restore Room/Artifact data as a verified local backup plus typed SharedPreferences snapshots (including encrypted API-Key ciphertext, never plaintext) into an app-private AtomicFile journal before mutation.
- The restore is applied only after the journal is durably written. On any thrown failure, the old backup and preference snapshots are replayed as compensation. If compensation fails, the journal is retained and the error explicitly blocks startup rather than silently continuing with mixed state.
- `NestroApplication.ensureBackupRestoreRecovered()` serializes recovery and is awaited before Compose startup and before Phone Agent Workers touch task state. A process death leaves the journal for the next process to replay idempotently.
- Added an instrumentation regression test that simulates process death after partial Room/Preferences/Key mutation and verifies recovery of Room data, preferences, encrypted API keys, and journal cleanup. CI must validate; no APK/device acceptance is being performed.


## 2026-11-18 — Phone Agent pending-marker crash recovery

- PhoneAgentWorkScheduler now timestamps each pending marker. Application startup queries the persisted WorkManager unique queue and compares live task IDs with stale markers.
- A marker older than 30 seconds with no live WorkRequest is reconciled: active Room tasks are re-enqueued with saved handoff/retry flags; terminal or missing tasks have stale scheduling metadata cleared. A failed WorkManager query does not clear markers or trigger duplicate work.
- PhoneAgentWorker clears the timestamp together with the existing pending marker during normal completion.
- Added JVM policy tests for live work, startup grace period, stale marker recovery, and clock rollback.
- This addresses the crash window after marker persistence but before WorkManager enqueue; the Room/SharedPreferences/LocalVault/Artifact restore transaction remains unresolved and separate.

- The interrupted-restore instrumentation test now also deletes a pre-existing Artifact after journaling and verifies the old Artifact content is restored, covering the file/index store in the compensation path.

## 2026-11-18 — Durable WorkManager marker persistence

- Pending marker, enqueue timestamp, and handoff/retry flags are now synchronously committed before submitting the WorkRequest. If SharedPreferences cannot persist the marker, scheduling aborts before WorkManager enqueue rather than creating an untracked request.
- `enqueue()` is synchronized within the process so concurrent calls cannot both pass the pending-marker guard and append duplicate WorkRequests.

## 2026-11-18 — CI #819 compiler inference fix

- CI #819 failed in `PhoneAgentWorkScheduler.reconcilePending()` because Kotlin inferred the WorkManager query's `try/catch` expression as `Nothing`/`Unit`, leaving `WorkInfo.inputData` unresolved.
- The query result is now explicitly typed as `List<WorkInfo>` before mapping active task IDs; WorkManager query failure still exits reconciliation without clearing markers.

## 2026-11-18 — CI #820 WorkInfo query correction

- CI #820 failed because AndroidX `WorkInfo` does not expose the original `inputData` property; the active WorkManager query was therefore attempting to read a nonexistent member.
- Reconciliation now derives active task tags from non-finished `WorkInfo.tags` and checks each task's existing unique tag. Query failures still exit without clearing any pending marker.
- The same prepared batch extracts expert-team execution lifecycle from `NestroApp` into `ExpertTeamExecutionCoordinator`, with JVM coverage for member execution, status callbacks, and shared task completion.

## 2026-11-18 — Chat request context assembly extracted

- Added `ChatRequestContextAssembler` to own the recent-history recall query, project-scoped conversation retrieval, knowledge retrieval, user-memory retrieval, Phone Agent context, and bounded system-context assembly.
- `NestroApplication` owns the assembler; `NestroApp` now applies its result to recall counters/sources and passes the assembled context to the existing team/stream execution path.
- Added JVM coverage for the last-eight-message query window, project/conversation scoping, context composition, recall source propagation, and the 16k-character cap.

- CI #822 identified one Kotlin API visibility error in the newly extracted chat context assembler: its public method exposed internal `ChatMessage`. The assembler/result and the application composition-root property are now internal, matching their app-internal use and avoiding accidental public API leakage.

- Backup repository operations are now serialized with a repository-scoped Mutex. Export first replays any interrupted restore journal before snapshotting Room/Preferences/Vault/Artifacts, preventing a backup of partially restored state; restore and explicit recovery share the same lock. Added instrumentation coverage for export-triggered recovery.

## 2026-11-18 — Workflow lifecycle boundary (restored code-map entry)

- `WorkflowExecutionCoordinator` owns workflow task registration, queued/running/finished checkpoints, and shared AgentTaskCoordinator lifecycle.
- `NestroApplication` owns the process-scoped coordinator. `NestroApp` supplies workflow definition/input, model invocation, and pause/stop/UI progress callbacks; existing WorkflowEngine retry, timeout, repeat, skip, and continue-on-error semantics remain unchanged.
- JVM coverage validates step execution, progress callback, output, and terminal shared-task state.

## 2026-11-18 — Chat streaming and partial-response persistence extracted

- Added `ChatStreamingCoordinator` as the boundary for model stream transport, incremental partial-response persistence, and cancellation-safe result handling.
- Partial answers continue to be written to `chat_request_state_v1` with the same request/conversation/project metadata and 24k cap; Compose root only applies partial text to the active conversation.
- Added JVM tests for incremental persistence, preserving partial output on transport failure, caller cancellation propagation, and partial-state JSON metadata/size bounds.

## 2026-11-18 — Retry pending restore recovery within the same process

- `ensureBackupRestoreRecovered()` now checks for a journal even after one successful startup recovery. If a later restore attempt fails and its compensation journal remains, subsequent UI/Worker entry points retry recovery instead of trusting a stale in-memory completion flag.
- `LocalBackupRepository.hasPendingRestoreJournal()` exposes only the app-private journal presence; it does not read or expose journal contents.

## 2026-11-18 — Automatic memory extraction moved behind a coordinator

- Moved long-term-memory signal filtering and JSON candidate extraction from `ConversationIntelligence.kt` into the data layer, then added `AutomaticMemoryExtractionCoordinator` to own enabled/safety gating, extraction, and repository persistence.
- `NestroApp` now schedules one coordinator call after a successful non-stopped response; it no longer directly decides memory eligibility or coordinates candidate persistence.
- Added JVM tests for eligible prompts, disabled memory, low-signal prompts, and persistence scoping.

## 2026-11-18 — Chat response finalization extracted

- Added `ChatResponseFinalizer` to centralize success/partial/stop/timeout precedence, assistant revision history retention (dedupe + last 20), and terminal response status.
- NestroApp now applies the returned completed history/status and remains responsible only for local conversation persistence and Compose state updates.
- Added JVM tests for successful output, partial output on failure, explicit stop, and timeout messaging.

## 2026-11-18 — Capability draft generation extracted

- Added `CapabilityDraftCoordinator` for expert/skill draft prompt construction, model invocation, and strict JSON/name/prompt validation. `isCreationIntent` moved into the data layer with the coordinator.
- `NestroApp` now handles the resulting draft preview/confirmation UI only; model request and definition parsing are delegated to the coordinator.
- Added JVM tests for expert draft prompt context, existing-name constraints, strict definition parsing, and negated creation intent.

## 2026-11-18 — CI #827 revision-history extension fix

- CI #827 found that `ChatMessage.allVersions()` had been a private extension in NestroApp and was not visible to the extracted `ChatResponseFinalizer` data-layer service.
- Moved the exact revision-history rule into an internal data-layer extension and added a regression test covering an active version beyond the stored version list and appending the new response.

## 2026-11-18 — Chat request timestamp and active-task restore protection

- The chat request start timestamp is captured once at send time and reused for every persisted partial answer; streaming chunks no longer reset `started_at`. JVM coverage checks timestamp stability.
- Backup restore replays any earlier interrupted journal, then rejects replacement while Phone Agent tasks are planned, running, paused, or waiting for user confirmation. The same check runs inside the Room transaction that replaces task rows, closing the preflight race.
- If the transactional guard rejects before any database mutation, the just-created restore journal is deleted rather than replayed; this preserves any task created after the journal snapshot. Rollback recovery itself deliberately bypasses the active-task guard.
- Instrumentation coverage checks all four non-terminal task states remain intact and no stale journal is left after rejection.

- Phone Agent pending-marker reconciliation now keeps the durable marker until the Worker consumes it. A stale marker is no longer removed before retry enqueue; `enqueue()` may explicitly reuse the existing marker, and WorkManager enqueue exceptions leave it intact for a later startup retry. This closes the orphaned-task window when WorkManager rejects or fails an enqueue attempt.

- CI #829 compiled production Kotlin and completed its first Gradle build, then failed in `ChatStreamingCoordinatorTest`: one cancellation-path fake still implemented the old five-argument `ChatPartialStateStore` lambda after the timestamp parameter was added. Updated the remaining fake to the six-argument contract; no production-source compile errors were reported in this run. This test-only correction is included with the pending-marker retry fix.

- Reconciliation now isolates synchronous enqueue failures per task: the failed task keeps its durable marker for the next startup, while other pending tasks continue through the same reconciliation pass. Coroutine cancellation still propagates.

- Early user-stop handling now clears `chat_request_state_v1` after saving the stopped conversation. Previously, stopping during Phone Agent intent planning returned before the normal finalizer and left a stale `running` marker that could be misinterpreted as an interrupted request on next launch.


## 2026-11-18 — Lightweight chat, controllable reasoning, and precision tool dispatch (product-wide refactor requirement)

This is a cross-cutting architecture requirement for Phase 2, not a later polish item. Preserve it while extracting the chat pipeline and integrating the task/agent runtime.

### 1. Separate ordinary chat from task execution

- Ordinary chat is the default path. It must not implicitly start an Agent task, invoke Phone Agent planning, run an expert team, enumerate/initialize the full tool catalog, or load task-only context.
- Task execution is a separate, explicit path. Enter it only when the user deliberately selects a task/action mode or clearly requests an action that requires execution. Keep task state, task history, and task-only context scoped to that task; do not carry them into subsequent ordinary chat.
- A conversational mention of an action/tool is not, by itself, authorization to execute it. Ambiguous intent stays in chat or asks for clarification; never create a task merely because a keyword matched.

### 2. User-controllable reasoning effort

- Provide four clear reasoning choices: **Off (关闭思考) / Light (轻量思考, default) / Medium (中度思考) / Heavy (重度思考)**. The UI may present Off and the three effort levels as compact controls; provider/model capability differences must be handled honestly.
- Off means request the provider’s explicit no-reasoning/none setting only when the selected model/provider documents support for it; otherwise omit the parameter and clearly expose that the provider cannot guarantee this mode. It does not promise that every provider internally performs zero reasoning.
- Lightweight is the default for ordinary chat. Medium and Heavy are opt-in for complex questions or tasks. Do not silently escalate effort because a tool or expert is available.
- If a provider does not expose a reasoning-effort parameter, omit it rather than sending invented or unsupported parameters.

### 3. Precision, capability-scoped tool dispatch

- Resolve the requested action to the minimum capability set required for that action, then load only those tool definitions and credentials. Never inject the entire MCP/plugin/tool catalog into every prompt or enumerate every tool on every turn.
- Use a staged resolver: local intent/rule routing first where reliable; only request model-based planning when ambiguity or task complexity warrants it; then dispatch only the selected tools.
- Tool calls must be justified by the current task plan and user authorization. No unrelated tools, broad discovery sweeps, speculative calls, or repeated initialization.
- Reuse the task's selected tool context during that task where safe; release task-only context when it ends. Keep ordinary chat's prompt/history compact and independently budgeted.

### 4. Token and context budgets

- Define separate budgets for ordinary chat and task execution: recent-turn window, summary/compaction policy, memory retrieval, knowledge retrieval, expert context, tool schema, and output.
- Ordinary chat should use a small recent context and only retrieve memory/knowledge when there is a concrete relevance signal. Do not attach the full conversation, all memories, all project instructions, or all skill definitions by default.
- Task execution may spend more tokens, but context must be assembled incrementally for the current action and bounded by explicit limits. Record per-request input/output usage and, where available, tool/planner usage.
- Add tests proving that an ordinary chat turn does not trigger Agent/Phone Agent task creation, expert execution, or tool catalog loading; task-mode tests should assert only the required tools are selected.

### 5. Acceptance gates

- A plain conversational message makes no task/Phone Agent scheduling call and does not load MCP/plugin definitions.
- A previously selected expert team must not execute during ordinary chat; team execution requires the explicit task-execution mode. Merely persisting a selected team is not per-message authorization.
- An explicit action selects only its required capabilities; unrelated tools are absent from the model context and are not initialized.
- Ordinary chat defaults to Light (轻量思考); users can select Off (关闭思考), Medium (中度思考), or Heavy (重度思考). Never silently escalate from the selected level.
- Task context and tool schemas do not leak into the next ordinary chat turn.
- Prompt/token usage is bounded and measured separately for chat and tasks; memory/knowledge retrieval is selective rather than unconditional.
- Provider limitations are visible and safe: unsupported reasoning parameters are omitted, not guessed.

### Implementation sequencing

Apply these requirements while finishing the Phase 2 chat pipeline and Agent/Phone Agent lifecycle refactor. Add routing and token-budget regression tests in the same batches as the implementation. Do not postpone this to Phase 3 or treat it as an optional optimization.


## 2026-11-18 — Explicit task-mode gate for Phone Agent

- Added a persistent composer switch with two states: **普通聊天** (default) and **任务执行** (explicit opt-in).
- Removed unconditional Phone Agent planning from every chat send. Ordinary chat now skips Phone Agent planning entirely, even if the text contains keywords such as 搜索、咖啡 or 预约.
- Phone Agent planning/task creation runs only while the user has explicitly enabled task mode. A GENERAL plan still does not create a task.
- Added JVM regression tests proving action-like wording in ordinary chat does not create a task, while explicit task mode can create a non-GENERAL task.
- The switch is persisted locally so the selected mode survives app restarts; the visible composer label always shows which mode is active.

## 2026-11-18 — Selective context retrieval for low-cost ordinary chat

- Added a local, zero-model-call retrieval policy for conversation history, project knowledge, and personal memory. Each source is queried only when the current prompt contains a relevant reference signal.
- Ordinary chat no longer receives Phone Agent planning instructions. Those instructions are included only when explicit task mode is enabled.
- Removed unconditional full global-memory injection. Personal global memory is included only for prompts that explicitly refer to the user’s preferences, background, habits, or remembered details, and is capped at 1,600 characters.
- Reduced the assembled optional context ceiling from 16,000 to 8,000 characters; the ordinary-chat path can remain at only its base context when no retrieval signal is present.
- Added JVM tests asserting an action-keyword ordinary chat causes zero optional retrieval calls and receives no Phone Agent instructions, while explicit task mode adds the task-only instructions.

## 2026-11-18 — Four-level reasoning control wired to model capabilities

- Added four composer choices: Off (关闭思考), Light (轻量思考, default), Medium (中度思考), and Heavy (重度思考). The choice is persisted locally.
- Added an explicit per-model capability flag in the model registry. It defaults to false for existing/legacy models and is preserved across registry edits and legacy reconciliation.
- The request selector attaches reasoning_effort only to models whose configuration explicitly declares support. Failover candidates are evaluated independently; unsupported backups receive no parameter.
- Completion and streaming requests include the selected provider value (none, low, medium, high) only when the selected model has declared support. This avoids breaking generic OpenAI-compatible endpoints that reject unknown fields.
- Added tests for registry backward compatibility and per-model reasoning-parameter selection.
- The composer displays an explicit unsupported-capability marker when the active model has not declared four-level reasoning support; the user selection is retained, but the unsupported model receives no reasoning parameter.
- Regression coverage checks all four values (`none`, `low`, `medium`, `high`) on a capable model and confirms unsupported failover models omit the parameter.

## 2026-11-18 — Snapshot task authorization at send time

- Capture task-mode state, selected team ID, team list, expert profiles/prompts, skills, and disabled-skill set before launching the request coroutine.
- Phone Agent planning, expert-team execution, and request context assembly use only captured request values; later composer changes cannot silently upgrade or alter an in-flight request.
- Added pure JVM coverage for ordinary-chat denial and explicit task-mode authorization.


## 2026-11-18 — Keep selected expert/skill assets out of ordinary chat

- The request snapshot gates selected expert role prompts, expert profile skill context, and selected skill prompt assets behind explicit task mode.
- Ordinary chat still performs only prompt-triggered, bounded conversation/knowledge/user-memory retrieval; it does not inject expert or skill instructions merely because a prior selection persisted.
- Added JVM coverage for the ordinary-chat/task-mode prompt-asset gate.


## 2026-11-18 — Enforce a bounded model-history window

- Existing token-aware history budgeting is now wired into both normal streaming requests and each expert-team member request.
- The full conversation history remains intact for UI display and persistence; only the per-request model payload is trimmed to the configured context/output budget and the latest 40 messages.
- Expert member prompts now use the immutable request-time expert prompt snapshot rather than reading mutable composer state.
- Added regression coverage proving model history is bounded while the original persisted history remains unchanged.


## 2026-11-18 — Complete request-time state snapshot

- Captured active expert IDs, project instructions, automatic-memory preference, and user ID alongside task mode, team, expert profiles/prompts, skills, and disabled skills.
- The in-flight chat and its post-response memory extraction no longer read these mutable UI values after launch.


## 2026-11-18 — Task-scoped tool capability sessions

- Added a narrow `AgentToolAccess` boundary and task-local `AgentToolSession` leases. A session snapshots only explicitly selected tool definitions, exposes no other catalog entries, rejects invocation outside its allow-list, and becomes unusable after `close()`.
- `DefaultAgentRuntime` now accepts the narrow access interface rather than requiring the mutable global catalog.
- Phone Agent now registers a typed capability for the current action only (open URL, launch app, click, enter text, scroll, wait, verify, back, or sensitive handoff). It opens a one-capability session for that step and releases the session and registration immediately afterward.
- Added JVM tests for allow-list isolation, unknown tool rejection, and post-close invalidation.
- This is the first executable slice of precision dispatch, not completion of the entire unified tool center. MCP/plugin discovery, per-tool schema loading, credential leasing, and cross-module expert/workflow tool routing remain outstanding.


## 2026-11-18 — Keep the newest Phone Agent action context

- The bounded Phone Agent history previously retained the oldest action-result lines when the 1,000-character cap was reached, potentially dropping the most recent page transition evidence.
- Changed the compactor to deduplicate and retain the latest 12 action results within the same fixed budget.
- Added a JVM regression test proving recent actions survive while stale first actions are dropped.


## 2026-11-18 — Correct token accounting and include Phone Agent calls

- Streaming fallback estimates now use the same bounded-payload token estimator as non-streaming calls; the previous streaming fallback still counted raw characters.
- Added a direct estimated-token recorder so estimated token counts are not divided by four a second time as though they were characters.
- Usage attribution now resolves the actual selected Registry model ID (while preserving legacy profile IDs), rather than incorrectly assigning unknown/failover calls to the active profile.
- Phone Agent planning calls now record provider-reported usage when available and bounded local estimates otherwise.
- Added JVM coverage for direct estimated-token recording and Registry model usage attribution.
- Provider-reported and estimated counts remain separate. Calls that return no provider usage are explicitly counted as estimates.


## 2026-11-18 — Include background model calls in usage accounting

- Automatic-memory candidate extraction now forwards both provider-reported usage and local estimated usage to the same per-model accounting path.
- The background extraction path resolves the actual Registry model ID and persists the usage separately as provider-reported or estimated.
- Chat usage callbacks now reload the latest persisted usage map before applying each increment, reducing stale Compose-state overwrites when background Phone Agent/memory calls run concurrently.
- Added regression coverage for mapping both custom Registry model IDs and legacy profile IDs to stable usage-stat keys.


## 2026-11-18 — Keep capability selection separate from authorization

- Added a regression test proving that an explicitly loaded `EXTERNAL_WRITE` tool remains blocked by the default permission router and transitions the task to `WAITING_FOR_USER`.
- A task-scoped allow-list only controls what is discoverable/invokable; it never grants permission. Every invocation still passes through the permission router.


## 2026-11-18 — Pin tool handlers to task-local leases

- Fixed a registry race in task-scoped tool sessions: the session now captures the selected tool definitions and their exact handler references when the lease opens.
- Re-registering the same tool ID while a task is running can no longer silently redirect that in-flight session to a different handler.
- Added a JVM regression test proving that the session keeps its original definition/handler while the global catalog uses the replacement for newly opened calls.


## 2026-11-18 — Revocable task capability and credential leases

- Added a process-local `AgentToolLeaseRegistry` with lease IDs and task ownership. A task can release one lease or revoke every open capability lease associated with its task ID.
- Added short-lived `AgentCredentialLease` / `AgentCredentialLeaseRegistry` primitives. Credentials are resolved on demand from their secure owner, are not copied into the registry or persisted by the lease layer, and are bounded to a maximum 15-minute TTL.
- `AgentTaskCoordinator` now revokes task capability and credential leases before pause/cancel and again when a task handler exits, so a stale task cannot keep using a previously opened lease.
- Phone Agent action execution now opens its one-action capability through the lease registry and releases it in `finally`.
- Added JVM regression coverage for task-wide tool-lease revocation and task-scoped credential resolution/revocation.
- This closes the lease lifecycle foundation for the current local tool runtime. It does **not** claim that MCP/plugin discovery, remote-provider credential adapters, dynamic schemas, or expert/workflow per-step tool routing are complete. Those remain Phase 2 integration work.



## 2026-11-18 — Unified capability registration and per-operation routing

- Added `AgentCapabilityRegistry` as the shared declaration point for operation-scoped minimum tool sets. Expert members are keyed by member name; workflow steps are keyed by stable step ID. Empty declarations load no tools.
- The registry validates identifiers and tool counts, resolves immutable tool sets, checks that every declared tool is currently registered, and fails closed when a declared tool is missing.
- Expert-team and workflow coordinators now resolve the declaration for each member/step, open a task-owned lease only for that operation, pass the narrow `AgentToolAccess` to a tool-aware adapter, and release the lease in `finally`. If tools are declared but no tool-aware adapter is supplied, execution fails closed rather than silently ignoring the declaration.
- `NestroApplication` now owns one process-wide `AgentToolCatalog`; Phone Agent, expert-team and workflow execution share this catalog. Phone Agent no longer creates an isolated catalog for each run.
- Added coordinator regression tests proving expert/workflow operations see only their declared tools and that the access view is invalid after the operation returns.
- Current expert/workflow model adapters remain model-only and declare no tool sets by default. This is intentional: the shared boundary is ready, but model-native tool-call execution and MCP/plugin discovery are not marked complete.
- Tool selection remains separate from authorization. Any actual invocation must still pass through `AgentPermissionRouter`; loading a tool does not grant permission.
