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

The chat request coordinator currently centralizes context assembly and streaming. The process-wide agent task coordinator and shared capability catalog already provide reusable scheduling/catalog foundations for Phone Agent, experts, workflows and MCP. Model registry/fallback/usage accounting, project-scope primitives, and community signature/dependency foundations also exist and must be reused rather than rebuilt. The chat path now has a model-led streaming decision protocol before final response: `DIRECT` streams user-facing text immediately; `TOOL` returns a typed JSON argument object. Tool decisions are restricted to the shared catalog, receive a task-local lease, pass through `AgentRuntime` and its permission router, and return bounded results for model synthesis. Existing granted MCP tools use their persisted per-tool grant check, and `knowledge.search` is registered as a project-scoped read-only capability. The decision request is streamed and cancellable; the tool loop is capped at three decision rounds. Remaining: provider-native tool-call protocol adapters (OpenAI/Anthropic/Gemini), user-confirmation continuation for non-MCP high-risk tools, richer schema-aware argument validation for every capability, and full project-scope enforcement for each future tool handler. Until those are complete, this is a functioning first model-led dispatch path, not the final native tool-call architecture.

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
- `phone_agent.plan` is exposed only when the user explicitly enabled task mode; its planning/scheduling callback is no longer run before the model decision. The model must select this capability before a Phone Agent task can be created.
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
