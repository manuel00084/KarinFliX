package com.karin.streamtv.karinlink

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.karin.streamtv.karinlink.protocol.DiscoveredPeer
import com.karin.streamtv.util.GamepadHelper
import kotlinx.coroutines.launch

/**
 * Control remoto Karin Link (lado tel├®fono).
 *
 * Todo entra por la misma superficie y llega al mismo sitio:
 * - [TouchPadView]: 1 dedo mueve el cursor / toque = clic, 2 dedos = scroll.
 * - Teclado: cada letra se env├¡a a la TV al escribirla, sin pulsar nada.
 *   El bot├│n de la derecha solo manda Enter (confirma) y limpia el campo.
 * - Botonera: D-pad + OK, Atr├ís, Inicio.
 * - Multimedia: play/pausa, ┬▒10s, volumen, silencio.
 *
 * Cursor, scroll y barra comparten un [FrameThrottle]: 1 frame cada 40 ms.
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

        /** Frames por segundo m├íximo hacia la TV (cursor + scroll + barra). */
        private const val FRAME_INTERVAL_MS = 40L
    }

    private lateinit var manager: KarinLinkManager
    private lateinit var tvStatus: TextView
    private lateinit var etText: EditText
    private lateinit var touchPad: TouchPadView

    private val frameThrottle = FrameThrottle(FRAME_INTERVAL_MS)

    /**
     * Cierra el bucle del escritor en vivo: al limpiar el campo propio no se
     * debe mandar una r├ífaga de DEL a la TV.
     */
    private var suspendTextSync = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)

        if (!com.karin.streamtv.util.AppPreferences.isKarinLinkEnabled()) {
            Toast.makeText(this, "KARIN Link apagado: actívalo en Ajustes", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val targetId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        val targetName = intent.getStringExtra(EXTRA_DEVICE_NAME).orEmpty()
        if (host.isBlank() || port <= 0) {
            Toast.makeText(this, "Destino inv├ílido", Toast.LENGTH_SHORT).show()
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
            DiscoveredPeer(
                deviceName = targetName.ifBlank { host },
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
        // Un solo presupuesto de frames para los tres gestos: nunca pueden
        // estar activos a la vez (1 dedo vs 2 dedos vs barra), así que el
        // total sigue siendo 25 frames/s.
        touchPad.onMove = { x, y ->
            if (frameThrottle.allow()) manager.linkClient.sendMouseMove(x, y)
        }
        touchPad.onTap = { x, y -> manager.linkClient.sendMouseTap(x, y) }
        touchPad.onScroll = { dx, dy, x, y ->
            if (frameThrottle.allow()) manager.linkClient.sendMouseScroll(dx, dy, x, y)
        }
        // Barra vertical de scroll: arrastre = rueda del mouse.
        findViewById<ScrollBarView>(R.id.scroll_bar).onScroll = { dy ->
            if (frameThrottle.allow()) {
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
        // Escritura en vivo: cada cambio del campo se traduce al instante en
        // texto tecleado o teclas DEL sobre la TV.
        etText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suspendTextSync) return
                TextEdit.diff(start, before, count, s).forEach { edit ->
                    when (edit) {
                        is TextEdit.Edit.Typed -> manager.linkClient.sendRemoteText(edit.text)
                        is TextEdit.Edit.Deleted -> repeat(edit.count) { key(KeyEvent.KEYCODE_DEL) }
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        etText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitText()
                true
            } else false
        }
        btn(R.id.btn_send_text).setOnClickListener { submitText() }
    }

    /**
     * Confirma lo que ya se ha enviado en vivo: la TV ya tiene el texto, así
     * que aquí solo manda Enter y deja el campo local listo para lo siguiente.
     */
    private fun submitText() {
        suspendTextSync = true
        try {
            etText.text.clear()
        } finally {
            suspendTextSync = false
        }
        key(KeyEvent.KEYCODE_ENTER)
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
