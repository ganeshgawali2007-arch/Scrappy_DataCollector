# Export format (P9)

Verified offline dataset ZIP. Built in a temp dir, reopen-checked, then
streamed to the SAF destination. Source rows/files are never modified or
deleted; re-export mints a new `exportId` and never overwrites.

## ZIP layout

```text
manifest.v2.json
records.jsonl                    # one pedagogical_sample.v2 per sample
records/<sampleId>.v2.json       # per-sample JSON (same object)
audio/<sampleId>.wav             # 16 kHz mono PCM16, streamed copy
asr/<sampleId>.v1.json           # when transcribed
annotation/<sampleId>.v1.json    # when annotated (AI_SUGGESTION)
events.jsonl                     # device_event.v1 (diagnostics only, no content)
models.json                      # distinct asr:/llm: model identities observed
translation_pairs.v1.jsonl       # empty in Android v1 (no human_verified gold yet)
tts_pairs.v1.jsonl               # empty in Android v1
pedagogy_eval.v1.jsonl           # empty in Android v1
```

## Manifest (`manifest.v2.json`)

`ExportManifestV2`: `export_id`, `created_at` (ISO-8601 UTC), `app_version`,
`schema_version` (`pedagogical_sample.v2`), `record_count`, `files[]`
(`relative_path`, `sha256`, `byte_size`), `records_sha256`, `verification`
(`VERIFIED`).

## Verification (P9.5, D17)

1. Preflight: every sample has audio + readable file + matching SHA-256.
   Failures list `sampleId + code` (`AUDIO_MISSING`, `AUDIO_UNREADABLE`,
   `CHECKSUM_MISMATCH`); export is blocked with a FAILED record.
2. Temp ZIP is reopened: every manifest entry present + SHA-256 matches,
   `records.jsonl` line count == `record_count`, schema_version spot-check,
   `events.jsonl` + `models.json` present.
3. Destination finalize:
   - File / SAF-with-read-back → `VERIFIED`.
   - SAF without read-back → `WRITTEN_UNVERIFIED` (shown as a limitation,
     never labeled VERIFIED).

## Records

`ExportBuilder` maps `Session + ClassroomSample + Artifacts` to
`pedagogical_sample.v2` (see `docs/DATA_SCHEMA.md`): `source` from ASR text
(empty when unavailable — never fabricated), `grade` numeric-or-label,
`asr`/`annotation`/`quality`/`provenance` blocks, `review` unreviewed.
Unknown values use `null`/`pending`. `Vocab.isValidCaptureRecord` holds for
every emitted record.

## Recovery (P9.7–P9.8)

- Temp dirs `<cache>/exports/<exportId>.tmp` + `<exportId>.zip` are deleted
  after finalize (or on failure); interrupted exports leave a FAILED record.
- Destination refusal when the file exists (no silent overwrite).
- Local data kept until explicit user deletion (`ArtifactStore.deleteTree`
  only, never called by export/recovery).
