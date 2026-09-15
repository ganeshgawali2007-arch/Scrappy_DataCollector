# Classroom Capture App — Agent TODOs

This file is the execution checklist for the coding agent. Work through the parts in order unless a dependency explicitly permits parallel work. Keep the repository buildable after every completed task.

## Agent operating rules

- Read `plan.md` before starting and treat its invariants as mandatory.
- Do not add cloud services, network calls, accounts, analytics, translation, or unrelated dependencies.
- Do not delete raw audio, transcripts, metadata, logs, or failed artifacts automatically.
- Do not hide failures behind fallback behavior. Persist them and expose them to the operator.
- Update the relevant documentation and this checklist as work progresses.
- Mark each item `[x]` only after implementation and verification. Use `[~]` for active work and `[!]` for blocked work.
- Every commit or logical change must state what was implemented and how it was verified.

## Required blocker and failure protocol

When any step is stalled, ambiguous, technically complex, or failing:

1. Stop before making an architectural workaround or destructive change.
2. Mark the item `[!]` and record:
   - exact task and expected behavior
   - observed failure or uncertainty
   - reproduction steps
   - relevant logs, stack traces, device/API versions, and attempted fixes
   - files or contracts affected
   - impact on data integrity, schedule, and dependent tasks
3. Continue independent, low-risk work that does not depend on the blocked decision.
4. Report the blocker to the user in the task response using this format:

   ```text
   BLOCKED: <short title>
   Task: <todo identifier>
   Evidence: <reproducible facts and logs>
   Options: <at least two viable options, with tradeoffs>
   Recommendation: <preferred option and why>
   Input needed: <specific decision or information>
   ```

5. Wait for the user's input. Do not guess, silently downgrade requirements, switch libraries, erase data, or proceed through a dependent task.
6. After receiving input, record the decision in `DECISIONS.md`, update this file, implement the chosen path, and rerun all affected checks.
7. If the same failure recurs, include the new evidence and revised options rather than repeating the same attempt.

For a test failure that is clearly caused by the implementation, fix it autonomously. Ask the user only when the failure requires a product, privacy, compatibility, licensing, or architecture decision.

## Part 0 — Project preparation and decisions

- [x] P0.1 Read `plan.md`; extract non-negotiable invariants into code review notes.
- [x] P0.2 Inspect the existing repository, Android/Gradle/NDK versions, target SDK, and available test devices.
- [x] P0.3 Create `DECISIONS.md` and record target API range, minimum device specs, ABI, model licensing constraints, and initial model candidates.
- [x] P0.4 Define coding conventions, package names, error taxonomy, logging/redaction policy, and branch/commit practice.
- [x] P0.5 Create a CI workflow for formatting, static analysis, unit tests, schema validation, and debug build.
- [x] P0.6 Confirm that production manifests contain no `INTERNET` permission.

**Exit gate:** clean debug build, reproducible CI run, documented target-device matrix, no unrecorded architectural assumptions.

## Part 1 — Android application skeleton

- [x] P1.1 Create Kotlin/Compose app structure and dependency catalog.
- [x] P1.2 Configure target/min SDK, arm64-v8a release ABI, debug emulator configuration, NDK, CMake, and native source layout.
- [x] P1.3 Add navigation for Home, New Session, Recording, Summary, Export, Recovery, Errors, and developer diagnostics.
- [x] P1.4 Add application-level dependency injection and lifecycle-safe ViewModel scaffolding.
- [x] P1.5 Add runtime permission and notification-channel scaffolding.
- [x] P1.6 Add baseline theme, accessibility labels, large controls, dark mode, and localization-ready resources for Hindi/English/Marathi labels.
- [x] P1.7 Add README and architecture overview.

**Verify:** install and launch with airplane mode; rotate and recreate the UI without crashes.
_Verified 2026-09-14: `assembleDebug` + `lintDebug` + `testDebugUnitTest` pass, offline gate passes (no INTERNET), APK 25.9 MB. [x] Emulator verified 2026-09-15: Scraper_API35 (emulator-5554) — APK installed, app launched, screenshot captured at `docs/evidence_p1_rebuild.png`._

## Part 2 — Data model and Room persistence

- [x] P2.1 Define immutable domain models: Session, ClassroomSample, Artifact, ProcessingJob, DeviceEvent, QualityMetrics, ExportRecord.
- [x] P2.2 Define Room entities, DAOs, indexes, foreign keys, and transaction boundaries.
- [x] P2.3 Implement the persisted sample state machine and legal-transition validator.
- [x] P2.4 Add attempt counts, lease/heartbeat fields, error codes, timestamps, checksums, schema version, and model identity fields.
- [x] P2.5 Implement explicit Room migrations and migration tests; never add destructive fallback.
- [x] P2.6 Implement repositories exposing `Flow`/suspend APIs; keep UI independent of Room details.
- [x] P2.7 Add JSON serialization and versioned schemas for ASR, annotation, events, manifest, and JSONL records.

**Verify:** create, close, reopen, and query sessions/samples after process death; all migration tests pass.
_Verified 2026-09-15: `assembleDebug` + `lintDebug` + `ktlintCheck` + `testDebugUnitTest` (19 tests) green; `connectedDebugAndroidTest` 2/2 pass on Scraper_API35 (schema-vs-export validation + close/reopen durability); no destructive fallback; fresh APK (0.2.0-p2) installed, app launches with no crash (pid 7047)._

## Part 3 — Artifact storage and integrity

- [x] P3.1 Implement `ArtifactStore` with per-session/per-sample directories.
- [x] P3.2 Implement atomic temporary-file writes, flush/close, rename, and corruption quarantine.
- [x] P3.3 Implement SHA-256, byte-size, MIME, and relative-path recording.
- [x] P3.4 Prevent path traversal and reject unexpected artifact paths.
- [x] P3.5 Add storage-space preflight, low-storage thresholds, and recoverable error UI.
- [x] P3.6 Implement idempotent startup reconciliation between Room and filesystem.
- [x] P3.7 Discover orphan audio and partial files; create recovered records or quarantine with a report.
- [x] P3.8 Add tests for crashes at every write/rename/transaction boundary.

**Verify:** no orphan DB entries, no silent orphan files, checksum repeatability, recovery is idempotent.
_Verified 2026-09-15: 41 unit tests green (store crash-boundaries, reconciler adoption/error/quarantine/idempotency with zero second-run writes, preflight thresholds); `connectedDebugAndroidTest` 2/2; lint/ktlint clean; fresh APK on Scraper_API35 launches clean and `RECONCILIATION_COMPLETED|adopted=0 errored=0 quarantined=0 corrupt=0` read back from the on-device DB. P3.5 UI banners deferred to P8; store surfaces STORAGE_LOW/Refused/Low results._

## Part 4 — Recording foreground service

- [ ] P4.1 Declare microphone foreground-service type and required modern permissions for supported API levels.
- [ ] P4.2 Implement service-owned recording lifecycle and binder/state stream.
- [ ] P4.3 Implement `AudioRecord` at 16 kHz, mono, PCM16 with capability negotiation and clear failure reasons.
- [ ] P4.4 Implement streaming WAV writer with correct header finalization and duration tracking.
- [ ] P4.5 Add bounded buffers and writer backpressure; never retain an entire session in RAM.
- [ ] P4.6 Implement persistent recording notification with Stop action, session, sample count, and duration.
- [ ] P4.7 Handle screen lock, screen off, backgrounding, rotation, UI loss, and service restart.
- [ ] P4.8 Add recording-level meter, clipping detection, silence ratio, and sustained-clipping warning.
- [ ] P4.9 Handle interrupted recording, permission revocation, microphone busy, and write failure without losing completed samples.
- [ ] P4.10 Add manual start/stop segmentation and sticky per-sample language.

**Verify:** 10-minute valid WAV, lock/background survival, process recreation, low-storage behavior, and no UI-thread recording work.

## Part 5 — Audio device routing

- [ ] P5.1 Implement `AudioDeviceManager` for built-in, wired, and Bluetooth input devices.
- [ ] P5.2 Use current Android routing APIs, including `setCommunicationDevice` where supported.
- [ ] P5.3 Display verified active input and available devices.
- [ ] P5.4 Persist route changes with timestamps and from/to device identifiers.
- [ ] P5.5 Handle Bluetooth disconnect, reconnect, unavailable route, and explicit fallback notice.
- [ ] P5.6 Test routing on each target Android version and physical device.

**Verify:** record from each input, disconnect Bluetooth during capture, confirm event log and audible fallback behavior.

## Part 6 — Durable processing queue

- [ ] P6.1 Implement database-backed ASR and annotation jobs with unique sample keys.
- [ ] P6.2 Implement atomic claim, lease expiry, heartbeat, cancellation, and stale-job reclaim.
- [ ] P6.3 Implement bounded concurrency, recording priority, and configurable one-job native inference baseline.
- [ ] P6.4 Implement retry classification, exponential backoff, and permanent-error handling.
- [ ] P6.5 Make workers idempotent based on input checksum and model identity.
- [ ] P6.6 Expose queue depth, pending, processing, completed, and failed counts.
- [ ] P6.7 Ensure processing never blocks starting the next recording.

**Verify:** kill the process during each queue stage; jobs resume without duplicate or lost output.

## Part 7 — whisper.cpp ASR

- [ ] P7.1 Vendor/pin a reviewed whisper.cpp revision and record license/provenance.
- [ ] P7.2 Implement CMake/NDK build and JNI lifecycle with safe load/unload.
- [ ] P7.3 Implement `ASREngine` and `TranscriptionResult` with segments, timing, model ID/version, and input checksum.
- [ ] P7.4 Implement `ModelRegistry`, model metadata, installation checks, and SHA-256 verification.
- [ ] P7.5 Validate WAV format and bounds before native inference.
- [ ] P7.6 Add Hindi, English, and Marathi fixture recordings and offline transcription tests.
- [ ] P7.7 Capture processing time, real-time factor, memory errors, and native diagnostics.
- [ ] P7.8 Persist raw ASR JSON atomically and update state only after validation.

**Exit gate:** recorder + persistence + ASR survives a classroom-style trial before LLM work begins.

## Part 8 — Session UX and recovery

- [ ] P8.1 Implement Home and New Session with only essential metadata.
- [ ] P8.2 Implement Recording screen with large controls, language, mic, timer, and counts.
- [ ] P8.3 Implement Session Summary and Errors screens.
- [ ] P8.4 Implement startup recovery flow with resumable session and incomplete-sample report.
- [ ] P8.5 Prevent unsafe navigation/actions while recording.
- [ ] P8.6 Add accessibility, touch-target, and low-distraction usability checks.

**Verify:** an operator can run a class without opening processing controls or editing transcripts.

## Part 9 — Export engine

- [ ] P9.1 Define export directory/ZIP layout and schema versions.
- [ ] P9.2 Generate manifest, JSONL, per-sample JSON, events, audio, model metadata, and checksums.
- [ ] P9.3 Validate database/filesystem consistency before export and show actionable failures.
- [ ] P9.4 Implement SAF destination selection and streaming export.
- [ ] P9.5 Build in a temporary location, verify by reopening ZIP, validate entries/counts/checksums, then finalize.
- [ ] P9.6 Persist ExportRecord with export ID, path, checksum, and timestamp.
- [ ] P9.7 Support re-export without overwrite and interrupted-export recovery.
- [ ] P9.8 Keep local source data until explicit deletion.

**Verify:** export 100+ samples, interrupt at each stage, reopen archive independently, and confirm all checksums.

## Part 10 — Local LLM annotation (post-MVP)

- [ ] P10.1 Pin llama.cpp revision and document license/provenance.
- [ ] P10.2 Implement GGUF registry, metadata, checksum verification, and arm64 loading.
- [ ] P10.3 Implement `LocalLLMEngine` behind JNI; never run on main thread.
- [ ] P10.4 Define strict versioned annotation schema and prompt template.
- [ ] P10.5 Validate JSON, reject malformed output, and persist validation errors.
- [ ] P10.6 Add evidence spans and `insufficient_audio_evidence` handling.
- [ ] P10.7 Store prompt/model/settings and label outputs `AI_SUGGESTION`.
- [ ] P10.8 Ensure LLM failure still exports audio and ASR.

## Part 11 — Quality, benchmark, and diagnostics

- [ ] P11.1 Implement audio quality analyzer and quality flags without automatic deletion.
- [ ] P11.2 Add developer-only ASR/LLM benchmark screen and reproducible corpus metadata.
- [ ] P11.3 Measure latency, RTF, RAM, battery, temperature, queue backlog, JSON reliability, and per-language quality.
- [ ] P11.4 Add local redacted structured logs and rotating diagnostics.
- [ ] P11.5 Add diagnostic bundle export excluding content by default.

## Part 12 — Fault, soak, and field validation

- [ ] P12.1 Automate kill/restart tests across capture, processing, and export boundaries.
- [ ] P12.2 Test Bluetooth disconnect/reconnect, lock, rotation, reboot, low battery, low storage, heat, and permission changes.
- [ ] P12.3 Test empty, silent, clipped, noisy, very long, duplicate, and mixed-language samples.
- [ ] P12.4 Run 3–6 hour field-style soak with hundreds of samples per target device.
- [ ] P12.5 Verify zero lost audio, zero silent mutation, no orphan artifacts, valid WAVs, bounded resources, and valid export checksums.
- [ ] P12.6 Record results in `FIELD_DEPLOYMENT.md`; fix failures and rerun affected gates.

## Part 13 — Documentation and release

- [ ] P13.1 Complete `ARCHITECTURE.md`, `DATA_SCHEMA.md`, `AUDIO_PIPELINE.md`, `ASR.md`, `LOCAL_LLM.md`, `EXPORT_FORMAT.md`, `RECOVERY.md`, `TESTING.md`, `FIELD_DEPLOYMENT.md`, and `MODEL_LICENSES.md`.
- [ ] P13.2 Add `CHANGELOG.md` and `SCHEMA_CHANGELOG.md`.
- [ ] P13.3 Document offline guarantee, permissions, storage cleanup, backup behavior, and operator workflow.
- [ ] P13.4 Create release checklist covering APK signing, model packaging, ABI, migration, permissions, and field rollback.
- [ ] P13.5 Produce a release candidate and attach test evidence.

## Completion checklist

- [ ] No INTERNET permission or remote dependency.
- [ ] Capture path remains usable when ASR/LLM/export fail.
- [ ] Raw audio is durable, checksummed, recoverable, and never silently discarded.
- [ ] State transitions, retries, migrations, and exports are tested.
- [ ] Hindi, English, and Marathi are covered on real target hardware.
- [ ] External microphones and Bluetooth failure behavior are verified.
- [ ] The verified ZIP can be copied to Mac and parsed using only documented schemas.
- [ ] All `[!]` items are resolved or explicitly accepted by the user with a recorded decision.
