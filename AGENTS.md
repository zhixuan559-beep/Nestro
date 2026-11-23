# NESTRO Agent Mission & Execution Rules

This file is the mandatory execution contract for every AI coding session in this repository. Read it before inspecting or changing code. The only development and delivery branch is `main`.

## 1. Mission

Finish NESTRO by preserving and completing the existing implementation, not by rebuilding it. Reconcile all historical branch-only work into the current `main` where it is genuinely missing, then audit all 19 product modules, close verified gaps, perform a duplicate/dead-code review, and only then enter unified build and acceptance.

## 2. Non-negotiable rules

1. **One branch only:** all accepted code and documentation changes go directly to `main`. Never create a development branch, staging branch, integration branch, or parallel implementation.
2. **No duplicate development:** before adding a class, service, store, worker, provider, dispatcher, queue, UI page, or test, inspect the current implementation, callers, data flow, manifest/DI/serialization references, and history. Reuse or repair what exists.
3. **Historical branches are evidence, not merge targets:** never merge an entire historical branch or overwrite a newer `main` file with an old snapshot. Port only verified missing semantics, adapting them to the current architecture.
4. **Order is a hard gate:** historical branch reconciliation must finish before unrelated feature work or the 19-module re-audit. Do not skip ahead because a possible gap is interesting.
5. **No premature verification:** do not manually run Gradle, Kotlin/JVM tests, Android tests, emulator/device tests, APK builds, or manually dispatch GitHub Actions until all authorized code work, historical reconciliation, module audit, fixes, cleanup, and static read-back are complete. Automatic CI triggered by a push is not a task assignment; do not repeatedly inspect or dispatch it.
6. **No destructive cleanup early:** do not delete historical branches, source files, tests, or compatibility code until their unique value and all references have been checked. Branch deletion requires explicit user approval.
7. **Evidence over reports:** README, roadmap, audit tables, old chat summaries, and checklists are leads only. They never prove code exists or works. Current source, call sites, data flow, Git history, and actual test/build results are the evidence.
8. **No false completion:** distinguish source-present, entry-wired, lifecycle-complete, test-source-present, test-executed, build-passed, and device-accepted. Never promote one status into another.
9. **Keep the user in control:** do not silently expand scope, run extra checks, make unrelated fixes, or claim an action succeeded before GitHub confirms it.
10. **If interrupted, resume from reality:** reread current `main` HEAD, branch inventory, latest commits, and the latest reconciliation checkpoint. Never rely on remembered SHAs or stale progress.

## 3. Mandatory session start

Before any code change:

- Read this file, README, the current implementation roadmap, current code map, 19-module audit/list, and the latest branch reconciliation checkpoint.
- Read the live `main` ref and record its exact HEAD SHA.
- Read the complete live branch inventory and compare every non-main branch against `main`.
- Read recent `main` commits since the last recorded checkpoint; identify every change made since then and its affected files.
- Treat any mismatch between documents and live GitHub state as an audit finding. Correct the checkpoint; do not guess.

## 4. Historical branch reconciliation — first execution stage

The 2026-11-24 live inventory returned 38 branches including `main`; 20 non-main branches had commits ahead of `main`, while 17 non-main branches had no ahead commits. This is an inventory snapshot, not proof that any branch is reconciled. Refresh it at the start of work.

For **every branch with unique commits**:

1. Compare its head against the current `main`; record branch SHA, merge base, ahead/behind counts, and changed-file list.
2. Inspect every unique commit's message, parent, and changed files. Do not infer value from ahead count or filenames.
3. For every changed file, inspect the branch version and current `main` version. Follow relevant callers, interfaces, persistence/schema, DI/manifest, serialization, permissions, error handling, cancellation/recovery, and tests as applicable.
4. Assign exactly one disposition to each semantic change:
   - already present or superseded by a stronger `main` implementation — do not copy;
   - genuinely missing — adapt and add the smallest required change directly to `main`;
   - partially present — complete the existing implementation in place;
   - obsolete, unsafe, or duplicate architecture — document why it is excluded;
   - tests/docs/workflows — assess separately; do not treat them as production behavior.
5. Do not copy old files wholesale where that would regress newer behavior. Never reintroduce a second task queue, dispatcher, permission system, provider lifecycle, or parallel store for behavior already owned by the current architecture.
6. After handling a branch, reread the affected `main` files and callers, verify the resulting commit/ref, and write a branch-specific reconciliation record: branch SHA, unique commits reviewed, files reviewed, disposition, code commit (if any), and remaining uncertainty.
7. A branch is **reconciled** only when every unique commit and every affected file has a recorded disposition. A compare result with zero files or zero ahead commits is only “no file-level unique change identified”; it is not proof of functional equivalence.
8. Keep all historical branches intact during reconciliation. Do not delete any branch without explicit user approval.

Do not start unrelated module development until every unique historical branch is reconciled and the reconciliation ledger has been read back.

## 5. Unified 19-module audit — second stage

Use the fixed 19-module product scope. For each requirement, inspect actual source and trace the complete path:

- user-visible entry and navigation;
- model/provider capability and request construction;
- interfaces, dependency injection, tool registration and dispatch;
- task identity, idempotency, state transitions and concurrency;
- persistence, migrations, backup/restore and data ownership;
- project/user permission boundaries and approval;
- errors, timeout, retry/backoff and side-effect safety;
- pause, cancellation, process death and restart recovery;
- result/artifact handoff and final model/user-visible status;
- unit/instrumentation test source and coverage.

Record each item separately as: requirement confirmed, source present, entry connected, end-to-end path connected, persistence/recovery covered, test source present, test executed, build verified, device accepted. Mark unknown items as “待核实”; do not infer completion.

## 6. Targeted repair and consolidation — third stage

- Repair only gaps proven by the audit. Prefer wiring, adapting, or extending existing code over adding new abstractions.
- Keep every change on `main`; make focused commits with clear messages.
- Before a change, inspect current implementation and references. After a change, reread the full changed file and all affected call sites; check for duplicate symbols, stale paths, broken compatibility, and accidental deletions.
- Add or update focused regression-test source for each behavior change, but label it “not run” until actually executed at the authorized verification stage.
- Do not modify unrelated modules merely because they are nearby.

## 7. Dead-code and duplicate review — fourth stage

Only after branch reconciliation and module repairs:

- Search production references, reflection, manifest registrations, dependency injection, serialization names, database migrations, worker names, deep links, and compatibility readers before declaring code unused.
- Distinguish genuinely unreachable code from platform entry points and backward-compatibility code.
- Remove only code proven redundant and safe to remove; record the evidence and preserve required data migrations/backup compatibility.
- Re-read the resulting repository tree and all changed call sites.

## 8. Unified verification gate — fifth stage

The verification gate opens only after all of the following are recorded:

- all unique historical branches reconciled;
- all 19 modules audited against source and call paths;
- all verified functional gaps repaired or explicitly listed as accepted limitations;
- duplicate/dead-code review complete;
- documentation and code map updated;
- final static read-back and diff review complete.

At that point, stop and report the exact candidate commit and remaining risks to the user. Wait for the user's authorization before running Gradle/Kotlin compilation, JVM tests, Android instrumentation, emulator/device tests, or APK packaging. Do not manually dispatch workflows without authorization. When authorized, run the agreed verification sequence, fix failures on `main`, rerun the relevant checks, and report actual results separately.

## 9. Progress ledger and handoff

At every completed branch or meaningful stage, update the reconciliation ledger/checkpoint with:

- timestamp and exact `main` HEAD before/after;
- current branch inventory and remaining unique branches;
- branch/commit/file evidence reviewed;
- changes made and resulting commit SHAs;
- verified facts, unverified facts, blockers, and next exact action;
- verification actually run (or explicitly “not run”).

On a new conversation, the first action is to reread this file and the latest checkpoint, then verify live GitHub state. Continue from the first unfinished item; do not restart completed work or skip ahead.

## 10. Current checkpoint warning (2026-11-24)

The live `main` HEAD observed during this rule update was `ff7cb25db9d2258df76bda16ebe68289293f360d`. The live inventory contained 38 branches, including `main`. Compare against this HEAD found 20 non-main branches with ahead commits and 17 non-main branches without ahead commits. The existing reconciliation document was based on an older `main` SHA and must be revalidated against the live baseline before any of its “completed” dispositions are trusted. Do not claim that the 20 branches are currently reconciled merely because an older document says so.
