package com.karin.streamtv.player

import android.content.Context
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object VideoEnhanceUi {
    fun showSingleFeature(
        context: Context,
        title: String,
        enabled: () -> Boolean,
        value: () -> Float,
        onSave: (Boolean, Float) -> Unit,
        description: String? = null,
        extraToggleLabel: String? = null,
        extraToggle: (() -> Boolean)? = null,
        onExtraToggle: ((Boolean) -> Unit)? = null,
        onClosed: () -> Unit = {}
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 16)
        }
        if (!description.isNullOrBlank()) {
            container.addView(TextView(context).apply {
                text = description
                textSize = 12f
                setPadding(0, 0, 0, 12)
                setTextColor(android.graphics.Color.parseColor("#AAB0B8"))
            })
        }
        val cb = CheckBox(context).apply {
            text = "Activar"
            isChecked = enabled()
            setPadding(0, 8, 0, 8)
        }
        container.addView(cb)
        container.addView(TextView(context).apply {
            text = "Intensidad"
            textSize = 13f
            setPadding(0, 12, 0, 4)
        })
        val sb = SeekBar(context).apply {
            max = 100
            progress = (value() * 100).toInt().coerceIn(0, 100)
        }
        container.addView(sb)
        if (!extraToggleLabel.isNullOrBlank() && extraToggle != null && onExtraToggle != null) {
            container.addView(TextView(context).apply {
                text = ""
                setPadding(0, 12, 0, 0)
            })
            val exCb = CheckBox(context).apply {
                text = extraToggleLabel
                isChecked = cb.isChecked && extraToggle()
                isEnabled = cb.isChecked
                setPadding(0, 6, 0, 6)
                setOnCheckedChangeListener { _, isChecked ->
                    onExtraToggle(isChecked)
                }
            }
            container.addView(exCb)
            cb.setOnCheckedChangeListener { _, checked ->
                exCb.isEnabled = checked
                if (!checked) {
                    exCb.isChecked = false
                    onExtraToggle(false)
                }
            }
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                onSave(cb.isChecked, sb.progress / 100f)
                onClosed.invoke()
            }
            .setNegativeButton("Cancelar", null)
            .setOnDismissListener { onClosed.invoke() }
            .show()
    }
}
