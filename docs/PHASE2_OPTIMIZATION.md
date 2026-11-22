# Phase 2 product and architecture optimization

Status: implementation in progress. This document records the agreed target; a checked item means code exists, not that build/device acceptance has passed.

## Cross-module improvements recovered from development

- [ ] AI co-development workbench: isolated execution sandbox, scoped permissions, patch preview, impact analysis, isolated build/test, explicit approval, snapshots and rollback.
- [x] Shared Agent capability foundation exists: `AgentToolCatalog`, task-scoped sessions/leases, `AgentRuntime`, permission routing and MCP registration reuse the process-wide catalog. Remaining: unify Skill/plugin catalog lifecycle and verify every invocation path goes through the same boundary.
- [ ] Project spaces: project and cross-project scope primitives exist; complete project-level ownership and lifecycle for files, experts, teams, Skills, workflows and artifacts, with explicit auditable cross-project access.
- [ ] Model routing: multi-provider/model/key registry, fallback and usage attribution exist; complete capability-aware selection, rate limits, budgets and cost-aware routing.
- [ ] Community ecosystem: signature/trust-root/revocation and SemVer dependency foundations exist; complete trusted catalog distribution, import/export/share, safe updates and rollback.

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

The chat request coordinator currently centralizes context assembly and streaming. The process-wide agent task coordinator and shared capability catalog already provide reusable scheduling/catalog foundations for Phone Agent, experts, workflows and MCP. Model registry/fallback/usage accounting, project-scope primitives, and community signature/dependency foundations also exist and must be reused rather than rebuilt. The chat path now has a model-led streaming decision protocol before final response: `DIRECT` streams user-facing text immediately; `TOOL` returns a typed JSON argument object. Tool decisions are restricted to the shared catalog, receive a task-local lease, pass through `AgentRuntime` and its permission router, and return bounded results for model synthesis. Existing granted MCP tools use their persisted per-tool grant check, and `knowledge.search` is registered as a project-scoped read-only capability. The decision request is streamed and cancellable; the tool loop is capped at three decision rounds. Remaining: provider-native tool-call protocol adapters (OpenAI/Anthropic/Gemini), user-confirmation continuation for non-MCP high-risk tools, richer schema-aware argument validation for every capability, and full project-scope enforcement for each future tool handler. Until those are complete, this is a functioning first model-led dispatch path, not the final native tool-call architecture. The current text-protocol decision pass is a separate model request before the normal answer/tool execution, so it adds a network round trip when the dispatcher is active; do not describe it as native tool calling or as the final low-latency design. Replace it with provider-native streaming tool-call adapters before claiming the one-model/one-decision request path is complete.

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
- `phone_agent.execute` is the single canonical model-visible Phone Agent capability and is exposed only in explicit task mode. The legacy `phone_agent.plan` registration is hidden from model discovery; both native and compatibility dispatcher paths route the canonical tool through the same task-planning/scheduling callback.
- `expert_team.run` is exposed only when the user explicitly selected an enabled team in task mode. Expert-team execution is deferred until the model selects that capability; it no longer runs automatically before the model decision.

## 专家团双模式与 AI 插件工厂（需求已确认，代码未完成）

详细约束见 docs/EXPERT_TEAM_AUTONOMOUS_PROJECTS_AND_PLUGIN_FACTORY.md。

已落地的第一步代码：ExpertTeamMemberTask 增加独立 assignedTask，ExpertTeamEngine.run() 会按成员各自任务执行；ExpertTeamExecutionCoordinator 增加 MANUAL_PARALLEL / SEQUENTIAL_DELEGATION 两种执行策略，测试源码覆盖成员任务隔离。当前 UI 尚未接入逐成员派单，聊天入口默认仍走原有链式协作，不能视为双模式产品功能完成。\n\n源码核查纠正：
- 当前聊天专家团入口调用 ExpertTeamExecutionCoordinator.execute()，其内部实际使用 ExpertTeamEngine.runCollaborative()，是单专家逐个委派的链式协作，不是项目级多专家并行任务 DAG。
- ExpertTeamEngine.run() 虽有受限并行执行能力，但尚未与主模型项目规划、依赖调度、持久化消息事件、用户可查看的协作工作区和最终主模型审查交付连成闭环。
- 当前仅有临时成员状态与运行历史，不等于用户可进入的实时协作工作区。
- CapabilityDraftCoordinator 只生成专家/Skill 的文字定义，不是可执行插件工厂；插件 manifest、受限运行时、沙箱测试、审批安装、版本生命周期和回滚均待实现。
- 上述内容加入本轮优化范围；不能标记为已完成。


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
- 新增 GeneratedPluginFactory：模型可依据当前已注册 AgentToolCatalog 生成声明式插件包；插件只编排已有工具，不生成任意语言代码或声明任意系统权限。
- 新增插件 manifest 数据结构、SemVer/工具白名单/模板引用校验、有界 codec，以及按顺序调用注入的统一 Runtime 回调的执行器。
- 新增 JVM 测试源码，覆盖前序步骤引用、未授权工具拒绝、运行时路由和包 codec；尚未执行。
- 尚未完成：产品 UI、草案/安装/启用持久化仓库、插件注册进全局能力目录、签名/来源、依赖锁定、隔离沙箱、权限审批、审计、升级/撤销/回滚。因此当前是受限插件生成与执行内核，不是完整插件商店。

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
- Phone Agent now has a Mika-derived isolated-display execution adapter. It reads AccessibilityService windows filtered by the selected display ID, routes app launch/URL/back input through the shell helper, persists user-handoff task IDs, and restores the task to the isolated display after user confirmation. The Phone Agent page now exposes wireless-debugging pairing, helper reconnect, virtual-screen creation, live frame preview, tap/swipe input, gesture-only preview resizing, collapse/expand and explicit main-screen restoration. The execution-mode preference is persisted so a lost virtual session pauses instead of silently falling back to the real screen. This is source-level implementation only; accessibility-window behavior and OEM/app compatibility remain unverified until the agreed acceptance phase.
- The current task dispatcher exposes selected registered capabilities and gates Phone Agent/expert tools by task-mode/team state. The complete scenario router must not hard-code “expert first” or “phone first”; it must select the correct route based on intent and actual capability availability.

### Work order — do not start acceptance early

A. Complete the post-module-19 additions and close feature gaps, prioritizing the scenario router and isolated virtual-screen/handoff path above, plus any missing lifecycle/UI integration found by source audit.

B. Perform source-level engineering cleanup and optimization after feature completion: remove dead/duplicate paths, reconcile stale documentation/status, reduce duplicated orchestration/state ownership, inspect large root files and error/cancellation paths, and improve responsiveness without changing established behavior.

C. Only after A and B are complete, begin unified acceptance: compile, JVM tests, lint, Android test sources/instrumentation and emulator flows.

D. After unified acceptance, perform phone/tablet/foldable/landscape/IME/safe-area adaptation and representative performance profiling; then real-device installation, permissions, background recovery, audio/phone automation and battery checks.

E. Build the distributable APP only after the preceding gates pass.

No build, test, lint, emulator or real-device acceptance is authorized during A/B. Do not merge, lock or publish a release until the agreed completion gate.

## 2026-11-22 — Six-item implementation sequence (authoritative)

The following order is fixed. Do not reorder, split into parallel acceptance work, or start build/test/lint/emulator/device validation before all six items are source-complete.

1. **Mika virtual-screen source study and targeted port.** Core ADB pairing/transport, shell helper process, Binder handoff, virtual-display lifecycle, frame receiver, display geometry/orientation and main-screen watcher code are present under app/src/main/java/app/nestro/phone/virtualdisplay/mika/. LICENSE-MIKA-APACHE-2.0.txt and NOTICE-MIKA.txt are present; adapted sources retain attribution/change notices. The helper runs under shell UID and is gated by wireless-debugging pairing, installed-APK certificate pin, app identity and one-use nonce. Remaining in this item: finish line-by-line protocol/lifecycle comparison and static correction of any remaining mismatches; do not claim compile/device success.
2. **Audit Nestro source and exact integration points.** Existing Phone Agent, expert DAG, media generation/import, project asset store and model-native tool adapters must be reused. The audit has identified and corrected the previously orphaned virtual-display controller integration and the missing canonical phone_agent.execute dispatch path. Continue auditing all call sites and error/cancellation/lifecycle paths against the actual branch source; stale docs are not evidence of missing code.
3. **Composable creative + phone execution.** phone_agent.execute is the canonical model-visible tool, gated by explicit task mode. General phone goals may be scheduled when the model deliberately selects the tool. Creative asset goals have a dedicated planner category and download/import steps. The Phone Agent model may emit download_asset for a public HTTPS media direct link; the task executor downloads it with Android DownloadManager, enforces a 512 MB task cap, and imports it into the selected project through MediaProjectStore.importAsset, preserving the requested display name. The main model remains the sole intent router; expert work and phone execution are selectable/composable branches rather than a fixed expert-first or phone-first pipeline. Remaining gap: direct HTTPS media links are supported; arbitrary files downloaded inside third-party app-private sandboxes, authenticated links and automatic collection from unrelated apps are not yet generalized.
4. **Virtual display, background execution, preview interaction and user handoff.** The controller is connected to Phone Agent through MikaPhoneAgentExecutionEnvironment; accessibility reads are scoped to the isolated display, while shell operations handle launch/navigation/back. Sensitive/login/verification handoffs move the task to the main display and persist the task ID; resume moves it back. The Phone Agent page exposes pairing/reconnect, create/close, live preview, tap/swipe, gesture resize and collapse/expand. Closing or losing the virtual session does not silently switch tasks to the user's main screen. Remaining: static source review of all edge cases and lifecycle transitions; real-device behavior is intentionally untested until all six items finish.
5. **Close remaining feature gaps and remove historical/dead code.** Not complete. Reconcile the actual source against the confirmed product requirements; remove only code proven dead or superseded, retain existing working modules, repair any broken integration, and update stale docs/status.
6. **Full source optimization and current-phase handoff.** Not complete. After item 5, perform cross-module cleanup, error/cancellation review, duplicated-state reduction, license/NOTICE review and documentation reconciliation. Commit and re-read the final source state before reporting the six-item phase complete.

### Current verification boundary

- Recent source commits are on feature/phase2-optimization; branch changes are source-level commits only.
- No Gradle build, JVM test, lint, Android instrumentation, emulator or real-device test has been run in this six-item phase.
- A committed test source is not a passing test. A source-level implementation is not an accepted device feature.
- After all six items are source-complete, the next stage is a full Nestro UI preview for user review (chat, expert team, project workbench, phone execution, virtual-screen capsule, media/assets, model/API settings, task calendar and settings). The user reviews and locks the UI direction before APK construction. Do not build the APK before that UI review.

