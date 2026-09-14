package com.getmaya.android.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.getmaya.android.core.LicenseState

/** Where the user pastes their Gemini API key (stored in encrypted prefs). */
class UnlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyField = EditText(this).apply {
            hint = "Your Gemini API key (aistudio.google.com)"
        }
        val save = Button(this).apply {
            text = "Save"
            setOnClickListener {
                LicenseState.setGeminiKey(keyField.text.toString().trim())
                finish()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(TextView(this@UnlockActivity).apply {
                text = "Maya's voice and brain run on Google's Gemini. Your key is stored in encrypted storage on this device and used only to connect to Google's API."
            })
            addView(keyField)
            addView(save)
        })
    }
}
