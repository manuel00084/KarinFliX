package com.karin.streamtv.player.dsp.audiophile

import android.content.Context
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.karin.streamtv.player.dsp.BiquadFilter
import java.util.Locale

/**
 * UI del Audio Engine y del panel Karin Audiophile (Experimental).
 * Sigue el patrón de AudioDspUi (LinearLayout + AlertDialog, sin XML).
 */
object AudiophileUi {

    /** Sección "Audio Engine" con 3 radios: OFF / Current / Audiophile. */
    fun addEngineSection(container: LinearLayout, context: Context, onChanged: (() -> Unit)? = null) {
        container.addView(header(context, "Audio Engine"))
        container.addView(TextView(context).apply {
            text = "Modo de procesamiento de audio. «Experimental» = sin garantías de superioridad."
            textSize = 12f
            setTextColor(0xFF90A4AE.toInt())
            setPadding(0, 0, 0, 4)
        })
        val chosen = ArrayList<RadioButton>()
        fun row(engine: AudiophileConfig.Engine, subtitle: String) {
            val checked = AudiophileConfig.engine() == engine
            val rb = RadioButton(context).apply {
                text = engine.label
                textSize = 15f
                setTextColor(0xFFECEFF1.toInt())
                isChecked = checked
                isFocusable = false
                isClickable = false
            }
            if (checked) chosen.add(rb)
            val ll = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 6, 0, 6)
                isClickable = true
                isFocusable = true
                addView(rb)
                if (subtitle.isNotEmpty()) {
                    addView(TextView(context).apply {
                        text = subtitle
                        textSize = 12f
                        setTextColor(0xFF90A4AE.toInt())
                        setPadding(52, 0, 0, 0)
                    })
                }
                setOnClickListener {
                    if (rb.isChecked) return@setOnClickListener
                    for (s in chosen) s.isChecked = false
                    chosen.clear()
                    rb.isChecked = true
                    chosen.add(rb)
                    AudiophileConfig.setEngine(engine)
                    onChanged?.invoke()
                }
            }
            container.addView(ll)
        }
        row(AudiophileConfig.Engine.OFF, "Sin procesamiento (copia directa)")
        row(AudiophileConfig.Engine.CURRENT, "DSP principal de KarinFliX (estable)")
        row(AudiophileConfig.Engine.AUDIOPHILE, "Experimental · transparencia y headroom")

        if (AudiophileConfig.engine() == AudiophileConfig.Engine.AUDIOPHILE) {
            container.addView(Button(context).apply {
                text = "Ajustes Audiophile (Experimental)"
                isFocusable = true
                setOnClickListener { showAudiophilePanel(context) }
            })
        }
    }

    /** Panel completo: preset, advanced toggles, A/B, monitor. */
    fun showAudiophilePanel(context: Context) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 16, 56, 16)
        }

        fun header(title: String) {
            container.addView(TextView(context).apply {
                text = title
                textSize = 15f
                setPadding(0, 16, 0, 6)
            })
        }

        fun check(label: String, init: Boolean, onChange: (Boolean) -> Unit) {
            container.addView(CheckBox(context).apply {
                text = label
                isChecked = init
                setPadding(0, 6, 0, 6)
                setOnCheckedChangeListener { _, _ -> onChange(isChecked) }
            })
        }

        fun slider(label: String, progress: Int, max: Int = 100, onStop: (Int) -> Unit) {
            container.addView(TextView(context).apply {
                text = label
                textSize = 14f
                setPadding(0, 10, 0, 2)
            })
            val sb = SeekBar(context).apply {
                this.max = max
                this.progress = progress
                isFocusable = true
                isFocusableInTouchMode = true
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) onStop(p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { onStop(progress) }
                })
            }
            container.addView(sb)
        }

        container.addView(TextView(context).apply {
            text = "Experimental · DSP secundario. No sustituye al DSP actual salvo que lo selecciones en Audio Engine."
            textSize = 12f
            setTextColor(0xFFFFB74D.toInt())
            setPadding(0, 0, 0, 8)
        })

        // ── Preset ──
        header("Preset")
        val presets = AudiophileConfig.ApPreset.entries
        val chosen = ArrayList<RadioButton>()
        val presetList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun presetRow(p: AudiophileConfig.ApPreset) {
            val checked = AudiophileConfig.preset() == p
            val rb = RadioButton(context).apply {
                text = p.label
                textSize = 15f
                setTextColor(0xFFECEFF1.toInt())
                isChecked = checked
                isFocusable = false
                isClickable = false
            }
            if (checked) chosen.add(rb)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isClickable = true
                isFocusable = true
                setPadding(0, 4, 0, 4)
                addView(rb)
                setOnClickListener {
                    if (rb.isChecked) return@setOnClickListener
                    for (s in chosen) s.isChecked = false
                    chosen.clear()
                    rb.isChecked = true
                    chosen.add(rb)
                    AudiophileConfig.setPreset(p)
                    Toast.makeText(context, "Preset ${p.label}", Toast.LENGTH_SHORT).show()
                }
            }
            presetList.addView(row)
        }
        for (p in presets) presetRow(p)
        container.addView(presetList)

        // ── Advanced ──
        header("Advanced")
        val pr = AudiophileConfig.params()
        check("Auto Headroom (compensa boosts de EQ)", pr.autoHeadroom) {
            AudiophileConfig.setAutoHeadroom(it)
        }
        check("Parametric EQ", pr.eqEnabled) { AudiophileConfig.setEqEnabled(it) }
        check("Loudness (objetivo suave)", pr.loudnessEnabled) {
            AudiophileConfig.setLoudnessEnabled(it)
        }
        // Selector de objetivo LUFS
        val targets = floatArrayOf(-14f, -16f, -18f)
        val labels = arrayOf("−14 LUFS", "−16 LUFS", "−18 LUFS")
        var targetIdx = targets.indexOfFirst { it == pr.loudnessTargetLufs }.coerceAtLeast(1)
        container.addView(TextView(context).apply {
            text = "Loudness objetivo: ${labels[targetIdx]}"
            textSize = 14f
            setPadding(0, 8, 0, 2)
        })
        container.addView(SeekBar(context).apply {
            max = 2
            progress = targetIdx
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    targetIdx = p
                    AudiophileConfig.setLoudnessTarget(targets[p])
                    (s?.parent as? LinearLayout)?.let { }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        check("Dynamics (compresor suave de preservación)", pr.dynamicsEnabled) {
            AudiophileConfig.setDynamicsEnabled(it)
        }
        check("Bass Extension (armónicos controlados)", pr.bassExtEnabled) {
            AudiophileConfig.setBassEnabled(it)
        }
        slider("Bass amount", (pr.bassAmount * 100).toInt()) {
            AudiophileConfig.setBassAmount(it / 100f)
        }
        check("Transient (ataque sutil)", pr.transientEnabled) {
            AudiophileConfig.setTransientEnabled(it)
        }
        slider("Transient amount", (pr.transientAmount * 100).toInt()) {
            AudiophileConfig.setTransientAmount(it / 100f)
        }
        check("Harmonic Enhancement (sutiles)", pr.harmonicEnabled) {
            AudiophileConfig.setHarmonicEnabled(it)
        }
        slider("Harmonic amount", (pr.harmonicAmount * 100).toInt()) {
            AudiophileConfig.setHarmonicAmount(it / 100f)
        }

        // Speaker voicing
        header("Speaker voicing (TV / parlantes sencillos)")
        container.addView(TextView(context).apply {
            text = "Tonalidad para bocina pequeña (bass, calidez, diálogo, suavizado). OFF = transparencia."
            textSize = 12f
            setTextColor(0xFF90A4AE.toInt())
            setPadding(0, 0, 0, 4)
        })
        val spkModes = AudiophileConfig.SpeakerMode.entries
        val spkChosen = ArrayList<RadioButton>()
        val spkList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (m in spkModes) {
            val checked = pr.speakerMode == m
            val rb = RadioButton(context).apply {
                text = m.label
                isChecked = checked
                isFocusable = false
                isClickable = false
                setTextColor(0xFFECEFF1.toInt())
            }
            if (checked) spkChosen.add(rb)
            val row = LinearLayout(context).apply {
                isClickable = true; isFocusable = true
                setPadding(0, 2, 0, 2)
                addView(rb)
                setOnClickListener {
                    if (rb.isChecked) return@setOnClickListener
                    for (s in spkChosen) s.isChecked = false
                    spkChosen.clear()
                    rb.isChecked = true; spkChosen.add(rb)
                    AudiophileConfig.setSpeakerMode(m)
                }
            }
            spkList.addView(row)
        }
        container.addView(spkList)

        // Crossfeed
        header("Crossfeed (auriculares)")
        val cfModes = AudiophileConfig.CrossfeedMode.entries
        val cfChosen = ArrayList<RadioButton>()
        val cfList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (m in cfModes) {
            val checked = pr.crossfeed == m
            val rb = RadioButton(context).apply {
                text = m.label
                isChecked = checked
                isFocusable = false
                isClickable = false
                setTextColor(0xFFECEFF1.toInt())
            }
            if (checked) cfChosen.add(rb)
            val row = LinearLayout(context).apply {
                isClickable = true; isFocusable = true
                setPadding(0, 2, 0, 2)
                addView(rb)
                setOnClickListener {
                    if (rb.isChecked) return@setOnClickListener
                    for (s in cfChosen) s.isChecked = false
                    cfChosen.clear()
                    rb.isChecked = true; cfChosen.add(rb)
                    AudiophileConfig.setCrossfeed(m)
                }
            }
            cfList.addView(row)
        }
        container.addView(cfList)

        // True Peak ceiling
        header("True Peak Protection")
        val ceilings = floatArrayOf(-0.5f, -1.0f, -1.5f, -2.0f)
        val ceilLabels = arrayOf("−0.5 dBTP", "−1.0 dBTP", "−1.5 dBTP", "−2.0 dBTP")
        var ceilIdx = ceilings.indexOfFirst { it == pr.truePeakCeilingDb }.coerceAtLeast(1)
        container.addView(TextView(context).apply {
            text = "Techo: ${ceilLabels[ceilIdx]}"
            textSize = 14f
            setPadding(0, 4, 0, 2)
        })
        container.addView(SeekBar(context).apply {
            max = 3
            progress = ceilIdx
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        ceilIdx = p
                        AudiophileConfig.setTruePeakCeiling(ceilings[p])
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        // ── A/B ──
        header("A/B")
        container.addView(Button(context).apply {
            text = if (AudiophileConfig.isAbBypass()) "A/B: Bypass (original)" else "A/B: Procesado"
            setOnClickListener {
                val now = !AudiophileConfig.isAbBypass()
                AudiophileConfig.setAbBypass(now)
                text = if (now) "A/B: Bypass (original)" else "A/B: Procesado"
                Toast.makeText(
                    context,
                    if (now) "Bypass · dry" else "Procesado · wet",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        // ── Monitor ──
        header("Audiophile Monitor")
        val monitor = TextView(context).apply {
            textSize = 13f
            setPadding(0, 4, 0, 4)
            text = formatMetrics(AudiophileMetrics.current)
        }
        container.addView(monitor)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                monitor.text = formatMetrics(AudiophileMetrics.current)
                if (monitor.isAttachedToWindow) handler.postDelayed(this, 200L)
            }
        }
        monitor.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                handler.post(tick)
            }
            override fun onViewDetachedFromWindow(v: android.view.View) {
                handler.removeCallbacks(tick)
            }
        })

        AlertDialog.Builder(context)
            .setTitle("Karin Audiophile · Experimental")
            .setView(ScrollView(context).apply { addView(container) })
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun formatMetrics(m: AudiophileMetrics): String {
        fun db(v: Float): String =
            if (v.isNaN() || v == Float.NEGATIVE_INFINITY) "—" 
            else String.format(Locale.US, "%.1f dB", v)
        fun dbtp(v: Float): String =
            if (v.isNaN() || v == Float.NEGATIVE_INFINITY) "—"
            else String.format(Locale.US, "%.1f dBTP", v)
        fun lufs(v: Float): String =
            if (v.isNaN() || v == Float.NEGATIVE_INFINITY) "—"
            else String.format(Locale.US, "%.1f", v)
        fun dr(v: Float): String =
            if (v.isNaN()) "—" else String.format(Locale.US, "%.1f dB", v)
        fun corr(v: Float): String = String.format(Locale.US, "%+.2f", v)
        fun cpu(v: Float): String = String.format(Locale.US, "%.1f%%", v)
        return buildString {
            append("Peak in   ").append(db(m.inputPeakDb)).append('\n')
            append("Peak out  ").append(db(m.outputPeakDb)).append('\n')
            append("TruePeak  ").append(dbtp(m.truePeakDbtp)).append('\n')
            append("LUFS      ").append(lufs(m.lufs)).append('\n')
            append("DynRange  ").append(dr(m.dynamicRangeDb)).append('\n')
            append("Corr      ").append(corr(m.correlation)).append('\n')
            append("CPU       ").append(cpu(m.cpuPercent))
        }
    }

    private fun header(context: Context, title: String): TextView =
        TextView(context).apply {
            text = title
            textSize = 15f
            setPadding(0, 16, 0, 6)
        }
}
