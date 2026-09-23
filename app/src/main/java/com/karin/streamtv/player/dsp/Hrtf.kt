package com.karin.streamtv.player.dsp

import kotlin.math.PI
import kotlin.math.sin

/**
 * HRTF medido/real para el renderizado binaural.
 *
 * Dos fuentes de verdad, con respaldo automático:
 *  1. [parse] — un HRTF medido (p.ej. MIT KEMAR, CIPIC o una medición propia)
 *     volcado a TSV: por fuente (azim, elev) entrega retardo por oído (µs),
 *     lowpass del oído (Hz), ganancia (dB) y notchs de pinna (f, dB, Q).
 *     Si hay un archivo válido, sus parámetros reemplazan la mesa interna.
 *  2. [woodworthItdUsec] — ITD físico (fórmula de Woodworth: el sonido recorre
 *     el arco de la cabeza más la cuerda) como respaldo/mejora fisiológica.
 */
class Hrtf {

    /** Parámetros de renderizado para una fuente virtual (L/R por separado). */
    class Ear(val delayUsec: Double, val lpHz: Float, val gainDb: Float)
    class Source(
        val azimDeg: Double,
        val elevDeg: Double,
        val left: Ear,
        val right: Ear,
        val notches: Array<Triple<Float, Float, Float>>
    ) {
        override fun toString(): String = "Hrtf.Source(azim=$azimDeg elev=$elevDeg)"
    }

    data class Parsed(val sources: List<Source>)

    /** SQL-style: fuente más cercana en azim/elev. */
    fun nearest(azimDeg: Double, elevDeg: Double): Source? {
        var best: Source? = null
        // Umbral amplio (d = Δaz + 2·Δelev): los archivos con elev ±30° dan
        // d=60 y con el corte viejo (45) nunca casaban → se perdía el HRTF.
        var bestD = 90.0
        for (s in sourcesFast) {
            var da = kotlin.math.abs(((s.azimDeg - azimDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0)
            val de = kotlin.math.abs(s.elevDeg - elevDeg)
            val d = da + 2.0 * de
            if (d < bestD) {
                bestD = d
                best = s
            }
        }
        return best
    }

    private var sourcesFast = emptyList<Source>()

    /** Construye una instancia desde el TSV parseado (vacío si no hay datos). */
    constructor(raw: String?) {
        sourcesFast = parse(raw)
    }

    val isEmpty: Boolean get() = sourcesFast.isEmpty()
    val size: Int get() = sourcesFast.size

    companion object {
        /** ITD de Woodworth: Δt = a/c · (sen θ + θ), θ en rad (a ≈ 7,6 cm, c = 343). */
        fun woodworthItdUsec(azimuthDeg: Double): Double {
            val a = 0.075 // radio de cabeza (m)
            val c = 343.0
            var th = azimuthDeg.coerceIn(-180.0, 180.0) * PI / 180.0
            // Fórmula válida |θ| ≤ 90°; para el resto usamos la extensión
            // "alrededor de la cabeza": ITD crece hasta ~90° y luego vuelve a caer.
            val itd = if (kotlin.math.abs(th) <= PI / 2) {
                th + sin(th)
            } else {
                val s = if (th > 0) 1.0 else -1.0
                (PI - kotlin.math.abs(th)) + 1.0 - (1.0 - sin(kotlin.math.abs(th))) // forma suave alrededor
            } * (a / c) * 1e6
            return itd
        }

        /**
         * Parsea el TSV medido. Formato (texto plano, # = comentario):
         *   azim  elev  delayL_us  lpL_hz  gainL_db  delayR_us  lpR_hz  gainR_db
         *   notch_f_hz notch_db notch_1 ... (0 o N tripletas por línea)
         */
        fun parse(raw: String?): List<Source> {
            if (raw == null) return emptyList()
            val out = ArrayList<Source>()
            for (line in raw.lineSequence()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val p = t.split(Regex("\\s+"))
                if (p.size < 8) continue
                try {
                    val azim = p[0].toDouble()
                    val elev = p[1].toDouble()
                    val dl = p[2].toDouble()
                    val llp = p[3].toFloat()
                    val gl = p[4].toFloat()
                    val dr = p[5].toDouble()
                    val rlp = p[6].toFloat()
                    val gr = p[7].toFloat()
                    val notches = ArrayList<Triple<Float, Float, Float>>()
                    var i = 8
                    while (i + 2 < p.size) {
                        notches.add(Triple(p[i].toFloat(), p[i + 1].toFloat(), p[i + 2].toFloat()))
                        i += 3
                    }
                    out.add(
                        Source(
                            azim, elev,
                            Ear(dl, llp, gl),
                            Ear(dr, rlp, gr),
                            notches.toTypedArray()
                        )
                    )
                } catch (_: NumberFormatException) {
                    // línea basura: se salta
                }
            }
            return out
        }
    }
}