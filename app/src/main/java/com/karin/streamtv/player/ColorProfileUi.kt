package com.karin.streamtv.player

import android.content.Context
import androidx.appcompat.app.AlertDialog

object ColorProfileUi {
    fun show(context: Context, onClosed: () -> Unit = {}) {
        val presets = VideoEnhanceConfig.ColorPreset.entries
        val labels = presets.map { it.label }.toTypedArray()
        val current = VideoEnhanceConfig.colorPreset()
        val selectedIdx = presets.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Perfil de color")
            .setSingleChoiceItems(labels, selectedIdx) { _, which ->
                VideoEnhanceConfig.applyColorPreset(presets[which])
            }
            .setNegativeButton("Cerrar", null)
            .setOnDismissListener { onClosed.invoke() }
            .show()
    }
}