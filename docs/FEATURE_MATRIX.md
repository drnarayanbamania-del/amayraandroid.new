# Reference vs Replica — Feature Matrix

Legend: ✅ implemented & verified at compile/run level · 🟡 closest legal equivalent · ⬜ not yet (documented blocker) · 🧩 INFERRED (not observable in the analyzed reference build)

> Reference analyzed: **maya-v4.15.1** (real build — see `APK_ANALYSIS.md`). Earlier MAYA-3.0-based matrix rows are retained below the v4.15.1 section.

## v4.15.1 parity additions

| Area | Reference behavior | Replica implementation | Status |
|---|---|---|---|
| Voice Guardian | ECAPA 6s + Silero VAD + 200-stranger AS-Norm cohort; 1.43% EER; graceful per-asset degradation | `voice/guardian/`: SpeakerEmbedder (raw-waveform + fbank autodetect), SpeechGate (Silero v4 LSTM state + DSP fallback), ScoreNormalizer (cohort.bin parser, top-K AS-Norm, calibrated logistic), VoiceGuardian (voiced-window assembly, enrollment, verification). Models extracted into assets | ✅ |
| Wake word | hey_maya/wake_up_maya TFLite chain (mel → embedding → keyword) on always-on mic FGS | `voice/wakeword/`: WakeWordEngine (3-model chain, shape introspection, streak smoothing) + WakeWordService (16 kHz loop, mic FGS, wakelock, Guardian piggyback) | ✅ |
| Personas | Maya/Friday/Venom with own Live2D model + Gemini voice pack + Hinglish prompts; `switch_persona` CONFIRM-gated; girlfriend mode = settings, not persona | `persona/`: Persona definitions (voice packs, prompts, hues), PersonaManager (DataStore persistence), SwitchPersonaTool; prompt overlay in cognitive core | ✅ |
| Accessibility macros | tap_text/type_text/tap_coords/wait_for_screen_text/go_back/lock_screen/screenshot/open_app_by_name; builtin_macros.json with _optional/_why; SIM-picker guidance | `accessibility/`: MayaAccessibilityService (tree dump, gestures, API-30 screenshot, GLOBAL_ACTION_LOCK_SCREEN), MacroEngine (full step set, param substitution, optional-step semantics, cancel flag), MacroTools (play_macro/list_macros/lock_screen/take_screenshot/screen_info). builtin_macros.json shipped verbatim | ✅ |
| Calls | INCOMING_CALL/ANSWER_CALL/REJECT_CALL contracts; caller announce | `integration/CallManager.kt`: TelecomManager answer/reject with honest SecurityException reporting; answer_call/reject_call/send_sms tools | ✅ |
| Screen share | START_SCREEN_SHARE | Full pipeline: consent dialog → ScreenCaptureService (mediaProjection FGS, VirtualDisplay + ImageReader latest-frame holder) → see_screen tool pulls the newest frame as JPEG and gets a vision-model description via AiClient.visionOnce; screen_share starts/stops capture and reports honestly when consent is denied | ✅ |
| Barcode | ML Kit bundled models + Scanner | CameraX + ML Kit maven artifact; scanner screen + scan_barcode tool via StateBus | ✅ |
| PC relay | SEND_COMMAND/SEND_FILE/TERMUX_RESULT + OpenClaw gateway :18789 | `integration/PcRelay.kt`: JSON-lines TCP bridge on 18789; PING + SEND_COMMAND routed through TermuxManager with honest failures | ✅ |
| SOS | not observed in 4.15.1 | opt-in siren + pre-filled dialer (retained from 3.0 parity) | ✅ |
| Live2D Cubism | native lib + 3 persona models (Haru/Mao/Natori) | pluggable Live2D renderer contract + loader; models not bundled (proprietary license — see LIVE2D_SETUP.md) | 🟡 |
| Gemini-TTS voices | 39 preview .ogg files (Aoede…Zubenelgenubi) | Gemini TTS wired: per-persona voice + style hint via the interactions API with a generateContent fallback; 24 kHz PCM AudioTrack playback; per-persona voice picker + Test button in Settings; falls back to Android TTS on failure | ✅ |
| Permissions center | Permissions screen: one card per capability (assistant, mic, camera, calls, location, contacts, SMS, gallery, answer/manage calls, Bluetooth) + special accesses (notifications, notification access, accessibility, battery, overlay, screen-capture test), live Granted/Grant state | `ui/permissions/PermissionsScreen.kt`: runtime groups via RequestMultiplePermissions; special-access deep links to exact system panels with app-page fallback; state re-checks on resume; manifest gains location/media/BT/ASSISTANT | ✅ |
| First-run setup | (reference opens straight to chat) | `ui/setup/SetupGateScreen.kt`: welcome → encrypted Gemini key entry (skippable) → mic permission primer; `setupComplete` gate in SettingsRepository; proactive greeting suppressed by MayaCognitiveCore until setup done; setup route forces on fresh installs, bottom nav hidden | ✅ |

## MAYA-3.0 parity (retained)

| Area | Reference behavior | Replica implementation | Status |
|---|---|---|---|
| Package/namespace | `com.sakeera.ai` (3.0) / `com.getmaya.android` contracts (4.15.1) | `com.amayra.maya` | ✅ |
| UI stack | Single-activity Compose, Chat + Settings | Single-activity Compose, Chat/Tools/Settings/Diagnostics, bottom nav | ✅ |
| Streaming chat | MayaaAiClient chat+stream | AiClient → Gemini/OpenAI-compat SSE streaming | ✅ |
| Providers | (server-side in reference) | Gemini + OpenAI-compatible (Groq/OpenRouter/OpenAI/local), user-configurable | ✅ |
| Multimodal image input | SEND_IMAGE action | Photo picker → downscale → inline_data / image_url parts | ✅ |
| Tool system | ToolRegistry/ToolExecutor/RiskLevel | ToolRegistry + RiskLevel + confirmation dialog + audit log | ✅ |
| Torch/Battery/Time/Storage/OpenApp/OpenUrl/WebSearch/Dialer tools | observed | implemented (same set + extras) | ✅ |
| Memory store | MemoryStore + task status | MemoryDao (facts/preferences/tasks, pin/importance/done) + chat-context injection | ✅ |
| Clear memory | ClearMemory tool | clear_memory tool + Tools screen button | ✅ |
| Proactive/initiative engines | GoalManager/InitiativeEngine/ReflectionEngine/AttentionEngine | proactive greeting + event-driven hooks (simplified) | 🟡 |
| Offline reasoning | LocalReasoner | honest offline error states; no fabricated replies | 🟡 |
| Voice STT/TTS | VoiceController + SpeechArbiter | VoiceController with speak/listen arbiter | ✅ |
| Assistant state machine | StateManager/Focus/InternalState | StateBus + AssistantState/Emotion | ✅ |
| Foreground service | MayaCoreService | MayaCoreService + channels | ✅ |
| Notification listener | MayaNotificationListener | MayaNotificationListener → EventBus → auto-reply | ✅ |
| Live2D Cubism avatar | native lib in v4.15.1 (🧩 for 3.0 build) | MayaAvatarController contract + canvas renderer; Live2D pluggable behind same interface | 🟡 |
| WhatsApp send | SEND actions | share-intent send; user always presses Send | 🟡 |
| WhatsApp auto-reply | auto reply + logs | allowlist/quiet-hours/daily-cap/logged replies | ✅ |
| WhatsApp delete-for-everyone | delete tool | not reliably possible without accessibility; honest failure reported | 🟡 |
| Termux | deep integration (v4.15.1 🧩) | RUN_COMMAND intent + result-file protocol + permission diagnostics | 🟡 |
| OpenClaw | gateway :18789 (🧩) | gateway probe + guided onboarding via Termux | 🟡 |
| Screen share | START_SCREEN_SHARE (🧩) | not yet — needs MediaProjection UI flow | ⬜ |
| Calls | INCOMING/ANSWER/REJECT (🧩) | dialer pre-fill only; no silent call control | 🟡 |
| SOS | (not observed) 🧩 | opt-in siren + pre-filled emergency dialer + settings | ✅ |
| Macros | PLAY_MACRO (🧩) | not yet | ⬜ |
| Backup/export | memory/backup functionality 🧩 | not yet | ⬜ |
| Standby/wake | BOOT_STANDBY/ENSURE_STANDBY/WAKE_PREF 🧩 | Sleeping state + boot-started foreground service | 🟡 |
| Honest errors | error states | AiError taxonomy + human messages; never fake success | ✅ |
| Secrets | — | Keystore EncryptedSharedPreferences; redacting logger | ✅ |
