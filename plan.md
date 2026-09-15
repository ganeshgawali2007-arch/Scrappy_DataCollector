# Classroom Capture Android App — Implementation Plan

## 1. Objective and non-negotiable invariants

Build an offline-first Android Kotlin/Compose application that records classroom speech in Hindi, English, and Marathi, persists every raw artifact, runs local ASR and optional local LLM annotation, and exports a self-contained verified dataset for Mac review.

The implementation must preserve these invariants:

1. **Capture is independent of inference.** Recording and audio persistence never wait for Whisper or the LLM.
2. **Raw evidence is immutable.** Never overwrite audio, raw transcripts, model outputs, or event logs; create new versions.
3. **Audio is durable before processing.** A sample is queued only after its WAV is fully flushed, fsynced where supported, checksum computed, and metadata committed.
4. **Derived data is optional.** Missing ASR/LLM output never blocks export.
5. **Every state transition is recoverable and auditable.** Persist state, attempts, errors, timestamps, app/model versions, and provenance.
6. **Offline means technically enforceable.** Production has no INTERNET permission and no network dependency.
7. **No destructive recovery.** Never use destructive Room migration, automatic deletion, silent overwrite, or silent deduplication.

## 2. Scope

### MVP (must ship first)

- Manual start/stop segmentation.
- Foreground microphone service surviving lock, background, rotation, and UI process loss.
- 16 kHz, mono, PCM16 WAV files, one file per sample.
- Room metadata store plus app-private artifact directory.
- Startup reconciliation and crash recovery.
- Persistent ASR queue with whisper.cpp behind a Kotlin interface.
- Hindi/English/Marathi per-sample selection.
- SAF export of a versioned ZIP and/or directory with manifest, JSONL, audio, checksums, and event logs.
- No local LLM required for MVP; annotation stage is an extension behind the same queue contract.

### Post-MVP

- llama.cpp GGUF inference and schema-constrained annotation suggestions.
- Evidence spans, quality analysis, benchmark screen, automatic VAD segmentation, model package installation, encrypted local storage.

Explicitly out of scope: cloud sync, accounts, translation, remote AI, analytics SDKs, Mac verification UI.

## 3. Repository and module structure

Use a single Android repository with clear module boundaries:

```text
app/src/main/java/.../
  data/                 Room entities, DAOs, migrations
  storage/              ArtifactStore, atomic files, checksums, reconciliation
  audio/                AudioRecord, WAV writer, device routing, quality meter
  recording/            Foreground service, controller, notifications
  processing/           Durable queue, workers, retry policy, backpressure
  asr/                  ASREngine, whisper JNI adapter, model registry
  llm/                  LocalLLMEngine, llama JNI adapter, schema validator
  export/               Manifest, JSONL, ZIP, SAF, verification
  ui/                   Compose screens and view models
  diagnostics/          logs, benchmark, debug diagnostics
app/src/main/cpp/       whisper/, llama/, jni/
docs/                   architecture, schema, recovery, export, testing, deployment
models/                 README and checksums; never commit large model binaries
benchmark/              corpus metadata and reproducible benchmark tooling
```

Keep native pointers and C++ types behind interfaces. Use arm64-v8a as the deployment ABI; retain a test-only emulator configuration.

## 4. Data contracts

Create immutable, versioned records for Session, Sample, Artifact, ProcessingJob, DeviceEvent, QualityMetrics, and ExportRecord. Use UUIDs, UTC epoch milliseconds, explicit nullable fields, and schema version fields. Store language as `hi|en|mr`; retain a separate `codeSwitching` flag.

Sample state machine:

```text
CREATED -> RECORDING -> AUDIO_SAVED -> QUEUED_ASR -> TRANSCRIBING
-> TRANSCRIBED -> QUEUED_LLM -> ANNOTATING -> ANNOTATED -> READY_FOR_EXPORT
```

Any processing state may transition to retryable `ERROR`; audio-saved samples remain exportable. Persist `attemptCount`, `lastErrorCode`, `lastErrorMessage`, and `nextAttemptAt`. Enforce legal transitions in one domain component and test every edge.

Artifacts use content-addressed metadata (SHA-256, byte size, MIME type, relative path). Never trust filenames as identity.

## 5. Crash-safe capture protocol

1. Service creates sample UUID and DB row in `RECORDING`.
2. Write a temporary file in the sample directory (`audio.wav.partial`) using a streaming WAV writer with a placeholder header.
3. On stop, finalize header, close stream, flush, compute SHA-256 and audio quality metrics.
4. Atomically rename `.partial` to `audio.wav`.
5. In one Room transaction, record artifact metadata and move state to `AUDIO_SAVED`/`QUEUED_ASR`.
6. Enqueue processing using a unique job key per sample.

On startup, scan partial files, completed files, and DB rows. Finalize recoverable files, create `RECOVERED` samples for files without rows, quarantine corrupt/ambiguous files, and present a repair report. Recovery must be idempotent.

## 6. Recording service and audio devices

Implement a microphone foreground service with required modern Android declarations and runtime permission handling. The service owns `AudioRecord`, notification, session/sample lifecycle, and binder/state flow; Compose never owns recording.

Use `AudioManager` device APIs and `setCommunicationDevice` where supported. Observe add/remove events, persist every route change, show the active input, and explicitly notify on Bluetooth fallback. Never claim a route is active until verified from the recording configuration. Handle initialization failure with a recoverable UI and preserve prior samples.

Add bounded buffering, writer failure detection, monotonic timestamps, clipping/silence meter, and a storage-space preflight. Stop safely on unrecoverable write failure and mark the sample with a reason.

## 7. Processing architecture

Use a durable database-backed queue, not in-memory coroutines. A dispatcher claims one job atomically with a lease/heartbeat; stale leases are reclaimed after restart. Start with one native inference job at a time, configurable by device profile. Recording has priority and may pause inference.

Workers must be idempotent: if output exists with a matching input checksum/model identity, mark complete; otherwise write output atomically. Retry transient failures twice with exponential backoff; classify permanent model/input errors separately. Cancellation must leave a valid retryable state.

## 8. ASR integration

Define `ASREngine.transcribe(audioPath, language): TranscriptionResult` with text, segments, timings, model ID/version, and input checksum. Integrate whisper.cpp through JNI/CMake; validate sample rate, duration, and file integrity before native calls. Bound memory, catch native crashes at the worker boundary where possible, and record diagnostics. Ship one benchmarked model initially through `ModelRegistry`; do not hard-code paths.

## 9. LLM annotation extension

Implement only after MVP field gate passes. Use llama.cpp with GGUF and a strict JSON schema. Validate output against schema, reject malformed or unsupported claims, and store the exact prompt template, model identity, temperature/settings, output, and validation errors. Every claim should carry transcript evidence spans when available; otherwise return null plus `insufficient_audio_evidence`. Label all output `AI_SUGGESTION`.

## 10. UI requirements

Provide only essential flows: Home, New Session, Recording, Session Summary, Export, Recovery, and Errors. Large start/stop controls, sticky language, visible active microphone, recording duration, sample counts, and pending/failed counts. Processing continues in background; never require operator review during class. Disable unsafe actions while recording, preserve state through recreation, and make retry/recovery explicit.

## 11. Storage, export, and verification

Store artifacts under:

```text
sessions/<session-id>/samples/<sample-id>/audio.wav
sessions/<session-id>/samples/<sample-id>/asr.v1.json
sessions/<session-id>/samples/<sample-id>/annotation.v1.json
sessions/<session-id>/samples/<sample-id>/events.json
sessions/<session-id>/manifest.v1.json
```

Export through Android SAF to a user-selected destination. Build in a temporary export directory, write manifest and JSONL, include all artifacts and checksums, fsync/close, reopen the ZIP, validate every entry and count, then atomically finalize the destination. Interrupted exports remain resumable and never alter source data. Re-export creates a new export record; never silently overwrite. Keep local data until explicit user deletion.

Manifest must include schema version, export ID, creation time, app version, device, session/sample counts, artifact checksums, processing states, model identities, and event-log references. Define JSON schemas and validate them in CI.

## 12. Privacy and security

Request only microphone and notification permissions needed for operation. Do not request INTERNET. Use pseudonymous codes by default. Exclude classroom content from logs. Disable backup of raw artifacts unless explicitly designed and documented. Record consent/operator acknowledgement in session metadata if required by deployment policy. Plan encrypted storage as a later milestone without changing artifact contracts.

## 13. Observability and diagnostics

Use local structured logs with rotation and redaction. Record crash-safe counters for recordings started/completed, write failures, queue depth, retry counts, storage/battery/thermal warnings, and export verification failures. Provide a diagnostic bundle that excludes audio/transcripts by default and can be manually shared.

## 14. Testing and release gates

### Automated tests

- Unit: state machine, migrations, atomic storage, WAV headers, checksums, JSON schemas, retry logic, export manifest.
- Instrumentation: permission flows, foreground service, lock/background/rotation, process recreation, Room persistence, SAF.
- Native: model load/unload, malformed WAV, empty/silent/noisy input, memory failure, JNI lifecycle.
- Property/fault tests: kill the app at each capture/export step; recovery is idempotent and lossless.

### Required scenarios

Bluetooth disconnect/reconnect, low storage, low battery, thermal throttling, device reboot, empty/silent/clipped/noisy/long audio, mixed Hindi-English and Marathi-English, duplicate audio, migration, interrupted ZIP creation, ASR/LLM failure.

### Field gate

Run a 3–6 hour classroom-style soak on each target device with hundreds of samples and no internet. Verify zero lost audio, no orphan artifacts, valid WAVs, bounded memory, acceptable battery/temperature, and export checksum validation. Do not enable LLM or automatic VAD until the capture/ASR gate passes.

## 15. Agent execution order and acceptance criteria

1. **Skeleton:** build/install/launch offline; CI debug APK.
2. **Data/storage:** create/reopen sessions; migration tests; no destructive fallback.
3. **Recorder:** valid WAV, lock/background survival, atomic capture protocol.
4. **Devices:** built-in/wired/Bluetooth routing and event log.
5. **Recovery:** startup reconciliation and idempotent repair.
6. **ASR:** whisper JNI, model registry, durable queue, Hindi/English/Marathi fixtures.
7. **Pipeline UX:** record next segment immediately; progress/errors/retries.
8. **Export:** SAF, versioned manifest/JSONL, ZIP verification and resume.
9. **LLM extension:** llama JNI, schema validation, evidence spans and anti-hallucination rules.
10. **Benchmark/field hardening:** device/model benchmark, soak tests, fixes, release checklist.

Each agent must update relevant docs, add tests for its contract, avoid unrelated dependencies, and leave the repository buildable. No milestone is accepted until its exit criteria and regression suite pass.

## 16. Definition of done

With no internet and an external microphone, an operator can create a Grade 3 Mathematics Marathi session, record many manual segments while locking/backgrounding the phone, recover after process death, process audio offline, export a verified ZIP containing audio, transcript/annotation artifacts, metadata, events, schema versions, and checksums, and repeat export without data loss or silent mutation.
