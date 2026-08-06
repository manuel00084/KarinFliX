package com.karin.streamtv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
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

        val refreshers = ArrayList<() -> Unit>()
        val handler = Handler(Looper.getMainLooper())
        var refreshing = false

        fun techniqueToggle(label: String, enabled: () -> Boolean, value: () -> Float, onStop: (Boolean, Int) -> Unit) {
            container.addView(TextView(context).apply {
                text = label
                textSize = 15f
                setPadding(0, 16, 0, 4)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            val cb = CheckBox(context).apply {
                text = "Activar"
                isChecked = enabled()
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
            sb.progress = (value() * 100).toInt().coerceIn(0, 100)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (refreshing) return
                    onStop(cb.isChecked, seekBar?.progress ?: 0)
                }
            })
            container.addView(sb)
            cb.setOnCheckedChangeListener { _, isChecked ->
                if (refreshing) return@setOnCheckedChangeListener
                onStop(isChecked, sb.progress)
            }
            refreshers.add {
                val en = enabled()
                val v = (value() * 100).toInt().coerceIn(0, 100)
                if (cb.isChecked != en) cb.isChecked = en
                if (sb.progress != v) sb.progress = v
            }
        }

        techniqueToggle(
            "Mejora baja calidad",
            { VideoEnhanceConfig.superResEnabled() },
            { VideoEnhanceConfig.getSuperRes() }
        ) { en, pr ->
            VideoEnhanceConfig.setSuperResEnabled(en)
            VideoEnhanceConfig.setSuperRes(pr / 100f)
        }

        techniqueToggle(
            "Detail Boost",
            { VideoEnhanceConfig.detailBoostEnabled() },
            { VideoEnhanceConfig.getDetailBoost() }
        ) { en, pr ->
            VideoEnhanceConfig.setDetailBoostEnabled(en)
            VideoEnhanceConfig.setDetailBoost(pr / 100f)
        }

        techniqueToggle(
            "Light Boost",
            { VideoEnhanceConfig.lightBoostEnabled() },
            { VideoEnhanceConfig.getLightBoost() }
        ) { en, pr ->
            VideoEnhanceConfig.setLightBoostEnabled(en)
            VideoEnhanceConfig.setLightBoost(pr / 100f)
        }

        techniqueToggle(
            "HDR (Fake)",
            { VideoEnhanceConfig.hdrEnabled() },
            { VideoEnhanceConfig.getHdr() }
        ) { en, pr ->
            VideoEnhanceConfig.setHdrEnabled(en)
            VideoEnhanceConfig.setHdr(pr / 100f)
        }

        techniqueToggle(
            "Granulado fílmico",
            { VideoEnhanceConfig.grainEnabled() },
            { VideoEnhanceConfig.getGrain() }
        ) { en, pr ->
            VideoEnhanceConfig.setGrainEnabled(en)
            VideoEnhanceConfig.setGrain(pr / 100f)
        }

        techniqueToggle(
            "Nitidez adaptativa",
            { VideoEnhanceConfig.adaptiveSharpEnabled() },
            { VideoEnhanceConfig.getAdaptiveSharp() }
        ) { en, pr ->
            VideoEnhanceConfig.setAdaptiveSharpEnabled(en)
            VideoEnhanceConfig.setAdaptiveSharp(pr / 100f)
        }

        val scroll = ScrollView(context).apply {
            addView(container)
        }
        var dialog: AlertDialog? = null
        val refresh = object : Runnable {
            override fun run() {
                refreshing = true
                for (r in refreshers) r()
                refreshing = false
                handler.postDelayed(this, 1200)
            }
        }
        dialog = AlertDialog.Builder(context)
            .setTitle("Mejora de video")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .setOnDismissListener {
                handler.removeCallbacks(refresh)
                onClosed.invoke()
            }
            .show()
        refresh.run()
    }
}
