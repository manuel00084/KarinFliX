package com.karin.streamtv.karinlink

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.karin.streamtv.R
import com.karin.streamtv.util.GamepadHelper
import kotlinx.coroutines.launch

/**
 * Control remoto Karin Link (lado teléfono).
 *
 * - Botonera: D-pad + OK, Atrás, Inicio.
 * - Multimedia: play/pausa, ±10s, volumen, silencio.
 * - [TouchPadView]: 1 dedo mueve el cursor / toque = clic, 2 dedos = scroll.
 * - Teclado: escribe en el campo y pulsa Enviar (o el Enter del teclado).
 *
 * Se abre desde [KarinLinkActivity] con los datos del equipo destino.
 */
class RemoteControlActivity : FragmentActivity() {

    companion object {
        const val EXTRA_HOST = "remote_host"
        const val EXTRA_PORT = "remote_port"
        const val EXTRA_DEVICE_ID = "remote_device_id"
        const val EXTRA_DEVICE_NAME = "remote_device_name"

        /** Ganancia del scroll de la barra vertical (rueda del mouse). */
        private const val SCROLL_GAIN = 3.0f
    }

    private lateinit var manager: KarinLinkManager
    private lateinit var tvStatus: TextView
    private lateinit var etText: EditText
    private lateinit var touchPad: TouchPadView

    private var lastMoveSent = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val targetId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        val targetName = intent.getStringExtra(EXTRA_DEVICE_NAME).orEmpty()
        if (host.isBlank() || port <= 0) {
            Toast.makeText(this, "Destino inválido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvStatus = findViewById(R.id.tv_remote_status)
        etText = findViewById(R.id.et_remote_text)
        touchPad = findViewById(R.id.touch_pad)
        findViewById<TextView>(R.id.tv_remote_target).text =
            if (targetName.isNotBlank()) targetName else "$host:$port"

        manager = KarinLinkManager(this)
        manager.start()
        manager.connectToDevice(
            DiscoveryManager.DiscoveredDevice(
                name = targetName.ifBlank { host },
                host = host,
                port = port,
                deviceId = targetId.ifBlank { "$host:$port" },
            )
        )

        lifecycleScope.launch {
            manager.status.collect { tvStatus.text = it }
        }

        wirePad()
        wireKeys()
        wireMedia()
        wireKeyboard()

        findViewById<View>(R.id.btn_remote_back_screen).setOnClickListener { finish() }
    }

    private fun wirePad() {
        touchPad.onMove = { x, y ->
            val now = System.currentTimeMillis()
            if (now - lastMoveSent > 40) {
                lastMoveSent = now
                manager.linkClient.sendMouseMove(x, y)
            }
        }
        touchPad.onTap = { x, y -> manager.linkClient.sendMouseTap(x, y) }
        touchPad.onScroll = { dx, dy, x, y -> manager.linkClient.sendMouseScroll(dx, dy, x, y) }
        // Barra vertical de scroll: arrastre = rueda del mouse.
        var lastBarSent = 0L
        findViewById<ScrollBarView>(R.id.scroll_bar).onScroll = { dy ->
            val now = System.currentTimeMillis()
            if (now - lastBarSent > 40) {
                lastBarSent = now
                manager.linkClient.sendMouseScroll(0f, dy * SCROLL_GAIN, 0.5f, 0.5f)
            }
        }
    }

    private fun wireKeys() {
        btn(R.id.btn_up).setOnClickListener { key(KeyEvent.KEYCODE_DPAD_UP) }
        btn(R.id.btn_down).setOnClickListener { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        btn(R.id.btn_left).setOnClickListener { key(KeyEvent.KEYCODE_DPAD_LEFT) }
        btn(R.id.btn_right).setOnClickListener { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        btn(R.id.btn_ok).setOnClickListener { key(KeyEvent.KEYCODE_DPAD_CENTER) }
        btn(R.id.btn_remote_back).setOnClickListener { manager.linkClient.sendMedia(RemoteProtocol.CMD_BACK) }
        btn(R.id.btn_remote_home).setOnClickListener { manager.linkClient.sendMedia(RemoteProtocol.CMD_HOME) }
        btn(R.id.btn_del).setOnClickListener { key(KeyEvent.KEYCODE_DEL) }
        btn(R.id.btn_enter).setOnClickListener { key(KeyEvent.KEYCODE_ENTER) }
    }

    private fun wireMedia() {
        btn(R.id.btn_playpause).setOnClickListener { manager.linkClient.sendMedia(RemoteProtocol.CMD_TOGGLE) }
        btn(R.id.btn_rw).setOnClickListener { manager.linkClient.sendMedia(RemoteProtocol.CMD_RW, 10_000L) }
        btn(R.id.btn_ff).setOnClickListener { manager.linkClient.sendMedia(RemoteProtocol.CMD_FF, 10_000L) }
        btn(R.id.btn_vol_down).setOnClickListener { manager.linkClient.sendMedia(RemoteProtocol.CMD_VOL_DOWN) }
        btn(R.id.btn_vol_up).setOnClickListener { manager.linkClient.sendMedia(RemoteProtocol.CMD_VOL_UP) }
        btn(R.id.btn_mute).setOnClickListener { manager.linkClient.sendMedia(RemoteProtocol.CMD_MUTE) }
    }

    private fun wireKeyboard() {
        etText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendText(submit = true)
                true
            } else false
        }
        btn(R.id.btn_send_text).setOnClickListener { sendText(submit = false) }
    }

    private fun sendText(submit: Boolean) {
        val text = etText.text.toString()
        if (text.isEmpty() && !submit) return
        manager.linkClient.sendRemoteText(text, submit)
        if (text.isNotEmpty()) etText.text.clear()
    }

    private fun key(keyCode: Int) = manager.linkClient.sendRemoteKey(keyCode)

    private fun btn(id: Int): Button = findViewById(id)

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
}
