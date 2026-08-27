package com.karin.streamtv.player.dsp

import kotlin.math.abs
import kotlin.math.tanh

internal fun bassBoost(amt: Double, harm: Double): Double {
    val boost = amt * harm
    val a = abs(boost)
    return if (a > 0.35) {
        val s = if (boost > 0) 1.0 else -1.0
        s * (0.35 + (a - 0.35) / (1.0 + (a - 0.35)))
    } else boost
}

// Síntesis armónica estilo MaxxBass/TruBass: extrae la banda de graves, la
// rectifica (|x| genera armónicos pares 2f, 4f, 6f... sin el fundamental) y
// filtra el fundamental antes de mezclar. La bocina no reproduce el sub-grave,
// pero sí sus armónicos; el oído reconstruye el bajo percibido.
//
// Bass dinámico: con un detector de envolvente del propio banda de graves se
// adapta el factor de mezcla — sube hasta +60% cuando el bajo es débil (para
// que pasajes tenues mantengan cuerpo) y baja hasta -50% en pasajes fuertes
// (para no ensuciar/recortar). Implementación propia, sin derivar de V4A/JDSP.
// Claridad de voz: la banda 1.1–4.5 kHz es donde vive la articulación del habla y
// donde una bocina chica de TV suena "opaca". Aquí se realza dinámicamente:
//   - Env que sube con la articulación (attack 4 ms, release 150 ms).
//   - Umbral adaptativo (thr) que sigue el piso de ruido; solo se realza cuando hay
//     contenido real en la banda (evita subir silencios y siseo de fondo).
//   - De-esser (7 kHz): si la sibilancia domina, baja el boost para no crispar.
// La mezcla es aditiva (x + banda·(g−1)): con g=1 es bypass exacto.
internal class SpeechClarity {
    private val pHp = BiquadFilter()
    private val pLp = BiquadFilter()
    private val dHp = BiquadFilter()
    private var env = 0.0
    private var thr = 0.0
    private var sEnv = 0.0
    private var gain = 1.0
    private var aA = 0.0
    private var aR = 0.0
    private var aT = 0.0
    private var amount = 0f

    fun configure(fs: Int, amount: Float) {
        this.amount = amount
        pHp.configure(BiquadFilter.Kind.HIGHPASS, fs, 1100f, 0f, 0.707f)
        pLp.configure(BiquadFilter.Kind.LOWPASS, fs, 4500f, 0f, 0.707f)
        dHp.configure(BiquadFilter.Kind.HIGHPASS, fs, 7000f, 0f, 0.707f)
        aA = Math.exp(-1.0 / (0.004 * fs))
        aR = Math.exp(-1.0 / (0.150 * fs))
        aT = Math.exp(-1.0 / (1.0 * fs))
        env = 0.0
        thr = 0.0
        sEnv = 0.0
        gain = 1.0
    }

    fun reset() {
        pHp.reset()
        pLp.reset()
        dHp.reset()
        env = 0.0
        thr = 0.0
        sEnv = 0.0
        gain = 1.0
    }

    fun process(x: Double): Double {
        val pres = pLp.process(pHp.process(x))
        val a = abs(pres)
        env = if (a > env) env * aA + (1 - aA) * a else env * aR + (1 - aR) * a
        if (a < env) thr = thr * aT + a * (1 - aT)
        val ratio = env / (thr + 1e-5)
        val t = if (ratio > 2.0) 1.0 + 0.45 * amount else 1.0 + 0.08 * amount
        gain += (t - gain) * 0.0008
        val s = abs(dHp.process(x))
        sEnv = if (s > sEnv) sEnv * aA + (1 - aA) * s else sEnv * aR + (1 - aR) * s
        val sr = sEnv / (env + 1e-5)
        val ess = if (sr > 1.2) 1.0 - 0.5 * ((sr - 1.2) / 0.8).coerceIn(0.0, 1.0) else 1.0
        return x + pres * (gain * ess - 1.0)
    }
}

// Emulación de "bocina sobre superficie" (la idea del Crystal Sound de LG, en DSP):
// una bocina chica apoyada en un rack/mesa gana cuerpo porque la superficie actúa
// como baffle (refuerzo de graves) y como caja resonante. Aquí:
//   1) shelf de boundary ~240 Hz (+4.5 dB máx) → el "baffle".
//   2) modo resonante de cavidad ~150 Hz Q≈5 que "canta" con los transientes de
//      graves → el "cajón". b0 normalizado para ganancia de pico = 1.0.
internal class SurfaceResonator {
    private val shelf = BiquadFilter()
    private val driveHp = BiquadFilter()
    private val driveLp = BiquadFilter()
    private var a1 = 0.0
    private var a2 = 0.0
    private var b0 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    private var amount = 0f

    fun configure(fs: Int, amount: Float) {
        this.amount = amount
        val a = amount.coerceIn(0f, 1f)
        shelf.configure(BiquadFilter.Kind.LOWSHELF, fs, 240f, 4.5f * a, 0.71f)
        driveHp.configure(BiquadFilter.Kind.HIGHPASS, fs, 45f, 0f, 0.71f)
        driveLp.configure(BiquadFilter.Kind.LOWPASS, fs, 320f, 0f, 0.71f)
        val k = fs / 48000.0
        val f0 = 150.0 * k
        val bw = 32.0 * k
        val r = Math.exp(-Math.PI * bw / fs)
        val w = 2.0 * Math.PI * f0 / fs
        a1 = -2.0 * r * Math.cos(w)
        a2 = r * r
        b0 = (1.0 - r) * Math.sqrt(1.0 + r * r - 2.0 * r * Math.cos(2.0 * w))
    }

    fun reset() {
        shelf.reset()
        driveHp.reset()
        driveLp.reset()
        y1 = 0.0
        y2 = 0.0
    }

    fun process(x: Double): Double {
        val boosted = shelf.process(x)
        val d = driveLp.process(driveHp.process(x))
        val y = b0 * d - a1 * y1 - a2 * y2
        y2 = y1
        y1 = y
        return boosted + y * amount.toDouble()
    }
}

internal class VirtualBass {
    private val lp = BiquadFilter()
    private val smooth = BiquadFilter()
    private val hp = BiquadFilter()
    private var dynamic = true
    private var env = 0.0
    private var dynGain = 1.0
    private var attack = 0.0
    private var release = 0.0
    private var smoothG = 0.0

    fun configure(fs: Int, crossover: Float = 120f, dynamic: Boolean = true) {
        this.dynamic = dynamic
        lp.configure(BiquadFilter.Kind.LOWPASS, fs, crossover, 0f, 0.707f)
        smooth.configure(BiquadFilter.Kind.LOWPASS, fs, crossover * 2.5f, 0f, 0.707f)
        hp.configure(BiquadFilter.Kind.HIGHPASS, fs, crossover * 1.33f, 0f, 0.707f)
        attack = Math.exp(-1.0 / (0.020 * fs))
        release = Math.exp(-1.0 / (0.25 * fs))
        smoothG = Math.exp(-1.0 / (0.050 * fs))
        env = 0.0
        dynGain = 1.0
    }

    fun process(x: Double): Double {
        val bass = lp.process(x)
        val rect = smooth.process(abs(bass))
        val a = abs(rect)
        env = if (a > env) env * attack + (1 - attack) * a else env * release + (1 - release) * a
        if (dynamic) {
            // Gate: sin contenido de graves real (silencio o medios que cuelan por
            // el LPF) la generación de armónicos se apaga. Antes se AMPLIFICABA hasta
            // +60% con poco bajo, lo que convertía el residuo de voz/medios en
            // armónicos audibles (distorsión). Con graves reales → ganancia 1.0;
            // pasajes fuertes → ducking para no ensuciar.
            var target = 0.0
            if (env >= 0.05) {
                target = if (env < 0.40) 1.0 else 1.0 - (env - 0.40) / 0.40 * 0.5
            }
            dynGain = dynGain * smoothG + target * (1 - smoothG)
        } else {
            dynGain = 1.0
        }
        return hp.process(rect)
    }

    fun gainFactor(): Double = dynGain

    fun reset() {
        lp.reset()
        smooth.reset()
        hp.reset()
        env = 0.0
        dynGain = 1.0
    }
}

// Wave-shaper de triodo: saturación suave y asimétrica (genera armónico par,
// el "calor" del tubo) con companding racional (v + a·v²)/(1 + b·v²). El DC
// generado por la asimetría se elimina con un highpass; drive=0 es bypass puro.
internal fun tubeDrive(x: Double, dc: BiquadFilter, drive: Float): Double {
    if (drive <= 0f) return x
    val g = 1.0 + 2.2 * drive.toDouble()
    val a = 0.22 * drive.toDouble()
    val b = 1.8 * drive.toDouble()
    val v = g * x
    val y = (v + a * v * v) / (1.0 + b * v * v)
    val makeup = 1.0 / (1.0 + 0.35 * drive.toDouble())
    return dc.process(y) * makeup
}

// Emulación de excitador armónico para bocinas TV: aplica saturación suave
// al componente de graves (LPF < 1400 Hz) para generar armónicos de calidez,
// y una saturación más contenida a los agudos para presencia sin agresividad.
// El resultado es un sonido más "lleno" y "profesional" sin distorsión escuchable.
internal fun excite(x: Double, lp: BiquadFilter, hp: BiquadFilter, amt: Float): Double {
    if (amt <= 0.0f) return x
    // Excitador de 3 bandas (diseño profesional tipo Aural Exciter):
    //   sub = < crossover bajo   -> saturación suave (calidez en sub-graves)
    //   hi  = > crossover alto   -> saturación suave (presencia/brillo)
    //   mid = banda de voz       -> BYPASS exacto (intacta, sin armónicos)
    // Antes se saturada toda la banda < 1.4 kHz con tanh duro, que distorsionaba
    // voz y medios (el 2% de THD medido). Ahora la voz queda limpia.
    val sub = lp.process(x)
    val hi = hp.process(x)
    val mid = x - sub - hi
    val bassHarm = tanh(sub * 1.6) * 0.30
    val highHarm = tanh(hi * 2.0) * 0.25
    return mid + sub + hi + amt * 0.7 * (bassHarm + highHarm)
}
