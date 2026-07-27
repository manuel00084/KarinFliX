package com.karin.streamtv.karinlink

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
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
                    tvRoom.text = "Sala: ${room.name} (${room.id})\nMiembros: ${room.members.size}"
                    tvRoom.visibility = View.VISIBLE
                    generateRoomQR(room.id)
                } else {
                    tvRoom.visibility = View.GONE
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
                setBackgroundResource(android.R.drawable.dialog_holo_dark_frame)
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
            val contents = "karinflinx://room/$roomId"
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
        } catch (e: Exception) {
            ivQR.visibility = View.GONE
        }
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
}
