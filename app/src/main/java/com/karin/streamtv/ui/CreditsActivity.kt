package com.karin.streamtv.ui

import android.os.Bundle
import android.widget.TextView
import com.karin.streamtv.BuildConfig
import com.karin.streamtv.R

class CreditsActivity : ScrollableInfoActivity() {

    override val layoutRes = R.layout.activity_credits

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        tvVersion.text = "Versión ${BuildConfig.VERSION_NAME}"
    }
}
