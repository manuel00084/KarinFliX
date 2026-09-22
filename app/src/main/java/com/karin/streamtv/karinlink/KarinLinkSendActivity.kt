package com.karin.streamtv.karinlink

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.karin.streamtv.R
import com.karin.streamtv.util.GamepadHelper
import kotlinx.coroutines.launch

/**
 * Enviar capítulo a reproducir en otro equipo (celular → TV).
 *
 * Recibe el episodio por extras, muestra los dispositivos Karin Link
 * descubiertos y al tocar uno conecta y comparte el capítulo: la TV lo
 * reproduce automáticamente ([KarinLinkHost] atiende `sync`).
 */
class KarinLinkSendActivity : FragmentActivity() {

    companion object {
        const val EXTRA_TITLE = "send_title"
        const val EXTRA_EPISODE_TITLE = "send_episode_title"
        const val EXTRA_EPISODE_URL = "send_episode_url"
        const val EXTRA_SITE_NAME = "send_site_name"
        const val EXTRA_EMBED_URL = "send_embed_url"
    }

    private lateinit var manager: KarinLinkManager
    private lateinit var tvStatus: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var deviceListContainer: LinearLayout

    private var title: String = ""
    private var episodeTitle: String = ""
    private var episodeUrl: String = ""
    private var siteName: String = ""
    private var embedUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_tv)

        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        episodeTitle = intent.getStringExtra(EXTRA_EPISODE_TITLE).orEmpty()
        episodeUrl = intent.getStringExtra(EXTRA_EPISODE_URL).orEmpty()
        siteName = intent.getStringExtra(EXTRA_SITE_NAME).orEmpty()
        embedUrl = intent.getStringExtra(EXTRA_EMBED_URL).orEmpty()

        if (embedUrl.isBlank() && episodeUrl.isBlank()) {
            Toast.makeText(this, "Nada que enviar", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.tv_send_title).text =
            if (episodeTitle.isNotBlank()) episodeTitle else title
        tvStatus = findViewById(R.id.tv_send_status)
        tvEmpty = findViewById(R.id.tv_send_empty)
        deviceListContainer = findViewById(R.id.send_device_list)

        manager = KarinLinkManager(this)
        manager.start()

        lifecycleScope.launch {
            manager.status.collect { tvStatus.text = it }
        }
        lifecycleScope.launch {
            manager.discoveryManager.devices.collect { updateDeviceList(it) }
        }

        findViewById<View>(R.id.btn_send_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_send_refresh).setOnClickListener {
            manager.discoveryManager.stopDiscovery()
            manager.discoveryManager.startDiscovery()
            Toast.makeText(this, "Buscando dispositivos...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDeviceList(devices: List<DiscoveryManager.DiscoveredDevice>) {
        deviceListContainer.removeAllViews()
        tvEmpty.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        devices.forEach { device ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 16, 24, 16)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#B3000000"))
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.bottomMargin = 8
                layoutParams = lp
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener { sendTo(device) }
            }
            val nameText = TextView(this).apply {
                text = "📺 ${device.displayName}"
                setTextColor(Color.WHITE)
                textSize = 18f
            }
            item.addView(nameText)
            val ipText = TextView(this).apply {
                text = "${device.host}:${device.port}"
                setTextColor(Color.LTGRAY)
                textSize = 14f
            }
            item.addView(ipText)
            deviceListContainer.addView(item)
        }
    }

    private fun sendTo(device: DiscoveryManager.DiscoveredDevice) {
        // Se guarda como pendiente y se envía al conectar (onPeerConnected).
        manager.shareEpisode(title, episodeTitle, episodeUrl, siteName, embedUrl)
        manager.connectToDevice(device)
        Toast.makeText(this, "Enviando a ${device.displayName}...", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) return onKeyDown(mapped, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            manager.stop()
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
