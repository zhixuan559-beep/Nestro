# 2026-11-22 Branch Merge Audit

## Scope and current baseline

- Repository: `zhixuan559-beep/Nestro`
- Integration target: `integration/19-modules`
- Baseline at audit time: `7449ef3211ecb2ebc2043abac956a5860274063b`
- Branch inventory: 28 branches returned by GitHub branch search.
- This is a functional merge audit, not the 19-module feature audit or acceptance report.
- No source branch was deleted. No build, JVM test, Android instrumentation test, emulator test, or device test was run; those remain deferred to the agreed unified verification stage.

## Changes committed directly to the integration branch

1. `39a42abd426c2662298f2213278f721d7f43afed` — Phone Agent execution boundary:
   - Mika VirtualDisplay is the only active Phone Agent execution environment.
   - Removed the AccessibilityService-as-real-screen fallback and the UI control that restored real-screen automation.
   - PhoneAgentService no longer implements or attaches itself as PhoneAgentExecutionEnvironment.
   - Kept AccessibilityService only as the virtual-display node inspection/targeting bridge used by MikaPhoneAgentExecutionEnvironment.
   - Scoped safe text undo and read-only snapshot helpers to a virtual display ID.
   - Preserved explicit user handoff to the real display; that is user takeover, not Agent fallback.

2. `97b3870dcc04febf5e59329c6354913ba82c2d17` — Updated README and project maps to describe the virtual-display-only execution boundary.

3. `7449ef3211ecb2ebc2043abac956a5860274063b` — Migrated the valid Module 5 policy from `wip/module-5-project-space`:
   - WRITE grants must also contain READ.
   - Added four focused policy regression tests.
   - Kept the newer integration implementation's atomic grant/audit persistence, project-deletion grant cleanup, and stricter audit validation; did not replace it with the older branch file.

## Branch dispositions

### Superseded by newer integration code; do not merge branch snapshots over integration

- `feature/module-6-knowledge-base` and `wip/module-6-finalize`: integration already contains the OCR/vector/embedding implementation and newer cross-module wiring, database/migration work, and tests. Representative file comparison showed current integration implementations are newer; no branch snapshot overwrite.
- `feature/module-7-skill-library`: integration has recursive backtracking SemVer dependency resolution, signer fingerprints, stricter HTTPS/path validation, independent trust-root/revocation handling, and atomic dependency-plan install/rollback. The old branch's install/update/uninstall/rollback dependency guards are functionally covered by the newer lifecycle store. A separate installed-state diagnostic API was not found in the current UI path; keep that behavior as a candidate for the later unified scan rather than copying the old resolver/UI wholesale.
- `feature/module-8-phone-agent`: changed helper/workflow/Gradle files were already present; its remaining Android workflow was older. Phone Agent source architecture is handled by the dedicated integration commit above.
- `feature/module-9-voice-system`: checked production speech files and VoiceSystemPage have matching blob SHAs between the feature branch and integration. No transplant.
- `feature/module-13-mcp-tool-center`: integration has serialized audit writes, damaged-audit quarantine, visible audit-persistence failures, and newer process-wide catalog wiring. The feature branch would regress those fixes.
- `fix/module-13-audit-reliability`: MCP core and regression test blobs match integration exactly.
- `feature/module-14-github-workbench`: integration has stronger credential rollback verification, synchronized repository indexing, path containment, quarantine-based deletion/recovery, and verified push-result handling.
- `feature/module-15-ai-coworkbench`: checked Coworkbench service and static analyzer files match integration; integration also contains the required route, SBOM scripts, and additional tests.
- `refactor/phase-2-architecture`, old staging branches, and feature branches for modules 5, 7-memory, 10, 11, 12, 17, and 18 are behind the integration baseline and have no ahead commits in the branch comparison. They are not merge sources.

### Preserve as candidates; do not merge wholesale

- PR #1, `feature/knowledge-retrieval-ranking`, remains open and Draft, based on `main`. Its knowledge/memory/Phone Agent snapshots are older than integration. Its `CapabilityRegistryStore`, `CapabilityTaskEngine`, and `CapabilityCenterPage` are not to be copied as a second capability/task subsystem.
- The current kernel already has `AgentToolCatalog`, `AgentRuntime`, `AgentTaskCoordinator`, tool/credential leases, and shared traces. The old task engine duplicates this base.
- PR #1 nevertheless contains semantics not yet represented fully in the current kernel: project-scoped capability metadata/grants/audit overview, business idempotency keys, unified retry/backoff, and complete task metadata. These must be mapped into the existing kernel in the later module-16 integration/repair pass; do not lose them or claim they are complete.
- `main` has three unreferenced additions: `UnifiedAgentDispatcher` (superseded by the model-led dispatcher), `ChatNetworkPolicy` (overlaps existing timeout/retry handling and is not wired), and `TtsCapability` (not wired into the current TTS controller/UI). Do not copy these as dead parallel implementations; reassess any unique behavior during the unified scan.

## Remaining gate before the full 19-module scan

The first integration stage is **not yet declared complete**. The outstanding merge decision is the PR #1 capability/task semantics listed above. Adapt only the necessary semantics to the existing AgentTaskCoordinator/AgentToolCatalog architecture, with no duplicate engine or stale UI transplant. Then re-read the integration branch and verify all branch dispositions. Only after that should the first full 19-module scan begin, followed by repair and the second full scan.

## Verification boundary

- GitHub commits and changed file blobs were read back after each commit.
- The latest integration ref at audit time was `7449ef3211ecb2ebc2043abac956a5860274063b`.
- Static source/call-site checks were performed for the Phone Agent environment boundary.
- Compilation and tests are intentionally not claimed as passed.
