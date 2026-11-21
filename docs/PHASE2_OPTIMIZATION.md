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

源码核查纠正：
- 当前聊天专家团入口调用 ExpertTeamExecutionCoordinator.execute()，其内部实际使用 ExpertTeamEngine.runCollaborative()，是单专家逐个委派的链式协作，不是项目级多专家并行任务 DAG。
- ExpertTeamEngine.run() 虽有受限并行执行能力，但尚未与主模型项目规划、依赖调度、持久化消息事件、用户可查看的协作工作区和最终主模型审查交付连成闭环。
- 当前仅有临时成员状态与运行历史，不等于用户可进入的实时协作工作区。
- CapabilityDraftCoordinator 只生成专家/Skill 的文字定义，不是可执行插件工厂；插件 manifest、受限运行时、沙箱测试、审批安装、版本生命周期和回滚均待实现。
- 上述内容加入本轮优化范围；不能标记为已完成。
