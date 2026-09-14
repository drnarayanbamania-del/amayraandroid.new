# Maya — Reconstructed Android Project

Rebuilt from the `maya_recovery_pack` (APK maya-4.18.5, package `com.getmaya.android`, AGP 8.5.2,
embedded git rev `c4f697503d061f3d2e93c85acafac3080940249c`).

## What's in this build

- Complete Gradle project skeleton (AGP 8.5.2 / Gradle 8.7 / Kotlin 2.0.20), matching the
  original package structure and all recovered components in the manifest.
- **All 184 recovered runtime assets** under `app/src/main/assets/`:
  Live2D models (`live2d/maya|friday|venom`), wake-word models (`wakeword/*.tflite`),
  voice-guardian/VAD models (`guardian/`), voice assets (`voices/`), macros,
  ML Kit barcode models, Live2D shader sources.
- License/demo state machine (`core/LicenseState.kt`) implementing the recovered identifiers
  (`durationLimitMillis`, `rateRemaining`, `rateReset`, `rateLimited`, `licensed`/`NOT_LICENSED`, ...)
  with local 10-minutes-free-per-day logic and hooks for your backend
  (`/api/license/verify-android`, `/api/v1/lease`, ...).
- Gemini client (`core/GeminiLiveClient.kt`) using the user's own API key stored in app prefs.
- `find_tool` / `run_tool` registry (`core/ToolRegistry.kt`) with CONFIRM_REQUIRED gating.
- Stub implementations of every recovered service/receiver (voice interaction, recognition,
  accessibility, notification listener, Termux bridge, boot/phone/social receivers, job workers).

## What's NOT recovered / next steps

1. **Original source is gone.** An APK contains no `.kt` files — this is a functional shell, not a
   byte-for-byte restoration. The exact original persistence, prompt strings, and backend logic are
   not provable from string extraction.
2. Voice pipeline: implement wake-word inference (tflite) → VAD → Gemini Live WebSocket streaming.
3. Live2D rendering: add the Live2D Cubism SDK (native libs were in the APK; licensing applies).
4. Termux/WhatsApp/GitHub tool handlers: fill in `ToolRegistry` implementations one by one.
5. Backend: point `LicenseState.verifyWithBackend` at your own deployment of the recovered endpoints.
6. Build: `./gradlew assembleDebug` (needs Android SDK 35; no gradlew scripts included — generate
   with `gradle wrapper` or open in Android Studio).
