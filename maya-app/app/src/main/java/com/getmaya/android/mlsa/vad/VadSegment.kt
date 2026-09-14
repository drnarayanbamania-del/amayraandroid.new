package com.getmaya.android.mlsa.vad

/** Segment of detected speech from the VAD (assets/guardian/silero_vad.tflite). */
data class VadSegment(val startMs: Long, val endMs: Long, val isSpeech: Boolean)
