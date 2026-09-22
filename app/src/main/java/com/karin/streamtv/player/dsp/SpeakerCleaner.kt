package com.karin.streamtv.player.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

object SpeakerCleaner {

    private var track: AudioTrack? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun start(
        durationSeconds: Int = 15,
        onProgress: ((Int) -> Unit)? = null,
        onFinished: (() -> Unit)? = null
    ) {
        if (running) return
        running = true

        worker = Thread {
            try {
                val sampleRate = 44100
                val totalSamples = sampleRate * durationSeconds
                val minFreq = 50.0
                val maxFreq = 18000.0
                val amplitude = 0.5f

                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val at = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track = at
                at.play()

                val buffer = ShortArray(bufferSize / 2)
                var samplesWritten = 0
                val phaseStep = 2.0 * PI / sampleRate
                var phase = 0.0

                while (samplesWritten < totalSamples && running) {
                    val progress = samplesWritten * 100 / totalSamples
                    onProgress?.invoke(progress)

                    val samplesToWrite = buffer.size.coerceAtMost(totalSamples - samplesWritten)

                    for (i in 0 until samplesToWrite) {
                        val t = samplesWritten.toDouble() / sampleRate
                        val progressNorm = samplesWritten.toDouble() / totalSamples

                        val freq = minFreq + (maxFreq - minFreq) * progressNorm

                        val sample = (sin(phase) * Short.MAX_VALUE * amplitude).toInt().toShort()
                        buffer[i] = sample

                        phase += freq * phaseStep
                        if (phase > 2.0 * PI) phase -= 2.0 * PI

                        samplesWritten++
                    }

                    at.write(buffer, 0, samplesToWrite)
                }

                onProgress?.invoke(100)

                try {
                    at.stop()
                } catch (_: IllegalStateException) { }
                at.release()
            } catch (_: Exception) { } finally {
                track = null
                running = false
                onFinished?.invoke()
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        try {
            track?.stop()
        } catch (_: Exception) { }
        try {
            track?.release()
        } catch (_: Exception) { }
        track = null
        worker?.interrupt()
        worker = null
    }
}
