package com.karin.streamtv.player.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Mejoras "mastering-grade" (Fase 1):
//   · Dither + noise-shaping para 24/32-bit (audio digital de alta resolución).
//   · Sobremuestreo 2× anti-aliasing para las etapas no lineales.
//   · Limiter multibanda (LR4 + true-peak linkeado por banda).
//   · Nivelación de sonoridad EBU R128 (medidor K + AGC de volumen).
// ---------------------------------------------------------------------------

// F-weighted 4th-order noise shaper (Lipshitz/Vanderkooy/Waugh): empuja el
// error de cuantización a las bandas de menor sensibilidad. Se combina con un
// dither TPDF de ±0.5 LSB para 24/32-bit (implícitamente ya disfrazado por el
// shaping en las frecuencias altas donde la audición es más tolerante).
class NoiseShaper {
    private var e1 = 0.0
    private var e2 = 0.0
    private var e3 = 0.0
    private var e4 = 0.0

    /** Devuelve la muestra "moldeada" (en unidades LSB) lista para cuantizar. */
    fun shaped(xLsb: Double): Double =
        xLsb - 2.371 * e1 + 1.725 * e2 - 0.574 * e3 + 0.212 * e4

    /** Introduce el error de cuantización del último paso (en unidades LSB). */
    fun pushError(shaped: Double, quantized: Double) {
        e4 = e3
        e3 = e2
        e2 = e1
        e1 = shaped - quantized
    }

    fun reset() {
        e1 = 0.0
        e2 = 0.0
        e3 = 0.0
        e4 = 0.0
    }
}

// Sobremuestreador 2× en streaming para funciones NO lineales sin estado.
// Sube 2× (interpolación lineal), aplica f y luego filtra paso-bajo (Blackman-
// sinc, corte ≈ 0.45 de la Nyquist nueva) antes de decimar. Así los armónicos
// que f generaría por encima de la Nyquist original se eliminan ANTES de que
// plieguen (aliasing). El filtro introduce ~ (N-1)/4 muestras de retardo de
// grupo (despreciable a 48 kHz para rutas aditivas).
class Over2x {
    private var fn: (Double) -> Double = { it }
    private val nTaps = 33
    private val mask = 63
    private val h = DoubleArray(nTaps)
    private val fy = DoubleArray(mask + 2)
    private var head = 0
    private var prev = 0.0

    init {
        val k = (nTaps - 1) / 2
        val fc = 0.45 // normalizado a la Nyquist de la señal a 2× (0.5)
        for (i in 0 until nTaps) {
            val x = (i - k).toDouble()
            val w = 0.42 - 0.5 * cos(PI * i / (nTaps - 1)) + 0.08 * cos(2.0 * PI * i / (nTaps - 1))
            val sinc = if (abs(x) < 1e-9) 2.0 * fc else sin(2.0 * PI * fc * x) / (PI * x)
            h[i] = w * sinc
        }
        var s = 0.0
        for (i in 0 until nTaps) s += h[i]
        for (i in 0 until nTaps) h[i] /= s
    }

    constructor() {}

    constructor(nonlinearity: (Double) -> Double) {
        fn = nonlinearity
    }

    fun use(nonlinearity: (Double) -> Double) {
        fn = nonlinearity
    }

    fun process(x: Double): Double =
        compute(fn((x + prev) * 0.5), fn(x), x)

    // Racional del tubo (v + a·v²)/(1 + b·v²) con sobremuestreo, SIN allocar
    // un closure por muestra (los coeficientes varían con drive, no por muestra).
    fun processTube(x: Double, a: Double, b: Double): Double =
        compute(tube(a, b, (x + prev) * 0.5), tube(a, b, x), x)

    private fun tube(a: Double, b: Double, v: Double): Double {
        val v2 = v * v
        return (v + a * v2) / (1.0 + b * v2)
    }

    private fun compute(oddValue: Double, evenValue: Double, x: Double): Double {
        prev = x
        fy[head] = oddValue
        fy[(head + 1) and mask] = evenValue
        head = (head + 2) and mask
        var acc = 0.0
        var slot = head + mask // (head - 1) mod M
        for (i in 0 until nTaps) {
            acc += h[i] * fy[slot and mask]
            slot -= 1
        }
        return acc
    }

    fun reset() {
        head = 0
        prev = 0.0
        for (i in fy.indices) fy[i] = 0.0
    }
}

// Limiter maESTRO multibanda (3 bandas: graves 0-f1, medios f1-f2, agudos f2-Nyq)
// con crossovers Linkwitz-Riley 4º orden (2 Butterworth 2º en cascada). Cada
// banda usa un limiter linkeado L/R con detección true-peak inter-sample; la
// suma se protege con un lookahead final. Ventaja sobre el limiter de banda
// completa: un golpe de graves ya no comprime los agudos (menos "pumping"),
// y cada banda puede tener su propio perfil de release.
class MultibandLimiter {
    private val fLow = LookaheadLimiterPair()
    private val fMid = LookaheadLimiterPair()
    private val fHigh = LookaheadLimiterPair()
    private val fMaster = LookaheadLimiterPair()

    // Cascadas LR4 (dos Biquad 2º orden en serie) por canal.
    private val lpLowAL = BiquadFilter()
    private val lpLowBL = BiquadFilter()
    private val lpLowAR = BiquadFilter()
    private val lpLowBR = BiquadFilter()

    // Medios = HP4(f1) seguido de LP4(f2)
    private val hpMidAL = BiquadFilter()
    private val hpMidBL = BiquadFilter()
    private val hpMidAR = BiquadFilter()
    private val hpMidBR = BiquadFilter()
    private val lpMidAL = BiquadFilter()
    private val lpMidBL = BiquadFilter()
    private val lpMidAR = BiquadFilter()
    private val lpMidBR = BiquadFilter()

    // Agudos = HP4(f2)
    private val hpHighAL = BiquadFilter()
    private val hpHighBL = BiquadFilter()
    private val hpHighAR = BiquadFilter()
    private val hpHighBR = BiquadFilter()

    fun configure(fs: Int) {
        val f1 = minOf(120f, 0.20f * fs)      // graves/medios
        val f2 = minOf(3200f, 0.44f * fs)     // medios/agudos
        val lp = listOf(lpLowAL, lpLowBL, lpLowAR, lpLowBR)
        for (b in lp) b.configure(BiquadFilter.Kind.LOWPASS, fs, f1, 0f, 0.707f)
        val hpMid = listOf(hpMidAL, hpMidBL, hpMidAR, hpMidBR)
        for (b in hpMid) b.configure(BiquadFilter.Kind.HIGHPASS, fs, f1, 0f, 0.707f)
        val lpMid = listOf(lpMidAL, lpMidBL, lpMidAR, lpMidBR)
        for (b in lpMid) b.configure(BiquadFilter.Kind.LOWPASS, fs, f2, 0f, 0.707f)
        val hpHigh = listOf(hpHighAL, hpHighBL, hpHighAR, hpHighBR)
        for (b in hpHigh) b.configure(BiquadFilter.Kind.HIGHPASS, fs, f2, 0f, 0.707f)
        fLow.configure(fs, 1f, 140f, 0.97)
        fMid.configure(fs, 1f, 110f, 0.99)
        fHigh.configure(fs, 1f, 90f, 0.99)
        fMaster.configure(fs, 2f, 100f, 0.99)
    }

    fun process(l: Double, r: Double): Pair<Double, Double> {
        // Bandas (crossovers LR4 en serie)
        val lowL = lpLowBL.process(lpLowAL.process(l))
        val lowR = lpLowBR.process(lpLowAR.process(r))
        val midL = lpMidBL.process(lpMidAL.process(hpMidBL.process(hpMidAL.process(l))))
        val midR = lpMidBR.process(lpMidAR.process(hpMidBR.process(hpMidAR.process(r))))
        val hiL = hpHighBL.process(hpHighAL.process(l))
        val hiR = hpHighBR.process(hpHighAR.process(r))

        // Limitación por banda (linkeada L/R, true-peak)
        val pl = fLow.process(lowL, lowR)
        val pm = fMid.process(midL, midR)
        val ph = fHigh.process(hiL, hiR)
        return fMaster.process(pl.first + pm.first + ph.first, pl.second + pm.second + ph.second)
    }

    fun setThreshold(t: Double) {
        fMaster.setThreshold(t)
    }

    fun reset() {
        for (b in listOf(lpLowAL, lpLowBL, lpLowAR, lpLowBR, hpMidAL, hpMidBL, hpMidAR, hpMidBR,
                lpMidAL, lpMidBL, lpMidAR, lpMidBR, hpHighAL, hpHighBL, hpHighAR, hpHighBR)) b.reset()
        fLow.reset()
        fMid.reset()
        fHigh.reset()
        fMaster.reset()
    }
}

// Medidor de sonoridad EBU R128 / ITU-R BS.1770 (K-weighting) + AGC de
// "volumen nivelado": mira la LUFS short-term del flujo y ajusta lentamente la
// ganancia para que los distintos contenidos (serie, peli, anuncio, pista)
// suenen al mismo volumen objetivo. Silencio → sin boost (gating en el piso).
class LoudnessAgc {
    private var hpL = BiquadFilter()
    private var shL = BiquadFilter()
    private var hpR = BiquadFilter()
    private var shR = BiquadFilter()
    private var acc = 0.0
    private var gainDb = 0.0
    private var leak = 0.0
    private var smoothUp = 0.0   // la ganancia SUBE lento (nivelación suave)
    private var smoothDn = 0.0   // la ganancia BAJA rápido (no satura)
    private var amount = 0f
    private var target = -16.0   // LUFS objetivo (dominio streaming)
    private var active = false
    private var cnt = 0
    private var updateEvery = 1024

    fun configure(fs: Int, amount: Float) {
        this.amount = amount.coerceIn(0f, 1f)
        this.active = amount > 0f && fs > 0
        if (!active) {
            gainDb = 0.0
            acc = 0.0
            return
        }
        // K-weighting: HP 38 Hz Butterworth + high-shelf RLB 4 dB a ~1.7 kHz.
        hpL.configure(BiquadFilter.Kind.HIGHPASS, fs, 38f, 0f, 0.707f)
        hpR.configure(BiquadFilter.Kind.HIGHPASS, fs, 38f, 0f, 0.707f)
        shL.configure(BiquadFilter.Kind.HIGHSHELF, fs, 1681.97f, 4f, 0.707f)
        shR.configure(BiquadFilter.Kind.HIGHSHELF, fs, 1681.97f, 4f, 0.707f)
        leak = Math.exp(-1.0 / (3.0 * fs))     // ventana efectiva ~3 s (short-term)
        smoothUp = Math.exp(-1.0 / (2.0 * fs)) // +12 dB/s aprox. (subir)
        smoothDn = Math.exp(-1.0 / (0.25 * fs)) // bajar más rápido (control)
        updateEvery = maxOf(fs / 4, 2048)
        acc = 0.0
        gainDb = 0.0
        cnt = 0
    }

    fun reset() {
        hpL.reset()
        shL.reset()
        hpR.reset()
        shR.reset()
        acc = 0.0
        gainDb = 0.0
        cnt = 0
    }

    /** Alimenta el medidor con la salida L/R ya procesada (lo que oye el usuario). */
    fun tap(l: Double, r: Double) {
        if (!active) return
        val al = shL.process(hpL.process(l))
        val ar = shR.process(hpR.process(r))
        acc = acc * leak + (al * al + ar * ar)
        cnt++
        if (cnt < updateEvery) return
        cnt = 0
        // BS.1770: LUFS = -0.691 + 10·log10( Σx² / N )
        val lufs = -0.691 + 10.0 * log10((acc / 2.0).coerceAtLeast(1e-10))
        if (lufs < -50.0) return // silencio: no nivelar contra el piso
        val need = (target - lufs) * amount.toDouble()
        val clamped = need.coerceIn(-3.0 * amount, 3.0 * amount)
        val coef = if (clamped < gainDb) smoothDn else smoothUp
        gainDb += (clamped - gainDb) * (1.0 - coef)
    }

    /** Ganancia actual de nivelación (1.0 = sin cambio). */
    fun gain(): Double = if (active) 10.0.pow(gainDb / 20.0) else 1.0
}

// ---------------------------------------------------------------------------
// RTA en vivo (⑥): medidor de espectro + LUFS que el UI dibuja en tiempo real.
// Corre en el hilo de audio (barato: 8 band-pass + follower de envolvente) y
// publica snapshots inmutables en un campo volatile → el hilo de UI los lee sin
// locks. Bandas logarítmicas; dB normalizado contra techo de nivel.
// ---------------------------------------------------------------------------
class LiveRta {
    class Snapshot(val bands: FloatArray, val lufs: Float)

    @Volatile
    var snapshot = Snapshot(FloatArray(BAND_COUNT) { -120f }, -160f)
        private set

    companion object {
        const val BAND_COUNT = 8
        // Último snapshot publicado; el hilo de UI lo lee directamente.
        // Piso -120/-160 (no 0f): con bandas en 0 dB el UI dibujaba barras
        // llenas en reposo y un falso LUFS 0.0.
        @Volatile
        var current = Snapshot(FloatArray(BAND_COUNT) { -120f }, -160f)
        // Bordes geométricos de las bandas (Hz): [45..90..180..355..707..1414..2828..5657..11314]
        private val EDGE_HZ = intArrayOf(45, 90, 180, 355, 707, 1414, 2828, 5657, 11314)
    }

    private val hp = Array(BAND_COUNT) { BiquadFilter() }
    private val lp = Array(BAND_COUNT) { BiquadFilter() }
    private val rms = DoubleArray(BAND_COUNT)
    private var ready = false
    private var cnt = 0
    private var publishEvery = 512
    private val attack = 0.35
    private val release = 0.12
    private val freeze = 0.9995

    fun configure(fs: Int) {
        for (i in 0 until BAND_COUNT) {
            hp[i].configure(BiquadFilter.Kind.HIGHPASS, fs, EDGE_HZ[i].toFloat(), 0f, 0.9f)
            lp[i].configure(BiquadFilter.Kind.LOWPASS, fs, EDGE_HZ[i + 1].toFloat(), 0f, 0.9f)
        }
        publishEvery = (fs / 100).coerceIn(64, 2048) // ~10 Hz de refresco
        ready = true
    }

    fun reset() {
        for (f in hp) f.reset()
        for (f in lp) f.reset()
        rms.fill(0.0)
        peak.fill(0.0)
        cnt = 0
        rmsLufs = 0.0
        lufsCnt = 0
        // Limpiar también el companion: reset() solo dejaba el snapshot local
        // en 0 y el companion seguía congelando el último valor en la UI.
        snapshot = Snapshot(FloatArray(BAND_COUNT) { -120f }, -160f)
        current = snapshot
    }

    /** Alimentar post-procesamiento (ya con ganancia de master aplicada). */
    fun process(l: Double, r: Double) {
        if (!ready) return
        peak[0] = maxOf(peak[0] * freeze, abs(l))
        peak[1] = maxOf(peak[1] * freeze, abs(r))
        val mono = (l + r) * 0.5
        // LUFS aproximado en vivo (sin gate móvil; válido para medidor).
        rmsLufs += mono * mono
        lufsCnt++
        for (i in 0 until BAND_COUNT) {
            val v = lp[i].process(hp[i].process(mono))
            val sq = v * v
            rms[i] = if (sq > rms[i]) rms[i] + (sq - rms[i]) * attack else rms[i] + (sq - rms[i]) * release
        }
        cnt++
        if (cnt >= publishEvery) {
            cnt = 0
            val bands = FloatArray(BAND_COUNT)
            var rsum = 0.0
            for (i in 0 until BAND_COUNT) {
                rsum += rms[i]
                // dB relativos del espectro (normalizado a la banda más fuerte).
                bands[i] = (20.0 * log10(sqrt(rms[i]).coerceAtLeast(1e-12))).toFloat()
            }
            val lufs = -0.691 + 10.0 * log10((rmsLufs / lufsCnt).coerceAtLeast(1e-10))
            rmsLufs = 0.0
            lufsCnt = 0
            snapshot = Snapshot(bands, lufs.toFloat())
            current = snapshot
        }
    }

    private var peak = doubleArrayOf(0.0, 0.0)
    private var rmsLufs = 0.0
    private var lufsCnt = 0L
}