package com.karin.streamtv.karinlink

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.karin.streamtv.R
import com.karin.streamtv.share.ShareManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

class KarinLinkActivity : FragmentActivity() {

    private lateinit var karinLink: KarinLinkManager
    private lateinit var tvStatus: TextView
    private lateinit var tvDevices: TextView
    private lateinit var tvRoom: TextView
    private lateinit var ivQR: ImageView
    private lateinit var deviceListContainer: LinearLayout
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_karinlink)

        karinLink = KarinLinkManager(this)
        karinLink.start()

        karinLink.onPlaybackRequest = { title, epUrl, embed, site ->
            runOnUiThread {
                if (embed.isNotBlank()) {
                    finish()
                    val intent = android.content.Intent(this, com.karin.streamtv.ui.EmbedWebViewActivity::class.java)
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
        tvRoom = findViewById(R.id.tv_karinlink_room)
        ivQR = findViewById(R.id.iv_karinlink_qr)
        deviceListContainer = findViewById(R.id.device_list_container)
        scrollView = findViewById(R.id.scroll_karinlink)

        findViewById<View>(R.id.btn_create_room).setOnClickListener { showCreateRoomDialog() }
        findViewById<View>(R.id.btn_refresh).setOnClickListener { refreshDevices() }
        findViewById<View>(R.id.btn_share_whatsapp).setOnClickListener { shareViaWhatsApp() }
        findViewById<View>(R.id.btn_share_facebook).setOnClickListener { shareViaFacebook() }
        findViewById<View>(R.id.btn_share_generic).setOnClickListener { shareGeneric() }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        refreshDevices()
        observeState()
        handleJoinIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleJoinIntent(intent)
    }

    private fun handleJoinIntent(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "karinflinx" || uri.host != "room") return
        val roomId = uri.path?.removePrefix("/") ?: return
        val host = uri.getQueryParameter("host")
        val port = uri.getQueryParameter("port")?.toIntOrNull()
        Log.i("KarinLink", "Join request: room=$roomId host=$host port=$port")
        karinLink.joinRoom(roomId, host, port)
        Toast.makeText(this, "Uniéndote a la sala $roomId...", Toast.LENGTH_SHORT).show()
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
        lifecycleScope.launch {
            karinLink.roomManager.currentRoom.collect { room ->
                if (room != null) {
                    tvRoom.text = "Sala: ${room.name} (${room.id})\nMiembros: ${room.members.size}\nIP: ${karinLink.localIpAddress()}:${LinkServer.port}"
                    tvRoom.visibility = View.VISIBLE
                    generateRoomQR(room.id)
                } else {
                    tvRoom.visibility = View.GONE
                    ivQR.visibility = View.GONE
                }
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
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
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
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener { karinLink.connectToDevice(device) }
            }

            val nameText = TextView(this).apply {
                text = device.displayName
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

    private fun showCreateRoomDialog() {
        val input = EditText(this).apply {
            hint = "Nombre de la sala"
            setTextColor(Color.WHITE)
            setPadding(48, 32, 48, 32)
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Crear Sala")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Sala de KarinFLiX" }
                val room = karinLink.createRoom(name)
                Toast.makeText(this, "Sala creada: ${room.id}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.show()
    }

    private fun generateRoomQR(roomId: String) {
        try {
            val contents = karinLink.getJoinUrl(roomId)
            val qrSize = 300
            val writer = QRCodeWriter()
            val matrix = writer.encode(contents, BarcodeFormat.QR_CODE, qrSize, qrSize)
            val bitmap = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.RGB_565)
            for (x in 0 until qrSize) {
                for (y in 0 until qrSize) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            ivQR.setImageBitmap(bitmap)
            ivQR.visibility = View.VISIBLE
            tvRoom.text = "Sala: ${roomManagerRoomName(roomId)} ($roomId)\nMiembros: ${roomManagerRoomSize(roomId)}\nIP: ${karinLink.localIpAddress()}:${LinkServer.port}"
        } catch (e: Exception) {
            ivQR.visibility = View.GONE
        }
    }

    private fun roomManagerRoomName(roomId: String): String {
        return karinLink.roomManager.currentRoom.value?.name ?: "Sala $roomId"
    }

    private fun roomManagerRoomSize(roomId: String): Int {
        return karinLink.roomManager.currentRoom.value?.members?.size ?: LinkServer.roomSize(roomId)
    }

    private fun refreshDevices() {
        karinLink.discoveryManager.stopDiscovery()
        karinLink.discoveryManager.startDiscovery()
        Toast.makeText(this, "Buscando dispositivos...", Toast.LENGTH_SHORT).show()
    }

    private fun shareViaWhatsApp() {
        val room = karinLink.roomManager.currentRoom.value
        val data = ShareManager.ShareData(
            title = "KarinFLiX",
            episodeTitle = room?.let { "Sala: ${it.name}" } ?: "Únete a mi sala",
            episodeUrl = room?.let { karinLink.roomManager.getRoomUrl(it.id) } ?: "https://karintv.app",
            siteName = "KARIN Link"
        )
        ShareManager.shareToWhatsApp(this, data)
    }

    private fun shareViaFacebook() {
        val room = karinLink.roomManager.currentRoom.value
        val data = ShareManager.ShareData(
            title = "KarinFLiX",
            episodeTitle = room?.let { "Sala: ${it.name}" } ?: "Únete a mi sala",
            episodeUrl = room?.let { karinLink.roomManager.getRoomUrl(it.id) } ?: "https://karintv.app",
            siteName = "KARIN Link"
        )
        ShareManager.shareToFacebook(this, data)
    }

    private fun shareGeneric() {
        val room = karinLink.roomManager.currentRoom.value
        val data = ShareManager.ShareData(
            title = "KarinFLiX",
            episodeTitle = room?.let { "Sala: ${it.name}" } ?: "Únete a mi sala",
            episodeUrl = room?.let { karinLink.roomManager.getRoomUrl(it.id) } ?: "https://karintv.app",
            siteName = "KARIN Link"
        )
        ShareManager.shareGeneric(this, data)
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
