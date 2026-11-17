# Nestro Architecture

## Purpose

This document is the shared architectural contract for the 19 product modules. It defines ownership, dependency direction, integration rules, and the staged refactor boundary. It does not rename, reorder, merge, or remove product modules.

The refactor is incremental: preserve working behavior, reuse existing implementations, and move responsibilities behind stable boundaries. File count and line count are not completion criteria.

## Runtime layers

```text
Compose presentation (screens, navigation, UI state)
                    |
                    v
Application services / use cases
                    |
                    v
Domain policies and contracts
                    ^
                    |
Data and Android platform adapters
(Room, encrypted vault, WorkManager, SpeechRecognizer,
AccessibilityService, notifications, SAF, network transport)
```

Dependency rules:

- Presentation may call application services and observe their state. It must not own encryption, raw HTTP transport, Room query policy, or task recovery rules.
- Application services coordinate use cases and may depend on domain contracts. They are the boundary for cross-feature operations.
- Domain policies are platform-independent where practical. They own validation, selection, routing, state transitions, and serialization rules.
- Data/platform adapters implement persistence, networking, Android lifecycle, permissions, and device operations.
- Feature modules must not reach into another feature's private UI or storage implementation. Cross-feature communication goes through an application service or a stable shared contract.
- The shared capability center (module 16) remains the stable integration surface. Existing consumers may be adapted to it; its contract and implementation are not to be redesigned in this phase.
- Secrets are never copied into model metadata, logs, backup payloads, UI state snapshots, or diagnostic strings. Secret reads/writes go through the encrypted credential boundary.
- Long-running or device-affecting agent work is coordinated through the shared AgentTaskCoordinator. A feature must not create a parallel task lifecycle or independently replay device actions.

## Shared foundations

| Foundation | Current implementation to reuse | Ownership boundary |
|---|---|---|
| Model catalog and credentials | `ModelRegistry`, `ModelRegistryStore`, `ModelConfigurationRepository`, `ModelConnectionService`, `ModelRequestConfigurationSelector`, `LocalVault` | Provider/model/key metadata, encrypted secrets, model connectivity checks, active model, selectable-model and failover policy |
| Chat execution and transport | `ChatExecutionService`, `OpenAiClient`, `ChatHttpTransport`, `ChatEndpointPolicy`, `ChatRequestPolicy`, `OpenAiSseDecoder` | Application service owns request execution/cancellation; transport and policy own HTTP, streaming decode, retry and context limits |
| Agent task lifecycle | `AgentTaskCoordinator`, `PhoneAgentTaskService`, state/trace stores, `AgentTaskHandlerRegistry` | Idempotency, queueing, pause/resume/cancel, checkpoints, recovery and trace |
| Local persistence | `NestroDatabase`, Room stores, preference stores | Durable app data and migrations |
| Backup | `LocalBackupRepository`, `LocalBackupCompatibility` | Versioned export/import, integrity and compatibility; secrets excluded |
| Knowledge and memory | `KnowledgeStore`, `KnowledgeSearchEngine`, `ConversationMemoryStore`, `UserMemoryStore` | Project-scoped retrieval and memory lifecycle |
| Speech | `SpeechProviders`, `VoiceInputButton` and Android speech adapter | Input, output and real-time voice provider contracts; UI must not own provider credentials |
| Artifact and community | `ArtifactStore`, `SkillCommunityClient`, parsers and catalog rules | Versioned artifacts, trust validation, permission preview and install policy |

## Product module ownership (fixed order)

1. **Expert system** — expert execution, team orchestration, expert profile and revision data. Reuse `ExpertTeamEngine` and the shared task coordinator; do not duplicate the chat transport.
2. **Model management** — provider, API key and model lifecycle; encrypted credential storage; model availability and failover. A deleted or invalid credential must make every model that depends on it unselectable.
3. **Chat system** — conversation lifecycle, streaming, history, revisions and request cancellation. It consumes the shared model-selection and chat-transport contracts.
4. **Data and backup** — Room persistence, local preferences, migrations, backup/restore and compatibility. Secrets remain outside exported backups.
5. **Project space** — project lifecycle and project-scoped ownership of conversations, knowledge, memories, artifacts and agent tasks.
6. **Knowledge base** — import, chunking, indexing, retrieval and document lifecycle, scoped to a project.
7. **My memory** — explicit and automatic user memory, review/edit/delete, retrieval and provenance. It is distinct from source conversation history.
8. **Phone Agent** — task planning, permission checks, accessibility execution, user handoff and device-action audit. It uses the shared coordinator and must never silently replay an uncertain action.
9. **Voice system** — press-to-talk input, transcript feedback, speech output and optional full-duplex voice sessions. Voice conversation is an in-app AI interaction, not cellular calling. Providers and API keys are user-configurable; no provider secret is bundled.
10. **My works** — generated artifacts and version history, project-scoped, with preview/share/export.
11. **Expert templates** — template catalog, validation, import/export and installation. Keep its existing behavior and verify it before module 12 integration.
12. **Dual-model comparison** — parallel model requests and side-by-side results. It starts only after module 11 passes regression verification and uses the same model/credential boundary as chat.
13. **MCP tool center** — tool discovery, configuration, permission boundaries, invocation and audit. Tool calls must not bypass application task policies.
14. **GitHub workspace** — repository, branch, file and PR workflows through explicit GitHub integration boundaries.
15. **AI co-building workspace** — project planning and multi-step creation workflows, reusing experts, artifacts, tools and shared task orchestration.
16. **Unified capability center** — locked shared capability contract. Adapt callers to it; do not redesign or reimplement it.
17. **Smart editing** — media editing workflows and job state; expensive/long-running jobs use the shared task lifecycle.
18. **Calendar** — calendar data, scheduling and reminders, with explicit user permissions and durable scheduling.
19. **Multimodal services and community ecosystem** — optional user-configured media providers, trusted catalogs, signed bundles and community distribution. Network trust, signature validation and permission preview are mandatory before installation.

## Current implementation reality and refactor focus

The repository already contains reusable domain/data components for model registry, chat policy/transport, Room persistence, project rules, knowledge, memory, agent coordination, speech contracts, artifacts, expert templates and community catalog validation.

The process composition root (`NestroApplication`) now owns the database, model repository, shared agent coordinator and Phone Agent task service. Model request selection/failover is a pure model-layer policy; Phone Agent persistence, scheduling and cancellation are routed through its task service. The Compose root (`NestroApp.kt`) still owns too much cross-feature state and orchestration. The target is not to split it mechanically into many files: move cohesive responsibilities behind application/domain boundaries, then wire the feature screens to those boundaries. Shared state must have one owner; duplicate registries, preference keys, retry loops and task queues are not acceptable.

The model UI currently preserves the legacy profile workflow while the registry supports provider/key/model entities. Keep legacy data migration compatible while progressively routing all model operations through the repository boundary. Do not let the legacy UI become a second source of truth.

Phone Agent currently combines a Room task record, shared agent state/trace, WorkManager scheduling and an accessibility executor. Treat these as one lifecycle: enqueue, run, checkpoint, wait-for-user, pause, resume, cancel, fail and process-restart recovery must agree across all stores. A WorkManager retry must not repeat a non-idempotent device action.

Speech provider interfaces are contracts, not proof that output speech or real-time voice is already implemented. The user-facing voice system is complete only when input, output, real-time session lifecycle, provider/key editing and failure handling are implemented and integrated.

## Verification gates

- During architecture refactoring: run Kotlin compilation, JVM unit tests and Android lint on pull requests. These checks must not assemble an installable APK.
- Do not perform device acceptance or produce the final installable APK while feature implementation is still in progress.
- After all 19 modules are integrated: perform the unified Android build, installable artifact generation, real-device acceptance, background/permission/vendor-power checks and regression testing.
- The pull request remains Draft and unmerged until the user explicitly authorizes a state change.
- Eta is GPL-3.0: use it only for architectural reference unless a separate license-compatibility review explicitly authorizes code reuse.
