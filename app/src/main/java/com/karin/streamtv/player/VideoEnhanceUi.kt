package com.karin.streamtv.player

import android.content.Context
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object VideoEnhanceUi {
    fun showAdvanced(context: Context, onClosed: () -> Unit = {}) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 16, 56, 16)
        }

        val master = CheckBox(context).apply {
            text = "Activar mejora de video"
            isChecked = VideoEnhanceConfig.isEnabled()
            setPadding(0, 8, 0, 8)
        }
        container.addView(master)

        fun techniqueToggle(label: String, enabled: Boolean, value: Float, onStop: (Boolean, Int) -> Unit): Pair<CheckBox, SeekBar> {
            container.addView(TextView(context).apply {
                text = label
                textSize = 15f
                setPadding(0, 16, 0, 4)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            val cb = CheckBox(context).apply {
                text = "Activar"
                isChecked = enabled
                setPadding(0, 4, 0, 4)
            }
            container.addView(cb)
            container.addView(TextView(context).apply {
                text = "Intensidad"
                textSize = 13f
                setPadding(0, 4, 0, 2)
            })
            val sb = SeekBar(context)
            sb.max = 100
            sb.progress = (value * 100).toInt().coerceIn(0, 100)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    onStop(cb.isChecked, seekBar?.progress ?: 0)
                }
            })
            container.addView(sb)
            cb.setOnCheckedChangeListener { _, isChecked ->
                onStop(isChecked, sb.progress)
            }
            return Pair(cb, sb)
        }

        techniqueToggle(
            "Claridad",
            VideoEnhanceConfig.superResEnabled(),
            VideoEnhanceConfig.getSuperRes()
        ) { en, pr ->
            VideoEnhanceConfig.setSuperResEnabled(en)
            VideoEnhanceConfig.setSuperRes(pr / 100f)
        }

        techniqueToggle(
            "Detail Boost",
            VideoEnhanceConfig.detailBoostEnabled(),
            VideoEnhanceConfig.getDetailBoost()
        ) { en, pr ->
            VideoEnhanceConfig.setDetailBoostEnabled(en)
            VideoEnhanceConfig.setDetailBoost(pr / 100f)
        }

        techniqueToggle(
            "Light Boost",
            VideoEnhanceConfig.lightBoostEnabled(),
            VideoEnhanceConfig.getLightBoost()
        ) { en, pr ->
            VideoEnhanceConfig.setLightBoostEnabled(en)
            VideoEnhanceConfig.setLightBoost(pr / 100f)
        }

        techniqueToggle(
            "HDR (Fake)",
            VideoEnhanceConfig.hdrEnabled(),
            VideoEnhanceConfig.getHdr()
        ) { en, pr ->
            VideoEnhanceConfig.setHdrEnabled(en)
            VideoEnhanceConfig.setHdr(pr / 100f)
        }

        techniqueToggle(
            "Granulado fílmico",
            VideoEnhanceConfig.grainEnabled(),
            VideoEnhanceConfig.getGrain()
        ) { en, pr ->
            VideoEnhanceConfig.setGrainEnabled(en)
            VideoEnhanceConfig.setGrain(pr / 100f)
        }

        techniqueToggle(
            "Nitidez adaptativa",
            VideoEnhanceConfig.adaptiveSharpEnabled(),
            VideoEnhanceConfig.getAdaptiveSharp()
        ) { en, pr ->
            VideoEnhanceConfig.setAdaptiveSharpEnabled(en)
            VideoEnhanceConfig.setAdaptiveSharp(pr / 100f)
        }

        val scroll = ScrollView(context).apply {
            addView(container)
        }
        AlertDialog.Builder(context)
            .setTitle("Mejora de video")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .setOnDismissListener {
                VideoEnhanceConfig.setEnabled(master.isChecked)
                onClosed.invoke()
            }
            .show()
    }
}