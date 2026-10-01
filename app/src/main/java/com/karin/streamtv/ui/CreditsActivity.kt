package com.karin.streamtv.ui

import android.os.Bundle
import android.widget.TextView
import com.karin.streamtv.BuildConfig
import com.karin.streamtv.R

class CreditsActivity : ScrollableInfoActivity() {

    override val layoutRes = R.layout.activity_credits

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ultra económico: sin fondo grande, color plano (menos memoria).
        if (com.karin.streamtv.util.AppPreferences.isUltraEconomyMode()) {
            try {
                findViewById<android.view.View>(R.id.bg_art)?.visibility = android.view.View.GONE
            } catch (_: Exception) { }
        }
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        tvVersion.text = "Versión ${BuildConfig.VERSION_NAME}"
    }
}
