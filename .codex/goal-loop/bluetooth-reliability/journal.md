# Bluetooth reliability implementation journal

## 2026-09-08 JST — iteration 1
- User approved all 14 issues (#9–#22). Started branch `codex/bluetooth-reliability` from main `38ab819`.
- Baseline evidence: prior direct reproductions confirmed scan/connect/subscribe lifecycle races, measurement cleanup bypass, ACC timer/startup races, filter/freshness defects, and recording ownership/anchor/recovery-policy defects.
- Plan: Node connection/capture ownership; shared filter/freshness; Android control/recording separation; diagnostics/device selection; integrated verification and delivery.
- No independent verifier is used: repository instructions require the implementing agent to own verification and prohibit external reviews.

## 2026-09-08 JST — iteration 2
- Implemented one-owner Node scan/connect/subscribe lifecycle with deadlines, cancellation, late-connect identity leases, bounded exponential retry, and centralized one-shot cleanup.
- Added measurement freshness so disconnected/silent periods expose null vitals and cannot create current-time chart or CSV rows. RR acceptance resets after long gaps and reacquires a stable shifted series after five beats.
- Split Android live control and serial PFTP work. Added cancellation-safe SDK ownership, ACC timer generations, stream callback generations, and ACC-only retry.
- Reworked recording recovery around explicit ownership, typed PFTP/storage failures, per-attempt anchors, exact-ID reconciliation, and durable quarantine for uncertain wall-clock anchors.
- Removed the fixed Android MAC. Added explicit device selection/persistence across UI/service/restart and exact Node `--device` selection.
- Added structured status/diagnostics and dashboard guidance. Node and Android retain only 64 sanitized lifecycle events.
- Found during review: the first Android device choice inherited a missing `acc` key as false. Corrected the migration default to ACC enabled.

## 2026-09-08 JST — iteration 3 (verification)
- Node: 20/20 fault-injection and contract tests passed with a 15 s runner timeout.
- Android: 78/78 JVM tests passed; `:app:testDebugUnitTest :app:assembleDebug --no-daemon` completed successfully. Debug APK produced.
- Web: esbuild completed, Capacitor assets copied, JavaScript syntax checks and `git diff --check` passed.
- Runtime: bounded `--simulate` session returned `connected=true`, `dataFresh=true`, stage `streaming`, JST `sampleAt`, and a two-event diagnostic export. Ctrl+C reached the shared `Shutting down...` cleanup.
- Chrome: dashboard rendered live status, metric cards, respiration, graph, and the connection-diagnostics control without visible overlap or clipping at the available desktop viewport.
- Process audit after tests/build/runtime: zero matching Node test/simulation, Gradle daemon, or javac survivors.
- Dependency audit executed. No dependency or lockfile changed. Existing lockfiles report 13 root and 4 app advisories; remediation is outside issues #9–#22 and remains a release risk.
- Hardware boundary: no H10, Android Bluetooth OFF/ON, bonding prompt, or two-physical-sensor test was run. Deterministic fakes cover lifecycle ownership and policies; on-device timing/frequency remains to be measured.

## Criterion result
- C1 PASS — Node lifecycle/measurement cleanup fault injection.
- C2 PASS — JS/Java filter recovery and stale-report tests.
- C3 PASS — ACC/startup/control-lane tests plus Android compile.
- C4 PASS — ownership, anchor, quarantine, persistence policy tests.
- C5 PASS — bounded/sanitized status contracts, API/runtime and rendered dashboard.
- C6 PASS — exact-match and persistence tests; all Android restart routes compile against one resolver.
- C7 PASS for the requested change — tests, builds, copy, syntax and diff checks pass; no dependency introduced. Existing audit findings recorded above.
- C8 PASS — issue mapping complete, process audit clean, project documentation and memory updated.
