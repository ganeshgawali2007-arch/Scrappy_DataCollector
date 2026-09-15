# Scraper — Classroom Capture (offline-first)

Offline-first Android app (Kotlin + Compose) that records classroom speech in
Hindi, English, and Marathi, persists every raw artifact, runs local ASR
(whisper.cpp) and optional local LLM annotation (llama.cpp), and exports a
verified dataset ZIP for Mac review.

See `plan.md` (implementation plan), `todos.md` (execution checklist),
`DECISIONS.md` (pinned decisions), and `docs/ARCHITECTURE.md` (overview).

## Status: P1 skeleton

Buildable offline-capable shell: navigation for Home, New Session, Recording,
Summary, Export, Recovery, Errors, Diagnostics; permission + notification
scaffolding; manual DI + ViewModels; Material3 theme with dark mode;
hi/en/mr string resources; CMake/NDK stub layout. No recording, ASR, or
export logic yet (P2+).

## Toolchain (pinned, see DECISIONS.md D7)

| Component | Version |
|---|---|
| JDK (builds) | 17.0.20.1 (`openjdk@17`) |
| Gradle wrapper | 8.9 (system 9.x is NOT for builds) |
| AGP | 8.7.3 |
| compile/target | 35, min 29 |
| build-tools | 35.0.1, NDK r27c, CMake 3.22.1 |
| SDK root (this host) | `/opt/homebrew/share/android-commandlinetools` |

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug
./gradlew lintDebug
./gradlew testDebugUnitTest
```

Install on emulator or USB device with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
Verify P1: airplane mode launch, navigate all 8 screens, rotate/recreate without crash.

## Offline guarantee

Production `app/src/main/AndroidManifest.xml` contains **no `INTERNET`
permission**. CI fails the build if it appears. The app has no network
dependency; model/whisper fetches happen only on the dev host for benchmarks.

## Package

Pre-release `com.scraper.classroomcapture` (single source in
`app/build.gradle.kts`). Trivial to rename before release.
