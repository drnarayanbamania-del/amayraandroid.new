# Maya Replica Architecture

## Layers
```
UI (Compose)            Chat / Tools / Settings / Diagnostics
─────────────────────────────────────────────────────────
State                   StateBus (AssistantState + Emotion StateFlows)
─────────────────────────────────────────────────────────
Brain                   MayaCognitiveCore — turn loop:
                          input → memory context → AI stream →
                          tool loop (registry → risk gate → confirm → execute) →
                          response → voice + avatar + persistence
─────────────────────────────────────────────────────────
AI                      AiClient → GeminiClient | OpenAiCompatClient (SSE streaming,
                        function calling, multimodal image parts)
─────────────────────────────────────────────────────────
Voice                   VoiceController (SpeechRecognizer + TTS arbiter)
─────────────────────────────────────────────────────────
Tools                   ToolRegistry (RiskLevel SAFE/DEFAULT/CONFIRM/DANGEROUS,
                        confirmation flow, audit log)
─────────────────────────────────────────────────────────
Integrations            TermuxManager (RUN_COMMAND intent + result files)
                        WhatsAppManager + WhatsAppAutoReplyEngine (share intents +
                        notification feed, allowlist/quiet-hours/cap, audit log)
                        OpenClawManager (gateway probe :18789)
                        SOSManager (siren + pre-filled dialer, opt-in)
─────────────────────────────────────────────────────────
Data                    JSON-table store (atomic writes, mutex):
                        messages / memories / tool_logs / auto_reply
                        SecureStore (EncryptedSharedPreferences, Keystore)
                        SettingsRepository (DataStore preferences)
─────────────────────────────────────────────────────────
Events                  EventBus; MayaNotificationListener → NOTIFICATION events
```

## State machine
`IDLE → LISTENING → PROCESSING → TOOL_EXECUTION → RESPONDING → SPEAKING → IDLE`
with `ERROR(message)` and `SLEEPING` branches. Single source of truth: `StateBus`
(`StateFlow<AssistantState>`); the avatar derives expressions from it.

## Tool execution policy
LLM never executes Android operations directly:
`model tool_call → ToolRegistry.execute → risk gate (CONFIRM/DANGEROUS ⇒ user dialog)
→ executor → ToolResult → audit log → result string back into the conversation`.

## Persistence note
AGP 9's built-in Kotlin compiler cannot run KSP, so Room was replaced by an
equal-surface JSON-table store (`MayaDatabaseHolder`) with atomic tmp-file writes and
mutex serialization. If KSP becomes compatible, the DAO surface maps 1:1 back to Room
entities already defined (`MessageEntity`, `MemoryEntity`, `ToolLogEntity`,
`AutoReplyLogEntity`).

## Build/dir conventions
- Build outputs redirected to `C:\maya-build` via `settings.gradle.kts`
  (`gradle.lifecycle.beforeProject`) because OneDrive locks Gradle's incremental dirs.
- Toolchain: AGP 9.3.1 · Kotlin 2.2.10 · Gradle 9.5 · compileSdk 37 · minSdk 24 ·
  navigation-compose 2.9.5 · datastore 1.1.1 · okhttp 4.12 · kotlinx-serialization.
