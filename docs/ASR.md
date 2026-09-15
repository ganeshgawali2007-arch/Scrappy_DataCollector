# ASR (P7) — whisper.cpp integration

> Status: P7 gate. Kotlin seams + validation + registry are implemented and
> tested. Native whisper.cpp is **pinned but not yet vendored** (see
> `MODEL_LICENSES.md`); the JNI bridge reports unavailable until vendoring,
> mapping cleanly to `MODEL_MISSING` without blocking capture/export.

## Contract

- `AsrEngine.transcribe(wavFile, language): AsrResultV1` (`processing/Engines.kt`).
- Input: 16 kHz mono PCM16 WAV, `hi|en|mr` only.
- Output: `asr.v1.json` sidecar (`SidecarFormats.AsrResultV1`) with text,
  segments (`start_ms`/`end_ms`/text), `model_id`/`model_version`, `created_at`.
- Input checksum gate lives in the P6 dispatcher (audio SHA-256 re-verified
  before every run); the engine re-validates the WAV header itself (defense
  in depth, P7.5).

## Components (`asr/`)

| File | Role |
|---|---|
| `ModelRegistry.kt` (`FileModelRegistry`) | App-private `filesDir/scrappy/models/<id>.bin + .json`; `installFromFile` copies + SHA-256-verifies; `status` → `Installed/Missing/Corrupt`; no hard-coded paths. |
| `WavValidator.kt` | Parses RIFF/WAVE/fmt/data, enforces PCM16 mono 16 kHz, 200 ms–10 min bounds, truncation check. Throws `INPUT_INVALID` (permanent). |
| `WhisperBridge.kt` | JNI seam. `JniWhisperBridge` loads `scraper_native` safely (`UnsatisfiedLinkError` → unavailable); `FakeWhisperBridge` for unit/emulator tests. |
| `WhisperAsrEngine.kt` | Policy: language gate → WAV gate → model gate → `loadModel` → `transcribe`; maps native Throwables → `INFERENCE_FAILED` (retryable), OOM → `OUT_OF_MEMORY` (retryable); records `AsrDiagnostics` (inferenceMs, RTF). |

## JNI lifecycle (P7.2)

- `System.loadLibrary` in `init`, guarded; `isAvailable()` false until the
  reviewed whisper.cpp revision is vendored.
- `loadModel/unload/transcribe` each map failures to the P0.4 taxonomy
  (`MODEL_MISSING`, `MODEL_LOAD_FAILED`, `INFERENCE_FAILED`, `OUT_OF_MEMORY`).
- Native crashes are caught at the worker boundary (dispatcher
  `catch (Exception)` + `catch (OutOfMemoryError)` in the engine); the UI
  never touches native code.
- Stub symbols in `app/src/main/cpp/native-lib.cpp` (`nativeIsWhisperAvailable`
  → false) keep the CMake/NDK path green until vendoring.

## Model delivery (D15)

APK never bundles weights. Operator installs via file picker (USB/Mac
sideload) → app copies to app-private storage → SHA-256 verified → metadata
sidecar written atomically. Recording/export work before installation;
missing models surface as `MODEL_MISSING` on the Errors screen + a setup
banner (P8), never a blocking dialog.

## Fixtures (P7.6)

Developer fixtures are synthetic WAVs written by `WavStreamWriter`
(`WavValidatorTest` hi/en/mr 500 ms tones + `WhisperAsrEngineTest` 1 s files
with `FakeWhisperBridge` canned text). Clearly labeled, never presented as
field accuracy. Field accuracy (WER/CER per language, code-switch analysis)
requires consented classroom recordings + human references on the 2023 phone
(D4/D11) — tracked in P11/P12, not claimed here.

## Diagnostics (P7.7)

`AsrDiagnostics(modelId, modelVersion, audioDurationMs, inferenceMs, wallMs,
realTimeFactor)` exposed as `engine.lastDiagnostics`. The P11 benchmark
screen aggregates RTF/RAM/battery/thermal per language; logs carry IDs,
counts, durations, error codes — never audio/transcripts (redaction policy).

## Persistence (P7.8)

The P6 dispatcher persists `asr.v1.json` atomically via `ArtifactStore`
and advances `QUEUED_ASR → TRANSCRIBING → TRANSCRIBED` only after validation.
Raw JSON is immutable; re-export creates a new `ExportRecord` (P9).

## Exit gate

Recorder + persistence + ASR survives a classroom-style trial before LLM
work (P10) begins. Emulator scope: pipeline verified with fake bridge;
on-device benchmark (tiny/base/small, RTF/RAM/thermal) pending the 2023
phone — see `MODEL_LICENSES.md` candidates.
