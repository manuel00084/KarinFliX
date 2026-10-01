package com.karin.streamtv.karinlink

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.karin.streamtv.R
import com.karin.streamtv.karinlink.protocol.DiscoveredPeer
import com.karin.streamtv.util.GamepadHelper
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Enviar cap├¡tulo a reproducir en otro equipo (celular ÔåÆ TV).
 *
 * Recibe el episodio por extras, muestra los dispositivos Karin Link
 * descubiertos y al tocar uno conecta y comparte el cap├¡tulo: la TV lo
 * reproduce autom├íticamente ([KarinLinkHost] atiende `sync`).
 */
class KarinLinkSendActivity : FragmentActivity() {

    companion object {
        const val EXTRA_TITLE = "send_title"
        const val EXTRA_EPISODE_TITLE = "send_episode_title"
        const val EXTRA_EPISODE_URL = "send_episode_url"
        const val EXTRA_SITE_NAME = "send_site_name"
        const val EXTRA_EMBED_URL = "send_embed_url"

        private const val REQUEST_PICK = 4321
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

    /**
     * Qué hará el envío: parar lo que suena, o dejarlo y esperar turno.
     *
     * Es una decisión del usuario y no un parámetro oculto por el que se entra,
     * porque las dos cosas se piden a propósito en momentos distintos.
     */
    private var playNow: Boolean = true
    private var pendingUpload: PickedVideo? = null
    private lateinit var tvPick: TextView
    private lateinit var etPushToken: EditText

    /**
     * El token de la TV, escrito por el usuario.
     *
     * No se pide por el WebSocket firmado aunque el otro equipo ya esté
     * emparejado: ese token es lo único que protege `/fs`, y repartirlo por la
     * red convertiría a cualquier móvil emparejado en alguien con acceso a
     * todas las carpetas compartidas. Se escribe, como el resto del acceso a
     * archivos.
     */
    private val pushToken: String get() = etPushToken.text.toString().trim()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_tv)

        if (!com.karin.streamtv.util.AppPreferences.isKarinLinkEnabled()) {
            Toast.makeText(this, "KARIN Link apagado: actívalo en Ajustes", Toast.LENGTH_LONG).show()
            finish()
            return
        }

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

        tvPick = findViewById(R.id.tv_send_pick)
        etPushToken = findViewById(R.id.et_push_token)
        findViewById<View>(R.id.tv_send_pick).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // Los vídeos del móvil llegan como content:// y no como ruta, así
                // que se pide un documento en vez de un archivo.
                type = "video/*"
            }
            startActivityForResult(intent, REQUEST_PICK)
        }
        findViewById<View>(R.id.tv_send_pick).onActionKey {
            findViewById<View>(R.id.tv_send_pick).performClick()
        }

        // Los dos botones eligen qué hacer, y luego se toca el dispositivo: es
        // más difícil pulsar por error "reproducir ahora" que "añadir", porque
        // añadir solo encola.
        findViewById<View>(R.id.btn_send_now).setOnClickListener { chooseMode(true) }
        findViewById<View>(R.id.btn_send_now).onActionKey {
            findViewById<View>(R.id.btn_send_now).performClick()
        }
        findViewById<View>(R.id.btn_send_enqueue).setOnClickListener { chooseMode(false) }
        findViewById<View>(R.id.btn_send_enqueue).onActionKey {
            findViewById<View>(R.id.btn_send_enqueue).performClick()
        }
        updateMode()
        updatePickLabel()
    }

    private fun chooseMode(now: Boolean) {
        playNow = now
        updateMode()
    }

    private fun updateMode() {
        findViewById<TextView>(R.id.btn_send_now).alpha = if (playNow) 1f else 0.5f
        findViewById<TextView>(R.id.btn_send_enqueue).alpha = if (playNow) 0.5f else 1f
    }

    private fun updateDeviceList(devices: List<DiscoveredPeer>) {
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
                text = "­ƒô║ ${device.displayName}"
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

    private fun sendTo(device: DiscoveredPeer) {
        val picked = pendingUpload
        if (picked == null) {
            // Sin fichero local se manda el capítulo tal cual: si el otro equipo
            // lo resuelve, suena; si no, el embed de siempre.
            val ok = manager.shareToQueue(
                playNow = playNow,
                itemTitle = if (episodeTitle.isNotBlank()) episodeTitle else title,
                embedUrl = embedUrl,
                episodeUrl = episodeUrl,
                siteName = siteName,
            )
            manager.connectToDevice(device)
            Toast.makeText(
                this,
                if (ok) "Enviando a ${device.displayName}..." else "Se enviará al conectar",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        // Con fichero local hay que subirlo antes de poder encolarlo: la cola
        // necesita una ruta, y esa ruta solo existe en el otro equipo.
        if (pushToken.isBlank()) {
            Toast.makeText(
                this,
                "Falta el token de la TV (está en su pantalla de configuración)",
                Toast.LENGTH_LONG,
            ).show()
            etPushToken.requestFocus()
            return
        }
        Toast.makeText(this, "Subiendo a ${device.displayName}...", Toast.LENGTH_LONG).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val stored = runCatching {
                manager.uploadVideo(device, pushToken, picked.name, picked.title, picked.length) {
                    contentResolver.openInputStream(picked.uri)!!
                }
            }.getOrNull()

            withContext(Dispatchers.Main) {
                if (stored == null) {
                    Toast.makeText(
                        this@KarinLinkSendActivity,
                        "No se pudo subir el vídeo",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@withContext
                }
                manager.shareToQueue(
                    playNow = playNow,
                    itemTitle = picked.title,
                    videoUrl = stored,
                    siteName = siteName,
                    // La TV lo borra al terminar: no se guarda una copia.
                    localFile = true,
                )
                manager.connectToDevice(device)
                pendingUpload = null
                updatePickLabel()
            }
        }
    }

    private fun updatePickLabel() {
        tvPick.text = pendingUpload?.let { "Vídeo: ${it.title}" } ?: "Elegir un vídeo del móvil"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val label = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "video" }
        val length = runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        if (length <= 0) {
            Toast.makeText(this, "No se pudo leer el vídeo", Toast.LENGTH_LONG).show()
            return
        }
        pendingUpload = PickedVideo(uri, label, label, length)
        updatePickLabel()
    }

    private data class PickedVideo(val uri: Uri, val name: String, val title: String, val length: Long)

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
