package com.karin.streamtv.ui

import android.graphics.Color
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.karin.streamtv.R
import com.karin.streamtv.util.onActionKey

class TutorialActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tutorial)

        val btnBack = findViewById<TextView>(R.id.btn_back)
        btnBack.setOnClickListener { finish() }
        btnBack.onActionKey { btnBack.performClick() }

        applyHighContrastIfNeeded()
    }

    private fun applyHighContrastIfNeeded() {
        try {
            val am = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return
            val method = am.javaClass.getMethod("isHighTextContrastEnabled")
            val enabled = method.invoke(am) as? Boolean ?: false
            if (enabled) {
                val btnBack = findViewById<TextView>(R.id.btn_back)
                btnBack?.setTextColor(Color.WHITE)
            }
        } catch (_: Exception) { }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK || keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
