# DECISIONS.md — scrappy (Classroom Capture)

> Status: updated 2026-09-14 with user decisions on display name, UI handoff,
> state machine, model delivery, serialization, export verification.
> Source of truth for product, device, model, and process decisions that affect
> data integrity, privacy, licensing, accuracy, compatibility, schema, or architecture.
> Routine implementation details live in code/docs, not here.

## D1. Project identity

- **D1.1 Display name:** `scrappy` (lowercase). Application ID remains `com.scraper.classroomcapture`.
- **D1.2 Package name (pre-release):** `com.scraper.classroomcapture`.
- **D1.3 Package rename readiness:**
  - Single source of truth for `namespace` / `applicationId` in Gradle config (e.g. `app/build.gradle.kts` + version catalog).
  - No hard-coded package strings in Kotlin, manifests, deeplinks, FileProviders, or docs except this file.
  - Must be trivial to rename before release via one config change + refactor.
- **Rationale:** user explicitly requires easy pre-release rename.

## D2. Device and Android target (P0-pinned 2026-09-14)

- **D2.1 Initial physical field device:** user-purchased 2023 Android phone capable of demanding games (e.g. BGMI). Exact model **unknown until connected**.
- **D2.2 Device fingerprint TODO (record on first connection):**
  - exact model, Android version / API level, RAM, chipset/SoC, total + free storage, supported audio routes (built-in / wired / USB / Bluetooth SCO/LE).
  - Update this file with measured values; do not assume a specific model before then.
- **D2.3 SDK / ABI (user decision 2026-09-14):**
  - `minSdk = 29` (Android 10). Change only if compatibility testing shows strong reason + blocker report.
  - `targetSdk = latest stable supported by installed Android Studio` (to be pinned in `P1.2` once toolchain exists).
  - Release ABI: `arm64-v8a`. Emulator config is test-only.
- **D2.4 Test split:**
  - Emulator: UI, persistence, recovery/reconciliation logic.
  - Physical device: recording, Bluetooth routing, performance, thermal, ASR accuracy/latency/stability.
- **D2.5 Responsiveness invariant:** app must remain responsive while recording and must not interfere with normal phone operation. Recording has priority over inference (per `plan.md §7`); inference defaults to single concurrent native job, pausable.
- **Impact:** affects P0.2 (device matrix), P1.2 (SDK/ABI/NDK), P4/P5/P6/P7 benchmark gates.

## D3. Microphones and routing

- **D3.1 Supported inputs (all must work when exposed by Android):**
  1. Android built-in microphone (acceptable fallback).
  2. Wireless RØDE microphone.
  3. Wired headphone microphone.
  4. Wired or USB microphone when Android exposes it as an input device.
- **D3.2 Preferred mic may vary by classroom.** No single hard-coded default; persist per-sample route + allow operator-visible selection where API permits.
- **D3.3 Routing implementation:**
  - Use current Android audio-routing APIs (`AudioManager`, device callbacks, `setCommunicationDevice` where supported).
  - Display **verified** active input only (from recording configuration, not assumed).
  - Persist every route change with timestamps + from/to identifiers in `DeviceEvent`.
- **D3.4 Disconnect policy:** if external mic disconnects mid-capture, continue recording on fallback mic when possible, explicitly notify operator (notification + UI), log event. Never silently switch or stop without marking sample reason.
- **Affects:** P5 (AudioDeviceManager), P4 (service resilience), P8 (UX), P12 (Bluetooth disconnect/reconnect test).

## D4. Whisper model — accuracy-first selection

- **D4.1 Objective:** maximize accuracy for Hindi, English, Marathi (incl. classroom noise + code-switching: Hindi-English, Marathi-English) subject to reliable on-device execution without harming capture.
- **D4.2 No premature pinning.** Do not choose permanently on size or generic benchmark claims alone.
- **D4.3 Mechanism:** `ModelRegistry` + reproducible benchmark process (per `plan.md §7-8`, `todos.md P7/P11`):
  - Registry holds model ID/version, SHA-256, license/provenance, path resolution. No hard-coded model paths in app code.
  - Vendor/pin reviewed whisper.cpp revision; record license/provenance in `MODEL_LICENSES.md` (to be created in P7.1).
- **D4.4 Minimum candidates to evaluate on the actual 2023 phone:**
  - `tiny` multilingual
  - `base` multilingual
  - `small` multilingual
  - ≥1 quantized candidate if device can run it reliably (e.g. quantized `small` or `medium` trial — decide at benchmark time based on RAM/thermal headroom).
- **D4.5 Required measurements (real classroom-style recordings, all 3 languages):**
  - WER and CER where reference transcripts exist, reported per-language (hi/en/mr separately).
  - Code-switch + noisy-classroom behavior (qualitative + error analysis if no reference).
  - Latency + real-time factor (RTF), RAM peak/steady, battery drain, thermal behavior, crash/stability rate, long-session behavior.
- **D4.6 Selection rule:** choose most accurate model the phone runs reliably without harming capture (no dropped audio, no UI jank, bounded memory/thermal). If most accurate is too slow/unstable, document evidence and select best reliable alternative. Record decision + evidence here + `FIELD_DEPLOYMENT.md`.
- **D4.7 Replaceability:** model remains swappable via `ModelRegistry`; ASR worker validates input checksum + model identity for idempotency.
- **D4.8 LLM gating:** do **not** add local LLM until recorder, storage, recovery, ASR, and export gates pass (per user + `plan.md §9/§14` field gate).

## D5. Product / privacy / export / offline (unchanged)

- **D5.1** Keep previously specified metadata, privacy rules, export schemas, offline guarantee, and blocker protocol from `plan.md` + `todos.md`.
  - Privacy: mic + notification permissions only; no `INTERNET` in production (P0.6 gate); pseudonymous codes; no classroom content in logs; consent/operator ack in session metadata if deployment requires; backup of raw artifacts excluded unless designed/documented.
  - Export: SAF, temp-dir build, manifest.v1 + JSONL + per-sample JSON + events + audio + checksums, reopen-ZIP verification, new record per re-export, keep local until explicit delete.
  - Offline: technically enforceable (manifest permission check + CI check + no network dependency).
- **D5.2** Execution order: start at P0, proceed in order per `todos.md §15` / agent order.

## D6. Autonomy vs blocker escalation

- **D6.1 Agent may decide autonomously:** routine implementation choices (file layout within `plan.md §3`, naming, Compose structure, non-contract internals, test scaffolding) as long as invariants hold and repo stays buildable.
- **D6.2 Must STOP + report BLOCKED (with evidence, ≥2 options, recommendation, exact input needed) when a decision affects:**
  - data integrity, privacy, licensing, model accuracy, device compatibility, schema compatibility, or core architecture.
  - Includes: state-machine changes, migration strategy, storage layout, WAV/capture protocol deviations, routing fallback behavior, model choice, schema/export format changes, permission changes, native crash handling tradeoffs.
- **D6.3** After user input, record outcome in this file, update `todos.md`, implement chosen path, rerun affected checks.

## Open items / TODOs for P0

- [ ] Record exact 2023 phone fingerprint on first connection (D2.2).
- [x] Pin `targetSdk` + AGP/Gradle/NDK/CMake versions (done: target/compile 35, AGP 8.7.3, Gradle wrapper 8.9, NDK r27c, CMake 3.22.1 — see D7).
- [ ] Pin whisper.cpp revision + model SHAs + licenses at P7.1 (do not fetch binaries into git).
- [ ] Verify production manifest contains no `INTERNET` (P0.6 gate — enforced at skeleton + CI).
- [ ] Finalize phone model, RØDE model, class sizes, transfer method when field workflow available (non-blocking).

## D7. Development environment — pinned (P0.2, opt B installed 2026-09-14)

Host: `darwin arm64`, repo `/Users/ganesh/Desktop/Datacollector`, git `master` no commits/no remote yet.

| Component | Pinned version | Location / note |
|---|---|---|
| Java (builds) | OpenJDK 17.0.20.1 (Homebrew `openjdk@17`) | `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`. Default `openjdk` 26.0.2 is **not** for builds (too new for AGP). |
| Android SDK root | cmdline-tools pkg 15859902 | `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` (brew cask default). No Studio; no `~/Library/Android/sdk`. |
| sdkmanager | 22.0 (deprecated wrapper, works) | `/opt/homebrew/bin/sdkmanager`. Licenses accepted 2026-09-14. |
| platform-tools / adb | 37.0.1-15733141 | SDK copy + brew cask. First device (emulator) attached 2026-09-14; fingerprint below. |
| compileSdk / targetSdk | **35** | `platforms;android-35` rev 2. Latest stable compatible with AGP 8.7; API 36 exists but deferred to avoid AGP risk — upgrade path noted. |
| minSdk | 29 (Android 10) | `platforms;android-29` rev 5 installed for min check. |
| build-tools | 35.0.1 | `build-tools;35.0.1` (aapt2 2.19). |
| NDK | 27.2.12479018 (r27c LTS) | `ndk;27.2.12479018`. |
| CMake (project) | 3.22.1 (SDK) | `cmake;3.22.1` — AGP default. System cmake 4.4.3 present but **not** for builds. |
| Gradle (system) | 9.7.1 (brew, Kotlin 2.4.0) | Convenience only — **project must use wrapper 8.9**, system 9.x is too new for AGP 8.x. |
| Gradle wrapper (project, P1) | **8.9** | To be added with skeleton. |
| AGP (project, P1) | **8.7.3** | Supports compile/target 35, JDK 17, min 29. |
| Kotlin (project, P1) | 2.0.x (per AGP 8.7.3 / Compose BOM) | Pin at skeleton in version catalog. |

Initial detect (pre-install) was all-absent except JDK 26; install done under opt-B authorization. No env upgrade/replace from here without blocker report.

### D7.1 Test-device matrix (P1 verify 2026-09-14)

- **Emulator (attached 2026-09-14, online):** AVD `Scraper_API35` on `emulator-5554`.
  - Fingerprint: `sdk_gphone64_arm64` (Android 15 / SDK 35, `arm64-v8a`, API level 35).
  - ABI/arch `arm64-v8a`; model `ranchu`, board `goldfish_arm64`; ~2.5 GB RAM (MemTotal 2531992 kB); /data ~9.3 GB available.
  - Config: 4 cores, 2 GB RAM, tag `google_apis` (Play Store off), no snapshots baseline; airplane mode on for offline testing.
  - Used for UI/persistence/recovery verification until the 2023 phone connects (fingerprint still TODO — D2.2).
- **2023 phone:** first `adb` connection pending; fingerprint to be recorded under D2.2 on arrival.

## D8. Git, CI, package (P0 decision 2026-09-14)

- Package/application ID: `com.scraper.classroomcapture`. App name: `Scraper`. Single source in Gradle config; no hard-coded strings (D1.3).
- `main` must remain buildable. Short-lived feature branches for larger milestones. Clear conventional commits (e.g. `feat:`, `fix:`, `docs:`, `test:`, `chore:`).
- CI: GitHub Actions if hosted on GitHub (no remote yet — add remote to enable). CI must run: formatting, static analysis, unit tests, schema validation, debug build. CI config to be added with skeleton (P0.5/P1) once toolchain exists; draft recorded in P0.5 note below.
- P0.5 draft: workflow `ci.yml` on push/PR → `gradle spotlessCheck` (or ktlint), `gradle lintDebug`, `gradle testDebugUnitTest`, JSON-schema validation script for export/manifest schemas, `gradle assembleDebug`. Reproducible: pinned Gradle wrapper + dependency catalog.

## D9. Session metadata and privacy (P0 decision 2026-09-14)

- Fields: Grade, Subject, Teacher pseudonymous code, School pseudonymous code, Default language (`hi|en|mr`), Optional session notes, Optional consent/authorization acknowledgement. Date/timestamps auto-generated (UTC epoch ms).
- Prohibited: real names, phone numbers, emails, unnecessary PII.
- Pseudocodes: flexible, e.g. `teacher_07`, `school_03`; basic validation (non-blank, allowed charset, length cap); no rigid numbering.
- Privacy (from plan): mic + notification permissions only; consent ack stored in session metadata if deployment requires; classroom content excluded from logs; raw-artifact backup disabled unless designed/documented.

## D10. Classroom audio session design (decision 2026-09-14)

- Typical session 1–2 h; soak 3–6 h. Many manual samples per class (100–300 initial design point).
- Recommended sample 5 s–2 min; configurable max starting at 5 min. Limits centralized in config (not scattered constants).
- Storage: preflight + warn before critically low; never stop/delete raw silently for low storage — mark reason, preserve completed samples, recoverable UI.
- Mic priority: test built-in first, then externals as available. No hard-coded RØDE model; runtime detection + verified active display + event log (D3).

## D11. ASR model acquisition and fixtures (decision 2026-09-14)

- Delivery: local sideload from Mac/USB/user-controlled file transfer. No cloud download at runtime. App stays fully offline; fetching OSS whisper.cpp code/weights allowed only for dev/benchmark prep with recorded source/version/license.
- No arbitrary RAM ceiling before measurement. First record phone RAM/storage/chipset/Android version, then benchmark tiny/base/small multilingual + practical quantized variants on-device.
- Ground truth: none yet. Create clearly labeled developer fixtures for pipeline/schema tests only — never present as field accuracy. Later step (P7/P11): collect consented classroom recordings + human references, then run per-language WER/CER + latency/RTF/RAM/battery/thermal/stability/long-session analysis.

## D12. Export format and transfer (decision 2026-09-14)

- Define `manifest.v1.json`, JSONL, all export schemas from scratch; document in `DATA_SCHEMA.md` + `EXPORT_FORMAT.md` (P9/P13).
- Destination: SAF user-selected location; produce verified folder or ZIP. Transfer to Mac by any user method (USB/SD/share/WhatsApp/etc.).
- Prefer normal ZIP for complete datasets; show size pre-export + warn if too large for messaging apps. No compression/split that alters raw audio. Later if needed: multiple numbered ZIP parts or folder export with preserved checksums + manifest.
- Invariants: never requires network, never silently overwrites existing export (new `ExportRecord` per export), never auto-deletes local source.

## D13. UI design handoff (user decision 2026-09-14)

- Adopt the ochre/ivory handoff (`ui.readme`) now, starting from P1 theme rework.
- Light-only for initial field release. Dynamic color disabled. Dark mode not claimed until separately tested.
- Display name in UI: `scrappy` (header on Home, About).
- `ui.readme` is canonical for visual reference, tokens, typography, spacing, navigation, per-screen behavior, accessibility.
- 7 mockup PNGs in `SampleforUi/` to be relocated to `ui/` for canonical path.
- Remaining handoff items (fonts, components, screen variants) to be implemented progressively through P1 and P8.

## D14. Sample state machine — persisted 14-state model (user decision 2026-09-14)

States (all persisted in Room):

| State | Meaning |
|---|---|
| `CREATED` | Metadata row created; no audio yet |
| `RECORDING` | Foreground service owns active capture |
| `SAVING` | Stop requested; WAV finalization/checksum/atomic rename in progress |
| `AUDIO_SAVED` | Durable audio + artifact metadata committed |
| `QUEUED_ASR` | Audio saved; waiting for ASR worker claim |
| `TRANSCRIBING` | ASR worker running whisper.cpp |
| `TRANSCRIBED` | ASR output persisted; ready for LLM or export |
| `QUEUED_LLM` | Waiting for LLM annotation worker |
| `ANNOTATING` | LLM worker running |
| `ANNOTATED` | LLM output persisted; ready for export |
| `READY_FOR_EXPORT` | All desired artifacts present |
| `EXPORTED` | At least one successful export completed |
| `ERROR` | Retryable or permanent error recorded |
| `RECOVERED` | Startup reconciliation repaired or adopted interrupted artifact |

Legal transitions enforced by a single validator component (P2.3):

- `CREATED → RECORDING` (service starts)
- `RECORDING → SAVING` (service stops, WAV finalization)
- `SAVING → AUDIO_SAVED` (WAV committed) or `SAVING → ERROR` (write failed)
- `AUDIO_SAVED → QUEUED_ASR` (auto-enqueue)
- `QUEUED_ASR → TRANSCRIBING` (worker claims)
- `TRANSCRIBING → TRANSCRIBED` or `TRANSCRIBING → ERROR`
- `TRANSCRIBED → QUEUED_LLM` (auto-enqueue, optional)
- `QUEUED_LLM → ANNOTATING` (worker claims)
- `ANNOTATING → ANNOTATED` or `ANNOTATING → ERROR`
- `ANNOTATED → READY_FOR_EXPORT` (or `TRANSCRIBED → READY_FOR_EXPORT` if no LLM)
- `READY_FOR_EXPORT → EXPORTED` (export completes)
- Any processing state → `ERROR` (retryable or permanent)
- `ERROR → QUEUED_ASR` or `ERROR → QUEUED_LLM` (retry)
- P6 retry (no ERROR hop, operator sees QUEUED while backing off):
  - `TRANSCRIBING → QUEUED_ASR` (retryable ASR failure)
  - `ANNOTATING → QUEUED_LLM` (retryable LLM failure)
- P6/P10.8 fallback (audio + ASR stay exportable on permanent LLM failure):
  - `ANNOTATING → TRANSCRIBED`, then `TRANSCRIBED → READY_FOR_EXPORT`
- `RECOVERED → AUDIO_SAVED` or `RECOVERED → QUEUED_ASR` or `RECOVERED → ERROR`
- `RECOVERED` is entered **only** by startup reconciliation; retains `recoveryReason`, `recoveredAt`, and prior observed state.
- `RECOVERED` never transitions directly to `EXPORTED`.
- `AUDIO_SAVED` is always exportable (audio alone; derived data optional per plan invariant 4).

## D15. Model delivery — user-controlled file picker (user decision 2026-09-14)

- APK must **not** bundle large Whisper/LLM weights.
- On first run (or when no model is installed), show a model setup screen.
- User imports model from local file via Android file picker (USB/Mac transfer or other user-controlled source).
- App copies into app-private model storage, verifies SHA-256 and declared metadata/license, then marks installed.
- Recording and export work **before** model installation; missing models produce a clear non-blocking status message.
- No runtime cloud download ever.
- Two model types expected: ASR (whisper.cpp) and local schema generator (for LLM annotation). Schema generator model delivery follows the same file-pick-and-verify pattern.
- `ModelRegistry` in P7/P10 manages model metadata, installation checks, version tracking. Paths never hard-coded in application code.

## D16. Serialization — kotlinx.serialization (user decision 2026-09-14)

- Use `kotlinx.serialization` for all structured data: Room type converters, ASR JSON, LLM annotation JSON, event logs, export manifest, JSONL records.
- JSON schemas validated in CI via the `schemas/` directory hook (ci.yml schema-validation placeholder).
- Add plugin + dependency in version catalog and `app/build.gradle.kts`.

## D17. Export verification semantics (user decision 2026-09-14)

- `VERIFIED` (or `VERIFIED` label): requires successful read-back validation after writing. Only used when the SAF provider supports reliable read-back.
- `WRITTEN_UNVERIFIED`: allowed only when the selected SAF provider cannot reliably read back the completed output. Must be shown to the user as a limitation. Never labeled `VERIFIED`.
- Source recordings always remain on the phone; never auto-deleted after export.
- `ExportRecord` persists: export ID, path, checksum, timestamp, verification status (`VERIFIED` / `WRITTEN_UNVERIFIED`).

## D18. Dataset schema — pedagogical_sample.v2 (user decision 2026-09-15)

- Canonical schema filed at `docs/DATA_SCHEMA.md` (source: user-supplied schema text).
- Canonical record is a teaching utterance/activity (`records.jsonl`, one UTF-8 JSON object per record), linked to source audio + provenance. Not a generic annotation record.
- Android v1 populates `source` from ASR; `target`/gold fields stay pending for Mac review. Reviewer corrections create a new immutable `gold.v2` artifact linked by `sample_id`; raw audio/ASR never changes.
- Capture languages: `hi`, `en`, `mr`. Export `target_language` is ISO 639-3 when known (incl. `mun` Mundari); sentinel `"unset"` until known — never a display name in target text.
- `provenance.processing_state` maps 1:1 to the D14 14-state machine (`READY_FOR_EXPORT` etc.).
- Training views (`translation_pairs.v1`, `tts_pairs.v1`, `pedagogy_eval.v1`) are derived, each retaining `sample_id`; gold training data requires `translation_status: human_verified`.
- Supersedes D12's `manifest.v1.json` naming: export manifest is `manifest.v2.json`, export dir layout per schema §Files and immutability.
- Unknown values use `null`/`pending`, never fabricated. AI metadata is suggestion-only, retains evidence spans + `generated_by`.
- OPEN: `pedagogical_form` vocabulary contained a suspect value ` ಹಾಡ` (leading space, Kannada script) — user authorized editor decision 2026-09-15: corrected to `song` (ಹಾಡ = "song"), documented in `docs/DATA_SCHEMA.md`. Resolved.

## D19. Artifact store + reconciliation (P3, 2026-09-15)

- On-device root: app-private `filesDir/scrappy/`; layout `sessions/<sessionId>/samples/<sampleId>/{audio.wav,asr.v1.json,annotation.v1.json}`, `tmp/` staging, `quarantine/` (never auto-deleted). Only relative paths persisted.
- Writes are tmp + fsync + atomic rename + dir fsync; readers never see half-written files. SHA-256 streamed (64 KB buffer).
- Thresholds: refuse new recordings below 100 MB free, warn below 500 MB (P3.5; UI banners in P8).
- Orphan audio with a session is adopted with language `"und"` (ISO 639-2 undetermined — never fabricated); orphans without a session are quarantined. Durable-state rows with missing audio are reported corrupt, never auto-transitioned (no legal edge exists).
- Startup reconciliation runs every process start on IO dispatcher and logs `RECONCILIATION_COMPLETED` with counts (verified on-device 2026-09-15).

## D20. Recording service (P4, 2026-09-15)

- Capture: `MIC` source (unprocessed, faithful archive across devices), 16 kHz mono PCM16, 100 ms read chunks on a dedicated recorder thread — never the main thread.
- Service is `START_NOT_STICKY` with `microphone` foreground type; crash recovery belongs to P3 reconciliation, not framework resurrection. Partial wake lock (2 h timeout) covers screen-off capture.
- Per-segment lifecycle CREATED → RECORDING → SAVING → AUDIO_SAVED → QUEUED_ASR with artifact + quality + ASR-job rows committed by the service. STOP_SEGMENT chains the next sample with sticky language.
- Metering (rms/peak/clipping/silence/sustained-clip window) accumulates incrementally; quality flags (`interrupted`, `sustained_clipping`) persist on the sample row.
- Interrupts (mic busy/revoked/read errors/empty capture) finalize partial audio when frames exist, else quarantine + ERROR — completed samples are never lost.
- Notification uses a system mic glyph until the P8 brand icon lands; chronometer shows elapsed time without polling.

## D21. Audio routing (P5, 2026-09-15)

- `AudioDeviceManager`: inventory via platform input devices (built-in first), operator preference in private prefs (device id only), route-change flow.
- Service applies the preferred device when still present, else platform routing; the *verified* live input (`AudioRecord.getRoutedDevice`) is persisted on the sample row for summary/export.
- Route changes log `ROUTE_CHANGED` with from/to; Bluetooth loss logs an explicit fallback notice and capture continues — never silently stops.
- P5.6 multi-version/physical-device matrix is pending the 2023 phone; emulator covers built-in mic only.

## D22. Durable processing queue (P6, 2026-09-15)

- DB-backed jobs with unique `(sampleId, kind)` (Room v2 migration 1→2); retries reuse the row, `attemptCount` grows, crash can never duplicate work.
- Atomic claim (`QUEUED → RUNNING` conditional update) + 10-min lease + heartbeat; startup reclaims expired leases and logs `LEASES_RECLAIMED`.
- One job per kind at a time (native baseline, P6.3); loops idle while `recordingActive()` (recording priority, P6.7) and when the engine for that kind is unregistered (ASR P7, LLM P10).
- Retry: `INFERENCE_FAILED`/`OUT_OF_MEMORY` retryable with 30s→60s→…→30min backoff; model/input/output errors permanent. Retry requeues to `QUEUED_*` (D14 P6 edges), permanent ASR failure → `ERROR`, permanent LLM failure → `TRANSCRIBED → READY_FOR_EXPORT` (audio+ASR exportable, P10.8).
- Idempotency (P6.5): audio SHA-256 re-verified before every run; existing `asr.v1.json`/`annotation.v1.json` sidecar adopted instead of re-run; sidecars written atomically via `ArtifactStore`.
- `QueueStats` flow exposes pending/running/done/failed per kind (P6.6, wired to P8 UI).
- Fixed in gate: claim snapshot is re-read after `claim()` so `attemptCount` survives `finish()`; added `TRANSCRIBING→QUEUED_ASR`, `ANNOTATING→QUEUED_LLM`, `ANNOTATING→TRANSCRIBED` edges (see D14).

## Open items / TODOs

- [ ] Record exact 2023 phone fingerprint on first connection (D2.2).
- [x] Pin `targetSdk` + AGP/Gradle/NDK/CMake versions (done: D7).
- [x] Record emulator fingerprint (D7.1 — attached 2026-09-14).
- [ ] Pin whisper.cpp revision + model SHAs + licenses at P7.1.
- [x] Verify production manifest contains no `INTERNET` (P0.6 gate).
- [ ] Finalize phone model, RØDE model, class sizes, transfer method (non-blocking).
- [x] Confirm intended `pedagogical_form` value for the suspect ` ಹಾಡ` entry (D18 — resolved as `song`).

## P0.4 Conventions (recorded for skeleton)

- Kotlin + Compose, single Android repo per `plan.md §3`. Version catalog (`libs.versions.toml`), Kotlin DSL Gradle, `namespace`/`applicationId = com.scraper.classroomcapture`.
- Error taxonomy: `code` (stable machine string, e.g. `AUDIO_START_FAILED`, `WAV_FINALIZE_FAILED`, `STORAGE_LOW`, `MODEL_LOAD_FAILED`, `EXPORT_VERIFY_FAILED`), `message` (operator-safe, no content), `retryable` bool, `nextAttemptAt`, persisted in `ProcessingJob` (`attemptCount`, `lastErrorCode/Message`).
- Logging/redaction: local structured logs with rotation; never log audio, transcripts, notes, or PII; log IDs, counts, durations, error codes, model/app versions, device-route events.
- Commits: conventional, small, buildable; update docs + `todos.md` per milestone.

## P0.6 Offline gate

- Production `AndroidManifest.xml` must contain no `INTERNET` permission. Enforced by manifest review + CI grep check (`grep INTERNET app/src/main/AndroidManifest.xml` must be empty / fail build if present). `plan.md` invariant 6.
