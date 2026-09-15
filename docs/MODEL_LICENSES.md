# Model licenses and provenance (P7.1)

> Source of truth for native-code revisions and weight provenance. Binaries
> are **never** committed to git (see `models/` note below).

## whisper.cpp (ASR, P7)

- **Pinned revision:** `TBD — vendor at benchmark time` (do not fetch until
  the 2023 phone fingerprint is recorded, D2.2).
- **Candidate upstream:** `ggerganov/whisper.cpp` (MIT license).
- **License:** MIT (code). Model weights have separate licenses — see below.
- **Provenance to record on vendoring:**
  - exact commit SHA, checkout date, applied patches (if any),
  - CMake flags, NDK r27c build log, ABI (`arm64-v8a` release),
  - `SHA-256` of the built `libscraper_native.so` + weight files.
- **Current state (P7 gate):** Kotlin seams + `nativeIsWhisperAvailable=false`
  stub only. `JniWhisperBridge.isAvailable()` is false; jobs fail as
  `MODEL_MISSING` (permanent) without blocking capture/export.

## Whisper weight candidates (D4, accuracy-first)

Evaluate on the actual 2023 phone with classroom-style hi/en/mr recordings:

| Candidate | Upstream | Weight license |
|---|---|---|
| `tiny` multilingual | OpenAI Whisper (via whisper.cpp) | MIT-compatible (OpenAI Whisper MIT) — verify at fetch |
| `base` multilingual | OpenAI Whisper (via whisper.cpp) | Same as above |
| `small` multilingual | OpenAI Whisper (via whisper.cpp) | Same as above |
| Quantized `small`/`medium` trial | whisper.cpp quants | Same + quant script provenance |

- Record per-candidate SHA-256, download URL, license text snapshot, and
  benchmark evidence (WER/CER per language, RTF, RAM, battery, thermal,
  stability) in `FIELD_DEPLOYMENT.md` (P12) before pinning the default.
- Default registry id is `tiny` (placeholder until the benchmark selects the
  most accurate reliably-runnable model per D4.6).

## llama.cpp (LLM annotation, P10)

- **Pinned revision:** `TBD — after the ASR field gate passes` (plan §9).
  Seams land now (P10 gate): `llm/` Kotlin + `nativeIsLlamaAvailable=false`
  stub (see `app/src/main/cpp/native-lib.cpp`); no vendored C++ yet.
- **Candidate upstream:** `ggerganov/llama.cpp` (MIT license).
- **Weights:** GGUF via the same file-picker + SHA-256 flow as ASR (D15):
  `filesDir/scrappy/models/<modelId>.gguf + <modelId>.json`, default id
  `schema-gen`, SHA-256 + byte-size verified on install and on status.
- `JniLlamaBridge.nativeIsLlamaAvailable` stub returns false until vendoring;
  jobs fail as `MODEL_MISSING` (permanent) without blocking capture/export.
- **Provenance to record on vendoring:** exact commit SHA, checkout date,
  patches, CMake flags, NDK r27c build log, ABI (`arm64-v8a` release),
  `SHA-256` of `libscraper_native.so` + GGUF files (same as whisper §).

## Storage

- `models/` (repo root): `README` + checksums only; large `.bin`/`.gguf`
  files live on the operator's Mac/phone, never in git.
- On-device: `filesDir/scrappy/models/<modelId>.bin + <modelId>.json`
  (metadata: version, sha256, byte size, license).
