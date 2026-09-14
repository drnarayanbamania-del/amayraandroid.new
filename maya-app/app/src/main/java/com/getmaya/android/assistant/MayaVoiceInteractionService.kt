package com.getmaya.android.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** System voice-assistant hook so "Hey Maya"/assist gestures route to Maya. */
class MayaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // TODO: register wake-word model (assets/wakeword/hey_maya.tflite)
    }
}

class MayaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        MayaVoiceInteractionSession(this)
}

class MayaVoiceInteractionSession(context: android.content.Context) :
    VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        // TODO: open the Live2D assist surface and start a Gemini Live session
    }
}

/** Speech recognition service for on-device transcription routing. */
class MayaRecognitionService : android.speech.RecognitionService() {
    override fun onCreate() {
        super.onCreate()
        // TODO: VAD (assets/guardian/silero_vad.tflite) + streaming ASR
    }
}
