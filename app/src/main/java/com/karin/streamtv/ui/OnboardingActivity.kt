package com.karin.streamtv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.karin.streamtv.R
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.GamepadHelper
import com.karin.streamtv.util.onActionKey

/**
 * Pantalla de primera ejecución. Se muestra una sola vez (tras el splash)
 * y exige aceptar los términos de licencia antes de entrar a la app.
 */
class OnboardingActivity : AppCompatActivity() {

    private var termsRead = false
    private var tutorialRead = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        findViewById<TextView>(R.id.btn_view_terms).apply {
            setOnClickListener { openTerms() }
            onActionKey { openTerms() }
        }

        findViewById<TextView>(R.id.btn_view_tutorial).apply {
            setOnClickListener { openTutorial() }
            onActionKey { openTutorial() }
        }

        findViewById<TextView>(R.id.btn_accept).apply {
            setOnClickListener { acceptAndStart() }
            onActionKey { acceptAndStart() }
        }

        if (DeviceUtils.isTvDevice(this)) {
            findViewById<TextView>(R.id.btn_view_terms)?.requestFocus()
        }
    }

    private fun openTerms() {
        startActivityForResult(Intent(this, TermsAndConditionsActivity::class.java), REQ_TERMS)
    }

    private fun openTutorial() {
        startActivityForResult(Intent(this, TutorialActivity::class.java), REQ_TUTORIAL)
    }

    private fun acceptAndStart() {
        if (!termsRead) {
            openTerms()
            return
        }
        if (!tutorialRead) {
            openTutorial()
            return
        }
        AppPreferences.setFirstRunDone()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_TERMS -> termsRead = true
            REQ_TUTORIAL -> tutorialRead = true
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val mapped = GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) {
            return onKeyDown(mapped, event)
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK ||
            keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
            // No permitir salir sin aceptar.
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val REQ_TERMS = 1
        private const val REQ_TUTORIAL = 2
    }
}