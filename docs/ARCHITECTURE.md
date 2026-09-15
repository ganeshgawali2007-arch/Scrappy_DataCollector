# Architecture overview (P1 skeleton → plan.md §3)

```
app/src/main/java/com/scraper/classroomcapture/
  MainActivity.kt          host only; no recording state
  ScraperApp.kt            Application; owns AppContainer
  di/AppContainer.kt       manual DI; repositories/services added in P2–P7
  ui/Routes.kt             8 destinations
  ui/ScraperNav.kt         NavHost + large-control ScreenScaffold
  ui/screens/Screens.kt    Home, New Session, Recording, Summary, Export, Recovery, Errors, Diagnostics
  ui/viewmodel/            one lifecycle-safe VM per screen (rotation-safe)
  ui/theme/Theme.kt        Material3 light/dark baseline
  permissions/             mic/POST_NOTIFICATIONS scaffolding + channels
app/src/main/cpp/          CMake 3.22.1 stub (whisper P7 / llama P10 adapters later)
app/src/main/res/          values (en) + values-hi + values-mr; backup excluded via xml/
```

## Invariants (plan.md §1, enforced from P1)

1. Capture independent of inference — P4 service owns AudioRecord; Compose never owns recording.
2. Raw evidence immutable — P2/P3 introduce versioned artifacts; nothing here deletes.
3. Audio durable before processing — P3 atomic WAV protocol; P6 queues only after commit.
4. Derived data optional — export works with audio alone (P9).
5. Auditable transitions — single state-machine validator (P2), attempt/error/timestamp fields.
6. Offline enforceable — no INTERNET permission (manifest + CI gate).
7. No destructive recovery — explicit migrations, quarantine + repair report, never silent delete.

## State machine (P2 implements; reserved here)

`CREATED → RECORDING → AUDIO_SAVED → QUEUED_ASR → TRANSCRIBING → TRANSCRIBED →`
`QUEUED_LLM → ANNOTATING → ANNOTATED → READY_FOR_EXPORT`, plus retryable `ERROR`.

## Roadmap hooks

- P2: Room entities/DAOs/migrations + repositories behind AppContainer.
- P3: ArtifactStore (atomic writes, SHA-256, reconciliation).
- P4: foreground mic service + WAV writer + notification Stop action.
- P5: AudioDeviceManager (built-in / RØDE / wired / USB, verified display + event log).
- P6: durable queue (claim/lease/heartbeat, backoff, idempotent workers).
- P7: whisper JNI + ModelRegistry + hi/en/mr fixtures + benchmark.
- P9: SAF export (manifest.v1 + JSONL + ZIP verify).
