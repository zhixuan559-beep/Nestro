# Nestro implementation status and roadmap

This file separates implemented foundations from planned capabilities. A screen or placeholder is not considered a completed feature.

## Product-wide interaction model
One conversation is the command surface. The orchestrator should infer and coordinate experts, teams, skills, workflows, MCP tools, project context, memory retrieval and Phone Agent. Switching conversations must not destroy shared memory or interrupt background tasks. Project data remains isolated unless the user explicitly requests cross-project retrieval.

## Implemented foundations
- OpenAI-compatible chat endpoint and encrypted local API key.
- Basic local chat history, custom expert prompts and local skills.
- JSON backup/restore for the currently supported local preferences.
- CI debug APK build.
- Phone Agent accessibility-service declaration, explicit user-triggered screen text capture, temporary single/task grants, revocation, basic visible-text click primitive, high-risk keyword guard and local audit trail.

## In progress / incomplete
- Phone Agent natural-language planner, task orchestration, robust action verification, stop control, exception recovery, sensitive-content redaction and complete risk review.
- Multi-conversation persistence and cross-session search.
- Global memory extraction, indexing, editing and deletion.
- Project spaces and project-level data isolation.
- Expert lifecycle, expert teams and multi-agent coordination.
- Skills repository import, provenance, version/dependency checks and sandboxing.
- Workflow editor and durable task scheduler.
- Knowledge ingestion, document parsing, vector indexing and RAG.
- MCP local/remote transports and tool permission management.
- Model provider profiles, routing, failover, budgets and usage metering.
- Git provider integration and repository operations.
- AI code workspace with patch review, isolated build/test, approval and rollback.
- Works/artifact library and export.
- Capability catalog/community.
- Voice input/output, notifications and background task recovery.
- Database migration, complete backup/restore and device migration.
- Full automated tests and physical-device acceptance.

## Phone Agent authorization decision
- No forced per-step confirmation for ordinary low-risk actions.
- User may grant a single operation or continuous authorization for the current task.
- Temporary grants can be revoked and expire; they do not silently become permanent.
- The service does not inspect accessibility event content automatically. Screen reading is initiated by the user or an explicitly authorized task.
- Payment, transfer, credentials, verification codes and sensitive-data disclosure remain user-confirmed actions.
- The user can interrupt execution. High-risk actions must be handed back to the user.
- Accessibility APIs are not universal; use Android intents and supported app/system APIs where appropriate.

## Validation rule
A CI success means the APK compiled and packaged in GitHub Actions. It does not prove real-device behavior. Any feature depending on OEM accessibility behavior, background execution, networking or data migration needs physical-device testing.
