# Phase 2 product and architecture optimization

> **当前状态校正（2026-11-22）：** 本文记录的是 `feature/phase2-optimization` 当时的源码阶段记录，不是当前整合分支的最终验收结论。该分支目前没有领先于 `integration/19-modules` 的独有提交，但本文中的“六项 source complete”不能直接推断当前整合分支仍满足用户后来补充的所有交互要求。当前源码审计发现：Phone Agent 浮窗仍有标题栏/展开收起入口，双击才缩放，和后续“无标题/按钮、点击窗口缩放”的要求不一致；模块18当前仍只有日程通知提醒，未接入统一 Agent/任务/工作流调度；主屏导航缺少专用地图规划/路线预览/授权流程；工作流审批等待状态仍未接入通用 `WAITING_FOR_USER` 持久状态。以 `docs/FUNCTION_AUDIT.md` 的当前源码证据和 `docs/IMPLEMENTATION_ROADMAP.md` 的最新需求为准。本文保留用于追溯历史实现，不得单独作为当前功能完成依据。

Status: implementation in progress. This document records the agreed target; a checked item means code exists, not that build/device acceptance has passed.

## Cross-module improvements recovered from development

- [x] AI co-development workbench source: protected-path policy, patch preview, impact analysis, explicit per-file approval, durable snapshots, transactional apply/recovery and rollback; GitHub-hosted Actions runner provides remote build/test isolation. GitLab/Gitee runners and real Actions execution validation remain pending.
- [x] Shared Agent capability foundation exists: `AgentToolCatalog`, task-scoped sessions/leases, `AgentRuntime`, permission routing and MCP registration reuse the process-wide catalog. Remaining: unify Skill/plugin catalog lifecycle and verify every invocation path goes through the same boundary.
- [x] Project-space and cross-project READ/WRITE/resource-scope primitives exist, including auditable grants. [ ] Full lifecycle consistency across files, experts, teams, Skills, workflows, media and artifacts still needs integration verification.
- [x] Multi-provider/model/key registry, protocol-native OpenAI/Anthropic/Gemini calling, model selection, fallback and usage attribution exist. [ ] Rate limits, budgets and cost-aware routing remain incomplete.
- [x] SemVer dependency solving, atomic install/rollback, independent trust root and revocation code exist. [ ] Official signed catalog distribution, operator-provided trust root/revocation feed and real community source validation remain pending.

## Second optimization: device adaptation and responsiveness

- [x] Drawer width now adapts across compact phones, regular phones, tablets and expanded windows.
- [x] Independent optional context retrievals (conversation recall and user memory) run concurrently; unused retrieval sources are not started.
- [x] Knowledge lookup is no longer prefetched by keyword; the model selects the project-scoped `knowledge.search` capability when needed.
- [x] Optional phone-task planning overlaps context assembly instead of serially extending the pre-stream critical path.
- [ ] Complete responsive layouts for compact phones, landscape, tablets and foldables; verify keyboard/IME insets, safe areas, font scaling, navigation and split-screen.
- [ ] Profile cold start, first-frame time, recomposition, memory, CPU, storage, network wakeups and battery on representative low/mid/high-tier devices.
- [x] Chat optional retrievals are skipped by policy and independent selected retrievals run concurrently.
- [ ] Remove avoidable main-thread work and duplicate parsing; add bounded caches and lazy feature initialization where profiling proves benefit.
- [ ] Define performance budgets and verify regressions with repeatable benchmarks. Do not claim "high responsiveness" based on source inspection alone.

## Product principle: model-led coordination, one execution base

The intended request path is:

1. The user sends a natural-language instruction.
2. The selected model interprets intent, clarifies ambiguity when necessary, and decides whether a direct answer is sufficient or a capability/tool is required.
3. Direct-answer path: the model replies or performs information synthesis without starting unrelated agents or tools.
4. Tool path: the model proposes typed tool calls; the unified dispatcher validates the request against the capability catalog, project scope, user grants and risk policy.
5. The dispatcher schedules and executes authorized work, persists task state, supports cancellation/recovery, and returns structured results to the model.
6. The model synthesizes the verified results into the user-facing response.

Architecture invariants:

- Model = intent understanding, reasoning, coordination and response synthesis.
- Unified dispatcher = capability lookup, authorization enforcement, scheduling, execution lifecycle, cancellation, audit and result handoff.
- Capability catalog selection is not authorization. Every call must pass the shared permission boundary.
- Feature modules must not independently bypass the dispatcher to invoke tools.
- High-risk or irreversible actions must pause for the user's explicit confirmation; no silent privilege expansion.
- Direct chat must remain lightweight: no eager construction of unrelated agents, no optional retrieval unless policy selects it, and no tool invocation merely because a task-mode preference was previously enabled.
- Tool execution results must be distinguished from model plans; the assistant may only report completed actions when the dispatcher returns verified completion.

## Current implementation boundary

The chat request coordinator centralizes context assembly and streaming. The process-wide AgentTaskCoordinator and shared AgentToolCatalog are reused by Phone Agent, expert teams, workflows, MCP and generated plugins. The model-led dispatcher prefers provider-native function calling for OpenAI-compatible Chat Completions, Anthropic Messages and Gemini; tool results are returned through each provider's native protocol and the loop is bounded. The compatibility DIRECT/TOOL text protocol is retained only when a provider explicitly rejects native tool calling. Tool decisions are allow-listed, task-leased, routed through AgentRuntime and its permission router, and receive bounded results for main-model synthesis. Phone Agent planning returns a typed AgentToolResult so task creation, authorization and scheduling outcomes are not flattened into false success. The dispatcher supports sequential capability composition: the main model may interleave phone execution, waiting, project asset verification, expert collaboration and media creation according to actual dependencies. Remaining boundaries include richer per-tool schema validation, complete project-scope enforcement for future handlers, and a product-level user-confirmation continuation for every high-risk external-write capability. Native calling and current routing are source-level implementations pending the unified verification phase.

## Acceptance sequence

Finish all feature code and architecture integration first. Only then run the agreed unified compile, automated tests, emulator checks and real-device acceptance. Until then, implementation status and acceptance status must remain separate.


## Existing code to reuse (do not duplicate)

- `agent/AgentRuntime.kt`: `AgentToolCatalog`, task-local tool sessions/leases, `AgentPermissionRouter`, `DefaultAgentRuntime`, trace and state transitions.
- `agent/AgentTaskCoordinator.kt`: persistent single-consumer task queue, idempotent enqueue, pause/resume/cancel and recovery.
- `NestroApplication.agentToolCatalog`: process-wide capability catalog already shared by Phone Agent, expert teams, workflows and MCP.
- `model/ModelRegistry.kt` and `ModelRequestConfigurationSelector`: provider/model/key registry and ordered request configuration selection.
- `ChatRequestExecutionCoordinator` and `ChatStreamingCoordinator`: existing single chat request boundary; extend these rather than creating another chat pipeline.
- `ProjectResourceScopeStore` / `ProjectAccessControlStore`: existing project resource scoping and explicit cross-project grants.
- Module 19 community code: SemVer dependency resolution and signed trust-root/revocation foundations.


## Newly connected implementation

- `ModelLedToolDispatcher` is wired into `ChatRequestExecutionCoordinator` from `NestroApplication`.
- The dispatcher uses the existing process-wide `AgentToolCatalog`, creates a task-scoped lease, invokes through `DefaultAgentRuntime`, persists task state and trace, and passes project scope to non-MCP handlers.
- Direct model responses are streamed to the chat UI in batches; ordinary streaming output also batches UI deltas and throttles partial-state writes to reduce recomposition and storage churn.
- MCP calls are allowed only while the persisted tool grant and server-enabled state remain valid; the MCP handler rechecks authorization at execution time.
- `knowledge.search` is available to the model and can only query the project ID injected by the dispatcher, not an ID supplied by the model.
- `phone_agent.execute` is the single canonical model-visible Phone Agent capability and is exposed only in explicit task mode. The duplicate `phone_agent.plan` registration and schema have been removed; both native and compatibility dispatcher paths route the canonical tool through the same task-planning/scheduling callback.
- `expert_team.run` is exposed only when the user explicitly selected an enabled team in task mode. Expert-team execution is deferred until the model selects that capability; it no longer runs automatically before the model decision.

## 历史阶段记录说明

本文件早期关于专家 DAG、插件工厂尚未接入的记录已被下方 2026-11-22 源码核查与实现状态覆盖；不得再按旧阶段描述判断当前分支状态。

## 2026-11-22 统一专家协作、插件工厂与 Android 助手入口补充

### 专家协作统一系统
- 产品架构不是两个互斥的专家团模式，而是一套统一执行系统的两个入口：主聊天入口由主模型自动查询专家目录、选择成员、拆分 DAG 工作包；专家中心入口允许用户临时勾选 2–4 位专家后回到聊天发起协作。
- 默认专家成员复用用户当前选定的同一套模型配置；专业差异来自专家角色指令、专属 Skill、独立任务上下文和共享工具能力，不依赖额外小模型。
- 聊天主模型可提交成员名单、工作包、依赖关系和验收标准；ExpertProjectPlanExecutor 校验依赖与循环并按拓扑波次执行。
- ExpertTeamExecutionCoordinator.executeProjectPlan 将 DAG 执行纳入 AgentTaskCoordinator 生命周期，并通过 AgentCapabilityRegistry 为专家任务创建工具租约。
- 依赖工作包的成果交接由 onHandoff 事件记录；专家开始、重试、完成、成果提交和团队完成事件保存在本地事件记录中，专家团队页显示最近协作时间线。
- 主模型在收到专家成果后被明确要求执行最终质量检查；工具 success 不代表成品已验收。
- 尚未完成：独立可交互项目工作区、双向自由消息、持久化事件分页/Room 迁移、工作包返工 UI、专家团队保留/解散操作和结构化验收协议。

### 受限 AI 插件工厂
- GeneratedPluginFactory 根据已注册 AgentToolCatalog 生成声明式插件包，包含 SemVer、输入字段、步骤、工具白名单与参数模板校验；拒绝递归插件及高风险设备/身份验证/金融工具。
- GeneratedPluginCenterPage 与 GeneratedPluginStore 已提供草案生成/预览、安装（默认关闭）、启用/停用、升级、版本回滚和卸载；启用插件才注册到共享能力目录。
- 插件只编排现有工具，不执行任意代码；每步仍通过统一 AgentRuntime 回调及工具自身权限路由。
- 仍待补齐签名/来源验证、依赖锁定、独立资源沙箱、插件级完整审批/审计视图、远程目录及导入/导出。

### Android 默认助手入口
- Manifest 增加 ACTION_ASSIST，系统助手触发时 MainActivity 进入主聊天页面。
- 设置页新增 Android ROLE_ASSISTANT 可用性检查和系统确认式申请入口；角色是否可用由设备运行时检查，不假设所有 OEM 都支持。
- 尚未实现 VoiceInteractionService/VoiceInteractionSessionService、常驻热词、锁屏语音会话、屏幕上下文读取和厂商专项适配。系统助手角色入口可先落地，完整语音助手体验后续扩展。

### 验收边界
- 本轮新增/修改均只提交代码和测试源码；按用户要求，尚未运行编译、JVM 测试、Lint、模拟器或真机验收。
- 设备全面适配与高响应/低资源专项优化仍在功能代码补齐之后进行，不提前开始统一验收。


## Native tool calling and multimodal media integration

- Added provider-native function calling for OpenAI-compatible Chat Completions, Anthropic Messages, and Gemini Generate Content. Native tool calls are preferred; the text decision protocol remains only for providers that explicitly reject function calling.
- Tool schemas are provider-shaped, tool results are re-injected using the corresponding protocol, and the loop is bounded. Authentication/network failures do not trigger a duplicate legacy model request.
- Added a separate asynchronous video generation provider path with video-only provider support, custom submit/status routes, configurable request/response field names, authentication header/prefix, and extra request JSON.
- Video generation tasks run through WorkManager, persist progress and revision lineage, download HTTPS outputs under size/host constraints, and import assets into the current project.
- Chat responses expose actions to open generated assets in the media editor or open the secure provider setup screen.
- Remaining validation and broader optimization work are still pending; no acceptance tests were run in this implementation phase.

## 2026-11-22 — Authoritative task-routing requirements and source-audit corrections

This section supersedes earlier stale statements in this document wherever they conflict with the current branch source or the user-confirmed product requirements below. Implementation status and acceptance status remain separate.

### One-sentence execution: choose a task path, do not force a fixed pipeline

The main model is the single user-facing decision and orchestration entry. It must classify the actual goal and choose the minimum sufficient execution path. The following paths are alternatives/composable branches, not mandatory sequential stages:

1. **Direct conversation / direct creation:** answer, explain, write or create directly when the configured model can do so. For substantial creative work, use the expert collaboration path when it materially improves the deliverable; do not start phone automation or unrelated tools.
2. **Phone task / commerce task:** invoke the phone execution path only when the goal requires an app or device action. Example: “order me a cup of milk tea” → open an isolated/virtual phone environment, inspect available merchants and prices, compare eligible options, prepare the selected order, and stop at the appropriate user-confirmation boundary for payment or irreversible submission. Do not invoke the expert team by default.
3. **Navigation / real-world handoff:** use the map/navigation app through the isolated phone environment to plan the route and prepare navigation. When the task needs to open or continue navigation on the user's foreground/main screen, explicitly request the required handoff/authorization, then hand the route to the real app. Do not silently take over the foreground screen.
4. **Research / multi-stage project:** use tools and/or expert work packages only when the goal needs external evidence, multiple specialties, dependent deliverables or cross-review.
5. **Multimodal task:** route to the configured specialist capability (image/audio/video) only when the input/output requires it; keep the main text model as the orchestrator.

Core efficiency rule: direct-answer requests must not pay for an unnecessary decision round, expert run, retrieval, or tool call. When a decision is needed, provider-native tool calling should be preferred; compatibility text protocol is fallback only for providers that do not support native calling. Measure actual token/network savings during unified acceptance; do not claim savings based only on design.

### Source-audit corrections on this branch

- Provider-native tool-call support for OpenAI-compatible Chat Completions, Anthropic Messages and Gemini is present in the current branch source (OpenAiClient.kt and ModelLedToolDispatcher.kt); older text in this document claiming these adapters are still wholly missing is stale. The complete round-trip, streaming, cancellation, permission and error behavior still requires unified verification.
- The expert project DAG executor, automatic expert selection/plan handoff, manual expert-center selection, collaboration event timeline and final main-model review instructions have code落点. The richer interactive workspace, bidirectional member chat, pause/resume/rework controls, durable event pagination and structured acceptance protocol remain gap candidates; do not describe the entire expert system as absent.
- The declarative generated-plugin factory, local store/lifecycle, plugin center UI and catalog registration have code落点. Signature/provenance, dependency lifecycle, sandbox/resource isolation, complete approval/audit semantics and distribution remain separate gap candidates; do not describe the plugin factory as only a prompt draft.
- Video generation has provider configuration, asynchronous WorkManager tasks, progress/cancellation/recovery, revision linkage and project asset import paths. Real provider interoperability and user-visible playback/revision flows require unified verification.
- Phone Agent now has a Mika-derived isolated-display execution adapter. It reads AccessibilityService windows filtered by the selected display ID, routes app launch/URL/back input through the shell helper, persists user-handoff task IDs, and restores the task to the isolated display after user confirmation. The Phone Agent page exposes wireless-debugging pairing, helper reconnect, virtual-screen creation, live frame preview, tap/swipe input, double-tap preview zoom, corner-drag resizing (up shrinks, down enlarges), and explicit main-screen restoration. Preview scale remains independent from virtual-display resolution; retired preview bitmaps are recycled after a short UI handoff delay. A stale session cannot detach a replacement session's active execution environment. The execution-mode preference is persisted so a lost virtual session pauses instead of silently falling back to the real screen. This is source-level implementation only; accessibility-window behavior and OEM/app compatibility remain unverified until the agreed acceptance phase.
- The current task dispatcher exposes selected registered capabilities and gates Phone Agent/expert tools by task-mode/team state. The complete scenario router must not hard-code “expert first” or “phone first”; it must select the correct route based on intent and actual capability availability.

### Work order — do not start acceptance early

A. Complete the post-module-19 additions and close feature gaps, prioritizing the scenario router and isolated virtual-screen/handoff path above, plus any missing lifecycle/UI integration found by source audit.

B. Perform source-level engineering cleanup and optimization after feature completion: remove dead/duplicate paths, reconcile stale documentation/status, reduce duplicated orchestration/state ownership, inspect large root files and error/cancellation paths, and improve responsiveness without changing established behavior.

C. Only after A and B are complete, begin unified acceptance: compile, JVM tests, lint, Android test sources/instrumentation and emulator flows.

D. After unified acceptance, perform phone/tablet/foldable/landscape/IME/safe-area adaptation and representative performance profiling; then real-device installation, permissions, background recovery, audio/phone automation and battery checks.

E. Build the distributable APP only after the preceding gates pass.

No build, test, lint, emulator or real-device acceptance is authorized during A/B. Do not merge, lock or publish a release until the agreed completion gate.

## 2026-11-22 — Six-item implementation sequence (authoritative)

The fixed source-work order is complete on `feature/phase2-optimization`. This means source integration, cleanup and static review are complete; it does **not** mean Gradle compilation or device acceptance has passed.

1. **Mika virtual-screen source study and targeted port — source complete.** Selectively ported ADB pairing/TLS, shell helper, Binder handoff, VirtualDisplay lifecycle, frame receiver, display geometry/orientation, task control, main-screen watcher and input injection. Compared the 18 retained AIDL methods against Mika's upstream 61-method interface: every retained method name has the exact upstream transaction ID. Mika Apache-2.0 attribution/license/NOTICE are retained; the scrcpy-derived shell-context portion has its own Apache-2.0 copy, NOTICE and modification statement. Helper identity is bound to shell UID, installed APK path, signing certificate, app UID/PID and durable one-use nonce.
2. **Audit Nestro source and exact integration points — source complete.** Reused existing Phone Agent task lifecycle, expert DAG, media generation/import, project asset store and native model tool adapters. Corrected the coordinator's Phone Agent callback type, the canonical `phone_agent.execute` result path, virtual-display AccessibilityService lookup, task handoff verification and stale-session teardown. The audit used branch source rather than old documentation as truth.
3. **Composable creative + phone execution — source complete within the declared platform boundary.** The main model is the only top-level intent router. Creative work, expert DAG execution, phone execution, task waiting and project-asset verification are composable; the dispatcher does not force expert-first or phone-first. It waits only when the Phone Agent result confirms the task was queued/running, verifies imported asset IDs through `media.project.assets`, then continues the creative workflow. Public HTTPS media direct links are downloaded through DownloadManager, size-limited, imported into the selected project and recorded with source/license provenance. Explicit limitation retained: arbitrary app-private files, authenticated links and automatic extraction from unrelated apps are not generalized; the model must surface this boundary rather than claim those files were imported.
4. **Virtual display, background execution, preview interaction and user handoff — source complete.** The controller and `MikaPhoneAgentExecutionEnvironment` are wired into the existing task system. Accessibility reads use `getWindowsOnAllDisplays()` for the selected display and preserve top-to-bottom visual layer order; shell operations launch apps, move tasks and inject input. Handoff task IDs persist before a task moves to the main display and are used to resume it on the isolated display. The Phone Agent page has a draggable floating preview/task capsule, fold/unfold by tapping its title bar, single-tap app input, double-tap zoom, upward-corner shrink and downward-corner enlarge; preview scale and frame aspect do not alter virtual-display resolution/DPI. Live-frame collection is isolated from the page's main recomposition path and retired bitmaps are recycled.
5. **Close remaining feature gaps and remove historical/dead code — source complete.** Fixed false-success and lifecycle paths found in the audit, removed obsolete preview-collapse state/imports, verified task moves/removals instead of trusting void Binder returns, recycled input events, cancelled interrupted gestures, made release cleanup resilient, and reconciled stale phase-two/plugin/Mika documentation. Existing completed modules were retained.
6. **Full source optimization and current-phase handoff — source complete.** Added helper reconnect backoff, duplicate-helper cleanup, force-stop/uninstall exit, stale-Binder recovery and synchronous nonce persistence; guarded session replacement and virtual-display teardown; isolated 10fps preview recomposition; reviewed cancellation, error, project-scope and license/NOTICE paths; added virtual-display geometry regression test source. All changed files and the branch head were re-read after commits.


### Current verification boundary

- Recent source commits are on feature/phase2-optimization; branch changes are source-level commits only.
- No Gradle build, JVM test, lint, Android instrumentation, emulator or real-device test has been run in this six-item phase.
- A committed test source is not a passing test. A source-level implementation is not an accepted device feature.
- After all six items are source-complete, the next stage is a full Nestro UI preview for user review (chat, expert team, project workbench, phone execution, virtual-screen capsule, media/assets, model/API settings, task calendar and settings). The user reviews and locks the UI direction before APK construction. Do not build the APK before that UI review.

