package com.getmaya.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.getmaya.android.core.LicenseState

/**
 * Entry activity. Reconstructed from the recovered APK's component list and
 * license identifiers (durationLimitMillis / rateRemaining / rateReset etc.).
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = LicenseState.current()
        val text = TextView(this).apply {
            setPadding(48, 96, 48, 48)
            setTextSize(20f)
            text = buildString {
                append("Maya 4.18.5 (reconstructed build)\n\n")
                append("License: ${if (status.licensed) "LICENSED" else "NOT_LICENSED"}\n")
                append("Remaining talk today: ${status.rateRemaining} ms\n")
                append("Resets at: ${status.rateReset}\n\n")
                append("Gemini key: ${if (status.hasGeminiKey) "set" else "missing"}")
            }
        }
        val talk = Button(this).apply {
            text = "Talk"
            setOnClickListener {
                val gate = LicenseState.checkTalkAccess()
                if (gate.allowed) {
                    // TODO: start Gemini Live voice session (see core/GeminiLiveClient)
                    Toast.makeText(this@MainActivity, "Voice session would start (sessionMs=${gate.sessionMs})", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, gate.reason, Toast.LENGTH_LONG).show()
                }
            }
        }
        val settings = Button(this).apply {
            text = "Settings / Gemini key"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, com.getmaya.android.ui.UnlockActivity::class.java))
            }
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(text)
            addView(talk)
            addView(settings)
        }
        setContentView(layout)
    }
}
