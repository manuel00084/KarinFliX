package com.karin.streamtv.ui

import android.os.Bundle
import android.view.KeyEvent
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.karin.streamtv.R
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.onActionKey

class CreditsActivity : AppCompatActivity() {

    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_credits)

        scrollView = findViewById(R.id.scroll_view)
        val btnBack = findViewById<TextView>(R.id.btn_back)

        btnBack.setOnClickListener { finish() }
        btnBack.onActionKey { finish() }

        if (DeviceUtils.isTvDevice(this)) {
            btnBack.requestFocus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = com.karin.streamtv.util.GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) {
            return onKeyDown(mapped, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                scrollView.scrollBy(0, -200)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                scrollView.scrollBy(0, 200)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
