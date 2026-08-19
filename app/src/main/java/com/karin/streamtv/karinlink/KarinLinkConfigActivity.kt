package com.karin.streamtv.karinlink

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.karin.streamtv.R
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
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_back).onActionKey {
            findViewById<View>(R.id.btn_back).performClick()
        }

        updateFsToken()
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
