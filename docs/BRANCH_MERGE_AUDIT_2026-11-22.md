# 2026-11-22 Branch Merge Audit

## Scope and current baseline

- Repository: `zhixuan559-beep/Nestro`
- Integration target: `integration/19-modules`
- Baseline at audit time: `7449ef3211ecb2ebc2043abac956a5860274063b`
- Branch inventory: 29 branches returned by GitHub branch listing and compared against the integration target.
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
- PR #1's unique semantics have now been selectively mapped into the integration branch (see the completion record below). The old task engine remains excluded because the existing AgentTaskCoordinator now owns idempotency, retry/backoff, durable metadata, recovery and traces. The old CapabilityCenterPage UI remains deferred to the agreed UI phase; its status/detail/route metadata fields are preserved in the current capability descriptor.
- `main` has three unreferenced additions: `UnifiedAgentDispatcher` (superseded by the model-led dispatcher), `ChatNetworkPolicy` (overlaps existing timeout/retry handling and is not wired), and `TtsCapability` (not wired into the current TTS controller/UI). Do not copy these as dead parallel implementations; reassess any unique behavior during the unified scan.

## PR #1 selective integration record (2026-11-22)

The PR was not merged wholesale. Its independent behaviors were mapped into the existing architecture:

1. **Project-scoped capability registry and authorization**
   - Added `CapabilityRegistryStore` to the integration branch with validated capability descriptors, status/detail/navigation metadata, per-project overrides, fail-closed reads, and atomic permission-change/audit persistence.
   - Wired project capability authorization into model-led chat tool dispatch and generated-plugin tool execution.
   - Added MCP service/tool project-level overrides while retaining the existing global MCP grant as an upper bound. A project grant cannot revive a globally revoked MCP tool.
   - Unregistering a capability removes project grants and records revocation; MCP audit parameter names are filtered to avoid storing common secret fields.
   - Added Android instrumentation coverage for grants, revocation, fail-closed behavior, metadata round-trip, and MCP overrides.
   - A second authorization path in the model-led text-protocol fallback was found during call-site review and brought under the same project capability gate; both native and compatibility execution paths now enforce it.
   - Propagated the chat request identity to tool execution and derived generated-plugin idempotency keys from project, plugin, request identity, and a SHA-256 fingerprint of declared inputs. Duplicate plugin requests return the canonical persisted task instead of executing again; successful output is saved to the shared task result summary. Existing canonical task states are surfaced according to their actual status, so a prior failure is not misreported as a new success.
   - Generated-plugin execution now applies the shared retryable-error classifier and bounded backoff policy, persists RETRY_WAIT/attempt/availableAt state, and traces scheduled retries. Whole-workflow replay is disabled as soon as any non-read-only step starts, because a timeout after a write/device action can have an unknown outcome.
   - The legacy CapabilityCenterPage UI was not copied. Project-level capability/MCP grant switches and audit visibility were integrated into the existing CapabilityDirectoryPage; descriptor status/detail/route metadata remains available for later visual refinement.

2. **Unified task metadata, idempotency, and retry**
   - Extended the existing AgentTaskState/Room persistence with project, conversation, task kind, capability, title/input/result summary, idempotency key, attempts, retry limit, availability time, and lifecycle timestamps.
   - Added a unique Room idempotency index and checked/transactional insert paths; in-memory and Room stores reject collisions instead of replacing another task.
   - The existing AgentTaskCoordinator now applies stable idempotency checks, retryable-error classification, bounded exponential backoff, restart recovery, and trace events.
   - Added project/workflow business keys to expert-team and workflow inline tasks, and persisted chat tool-dispatch metadata/idempotency.
   - Extended local backup/restore and round-trip tests to preserve and validate task metadata and idempotency.
   - Did not import PR #1's CapabilityTaskEngine: it would create a second queue/state machine and its transition/retry logic is weaker than the unified kernel.

3. **Memory behavior**
   - Integrated the PR's BM25/CJK n-gram recall ranker into the existing UserMemoryStore, preserving semantic-vector recall and keyword fallback.
   - Ported explicit memory supersession into Room with owner-project/category checks and fingerprint-conflict protection; an invalid explicit correction cannot silently become a new memory.
   - Added ranking, deduplication, project isolation, and correction regression tests.

4. **Knowledge and Phone Agent snapshots**
   - Kept the integration branch's newer knowledge ingestion/retrieval stack (project-scoped BM25/FTS + vector ranking, chunking, encoding handling, PDF extraction/OCR entry point and existing embedding pipeline); no older PR snapshot was copied over it.
   - Kept the integration branch's virtual-display-only Phone Agent architecture. PR #1's older AccessibilityService/WorkManager implementation was not transplanted.
   - Did not copy PR #1's workflow change: it switches to `macos-26-intel`, narrows the instrumentation command to only the migration test, and changes the emulator boot timeout from 600000 to 600. These changes are not a safe substitute for the full validation workflow.

## Integration gate status

The PR #1 functional merge gate is **selectively integrated** on `integration/19-modules`. Capability authorization, MCP project grants/audit, memory correction/ranking, task metadata, idempotency, and retry/backoff semantics have been mapped into the current shared architecture. The old duplicate task engine and stale UI snapshot remain excluded.

This closes the PR #1 semantic-mapping gate only. It does **not** mean the full 19-module scan, source cleanup, UI design, or app acceptance is complete. The next authorized stage is to finish the all-branch functional merge inventory and consolidated read-back verification; only then begin the first full 19-module scan.

## Verification boundary

- GitHub commit refs, changed-file blobs, task/permission call sites, PR #1 patches, and the latest integration branch HEAD were read back.
- The branch comparison against the original integration baseline `7449ef3211ecb2ebc2043abac956a5860274063b` reports 62 ahead / 0 behind. Latest branch HEAD read-back: `cefc40a02e040800c840f8c295e0238dba2efab1`.
- All 28 non-integration branches were compared against the integration target. The two branches not listed in the initial 28-branch audit (`feature/modelscope-oauth` and `feature/phase2-optimization`) are behind with no ahead commits. Previously identified diverged branches remain subject to the file-level dispositions above; no source branch was deleted.
- No GitHub Actions workflow run or check run exists for the current integration HEAD.
- A local repository clone/build could not be started because the execution environment could not resolve `github.com`; compilation, JVM tests, Android instrumentation tests, emulator tests, and device tests remain **not run**.
- No branch was deleted and PR #1 remains open + Draft; it was not merged wholesale.
