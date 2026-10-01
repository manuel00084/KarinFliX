package com.karin.streamtv.player

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.Debug
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.karin.streamtv.player.dsp.AudioEnhanceProcessor
import com.karin.streamtv.player.dsp.smartlite.SmartLiteConfig
import com.karin.streamtv.util.DeviceProfile

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
        aspectLabel: String? = null,
        aspectRatioMode: Int = ExoPlayerSettingsHelper.MODE_ORIGINAL,
        dsp: AudioEnhanceProcessor? = null,
        ultra: Boolean = false,
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
        val aspectOut = when (aspectRatioMode) {
            ExoPlayerSettingsHelper.MODE_4_3 -> "4:3 (forzado)"
            ExoPlayerSettingsHelper.MODE_16_9 -> "16:9 (forzado)"
            ExoPlayerSettingsHelper.MODE_2_35 -> "2.35:1 (forzado)"
            ExoPlayerSettingsHelper.MODE_ZOOM -> "Zoom (recorte)"
            ExoPlayerSettingsHelper.MODE_STRETCH -> "Estirar (relleno)"
            else -> aspect
        }
        val colorSpace = prettyColorSpace(video?.colorInfo)
        val transfer = prettyTransfer(video?.colorInfo)
        val range = prettyRange(video?.colorInfo)
        val hdr = if (isHdr(video?.colorInfo)) "Sí (HDR)" else "No (SDR)"
        // Media3 no expone el muestreo de croma: valor típico de streaming
        // (el bit-depth SÍ lo expone ColorInfo.lumaBitdepth/chromaBitdepth).
        val chroma = "4:2:0 (est.)"
        val bitDepth = bitDepthLabel(video?.colorInfo)
        val scan = "Progresivo (est.)"
        val pantalla = displayHdrLabel(activity)

        val aCodec = prettyAudioCodec(audio)
        val aBitrate = fmtBitrate(effectiveAudioBitrate(audio))
        val aSample = if ((audio?.sampleRate ?: Format.NO_VALUE) > 0) "${audio!!.sampleRate} Hz" else "—"
        val aCh = prettyChannels(audio?.channelCount ?: Format.NO_VALUE)
        val aLang = prettyLanguage(audio?.language)
        val aComp = audioCompression(audio)

        // ---------- Datos nuevos que expone ExoPlayer (Media3 1.11) ----------
        // Formato: id/etiqueta de pista, perfil del códec, resolución decodificada
        // real, metadatos HDR estáticos (ST.2086 MaxCLL/MaxFALL), roles y flags
        // de selección, DRM y los contadores reales del decodificador
        // (frames renderizados/caídos, latencia media, buffers de audio).
        val videoTrack = formatTag(video)
        val videoProfile = prettyVideoProfile(video)
        val videoDecoded = decodedSize(video)
        val hdrStatic = hdrStaticInfo(video?.colorInfo)
        val videoRoles = roleFlagsLabel(video?.roleFlags)
        val videoSel = selectionFlagsLabel(video?.selectionFlags)
        val videoDrm = drmLabel(video)
        val videoFrames = videoFramesLine(player)
        val audioTrack = formatTag(audio)
        val audioProfile = prettyAudioProfile(audio)
        val audioPcm = pcmLabel(audio)
        val audioDelay = encoderDelayLabel(audio)
        val audioRoles = roleFlagsLabel(audio?.roleFlags)
        val audioSel = selectionFlagsLabel(audio?.selectionFlags)
        val audioDrm = drmLabel(audio)
        val audioBuffers = audioBuffersLine(player)

        // ---------- Salida (tras la cadena de efectos) ----------
        // En ultra económico el reproductor NO monta ningún filtro: lo que
        // haya en los ajustes queda sin aplicar, así que no se cuenta como
        // "pedido" para no reportar mejoras que no están corriendo.
        val motionReqOn = !ultra &&
            prefs.getBoolean(ExoPlayerSettingsHelper.KEY_MOTIONX2_EN, false)
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

        val upscalerOn = !ultra &&
            prefs.getBoolean(ExoPlayerSettingsHelper.KEY_UPSCALER_EN, false)
        val upscalerNoOp = upscalerOn && inH >= SuperResolutionEffect.MAX_UPSCALE_HEIGHT
        val upscaled = finalVideoSize != null && inW > 0 &&
            (finalVideoSize.first != inW || finalVideoSize.second != inH) &&
            finalVideoSize.first > 0 && finalVideoSize.second > 0
        val upscalerLabel = chainUpscalerLabel?.takeIf { it.isNotBlank() }
            ?: upscalerModeLabel(prefs)

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
            gravity = android.view.Gravity.CENTER_HORIZONTAL
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
                gravity = android.view.Gravity.CENTER
            }
        }
        fun headerRow(c1: String, c2: String, c3: String) {
            val tr = TableRow(ctx)
            tr.addView(cell(c1, mono = false, bold = true, color = labelColor).apply {
                gravity = android.view.Gravity.START
            })
            tr.addView(cell(c2, mono = false, bold = true, color = acc))
            tr.addView(cell(c3, mono = false, bold = true, color = acc))
            table.addView(tr)
        }
        fun row(label: String, entrada: String, salida: String? = null, accented: Boolean = false) {
            val tr = TableRow(ctx)
            tr.addView(cell(label, mono = false, bold = false, color = labelColor).apply {
                gravity = android.view.Gravity.START
            })
            tr.addView(cell(entrada, color = Color.WHITE))
            val final = salida ?: entrada
            val changed = salida != null && salida != entrada
            tr.addView(cell(
                if (changed) "$final ✦" else final,
                color = if (changed) down else Color.WHITE,
            ))
            table.addView(tr)
        }
        // Fila de ancho completo: BUG QUE SE ARREGLA AQUI. Antes usaba
        // TableRow.LayoutParams(0, 1, 3f): altura EXACTA de 1px, así que
        // títulos de sección, separadores, notas, techRows y las filas de
        // hardware salían de 1px = invisibles. Solo se veían las row() de
        // 3 columnas. Ahora: alto envolvente y span de las 3 columnas.
        fun span(view: View) {
            val tr = TableRow(ctx)
            tr.addView(view, TableRow.LayoutParams(
                TableRow.LayoutParams.MATCH_PARENT,
                TableRow.LayoutParams.WRAP_CONTENT,
            ).apply { span = 3 })
            table.addView(tr)
        }
        fun section(t: String) {
            span(TextView(ctx).apply {
                text = t
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(acc)
                setPadding(8, 20, 8, 6)
            })
        }
        // División invisible: antes era una línea de guiones; ahora solo aire,
        // la división la marcan los títulos de sección (ya visibles tras el
        // fix de span()).
        fun separator() {
            span(TextView(ctx).apply {
                text = ""
                setPadding(8, 12, 8, 2)
            })
        }
        fun note(t: String, color: Int = noteColor) {
            span(TextView(ctx).apply {
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
            span(card)
        }

        root.addView(TextView(ctx).apply {
            text = if (ultra) "Entrada decodificada vs salida real (ultra: sin filtros)"
            else "Entrada decodificada vs salida con efectos"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(acc)
            setPadding(8, 0, 8, 4)
        })

        // Estado REAL por función: lo pedido en ajustes vs lo que de verdad
        // corre en la cadena (● ON / ○ OFF + motivo). Va primero: es lo que
        // se quiere saber de un vistazo.
        section("Mejoras · imagen")
        val pausedByOwnRender = chainActive.any { it.contains("render propio", ignoreCase = true) }
        fun offReason(req: Boolean): String = when {
            !req -> "Apagado en ajustes"
            ultra -> "Apagado por ultra económico"
            pausedByOwnRender -> "En pausa: MotionX2 60fps usa render propio"
            else -> "Pedido pero omitido por límite GPU"
        }
        run {
            val detail = if (motionActiveReal) {
                val fx = when {
                    isDoubling -> "~x2 fps"
                    isInterpReal -> "~60 fps"
                    else -> "suavizado"
                }
                val own = if (pausedByOwnRender) " · render propio" else ""
                "$motionLabel · $fx$own"
            } else offReason(motionReqOn)
            techRow("MotionX2", detail, motionActiveReal)
        }
        run {
            val realOn = chainActive.any { it == "Upscaler" }
            val detail = when {
                realOn -> "Upscaler $upscalerLabel activo" + if (upscaled) " (✦)" else ""
                upscalerOn && upscalerNoOp -> "Sin efecto en ${inH}p+ (solo SD/720p)"
                else -> offReason(upscalerOn)
            }
            techRow("Upscaler", detail, realOn)
        }
        run {
            val req = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_RESTORE_EN, false)
            val realOn = chainActive.any { it == "Restore" }
            val m = prefs.getInt(ExoPlayerSettingsHelper.KEY_RESTORE_STRENGTH, 60)
            val detail = if (realOn) "Restore Boost $m% activo en cadena" else offReason(req)
            techRow("Restore Boost", detail, realOn)
        }
        run {
            val cineEn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_CINE_EN, false)
            val colorsEn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_COLORS_EN, false)
            val rangeMode = prefs.getInt(ExoPlayerSettingsHelper.KEY_RANGE_MODE, 0)
            val req = cineEn || colorsEn || rangeMode != 0
            val realOn = chainActive.any { it == "Light+Color" }
            val m = prefs.getInt(ExoPlayerSettingsHelper.KEY_CINE_STRENGTH, 50)
            val what = buildList {
                if (cineEn) add(if (prefs.getInt(ExoPlayerSettingsHelper.KEY_CINE_MODE, 0) == 1) "Light AUTO $m%" else "Light $m%")
                if (colorsEn) add("Color ${prefs.getInt(ExoPlayerSettingsHelper.KEY_COLORS_STRENGTH, 60)}%")
                if (rangeMode == 1) add("Limitado→Completo")
                if (rangeMode == 2) add("Completo→Limitado")
            }.joinToString(" + ").ifBlank { "Luz/Color/Rango" }
            val detail = if (realOn) "$what activo en cadena" else offReason(req)
            techRow("Light Boost / Color", detail, realOn)
        }
        run {
            val (t, s) = ExoPlayerSettingsHelper.shaderSelection(prefs)
            val req = t != ExoPlayerSettingsHelper.SHADER_OFF && s > 0f
            val realOn = chainActive.any { it.startsWith("Shader:") }
            val detail = if (realOn) {
                "Shader ${ExoPlayerSettingsHelper.shaderTypeName(t)} ${(s * 100).toInt()}% (acabado final)"
            } else offReason(req)
            techRow("Shader (acabado)", detail, realOn)
        }
        // ── Asistencia / accesibilidad (botón de anteojos) ──
        run {
            val cfg = VisionAssistHelper.fromPrefs(prefs)
            val realOn = chainActive.any { it == "Visión" }
            val detail = if (realOn) {
                "${VisionAssistHelper.needsLabel(cfg)} · fuera del cupo GPU (accesibilidad)"
            } else when {
                !cfg.isActive -> "Apagada en ajustes"
                ultra -> "Apagada por ultra económico"
                pausedByOwnRender -> "En pausa: MotionX2 60fps usa render propio"
                else -> "Pedida pero omitida por límite GPU"
            }
            techRow("Ayuda visual", detail, realOn)
        }
        run {
            val cfg = VisionAssistHelper.fromPrefs(prefs)
            val realOn = chainActive.any { it == "Audición" }
            val detail = if (realOn) {
                val parts = mutableListOf<String>()
                if (cfg.hasAudSpeech) parts.add("diálogos ${(cfg.audSpeech * 100).toInt()}%")
                if (cfg.hasAudLoss) parts.add("agudos ${(cfg.audLoss * 100).toInt()}%")
                "${parts.joinToString(" + ")} · corre en el DSP de audio"
            } else if (!cfg.hasAudSpeech && !cfg.hasAudLoss) {
                "Apagada en ajustes"
            } else if (ultra) {
                "Apagada por ultra económico"
            } else {
                chainOmitted.firstOrNull { it.startsWith("Audición") }
                    ?.removePrefix("Audición")
                    ?.trim()
                    ?.let { "Pedida pero sin efecto: $it" }
                    ?: "Pedida pero sin efecto"
            }
            techRow("Ayuda auditiva", detail, realOn)
        }
        run {
            val label3d = Karin3DController.chainLabel(prefs)
            val realOn = label3d.isNotBlank() && chainActive.any { it == label3d }
            val detail = if (realOn) "$label3d · último de la cadena (reformatea la salida)"
            else if (label3d.isBlank()) "Apagado en ajustes"
            else if (ultra) "Apagado por ultra económico"
            else "Pedido pero sin efecto"
            techRow("3D", detail, realOn)
        }
        if (!ultra && prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DEMO_EN, false)) {
            techRow("Demo", "Mitad izquierda original, derecha con efectos", chainActive.contains("Demo"))
        }
        // Orden real de aplicación: es lo que explica por qué se ve lo que se ve.
        if (chainActive.isNotEmpty()) {
            note("Cadena activa (en orden): ${chainActive.joinToString(" › ")}")
        } else if (ultra) {
            note("Cadena activa: (vacía) — el ultra económico no monta filtros; la salida es la pista elegida, sin mejoras de imagen.")
        } else {
            note("Cadena activa: (sin filtros) — la salida es idéntica a la entrada.")
        }
        if (!ultra && prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DEMO_EN, false)) {
            note("Demo split: izquierda = original, derecha = con efectos.")
        }
        if (chainActive.any { it == "Modo seguro" }) {
            note("Modo seguro: sin efectos de imagen.", warn)
        }
        if (ultra) {
            note("☘️ Ultra económico activo: efectos, 3D y ayudas visuales apagados; se elige la pista de menor resolución (tope 480p) si el servidor ofrece varias.", warn)
        }
        if (chainOmitted.isNotEmpty()) {
            note("Omitidos por límite GPU: ${chainOmitted.joinToString(", ")}.", warn)
        }
        if (!aspectLabel.isNullOrBlank() && aspectLabel != "Original") {
            techRow("Proporción", "$aspectLabel (solo vista: no cambia el video)", true)
        }
        // Gama del equipo: explica por qué la GPU omite filtros.
        val tierName = try {
            DeviceProfile.get(activity).tier.label
        } catch (_: Throwable) {
            "desconocida"
        }
        note("Equipo: $tierName · el presupuesto de filtros de imagen depende de la gama.")
        separator()

        headerRow("Campo", "Entrada", "Salida")

        section("Video · stream")
        row("Contenedor", container)
        row("Códec", vCodec)
        videoProfile?.let { row("Perfil", it) }
        videoTrack?.let { row("Pista", it) }
        row("Resolución", vRes, finalRes, accented = upscaled)
        videoDecoded?.let { row("Decodificado", it) }
        row("Aspecto", aspect, aspectOut, accented = aspectOut != aspect)
        row("FPS", vFps, finalFps, accented = motionReqOn)
        row("Bitrate", vBitrate)
        row("Color", colorSpace)
        // HDR simulado: Light Boost aplica curva tonal por tramos + clarity
        // local (fake HDR) sobre SDR. Se declara solo si la luz está en la
        // cadena real, no basta el pref si la GPU la omitió.
        val cinePrefOn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_CINE_EN, false)
        val fakeHdrPref = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_CINE_FAKEHDR, true)
        val fakeHdrOn = !ultra && cinePrefOn && fakeHdrPref &&
            (chainActive.isEmpty() || chainActive.any { it == "Light+Color" })
        row(
            "Transfer / HDR", "$transfer • $hdr",
            when {
                !fakeHdrOn -> "$transfer • $hdr"
                hdr.startsWith("Sí") -> "$transfer • $hdr + realce"
                else -> "SDR (HDR simulado)"
            },
            accented = fakeHdrOn,
        )
        // Rango de salida: la compensación Limitado<->Completo de Light Boost
        // sí cambia lo que llega a pantalla. Se muestra solo si el efecto
        // está en la cadena real (el pref solo no basta si la GPU lo omitió
        // por presupuesto); sin cadena aún, se confía en prefs.
        val rangeModePref = prefs.getInt(ExoPlayerSettingsHelper.KEY_RANGE_MODE, 0).coerceIn(0, 2)
        val rangeEffective = !ultra && rangeModePref != 0 &&
            (chainActive.isEmpty() || chainActive.any { it == "Light+Color" })
        row(
            "Rango", range,
            when {
                !rangeEffective -> range
                rangeModePref == 1 -> "Completo (0-255)"
                else -> "Limitado (16-235)"
            },
            accented = rangeEffective,
        )
        // Croma de salida: al decodificar a RGB el submuestreo 4:2:0 deja de
        // existir (cada píxel lleva su color completo). El detalle sigue
        // viniendo del 4:2:0 original —se interpola, no se inventa—, pero el
        // pipeline de salida es 4:4:4 efectivo. Es inherente, no depende de
        // opciones, así que va sin acento.
        row("Chroma", chroma, "4:4:4 (RGB pleno)")
        row("Profundidad", bitDepth)
        if (hdrStatic != null) row("HDR estático", hdrStatic)
        videoRoles?.let { row("Rol", it) }
        videoSel?.let { row("Selección", it) }
        videoDrm?.let { row("Cifrado", it) }
        videoFrames?.let { row("Fotogramas", it) }
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
        audioProfile?.let { row("Perfil", it) }
        audioTrack?.let { row("Pista", it) }
        row("Bitrate", aBitrate)
        row("Muestreo", aSample)
        row("Canales", aCh)
        if (audioPcm != null) row("Bits/muestra", audioPcm)
        if (audioDelay != null) row("Retardo", "$audioDelay (delay/padding)")
        row("Idioma", aLang)
        audioRoles?.let { row("Rol", it) }
        audioSel?.let { row("Selección", it) }
        audioDrm?.let { row("Cifrado", it) }
        row("Compresión", aComp)
        audioBuffers?.let { row("Decodificación", it) }
        separator()

        section("Sonido · DSP activo")
        // El procesador tiene EXCLUSIÓN MUTUA (AudioEnhanceProcessor): solo
        // suena UNA cadena. Antes esta sección mezclaba el motor smartlite
        // con el detalle del DSP normal, así que con el motor SmartLite
        // (que es el valor por defecto) describía una cadena que NO sonaba.
        // Ahora el detalle sigue al motor real.
        fun tech(
            label: String,
            detail: String,
            active: Boolean,
        ) { techRow(label, detail, active) }
        val eng = SmartLiteConfig.engine()
        when (eng) {
            SmartLiteConfig.Engine.SMART_LITE -> {
                val sl = SmartLiteConfig.params()
                val slBands = sl.bands.count { it.enabled }
                tech(
                    "Motor de audio",
                    "SmartLite (experimental) · ${sl.preset.label}",
                    true,
                )
                tech(
                    "Salida detectada",
                    SmartLiteConfig.detectedPlaybackOutput().label,
                    true,
                )
                tech(
                    "Ecualizador",
                    "EQ de $slBands de ${sl.bands.size} bandas" +
                        if (sl.autoHeadroom) " · headroom auto" else "",
                    sl.eqEnabled && slBands > 0,
                )
                tech(
                    "Sonoridad",
                    "Compensa por nivel (meta ${sl.loudnessTargetLufs} LUFS)",
                    sl.loudnessEnabled,
                )
                tech(
                    "Dinámica",
                    "Compresión/expansión multibanda",
                    sl.dynamicsEnabled,
                )
                tech(
                    "Graves",
                    "Extensión ${sl.bassFreqHz.toInt()} Hz" +
                        if (sl.trueBassEnabled) " + TrueBass ${(sl.trueBassLevel * 100).toInt()}%" else "",
                    sl.bassExtEnabled || sl.trueBassEnabled,
                )
                tech(
                    "Transitorios",
                    "Ataque/cuerpo de percusión ${(sl.transientAmount * 100).toInt()}%",
                    sl.transientEnabled,
                )
                tech(
                    "Armónicos",
                    "Calidez ${sl.harmonicFreqHz.toInt()} Hz",
                    sl.harmonicEnabled,
                )
                tech(
                    "Crossfeed",
                    "Binaural ${sl.crossfeed.label}: suena fuera de la cabeza",
                    sl.crossfeed != SmartLiteConfig.CrossfeedMode.OFF,
                )
                tech(
                    "Bocina",
                    "Voicing ${sl.speakerMode.label}",
                    sl.speakerMode != SmartLiteConfig.SpeakerMode.OFF,
                )
                if (SmartLiteConfig.isAbBypass()) {
                    note("A/B en bypass: comparando sin procesar.")
                }
                note(
                    "Techo true-peak ${sl.truePeakCeilingDb} dB · " +
                        "Ganancia ${sl.outputGainDb} dB · " +
                        "Dither ${if (sl.ditherWhenNeeded) "auto" else "off"}",
                )
            }
            SmartLiteConfig.Engine.OFF -> {
                tech("Motor de audio", "OFF · audio original sin procesar", false)
            }
        }
        separator()

        section("Información del Hardware")
        // División propia: filas anchas "etiqueta: valor", sin columnas
        // Entrada/Salida (aquí no hay stream que comparar).
        fun hardRow(label: String, value: String): TextView {
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(labelColor)
                setPadding(8, 7, 8, 7)
            })
            val v = TextView(ctx).apply {
                text = value
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.WHITE)
                setPadding(8, 7, 8, 7)
            }
            box.addView(v)
            span(box)
            return v
        }
        // CPU y GPU se miden en fondo (doble muestra con 300 ms) y las celdas
        // se actualizan al llegar el dato. GPU: % instantáneo del driver y, si
        // no lo expone, delta de contadores Adreno legacy (plan B en físicos).
        val cpuCell = hardRow("CPU (global)", "midiendo…")
        val gpuCell = hardRow("GPU (carga)", "midiendo…")
        val vHwCell = hardRow("Video HW", "midiendo…")
        val aHwCell = hardRow("Audio HW", "midiendo…")
        Thread {
            val pct = sampleCpuPercent()
            val gpu = gpuInstantPercent() ?: gpuBusyDeltaPercent()
            val vHw = hwVideoLine()
            val aHw = audioHwLine()
            try {
                activity.runOnUiThread {
                    cpuCell.text = if (pct != null) "$pct%" else "no disponible"
                    gpuCell.text = if (gpu != null) "$gpu%" else "no expuesto"
                    vHwCell.text = vHw
                    aHwCell.text = aHw
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true }.start()
        hardRow("RAM sistema", ramLine(ctx))
        hardRow("RAM app", appRamLine())
        hardRow("Gráficos (VRAM)", gfxLine())
        hardRow(
            "Procesador",
            "${cpuName()} · ${Runtime.getRuntime().availableProcessors()} núcleos",
        )
        hardRow("GPU", gpuName())
        // Versiones GL y techo de textura: salen de la misma consulta EGL ya
        // cacheada (sin costo extra). La textura máxima es el techo real del
        // Upscaler en este equipo.
        val gl = glInfo()
        hardRow(
            "GLES",
            if (gl != null) {
                val v = gl.version.ifBlank { "?" }.take(40)
                val s = gl.glsl.ifBlank { "?" }.take(28)
                "$v · $s · ${gl.egl}"
            } else "—",
        )
        hardRow(
            "Textura máx",
            if (gl != null && gl.maxTexture > 0) "${gl.maxTexture} px" else "—",
        )
        hardRow("Pantalla", displayLine(activity))
        hardRow("Audio salida", audioOutLine(ctx))
        note("Sin VRAM dedicada: CPU/GPU comparten memoria; \"Gráficos\" es la parte GL.")
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

    // ---------- Nuevos datos de ExoPlayer (Media3 1.11) ----------

    // ---------- Equipo: CPU/GPU/RAM sin root ----------
    private var cachedGpuName: String? = null
    private var cachedGl: GlInfo? = null

    private data class GlInfo(
        val renderer: String,
        val version: String,
        val glsl: String,
        val egl: String,
        val maxTexture: Int,
    )

    /** Nombre del SoC/procesador: API 31+ > /proc/cpuinfo > Build.HARDWARE. */
    private fun cpuName(): String {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val soc = Build.SOC_MODEL
                if (!soc.isNullOrBlank()) return soc
            }
        } catch (_: Exception) { }
        try {
            java.io.BufferedReader(java.io.FileReader("/proc/cpuinfo")).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    val l = line!!.trim()
                    if (l.startsWith("Hardware") || l.startsWith("model name")) {
                        val name = l.substringAfter(':', "").trim()
                        if (name.isNotEmpty() && !name.startsWith("0x")) return name
                    }
                }
            }
        } catch (_: Exception) { }
        return try {
            Build.HARDWARE.takeIf { it.isNotBlank() } ?: "desconocido"
        } catch (_: Exception) { "desconocido" }
    }

    /** GPU (GL_RENDERER) vía pbuffer EGL propio; sin contexto GL no se puede preguntar. */
    private fun gpuName(): String {
        cachedGpuName?.let { return it }
        val out = glInfo()?.renderer
            ?: try {
                if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL?.takeIf { it.isNotBlank() } else null
            } catch (_: Exception) { null }
            ?: "desconocido"
        cachedGpuName = out
        return out
    }

    private fun glInfo(): GlInfo? {
        cachedGl?.let { return it }
        val gi = try { queryGlInfo() } catch (_: Exception) { null }
        if (gi != null) cachedGl = gi
        return gi
    }

    private fun queryGlInfo(): GlInfo? {
        val egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY) ?: return null
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(egl, ver, 0, ver, 1)) return null
        try {
            val cfgAttr = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            if (!EGL14.eglChooseConfig(egl, cfgAttr, 0, cfgs, 0, 1, n, 0) || n[0] == 0) return null
            val surfAttr = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            val surf = EGL14.eglCreatePbufferSurface(egl, cfgs[0], surfAttr, 0)
            val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val ctx = EGL14.eglCreateContext(egl, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
            try {
                if (surf == null || surf == EGL14.EGL_NO_SURFACE) return null
                if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) return null
                if (!EGL14.eglMakeCurrent(egl, surf, surf, ctx)) return null
                val r = GLES20.glGetString(GLES20.GL_RENDERER)
                val v = GLES20.glGetString(GLES20.GL_VENDOR)
                val gles = GLES20.glGetString(GLES20.GL_VERSION)
                val glsl = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)
                val maxTex = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
                EGL14.eglMakeCurrent(
                    egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                val renderer = listOfNotNull(v, r).joinToString(" ").trim().ifBlank { null }
                    ?: return null
                return GlInfo(
                    renderer = renderer,
                    version = (gles ?: "").trim(),
                    glsl = (glsl ?: "").trim(),
                    egl = "EGL ${ver[0]}.${ver[1]}",
                    maxTexture = maxTex[0],
                )
            } finally {
                if (surf != null && surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(egl, surf)
                if (ctx != null && ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(egl, ctx)
            }
        } finally {
            // NO eglTerminate: el display por defecto es compartido con el
            // pipeline GL de ExoPlayer; terminarlo desde el hilo de la UI deja
            // los contextos del reproductor con EGL_BAD_DISPLAY (video negro al
            // volver del dialogo). Solo se destruye lo creado aqui.
            EGL14.eglReleaseThread()
        }
    }

    private fun readCpuStat(): Pair<Long, Long>? {
        return try {
            java.io.BufferedReader(java.io.FileReader("/proc/stat")).use { br ->
                val p = (br.readLine() ?: return null).trim().split(Regex("\\s+"))
                if (p.size < 8 || p[0] != "cpu") return null
                val n = p.drop(1).map { it.toLongOrNull() ?: 0L }
                n.sum() to (n[3] + n[4])
            }
        } catch (_: Exception) { null }
    }

    /** % CPU global con doble muestra de /proc/stat separada 300 ms (hilo fondo). */
    private fun sampleCpuPercent(): Int? {
        val (t0, i0) = readCpuStat() ?: return null
        try { Thread.sleep(300) } catch (_: Exception) { return null }
        val (t1, i1) = readCpuStat() ?: return null
        val dt = t1 - t0
        if (dt <= 0) return null
        return ((1.0 - (i1 - i0).toDouble() / dt) * 100).toInt().coerceIn(0, 100)
    }

    /** % GPU si el driver lo expone (Adreno kgsl / Mali utilization); si no, null. */
    private fun gpuInstantPercent(): Int? {
        for (f in listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/devices/platform/soc/soc:qcom,kgsl-hyp/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/devices/soc/soc:qcom,kgsl-hyp/kgsl/kgsl-3d0/gpu_busy_percentage",
        )) {
            try {
                val t = java.io.File(f).takeIf { it.canRead() }?.readText()?.trim() ?: continue
                val pct = t.toIntOrNull()?.coerceIn(0, 100) ?: continue
                return pct
            } catch (_: Exception) { }
        }
        // Mali: la ruta varía por SoC; se busca el nodo *mali* en dos
        // directorios pequeños en vez de adivinar la ruta completa.
        val maliUtils = mutableListOf(
            java.io.File("/sys/class/misc/mali0/device/utilization"),
        )
        try {
            java.io.File("/sys/devices/platform")
                .listFiles { d -> d.isDirectory && d.name.contains("mali", ignoreCase = true) }
                ?.forEach { maliUtils.add(java.io.File(it, "utilization")) }
        } catch (_: Exception) { }
        try {
            java.io.File("/sys/class/misc")
                .listFiles { d -> d.name.startsWith("mali") }
                ?.forEach { maliUtils.add(java.io.File(it, "device/utilization")) }
        } catch (_: Exception) { }
        for (u in maliUtils) {
            try {
                val t = u.takeIf { it.canRead() }?.readText()?.trim() ?: continue
                // Formato Mali "usado/max", ej "120/1000".
                val pct = if (t.contains("/")) {
                    val parts = t.split("/").map { it.trim().toIntOrNull() ?: 0 }
                    val b = parts.getOrElse(1) { 0 }
                    if (b > 0) parts[0] * 100 / b else null
                } else t.toIntOrNull()
                if (pct != null) {
                    return pct.coerceIn(0, 100)
                }
            } catch (_: Exception) { }
        }
        return null
    }

    /** Adreno legacy: gpubusy trae "usado total" acumulados (ns); % por delta. */
    private fun readGpuBusy(): Pair<Long, Long>? {
        return try {
            val f = java.io.File("/sys/class/kgsl/kgsl-3d0/gpubusy")
            if (!f.canRead()) return null
            val p = f.readText().trim().split(Regex("\\s+"))
            if (p.size < 2) return null
            val used = p[0].toLongOrNull() ?: return null
            val total = p[1].toLongOrNull() ?: return null
            if (total <= 0) return null
            used to total
        } catch (_: Exception) { null }
    }

    private fun gpuBusyDeltaPercent(): Int? {
        val (u0, t0) = readGpuBusy() ?: return null
        try { Thread.sleep(300) } catch (_: Exception) { return null }
        val (u1, t1) = readGpuBusy() ?: return null
        val dt = t1 - t0
        if (dt <= 0) return null
        return ((u1 - u0).toDouble() / dt * 100).toInt().coerceIn(0, 100)
    }

    private fun ramLine(ctx: Context): String {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val totalGb = mi.totalMem / 1073741824.0
            val usedGb = (mi.totalMem - mi.availMem) / 1073741824.0
            val pct = if (mi.totalMem > 0) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt() else 0
            "%.1f / %.1f GB (%d%%)".format(usedGb, totalGb, pct)
        } catch (_: Exception) { "—" }
    }

    private fun appRamLine(): String {
        return try {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            "%.0f MB (PSS)".format(mi.totalPss / 1024.0)
        } catch (_: Exception) { "—" }
    }

    private fun gfxLine(): String {
        return try {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            val kb = mi.getMemoryStat("summary.graphics")?.toIntOrNull() ?: return "—"
            "%.0f MB (unificada)".format(kb / 1024.0)
        } catch (_: Exception) { "—" }
    }

    /** Pantalla: tamaño, refresco, HDR y gama amplia (lo que limita el render). */
    private fun displayLine(activity: Activity): String {
        return try {
            val dm = activity.resources.displayMetrics
            val disp: android.view.Display? = try {
                if (Build.VERSION.SDK_INT >= 30) activity.display
                else {
                    @Suppress("DEPRECATION")
                    activity.windowManager.defaultDisplay
                }
            } catch (_: Exception) { null }
            var hz = 0f
            var hdr = ""
            var wide = false
            disp?.let { d ->
                try { hz = d.refreshRate } catch (_: Exception) { }
                try {
                    if (Build.VERSION.SDK_INT >= 24) {
                        hdr = d.hdrCapabilities?.supportedHdrTypes?.map {
                            when (it) {
                                android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                                android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                                android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                                android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DV"
                                else -> null
                            }
                        }?.filterNotNull()?.joinToString("/") ?: ""
                    }
                } catch (_: Exception) { }
                try {
                    if (Build.VERSION.SDK_INT >= 26) wide = d.isWideColorGamut
                } catch (_: Exception) { }
            }
            buildList {
                add("${dm.widthPixels}×${dm.heightPixels}")
                if (hz > 0) add("${hz.toInt()} Hz")
                if (hdr.isNotEmpty()) add(hdr)
                if (wide) add("gama amplia")
            }.joinToString(" · ").ifBlank { "—" }
        } catch (_: Exception) { "—" }
    }

    private fun isHwCodec(info: android.media.MediaCodecInfo): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
            else !info.name.startsWith("OMX.google.", ignoreCase = true)
        } catch (_: Exception) { true }
    }

    /** Decodificadores de video por hardware (lo que el equipo reproduce sin CPU). */
    private fun hwVideoLine(): String {
        return try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            val want = mapOf(
                "video/avc" to "AVC",
                "video/hevc" to "HEVC",
                "video/x-vnd.on2.vp9" to "VP9",
                "video/av01" to "AV1",
            )
            want.map { (mime, tag) ->
                val hw = list.codecInfos.any { info ->
                    !info.isEncoder &&
                        info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                        isHwCodec(info)
                }
                if (hw) "$tag HW" else "$tag no"
            }.joinToString(" · ")
        } catch (_: Exception) { "—" }
    }

    /** Salida de audio del sistema (frecuencia y buffer: latencia base). */
    private fun audioOutLine(ctx: Context): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val rate = am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            val frames = am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            listOfNotNull(
                rate?.takeIf { it.isNotBlank() }?.let { "$it Hz" },
                frames?.takeIf { it.isNotBlank() }?.let { "buffer $it" },
            ).joinToString(" · ").ifBlank { "—" }
        } catch (_: Exception) { "—" }
    }

    /** Dolby/DTS por hardware (passthrough y descarga de la CPU). */
    private fun audioHwLine(): String {
        return try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            fun hasHw(vararg mimes: String): Boolean = list.codecInfos.any { info ->
                !info.isEncoder &&
                    info.supportedTypes.any { t -> mimes.any { m -> t.equals(m, ignoreCase = true) } } &&
                    isHwCodec(info)
            }
            "Dolby ${if (hasHw("audio/ac3", "audio/eac3", "audio/ac4")) "HW" else "no"}" +
                " · DTS ${if (hasHw("audio/vnd.dts", "audio/vnd.dts.hd")) "HW" else "no"}"
        } catch (_: Exception) { "—" }
    }

    /** Id/etiqueta del formato (pista), si el proveedor los manda. */
    private fun formatTag(f: Format?): String? {
        if (f == null) return null
        val id = f.id?.takeIf { it.isNotBlank() }
        val label = f.label?.takeIf { it.isNotBlank() }
        return when {
            id != null && label != null -> "$id · $label"
            label != null -> label
            id != null -> id
            else -> null
        }
    }

    /** Resolución decodificada real (1920×1088 → 1080p) cuando difiere del manifiesto. */
    private fun decodedSize(f: Format?): String? {
        if (f == null) return null
        val dw = f.decodedWidth
        val dh = f.decodedHeight
        if (dw <= 0 || dh <= 0) return null
        if (dw == f.width && dh == f.height) return null
        return "$dw×$dh"
    }

    /** Profundidad de bits REAL del video si el manifiesto la declara. */
    private fun bitDepthLabel(c: ColorInfo?): String {
        if (c == null) return "8-bit (est.)"
        return try {
            if (c.isBitdepthValid() && c.lumaBitdepth > 0) "${c.lumaBitdepth}-bit"
            else if (isHdr(c)) "10-bit (est.)" else "8-bit (est.)"
        } catch (_: Exception) {
            if (isHdr(c)) "10-bit (est.)" else "8-bit (est.)"
        }
    }

    /** Metadatos HDR estáticos (SMPTE ST.2086): MaxCLL y MaxFALL en cd/m². */
    private fun hdrStaticInfo(c: ColorInfo?): String? {
        val b = c?.hdrStaticInfo ?: return null
        if (b.size < 28) return null
        fun u16(off: Int): Int {
            val lo = b[off + 1].toInt() and 0xFF
            return ((b[off].toInt() and 0xFF) shl 8) or lo
        }
        val maxCll = u16(24)
        val maxFall = u16(26)
        val parts = mutableListOf<String>()
        if (maxCll > 0) parts += "MaxCLL $maxCll cd/m²"
        if (maxFall > 0) parts += "MaxFALL $maxFall cd/m²"
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** Roles de la pista (main/alternativa/doblaje/…). */
    private fun roleFlagsLabel(flags: Int?): String? {
        if (flags == null || flags == 0) return null
        val role = linkedMapOf(
            C.ROLE_FLAG_MAIN to "Principal",
            C.ROLE_FLAG_ALTERNATE to "Alternativa",
            C.ROLE_FLAG_SUPPLEMENTARY to "Suplementaria",
            C.ROLE_FLAG_COMMENTARY to "Comentario",
            C.ROLE_FLAG_DUB to "Doblaje",
            C.ROLE_FLAG_EMERGENCY to "Emergencia",
            C.ROLE_FLAG_CAPTION to "Caption",
            C.ROLE_FLAG_SUBTITLE to "Subtítulos",
            C.ROLE_FLAG_SIGN to "Lengua de señas",
            C.ROLE_FLAG_DESCRIBES_VIDEO to "Audiodescripción",
            C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND to "Describe audio",
            C.ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY to "Diálogo mejorado",
            C.ROLE_FLAG_TRANSCRIBES_DIALOG to "Transcribe diálogo",
            C.ROLE_FLAG_EASY_TO_READ to "Lectura fácil",
            C.ROLE_FLAG_TRICK_PLAY to "Trick-play",
            C.ROLE_FLAG_AUXILIARY to "Auxiliar",
        )
        return role.filterKeys { flags and it == it }.values
            .joinToString(", ").ifBlank { null }
    }

    /** Flags de selección del manifiesto (predeterminada/forzada/auto). */
    private fun selectionFlagsLabel(flags: Int?): String? {
        if (flags == null || flags == 0) return null
        val parts = mutableListOf<String>()
        if (flags and C.SELECTION_FLAG_DEFAULT != 0) parts += "Predeterminada"
        if (flags and C.SELECTION_FLAG_FORCED != 0) parts += "Forzada"
        if (flags and C.SELECTION_FLAG_AUTOSELECT != 0) parts += "Auto"
        return parts.joinToString(", ").ifBlank { null }
    }

    /** Cifrado/DRM del stream (solo si el manifiesto lo declara). */
    private fun drmLabel(f: Format?): String? {
        if (f == null) return null
        val hasDrm = try { f.drmInitData != null } catch (_: Exception) { false }
        val t = try { f.cryptoType } catch (_: Exception) { C.CRYPTO_TYPE_NONE }
        if (!hasDrm && t == C.CRYPTO_TYPE_NONE) return null
        val kind = when (t) {
            C.CRYPTO_TYPE_FRAMEWORK -> "Framework"
            C.CRYPTO_TYPE_UNSUPPORTED -> "No soportado"
            else -> "Custom"
        }
        return if (hasDrm) "Cifrada (DRM $kind)" else "Cifrada ($kind)"
    }

    /** Contadores reales del decodificador de video (frames y latencia). */
    private fun videoFramesLine(p: ExoPlayer?): String? {
        if (p == null) return null
        val c = try { p.getVideoDecoderCounters() ?: return null } catch (_: Exception) { return null }
        val rendered = c.renderedOutputBufferCount
        val dropped = c.droppedBufferCount
        val total = rendered + dropped + c.skippedOutputBufferCount
        if (total == 0) return null
        val parts = mutableListOf("$rendered render")
        if (dropped > 0) {
            val pct = dropped * 100f / total
            parts += "${dropped} caídos (%.1f%%)".format(pct)
            if (c.maxConsecutiveDroppedBufferCount > 1) parts += "consec ${c.maxConsecutiveDroppedBufferCount}"
        } else {
            parts += "0 caídos"
        }
        if (c.videoFrameProcessingOffsetCount > 0) {
            val avgMs = c.totalVideoFrameProcessingOffsetUs / c.videoFrameProcessingOffsetCount / 1000.0
            parts += "lat media %.1f ms".format(avgMs)
        }
        return parts.joinToString(" · ")
    }

    /** Cuenta real de buffers de audio decodificados (y silencios saltados). */
    private fun audioBuffersLine(p: ExoPlayer?): String? {
        if (p == null) return null
        val c = try { p.getAudioDecoderCounters() ?: return null } catch (_: Exception) { return null }
        val total = c.renderedOutputBufferCount
        if (total == 0 && c.skippedOutputBufferCount == 0 && c.queuedInputBufferCount == 0) return null
        val parts = mutableListOf("$total buffers")
        if (c.skippedOutputBufferCount > 0) parts += "${c.skippedOutputBufferCount} silencio"
        return parts.joinToString(" · ").ifBlank { null }
    }

    /** Bits por muestra para audio PCM (el resto de códecs no lo declara). */
    private fun pcmLabel(f: Format?): String? {
        if (f == null) return null
        val enc = f.pcmEncoding
        if (enc == Format.NO_VALUE) return null
        return when (enc) {
            C.ENCODING_PCM_8BIT -> "8-bit PCM"
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "16-bit PCM"
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "24-bit PCM"
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "32-bit PCM"
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> "float 32-bit"
            C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> "double 64-bit"
            else -> "Códec $enc"
        }
    }

    /** Retardo del codificador (delay/padding) cuando el stream lo trae. */
    private fun encoderDelayLabel(f: Format?): String? {
        if (f == null) return null
        val d = f.encoderDelay
        val pad = f.encoderPadding
        if (d == Format.NO_VALUE && pad == Format.NO_VALUE) return null
        val parts = mutableListOf<String>()
        if (d != Format.NO_VALUE && d >= 0) parts += d.toString()
        if (pad != Format.NO_VALUE && pad >= 0) parts += pad.toString()
        return if (parts.isEmpty()) null else parts.joinToString("/")
    }

    /** VOD / En vivo / DVR según el timeline (isCurrentMediaItemLive/Dynamic). */
    private fun liveLabel(p: ExoPlayer?): String {
        val live = try { p?.isCurrentMediaItemLive ?: false } catch (_: Exception) { false }
        val dynamic = try { p?.isCurrentMediaItemDynamic ?: false } catch (_: Exception) { false }
        val seekable = try { p?.isCurrentMediaItemSeekable ?: false } catch (_: Exception) { false }
        return when {
            live && seekable -> "En vivo (DVR · rebobinable)"
            live -> "En vivo (en directo)"
            dynamic -> "Dinámico (DASH live edge)"
            else -> "VOD (archivo)"
        }
    }

    /** Estado de reproducción de ExoPlayer (STATE_* + isPlaying/isLoading). */
    private fun playbackStateLine(p: ExoPlayer?): String? {
        val s = try { p?.playbackState ?: return null } catch (_: Exception) { return null }
        val loading = try { p.isLoading } catch (_: Exception) { false }
        val playing = try { p.isPlaying } catch (_: Exception) { false }
        return when (s) {
            Player.STATE_IDLE -> "Listo (sin carga)"
            Player.STATE_BUFFERING -> if (loading) "Descargando… (buffering)" else "Descargando…"
            Player.STATE_READY -> if (playing) "Reproduciendo" else "Reproduciendo (en pausa)"
            Player.STATE_ENDED -> "Terminado"
            else -> "Estado $s"
        }
    }

    /** Memoria buffer real: duración extra descargada y % total. */
    private fun bufferLine(p: ExoPlayer?): String? {
        val buf = try { p?.totalBufferedDuration ?: return null } catch (_: Exception) { return null }
        val pct = try { p.bufferedPercentage } catch (_: Exception) { -1 }
        if (buf <= 0 && pct <= 0) return null
        val parts = mutableListOf<String>()
        if (buf > 0) parts += "extra %.1f s".format(buf / 1000.0)
        if (pct >= 0) parts += "$pct% total"
        return parts.joinToString(" · ").ifBlank { null }
    }

    /** Perfil y nivel del códec de video (H.264/HEVC/VP9/AV1/MPEG…). */
    private fun prettyVideoProfile(f: Format?): String? {
        if (f == null) return null
        val raw = (f.codecs ?: "").lowercase().trim()
        if (raw.isBlank()) return null
        return try {
            val p = raw.split('.')
            when {
                // H.264: avc1.PPCCLL → profile_idc hex, nivel 2 dígitos decimal.
                raw.startsWith("avc1") || raw.startsWith("avc3") -> {
                    if (p.size < 2 || p[1].length < 4) return raw
                    val profileIdc = Integer.parseInt(p[1].substring(0, 2), 16)
                    val levelCode = Integer.parseInt(p[1].substring(2, 4), 16)
                    val profile = when (profileIdc) {
                        66 -> "Baseline"
                        77 -> "Main"
                        88 -> "Extended"
                        100 -> "High"
                        110 -> "High 10"
                        122 -> "High 4:2:2"
                        244 -> "High 4:4:4"
                        else -> "Perfil $profileIdc"
                    }
                    "$profile · Nivel ${levelCode / 10}.${levelCode % 10}"
                }
                // HEVC: hvc1.PP.CC.TLLL → perfil, tier (L/H) y nivel_idc decimal.
                raw.startsWith("hvc1") || raw.startsWith("hev1") -> {
                    if (p.size < 4) return raw
                    val profile = when (p[1]) {
                        "1" -> "Main"
                        "2" -> "Main 10"
                        "3" -> "Main Still"
                        else -> "Perfil ${p[1]}"
                    }
                    val tier = when (p[3].firstOrNull()) {
                        'h', 'H' -> "Alto"
                        'l', 'L' -> "Main"
                        else -> null
                    }
                    val levelIdc = p[3].substring(1).toIntOrNull()
                    val level = levelIdc?.let { hevcLevel(it) }
                    listOf(profile, tier, level).filter { it != null && it.isNotBlank() }
                        .joinToString(" · ")
                }
                // VP9: vp09.PP.LL → perfil y nivel (hex).
                raw.startsWith("vp09") -> {
                    if (p.size < 3) return raw
                    val prof = p[1].toIntOrNull()
                    val levelCode = p[2].toIntOrNull(16) ?: 0
                    "Perfil ${prof ?: "?"} · Nivel ${levelCode / 10}.${levelCode % 10}"
                }
                // AV1: av01.P.LLxx → perfil y nivel.
                raw.startsWith("av01") -> {
                    if (p.size < 3) return raw
                    val profile = when (p[1]) {
                        "0" -> "Main"
                        "1" -> "High"
                        "2" -> "Professional"
                        else -> "Perfil ${p[1]}"
                    }
                    val levelCode = p[2].substring(0, 2).toIntOrNull(16) ?: 0
                    "$profile · Nivel ${levelCode / 10}.${levelCode % 10}"
                }
                raw.contains("mp4v") || raw.contains("mp2v") -> raw
                else -> null
            }
        } catch (_: Exception) {
            raw
        }
    }

    /** Perfil del códec de audio (AAC-LC / HE-AAC / xHE-AAC… de mp4a.NN). */
    private fun prettyAudioProfile(f: Format?): String? {
        if (f == null) return null
        val raw = ((f.codecs ?: f.sampleMimeType) ?: "").lowercase().trim()
        if (raw.isBlank() || !raw.startsWith("mp4a")) return null
        return try {
            val obj = raw.split('.')[1].toIntOrNull()
            when (obj) {
                1 -> "AAC Main"
                2 -> "AAC-LC"
                3 -> "AAC SSR"
                4 -> "AAC LTP"
                5 -> "HE-AAC (SBR)"
                17 -> "AAC LD"
                23 -> "AAC LDv2"
                29 -> "HE-AAC v2 (SBR+PS)"
                39 -> "AAC ELD"
                42 -> "xHE-AAC (USAC)"
                null -> null
                else -> "AAC (objeto $obj)"
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Nivel HEVC (level_idc → etiqueta). */
    private fun hevcLevel(idc: Int): String? {
        return when (idc) {
            30 -> "Nivel 1.0"
            60 -> "Nivel 2.0"
            63 -> "Nivel 2.1"
            90 -> "Nivel 3.0"
            93 -> "Nivel 3.1"
            120 -> "Nivel 4.0"
            123 -> "Nivel 4.1"
            150 -> "Nivel 5.0"
            153 -> "Nivel 5.1"
            156 -> "Nivel 5.2"
            180 -> "Nivel 6.0"
            183 -> "Nivel 6.1"
            186 -> "Nivel 6.2"
            else -> "Nivel $idc"
        }
    }
}
