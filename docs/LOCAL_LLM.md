# Local LLM annotation (P10)

Schema-constrained pedagogical suggestions, on-device only. Recording,
ASR, and export work without the LLM; LLM failure still exports audio +
ASR (P10.8).

## Model delivery (D15)

- GGUF via the same file-picker + SHA-256 flow as ASR: operator sideloads
  from Mac/USB, app copies to `filesDir/scrappy/models/<modelId>.gguf` +
  `<modelId>.json` (version/sha256/bytes/license), verifies, marks installed.
- APK never bundles weights. Default id `schema-gen` until the field gate
  picks the final model. Missing/corrupt → `MODEL_MISSING`/`MODEL_CORRUPT`
  (permanent), surfaced in Diagnostics without blocking capture/export.

## Native seam (P10.1–P10.3)

- Candidate upstream `ggerganov/llama.cpp` (MIT); pinned revision TBD after
  the ASR field gate (see `docs/MODEL_LICENSES.md`). `JniLlamaBridge`
  safe-loads `libscraper_native.so`; `UnsatisfiedLinkError` → unavailable →
  `MODEL_MISSING`. Stub `nativeIsLlamaAvailable=false` until vendoring.
- `LocalLlmEngine : LlmEngine` runs on `Dispatchers.IO` (never main thread),
  concurrency 1 via the dispatcher (P6.3), recording priority (P6.7).
- Diagnostics per run: prompt chars/version, inference ms, wall ms
  (`LlmDiagnostics`; no transcript content in logs).

## Prompt (P10.4, P10.7)

`PromptTemplate.VERSION = annotation-prompt.v1`, `MAX_TOKENS = 512`,
`TEMPERATURE = 0.0`, transcript truncated to 2000 chars. The exact prompt +
model id/version + settings identity persist on the sidecar
(`generated_by = AI_SUGGESTION:<modelId>`, `model_version`,
`prompt_version`) so Mac review can reproduce the suggestion.

## Validation (P10.5–P10.6)

`AnnotationValidator.parseAndValidate` enforces:
- strict JSON (malformed → `OUTPUT_REJECTED`, permanent, never persisted);
- `activity`/`pedagogical_form`/`concept_stage` in `Vocab` (unsupported →
  `OUTPUT_REJECTED`);
- non-empty `evidence_spans`, every `quote` an exact transcript substring
  (anti-hallucination; ungrounded → `OUTPUT_REJECTED`);
- non-blank model identity.
- `insufficient_audio_evidence` (empty transcript or < 3 chars, or model
  returns the sentinel) → `INPUT_INVALID` (permanent) → dispatcher falls
  back to `TRANSCRIBED → READY_FOR_EXPORT` (audio + ASR exportable).

## Queue integration (P6/P10.8)

The dispatcher claims LLM jobs with lease/heartbeat, re-verifies idempotency
(existing `annotation.v1.json` adopted), writes sidecars atomically via
`ArtifactStore`, advances `QUEUED_LLM → ANNOTATING → ANNOTATED`. Retryable
(`INFERENCE_FAILED`/`OUT_OF_MEMORY`) backs off 30s→30min and requeues to
`QUEUED_LLM`; permanent failures fall back to `TRANSCRIBED →
READY_FOR_EXPORT` with `LLM_FAILED_EXPORTABLE` logged.
