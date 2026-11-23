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
   - Generated-plugin execution now applies the shared retryable-error classifier and bounded backoff policy, persists RETRY_WAIT/attempt/availableAt state, and traces scheduled retries. Whole-workflow replay is disabled as soon as any non-read-only step starts, because a timeout after a write/device action can have an unknown outcome. Cancellation during retry backoff is persisted as PAUSED rather than leaving a stale RETRY_WAIT state.
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

This closes the PR #1 semantic-mapping gate only. It does **not** mean the full 19-module scan, source cleanup, UI design, or app acceptance is complete. The all-branch comparison, PR #1 selective semantic integration, and consolidated read-back record are complete for this first integration gate. The next authorized stage is the first full 19-module scan.

## Verification boundary

- GitHub commit refs, changed-file blobs, task/permission call sites, PR #1 patches, and the latest integration branch HEAD were read back.
- The branch comparison against the original integration baseline `7449ef3211ecb2ebc2043abac956a5860274063b` reports 63 ahead / 0 behind. Latest code commit read-back before this audit-document update: `dadd88eacb9a2daa78478f9abaf7f9af103ba446`.
- All 28 non-integration branches were compared against the integration target. The two newly observed branches (`feature/modelscope-oauth` and `feature/phase2-optimization`) are behind with no ahead commits. Previously identified diverged branches remain subject to the file-level dispositions above; no source branch was deleted.
- No GitHub Actions workflow run or check run exists for the current integration HEAD.
- A local repository clone/build could not be started because the execution environment could not resolve `github.com`; compilation, JVM tests, Android instrumentation tests, emulator tests, and device tests remain **not run**.
- No branch was deleted and PR #1 remains open + Draft; it was not merged wholesale.


## 2026-11-22 16:58 UTC — 全分支图谱刷新（源码基线 b394e60）

- 重新读取 GitHub 分支列表：共 30 个分支（含 `integration/19-modules`），逐个将其与整合分支进行 compare。
- 29 个非整合分支的提交图比较结果：14 个 diverged、15 个 behind、0 个 ahead-only。ahead/behind 是相对共同祖先的提交图差异，不等于有多少功能未合并；尤其 diverged 分支必须继续比较具体文件 blob/语义，禁止整分支覆盖。
- 已确认完全 behind、没有独有提交的分支包括：`feature/modelscope-oauth`、`feature/module-5-project-space`、`feature/module-7-memory`、`feature/module-10-artifacts`、`feature/module-11-expert-templates`、`feature/module-12-model-comparison`、`feature/module-17-smart-editing`、`feature/module-18-calendar`、`feature/phase2-optimization`、`feature/voice-system`、`refactor/phase-2-architecture` 和四个 phase2 staging 分支。
- Diverged 候选分支包括 PR #1 的知识检索/能力中心旧快照、模块6/7/8/9/13/14/15 历史分支、模块13审计修复分支、模块5/6 WIP 分支、旧 main 和临时写权限测试分支。此前专项审计已确认：PR #1 仅选择性回收独立语义，不合入旧 CapabilityTaskEngine；模块6/7/13/14/15 整合分支有较新的修复；模块8差异主要是 CI/脚本；旧 main 的 UnifiedAgentDispatcher/ChatNetworkPolicy/TtsCapability 不应复制回来。仍需对本轮列出的候选文件做 blob/语义复核，不能仅凭 compare 状态下最终结论。
- 当前仍开放的 Draft PR 只有：PR #1 `feature/knowledge-retrieval-ranking → main`；PR #3 `refactor/phase-2-architecture → feature/voice-system`。两者都不是 integration 目标分支的待合并 PR，PR #3 所在 refactor 分支当前相对 integration behind 0 ahead。
- 本节只完成分支图谱和 PR 元数据刷新，不代表全部分支文件差异已逐文件核验，也不代表所有功能都已合并/验收。


## 2026-11-22 17:18 UTC — main 分支旧代码状态复核纠正

上一版本报告写明 main 的 `ChatNetworkPolicy` 与 `TtsCapability` 未接入当前路径；该判断已被当前整合分支源码推翻，属于过期记录：
- 当前 integration 有独立版本 `app/src/main/java/app/nestro/data/ChatNetworkPolicy.kt`（与 main blob 不同），`OpenAiClient.streamWithFallback` 已调用 `timeoutPolicy()` 和 `retryPlan()`，并有当前测试 `ChatNetworkPolicyTest.kt`；不能再标为未接线。
- `TtsCapability.kt` 与 main blob 一致，当前 `AndroidTextToSpeechController` 已调用 `TtsCapability.chineseVoices/isLocaleAvailable`；不能再标为未接线。
- `UnifiedAgentDispatcher.kt` 当前 integration 不存在；顶层调度由 `ModelLedToolDispatcher` 承担，不能把 main 的旧 dispatcher 复制回来。
- 这进一步说明分支审计必须以当前 integration 源码调用链为准，旧审计文档也必须随源码复核修订。


## 2026-11-24 main 主线冗余代码复核补记

- 以当前 `main` 源码全仓搜索 `UnifiedAgentDispatcher`，生产代码没有调用点；仅命中该类自身、对应单元测试及历史审计文档。其关键词计数路由已被 `ModelLedToolDispatcher` 主模型决策与工具目录取代，且没有可复用的独有执行能力。
- 已从 `main` 删除 `app/src/main/java/app/nestro/agent/UnifiedAgentDispatcher.kt` 与仅覆盖该死代码的 `app/src/test/java/app/nestro/agent/UnifiedAgentDispatcherTest.kt`。删除提交：`7124010867e79028159a1cf07c26fd55e95f6f4d`、`60b64468062f54dec19e77c54acf2d1645a66435`。
- 同轮复核确认 `ChatNetworkPolicy` 被 `ChatExecutionService` / `OpenAiClient` 使用，`TtsCapability` 被 `AndroidTextToSpeechController` 使用；两者不是死代码，保留。
- 本次只清理已证明无生产引用的旧路由，不涉及历史分支删除，不运行编译或测试。
