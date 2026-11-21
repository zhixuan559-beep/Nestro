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
- [x] Independent optional context retrievals (conversation recall, knowledge retrieval and user memory) run concurrently; unused retrieval sources are not started.
- [x] Optional phone-task planning overlaps context assembly instead of serially extending the pre-stream critical path.
- [ ] Complete responsive layouts for compact phones, landscape, tablets and foldables; verify keyboard/IME insets, safe areas, font scaling, navigation and split-screen.
- [ ] Profile cold start, first-frame time, recomposition, memory, CPU, storage, network wakeups and battery on representative low/mid/high-tier devices.
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

The chat request coordinator currently centralizes context assembly and streaming. The process-wide agent task coordinator and shared capability catalog already provide reusable scheduling/catalog foundations for Phone Agent, experts, workflows and MCP. Model registry/fallback/usage accounting, project-scope primitives, and community signature/dependency foundations also exist and must be reused rather than rebuilt. The present chat path still has a legacy task-mode gate and keyword-based Phone Agent category planner; it is not yet the complete model-native tool-decision loop described above. Native model tool-call parsing, provider protocol adaptation, dispatcher handoff, bounded multi-step tool rounds and end-to-end cancellation/result reinjection must be implemented before this principle can be marked complete.

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
