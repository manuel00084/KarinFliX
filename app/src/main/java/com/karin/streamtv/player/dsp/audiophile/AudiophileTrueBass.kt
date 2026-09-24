package com.karin.streamtv.player.dsp.audiophile

import com.karin.streamtv.player.dsp.BiquadFilter
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tanh

/**
 * True Bass — graves "reales" que además suenan en un TV.
 *
 * Una bocina de TV/parlante sencillo no reproduce subgraves (<60-80 Hz): no hay
 * cono que los mueva. Este módulo da la percepción de grave profundo mediante 4
 * piezas, todas band-limited para NO destruir el driver:
 *   1) Bass management: los graves (< 140 Hz) se suman a MONO (estándar de
 *      soundbar): un solo woofer percibido centrado, más peso y menos fatigua
 *      del driver. El resto de bandas queda intacto (transparencia).
 *   2) Boost seguro: amplifica los graves REALES del material (resaltar bajos).
 *   3) Síntesis: 2º armónico (x·|x|) + "thump" (envelope del low-band filtrada
 *      ~75 Hz) generan contenido grave que el pequeño cono SÍ puede reproducir.
 *   4) Protección de excursion: compresor soft-knee sobre el low-band evita
 *      "farting"/clipping al subir el nivel (único limitador de la banda bajos).
 *
 * Coste: 4 biquads + un follower; sin PLL, sin alloc, sin convolución.
 * OFF por defecto (no colorea en presets de transparencia).
 */
class AudiophileTrueBass {
    private val lpL = BiquadFilter()
    private val lpR = BiquadFilter()
    private val thumpLp = BiquadFilter()
    private var active = false
    private var level = 0.0
    private var env = 0.0
    private var aAtk = 1.0
    private var aRel = 1.0

    fun configure(fs: Int, enabled: Boolean, lvl: Float) {
        val safeFs = fs.coerceAtLeast(8000)
        active = enabled && fs > 0
        level = lvl.coerceIn(0f, 1f).toDouble()
        if (!active) return
        lpL.configure(BiquadFilter.Kind.LOWPASS, safeFs, 140f, 0f, 0.707f)
        lpR.configure(BiquadFilter.Kind.LOWPASS, safeFs, 140f, 0f, 0.707f)
        thumpLp.configure(BiquadFilter.Kind.LOWPASS, safeFs, 75f, 0f, 0.707f)
        aAtk = 1.0 - Math.exp(-1.0 / (0.004 * safeFs))
        aRel = 1.0 - Math.exp(-1.0 / (0.060 * safeFs))
        env = 0.0
    }

    fun process(l: Double, r: Double, out: DoubleArray) {
        if (!active || level <= 0.0) {
            out[0] = l; out[1] = r
            return
        }
        // 1) Band-split + bass mono (management de soundbar).
        val loL = lpL.process(l)
        val loR = lpR.process(r)
        val lo = 0.5 * (loL + loR)
        val highL = l - loL
        val highR = r - loR

        // 2) Boost de graves reales + síntesis.
        val boost = lo * (1.0 + 0.9 * level)
        val harm = lo * abs(lo) * (0.55 * level)
        val a = abs(lo)
        env = if (a > env) env + (a - env) * aAtk else env + (a - env) * aRel
        val thump = thumpLp.process(env) * (0.5 * level)
        var bassOut = boost + harm + thump

        // 4) Protección de excursion: soft-knee sobre el low-band.
        val key = abs(bassOut)
        val threshold = 0.30
        val ratio = 1.0 + 6.0 * level
        val knee = 8.0
        val xdb = 20.0 * log10((key / threshold).coerceAtLeast(1e-9))
        val r = 1.0 - 1.0 / ratio
        val grDb = when {
            xdb <= -knee / 2.0 -> 0.0
            xdb >= knee / 2.0 -> r * xdb
            else -> r * (xdb + knee / 2.0) * (xdb + knee / 2.0) / (2.0 * knee)
        }
        val g = 10.0.pow(-grDb / 20.0)
        bassOut *= g
        // Saturación final solo de la banda grave: acota transitorios a ≤ 1.0
        // (no mueve el limitador maestro) y aporta armónicos que en TV suenan
        // como grave. Medios/agudos quedan intactos.
        bassOut = tanh(bassOut)

        out[0] = highL + bassOut
        out[1] = highR + bassOut
    }

    fun reset() {
        lpL.reset(); lpR.reset(); thumpLp.reset()
        env = 0.0
    }
}