# Maya — AI Assistant for Android

A native Android AI assistant (Kotlin + Jetpack Compose, Material 3) reconstructed from
behavioral analysis of the reference APKs — now upgraded to **maya-v4.15.1 parity**
(see `docs/APK_ANALYSIS.md`). Clean-room implementation in the `com.amayra.maya`
namespace — no decompiled code.

**Status: `BUILD SUCCESSFUL`** — debug APK at `C:\maya-build\app\outputs\apk\debug\app-debug.apk` (~105 MB; ships the Voice Guardian + wake-word models)

## v4.15.1 parity features (new)
- **Voice Guardian** — speaker verification with the real ECAPA-TDNN 6 s model, Silero VAD
  speech gate and 200-stranger AS-Norm cohort (assets extracted from the reference APK;
  every component degrades gracefully when missing).
- **Wake word** — offline "Hey Maya" / "Wake up Maya" (mel-spectrogram → embedding →
  keyword TFLite chain) on an always-on microphone foreground service.
- **3 personas** — Maya / Friday / Venom, each with its own system prompt, Gemini-TTS
  voice pack and accent; `switch_persona` is a CONFIRM-gated tool.
- **Gemini TTS** — each persona speaks with its configured prebuilt voice (Aoede, Kore,
  Fenrir…) via the Gemini TTS API, with a natural-language style hint per persona; a
  voice picker + Test button live in Settings → Voice Guardian & Personas, and the
  device TTS remains as the automatic fallback.
- **Accessibility macros** — tap_text / type_text / tap_coords / wait_for_screen_text /
  lock_screen / screenshot / open_app_by_name, with the reference `builtin_macros.json`
  ("chatgpt image", "gemini image") shipped verbatim.
- **Calls & SMS** — answer/reject ringing calls (honest about dialer-role requirements),
  send_sms behind the CONFIRM gate.
- **Barcode scanner** — CameraX + ML Kit screen with a `scan_barcode` tool.
- **PC relay** — JSON-lines TCP bridge on :18789 (OpenClaw gateway contract): PING and
  SEND_COMMAND (routed through Termux) from your PC agent.
- **Screen share** — MediaProjection consent flow wired to the `screen_share` tool.

---

## Quick start

### 1. Open & build
- Android Studio: `File → Open` → this folder. Or CLI: `./gradlew assembleDebug`
- Toolchain (already configured): AGP 9.3.1 · Kotlin 2.2.10 · Gradle 9.5 · compileSdk 37 · minSdk 24
- Build outputs live in `C:\maya-build` (kept off OneDrive to avoid sync file locks — see `settings.gradle.kts`).

### 2. Install
```bash
adb install C:/maya-build/app/outputs/apk/debug/app-debug.apk
```

### 3. Configure an AI provider (Settings → AI)
| Provider | What to enter |
|---|---|
| **Google Gemini** | Provider = Gemini, paste your API key (aistudio.google.com), model e.g. `gemini-2.0-flash` |
| **Groq** | Provider = OpenAI-compatible, Base URL `https://api.groq.com/openai/v1`, key, model e.g. `llama-3.3-70b-versatile` |
| **OpenRouter** | Provider = OpenAI-compatible, Base URL `https://openrouter.ai/api/v1`, key, model e.g. `google/gemini-2.0-flash-001` |
| **OpenAI** | Provider = OpenAI-compatible, Base URL `https://api.openai.com/v1`, key, model e.g. `gpt-4o-mini` |
| **Local (LM Studio/Ollama)** | Base URL `http://<pc-ip>:1234/v1`, key = anything non-blank |

API keys are stored in **EncryptedSharedPreferences** (Android Keystore). They never appear
in logs, chat, backups, or source.

### 4. Grant runtime permissions
- **Notifications** — requested on first launch (drives the foreground service).
- **Microphone** — first tap of the mic button.
- **Notification access** (optional, Settings) — enables WhatsApp auto-reply. The app explains
  why before sending you to the system page.

---

## Features

**Working today**
- Streaming chat with Gemini + any OpenAI-compatible endpoint, multimodal image input (attach → ask)
- Function-calling tool loop: model requests → `ToolRegistry` → risk gate → user confirmation → execution → result fed back
- Tools: torch, battery, device info, open app/URL, web search, time, remember/add-task/clear-memory,
  `termux_execute`/`termux_check`, `openclaw_status`/`openclaw_start`, `whatsapp_send_message`, `sos_trigger`
- Voice: SpeechRecognizer STT + Android TTS with a speak/listen arbiter; auto-speak configurable
- Animated avatar (canvas renderer) wired to the assistant state machine + emotions
  (IDLE/LISTENING/THINKING/SPEAKING/SLEEPING/ERROR + HAPPY/SAD/ANGRY/SURPRISED)
- Persistent history & long-term memory (atomic JSON store; same DAO surface as the original Room design)
- Tool audit log + WhatsApp auto-reply traceability (contact, incoming text, reply, timestamp, daily cap)
- WhatsApp auto-reply with allowlist, quiet hours, daily cap — every reply logged
- SOS (strictly opt-in): siren + dialer pre-filled with your emergency contact — never silent dialing
- Termux via the official `RUN_COMMAND` intent + documented result-file protocol
- OpenClaw gateway probe (`http://localhost:18789`) with graceful "not running" guidance
- Foreground service + structured notification channels
- Diagnostics screen (provider, Termux, WhatsApp, OpenClaw, TTS, battery-optimization guidance)

**Safety model**
- `RiskLevel`: SAFE / DEFAULT / CONFIRM / DANGEROUS — CONFIRM+ tools always ask via an in-app dialog
- Errors are honest: `AiError` taxonomy (Auth/RateLimit/Network/Timeout/Provider/Unsupported) surfaces
  human-readable messages; nothing reports success it cannot prove
- All logs pass through a redacting logger (`MayaLog`)

---

## Integrations setup

### Termux
1. Install Termux (F-Droid build recommended).
2. In Termux run: `pkg install termux-api` is *not* required; instead grant Maya the
   **"Run commands in Termux environment"** permission (Settings → Apps → Special access → 
   or Termux prompts on first use).
3. Maya sends commands via `com.termux.RUN_COMMAND` and reads results from the result file
   passed in `TERMUX.OUTFILE`. Diagnostics → Termux shows status.

### OpenClaw
- Maya detects a running gateway at `http://localhost:18789` (device-local). If down, the
  `openclaw_start` tool walks you through the onboarding (`openclaw onboard --install-daemon`,
  `openclaw gateway`) in a visible Termux session.

### WhatsApp
- **Send**: share-intent based (`whatsapp://send` / `api.whatsapp.com`) — user presses Send;
  Maya never silently messages.
- **Auto-reply**: requires Notification Access; allowlist contacts, quiet hours, daily cap;
  every reply is logged (Tools screen shows the audit trail).

---

## Project layout
```
app/src/main/java/com/amayra/maya/
  ai/          Models, AiProvider, GeminiClient, OpenAiCompatClient, AiClient
  avatar/      Avatar controller + canvas renderer (Live2D pluggable behind same contract)
  core/        MayaCognitiveCore (brain/turn loop), StateBus/AssistantState, service, logger
  data/        JSON-table persistence: messages, memories, tool logs, auto-reply log
  events/      EventBus, MayaNotificationListener
  integration/ TermuxManager, WhatsAppManager(+AutoReplyEngine), OpenClawManager, SOSManager
  memory/      SecureStore (Keystore-backed)
  settings/    SettingsRepository (DataStore) + UserPreferences
  tools/       ToolRegistry, RiskLevel, device tools
  ui/          theme, chat, settings, diagnostics
  voice/       VoiceController (STT+TTS arbiter)
```

## Documentation
- `docs/APK_ANALYSIS.md` — reference APK analysis report
- `docs/ARCHITECTURE.md` — replica architecture & data flow
- `docs/FEATURE_MATRIX.md` — reference vs replica comparison
- `docs/LIVE2D_SETUP.md` — Live2D Cubism SDK download, integration & license guide
- `preview/index.html` — live project dashboard

## Troubleshooting
| Symptom | Fix |
|---|---|
| "Invalid or missing API key" | Settings → AI → paste key for the selected provider |
| "Model not found" | Check exact model id for your provider |
| No speech service | Install Google app / set default assistant; some emulators lack STT |
| Termux "not permitted" | Grant Run Command permission; keep Termux installed (not frozen) |
| Auto-reply never fires | Grant Notification Access; check allowlist, quiet hours, daily cap |
| Background killed | Diagnostics → battery-optimization guidance for your OEM |
