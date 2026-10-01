package com.karin.streamtv.karinlink

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.karin.streamtv.R
import com.karin.streamtv.karinlink.protocol.Pairing
import com.karin.streamtv.karinlink.queue.QueueHub
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.enableTvFocus
import com.karin.streamtv.util.onActionKey

/**
 * Página de configuración de KARIN Link:
 *  - Nombre del dispositivo anunciado en la red.
 *  - Acceso remoto a archivos (endpoints /fs) con su token.
 *  - ID del dispositivo (solo lectura).
 */
class KarinLinkConfigActivity : FragmentActivity() {

    private lateinit var karinLink: KarinLinkManager
    private lateinit var etDeviceName: EditText
    private lateinit var switchRemoteFiles: SwitchMaterial
    private lateinit var switchSmbHome: SwitchMaterial
    private lateinit var tvFsToken: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var etPairingCode: EditText
    private lateinit var tvPairingStatus: TextView
    private lateinit var tvFsRoots: TextView
    private lateinit var tvQueueSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_karinlink_config)
        enableTvFocus()

        karinLink = KarinLinkManager(this)

        etDeviceName = findViewById(R.id.et_device_name)
        switchRemoteFiles = findViewById(R.id.switch_remote_files)
        switchSmbHome = findViewById(R.id.switch_smb_home)
        tvFsToken = findViewById(R.id.tv_fs_token)
        tvDeviceId = findViewById(R.id.tv_device_id)
        etPairingCode = findViewById(R.id.et_pairing_code)
        tvPairingStatus = findViewById(R.id.tv_pairing_status)
        tvFsRoots = findViewById(R.id.tv_fs_roots)

        etDeviceName.setText(karinLink.deviceName)
        tvDeviceId.text = karinLink.deviceId

        switchRemoteFiles.isChecked = karinLink.isRemoteFileAccessEnabled
        switchRemoteFiles.setOnCheckedChangeListener { _, checked ->
            karinLink.setRemoteFileAccess(checked)
            updateFsToken()
            announceState(switchRemoteFiles, "Acceso remoto a archivos", checked)
        }

        val rowRemoteFiles = findViewById<View>(R.id.row_remote_files)
        rowRemoteFiles.setOnClickListener { switchRemoteFiles.toggle() }
        rowRemoteFiles.onActionKey { switchRemoteFiles.toggle() }

        switchSmbHome.isChecked = AppPreferences.isSmbShowOnHome()
        switchSmbHome.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setSmbShowOnHome(checked)
            announceState(switchSmbHome, "Red de Windows en el explorador", checked)
        }
        val rowSmbHome = findViewById<View>(R.id.row_smb_home)
        rowSmbHome.setOnClickListener { switchSmbHome.toggle() }
        rowSmbHome.onActionKey { switchSmbHome.toggle() }

        findViewById<View>(R.id.btn_save_name).setOnClickListener { saveDeviceName() }
        findViewById<View>(R.id.btn_save_name).onActionKey {
            findViewById<View>(R.id.btn_save_name).performClick()
        }
        findViewById<View>(R.id.btn_regenerate_token).setOnClickListener { regenerateToken() }
        findViewById<View>(R.id.btn_regenerate_token).onActionKey {
            findViewById<View>(R.id.btn_regenerate_token).performClick()
        }

        findViewById<View>(R.id.btn_fs_add_internal).setOnClickListener {
            shareFolder(android.os.Environment.getExternalStorageDirectory())
        }
        findViewById<View>(R.id.btn_fs_add_internal).onActionKey {
            findViewById<View>(R.id.btn_fs_add_internal).performClick()
        }
        findViewById<View>(R.id.btn_fs_add_app).setOnClickListener {
            // El directorio externo propio de la app siempre existe, y es el
            // único sitio donde se puede escribir sin permisos raros.
            val dir = getExternalFilesDir(null)
            if (dir != null) shareFolder(dir) else {
                Toast.makeText(this, "La carpeta de la app no está disponible", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.btn_fs_add_app).onActionKey {
            findViewById<View>(R.id.btn_fs_add_app).performClick()
        }
        tvFsRoots.setOnClickListener {
            val folders = karinLink.sharedFolders()
            if (folders.isNotEmpty()) confirmRemoveFolder(folders.last())
        }
        tvFsRoots.onActionKey { tvFsRoots.performClick() }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_back).onActionKey {
            findViewById<View>(R.id.btn_back).performClick()
        }

        // Entrada a la cola. Se muestra lo que hay en el propio dispositivo, que
        // es donde se reproduce, y no lo que el otro diga tener.
        QueueHub.attach(this)
        tvQueueSummary = findViewById(R.id.tv_queue_summary)
        updateQueueSummary()
        findViewById<View>(R.id.row_queue).setOnClickListener {
            startActivity(Intent(this, KarinLinkQueueActivity::class.java))
        }
        findViewById<View>(R.id.row_queue).onActionKey {
            findViewById<View>(R.id.row_queue).performClick()
        }

        findViewById<View>(R.id.btn_pairing_apply).setOnClickListener { applyPairingCode() }
        findViewById<View>(R.id.btn_pairing_apply).onActionKey {
            findViewById<View>(R.id.btn_pairing_apply).performClick()
        }
        findViewById<View>(R.id.btn_pairing_generate).setOnClickListener { generatePairingCode() }
        findViewById<View>(R.id.btn_pairing_generate).onActionKey {
            findViewById<View>(R.id.btn_pairing_generate).performClick()
        }
        findViewById<View>(R.id.btn_pairing_clear).setOnClickListener { clearPairingCode() }
        findViewById<View>(R.id.btn_pairing_clear).onActionKey {
            findViewById<View>(R.id.btn_pairing_clear).performClick()
        }

        updateFsToken()
        updatePairingStatus()
    }

    private fun updateQueueSummary() {
        val queue = QueueHub.current()
        val now = queue.current?.title
        val waiting = queue.size - 1
        tvQueueSummary.text = when {
            now == null -> "Vacía"
            waiting <= 0 -> "Sonando: $now"
            else -> "Sonando: $now · $waiting en espera"
        }
    }

    /**
     * Pairs by shared code.
     *
     * Both devices derive the same key from the same code, so the code has to be
     * typed on the device that is connecting as well as the one that generates
     * it. That is why the field sits next to the generator rather than behind a
     * separate flow: on a TV remote the fastest path is read the code here,
     * type it there, and both sides are ready.
     */
    private fun applyPairingCode() {
        val typed = etPairingCode.text.toString()
        val code = Pairing.normalizePin(typed)
        if (!Pairing.isValidPinShape(code)) {
            Toast.makeText(this, "El código tiene 6 caracteres", Toast.LENGTH_SHORT).show()
            return
        }
        karinLink.setPairingCode(code)
        etPairingCode.setText("")
        updatePairingStatus()
        Toast.makeText(this, "Código $code listo", Toast.LENGTH_SHORT).show()
    }

    private fun generatePairingCode() {
        val code = Pairing.newPin()
        karinLink.setPairingCode(code)
        updatePairingStatus()
        // Shown as well as stored: the user has to read it to the other device.
        Toast.makeText(this, "Código $code", Toast.LENGTH_LONG).show()
    }

    private fun clearPairingCode() {
        karinLink.setPairingCode(null)
        updatePairingStatus()
    }

    private fun updatePairingStatus() {
        val code = karinLink.pendingPairingCode
        tvPairingStatus.text = if (code == null) {
            "Sin emparejamiento en curso"
        } else {
            "Esperando: $code"
        }
    }

    private fun saveDeviceName() {
        val name = etDeviceName.text.toString().trim()
        if (name.isBlank()) {
            Toast.makeText(this, "Escribe un nombre para el dispositivo", Toast.LENGTH_SHORT).show()
            return
        }
        karinLink.setDeviceName(name)
        Toast.makeText(this, "Nombre guardado: $name", Toast.LENGTH_SHORT).show()
    }

    private fun regenerateToken() {
        val newToken = karinLink.regenerateFsToken()
        updateFsToken()
        Toast.makeText(this, "Token regenerado", Toast.LENGTH_SHORT).show()
        Log.i("KarinLinkConfig", "FS token regenerated")
    }

    private fun updateFsToken() {
        tvFsToken.text = if (karinLink.isRemoteFileAccessEnabled) {
            karinLink.fsToken
        } else {
            "Desactivado"
        }
        updateSharedFolders()
    }

    /**
     * Muestra qué carpetas están abiertas y deja quitarlas.
     *
     * El interruptor de arriba solo decide si el acceso está activo; lo que se
     * puede ver de verdad es lo de esta lista. Por eso la lista es lo que se
     * explica, y no el interruptor.
     */
    private fun updateSharedFolders() {
        val folders = karinLink.sharedFolders()
        tvFsRoots.text = when {
            !karinLink.isRemoteFileAccessEnabled ->
                "Acceso remoto apagado. Estas son las carpetas preparadas:"
            folders.isEmpty() ->
                "Ninguna carpeta compartida todavía"
            else -> "Carpetas compartidas:\n" + folders.joinToString("\n") { "· ${it.path}" }
        }
    }

    private fun shareFolder(dir: java.io.File) {
        if (karinLink.shareFolder(dir)) {
            updateSharedFolders()
            Toast.makeText(this, "Compartiendo ${dir.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No se pudo compartir esa carpeta", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmRemoveFolder(dir: java.io.File) {
        AlertDialog.Builder(this)
            .setTitle("Dejar de compartir")
            .setMessage("¿Dejar de compartir ${dir.name}?")
            .setPositiveButton("Dejar de compartir") { _, _ ->
                karinLink.unshareFolder(dir)
                updateSharedFolders()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun announceState(switch: SwitchMaterial, label: String, enabled: Boolean) {
        switch.contentDescription = "$label: ${if (enabled) "activado" else "desactivado"}"
        switch.announceForAccessibility(switch.contentDescription)
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
}
