# maya/ — Multi-Module Restructure (Sep 14, 2026)

The single-module `app/` project was split into the 11-module Gradle layout
requested for the Maya rebuild. Every module compiles, `assembleDebug`
produces `app/build/outputs/apk/debug/app-debug.apk`, and all unit tests pass
(62 tests, 0 failures, `./gradlew testDebugUnitTest`).

## Module map (packages unchanged — imports did not move)

| Module | Contents (all under `com.amayra.maya.*`) |
|---|---|
| `core-data` | `data/` (JsonTable store + DAOs), `memory/SecureStore`, `settings/SettingsRepository`, `events/EventBus`, `core/AssistantState` (`StateBus`, `Emotion`), `core/MayaLog`, `ai/ProviderResolution` (pure helpers, package kept for compat) |
| `core-ai` | `ai/AiClient`, `GeminiClient`, `OpenAiCompatClient`, `Models`, `AiProvider` |
| `core-agent` | `core/MayaCognitiveCore`, `core/OpenAppIntent`, `tools/ToolRegistry`, `tools/AppPackageAliases`, `persona/*`, `feature/*` (new seam), `license/` placeholder |
| `tools-android` | `accessibility/` (service, macros, macro tools), `screen/ScreenCaptureService`, `tools/` (Basic/DeviceAction/QuickAction/Screen), `integration/` (calls, contacts, SOS, WhatsApp) |
| `tools-internet` | `integration/` PC bridge (`PcRelay`, `PcCommandClient`, `PcBridgeTools`), `TermuxManager`, `OpenClawManager` |
| `core-voice` | `voice/VoiceController`, `voice/guardian/*`, `voice/tts/GeminiTtsClient`, `voice/wakeword/*` (engine + service), `voice/tools/VoiceTools` (moved Guardian/WakeWord tools) |
| `feature-live2d` | `avatar/*` (controller, scene, view, holographic), `avatar/live2d/*`, `avatar/anime/*` |
| `app` | UI (`ui/*`, `ToolsScreen`, `ImageUtils`), `MainActivity`, `MayaApplication`, `AppGraph`, services (`core/MayaCoreService`, `events/MayaNotificationListener`), manifest, res, assets |
| `core-license`, `core-security`, `tools-dev` | Placeholder stubs for later phases |

## Dependency graph

```
app ──► everything (composition root)
core-agent ──► core-data, core-ai
core-ai ──► core-data
tools-android / tools-internet / core-voice / feature-live2d ──► core-agent
core-voice ──► tools-android (Runtime screen-share callbacks)
```

## New seams (the only structural code changes)

Everything lives in `core-agent/src/main/java/com/amayra/maya/feature/`:

- `Platform` — app-module intent factory for foreground-service PendingIntents
  (replaces `MainActivity` imports in `WakeWordService`, `ScreenCaptureService`).
  Registered in `AppGraph.init` via `Platform.register { Intent(app, MainActivity::class.java) }`.
- `AssistantVoice` — voice surface for `MayaCognitiveCore` (implemented by
  core-voice's `VoiceController` via an adapter in `AppGraph`).
- `AssistantAi` + `AssistantAiHolder` — vision surface for `SeeScreenTool`
  (adapter over `AiClient.visionOnce` registered in `AppGraph`; core-ai cannot
  depend on core-agent because core-agent depends on core-ai).
- `AssistantFeatureHolder` — `personaPort` (active persona for the system
  prompt), `postWiredExecutable` (WhatsApp auto-reply engine behind
  `LateInitExecutable`), `sosNumberProvider` (SOS tool in tools-android).
- `AvatarPort` — implemented by `AvatarController`; the cognitive core no
  longer references `feature-live2d` types.
- `PersonaVoiceResolver` (app module) — persona→TTS voice mapping moved out of
  `PersonaManager` (core-agent cannot see `GeminiTtsClient.PersonaVoice`).

## Why some files moved further than their package name suggests

- `MayaLog`, `AssistantState`/`StateBus`, `ProviderResolution` → **core-data**:
  leaf utilities used by every layer (core-ai uses MayaLog; SettingsRepository
  uses ProviderResolution; core-agent's cycle would have been circular).
- Guardian/WakeWord tools → **core-voice** (`voice/tools/VoiceTools.kt`):
  they reference `VoiceGuardian`/`WakeWordService`; leaving them in
  tools-android would have created a tools-android → core-voice cycle
  (core-voice already depends on tools-android for screen callbacks).

## Known follow-ups

- `core-license` / `core-security` / `tools-dev` are compile-ready placeholders.
  The recovered `LicenseState` state machine from the maya-app skeleton
  (10-min demo window, LICENSED/NOT_LICENSED, verify endpoints) is the first
  thing to port into `core-license`.
- Termux/OpenClaw tools currently live in `tools-internet`; when `tools-dev`
  comes online, move `integration/Termux*`-adjacent tool classes there.
- OneDrive sync on this machine intermittently locks `build/` directories
  ("Unable to delete directory … merged.dir"). If a build fails with that
  error, `--stop` the daemon, delete the module's `build/`, and retry;
  consider excluding `maya/` from OneDrive sync.
- AGP 9.3.1 has built-in Kotlin: Android modules must NOT apply
  `org.jetbrains.kotlin.android` (only `kotlin.compose` / `kotlin.serialization`
  where needed). The Gradle 9.5 catalog auto-imports `gradle/libs.versions.toml`
  — do not also declare it in `settings.gradle.kts`.
- The machine env has conflicting `ANDROID_SDK_HOME`/`ANDROID_USER_HOME`
  (AGP aborts). Builds here run with `env -u ANDROID_SDK_HOME ./gradlew …`;
  fixing the environment variable permanently would remove that need.
