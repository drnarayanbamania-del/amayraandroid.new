# APK Analysis Report — reference maya-v4.15.1 (real build analyzed)

> Reference file: `C:\Users\drnar\Desktop\maya-v4.15.1.apk` (76.2 MB, 717 entries).
> Analyzed via zip listing, binary-XML manifest strings, DEX string mining, and asset
> inspection. Clean-room replica lives in `com.amayra.maya` — no decompiled code.

## Identity
| Field | Value |
|---|---|
| Package | `com.getmaya.android` |
| App label | Maya |
| UI stack | Single-activity Jetpack Compose (Material 3) |
| Native libs | `libLive2DCubismCoreJNI.so`, TFLite, barhopper (ML Kit), androidx.graphics.path |

## Assets (direct evidence of features)
| Asset | Size | Meaning |
|---|---|---|
| `guardian/ecapa6s.tflite` | 42 MB | ECAPA-TDNN speaker embedder, raw waveform [1,96000] → 192-dim |
| `guardian/silero_vad.tflite` | 650 KB | Silero VAD v4 speech gate (LSTM state h/c [2,1,64]) |
| `guardian/cohort.bin` | 150 KB | 200 stranger voiceprints for AS-Norm + logistic calibration |
| `guardian/README.txt` | 3 KB | Documents EER table: 6s concatenated voiced audio → 1.43% EER |
| `wakeword/melspectrogram.tflite` | 1.09 MB | PCM → mel front-end |
| `wakeword/embedding_model.tflite` | 1.3 MB | mel → speech embedding |
| `wakeword/hey_maya.tflite`, `wake_up_maya.tflite` | 638 KB each | keyword classifiers |
| `live2d/maya/Mao.*`, `live2d/friday/Haru.*`, `live2d/venom/Natori.*` | ~30 MB | 3 persona models with expressions + motions |
| `voices/{maya,friday,venom}_*.ogg` | 39 files | Gemini-TTS voice previews (Aoede…Zephyr, Achernar…Zubenelgenubi) |
| `macros/builtin_macros.json` | 7 KB | Builtin macros ("chatgpt image", "gemini image") with `_optional`/`_why` semantics |
| `mlkit_barcode_models/*` | 1 MB | Bundled barcode detection models |

## Manifest capabilities (observed)
Accessibility service (gestures + screenshots), VoiceInteractionService, RecognitionService,
MediaProjection FGS, camera FGS, mic FGS, phone state, ANSWER_PHONE_CALLS, SEND_SMS,
READ_CALL_LOG, contacts, notification listener, boot receiver, FileProvider, exact alarms,
SYSTEM_ALERT_WINDOW, WRITE_SETTINGS, REQUEST_INSTALL_PACKAGES.

## Action contracts (namespace `com.getmaya.android.*`)
CHAT_TEXT, TOGGLE_MIC, SEND_IMAGE, START_SCREEN_SHARE, START_CODING, SEND_COMMAND, SEND_FILE,
TERMUX_RESULT, PLAY_MACRO, SCANNER_ON, INCOMING_CALL, ANSWER_CALL, REJECT_CALL, BOOT_STANDBY,
ENSURE_STANDBY, WAKE_PREF, ASSIST_WAKE, SLEEP, STOP, CAPTURE_MIC, SHARED_AUDIO, SET_AUDIO_MODE,
DRIVING_OFF, REARM_ALERTS, REARM_TOUCH_GUARD, USER_START/USER_STOP, VOICE_REPLY_*,
APPLY_LIVE_PROFILE, oauth:/callback, social.DAILY_STORY, social.QUEUED_POST.

## DEX string evidence (features beyond MAYA-3.0)
- Persona system: `switch_persona` tool with targets maya/friday/venom; "girlfriend mode is a
  Settings toggle, NOT a persona" appears in the tool description.
- Accessibility macro tools: `tap_text`, `tap_coords`, `tap_element`, `type_text`,
  `wait_for_screen_text` (mode disappear, timeout), `open_app_by_name`, `go_back`,
  `lock_screen` ("Needs the accessibility service. Use for 'phone lock kar do'"),
  `screenshot`, plus SIM-picker handling ("pick one with tap_text").
- Hinglish system prompt fragments: "Tum Maya ho, Boss ki personal AI…", PC-relay variants
  ("Ye command Boss ke DOOSRE device (PC) se relay pe aayi hai").
- Voice Guardian runtime: `VoiceGuardian.init()`, `SpeakerEmbedder.kt`, `SpeechGate.kt`,
  `ScoreNormalizer.kt`, `VoiceGuardian.RAW_FALLBACK_SCALE`, MSA corpus replay harness
  (`msa_corpus/`, `msa_results.csv`).
- Vision: screen reading via accessibility or a vision model; captions writer
  ("You write short, catchy social-media captions…"); Todoist-style natural due dates.
- Linear/Vercel integration prompts (personal API token, Hobby plan guidance).

## Risks / limitations for the replica
1. Live2D Cubism Core is proprietary (Live2D Proprietary redist license) — replica ships
   the pluggable renderer + model loader; the user can drop in the SDK + models.
2. Gemini-TTS voice packs (.ogg previews) are reference-app assets; replica uses Android
   TTS now and documents the Gemini TTS voice mapping per persona.
3. ML Kit bundled barcode models are Google-licensed; replica uses the public ML Kit
   Maven artifact (`barcode-scanning` 17.3.0) instead of packaging Google's assets.
4. Speaker/wake-word models are freely redistributable binaries inside the APK — extracted
   into the replica's assets unchanged.
