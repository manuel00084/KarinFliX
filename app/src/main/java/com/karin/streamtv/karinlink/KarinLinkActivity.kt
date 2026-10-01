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
import com.karin.streamtv.karinlink.protocol.DiscoveredPeer
import kotlinx.coroutines.launch

class KarinLinkActivity : FragmentActivity() {

    private lateinit var karinLink: KarinLinkManager
    private lateinit var tvStatus: TextView
    private lateinit var tvDevices: TextView
    private lateinit var deviceListContainer: LinearLayout
    private lateinit var tvRemoteTarget: TextView
    private lateinit var rowRemoteEntry: View
    private lateinit var switchLink: com.google.android.material.switchmaterial.SwitchMaterial
    private var linkStarted = false
    private var suppressSwitchCallback = false
    private var stateObserved = false
    private var discoveredDevices: List<DiscoveredPeer> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_karinlink)

        karinLink = KarinLinkManager(this)
        tvStatus = findViewById(R.id.tv_karinlink_status)
        tvDevices = findViewById(R.id.tv_karinlink_devices)
        deviceListContainer = findViewById(R.id.device_list_container)
        tvRemoteTarget = findViewById(R.id.tv_remote_target)
        rowRemoteEntry = findViewById(R.id.row_remote_entry)
        switchLink = findViewById(R.id.switch_karin_link)

        suppressSwitchCallback = true
        switchLink.isChecked = com.karin.streamtv.util.AppPreferences.isKarinLinkEnabled()
        suppressSwitchCallback = false
        switchLink.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            onLinkSwitchToggled(checked)
        }

        findViewById<View>(R.id.btn_refresh).setOnClickListener {
            if (!com.karin.streamtv.util.AppPreferences.isKarinLinkEnabled()) {
                Toast.makeText(this, "KARIN Link apagado: enciéndelo con el interruptor", Toast.LENGTH_SHORT).show()
            } else {
                refreshDevices()
            }
        }
        rowRemoteEntry.setOnClickListener { openRemoteEntry() }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_karinlink_config).setOnClickListener {
            startActivity(Intent(this, KarinLinkConfigActivity::class.java))
        }

        if (!com.karin.streamtv.util.AppPreferences.isKarinLinkEnabled()) {
            showDisabledUI()
            return
        }
        startLinkFlow()
    }

    private fun startLinkFlow() {
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
        karinLink.start()
        linkStarted = true
        rowRemoteEntry.visibility = View.VISIBLE
        refreshDevices()
        if (!stateObserved) {
            stateObserved = true
            observeState()
        }
    }

    private fun showDisabledUI() {
        tvStatus.text = "KARIN Link desactivado (enciéndelo con el interruptor)"
        tvDevices.text = "KARIN Link está apagado"
        tvDevices.visibility = View.VISIBLE
        deviceListContainer.removeAllViews()
        rowRemoteEntry.visibility = View.GONE
        Toast.makeText(this, "KARIN Link apagado: enciéndelo con el interruptor", Toast.LENGTH_LONG).show()
    }

    private fun onLinkSwitchToggled(checked: Boolean) {
        com.karin.streamtv.util.AppPreferences.setKarinLinkEnabled(checked)
        switchLink.contentDescription = "KARIN Link: ${if (checked) "activado" else "desactivado"}"
        switchLink.announceForAccessibility(switchLink.contentDescription)
        try {
            if (checked) {
                // El servicio mantiene el socket vivo cuando la app pasa a
                // segundo plano; el host solo hace el bind.
                KarinLinkService.start(this)
                KarinLinkHost.start(this)
                if (!linkStarted) {
                    startLinkFlow()
                }
                Toast.makeText(this, "KARIN Link encendido", Toast.LENGTH_SHORT).show()
            } else {
                if (linkStarted) {
                    karinLink.stop()
                    linkStarted = false
                }
                KarinLinkHost.stop()
                KarinLinkService.stop(this)
                showDisabledUI()
                Toast.makeText(this, "KARIN Link apagado", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) { }
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

    private fun updateDeviceList(devices: List<DiscoveredPeer>) {
        discoveredDevices = devices
        deviceListContainer.removeAllViews()
        updateRemoteEntryHint()

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
                    setColor(Color.parseColor("#B3000000")) // negro trasl├║cido
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
                // Sin este color el mando salía negro sobre la tarjeta negra
                // translúcida, es decir invisible: justo el botón que hace falta
                // para controlar el otro equipo.
                setTextColor(Color.WHITE)
                textSize = 24f
                setPadding(24, 12, 24, 12)
                isFocusable = true
                isFocusableInTouchMode = true
                contentDescription = "Control remoto de ${device.displayName}"
                setOnClickListener {
                    openRemote(device.host, device.port, device.deviceId, device.displayName)
                }
            }
            row.addView(remoteBtn)

            deviceListContainer.addView(row)
        }
    }

    private fun updateRemoteEntryHint() {
        val saved = com.karin.streamtv.util.AppPreferences.getLastRemoteTarget()
        tvRemoteTarget.text = when {
            discoveredDevices.size == 1 -> "En la red: ${discoveredDevices.first().displayName}"
            discoveredDevices.isNotEmpty() -> "${discoveredDevices.size} dispositivos en la red"
            saved != null -> "Último destino: ${saved.deviceName}"
            else -> "Sin destino todavía"
        }
    }

    /**
     * Entrada fija al mando. No depende de que haya filas de dispositivo:
     * usa el primero descubierto o, si no hay, el último destino guardado.
     */
    private fun openRemoteEntry() {
        val device = discoveredDevices.firstOrNull()
        if (device != null) {
            openRemote(device.host, device.port, device.deviceId, device.displayName)
            return
        }
        val saved = com.karin.streamtv.util.AppPreferences.getLastRemoteTarget()
        if (saved != null) {
            openRemote(saved.host, saved.port, saved.deviceId, saved.deviceName)
            return
        }
        Toast.makeText(this, "Sin ningún equipo en la red: pulsa Actualizar", Toast.LENGTH_LONG).show()
        refreshDevices()
    }

    private fun openRemote(host: String, port: Int, deviceId: String, deviceName: String) {
        com.karin.streamtv.util.AppPreferences.saveLastRemoteTarget(
            com.karin.streamtv.util.AppPreferences.RemoteTarget(host, port, deviceId, deviceName)
        )
        val intent = Intent(this, RemoteControlActivity::class.java).apply {
            putExtra(RemoteControlActivity.EXTRA_HOST, host)
            putExtra(RemoteControlActivity.EXTRA_PORT, port)
            putExtra(RemoteControlActivity.EXTRA_DEVICE_ID, deviceId)
            putExtra(RemoteControlActivity.EXTRA_DEVICE_NAME, deviceName)
        }
        startActivity(intent)
    }

    private fun refreshDevices() {
        karinLink.discoveryManager.stopDiscovery()
        karinLink.discoveryManager.startDiscovery()
        Toast.makeText(this, "Buscando dispositivos...", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        val enabled = com.karin.streamtv.util.AppPreferences.isKarinLinkEnabled()
        suppressSwitchCallback = true
        switchLink.isChecked = enabled
        suppressSwitchCallback = false
        if (!enabled && linkStarted) {
            karinLink.stop()
            linkStarted = false
            showDisabledUI()
        } else if (enabled && !linkStarted) {
            startLinkFlow()
        }
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
        if (linkStarted) {
            karinLink.stop()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}