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
