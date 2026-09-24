package com.karin.streamtv.player

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import com.karin.streamtv.player.dsp.AudioEnhanceConfig
import com.karin.streamtv.player.dsp.AudioEnhanceProcessor

/**
 * Diálogo "Original vs Mejorado": muestra los datos técnicos del stream
 * original (lo que entrega ExoPlayer) y lo que cambia tras las opciones
 * avanzadas (escalador, filtros, MotionX2...).
 *
 * Correcciones aplicadas:
 * - Se lee el track SELECCIONADO (videoFormat/audioFormat primero, luego el
 *   grupo marcado como seleccionado; antes se tomaba el primero y en
 *   adaptativo/HLS mostraba otro bitrate/idioma).
 * - FPS honesto: solo INTERP/REAL60 emiten cuadros extra (~60 fps reales);
 *   mantienen la cadencia y solo suavizan (antes la fila FPS mostraba el
 *   nombre del modo como si fuera un fps).
 * - MotionX2 mapea los modos legacy (0=HYBRID,1=DOUBLING,2=BLEND,3=INTERP migrado
 *   a REAL60,4=REAL60).
 * - Upscaler con tope real 1080p y aviso no-op en >=1080p (espejo de
 *   SuperResolutionEffect.outputSizeFor / isNoOp).
 * - Light Boost distingue "luz OFF + color/rango solo" (antes decía
 *   "Light Boost OFF" aunque el color seguía activo).
 * - Se muestra la cadena REAL construida por ExoPlayerActivity (omitidos por
 *   presupuesto GPU incluidos); antes solo se leían prefs y podía decir
 *   "activo" algo que la GPU omitió.
 * - Canales con layout real (Mono/Estéreo/5.1/7.1), idioma "und"→"—",
 *   aspecto con PAR (pixelWidthHeightRatio), pantalla HDR con API moderna.
 */
object VideoStatsHelper {

    fun showStatsDialog(
        activity: Activity,
        player: ExoPlayer?,
        prefs: SharedPreferences,
        videoUrl: String?,
        realInput: Pair<Int, Int>? = null,
        realOutput: Pair<Int, Int>? = null,
        chainActive: List<String> = emptyList(),
        chainOmitted: List<String> = emptyList(),
        chainMotionLabel: String? = null,
        chainUpscalerLabel: String? = null,
        playbackSpeed: Float = 1f,
        aspectLabel: String? = null,
        dsp: AudioEnhanceProcessor? = null,
    ) {
        val video = pickVideoFormat(player)
        val audio = pickAudioFormat(player)

        // ---------- Entrada (lo que decodificó ExoPlayer) ----------
        val container = guessContainer(videoUrl, video)
        val vCodec = prettyVideoCodec(video)
        val inW = realInput?.first ?: (video?.width ?: 0)
        val inH = realInput?.second ?: (video?.height ?: 0)
        val vRes = if (inW > 0 && inH > 0) "${inW}×${inH}${resTag(inW, inH)}"
            else if ((video?.width ?: 0) > 0) "${video!!.width}×${video.height}${resTag(video.width, video.height)}" else "—"
        val vBitrate = fmtBitrate(effectiveVideoBitrate(video))
        val srcFps = (video?.frameRate ?: Format.NO_VALUE.toFloat()).let {
            if (it > 0 && it < 240) it else -1f
        }
        val vFps = if (srcFps > 0) "%.2f fps".format(srcFps) else "—"
        val aspect = videoAspect(video, realInput)
        val colorSpace = prettyColorSpace(video?.colorInfo)
        val primaries = prettyPrimaries(video?.colorInfo)
        val transfer = prettyTransfer(video?.colorInfo)
        val range = prettyRange(video?.colorInfo)
        val hdr = if (isHdr(video?.colorInfo)) "Sí (HDR)" else "No (SDR)"
        // Media3 no expone estos tres: valor típico de streaming.
        val chroma = "4:2:0 (est.)"
        val bitDepth = if (isHdr(video?.colorInfo)) "10-bit (est.)" else "8-bit (est.)"
        val scan = "Progresivo (est.)"
        val pantalla = displayHdrLabel(activity)

        val aCodec = prettyAudioCodec(audio)
        val aBitrate = fmtBitrate(effectiveAudioBitrate(audio))
        val aSample = if ((audio?.sampleRate ?: Format.NO_VALUE) > 0) "${audio!!.sampleRate} Hz" else "—"
        val aCh = prettyChannels(audio?.channelCount ?: Format.NO_VALUE)
        val aLang = prettyLanguage(audio?.language)
        val aComp = audioCompression(audio)

        // ---------- Salida (tras la cadena de efectos) ----------
        val motionReqOn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_MOTIONX2_EN, false)
        val motionLegacy = prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_MODE, 0).coerceIn(0, 5)
        val motionLabel = shortMotion(
            chainMotionLabel?.takeIf { it.isNotBlank() } ?: motionLegacyLabel(motionLegacy),
        )
        // Realidad del pipeline: INTERP/REAL60/ECO60/DOUBLING emiten cuadros
        // extra (60 fps o x2). HYBRID/BLEND son 1:1 (misma cadencia + suavizado).
        // Se usa la etiqueta REAL de la cadena; si no hay cadena aún, prefs.
        val isDoubling =
            motionLabel.contains("DOUBLING", ignoreCase = true) ||
                (chainMotionLabel.isNullOrBlank() && motionLegacy == 1)
        val isInterpReal = motionLabel.contains("60", ignoreCase = true) || isDoubling ||
            (chainMotionLabel.isNullOrBlank() && MotionX2Mode.isRealFps(motionLegacy))
        val finalFps = when {
            !motionReqOn -> vFps
            isDoubling -> if (srcFps > 0) "%.2f → ~%.2f".format(srcFps, srcFps * 2) else "~x2 fps"
            isInterpReal -> if (srcFps > 0) "%.2f → ~60".format(srcFps) else "~60 fps"
            else -> if (vFps == "—") "Suavizado" else "$vFps + suav."
        }
        val motionActiveReal = chainActive.any { it.contains("Motion", ignoreCase = true) }

        val finalVideoSize = realOutput ?: computeFinalSize(prefs, video, inW, inH)
        val finalRes = if (finalVideoSize != null && finalVideoSize.first > 0) {
            "${finalVideoSize.first}×${finalVideoSize.second}${resTag(finalVideoSize.first, finalVideoSize.second)}"
        } else vRes

        val upscalerOn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_UPSCALER_EN, false)
        val upscalerNoOp = upscalerOn && inH >= SuperResolutionEffect.MAX_UPSCALE_HEIGHT
        val upscaled = finalVideoSize != null && inW > 0 &&
            (finalVideoSize.first != inW || finalVideoSize.second != inH) &&
            finalVideoSize.first > 0 && finalVideoSize.second > 0
        val upscalerLabel = chainUpscalerLabel?.takeIf { it.isNotBlank() }
            ?: upscalerModeLabel(prefs)

        val changes = buildChanges(prefs, motionReqOn, motionLabel, upscalerLabel, upscalerNoOp, inH, aspectLabel)

        // ---------- UI ----------
        // Raíz lineal (TV: texto legible a distancia) + tabla solo para
        // las filas de datos con cabecera Entrada/Salida.
        val ctx = activity
        val acc = Color.parseColor("#FFB74D")
        val down = Color.parseColor("#81C784")
        val warn = Color.parseColor("#FF8A65")
        val labelColor = Color.parseColor("#E0E0E0")
        val noteColor = Color.parseColor("#B0BEC5")
        val dimColor = Color.parseColor("#90A4AE")

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 12)
        }
        val table = TableLayout(ctx).apply {
            setStretchAllColumns(true)
            setShrinkAllColumns(true)
            setColumnStretchable(0, true)
            setColumnStretchable(1, true)
            setColumnStretchable(2, true)
        }
        root.addView(
            table,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        fun cell(t: String, mono: Boolean = true, bold: Boolean = false, color: Int = Color.WHITE, size: Float = 14f): TextView {
            return TextView(ctx).apply {
                text = t
                textSize = size
                typeface = Typeface.create(
                    if (mono) Typeface.MONOSPACE else Typeface.DEFAULT,
                    if (bold) Typeface.BOLD else Typeface.NORMAL,
                )
                setTextColor(color)
                setPadding(8, 7, 8, 7)
            }
        }
        fun headerRow(c1: String, c2: String, c3: String) {
            val tr = TableRow(ctx)
            tr.addView(cell(c1, mono = false, bold = true, color = labelColor))
            tr.addView(cell(c2, mono = false, bold = true, color = acc))
            tr.addView(cell(c3, mono = false, bold = true, color = acc))
            table.addView(tr)
        }
        fun row(label: String, entrada: String, salida: String? = null, accented: Boolean = false) {
            val tr = TableRow(ctx)
            tr.addView(cell(label, mono = false, bold = false, color = labelColor))
            tr.addView(cell(entrada, color = Color.WHITE))
            val final = salida ?: entrada
            val changed = salida != null && salida != entrada
            tr.addView(cell(
                if (changed) "$final ✦" else final,
                color = if (changed) down else Color.WHITE,
            ))
            table.addView(tr)
        }
        fun section(t: String) {
            root.addView(TextView(ctx).apply {
                text = t
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(acc)
                setPadding(8, 20, 8, 6)
            })
        }
        fun separator() {
            root.addView(TextView(ctx).apply {
                text = "────────────────────────"
                textSize = 12f
                setTextColor(Color.parseColor("#55FFFFFF"))
                setPadding(8, 10, 8, 2)
            })
        }
        fun note(t: String, color: Int = noteColor) {
            root.addView(TextView(ctx).apply {
                text = t
                textSize = 13f
                setTextColor(color)
                setPadding(8, 6, 8, 0)
            })
        }
        fun techRow(label: String, detail: String, active: Boolean) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 7, 8, 7)
            }
            card.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(ctx).apply {
                    text = if (active) "●" else "○"
                    textSize = 14f
                    setTextColor(if (active) down else Color.parseColor("#607D8B"))
                    setPadding(0, 0, 10, 0)
                })
                addView(TextView(ctx).apply {
                    text = label
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (active) Color.WHITE else Color.parseColor("#CFD8DC"))
                }.also { it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                addView(TextView(ctx).apply {
                    text = if (active) "ON" else "OFF"
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (active) down else Color.parseColor("#78909C"))
                })
            })
            card.addView(TextView(ctx).apply {
                text = detail
                textSize = 13f
                setTextColor(dimColor)
                setPadding(24, 2, 0, 0)
            })
            root.addView(card)
        }

        root.addView(TextView(ctx).apply {
            text = "Entrada decodificada vs salida con efectos"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(acc)
            setPadding(8, 0, 8, 4)
        })
        headerRow("Campo", "Entrada", "Salida")

        section("Video · stream")
        row("Contenedor", container)
        row("Códec", vCodec)
        row("Resolución", vRes, finalRes, accented = upscaled)
        row("Aspecto", aspect)
        row("FPS", vFps, finalFps, accented = motionReqOn)
        if (playbackSpeed != 1f) row("Velocidad", "%.2fx".format(playbackSpeed))
        row("Bitrate", vBitrate)
        row("Color", colorSpace)
        row("Transfer / HDR", "$transfer • $hdr")
        row("Rango", range)
        row("Primarios", primaries)
        row("Chroma", chroma)
        row("Profundidad", bitDepth)
        row("Barrido", scan)
        row("Pantalla", pantalla)
        if (upscalerOn && upscalerNoOp) {
            note("Upscaler $upscalerLabel sin efecto: entrada ${inH}p ≥ tope 1080p.", warn)
        } else if (upscaled) {
            note("La resolución crece: el pipeline GL renderiza al tamaño final.")
        }
        separator()

        section("Audio · stream")
        row("Códec", aCodec)
        row("Bitrate", aBitrate)
        row("Muestreo", aSample)
        row("Canales", aCh)
        row("Idioma", aLang)
        row("Compresión", aComp)
        separator()

        section("Mejoras")
        if (changes.isEmpty()) {
            note("Sin mejoras activas: la salida es idéntica a la entrada.")
        } else {
            changes.forEach { note("• $it") }
        }
        if (motionReqOn && !motionActiveReal && chainActive.isNotEmpty()) {
            note("MotionX2 pedido pero omitido por límite GPU.", warn)
        }
        if (chainActive.isNotEmpty() || chainOmitted.isNotEmpty()) {
            note("Cadena real: ${if (chainActive.isEmpty()) "(sin filtros)" else chainActive.joinToString(" › ")}")
            if (chainOmitted.isNotEmpty()) {
                note("Omitidos por límite GPU: ${chainOmitted.joinToString(", ")}.", warn)
            }
        }
        if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DEMO_EN, false)) {
            note("Demo split: izquierda = original, derecha = con efectos.")
        }
        separator()

        section("Sonido · DSP activo")
        // Valor efectivo real (preset + tuning por dispositivo + asistencia
        // de audición): es lo que de verdad suena, no solo el preset base.
        val acfg = AudioEnhanceConfig.effectiveParamsForDisplay(AudioEnhanceConfig.params())
        val audSp = AudioEnhanceConfig.getAudSpeech()
        val audLo = AudioEnhanceConfig.getAudLoss()
        fun tech(
            label: String,
            detail: String,
            active: Boolean,
        ) { techRow(label, detail, active) }
        tech("Exteriorización", "Crossfeed binaural: suena fuera de la cabeza",
            acfg.irType == AudioEnhanceConfig.IrPreset.CROSSFEED)
        tech("Graves, agudos y estéreo", "Bass/treble/presence y panorama",
            acfg.harmonicBass > 0f || acfg.bassGain != 0f || acfg.trebleGain != 0f || acfg.surroundWidth > 0f)
        val totalBands = AudioEnhanceConfig.EQ_FREQS.size
        val activeBands = (dsp?.activeEqBands ?: acfg.eq10?.size ?: totalBands).coerceIn(1, totalBands)
        tech("Ecualizador y presets", "Curva del perfil + EQ de $activeBands de $totalBands bandas",
            acfg.eq10 != null)
        tech("Surround virtual", "Campo Haas + panorama estéreo",
            acfg.fieldSurround > 0f || acfg.surroundWidth > 0f)
        tech("TruBass / MaxxBass", "Sub virtual por síntesis armónica",
            acfg.harmonicBass > 0f)
        tech("Diálogos claros", "Presence + limpieza de voz",
            acfg.speechClarity || acfg.presenceGain > 0f)
        tech("Sonoridad", "Compensa graves/agudos a volumen bajo",
            acfg.loudnessComp)
        tech("Compresor", "Nivelación dinámica",
            acfg.compression > 0f)
        if (audSp > 0f || audLo > 0f) {
            val parts = mutableListOf<String>()
            if (audSp > 0f) parts.add("diálogos ${(audSp * 100).toInt()}%")
            if (audLo > 0f) parts.add("agudos ${(audLo * 100).toInt()}%")
            tech("Asistencia audición", parts.joinToString(" + "), true)
        }
        note("Perfil: ${acfg.preset.label} · Master ${"%.2f".format(acfg.masterGain)}x · " +
            "Auto ${if (acfg.autoDevice) "ON" else "OFF"}")
        separator()

        note("“(est.)” = Media3 no lo expone; valor típico. ✦ = lo cambia la cadena.")

        val scroll = ScrollView(ctx).apply {
            addView(root)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Estadísticas")
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    // ---------- Etiquetas de modos (coherentes con Opciones avanzadas) ----------

    /** Legacy guardado en prefs: 0=HYBRID, 1=DOUBLING, 2=BLEND, 3=INTERP(migrado a REAL60), 4=REAL60, 5=ECO60. Corto para tabla. */
    fun motionLegacyLabel(legacy: Int): String {
        return when (legacy.coerceIn(0, 5)) {
            1 -> "DOUBLING x2"
            2 -> "BLEND"
            3, 4 -> "REAL60"
            5 -> "ECO60"
            else -> "HYBRID"
        }
    }

    /** Condensa etiquetas largas del pipeline ("HYBRID (Doubling + Micro-Blend)") a cortas. */
    private fun shortMotion(label: String): String {
        val l = label.uppercase()
        return when {
            l.contains("ECO") -> "ECO60"
            l.contains("GRID") || l.contains("60 FPS") || l.contains("REAL60") || l.contains("INTERP") -> "REAL60"
            l.contains("DOUBLING") || l.contains("X2 CUADROS") -> "DOUBLING x2"
            l.contains("BLEND") && !l.contains("HYBRID") -> "BLEND"
            else -> "HYBRID"
        }
    }

    private fun upscalerModeLabel(prefs: SharedPreferences): String {
        return when (prefs.getInt(ExoPlayerSettingsHelper.KEY_UPSCALER_MODE, SuperResolutionEffect.MODE_FSR)) {
            SuperResolutionEffect.MODE_ANIME4K -> "Anime4K"
            SuperResolutionEffect.MODE_KARIN -> "Karin"
            else -> "FSR"
        }
    }

    private fun videoAspect(v: Format?, realInput: Pair<Int, Int>?): String {
        var w = realInput?.first ?: (v?.width ?: 0)
        var h = realInput?.second ?: (v?.height ?: 0)
        if (w <= 0 || h <= 0) return "—"
        val par = pixelAspect(v)
        // Anamórfico (DVD 720x480 con PAR): el display aspect corrige.
        val dw = (w * par)
        val flat = "%.2f:1".format(dw / h.toFloat())
        // Reducir con el DAR redondeado para la etiqueta Nx:M.
        val dwI = (dw + 0.5f).toInt()
        val g = gcd(dwI, h)
        val rx = dwI / g
        val ry = h / g
        val parNote = if (par !in 0.99f..1.01f) " [PAR %.2f]".format(par) else ""
        return when {
            rx == 16 && ry == 9 -> "16:9 ($flat, panorámico)$parNote"
            rx == 4 && ry == 3 -> "4:3 ($flat)$parNote"
            rx == 21 && ry == 9 -> "21:9 ($flat, ultrawide)$parNote"
            rx == 1 && ry == 1 -> "1:1 (cuadrado)"
            rx == 9 && ry == 16 -> "9:16 ($flat, vertical)$parNote"
            else -> "$rx:$ry ($flat)$parNote"
        }
    }

    private fun pixelAspect(v: Format?): Float {
        if (v == null) return 1f
        return try {
            val f = Format::class.java.getField("pixelWidthHeightRatio").getFloat(v)
            if (f.isFinite() && f > 0.1f && f < 10f) f else 1f
        } catch (_: Exception) {
            1f
        }
    }

    private fun gcd(a0: Int, b0: Int): Int {
        var a = a0; var b = b0
        if (a <= 0 || b <= 0) return 1
        while (b != 0) { val t = b; b = a % b; a = t }
        return if (a <= 0) 1 else a
    }

    // ---------- Origen de datos ----------

    /**
     * Tamaño final estimado cuando el pipeline GL aún no reportó el real.
     * Espejo de SuperResolutionEffect (2x con tope 1080p, no-op en >=1080p).
     */
    private fun computeFinalSize(
        prefs: SharedPreferences,
        v: Format?,
        inW: Int = v?.width ?: 0,
        inH: Int = v?.height ?: 0,
    ): Pair<Int, Int>? {
        if (inW <= 0 || inH <= 0) return null
        if (!prefs.getBoolean(ExoPlayerSettingsHelper.KEY_UPSCALER_EN, false)) {
            return (inW to inH)
        }
        if (inH >= SuperResolutionEffect.MAX_UPSCALE_HEIGHT) return (inW to inH)
        return try {
            val out = SuperResolutionEffect.outputSizeFor(inW, inH, restorePass = false)
            (out.width to out.height)
        } catch (_: Exception) {
            (inW to inH)
        }
    }

    /** Track de video SELECCIONADO (no el primero del grupo adaptativo). */
    private fun pickVideoFormat(player: ExoPlayer?): Format? {
        if (player == null) return null
        try {
            player.videoFormat?.let { return it }
        } catch (_: Exception) { }
        return scanTracks(player, true)
    }

    /** Track de audio SELECCIONADO. */
    private fun pickAudioFormat(player: ExoPlayer?): Format? {
        if (player == null) return null
        try {
            player.audioFormat?.let { return it }
        } catch (_: Exception) { }
        return scanTracks(player, false)
    }

    private fun scanTracks(player: ExoPlayer, video: Boolean): Format? {
        return try {
            var fallback: Format? = null
            for (g in player.currentTracks.groups) {
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    val m = (f.sampleMimeType ?: "").lowercase()
                    val match = if (video) m.startsWith("video/") else m.startsWith("audio/")
                    if (!match) continue
                    if (fallback == null) fallback = f
                    try {
                        if (g.isSelected) return f
                    } catch (_: Exception) { }
                }
            }
            fallback
        } catch (_: Exception) {
            null
        }
    }

    /** Bitrate efectivo: bitrate → average → peak → NO_VALUE. */
    private fun effectiveVideoBitrate(f: Format?): Int = effectiveBitrate(f)

    private fun effectiveAudioBitrate(f: Format?): Int = effectiveBitrate(f)

    private fun effectiveBitrate(f: Format?): Int {
        if (f == null) return Format.NO_VALUE
        if (f.bitrate > 0) return f.bitrate
        // Campos por versión (averageBitrate/peakBitrate) vía reflexión segura.
        for (name in arrayOf("averageBitrate", "peakBitrate")) {
            try {
                val v = Format::class.java.getField(name).getInt(f)
                if (v > 0) return v
            } catch (_: Exception) { }
        }
        return Format.NO_VALUE
    }

    // ---------- Formato ----------

    private fun guessContainer(url: String?, v: Format?): String {
        val raw = (url ?: "").lowercase()
        val path = raw.substringBefore('?').substringBefore('#').trim()
        val fromUrl = when {
            path.contains(".m3u8") || (raw.contains("m3u8") && raw.contains("http")) -> "HLS (.m3u8)"
            path.endsWith(".mpd") || raw.contains(".mpd") -> "DASH (.mpd)"
            path.endsWith(".mp4") -> "MP4"
            path.endsWith(".m4v") -> "M4V"
            path.endsWith(".mkv") -> "MKV"
            path.endsWith(".webm") -> "WebM"
            path.endsWith(".avi") -> "AVI"
            path.endsWith(".mov") -> "MOV"
            path.endsWith(".flv") -> "FLV"
            path.endsWith(".ts") || path.endsWith(".m2ts") || path.endsWith(".mts") -> "MPEG-TS"
            path.endsWith(".mp3") -> "MP3"
            path.endsWith(".ogg") || path.endsWith(".ogv") -> "Ogg"
            path.startsWith("content:") || path.startsWith("file:") || path.startsWith("/") ->
                "Archivo local (" + path.substringAfterLast('.', "").take(5).uppercase().ifBlank { "?" } + ")"
            else -> null
        }
        if (fromUrl != null) return fromUrl
        val c = (v?.containerMimeType ?: v?.sampleMimeType ?: "").lowercase()
        return when {
            c.contains("x-mpegurl") || c.contains("mpegurl") || c.contains("m3u8") -> "HLS (.m3u8)"
            c.contains("dash") || c == "application/dash+xml" -> "DASH (.mpd)"
            c.contains("matroska") || c.contains("x-matroska") -> "MKV"
            c.contains("webm") -> "WebM"
            c.contains("mp4") -> "MP4"
            c.contains("avi") || c.contains("x-msvideo") -> "AVI"
            c.contains("quicktime") -> "MOV"
            c.contains("mpeg") -> "MPEG-TS"
            else -> "—"
        }
    }

    private fun prettyVideoCodec(f: Format?): String {
        if (f == null) return "—"
        val raw = ((f.codecs ?: f.sampleMimeType) ?: "").lowercase()
        val name = when {
            raw.contains("dvhe") || raw.contains("dvh1") || raw.contains("dolby") -> "Dolby Vision"
            raw.contains("av01") || raw == "video/av1" || raw.contains("av1") -> "AV1"
            raw.contains("hev1") || raw.contains("hvc1") || raw.contains("h265") || raw.contains("hevc") -> "H.265 / HEVC"
            raw.contains("avc") || raw.contains("h264") -> "H.264 / AVC"
            raw.contains("vp09") || raw.contains("vp9") -> "VP9"
            raw.contains("vp08") || raw == "video/vp8" -> "VP8"
            raw.contains("mp4v") -> "MPEG-4 ASP"
            raw.contains("mp2v") || raw.contains("mpeg2") -> "MPEG-2"
            else -> (f.codecs ?: f.sampleMimeType ?: "—")
        }
        val tag = f.codecs?.takeIf { it.isNotBlank() }
        return if (tag != null && !tag.equals(name, true)) "$name ($tag)" else name
    }

    private fun prettyAudioCodec(f: Format?): String {
        if (f == null) return "—"
        val raw = ((f.codecs ?: f.sampleMimeType) ?: "").lowercase()
        return when {
            raw.contains("ec-3") || raw.contains("eac3") || raw.contains("atmos") -> "E-AC-3 (Dolby+)"
            raw.contains("ac-3") || raw.contains("ac3") -> "AC-3 (Dolby)"
            raw.contains("truehd") -> "TrueHD"
            raw.contains("dts") -> "DTS"
            raw.contains("mp4a") || raw.contains("aac") -> "AAC"
            raw.contains("opus") -> "Opus"
            raw.contains("vorbis") -> "Vorbis"
            raw.contains("flac") -> "FLAC"
            raw.contains("alac") -> "ALAC"
            raw.contains("pcm") || raw.contains("lpcm") || raw.contains("raw") -> "PCM"
            raw.contains("mp3") || raw.contains("mpeg") && raw.contains("audio") -> "MP3"
            else -> (f.codecs ?: f.sampleMimeType ?: "—")
        }
    }

    private fun prettyChannels(ch: Int): String {
        if (ch == Format.NO_VALUE || ch <= 0) return "—"
        return when (ch) {
            1 -> "1 ch (mono)"
            2 -> "2 ch (estéreo)"
            3 -> "3 ch (2.1)"
            4 -> "4 ch (cuadrafónico)"
            6 -> "6 ch (5.1)"
            8 -> "8 ch (7.1)"
            else -> "$ch ch" + if (ch > 2) " (multicanal)" else ""
        }
    }

    private fun prettyLanguage(lang: String?): String {
        val l = (lang ?: "").trim()
        if (l.isBlank() || l.equals("und", true) || l == "-") return "—"
        return l
    }

    private fun audioCompression(f: Format?): String {
        val raw = ((f?.codecs ?: f?.sampleMimeType) ?: "").lowercase()
        return when {
            raw.contains("flac") || raw.contains("alac") || raw.contains("wav") ||
                raw.contains("pcm") || raw.contains("truehd") -> "Sin pérdida"
            raw.contains("aac") || raw.contains("mp3") || raw.contains("opus") ||
                raw.contains("vorbis") || raw.contains("ac-3") || raw.contains("ec-3") ||
                raw.contains("dts") -> "Con pérdida"
            f == null -> "—"
            else -> "Con pérdida (estimado)"
        }
    }

    private fun fmtBitrate(b: Int): String {
        if (b == Format.NO_VALUE || b <= 0) return "—"
        return when {
            b >= 1_000_000 -> "%.2f Mbps".format(b / 1_000_000f)
            b >= 1_000 -> "%d kbps".format(b / 1_000)
            else -> "$b bps"
        }
    }

    private fun resTag(w: Int, h: Int): String {
        val hi = maxOf(w, h)
        val lo = minOf(w, h)
        return when {
            hi >= 3800 -> " (4K UHD)"
            hi >= 1900 && lo >= 1000 -> " (Full HD)"
            hi >= 1900 -> " (Full HD)"
            hi >= 1250 && lo >= 680 -> " (HD)"
            hi >= 1250 -> " (HD)"
            hi >= 700 -> " (SD)"
            hi > 0 -> " (baja)"
            else -> ""
        }
    }

    private fun isHdr(c: ColorInfo?): Boolean {
        if (c == null) return false
        return try {
            c.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
                c.colorTransfer == C.COLOR_TRANSFER_HLG
        } catch (_: Exception) {
            false
        }
    }

    /** Capacidad HDR de la pantalla (API 24+, ruta moderna en API 30+). */
    private fun displayHdrLabel(activity: Activity): String {
        return try {
            if (Build.VERSION.SDK_INT < 24) return "SDR (API <24, sin dato)"
            val types: IntArray = try {
                if (Build.VERSION.SDK_INT >= 30) {
                    activity.display?.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
                } else {
                    @Suppress("DEPRECATION")
                    activity.windowManager?.defaultDisplay?.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
                }
            } catch (_: Exception) {
                IntArray(0)
            }
            if (types.isEmpty()) return "SDR (sin HDR)"
            val names = types.map {
                when (it) {
                    android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DV"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                    else -> "?"
                }
            }.distinct()
            "HDR (" + names.joinToString("/") + ")"
        } catch (_: Exception) {
            "—"
        }
    }

    private fun prettyColorSpace(c: ColorInfo?): String {
        if (c == null) return "—"
        return try {
            when (c.colorSpace) {
                C.COLOR_SPACE_BT601 -> "BT.601 (SD)"
                C.COLOR_SPACE_BT709 -> "BT.709 (HD)"
                C.COLOR_SPACE_BT2020 -> "BT.2020 (UHD)"
                else -> "Desconocido (${c.colorSpace})"
            }
        } catch (_: Exception) {
            "—"
        }
    }

    private fun prettyPrimaries(c: ColorInfo?): String {
        if (c == null) return "—"
        return try {
            when (c.colorSpace) {
                C.COLOR_SPACE_BT601 -> "BT.601"
                C.COLOR_SPACE_BT709 -> "BT.709"
                C.COLOR_SPACE_BT2020 -> "BT.2020"
                else -> "Desconocidas"
            }
        } catch (_: Exception) {
            "—"
        }
    }

    private fun prettyTransfer(c: ColorInfo?): String {
        if (c == null) return "—"
        return try {
            when (c.colorTransfer) {
                C.COLOR_TRANSFER_SDR -> "SDR (gamma)"
                C.COLOR_TRANSFER_ST2084 -> "PQ / ST.2084 (HDR10)"
                C.COLOR_TRANSFER_HLG -> "HLG (HDR)"
                else -> "Transfer ${c.colorTransfer}"
            }
        } catch (_: Exception) {
            "—"
        }
    }

    private fun prettyRange(c: ColorInfo?): String {
        if (c == null) return "—"
        return try {
            when (c.colorRange) {
                C.COLOR_RANGE_LIMITED -> "Limitado (16-235)"
                C.COLOR_RANGE_FULL -> "Completo (0-255)"
                else -> "Desconocido"
            }
        } catch (_: Exception) {
            "—"
        }
    }

    private fun buildChanges(
        prefs: SharedPreferences,
        motionOn: Boolean,
        motionMode: String,
        upscalerLabel: String,
        upscalerNoOp: Boolean,
        inputH: Int,
        aspectLabel: String?,
    ): List<String> {
        val out = mutableListOf<String>()
        if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_RESTORE_EN, false)) {
            val m = prefs.getInt(ExoPlayerSettingsHelper.KEY_RESTORE_STRENGTH, 60)
            var line = "Restore Boost $m%"
            if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_RESTORE_CUSTOM, false)) {
                val d = prefs.getInt(ExoPlayerSettingsHelper.KEY_DEPIXEL_STRENGTH, 60)
                val r = prefs.getInt(ExoPlayerSettingsHelper.KEY_RETRO_STRENGTH, 60)
                val t = prefs.getInt(ExoPlayerSettingsHelper.KEY_DETAIL_BOOST_STRENGTH, 70)
                line += " [L$d/R$r/D$t]"
            }
            out += line
        }
        val cineEn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_CINE_EN, false)
        val colorsEn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_COLORS_EN, false)
        val rangeMode = prefs.getInt(ExoPlayerSettingsHelper.KEY_RANGE_MODE, 0)
        if (cineEn || colorsEn || rangeMode != 0) {
            val m = prefs.getInt(ExoPlayerSettingsHelper.KEY_CINE_STRENGTH, 50)
            val cineAutoMode = prefs.getInt(ExoPlayerSettingsHelper.KEY_CINE_MODE, 0) == 1
            var line = if (!cineEn) "Color/Rango (luz OFF)"
                else if (cineAutoMode) "Light Boost AUTO $m%"
                else "Light Boost $m%"
            if (colorsEn) line += " + Color ${prefs.getInt(ExoPlayerSettingsHelper.KEY_COLORS_STRENGTH, 60)}%"
            line += when (rangeMode) {
                1 -> " + Expandir"
                2 -> " + Comprimir"
                else -> ""
            }
            out += line
        }
        if (motionOn) {
            val isDoubling = motionMode.contains("DOUBLING", ignoreCase = true)
            val isInterp = motionMode.contains("INTERP", ignoreCase = true) ||
                motionMode.contains("GRID", ignoreCase = true) ||
                motionMode.contains("60 fps", ignoreCase = true) ||
                motionMode.contains("ECO", ignoreCase = true)
            out += when {
                isDoubling -> "MotionX2 $motionMode (~x2 fps)"
                isInterp -> "MotionX2 $motionMode (~60 fps)"
                else -> "MotionX2 $motionMode (suavizado)"
            }
        }
        run {
            val (t, s) = ExoPlayerSettingsHelper.shaderSelection(prefs)
            if (t != ExoPlayerSettingsHelper.SHADER_OFF && s > 0f) {
                val kind = ExoPlayerSettingsHelper.shaderTypeName(t)
                out += "Shader $kind ${(s * 100).toInt()}% (acabado final)"
            }
        }
        if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_UPSCALER_EN, false)) {
            out += if (upscalerNoOp) {
                "Upscaler $upscalerLabel ${prefs.getInt(ExoPlayerSettingsHelper.KEY_UPSCALER_SHARP, 40)}% (sin efecto en ${inputH}p+)"
            } else {
                "Upscaler $upscalerLabel ${prefs.getInt(ExoPlayerSettingsHelper.KEY_UPSCALER_SHARP, 40)}% (2x, tope 1080p)"
            }
        }
        if (!aspectLabel.isNullOrBlank() && aspectLabel != "Original") {
            out += "Proporción $aspectLabel (solo vista)"
        }
        return out
    }
}
