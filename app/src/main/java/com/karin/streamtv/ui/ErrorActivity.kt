package com.karin.streamtv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.karin.streamtv.R
import com.karin.streamtv.util.onActionKey

/**
 * Pantalla de error con la imagen de fallo. Se abre cuando la página
 * no responde, no hay enlace de video o todos los servidores fallan.
 */
class ErrorActivity : AppCompatActivity() {

    private var embedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error)

        embedUrl = intent.getStringExtra("embed_url")

        findViewById<TextView>(R.id.tv_error_message).text =
            intent.getStringExtra("message") ?: "No se pudo cargar el video o la página."

        findViewById<TextView>(R.id.btn_error_retry).apply {
            visibility = if (embedUrl.isNullOrBlank()) View.GONE else View.VISIBLE
            setOnClickListener { retry() }
            onActionKey { retry() }
        }

        findViewById<TextView>(R.id.btn_error_back).apply {
            setOnClickListener { finish() }
            onActionKey { finish() }
        }
    }

    private fun retry() {
        val url = embedUrl
        if (url.isNullOrBlank()) return
        startActivity(
            Intent(this, EmbedWebViewActivity::class.java).putExtra("embed_url", url)
        )
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            retry()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}