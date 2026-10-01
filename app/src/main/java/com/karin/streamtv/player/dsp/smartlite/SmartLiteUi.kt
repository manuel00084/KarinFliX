package com.karin.streamtv.player.dsp.smartlite

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.karin.streamtv.player.dsp.AutoEqCatalog
import com.karin.streamtv.util.AppPreferences
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * UI del Audio Engine y del panel Karin DSP Smart Lite.
 * Panel construido con LinearLayout + AlertDialog, sin XML.
 *
 * Estructura pensada para un usuario novel: el panel principal solo muestra
 * lo esencial (DSP encendido/apagado, volumen, potencia). Los presets y todo
 * el resto de ajustes viven detrás de botones (Preset / Configuración avanzada).
 */
object SmartLiteUi {

    /** Salto de la potencia: 1 paso de slider = 0.24 dB (±12 dB en total). */
    private const val POT_STEP_DB = 0.24f
    /** Centro del slider de potencia (0 dB). */
    private const val POT_CENTER = 50

    /**
     * Panel principal (novato): estado, DSP vs audio original, volumen y
     * potencia, con acceso a presets y a la configuración avanzada.
     *
     * @param onVolumeChange aplica el volumen al reproductor en vivo; si es
     * null solo se guarda en preferencias (se aplica al próximo arranque).
     */
    fun showSmartLitePanel(context: Context, onVolumeChange: ((Float) -> Unit)? = null) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 16, 56, 16)
        }

        // ── Estado ──
        val status = TextView(context).apply {
            textSize = 15f
            setPadding(0, 0, 0, 4)
        }
        fun refreshStatus() {
            val on = SmartLiteConfig.engine() == SmartLiteConfig.Engine.SMART_LITE
            status.text = if (on) "DSP: encendido (Karin Smart Lite)" else "DSP: apagado (audio original)"
            status.setTextColor(if (on) 0xFF81C784.toInt() else 0xFF90A4AE.toInt())
        }
        refreshStatus()
        container.addView(status)

        // ── DSP encendido / audio original ──
        header(context, container, "Audio", compact = true)
        val audioChosen = ArrayList<RadioButton>()
        val engineOn = SmartLiteConfig.engine() == SmartLiteConfig.Engine.SMART_LITE
        audioRow(context, container, "Usar el DSP", "Karin Smart Lite (procesado)",
            checked = engineOn, chosen = audioChosen) {
            SmartLiteConfig.setEngine(SmartLiteConfig.Engine.SMART_LITE)
            refreshStatus()
        }
        audioRow(context, container, "Usar el audio original", "Sin DSP: copia directa",
            checked = !engineOn, chosen = audioChosen) {
            SmartLiteConfig.setEngine(SmartLiteConfig.Engine.OFF)
            refreshStatus()
        }

        // ── Volumen ──
        header(context, container, "Volumen", compact = true)
        val volLabel = TextView(context).apply {
            textSize = 14f
            setPadding(0, 2, 0, 2)
        }
        container.addView(volLabel)
        fun volText(p: Int) = "Volumen: $p%"
        val volSeek = SeekBar(context).apply { max = 100 }
        var volProgress = (AppPreferences.getPlayerVolume() * 100f).roundToInt().coerceIn(10, 100)
        volLabel.text = volText(volProgress)
        volSeek.progress = volProgress
        volSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                volLabel.text = volText(p)
                applyVolume(p, onVolumeChange)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { applyVolume(s?.progress ?: volProgress, onVolumeChange) }
        })
        container.addView(volSeek)

        // ── Potencia ──
        header(context, container, "Potencia", compact = true)
        container.addView(TextView(context).apply {
            text = "Ganancia extra del DSP. 100% = sin cambios; sube o baja el «punch» general."
            textSize = 12f
            setTextColor(0xFF90A4AE.toInt())
            setPadding(0, 0, 0, 4)
        })
        val potLabel = TextView(context).apply {
            textSize = 14f
            setPadding(0, 2, 0, 2)
        }
        container.addView(potLabel)
        val potSeek = SeekBar(context).apply { max = 100 }
        var potProgress =
            ((SmartLiteConfig.params().outputGainDb / POT_STEP_DB) + POT_CENTER).roundToInt()
                .coerceIn(0, 100)
        potLabel.text = potText(potProgress)
        potSeek.progress = potProgress
        potSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                potLabel.text = potText(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                potProgress = s?.progress ?: potProgress
                SmartLiteConfig.setOutputGainDb(potenzaDb(potProgress))
            }
        })
        container.addView(potSeek)
        container.addView(TextView(context).apply {
            text = "Solo actúa con el DSP encendido."
            textSize = 12f
            setTextColor(0xFF90A4AE.toInt())
            setPadding(0, 4, 0, 0)
        })

        // ── Presets (detrás de un botón) ──
        val presetBtn = Button(context).apply {
            isFocusable = true
        }
        fun refreshPresetBtn() {
            presetBtn.text = "Elegir preset · ${presetButtonText()}"
        }
        refreshPresetBtn()
        container.addView(presetBtn)

        // ── Configuración avanzada (detrás de un botón) ──
        container.addView(Button(context).apply {
            text = "Configuración avanzada…"
            isFocusable = true
            setOnClickListener { showAdvancedDialog(context) }
        })
        presetBtn.setOnClickListener {
            showPresetDialog(context) { refreshPresetBtn() }
        }

        AlertDialog.Builder(context)
            .setTitle("Sonido · Karin DSP Smart Lite")
            .setView(ScrollView(context).apply { addView(container) })
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Selector de preset en diálogo propio. */
    fun showPresetDialog(context: Context, onChanged: (() -> Unit)? = null) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 16, 56, 16)
        }
        container.addView(TextView(context).apply {
            text = "Un preset ajusta graves, claridad y dinámica de fábrica. Cambia el sonido al instante."
            textSize = 12f
            setTextColor(0xFF90A4AE.toInt())
            setPadding(0, 0, 0, 6)
        })
        val autoNote = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFF81C784.toInt())
            setPadding(0, 0, 0, 6)
        }
        fun refreshNote() {
            if (SmartLiteConfig.preset() == SmartLiteConfig.SlPreset.AUTO) {
                autoNote.text = "Ahora suena como «${SmartLiteConfig.effectivePreset().label}» " +
                    "(según la salida detectada)."
                autoNote.visibility = TextView.VISIBLE
            } else {
                autoNote.visibility = TextView.GONE
            }
        }
        refreshNote()
        container.addView(autoNote)
        val chosen = ArrayList<RadioButton>()
        for (p in SmartLiteConfig.SlPreset.entries) {
            audioRow(context, container, p.label, "",
                checked = SmartLiteConfig.preset() == p, chosen = chosen) {
                SmartLiteConfig.setPreset(p)
                Toast.makeText(context, "Preset ${SmartLiteConfig.preset().label}", Toast.LENGTH_SHORT).show()
                refreshNote()
                onChanged?.invoke()
            }
        }
        AlertDialog.Builder(context)
            .setTitle("Preset de sonido")
            .setView(ScrollView(context).apply { addView(container) })
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Todo el detalle técnico, fuera de la vista del usuario novato. */
    fun showAdvancedDialog(context: Context) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 16, 56, 16)
        }

        // En modo Automático la composición la decide el preset: los toggles
        // de abajo quedan de solo lectura para no prometer un cambio que no
        // se va a aplicar.
        val autoMode = SmartLiteConfig.preset() == SmartLiteConfig.SlPreset.AUTO
        if (autoMode) {
            container.addView(TextView(context).apply {
                text = "Preset Automático: la composición (EQ, graves, dinámica, voicing) " +
                    "la elige el preset según la salida detectada. Elige un preset concreto " +
                    "para editarla a mano."
                textSize = 12f
                setTextColor(0xFFFFB74D.toInt())
                setPadding(0, 0, 0, 6)
            })
        }

        fun headerA(title: String) = header(context, container, title)

        fun check(label: String, init: Boolean, enabled: Boolean = !autoMode, onChange: (Boolean) -> Unit) {
            container.addView(CheckBox(context).apply {
                text = label
                isChecked = init
                isEnabled = enabled
                setPadding(0, 6, 0, 6)
                alpha = if (enabled) 1f else 0.45f
                setOnCheckedChangeListener { _, _ -> onChange(isChecked) }
            })
        }

        container.addView(TextView(context).apply {
            text = "Ajustes finos. Si no los necesitas, deja todo en automático: los valores por defecto ya son seguros."
            textSize = 12f
            setTextColor(0xFFFFB74D.toInt())
            setPadding(0, 0, 0, 8)
        })

        // ── Salida detectada ──
        headerA("Salida detectada")
        container.addView(TextView(context).apply {
            text = SmartLiteConfig.detectedPlaybackOutput().label
            textSize = 15f
            setPadding(0, 0, 0, 2)
        })
        container.addView(TextView(context).apply {
            text = "Ajusta voicing, graves, dinámica y protección según la salida. No cambia el preset, la EQ ni el objetivo de loudness."
            textSize = 12f
            setPadding(0, 0, 0, 8)
        })
        check("Adaptar salida automáticamente", SmartLiteConfig.isAutoOutput(), enabled = true) {
            SmartLiteConfig.setAutoOutput(it)
        }
        container.addView(TextView(context).apply {
            text = "Códec BT: ${SmartLiteConfig.btCodecLabel()}"
            textSize = 13f
            setPadding(0, 8, 0, 2)
        })
        container.addView(Button(context).apply {
            text = "Permiso Bluetooth (ver códec)"
            setOnClickListener {
                (context as? Activity)?.requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 41
                )
            }
        })
        check("Bit-perfect (copia directa, sin DSP)", SmartLiteConfig.isBitPerfect(), enabled = true) {
            SmartLiteConfig.setBitPerfect(it)
            val mode = if (it) "activado: el motor copia bytes sin procesar" else "apagado"
            Toast.makeText(context, "Bit-perfect $mode", Toast.LENGTH_SHORT).show()
        }

        // ── AutoEQ medido ──
        headerA("AutoEQ medido")
        container.addView(TextView(context).apply {
            val match = SmartLiteConfig.matchedAutoEqProfile()?.name ?: "ninguno"
            text = "Match del dispositivo: $match. Corrección medida hacia objetivo Harman; usa 5 bandas y el headroom absorbe el preamp."
            textSize = 12f
            setPadding(0, 0, 0, 4)
        })
        val autoEqChosen = ArrayList<RadioButton>()
        fun autoEqRow(value: String, label: String) {
            audioRow(context, container, label, "",
                checked = SmartLiteConfig.autoEqSetting() == value, chosen = autoEqChosen) {
                SmartLiteConfig.setAutoEqSetting(value)
                Toast.makeText(context, "AutoEQ: $label", Toast.LENGTH_SHORT).show()
            }
        }
        autoEqRow("auto", "Automático (recomendado)")
        autoEqRow("none", "Ninguno (curva suave)")
        for (profile in AutoEqCatalog.profiles) autoEqRow(profile.name, profile.name)

        // ── Advanced ──
        headerA("Advanced")
        val pr = SmartLiteConfig.params()
        check("Auto Headroom (compensa boosts de EQ)", pr.autoHeadroom) {
            SmartLiteConfig.setAutoHeadroom(it)
        }
        check("Parametric EQ", pr.eqEnabled) { SmartLiteConfig.setEqEnabled(it) }
        check("Loudness (objetivo suave)", pr.loudnessEnabled) {
            SmartLiteConfig.setLoudnessEnabled(it)
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
        val loudnessLabel = container.getChildAt(container.childCount - 1) as TextView
        container.addView(SeekBar(context).apply {
            max = 2
            progress = targetIdx
            isEnabled = !autoMode
            alpha = if (autoMode) 0.45f else 1f
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    targetIdx = p
                    loudnessLabel.text = "Loudness objetivo: ${labels[p]}"
                    SmartLiteConfig.setLoudnessTarget(targets[p])
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        check("Dynamics (compresor suave de preservación)", pr.dynamicsEnabled) {
            SmartLiteConfig.setDynamicsEnabled(it)
        }
        check("Bass Extension (armónicos controlados)", pr.bassExtEnabled) {
            SmartLiteConfig.setBassEnabled(it)
        }
        slider(context, container, "Bass amount", (pr.bassAmount * 100).toInt(),
            enabled = !autoMode) {
            SmartLiteConfig.setBassAmount(it / 100f)
        }
        check("Transient (ataque sutil)", pr.transientEnabled) {
            SmartLiteConfig.setTransientEnabled(it)
        }
        slider(context, container, "Transient amount", (pr.transientAmount * 100).toInt(),
            enabled = !autoMode) {
            SmartLiteConfig.setTransientAmount(it / 100f)
        }
        check("Harmonic Enhancement (sutiles)", pr.harmonicEnabled) {
            SmartLiteConfig.setHarmonicEnabled(it)
        }
        slider(context, container, "Harmonic amount", (pr.harmonicAmount * 100).toInt(),
            enabled = !autoMode) {
            SmartLiteConfig.setHarmonicAmount(it / 100f)
        }

        // Speaker voicing
        headerA("Speaker voicing (TV / parlantes sencillos)")
        container.addView(TextView(context).apply {
            text = "Tonalidad para bocina pequeña (bass, calidez, diálogo, suavizado). OFF = transparencia."
            textSize = 12f
            setTextColor(0xFF90A4AE.toInt())
            setPadding(0, 0, 0, 4)
        })
        val spkChosen = ArrayList<RadioButton>()
        for (m in SmartLiteConfig.SpeakerMode.entries) {
            audioRow(context, container, m.label, "",
                checked = pr.speakerMode == m, chosen = spkChosen, enabled = !autoMode) {
                SmartLiteConfig.setSpeakerMode(m)
            }
        }

        // Crossfeed
        headerA("Crossfeed (auriculares)")
        val cfChosen = ArrayList<RadioButton>()
        for (m in SmartLiteConfig.CrossfeedMode.entries) {
            audioRow(context, container, m.label, "",
                checked = pr.crossfeed == m, chosen = cfChosen, enabled = !autoMode) {
                SmartLiteConfig.setCrossfeed(m)
            }
        }

        // True Peak ceiling
        headerA("True Peak Protection")
        val ceilings = floatArrayOf(-0.5f, -1.0f, -1.5f, -2.0f)
        val ceilLabels = arrayOf("−0.5 dBTP", "−1.0 dBTP", "−1.5 dBTP", "−2.0 dBTP")
        var ceilIdx = ceilings.indexOfFirst { it == pr.truePeakCeilingDb }.coerceAtLeast(1)
        container.addView(TextView(context).apply {
            text = "Techo: ${ceilLabels[ceilIdx]}"
            textSize = 14f
            setPadding(0, 4, 0, 2)
        })
        val ceilLabel = container.getChildAt(container.childCount - 1) as TextView
        container.addView(SeekBar(context).apply {
            max = 3
            progress = ceilIdx
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        ceilIdx = p
                        ceilLabel.text = "Techo: ${ceilLabels[p]}"
                        SmartLiteConfig.setTruePeakCeiling(ceilings[p])
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        // ── A/B ──
        headerA("A/B")
        container.addView(Button(context).apply {
            text = if (SmartLiteConfig.isAbBypass()) "A/B: Bypass (original)" else "A/B: Procesado"
            setOnClickListener {
                val now = !SmartLiteConfig.isAbBypass()
                SmartLiteConfig.setAbBypass(now)
                text = if (now) "A/B: Bypass (original)" else "A/B: Procesado"
                Toast.makeText(
                    context,
                    if (now) "Bypass · dry" else "Procesado · wet",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        // ── Verificación ──
        headerA("Verificación")
        container.addView(Button(context).apply {
            text = "Prueba ABX (¿oyes la diferencia?)"
            setOnClickListener { showAbxDialog(context) }
        })
        container.addView(Button(context).apply {
            text = "Copiar reporte del DSP"
            setOnClickListener {
                val report = SmartLiteReport.format(
                    SmartLiteConfig.params(),
                    SmartLiteMetrics.current,
                    SmartLiteConfig.detectedPlaybackOutput().label
                )
                Log.i("SmartLite", "\n$report")
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("smartlite", report))
                Toast.makeText(context, "Reporte copiado y en logcat", Toast.LENGTH_SHORT).show()
            }
        })

        // ── Monitor ──
        headerA("SmartLite Monitor")
        val monitor = TextView(context).apply {
            textSize = 13f
            setPadding(0, 4, 0, 4)
            text = formatMetrics(SmartLiteMetrics.current)
        }
        container.addView(monitor)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                monitor.text = formatMetrics(SmartLiteMetrics.current)
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
            .setTitle("Configuración avanzada")
            .setView(ScrollView(context).apply { addView(container) })
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /**
     * Prueba ABX: A = procesado, B = original, X = uno de los dos al azar.
     * Escucha A, B y X cuantas veces quieras y responde si X era A o B.
     * Al terminar muestra aciertos y valor p (p < 0.05 = diferencia audible).
     */
    fun showAbxDialog(context: Context) {
        val session = AbxSession(8)
        val previousBypass = SmartLiteConfig.isAbBypass()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 24)
        }
        val status = TextView(context).apply { textSize = 15f }
        box.addView(status)
        fun playWet() = SmartLiteConfig.setAbBypass(false)
        fun playDry() = SmartLiteConfig.setAbBypass(true)
        fun playX() = SmartLiteConfig.setAbBypass(!session.currentIsA())
        fun refresh() {
            status.text = if (session.done) {
                "Resultado: ${session.verdict()}"
            } else {
                "Ensayo ${session.current + 1}/${session.totalTrials} · aciertos ${session.correct}"
            }
        }
        fun button(label: String, onClick: () -> Unit) {
            box.addView(Button(context).apply {
                text = label
                setOnClickListener { onClick(); refresh() }
            })
        }
        button("Escuchar A (procesado)") { playWet() }
        button("Escuchar B (original)") { playDry() }
        button("Escuchar X (incógnita)") { playX() }
        button("X era A") { session.answer(true) }
        button("X era B") { session.answer(false) }
        refresh()
        AlertDialog.Builder(context)
            .setTitle("Prueba ABX")
            .setView(box)
            .setNegativeButton("Cerrar") { _, _ ->
                SmartLiteConfig.setAbBypass(previousBypass)
            }
            .setOnDismissListener { SmartLiteConfig.setAbBypass(previousBypass) }
            .show()
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Fila tipo radio con subtítulo opcional; la selección la decide el caller. */
    private fun audioRow(
        context: Context,
        container: LinearLayout,
        label: String,
        subtitle: String,
        checked: Boolean,
        chosen: MutableList<RadioButton>,
        enabled: Boolean = true,
        onSelect: () -> Unit,
    ) {
        val rb = RadioButton(context).apply {
            text = label
            textSize = 15f
            setTextColor(0xFFECEFF1.toInt())
            isChecked = checked
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
            isFocusable = false
            isClickable = false
        }
        if (checked) chosen.add(rb)
        val ll = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 6)
            isClickable = enabled
            isFocusable = enabled
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
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
                onSelect()
            }
        }
        container.addView(ll)
    }

    /** "Automático → TV Speaker" cuando el preset depende de la salida. */
    private fun presetButtonText(): String {
        val stored = SmartLiteConfig.preset()
        return if (stored == SmartLiteConfig.SlPreset.AUTO) {
            "${stored.label} → ${SmartLiteConfig.effectivePreset().label}"
        } else {
            stored.label
        }
    }

    private fun header(context: Context, container: LinearLayout, title: String, compact: Boolean = false) {
        container.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setPadding(0, if (compact) 12 else 16, 0, if (compact) 2 else 6)
        })
    }

    private fun slider(
        context: Context,
        container: LinearLayout,
        label: String,
        progress: Int,
        max: Int = 100,
        enabled: Boolean = true,
        onStop: (Int) -> Unit,
    ) {
        container.addView(TextView(context).apply {
            text = label
            textSize = 14f
            setPadding(0, 10, 0, 2)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
        })
        container.addView(SeekBar(context).apply {
            this.max = max
            this.progress = progress
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
            isFocusable = enabled
            isFocusableInTouchMode = enabled
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) onStop(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { onStop(progress) }
            })
        })
    }

    /** Volumen (0..100 %) a preferencias + reproductor en vivo. */    private fun applyVolume(percent: Int, onVolumeChange: ((Float) -> Unit)?) {
        val v = (percent / 100f).coerceIn(0.1f, 1f)
        AppPreferences.setPlayerVolume(v)
        onVolumeChange?.invoke(v)
    }

    /** dB de potencia correspondiente a la posición del slider (−12..+12). */
    private fun potenzaDb(progress: Int): Float = (progress - POT_CENTER) * POT_STEP_DB

    /** Etiqueta de potencia: porcentaje relativo + dB. */
    private fun potText(progress: Int): String {
        val db = potenzaDb(progress)
        val pct = 10.0.pow(db / 20.0) * 100.0
        return String.format(Locale.US, "Potencia: %d%% (%.1f dB)", pct.roundToInt(), db)
    }

    private fun formatMetrics(m: SmartLiteMetrics): String {
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
}
