package com.karin.streamtv.karinlink

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.karin.streamtv.R
import com.karin.streamtv.ui.EmbedWebViewActivity
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Dialogo para compartir el episodio seleccionado a otro dispositivo:
 *  - LAN: deteccion automatica (NSD) y envio con un toque.
 *  - Internet: crea una sala y muestra el QR / enlace para unirse.
 */
class KarinLinkShareDialog(
    context: Context,
    private val episodeTitle: String,
    private val episodeUrl: String,
    private val embedUrl: String,
    private val siteName: String
) : Dialog(context) {

    private val TAG = "KarinLinkShare"
    private val karinLink = KarinLinkManager(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var lanContainer: LinearLayout? = null
    private var internetContainer: LinearLayout? = null
    private var statusText: TextView? = null
    private var qrImage: ImageView? = null
    private var roomText: TextView? = null
    private var roomCreated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }

        setContentView(buildContent())

        try {
            karinLink.start()
        } catch (e: Exception) {
            Toast.makeText(context, "Error al iniciar KARIN Link: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        observeDevices()
        observeStatus()

        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                dismiss()
                true
            } else false
        }

        setOnDismissListener {
            karinLink.stop()
            scope.cancel()
        }
    }

    private fun buildContent(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = context.getDrawable(R.drawable.bg_card)
        }

        container.addView(TextView(context).apply {
            text = "📤 Compartir episodio"
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(0, 0, 0, dp(4))
        })

        container.addView(TextView(context).apply {
            text = episodeTitle.take(70)
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        })

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        statusText = TextView(context).apply {
            text = "Buscando dispositivos en la red..."
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        body.addView(statusText)

        body.addView(sectionTitle("📡 Red Local (LAN) - detección automática", "#6C63FF"))
        lanContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, dp(4))
        }
        body.addView(lanContainer)
        addLanPlaceholder()

        body.addView(sectionTitle("🌐 Internet (WAN) - código QR", "#22C55E"))
        internetContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, dp(4))
        }
        body.addView(internetContainer)

        body.addView(button("➕ Crear sala con QR", "#FF9800", showBottomMargin = true) {
            createRoomWithQr()
        })

        body.addView(button("🔗 Copiar enlace de sala", "#3B82F6", showBottomMargin = true) {
            copyRoomLink()
        })

        body.addView(TextView(context).apply {
            text = "El otro dispositivo debe tener KARIN Link activo para reproducir el episodio."
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 11f
            setPadding(0, dp(4), 0, dp(10))
        })

        body.addView(button("Cerrar", "#475569") { dismiss() })

        scroll.addView(body)
        container.addView(scroll)
        return container
    }

    private fun sectionTitle(text: String, color: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor(color))
            textSize = 14f
            setPadding(0, dp(4), 0, dp(6))
        }
    }

    private fun addLanPlaceholder() {
        lanContainer?.removeAllViews()
        lanContainer?.addView(TextView(context).apply {
            text = "Buscando dispositivos..."
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(10))
        })
    }

    private fun addInternetHint() {
        internetContainer?.removeAllViews()
        internetContainer?.addView(TextView(context).apply {
            text = "Crea una sala para compartir por internet con el QR."
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(10))
        })
    }

    private fun observeDevices() {
        scope.launch {
            karinLink.discoveryManager.devices.collect { devices ->
                lanContainer?.removeAllViews()
                if (devices.isEmpty()) {
                    addLanPlaceholder()
                } else {
                    statusText?.text = "${devices.size} dispositivo(s) encontrado(s) en LAN"
                    devices.forEach { device ->
                        lanContainer?.addView(createLanDeviceItem(device))
                    }
                }
            }
        }
    }

    private fun observeStatus() {
        scope.launch {
            karinLink.status.collect { st ->
                statusText?.text = st
            }
        }
        scope.launch {
            karinLink.roomManager.currentRoom.collect { room ->
                if (room != null && roomCreated) {
                    roomText?.text = "Sala: ${room.name} (${room.id}) • ${room.members.size} miembro(s)"
                    qrImage?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun createLanDeviceItem(device: DiscoveryManager.DiscoveredDevice): View {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#1AFFFFFF"))
            }
            isFocusable = true
            isFocusableInTouchMode = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            layoutParams = lp
        }

        item.addView(TextView(context).apply {
            text = "📱 ${device.displayName}"
            setTextColor(Color.WHITE)
            textSize = 14f
        })

        item.addView(TextView(context).apply {
            text = "${device.host}:${device.port}"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })

        item.setOnClickListener {
            shareToDevice(device)
        }
        item.onActionKey { shareToDevice(device) }

        return item
    }

    private fun shareToDevice(device: DiscoveryManager.DiscoveredDevice) {
        Toast.makeText(context, "Enviando a ${device.displayName}...", Toast.LENGTH_SHORT).show()
        statusText?.text = "Enviando a ${device.displayName}..."
        karinLink.connectToDevice(device)
        // Esperar a que se abra el WebSocket antes de enviar el episodio.
        scope.launch {
            kotlinx.coroutines.delay(1200)
            karinLink.shareEpisode(
                title = episodeTitle,
                episodeTitle = episodeTitle,
                episodeUrl = episodeUrl,
                siteName = siteName,
                embedUrl = embedUrl
            )
            statusText?.text = "Episodio enviado a ${device.displayName} 🎬"
        }
    }

    private fun createRoomWithQr() {
        if (roomCreated) {
            Toast.makeText(context, "La sala ya está creada", Toast.LENGTH_SHORT).show()
            return
        }
        val room = karinLink.createRoom("Sala KarinFLiX")
        roomCreated = true

        internetContainer?.removeAllViews()

        roomText = TextView(context).apply {
            text = "Sala: ${room.id} • 1 miembro"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }
        internetContainer?.addView(roomText)

        qrImage = ImageView(context).apply {
            setPadding(0, 0, 0, dp(8))
        }
        internetContainer?.addView(qrImage)
        generateQr(karinLink.getJoinUrl(room.id))

        // Compartir el episodio en la sala (el re-share lo recibe quien se una).
        karinLink.shareEpisode(
            title = episodeTitle,
            episodeTitle = episodeTitle,
            episodeUrl = episodeUrl,
            siteName = siteName,
            embedUrl = embedUrl
        )
        Toast.makeText(context, "Sala ${room.id} creada. Escanea el QR desde el otro dispositivo.", Toast.LENGTH_LONG).show()
    }

    private fun generateQr(contents: String) {
        try {
            val qrSize = 320
            val writer = QRCodeWriter()
            val matrix = writer.encode(contents, BarcodeFormat.QR_CODE, qrSize, qrSize)
            val bitmap = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.RGB_565)
            for (x in 0 until qrSize) {
                for (y in 0 until qrSize) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            qrImage?.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "QR failed: ${e.message}")
            qrImage?.visibility = View.GONE
        }
    }

    private fun copyRoomLink() {
        val room = karinLink.roomManager.currentRoom.value
        if (room == null) {
            Toast.makeText(context, "Crea una sala primero", Toast.LENGTH_SHORT).show()
            return
        }
        val url = karinLink.getJoinUrl(room.id)
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("KARIN Link", url))
            Toast.makeText(context, "Enlace copiado: $url", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo copiar el enlace", Toast.LENGTH_SHORT).show()
        }
    }

    private fun button(text: String, color: String, showBottomMargin: Boolean = false, onClick: () -> Unit): TextView {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor(color))
        }
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = bg
            isFocusable = true
            isFocusableInTouchMode = true
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (showBottomMargin) bottomMargin = dp(8)
            }
            layoutParams = lp
            setOnFocusChangeListener { v, hasFocus ->
                v.alpha = if (hasFocus) 1.0f else 0.8f
                bg.setStroke(if (hasFocus) dp(2) else 0, if (hasFocus) Color.WHITE else Color.TRANSPARENT)
            }
            setOnClickListener { onClick() }
            onActionKey { onClick() }
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
