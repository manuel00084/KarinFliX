package com.karin.streamtv.player.dsp

import com.karin.streamtv.player.dsp.audiophile.AudiophileConfig
import com.karin.streamtv.player.dsp.audiophile.KarinAudiophileDSP
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import org.junit.Assert.assertTrue
import org.junit.Test

// ── helpers puros a nivel de fichero (accesibles desde las clases anidadas) ──

private fun envFollow(x: Double, prev: Double, atk: Double, rel: Double): Double =
    if (x > prev) prev + (x - prev) * atk else prev + (x - prev) * rel

private fun softKnee(env: Double, strength: Float): Double {
    val threshold = 0.10
    val ratio = 1.0 + 7.0 * strength
    val knee = 12.0
    val makeup = 1.0 + 0.45 * strength
    val xdb = 20.0 * log10((env / threshold).coerceAtLeast(1e-9))
    val r = 1.0 - 1.0 / ratio
    val grDb = when {
        xdb <= -knee / 2.0 -> 0.0
        xdb >= knee / 2.0 -> r * xdb
        else -> r * (xdb + knee / 2.0) * (xdb + knee / 2.0) / (2.0 * knee)
    }
    return makeup * 10.0.pow(-grDb / 20.0)
}

/**
 * Comparación de rendimiento/recurso ENTRE DOS MOTORES en el mismo JVM y
 * material. "CURRENT" replica tonalStereo() del DSP actual (ANIME @48k,
 * PHONE_SPEAKER) usando sus mismos módulos/contadores; "AUDIOPHILE" usa el
 * motor real. La fidelidad de CURRENT es aproximada (orden de magnitud); los
 * tamaños de memoria se comparan aparte por análisis exacto de buffers.
 */
class DspCompareBenchmark {

    private class RepReverb(fs: Int) {
        private val scale = fs / 44100.0
        private val combT = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val apT = intArrayOf(556, 441, 341, 225)
        private val comb = Array(8) { k -> DoubleArray(sz(combT[k])) }
        private val combIdx = IntArray(8)
        private val damp = Array(8) { DoubleArray(2) }
        private val apIn = Array(4) { k -> DoubleArray(sz(apT[k])) }
        private val apOut = Array(4) { k -> DoubleArray(sz(apT[k])) }
        private val apIdx = IntArray(4)
        private val fb = 0.84
        private val dmp = 0.25
        private val apG = 0.5
        private fun sz(s: Int) = ((s * scale).toInt()).coerceAtLeast(1)

        fun process(x: Double): Double {
            var out = 0.0
            for (i in 0 until 8) {
                val b = comb[i]; val st = damp[i]; val idx = combIdx[i]
                val del = b[idx]
                val f = del * (1.0 - dmp) + st[0] * dmp
                st[0] = f
                val v = x + f * fb
                b[idx] = if (abs(v) < 1e-25) 0.0 else v
                combIdx[i] = (idx + 1) % b.size
                out += del
            }
            out *= 0.125
            for (i in 0 until 4) {
                val ib = apIn[i]; val ob = apOut[i]; val idx = apIdx[i]
                val bo = ob[idx]
                val y = -apG * out + ib[idx] + apG * bo
                ib[idx] = out; ob[idx] = y; out = y
                apIdx[i] = (idx + 1) % ib.size
            }
            return out
        }
    }

    private class RepBiquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        fun peaking(fs: Int, f: Float, g: Float, q: Float) {
            val w0 = 2.0 * Math.PI * f / fs; val cw = cos(w0); val sw = sin(w0)
            val a = 10.0.pow(g / 40.0); val al = sw / (2.0 * q); val a0 = 1.0 + al / a
            b0 = (1.0 + al * a) / a0; b1 = (-2.0 * cw) / a0; b2 = (1.0 - al * a) / a0
            a1 = (-2.0 * cw) / a0; a2 = (1.0 - al / a) / a0
        }

        fun shelf(low: Boolean, fs: Int, f: Float, g: Float, q: Float) {
            val w0 = 2.0 * Math.PI * f / fs; val cw = cos(w0); val sw = sin(w0)
            val a = 10.0.pow(g / 40.0)
            val al = sw / 2.0 * sqrt((a + 1.0 / a) * (1.0 / q - 1.0) + 2.0).coerceAtLeast(1e-6)
            val ts = 2.0 * sqrt(a)
            if (low) {
                val a0 = (a + 1.0) + (a - 1.0) * cw + ts * al
                b0 = a * ((a + 1.0) - (a - 1.0) * cw + ts * al) / a0
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cw) / a0
                b2 = a * ((a + 1.0) - (a - 1.0) * cw - ts * al) / a0
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cw) / a0
                a2 = ((a + 1.0) + (a - 1.0) * cw - ts * al) / a0
            } else {
                val a0 = (a + 1.0) - (a - 1.0) * cw + ts * al
                b0 = a * ((a + 1.0) + (a - 1.0) * cw + ts * al) / a0
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cw) / a0
                b2 = a * ((a + 1.0) + (a - 1.0) * cw - ts * al) / a0
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cw) / a0
                a2 = ((a + 1.0) - (a - 1.0) * cw - ts * al) / a0
            }
        }

        fun lp(fs: Int, f: Float, q: Float) {
            val w0 = 2.0 * Math.PI * f / fs; val cw = cos(w0); val sw = sin(w0)
            val al = sw / (2.0 * q); val a0 = 1.0 + al
            b0 = (1.0 - cw) / 2.0 / a0; b1 = (1.0 - cw) / a0; b2 = (1.0 - cw) / 2.0 / a0
            a1 = (-2.0 * cw) / a0; a2 = (1.0 - al) / a0
        }

        fun hp(fs: Int, f: Float, q: Float) {
            val w0 = 2.0 * Math.PI * f / fs; val cw = cos(w0); val sw = sin(w0)
            val al = sw / (2.0 * q); val a0 = 1.0 + al
            b0 = (1.0 + cw) / 2.0 / a0; b1 = -(1.0 + cw) / a0; b2 = (1.0 + cw) / 2.0 / a0
            a1 = (-2.0 * cw) / a0; a2 = (1.0 - al) / a0
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            if (abs(y) < 1e-20) return 0.0
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private class RepOver2x(private var mode: Int) {
        private val n = 33
        private val mask = 63
        private val h = DoubleArray(n)
        private val fy = DoubleArray(mask + 2)
        private var head = 0
        private var prev = 0.0

        init {
            val k = (n - 1) / 2
            val fc = 0.45
            for (i in 0 until n) {
                val x = (i - k).toDouble()
                val w = 0.42 - 0.5 * cos(Math.PI * i / (n - 1)) + 0.08 * cos(2.0 * Math.PI * i / (n - 1))
                val s = if (abs(x) < 1e-9) 2.0 * fc else sin(2.0 * Math.PI * fc * x) / (Math.PI * x)
                h[i] = w * s
            }
            var sum = 0.0
            for (i in 0 until n) sum += h[i]
            for (i in 0 until n) h[i] /= sum
        }

        fun process(x: Double): Double {
            val v1: Double
            val v2: Double
            when (mode) {
                1 -> { // tube (d=0.06)
                    val a = 0.0132; val b = 0.108
                    v1 = tube(a, b, (x + prev) * 0.5); v2 = tube(a, b, x)
                }
                2 -> { v1 = tanh((x + prev)) * 0.4; v2 = tanh(x) * 0.4 } // excite
                3 -> { v1 = clampOver((x + prev) * 0.5); v2 = clampOver(x) } // bass clamp
                else -> { v1 = abs((x + prev) * 0.5); v2 = abs(x) } // rect abs
            }
            prev = x
            fy[head] = v1; fy[(head + 1) and mask] = v2
            head = (head + 2) and mask
            var acc = 0.0
            var slot = head + mask
            for (i in 0 until n) { acc += h[i] * fy[slot and mask]; slot -= 1 }
            return acc
        }

        private fun tube(a: Double, b: Double, v: Double): Double {
            val v2 = v * v
            return (v + a * v2) / (1.0 + b * v2)
        }

        private fun clampOver(boost: Double): Double {
            val a = abs(boost)
            return if (a > 0.6) {
                val s = if (boost > 0) 1.0 else -1.0
                s * (0.6 + (a - 0.6) / (1.0 + (a - 0.6)))
            } else boost
        }
    }

    /** Réplica del coste de tonalStereo() ANIME@48k (orden de magnitud). */
    private class CurrentChainReplica {
        private val fs = 48000
        private val reverbL = RepReverb(fs)
        private val reverbR = RepReverb(fs)
        private val vbLp = RepBiquad().also { it.lp(fs, 150f, 0.707f) }
        private val vbSmooth = RepBiquad().also { it.lp(fs, 150f * 8f, 0.707f) }
        private val vbHp = RepBiquad().also { it.hp(fs, 150f * 1.33f, 0.707f) }
        private val aaRect = RepOver2x(0)
        private val aaBass = RepOver2x(3)
        private val aaExcite = RepOver2x(2)
        private val aaTube = RepOver2x(1)
        private val exciteLp = RepBiquad().also { it.lp(fs, 1400f, 0.707f) }
        private val eq = Array(5) { RepBiquad() }
        private val tubeDc = RepBiquad().also { it.hp(fs, 25f, 0.707f) }
        private var envBass = 0.0
        private var dynGain = 1.0
        private val fieldL = RepBiquad().also { it.lp(fs, 200f, 0.707f) }
        private val fieldR = RepBiquad().also { it.lp(fs, 200f, 0.707f) }
        private val compLp = RepBiquad().also { it.lp(fs, 220f, 0.707f) }
        private val compHp = RepBiquad().also { it.hp(fs, 3200f, 0.707f) }
        private val compLpR = RepBiquad().also { it.lp(fs, 220f, 0.707f) }
        private val compHpR = RepBiquad().also { it.hp(fs, 3200f, 0.707f) }
        private val compEnv = DoubleArray(3)
        private val compSm = doubleArrayOf(1.0, 1.0, 1.0)
        private val loudLpL = RepBiquad().also { it.shelf(true, fs, 120f, 4.5f, 0.7f) }
        private val loudHpL = RepBiquad().also { it.shelf(false, fs, 6000f, 3f, 0.7f) }
        private val loudLpR = RepBiquad().also { it.shelf(true, fs, 120f, 4.5f, 0.7f) }
        private val loudHpR = RepBiquad().also { it.shelf(false, fs, 6000f, 3f, 0.7f) }
        private val surfShelfL = RepBiquad().also { it.shelf(true, fs, 240f, 1.8f, 0.71f) }
        private val surfShelfR = RepBiquad().also { it.shelf(true, fs, 240f, 1.8f, 0.71f) }
        private val limMaster = MultibandLimiter()
        private val scratch = doubleArrayOf(0.0, 0.0)
        private val beatLpL = RepBiquad().also { it.lp(fs, 140f, 0.707f) }
        private val beatLpR = RepBiquad().also { it.lp(fs, 140f, 0.707f) }
        private var bfL = 0.0; private var bsL = 0.0
        private var bfR = 0.0; private var bsR = 0.0
        private val punchHpL = RepBiquad().also { it.hp(fs, 1200f, 0.707f) }
        private val punchHpR = RepBiquad().also { it.hp(fs, 1200f, 0.707f) }
        private var pfL = 0.0; private var psL = 0.0
        private var pfR = 0.0; private var psR = 0.0
        private val gain = exp(-1.0 / (0.020 * fs))
        private val relBass = exp(-1.0 / (0.25 * fs))
        private val smoothG = exp(-1.0 / (0.050 * fs))
        private val cAtk = exp(-1.0 / (0.010 * fs))
        private val cRel = exp(-1.0 / (0.150 * fs))
        private val cSm = exp(-1.0 / (0.025 * fs))

        init {
            limMaster.configure(fs)
            limMaster.setThreshold(0.99)
            val freq = floatArrayOf(60f, 250f, 1000f, 4000f, 12000f)
            val g = floatArrayOf(0.5f, 1.6f, 0.5f, 2.0f, 0.4f)
            for (i in 0 until 5) { eq[i].peaking(fs, freq[i], g[i], 0.8f) }
        }

        fun process(l0: Double, r0: Double): Double {
            var l = l0
            var r = r0
            val rm = 0.02 * 0.8 * 1.3
            l += rm * reverbL.process(l)
            r += rm * reverbR.process(r)
            // harmonicBass (VirtualBass + clamp) por canal
            for (c in 0 until 2) {
                val v = if (c == 0) l else r
                val bass = vbLp.process(v)
                val rect = vbSmooth.process(aaRect.process(abs(bass)))
                val a = abs(rect)
                envBass = if (a > envBass) envBass * gain + (1 - gain) * a else envBass * relBass + (1 - relBass) * a
                var target = 1.0
                if (envBass < 0.05) target = 1.0 + (0.05 - envBass) / 0.05 * 0.6
                else if (envBass > 0.40) target = 1.0 - (envBass - 0.40) / 0.40 * 0.5
                dynGain += (target - dynGain) * smoothG
                val hl = vbHp.process(rect)
                val boosted = aaBass.process(0.4 * dynGain * hl)
                if (c == 0) l += boosted else r += boosted
            }
            // beat boost (0.45)
            val bl = beatLpL.process(l); val br = beatLpR.process(r)
            bfL = envFollow(abs(bl), bfL, 0.35, 0.012); bsL = envFollow(abs(bl), bsL, 0.03, 0.08)
            bfR = envFollow(abs(br), bfR, 0.35, 0.012); bsR = envFollow(abs(br), bsR, 0.03, 0.08)
            var pl = (bfL - bsL).coerceAtLeast(0.0)
            var pr = (bfR - bsR).coerceAtLeast(0.0)
            l += bl * (pl / (bsL + 1e-9)).coerceIn(0.0, 8.0) * 0.15 * 0.45
            r += br * (pr / (bsR + 1e-9)).coerceIn(0.0, 8.0) * 0.15 * 0.45
            // exciter (0.1)
            l += 0.1 * 0.7 * aaExcite.process(l - exciteLp.process(l))
            r += 0.1 * 0.7 * aaExcite.process(r - exciteLp.process(r))
            // EQ 5 bandas
            for (i in 0 until 5) { l = eq[i].process(l); r = eq[i].process(r) }
            // tube (0.06)
            val d = 0.06; val g = 1.0 + 2.2 * d; val mk = 1.0 / (1.0 + 0.35 * d)
            l = tubeDc.process(aaTube.process(l * g)) * mk
            r = tubeDc.process(aaTube.process(r * g)) * mk
            // field surround (0.15)
            val cl = fieldL.process(l); val cr = fieldR.process(r)
            val center = (cl + cr) * 0.5
            l += center - cl; r += center - cr
            l += l * 0.38 * 0.15 * 1.3
            r += r * 0.38 * 0.15 * 1.3
            // punch (0.5)
            val prl = punchHpL.process(l); val prr = punchHpR.process(r)
            pfL = envFollow(abs(prl), pfL, 0.65, 0.004); psL = envFollow(abs(prl), psL, 0.008, 0.07)
            pfR = envFollow(abs(prr), pfR, 0.65, 0.004); psR = envFollow(abs(prr), psR, 0.008, 0.07)
            l += prl * ((pfL - psL).coerceAtLeast(0.0) / (psL + 1e-9)).coerceIn(0.0, 12.0) * 0.09 * 0.5
            r += prr * ((pfR - psR).coerceAtLeast(0.0) / (psR + 1e-9)).coerceIn(0.0, 12.0) * 0.09 * 0.5
            // compress 3 band (0.45)
            val ll = compLp.process(l); val lh = compHp.process(l); val lm = l - ll - lh
            val rl = compLpR.process(r); val rh = compHpR.process(r); val rm2 = r - rl - rh
            var ol = 0.0; var or = 0.0
            val bL = doubleArrayOf(ll, lm, lh)
            val bR = doubleArrayOf(rl, rm2, rh)
            for (i in 0 until 3) {
                val a2 = max(abs(bL[i]), abs(bR[i]))
                compEnv[i] = if (a2 > compEnv[i]) compEnv[i] * cAtk + (1 - cAtk) * a2 else compEnv[i] * cRel + (1 - cRel) * a2
                val g2 = softKnee(compEnv[i], 0.45f)
                compSm[i] += (g2 - compSm[i]) * cSm
                ol += bL[i] * compSm[i]; or += bR[i] * compSm[i]
            }
            l = ol; r = or
            // loudnessComp shelves
            l = loudHpL.process(loudLpL.process(l))
            r = loudHpR.process(loudLpR.process(r))
            // surface resonance (bocina, solo coste)
            l = surfShelfL.process(l); r = surfShelfR.process(r)
            // limiter maestro real
            limMaster.process(l * 1.08, r * 1.08, scratch)
            return scratch[0] + scratch[1]
        }
    }

    private val fs = 48000
    private val frames = 4800

    private fun material(): DoubleArray {
        val out = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            val t = i.toDouble() / fs
            val v = 0.25 * (sin(2.0 * Math.PI * 110 * t) + sin(2.0 * Math.PI * 220 * t) *
                (1.0 + 0.5 * sin(2.0 * Math.PI * 0.5 * t)) + 0.5 * sin(2.0 * Math.PI * 880 * t) + 0.25 * sin(2.0 * Math.PI * 2200 * t))
            val pan = 0.5 + 0.5 * sin(2.0 * Math.PI * 0.3 * t)
            out[i * 2] = v * (1.0 - pan * 0.5)
            out[i * 2 + 1] = v * (0.5 + pan * 0.5)
        }
        return out
    }

    private fun benchCurrent(): Double {
        val mat = material()
        val chain = CurrentChainReplica()
        var sink = 0.0
        var i = 0
        while (i < 512) { sink += chain.process(mat[i], mat[i + 1]); i += 2 }
        var best = Double.MAX_VALUE
        repeat(4) {
            val t0 = System.nanoTime()
            var j = 0
            while (j < frames * 2) { sink += chain.process(mat[j], mat[j + 1]); j += 2 }
            val ns = (System.nanoTime() - t0).toDouble()
            best = minOf(best, ns / frames)
        }
        if (sink == 123.0) println("")
        return best
    }

    private fun benchAudiophile(p: AudiophileConfig.ApPreset): Double {
        val mat = material()
        val dsp = KarinAudiophileDSP()
        val params = AudiophileConfig.presetParams(p)
        dsp.configure(fs, 2, params)
        dsp.setEngaged(true)
        val out = DoubleArray(2)
        var sink = 0.0
        var i = 0
        while (i < 512) { dsp.processFrame(mat[i], mat[i + 1], out); i += 2 }
        var best = Double.MAX_VALUE
        repeat(4) {
            val t0 = System.nanoTime()
            var j = 0
            while (j < frames * 2) { dsp.processFrame(mat[j], mat[j + 1], out); j += 2 }
            val ns = (System.nanoTime() - t0).toDouble()
            best = minOf(best, ns / frames)
        }
        sink += out[0]
        if (sink == 123.0) println("")
        return best
    }

    @Test
    fun comparativaRendimiento() {
        val currentNs = benchCurrent()
        val sb = StringBuilder()
        sb.appendLine("=== COMPARATIVA DSP (JVM host, 48 kHz, 4800 frames, musica -12 dBFS) ===")
        sb.appendLine("CURRENT (ANIME, replica tonalStereo): %.2f ns/frame".format(currentNs))
        for (p in AudiophileConfig.ApPreset.values()) {
            val ns = benchAudiophile(p)
            val pct = (ns / currentNs * 100).toInt()
            sb.appendLine("AUDIOPHILE %-15s: %.2f ns/frame  (%d%% de CURRENT)".format(p.label, ns, pct))
        }
        print(sb.toString())
        assertTrue("CURRENT debe correr (orden de magnitud)", currentNs in 1.0..10_000_000.0)
        assertTrue("audiophile debe correr", benchAudiophile(AudiophileConfig.ApPreset.PURE) in 1.0..10_000_000.0)
    }
}