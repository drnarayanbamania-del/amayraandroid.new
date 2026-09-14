package com.amayra.maya

import android.content.Context
import android.content.Intent
import com.amayra.maya.accessibility.ListMacrosTool
import com.amayra.maya.accessibility.LockScreenTool
import com.amayra.maya.accessibility.PlayMacroTool
import com.amayra.maya.accessibility.ScreenInfoTool
import com.amayra.maya.accessibility.TakeScreenshotTool
import com.amayra.maya.voice.tools.GuardianEnrollTool
import com.amayra.maya.voice.tools.GuardianStatusTool
import com.amayra.maya.voice.tools.WakeWordToggleTool
import com.amayra.maya.ai.AiClient
import com.amayra.maya.avatar.AvatarController
import com.amayra.maya.avatar.AvatarRenderers
import com.amayra.maya.avatar.live2d.Live2DAvatarRenderer
import com.amayra.maya.avatar.live2d.Live2DController
import com.amayra.maya.avatar.live2d.Live2DModelRepository
import com.amayra.maya.core.MayaCognitiveCore
import com.amayra.maya.core.MayaLog
import com.amayra.maya.data.MayaDatabase
import com.amayra.maya.data.MayaDatabaseHolder
import com.amayra.maya.events.EventBus
import com.amayra.maya.feature.AssistantAiHolder
import com.amayra.maya.feature.AssistantFeatureHolder
import com.amayra.maya.feature.AssistantVoice
import com.amayra.maya.feature.LateInitExecutable
import com.amayra.maya.feature.Platform
import com.amayra.maya.integration.AnswerCallTool
import com.amayra.maya.integration.OpenClawStartTool
import com.amayra.maya.integration.OpenClawStatusTool
import com.amayra.maya.integration.PcCommandTool
import com.amayra.maya.integration.PcPingTool
import com.amayra.maya.integration.PcRelay
import com.amayra.maya.integration.PcScreenshotTool
import com.amayra.maya.integration.RejectCallTool
import com.amayra.maya.integration.SendSmsTool
import com.amayra.maya.integration.SosTriggerTool
import com.amayra.maya.integration.TermuxCheckTool
import com.amayra.maya.integration.TermuxExecuteTool
import com.amayra.maya.integration.WhatsAppAutoReplyEngine
import com.amayra.maya.integration.WhatsAppSendTool
import com.amayra.maya.memory.SecureStore
import com.amayra.maya.persona.PersonaManager
import com.amayra.maya.settings.SettingsRepository
import com.amayra.maya.tools.AddTaskTool
import com.amayra.maya.tools.BatteryTool
import com.amayra.maya.tools.ClearMemoryTool
import com.amayra.maya.tools.DeviceInfoTool
import com.amayra.maya.tools.OpenAppTool
import com.amayra.maya.tools.VolumeUpTool
import com.amayra.maya.tools.VolumeDownTool
import com.amayra.maya.tools.VibrateTool
import com.amayra.maya.tools.SetAlarmTool
import com.amayra.maya.tools.StartTimerTool
import com.amayra.maya.tools.OpenCalendarTool
import com.amayra.maya.tools.OpenCameraTool
import com.amayra.maya.tools.OpenSettingsTool
import com.amayra.maya.tools.OpenUrlTool
import com.amayra.maya.tools.RememberTool
import com.amayra.maya.tools.ScanBarcodeTool
import com.amayra.maya.tools.ScreenShareTool
import com.amayra.maya.tools.SeeScreenTool
import com.amayra.maya.tools.TimeTool
import com.amayra.maya.tools.TorchTool
import com.amayra.maya.tools.ToolRegistry
import com.amayra.maya.tools.WebSearchTool
import com.amayra.maya.voice.VoiceController
import com.amayra.maya.voice.guardian.VoiceGuardian
import com.amayra.maya.voice.tts.GeminiTtsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * THE single composition root. Every subsystem is constructed here, exactly
 * once, and wired to its collaborators. [MayaApplication] is a thin Android
 * shell that calls [init] and forwards property access; UI reads via
 * `MayaApplication.get(ctx)` as before. No subsystem may be constructed
 * anywhere else.
 */
object AppGraph {
    lateinit var scope: CoroutineScope
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var secure: SecureStore
        private set
    lateinit var db: MayaDatabaseHolder
        private set
    lateinit var ai: AiClient
        private set
    lateinit var registry: ToolRegistry
        private set
    lateinit var voice: VoiceController
        private set
    lateinit var avatar: AvatarController
        private set
    lateinit var live2d: Live2DController
        private set
    lateinit var live2dModels: Live2DModelRepository
        private set
    lateinit var core: MayaCognitiveCore
        private set
    lateinit var guardian: VoiceGuardian
        private set
    lateinit var eventBus: EventBus
        private set
    lateinit var personas: PersonaManager
        private set
    lateinit var pcRelay: PcRelay
        private set

    var initialized = false
        private set

    /** Voice surface the agent core consumes (feature seam). */
    private lateinit var voicePort: AssistantVoice

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Platform seam: services in library modules need to reopen the app UI.
        Platform.register { Intent(app, MainActivity::class.java) }

        // -- foundational ---------------------------------------------------
        eventBus = EventBus()
        settings = SettingsRepository(app)
        secure = SecureStore(app)
        db = MayaDatabase.get(app)
        ai = AiClient(newHttpClient(), settings, secure)
        // Feature-seam adapter: tool modules see vision via AssistantAi without
        // core-ai depending on core-agent (which itself depends on core-ai).
        AssistantAiHolder.ai = object : com.amayra.maya.feature.AssistantAi {
            override suspend fun visionOnce(imageDataUrl: String, question: String): String? =
                ai.visionOnce(imageDataUrl, question)
        }
        personas = PersonaManager(app)
        pcRelay = PcRelay(app)

        // -- voice: TTS engine + persona voice (single resolution owner) ----
        registry = ToolRegistry(app, db, confirmationFlow = { title, details ->
            confirmViaUi(title, details)
        })
        voice = VoiceController(app, onFinalTranscript = { transcript ->
            scope.launch { core.onUserMessage(transcript) }
        }).also { v ->
            v.geminiTts = GeminiTtsClient(
                http = newHttpClient(),
                keyProvider = { secure.getString(SecureStore.KEY_GEMINI) }
            )
            v.personaVoiceProvider = {
                PersonaVoiceResolver.resolve(personas, settings.prefsCache?.ttsModel)
            }
            // Live settings flow, not an init-time snapshot: a TTS-engine or provider
            // switch lands on the next utterance, no process restart needed.
            // handsFree drives the post-reply auto-relisten (same live sync).
            scope.launch {
                settings.prefs.collect {
                    v.enginePreference = it.ttsEngine
                    v.handsFree = it.handsFreeListening
                }
            }
        }
        voicePort = object : AssistantVoice {
            override fun speak(text: String) = voice.speak(text)
            override fun startListening() = voice.startListening()
            override fun stopAll() = voice.stopAll()
        }
        AssistantFeatureHolder.voicePort = voicePort

        // -- avatar ----------------------------------------------------------
        avatar = AvatarController(scope)
        live2dModels = Live2DModelRepository(app)
        live2d = Live2DController(live2dModels, avatar, settings)
        // Live2D style renders via a Cubism engine when one is packaged;
        // otherwise a clearly-labeled placeholder — never fake.
        AvatarRenderers.register(com.amayra.maya.avatar.anime.AnimeAvatarRenderer())
        AvatarRenderers.register(Live2DAvatarRenderer(live2dModels, live2d, settings))

        // -- cognitive core ---------------------------------------------------
        core = MayaCognitiveCore(app, settings, db, secure, eventBus, registry, ai, voicePort, avatar)
        // Warm the synchronous prefs cache at PROCESS START, not only via the
        // Setup Gate: everything that reads prefsCache (autoSpeak voice, SOS
        // number, pc relay, wake-word gate, TTS model) silently no-ops when the
        // app launches without walking onboarding. Idempotent with core.start().
        settings.startCaching(scope)
        AssistantFeatureHolder.personaPort = personas
        AssistantFeatureHolder.sosNumberProvider = { settings.prefsCache?.sosContactNumber }
        AssistantFeatureHolder.postWiredExecutable = object : LateInitExecutable {
            private val engine = WhatsAppAutoReplyEngine(settings, ai)
            override fun isInitialized(): Boolean = true
            override suspend fun execute(ctx: Context, contact: String, text: String): String? =
                engine.onIncoming(ctx, contact, text)
        }
        voice.bindAvatar(
            VoiceController.AvatarHooks(
                onSpeechStart = { text -> avatar.onSpeechStart(text) },
                onSpeechEnd = { avatar.onSpeechEnd() }
            )
        )

        // -- Guardian (models load lazily in its own background thread) ------
        guardian = VoiceGuardian.init(app)

        registerAllTools()
        startBackground()
        initialized = true
        MayaLog.i("CORE", "AppGraph ready")
    }

    private fun registerAllTools() {
        val r = registry
        // Core device tools
        r.register(TimeTool()); r.register(BatteryTool()); r.register(TorchTool())
        r.register(OpenAppTool()); r.register(OpenUrlTool()); r.register(WebSearchTool())
        r.register(DeviceInfoTool()); r.register(RememberTool()); r.register(AddTaskTool())
        r.register(ClearMemoryTool())
        // Termux + OpenClaw + WhatsApp + SOS
        r.register(TermuxExecuteTool()); r.register(TermuxCheckTool())
        r.register(OpenClawStatusTool()); r.register(OpenClawStartTool())
        // PC bridge: drive the PC Amayra assistant over :18789
        r.register(PcCommandTool(settings)); r.register(PcPingTool(settings)); r.register(PcScreenshotTool(settings))
        r.register(WhatsAppSendTool()); r.register(SosTriggerTool())
        // v4.15.1 parity: personas, macros, guardian, calls, screen, barcode, wake
        r.register(personas.SwitchPersonaTool())
        r.register(PlayMacroTool()); r.register(ListMacrosTool()); r.register(LockScreenTool())
        r.register(TakeScreenshotTool()); r.register(ScreenInfoTool())
        r.register(GuardianEnrollTool()); r.register(GuardianStatusTool())
        r.register(WakeWordToggleTool())
        r.register(AnswerCallTool()); r.register(RejectCallTool()); r.register(SendSmsTool())
        r.register(ScreenShareTool()); r.register(SeeScreenTool()); r.register(ScanBarcodeTool())
        // Agent screen-control layer: inspect → act → verify (accessibility)
        r.register(com.amayra.maya.accessibility.InspectScreenTool())
        r.register(com.amayra.maya.accessibility.ClickElementTool())
        r.register(com.amayra.maya.accessibility.TypeTextTool())
        r.register(com.amayra.maya.accessibility.ScrollScreenTool())
        r.register(com.amayra.maya.accessibility.PressBackTool())
        r.register(com.amayra.maya.accessibility.FindElementTool())
        // Quick-action tiles (Tools screen + voice)
        r.register(VolumeUpTool()); r.register(VolumeDownTool()); r.register(VibrateTool())
        r.register(SetAlarmTool()); r.register(StartTimerTool())
        r.register(OpenCalendarTool()); r.register(OpenCameraTool()); r.register(OpenSettingsTool())
    }

    /** Persona load + relay resume: the two launch-time background jobs. */
    private fun startBackground() {
        scope.launch {
            personas.load()
            if (settings.prefsCache?.pcRelayEnabled == true) {
                pcRelay.start(settings.prefsCache?.pcRelayPort ?: PcRelay.DEFAULT_PORT)
            }
        }
        // Keep the TTS engine preference in sync with settings as it changes.
        scope.launch {
            settings.prefs.collect {
                voice.enginePreference = it.ttsEngine
                voice.handsFree = it.handsFreeListening
            }
        }
    }

    /**
     * Route tool-risk confirmations into the UI's pending-confirm dialog.
     *
     * Two leak guards (the debug panel exposed both on-device):
     *  1. A new request SUPERSEDES any pending one — the old continuation is
     *     resumed with `false`, never left hanging forever.
     *  2. An unanswered dialog auto-declines after CONFIRM_TIMEOUT_MS so a
     *     backgrounded/lost dialog cannot wedge the tool and the agent loop.
     */
    private suspend fun confirmViaUi(title: String, details: String): Boolean {
        core.pendingConfirm.value?.answer(false)
        val answered = kotlinx.coroutines.withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val pc = MayaCognitiveCore.PendingConfirm(
                    id = (core.pendingConfirm.value?.id ?: 0) + 1,
                    title = title,
                    details = details
                ) { approved ->
                    core.pendingConfirm.value = null
                    cont.resume(approved) {}
                }
                core.pendingConfirm.value = pc
                cont.invokeOnCancellation { if (core.pendingConfirm.value === pc) core.pendingConfirm.value = null }
            }
        }
        return answered ?: false // timeout → declined; upstream reports the honest failure
    }

    /** Unanswered confirmations auto-decline after this long. */
    private const val CONFIRM_TIMEOUT_MS = 90_000L

    fun newHttpClient() = AiClient.newHttpClient()
}

/**
 * App-module persona voice resolution (replaces the method that used to live
 * on PersonaManager): maps the active persona + persisted voice onto the TTS
 * client's PersonaVoice payload.
 */
private object PersonaVoiceResolver {
    fun resolve(personas: PersonaManager, model: String?): GeminiTtsClient.PersonaVoice {
        val p = personas.active.value
        return GeminiTtsClient.PersonaVoice(
            voice = personas.activeVoice,
            model = model ?: GeminiTtsClient.DEFAULT_MODEL,
            styleHint = p.voiceStyle
        )
    }
}
