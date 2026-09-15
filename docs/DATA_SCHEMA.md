# Scrappy pedagogical dataset schema v2

## Purpose

Scrappy collects the actual instructional content delivered in a classroom so reviewers can test whether a translation preserves the teacher's pedagogical meaning and form. The primary record is therefore a **teaching utterance/activity**, linked to source audio and provenance. It is not only an audio archive and it is not a generic annotation record.

The dataset supports:

- speech recognition from classroom audio;
- translation training and evaluation, including low-resource languages such as Mundari;
- preservation of pedagogical structure and intent;
- text-to-speech training with reviewed, language-specific text;
- later human comparison of source, translation, and pedagogical metadata.

## Canonical record: `pedagogical_sample.v2`

`records.jsonl` contains one UTF-8 JSON object per coherent teaching utterance or activity. Android may initially populate `source` from ASR and leave `target`/gold fields pending review. A Mac reviewer may add or correct fields in a new immutable gold artifact.

```json
{
  "schema_version": "pedagogical_sample.v2",
  "sample_id": "uuid",
  "session_id": "uuid",
  "sequence_number": 25,
  "source": "बच्चों, पाँच तक गिनती बोलो।",
  "source_language": "hi",
  "target": "",
  "target_language": "unset",
  "translation_status": "pending|machine_draft|human_verified|rejected",
  "grade": 1,
  "subject": "mathematics",
  "topic": "counting",
  "activity": "oral_practice",
  "learning_objective": "number_recognition",
  "pedagogical_form": "instruction",
  "interaction_type": "teacher_to_class",
  "concept_stage": "practice",
  "examples": [],
  "expected_learner_response": "oral counting from one to five",
  "translation_notes": "",
  "tts": {
    "text": "",
    "normalized_text": "",
    "pronunciation_notes": "",
    "speaker_id": "",
    "status": "pending|ready|excluded"
  },
  "audio": {
    "path": "audio/uuid.wav",
    "sha256": "hex",
    "mime": "audio/wav",
    "sample_rate_hz": 16000,
    "channels": 1,
    "duration_ms": 38000
  },
  "asr": {
    "status": "available|unavailable|failed",
    "path": "asr/uuid.v1.json",
    "text": "बच्चों, पाँच तक गिनती बोलो।",
    "model_id": "whisper-model-id",
    "model_version": "1.0",
    "verified": false
  },
  "annotation": {
    "status": "unavailable|available|failed",
    "path": "annotation/uuid.v1.json",
    "claims_verified": false,
    "generated_by": "local-schema-model-id"
  },
  "quality": {
    "rms_db": -22.1,
    "peak_db": -1.2,
    "clipping_detected": false,
    "silence_ratio": 0.04,
    "flags": []
  },
  "provenance": {
    "created_at": "ISO-8601 UTC",
    "recorded_start": "ISO-8601 UTC",
    "recorded_end": "ISO-8601 UTC",
    "input_device": "phone_microphone",
    "code_switching": false,
    "processing_state": "READY_FOR_EXPORT",
    "app_version": "0.1.0"
  },
  "review": {
    "status": "unreviewed|in_review|verified|rejected",
    "reviewer_id": "",
    "reviewed_at": null,
    "source_audio_checked": false,
    "pedagogical_fidelity": "pending|faithful|partially_faithful|unfaithful",
    "notes": ""
  }
}
```

The supplied example is valid with `target` populated by a reviewed Mundari translation and `target_language: "unset"` replaced with `mun` (ISO 639-3 Mundari). Android capture languages remain Hindi (`hi`), English (`en`), and Marathi (`mr`); the export schema supports additional target languages because translation review occurs later.

## Controlled vocabularies

- `source_language`: `hi`, `en`, `mr` in Android v1.
- `target_language`: ISO 639-3 code when known, including `mun` for Mundari; never write a display name such as “Mundari translation” into the target text.
- `activity`: controlled but extensible values such as `oral_practice`, `explanation`, `question_answer`, `demonstration`, `reading`, `group_work`, `assessment`.
- `pedagogical_form`: `instruction`, `question`, `explanation`, `example`, `feedback`, `story`, `song` (do not use arbitrary values without documenting them).
- `concept_stage`: `introduction`, `explanation`, `guided_practice`, `practice`, `assessment`, `recap`.
- `grade`: integer or documented preschool label; do not encode grade in free text when numeric.

If a value is unknown, use `null` or `pending`, never fabricate it. AI-generated metadata is a suggestion and must retain evidence spans and `generated_by` model identity.

## Training views

The export may include derived JSONL views, each retaining `sample_id`:

- `translation_pairs.v1.jsonl`: `{sample_id, source, source_language, target, target_language, grade, subject, topic, activity, learning_objective, pedagogical_form, review}`. Include only `translation_status: human_verified` records for gold training data.
- `tts_pairs.v1.jsonl`: `{sample_id, language, text, normalized_text, pronunciation_notes, speaker_id, audio_path, review}`. Include only reviewed/ready records; never replace original text with normalized text.
- `pedagogy_eval.v1.jsonl`: source, target, metadata, and `pedagogical_fidelity` for evaluating whether translation preserved instructional purpose and form.

## Files and immutability

`manifest.v2.json`, `records.jsonl`, `translation_pairs.v1.jsonl`, `tts_pairs.v1.jsonl`, `pedagogy_eval.v1.jsonl`, `audio/`, `asr/`, `annotation/`, `events/`, and checksums are exported together. Raw audio/ASR never changes. Corrections create a new `gold.v2` artifact linked by `sample_id` and preserve prior values.
