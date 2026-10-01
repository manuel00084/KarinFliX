package com.karin.streamtv.ui

import android.os.Bundle
import com.karin.streamtv.R

class TermsAndConditionsActivity : ScrollableInfoActivity() {
    override val layoutRes = R.layout.activity_terms

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ultra económico: sin fondo grande, color plano (menos memoria).
        if (com.karin.streamtv.util.AppPreferences.isUltraEconomyMode()) {
            try {
                findViewById<android.view.View>(R.id.bg_art)?.visibility = android.view.View.GONE
            } catch (_: Exception) { }
        }
    }
}
