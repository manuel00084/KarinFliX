package com.karin.streamtv.player.dsp

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

class AudioEnhanceProcessor : BaseAudioProcessor() {
    private var sampleRate = 48000
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    private var stereo = false
    private var eqReady = false
    private var logged = false

    private var eqL = Array(6) { BiquadFilter() }
    private var eqR = Array(6) { BiquadFilter() }
    private var bassLpL = BiquadFilter()
    private var bassLpR = BiquadFilter()
    private var shelfL = BiquadFilter()
    private var shelfR = BiquadFilter()
    private var envL = 0.0
    private var envR = 0.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        stereo = channels >= 2
        eqReady = false
        Log.i("AudioEnhance", "config fs=$sampleRate ch=$channels enc=$encoding")
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val params = AudioEnhanceConfig.params()
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }
        if (!params.enabled) {
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }
        if (!logged) {
            logged = true
            Log.i("AudioEnhance", "dsp activo preset=${params.preset} thx=${params.thx} bass=${params.bass} space=${params.space} voice=${params.voice} master=${params.masterGain}")
        }
        ensureConfigured(params)
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val bytesPerFrame = bytesPerSample * channels
        val frames = inputBuffer.remaining() / bytesPerFrame
        val out = replaceOutputBuffer(frames * bytesPerFrame)
        try {
            for (f in 0 until frames) {
                if (stereo) {
                    val l0 = readSample(inputBuffer)
                    val r0 = readSample(inputBuffer)
                    var l = l0.toDouble()
                    var r = r0.toDouble()
                    val m = (l + r) * 0.5
                    val s = (l - r) * 0.5
                    val w = 1.0 + params.space * 0.55
                    l = m + s * w
                    r = m - s * w
                    l = truBass(l, params, env = true)
                    r = truBass(r, params, env = false)
                    for (i in eqL.indices) {
                        l = eqL[i].process(l)
                        r = eqR[i].process(r)
                    }
                    writeSample(out, l)
                    writeSample(out, r)
                } else {
                    val x0 = readSample(inputBuffer)
                    var x = x0.toDouble()
                    x = truBass(x, params, env = true)
                    for (i in eqL.indices) {
                        x = eqL[i].process(x)
                    }
                    writeSample(out, x)
                }
            }
        } catch (t: Throwable) {
            Log.w("AudioEnhance", "dsp error: ${t.message}")
        }
        out.flip()
    }

    private fun truBass(x: Double, params: AudioEnhanceConfig.Params, env: Boolean): Double {
        val b = if (env) bassLpL.process(x) else bassLpR.process(x)
        val a = abs(b)
        val e = if (env) envL else envR
        val e2 = if (a > e) e + (a - e) * 0.35 else e + (a - e) * 0.012
        if (env) envL = e2 else envR = e2
        val norm = min(1.0, e2 * 6.0)
        val gainDb = params.bass * (3.0 + 7.0 * norm)
        val shelf = if (env) shelfL else shelfR
        shelf.configure(BiquadFilter.Kind.LOWSHELF, sampleRate, 65f, gainDb.toFloat(), 0.8f)
        return shelf.process(x)
    }

    private data class Band(val kind: BiquadFilter.Kind, val f0: Float, val gain: Float, val q: Float)

    private fun ensureConfigured(params: AudioEnhanceConfig.Params) {
        val thx = params.thx
        val voice = params.voice
        val bands = arrayOf(
            Band(BiquadFilter.Kind.PEAKING, 180f, 1.5f * thx, 1.0f),
            Band(BiquadFilter.Kind.PEAKING, 450f, 0.5f * thx, 0.9f),
            Band(BiquadFilter.Kind.PEAKING, 1200f, -1.2f * thx, 0.8f),
            Band(BiquadFilter.Kind.PEAKING, 3000f, 2.0f * voice, 0.9f),
            Band(BiquadFilter.Kind.PEAKING, 6500f, 1.2f * thx, 1.0f),
            Band(BiquadFilter.Kind.HIGHSHELF, 11500f, 1.2f * thx, 1.0f)
        )
        for (band in bands) {
            eqL[bands.indexOf(band)].configure(band.kind, sampleRate, band.f0, band.gain, band.q)
            if (stereo) eqR[bands.indexOf(band)].configure(band.kind, sampleRate, band.f0, band.gain, band.q)
        }
        bassLpL.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 65f, 0f, 0.707f)
        if (stereo) bassLpR.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 65f, 0f, 0.707f)
        eqReady = true
    }

    private fun readSample(buf: ByteBuffer): Float {
        return if (encoding == C.ENCODING_PCM_FLOAT) buf.float else buf.short.toFloat() / 32768f
    }

    private fun writeSample(out: ByteBuffer, v: Double) {
        val clamped = softLimit(v)
        if (encoding == C.ENCODING_PCM_FLOAT) {
            out.putFloat(clamped.toFloat())
        } else {
            out.putShort((clamped * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
    }

    private fun softLimit(v: Double): Double {
        val master = AudioEnhanceConfig.params().masterGain
        val x = v * master
        if (x > 0.9) {
            val d = x - 0.9
            return 0.9 + d / (1.0 + d)
        }
        if (x < -0.9) {
            val d = -x - 0.9
            return -(0.9 + d / (1.0 + d))
        }
        return x
    }

    override fun onFlush() {
        eqReady = false
        for (b in eqL) b.reset()
        for (b in eqR) b.reset()
        bassLpL.reset()
        bassLpR.reset()
        shelfL.reset()
        shelfR.reset()
        envL = 0.0
        envR = 0.0
    }

    override fun onReset() {
        onFlush()
    }
}
