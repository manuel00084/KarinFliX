package com.karin.streamtv.player.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

/**
 * Limpieza de bocina (polvo).
 *
 * Física del método: el polvo se desprende por la EXCURSIÓN del cono, es
 * decir por lo lejos que viaja la membrana. Eso ocurre en graves (100-400 Hz);
 * por arriba de ~4 kHz el cono apenas se mueve y el tono no sirve para
 * soltar nada. Un barrido lineal spends casi la mitad del tiempo en agudos
 * inútiles, así que aquí el perfil pone el tiempo donde sí hay vibración:
 *
 *  1. BOMBO: tono grave (~110 Hz) modulado en amplitud (dos tonos muy
 *     juntos laten y empujan más lejos que un solo seno al mismo pico).
 *  2. BARRIDO LOG: 90 Hz -> 9 kHz con curva log², que reparte el tiempo en
 *     los graves en vez de regalárselo a los agudos.
 *  3. SACUDIDA: ráfagas graves cortas separadas por silencio, para que el
 *     polvo ya soltado caiga de la rejilla.
 *
 * Rampa de entrada/salida de 60 ms en cada tramo: sin chasquidos (un click
 * en un cono pequeño lo hace saltar y es justo lo que daña).
 */
object SpeakerCleaner {

    enum class Level(val label: String, val amp: Double, val passes: Int) {
        SUAVE("Suave (uso frecuente)", 0.50, 1),
        ESTANDAR("Estándar (recomendado)", 0.78, 1),
        INTENSA("Máxima, 2 pasadas (polvo terco)", 0.95, 2),
    }

    private val lock = Any()
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    private const val SR = 44100
    private const val CHUNK = 1024
    private const val FADE_MS = 60

    /** tanh(1): normaliza el limitador para que el pico sea 1.0 exacto. */
    private val TANH1 = kotlin.math.tanh(1.0)

    /** Tramo del plan: tono fijo, barrido, o silencio. */
    private class Seg(
        val f0: Double,
        val f1: Double,
        val durMs: Int,
        val amp: Double,
        val sweep: Boolean = false,
        val amDepth: Double = 0.0,
        val amHz: Double = 0.0,
    )

    /**
     * Plan de limpieza. El polvo sale por EXCURSIÓN, y a igualdad de tensión
     * el desplazamiento crece al bajar la frecuencia: por eso el trabajo duro
     * está entre 55 y 200 Hz, no arriba. Las pasadas altas (sweep) barren lo
     * que quedó y remueven la rejilla.
     */
    private fun plan(amp: Double, passes: Int): List<Seg> {
        val out = mutableListOf<Seg>()
        repeat(passes.coerceIn(1, 2)) {
            // 1. Golpe grave: 70 Hz con latido de 5 Hz -> el cono viaja
            //    más lejos que con un seno limpio al mismo pico.
            out += Seg(70.0, 70.0, 6500, amp, amDepth = 0.5, amHz = 5.0)
            // 2. Barrido log² 55 Hz -> 9 kHz (dos veces): sube lentísimo al
            //    principio, así el tiempo se va a los graves.
            out += Seg(55.0, 9000.0, 5500, amp, sweep = true)
            out += Seg(55.0, 9000.0, 5500, amp, sweep = true)
            // 3. Golpe extra en 90 Hz: segunda zona de máximo desplazamiento.
            out += Seg(90.0, 90.0, 2500, amp, amDepth = 0.45, amHz = 6.0)
            // 4. Sacudida: grave fuerte, silencio (cae el polvo), y otra vez.
            out += Seg(70.0, 70.0, 800, amp, amDepth = 0.4, amHz = 6.0)
            out += Seg(0.0, 0.0, 700, 0.0)
            out += Seg(70.0, 70.0, 800, amp, amDepth = 0.4, amHz = 6.0)
            out += Seg(0.0, 0.0, 1000, 0.0)
        }
        return out
    }

    fun start(
        level: Level = Level.ESTANDAR,
        onProgress: ((Int) -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
    ) {
        synchronized(lock) {
            if (running) return
            running = true
            worker = Thread({
                var at: AudioTrack? = null
                try {
                    val segs = plan(level.amp, level.passes)
                    val totalMs = segs.sumOf { it.durMs }
                    val bufferSize = AudioTrack.getMinBufferSize(
                        SR,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ).coerceAtLeast(CHUNK * 4)

                    at = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SR)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    synchronized(lock) { track = at }
                    at.play()

                    val buffer = ShortArray(CHUNK)
                    var phase = 0.0
                    var doneMs = 0
                    var lastPct = -1

                    for (seg in segs) {
                        if (!running) break
                        val segSamples = seg.durMs * SR / 1000
                        val fadeSamples = (FADE_MS * SR / 1000).coerceAtLeast(1)
                        val logRatio = if (seg.f0 > 0 && seg.f1 > 0) ln(seg.f1 / seg.f0) else 0.0
                        var i = 0

                        while (i < segSamples && running) {
                            val n = minOf(CHUNK, segSamples - i)
                            for (k in 0 until n) {
                                val s = i + k
                                if (seg.amp <= 0.0) {
                                    buffer[k] = 0
                                    continue
                                }
                                val x = s.toDouble() / segSamples
                                // Barrido exponencial con peso x²: la frecuencia
                                // sube despacio al principio (los graves se llevan
                                // la mayor parte del tramo).
                                val freq = if (seg.sweep && logRatio != 0.0)
                                    seg.f0 * kotlin.math.exp(logRatio * x * x)
                                else seg.f0
                                // Rampa de 60 ms en los bordes del tramo.
                                val fade = when {
                                    s < fadeSamples -> s.toDouble() / fadeSamples
                                    s > segSamples - fadeSamples ->
                                        (segSamples - s).toDouble() / fadeSamples
                                    else -> 1.0
                                }
                                val am = if (seg.amDepth > 0.0) {
                                    val t = (doneMs + s) / 1000.0
                                    (1.0 - seg.amDepth) +
                                        seg.amDepth * sin(2.0 * PI * seg.amHz * t)
                                } else 1.0
                                val v = sin(phase) * seg.amp * fade * am
                                // Limitador suave (tanh): permite acercarse al
                                // tope de amplitud sin recorte duro, que es lo
                                // que más calienta y suena a distorsión.
                                val soft = kotlin.math.tanh(v) / TANH1
                                buffer[k] = (soft * Short.MAX_VALUE).toInt().toShort()
                                phase += freq * 2.0 * PI / SR
                                if (phase > 2.0 * PI) phase -= 2.0 * PI
                            }
                            val wrote = at.write(buffer, 0, n)
                            if (wrote < 0) break
                            i += n
                            doneMs += n * 1000 / SR
                            val pct = (doneMs * 100 / totalMs).coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress?.invoke(pct)
                            }
                        }
                    }
                    if (running) onProgress?.invoke(100)
                } catch (_: Exception) {
                } finally {
                    try {
                        at?.pause()
                    } catch (_: Exception) {
                    }
                    try {
                        at?.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        at?.release()
                    } catch (_: Exception) {
                    }
                    synchronized(lock) { track = null }
                    running = false
                    onFinished?.invoke()
                }
            }, "speaker-cleaner").also { it.start() }
        }
    }

    fun stop() {
        running = false
        // No se libera el AudioTrack desde aquí: el hilo es quien lo suelta.
        // Solo se corta la reproducción para desbloquear su write().
        val t = track
        if (t != null) {
            try { t.pause() } catch (_: Exception) { }
            try { t.flush() } catch (_: Exception) { }
        }
        worker?.interrupt()
        worker = null
    }
}
