package com.amayra.maya.feature

/**
 * Minimal platform seam between core-agent and the Android shell (app module).
 *
 * core-agent cannot depend on the app module (Activity, Application) or on the
 * UI-facing voice controller, so the shell registers implementations here at
 * startup. Unregistered ports fail honestly — no silent no-ops.
 */
object Platform {
    @Volatile var launchMainActivity: () -> Unit = {
        error("Platform.launchMainActivity not registered (app module must call Platform.register)")
    }

    /**
 * Explicit intent that opens the app's MainActivity. Registered by the shell;
 * used by foreground services (wake word, screen capture) for their tap actions.
 */
    @Volatile var mainActivityIntentFactory: () -> android.content.Intent = {
        error("Platform.mainActivityIntentFactory not registered (app module must call Platform.register)")
    }

    /** Registers the launch hooks. Called once from the app module's Application. */
    fun register(intentFactory: () -> android.content.Intent) {
        mainActivityIntentFactory = intentFactory
        launchMainActivity = { /* reserved: services only need the PendingIntent */ }
    }

    fun launchMainActivityIntent(): android.content.Intent = mainActivityIntentFactory()
}

/**
 * Ports the agent core consumes without knowing the concrete implementation.
 * Registered by the app module's composition root ([com.amayra.maya.AppGraph]).
 */
object AssistantFeatureHolder {
    /** Voice control surface (implemented by core-voice's VoiceController). */
    @Volatile var voicePort: AssistantVoice? = null

    /** Deferred executable wired after DI completes (e.g. WhatsApp auto-reply engine). */
    @Volatile var postWiredExecutable: LateInitExecutable? = null

    /** Active persona provider, satisfying MayaCognitiveCore's persona dependency. */
    @Volatile var personaPort: com.amayra.maya.persona.PersonaManager? = null

    /** SOS emergency-contact number (E.164 or raw digits); null before AppGraph wires it. */
    @Volatile var sosNumberProvider: (() -> String?)? = null
}

/** Voice surface consumed by the cognitive core. */
interface AssistantVoice {
    fun speak(text: String)
    fun startListening()
    fun stopAll()
}

/** Something that is initialized after the composition root finishes wiring. */
interface LateInitExecutable {
    fun isInitialized(): Boolean
    suspend fun execute(context: android.content.Context, contact: String, text: String): String?
}
