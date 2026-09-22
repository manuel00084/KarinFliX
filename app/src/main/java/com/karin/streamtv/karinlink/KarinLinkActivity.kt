package com.karin.streamtv.karinlink

import android.content.Intent
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
import kotlinx.coroutines.launch

class KarinLinkActivity : FragmentActivity() {

    private lateinit var karinLink: KarinLinkManager
    private lateinit var tvStatus: TextView
    private lateinit var tvDevices: TextView
    private lateinit var deviceListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_karinlink)

        karinLink = KarinLinkManager(this)
        karinLink.start()

        karinLink.onPlaybackRequest = { title, epUrl, embed, site ->
            runOnUiThread {
                if (embed.isNotBlank()) {
                    finish()
                    val intent = Intent(this, com.karin.streamtv.ui.EmbedWebViewActivity::class.java)
                    intent.putExtra("embed_url", embed)
                    intent.putExtra("video_title", title)
                    intent.putExtra("episode_url", epUrl)
                    intent.putExtra("episode_number", 0)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Recibido: $title", Toast.LENGTH_SHORT).show()
                }
            }
        }

        tvStatus = findViewById(R.id.tv_karinlink_status)
        tvDevices = findViewById(R.id.tv_karinlink_devices)
        deviceListContainer = findViewById(R.id.device_list_container)

        findViewById<View>(R.id.btn_refresh).setOnClickListener { refreshDevices() }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_karinlink_config).setOnClickListener {
            startActivity(Intent(this, KarinLinkConfigActivity::class.java))
        }

        refreshDevices()
        observeState()
    }

    private fun observeState() {
        lifecycleScope.launch {
            karinLink.isEnabled.collect { enabled ->
                tvStatus.text = if (enabled) karinLink.status.value else "KARIN Link desactivado"
            }
        }
        lifecycleScope.launch {
            karinLink.status.collect { status ->
                tvStatus.text = status
            }
        }
        lifecycleScope.launch {
            karinLink.discoveryManager.devices.collect { devices ->
                updateDeviceList(devices)
            }
        }
    }

    private fun updateDeviceList(devices: List<DiscoveryManager.DiscoveredDevice>) {
        deviceListContainer.removeAllViews()

        if (devices.isEmpty()) {
            tvDevices.text = "No se encontraron dispositivos"
            tvDevices.visibility = View.VISIBLE
            return
        }

        tvDevices.visibility = View.GONE
        devices.forEach { device ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(24, 16, 24, 16)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#B3000000")) // negro traslúcido
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 8
                layoutParams = lp
            }

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener { karinLink.connectToDevice(device) }
            }

            val nameText = TextView(this).apply {
                text = device.displayName
                setTextColor(Color.WHITE)
                textSize = 18f
            }
            info.addView(nameText)

            val ipText = TextView(this).apply {
                text = "${device.host}:${device.port}"
                setTextColor(Color.LTGRAY)
                textSize = 14f
            }
            info.addView(ipText)
            row.addView(info)

            val remoteBtn = TextView(this).apply {
                text = "🎮"
                textSize = 24f
                setPadding(24, 12, 24, 12)
                isFocusable = true
                isFocusableInTouchMode = true
                contentDescription = "Control remoto de ${device.displayName}"
                setOnClickListener {
                    val intent = Intent(this@KarinLinkActivity, RemoteControlActivity::class.java).apply {
                        putExtra(RemoteControlActivity.EXTRA_HOST, device.host)
                        putExtra(RemoteControlActivity.EXTRA_PORT, device.port)
                        putExtra(RemoteControlActivity.EXTRA_DEVICE_ID, device.deviceId)
                        putExtra(RemoteControlActivity.EXTRA_DEVICE_NAME, device.displayName)
                    }
                    startActivity(intent)
                }
            }
            row.addView(remoteBtn)

            deviceListContainer.addView(row)
        }
    }

    private fun refreshDevices() {
        karinLink.discoveryManager.stopDiscovery()
        karinLink.discoveryManager.startDiscovery()
        Toast.makeText(this, "Buscando dispositivos...", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = com.karin.streamtv.util.GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) {
            return onKeyDown(mapped, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        karinLink.stop()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}