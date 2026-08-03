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
    private var logged = false

    private var eqL = Array(6) { BiquadFilter() }
    private var eqR = Array(6) { BiquadFilter() }
    private var bassLpL = BiquadFilter()
    private var bassLpR = BiquadFilter()
    private var exciteLpL = BiquadFilter()
    private var exciteLpR = BiquadFilter()
    private var bassEnvL = 0.0
    private var bassEnvR = 0.0

    private var reverbL: SimpleReverb? = null
    private var reverbR: SimpleReverb? = null

    private var compLp = BiquadFilter()
    private var compHp = BiquadFilter()
    private var compLpR = BiquadFilter()
    private var compHpR = BiquadFilter()
    private val compEnv = DoubleArray(3)
    private val compSm = DoubleArray(3) { 1.0 }
    private var compAttack = 0.0
    private var compRelease = 0.0
    private var compSmooth = 0.0

    private var xfLen = 2
    private var xfBufL = DoubleArray(2)
    private var xfBufR = DoubleArray(2)
    private var xfIdxL = 0
    private var xfIdxR = 0
    private var masterGain = 1.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        stereo = channels >= 2
        reverbL = SimpleReverb(sampleRate)
        reverbR = SimpleReverb(sampleRate)
        compAttack = Math.exp(-1.0 / (0.010 * sampleRate))
        compRelease = Math.exp(-1.0 / (0.150 * sampleRate))
        compSmooth = Math.exp(-1.0 / (0.025 * sampleRate))
        xfLen = (sampleRate * 0.0004).toInt().coerceAtLeast(2)
        xfBufL = DoubleArray(xfLen)
        xfBufR = DoubleArray(xfLen)
        xfIdxL = 0
        xfIdxR = 0
        Log.i("AudioEnhance", "config fs=$sampleRate ch=$channels enc=$encoding")
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val params = AudioEnhanceConfig.params()
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            bypass(inputBuffer)
            return
        }
        if (!params.enabled || params.preset == AudioEnhanceConfig.Preset.OFF) {
            bypass(inputBuffer)
            return
        }
        if (!logged) {
            logged = true
            Log.i("AudioEnhance", "dsp activo preset=${params.preset} bass=${params.bassGain} treble=${params.trebleGain} subbass=${params.subBassGain} presence=${params.presenceGain} surround=${params.surroundWidth} exciter=${params.exciterAmount} harmbass=${params.harmonicBass} compression=${params.compression} reverb=${params.reverbMix} master=${params.masterGain}")
        }
        ensureConfigured(params)
        masterGain = params.masterGain.toDouble()
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val bytesPerFrame = bytesPerSample * channels
        val frames = inputBuffer.remaining() / bytesPerFrame
        val out = replaceOutputBuffer(frames * bytesPerFrame)
        try {
            val res = doubleArrayOf(0.0, 0.0)
            for (f in 0 until frames) {
                if (stereo) {
                    val l0 = readSample(inputBuffer)
                    val r0 = readSample(inputBuffer)
                    val m = (l0 + r0) * 0.5
                    val s = (l0 - r0) * 0.5
                    val w = 1.0 + params.surroundWidth * 0.45
                    var l = m + s * w
                    var r = m - s * w
                    if (params.surroundWidth > 0.0f) {
                        val k = 0.30 * min(1.0, params.surroundWidth.toDouble())
                        val dl = xfBufL[xfIdxL]
                        val dr = xfBufR[xfIdxR]
                        xfBufL[xfIdxL] = l
                        xfBufR[xfIdxR] = r
                        xfIdxL = (xfIdxL + 1) % xfLen
                        xfIdxR = (xfIdxR + 1) % xfLen
                        l = l - k * dr
                        r = r - k * dl
                    }
                    reverbL?.let { l += params.reverbMix.toDouble() * it.process(l) * 0.8 }
                    reverbR?.let { r += params.reverbMix.toDouble() * it.process(r) * 0.8 }

                    val bassL = bassLpL.process(l)
                    val bassR = bassLpR.process(r)
                    val aL = abs(bassL)
                    val aR = abs(bassR)
                    bassEnvL = if (aL > bassEnvL) bassEnvL + (aL - bassEnvL) * 0.35 else bassEnvL + (aL - bassEnvL) * 0.012
                    bassEnvR = if (aR > bassEnvR) bassEnvR + (aR - bassEnvR) * 0.35 else bassEnvR + (aR - bassEnvR) * 0.012
                    l += bassBoost(params.harmonicBass, bassL)
                    r += bassBoost(params.harmonicBass, bassR)

                    l = excite(l, exciteLpL, params.exciterAmount)
                    r = excite(r, exciteLpR, params.exciterAmount)

                    for (i in eqL.indices) {
                        l = eqL[i].process(l)
                        r = eqR[i].process(r)
                    }

                    compressStereo(l, r, params.compression, res)
                    l = res[0]
                    r = res[1]

                    writeSample(out, l)
                    writeSample(out, r)
                } else {
                    val x0 = readSample(inputBuffer)
                    var x = x0.toDouble()

                    val bass = bassLpL.process(x)
                    val aB = abs(bass)
                    bassEnvL = if (aB > bassEnvL) bassEnvL + (aB - bassEnvL) * 0.35 else bassEnvL + (aB - bassEnvL) * 0.012
                    x += bassBoost(params.harmonicBass, bass)

                    x = excite(x, exciteLpL, params.exciterAmount)

                    for (i in eqL.indices) {
                        x = eqL[i].process(x)
                    }

                    x = compressMono(x, params.compression)

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

    private fun harmonic(b: Double): Double = 0.45 * tanh(b * abs(b))

    private fun bassBoost(amt: Float, bass: Double): Double {
        val boost = amt * harmonic(bass)
        val a = abs(boost)
        return if (a > 0.35) {
            val s = if (boost > 0) 1.0 else -1.0
            s * (0.35 + (a - 0.35) / (1.0 + (a - 0.35)))
        } else boost
    }

    private fun excite(x: Double, lp: BiquadFilter, amt: Float): Double {
        if (amt <= 0.0f) return x
        val lpOut = lp.process(x)
        val high = x - lpOut
        val shaped = tanh(high * 4.0) * 0.4
        return x + amt * 0.7 * shaped
    }

    private fun compressStereo(l: Double, r: Double, strength: Float, res: DoubleArray) {
        val ll = compLp.process(l)
        val lh = compHp.process(l)
        val lm = l - ll - lh
        val rl = compLpR.process(r)
        val rh = compHpR.process(r)
        val rm = r - rl - rh
        var ol = 0.0
        var or = 0.0
        for (i in 0 until 3) {
            val bl = if (i == 0) ll else if (i == 1) lm else lh
            val br = if (i == 0) rl else if (i == 1) rm else rh
            val a = max(abs(bl), abs(br))
            compEnv[i] = if (a > compEnv[i]) compEnv[i] * compAttack + (1 - compAttack) * a else compEnv[i] * compRelease + (1 - compRelease) * a
            var g = 0.09 / (compEnv[i] + 1e-4)
            g = 1.0 + (g - 1.0) * strength
            g = g.coerceIn(0.5, 2.5)
            compSm[i] = compSm[i] * compSmooth + (1 - compSmooth) * g
            ol += bl * compSm[i]
            or += br * compSm[i]
        }
        res[0] = ol
        res[1] = or
    }

    private fun compressMono(x: Double, strength: Float): Double {
        val ll = compLp.process(x)
        val lh = compHp.process(x)
        val lm = x - ll - lh
        var o = 0.0
        for (i in 0 until 3) {
            val b = if (i == 0) ll else if (i == 1) lm else lh
            val a = abs(b)
            compEnv[i] = if (a > compEnv[i]) compEnv[i] * compAttack + (1 - compAttack) * a else compEnv[i] * compRelease + (1 - compRelease) * a
            var g = 0.09 / (compEnv[i] + 1e-4)
            g = 1.0 + (g - 1.0) * strength
            g = g.coerceIn(0.5, 2.5)
            compSm[i] = compSm[i] * compSmooth + (1 - compSmooth) * g
            o += b * compSm[i]
        }
        return o
    }

    private data class Band(val kind: BiquadFilter.Kind, val f0: Float, val gain: Float, val q: Float)

    private fun ensureConfigured(params: AudioEnhanceConfig.Params) {
        val bass = params.bassGain
        val treble = params.trebleGain
        val subBass = params.subBassGain
        val presence = params.presenceGain
        val bands = arrayOf(
            Band(BiquadFilter.Kind.PEAKING, 45f, subBass, 1.2f),
            Band(BiquadFilter.Kind.LOWSHELF, 130f, bass, 0.8f),
            Band(BiquadFilter.Kind.PEAKING, 700f, 0f, 0.9f),
            Band(BiquadFilter.Kind.PEAKING, 3000f, presence, 0.9f),
            Band(BiquadFilter.Kind.PEAKING, 6500f, 0.5f * treble, 1.0f),
            Band(BiquadFilter.Kind.HIGHSHELF, 9500f, treble, 0.9f)
        )
        for (i in bands.indices) {
            val band = bands[i]
            eqL[i].configure(band.kind, sampleRate, band.f0, band.gain, band.q)
            if (stereo) eqR[i].configure(band.kind, sampleRate, band.f0, band.gain, band.q)
        }
        bassLpL.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 70f, 0f, 0.707f)
        if (stereo) bassLpR.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 70f, 0f, 0.707f)
        exciteLpL.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 1400f, 0f, 0.707f)
        if (stereo) exciteLpR.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 1400f, 0f, 0.707f)
        compLp.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 220f, 0f, 0.707f)
        compHp.configure(BiquadFilter.Kind.HIGHPASS, sampleRate, 3200f, 0f, 0.707f)
        compLpR.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 220f, 0f, 0.707f)
        compHpR.configure(BiquadFilter.Kind.HIGHPASS, sampleRate, 3200f, 0f, 0.707f)
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
        val x = v * masterGain
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
        for (b in eqL) b.reset()
        for (b in eqR) b.reset()
        bassLpL.reset()
        bassLpR.reset()
        exciteLpL.reset()
        exciteLpR.reset()
        reverbL?.reset()
        reverbR?.reset()
        compLp.reset()
        compHp.reset()
        compLpR.reset()
        compHpR.reset()
        compEnv.fill(0.0)
        compSm.fill(1.0)
        xfBufL.fill(0.0)
        xfBufR.fill(0.0)
        xfIdxL = 0
        xfIdxR = 0
        bassEnvL = 0.0
        bassEnvR = 0.0
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
