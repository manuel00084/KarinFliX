package com.karin.streamtv.player.dsp

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

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
    private var exciteLpL = BiquadFilter()
    private var exciteLpR = BiquadFilter()
    private var envL = 0.0
    private var envR = 0.0

    private var reverbL: SimpleReverb? = null
    private var reverbR: SimpleReverb? = null

    private var agcEnv = 0.0
    private var agcSm = 1.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        stereo = channels >= 2
        eqReady = false
        reverbL = SimpleReverb(sampleRate)
        reverbR = SimpleReverb(sampleRate)
        Log.i("AudioEnhance", "config fs=$sampleRate ch=$channels enc=$encoding")
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val params = AudioEnhanceConfig.params()
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            bypass(inputBuffer)
            return
        }
        if (!params.enabled) {
            bypass(inputBuffer)
            return
        }
        if (!logged) {
            logged = true
            Log.i("AudioEnhance", "dsp activo preset=${params.preset} thx=${params.thx} bass=${params.bass} space=${params.space} voice=${params.voice} excite=${params.excite} harmbass=${params.harmbass} dynamic=${params.dynamic} ambience=${params.ambience} master=${params.masterGain}")
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
                    val m = (l0 + r0) * 0.5
                    val s = (l0 - r0) * 0.5
                    val w = 1.0 + params.space * 0.55
                    var l = m + s * w
                    var r = m - s * w

                    reverbL?.let { l += params.ambience * it.process(l) * 0.5 }
                    reverbR?.let { r += params.ambience * it.process(r) * 0.5 }

                    val bassL = bassLpL.process(l)
                    val bassR = bassLpR.process(r)
                    val aL = abs(bassL)
                    val aR = abs(bassR)
                    envL = if (aL > envL) envL + (aL - envL) * 0.35 else envL + (aL - envL) * 0.012
                    envR = if (aR > envR) envR + (aR - envR) * 0.35 else envR + (aR - envR) * 0.012
                    shelfL.configure(BiquadFilter.Kind.LOWSHELF, sampleRate, 65f, (params.bass * (3.0 + 7.0 * min(1.0, envL * 6.0))).toFloat(), 0.8f)
                    shelfR.configure(BiquadFilter.Kind.LOWSHELF, sampleRate, 65f, (params.bass * (3.0 + 7.0 * min(1.0, envR * 6.0))).toFloat(), 0.8f)
                    l = shelfL.process(l)
                    r = shelfR.process(r)
                    l += params.harmbass * harmonic(bassL)
                    r += params.harmbass * harmonic(bassR)

                    l = excite(l, exciteLpL, params.excite)
                    r = excite(r, exciteLpR, params.excite)

                    for (i in eqL.indices) {
                        l = eqL[i].process(l)
                        r = eqR[i].process(r)
                    }

                    val a = max(abs(l), abs(r))
                    val g = agcGain(a, params.dynamic)
                    l *= g
                    r *= g

                    writeSample(out, l)
                    writeSample(out, r)
                } else {
                    val x0 = readSample(inputBuffer)
                    var x = x0.toDouble()

                    val bass = bassLpL.process(x)
                    val aB = abs(bass)
                    envL = if (aB > envL) envL + (aB - envL) * 0.35 else envL + (aB - envL) * 0.012
                    shelfL.configure(BiquadFilter.Kind.LOWSHELF, sampleRate, 65f, (params.bass * (3.0 + 7.0 * min(1.0, envL * 6.0))).toFloat(), 0.8f)
                    x = shelfL.process(x)
                    x += params.harmbass * harmonic(bass)

                    x = excite(x, exciteLpL, params.excite)

                    for (i in eqL.indices) {
                        x = eqL[i].process(x)
                    }

                    x *= agcGain(abs(x), params.dynamic)

                    writeSample(out, x)
                }
            }
        } catch (t: Throwable) {
            Log.w("AudioEnhance", "dsp error: ${t.message}")
        }
        out.flip()
    }

    private fun bypass(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    private fun harmonic(b: Double): Double = 0.5 * (b * abs(b) + b * b * b)

    private fun excite(x: Double, lp: BiquadFilter, amt: Float): Double {
        val lpOut = lp.process(x)
        val high = x - lpOut
        val shaped = tanh(high * 4.0) * 0.4
        return x + amt * 0.7 * shaped
    }

    private fun agcGain(level: Double, dynamic: Float): Double {
        if (level > agcEnv) agcEnv += (level - agcEnv) * 0.2 else agcEnv += (level - agcEnv) * 0.006
        var g = 0.16 / (agcEnv + 1e-4)
        g = 1.0 + (g - 1.0) * dynamic
        g = g.coerceIn(0.7, 1.8)
        agcSm += (g - agcSm) * 0.05
        return agcSm
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
        exciteLpL.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 1400f, 0f, 0.707f)
        if (stereo) exciteLpR.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 1400f, 0f, 0.707f)
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
        exciteLpL.reset()
        exciteLpR.reset()
        reverbL?.reset()
        reverbR?.reset()
        envL = 0.0
        envR = 0.0
        agcEnv = 0.0
        agcSm = 1.0
    }

    override fun onReset() {
        onFlush()
    }
}

private class SimpleReverb(fs: Int) {
    private val combGains = doubleArrayOf(0.80, 0.78, 0.76, 0.74)
    private val combDelays = intArrayOf(
        (0.0297 * fs).toInt().coerceAtLeast(1),
        (0.0371 * fs).toInt().coerceAtLeast(1),
        (0.0411 * fs).toInt().coerceAtLeast(1),
        (0.0437 * fs).toInt().coerceAtLeast(1)
    )
    private val combBuf = Array(4) { i -> DoubleArray(combDelays[i]) }
    private val combIdx = IntArray(4)

    private val allpassDelays = intArrayOf(
        (0.0050 * fs).toInt().coerceAtLeast(1),
        (0.0017 * fs).toInt().coerceAtLeast(1)
    )
    private val apGain = 0.5
    private val apInBuf = Array(2) { i -> DoubleArray(allpassDelays[i]) }
    private val apOutBuf = Array(2) { i -> DoubleArray(allpassDelays[i]) }
    private val apIdx = IntArray(2)

    fun process(x: Double): Double {
        var out = 0.0
        for (i in combBuf.indices) {
            val buf = combBuf[i]
            val idx = combIdx[i]
            val delayed = buf[idx]
            buf[idx] = (x + delayed * combGains[i]).toFloat().toDouble()
            combIdx[i] = (idx + 1) % buf.size
            out += delayed
        }
        out *= 0.25
        for (i in apInBuf.indices) {
            val inBuf = apInBuf[i]
            val outBuf = apOutBuf[i]
            val idx = apIdx[i]
            val xd = inBuf[idx]
            val yd = outBuf[idx]
            val y = -apGain * out + xd + apGain * yd
            inBuf[idx] = out
            outBuf[idx] = y
            out = y
            apIdx[i] = (idx + 1) % inBuf.size
        }
        return out
    }

    fun reset() {
        for (b in combBuf) b.fill(0.0)
        combIdx.fill(0)
        for (b in apInBuf) b.fill(0.0)
        for (b in apOutBuf) b.fill(0.0)
        apIdx.fill(0)
    }
}
