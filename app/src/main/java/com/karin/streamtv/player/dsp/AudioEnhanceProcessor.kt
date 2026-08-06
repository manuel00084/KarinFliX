package com.karin.streamtv.player.dsp

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh
import kotlin.text.lowercase

class AudioEnhanceProcessor(context: Context) : BaseAudioProcessor() {
    companion object {
        private const val SHORT_SCALE = 1f / 32768f
        private val TYPE_SOUNDBAR: Int = try {
            AudioDeviceInfo::class.java.getField("TYPE_SOUNDBAR").getInt(null)
        } catch (t: Throwable) {
            -1
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var headphones = false
    private var multichannelCapable = false
    private var spatializerAvailable = false
    private var route = Route.STEREO

    override fun isActive(): Boolean =
        AudioEnhanceConfig.isEnabled() && AudioEnhanceConfig.preset() != AudioEnhanceConfig.Preset.OFF

    private var sampleRate = 48000
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var outChannels = 2
    private var stereo = false
    private var mono = false

    private var lastParams: AudioEnhanceConfig.Params? = null
    private var lastVolume = Float.NaN
    private var currentVolume = 0f

    // Cache del parámetro efectivo cuando autoDevice está activo:
    // solo se reconstruye si cambia el objeto de prefs o el tipo de salida.
    private var effRaw: AudioEnhanceConfig.Params? = null
    private var effDevice: AudioEnhanceConfig.DeviceKind? = null
    private var effOverride: AudioEnhanceConfig.Preset? = null
    private var effParams: AudioEnhanceConfig.Params? = null

    // Dispositivo que está sonando ahora. En modo Auto lo detecta el sistema;
    // en modo manual deriva del preset (SPEAKER = bocina chica).
    private var activeDevice: AudioEnhanceConfig.DeviceKind = AudioEnhanceConfig.DeviceKind.NEUTRAL

    // Detectado en detectOutput() (una vez por cambio de formato), reutilizado por
    // effectiveParams para no llamar a AudioManager.getDevices() en cada buffer.
    private var detectedDevice: AudioEnhanceConfig.DeviceKind = AudioEnhanceConfig.DeviceKind.PHONE_SPEAKER

    private fun deviceKindForPreset(p: AudioEnhanceConfig.Preset): AudioEnhanceConfig.DeviceKind = when (p) {
        AudioEnhanceConfig.Preset.SPEAKER -> AudioEnhanceConfig.DeviceKind.TV_SPEAKER
        else -> AudioEnhanceConfig.DeviceKind.NEUTRAL
    }

    // Trucos que solo tienen sentido en bocinas físicas chicas
    // (resonancia de caja, escenario 5.1 fantasma, etc.).
    private fun isSpeakerLike(device: AudioEnhanceConfig.DeviceKind): Boolean =
        device == AudioEnhanceConfig.DeviceKind.TV_SPEAKER ||
            device == AudioEnhanceConfig.DeviceKind.PHONE_SPEAKER

    // Conserva los ajustes finos del usuario (EQ, toggles, master) al cambiar
    // de preset base por la memoria del dispositivo.
    private fun withFineTunings(base: AudioEnhanceConfig.Params, raw: AudioEnhanceConfig.Params): AudioEnhanceConfig.Params =
        base.copy(
            autoDevice = raw.autoDevice,
            eq10 = raw.eq10,
            parametricEq = raw.parametricEq,
            tubeDrive = raw.tubeDrive,
            dynamicBass = raw.dynamicBass,
            loudnessComp = raw.loudnessComp,
            surfaceResonance = raw.surfaceResonance,
            speechClarity = raw.speechClarity,
            masterGain = raw.masterGain,
            useSystemSpatializer = raw.useSystemSpatializer,
            userIrName = raw.userIrName
        )

    private fun effectiveParams(raw: AudioEnhanceConfig.Params): AudioEnhanceConfig.Params {
        if (raw.autoDevice && raw.enabled && raw.preset != AudioEnhanceConfig.Preset.OFF) {
            val device = detectedDevice
            activeDevice = device
            val override = AudioEnhanceConfig.getDevicePreset(device)
            if (effRaw === raw && effDevice == device && effOverride == override && effParams != null) {
                return effParams!!
            }
            val base = if (override != null && override != raw.preset)
                withFineTunings(AudioEnhanceConfig.Params().withPreset(override), raw)
            else raw
            val eff = AudioEnhanceConfig.applyDeviceTuning(base, device)
            effRaw = raw
            effDevice = device
            effOverride = override
            effParams = eff
            return eff
        }
        activeDevice = deviceKindForPreset(raw.preset)
        return raw
    }

    private var eqL = Array(10) { BiquadFilter() }
    private var eqR = Array(10) { BiquadFilter() }
    private var vbL = VirtualBass()
    private var vbR = VirtualBass()
    private var exciteLpL = BiquadFilter()
    private var exciteLpR = BiquadFilter()
    private var exciteHpL = BiquadFilter()
    private var exciteHpR = BiquadFilter()
    private var vs = VirtualSpeaker()

    private var fieldLp = BiquadFilter()
    private var fieldDelayMax = 8
    private var fieldDelayL = DoubleArray(8)
    private var fieldDelayR = DoubleArray(8)
    private var fieldIdxL = 0
    private var fieldIdxR = 0

    private var reverbL: SimpleReverb? = null
    private var reverbR: SimpleReverb? = null

    private var convL = Convolver()
    private var convR = Convolver()
    private var lastIr = AudioEnhanceConfig.IrPreset.NONE
    private var lastIrMix = Float.NaN
    private var dryDelayL = DoubleArray(1)
    private var dryDelayR = DoubleArray(1)
    private var dryIdxL = 0
    private var dryIdxR = 0
    private var dryFill = 0

    // LCG para dither TPDF: 5-10x más rápido que kotlin.random.Random.nextDouble()
    private var ditherState = 0xC0FFEE17L
    private val ditherScale = 1.0 / 16384.0

    private inline fun nextDither(): Double {
        ditherState = ditherState * 0x5DEECE66DL + 0xBL
        return ((ditherState shr 17).toInt() and 0x7FFF) * ditherScale - 1.0
    }

    private var compLp = BiquadFilter()
    private var compHp = BiquadFilter()
    private var compLpR = BiquadFilter()
    private var compHpR = BiquadFilter()
    private val compEnv = DoubleArray(3)
    private val compSm = DoubleArray(3) { 1.0 }
    private var compAttack = 0.0
    private var compRelease = 0.0
    private var compSmooth = 0.0

    // Bass tightening: compresor dedicado a la banda de graves (< 150 Hz)
    // con ataque rápido y ratio alto para controlar el "boom" de bocinas baratas.
    private var bassTightLp = BiquadFilter()
    private var bassTightRp = BiquadFilter()
    private var bassTightEnv = 0.0
    private var bassTightGain = 1.0

    private var masterGain = 1.0

    // Tubo (saturación analógica): DC-block por canal para eliminar el offset
    // que genera la asimetría (armónico par) del wave-shaper.
    private var tubeDcL = BiquadFilter()
    private var tubeDcR = BiquadFilter()

    // EQ paramétrica (curvas AutoEQ importadas): peaking/shelf tras la EQ de 10 bandas.
    private var paramEqL = Array(0) { BiquadFilter() }
    private var paramEqR = Array(0) { BiquadFilter() }

    // Compensación de sonoridad (ISO 226 simplificado): shelf de graves ~120 Hz y
    // agudos ~6 kHz que se elevan cuando el volumen efectivo baja. Es LA ayuda
    // para bocinas humildes de TV/soundbar que se escuchan a volumen bajo.
    private var loudLpL = BiquadFilter()
    private var loudLpR = BiquadFilter()
    private var loudHpL = BiquadFilter()
    private var loudHpR = BiquadFilter()

    // Emulación de "superficie/caja": la TV pequeña apoyada en un rack se comporta
    // como un radiador pasivo. Shelf de boundary (~240 Hz) + resonador de cavidad
    // (~150 Hz) que "canta" con los transientes de graves. Solo en preset SPEAKER.
    private var surfResoL = SurfaceResonator()
    private var surfResoR = SurfaceResonator()

    // Claridad de voz: realce dinámico de la banda de presencia (1.1–4.5 kHz),
    // donde las bocinas chicas de TV se escuchan "opacas". Sube con la articulación
    // y se retira en silencios/explosiones; incluye de-esser (7 kHz) anti-sibilancia.
    private var scL = SpeechClarity()
    private var scR = SpeechClarity()

    // De-boxing: corte de resonancia de caja en 250-400 Hz para eliminar el sonido
    // "plástico/cajoso" de las bocinas baratas de TV montadas en chasis de plástico.
    private var deBoxL = BiquadFilter()
    private var deBoxR = BiquadFilter()

    // Limiter maestro con lookahead (~2 ms) y detección linkeada estéreo.
    // Reemplaza al softLimit: anticipa los picos gracias al buffer de retardo.
    private val limStereo = LookaheadLimiterPair()

    // Sintetizador subarmónico: genera graves una octava debajo de lo que
    // la bocina puede reproducir físicamente. Esencial para TVs con bocinas
    // baratas que carecen de extensión bajo 100 Hz.
    private var subSynth: SubharmonicSynth? = null
    private var mcCenter: MonoChain? = null
    private var mcRearL: MonoChain? = null
    private var mcRearR: MonoChain? = null
    private var mcBinaural: Array<MonoChain> = emptyArray()
    private var lfeLp = BiquadFilter()
    private var centerLp = BiquadFilter()
    private var rearDelayL = RingDelay()
    private var rearDelayR = RingDelay()
    private var virtual = VirtualSurround()

    private enum class Route { STEREO, MULTI, BINAURAL, UPMIX }

    private fun detectOutput() {
        headphones = false
        multichannelCapable = false
        spatializerAvailable = false
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (!d.isSink) continue
                    when (d.type) {
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_HEADSET -> headphones = true
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                            // Heurística por nombre: distinguir auriculares vs bocina BT clásico
                            val name = d.productName?.toString()?.lowercase() ?: ""
                            val isLikelySpeaker = name.contains("speaker") || name.contains("soundbar") || name.contains("tv ") || name.contains("tv-") || name.contains("tv_") || name.contains("box") || name.contains("home") || name.contains("receiver") || name.contains("amp")
                            val isLikelyHeadphone = name.contains("headphone") || name.contains("buds") || name.contains("headset") || name.contains("earphone") || name.contains("earbud") || name.contains("airpod") || name.contains("galaxy bud") || name.contains("pixel bud") || name.contains("wh-") || name.contains("wf-") || name.contains("qc35") || name.contains("qc45") || name.contains("momentum") || name.contains("pxc") || name.contains("hd ") || name.contains("dt ") || name.contains("ath-") || name.contains("kz ") || name.contains("blon")
                            if (isLikelySpeaker && !isLikelyHeadphone) multichannelCapable = true else headphones = true
                        }
                        AudioDeviceInfo.TYPE_HDMI,
                        AudioDeviceInfo.TYPE_HDMI_ARC,
                        AudioDeviceInfo.TYPE_AUX_LINE,
                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_ACCESSORY,
                        AudioDeviceInfo.TYPE_DOCK,
                        TYPE_SOUNDBAR -> multichannelCapable = true
                    }
                }
            } catch (t: Throwable) {
                Log.w("AudioEnhance", "detect output fallo: ${t.message}")
            }
        } else {
            headphones = am.isWiredHeadsetOn || am.isBluetoothA2dpOn
        }
        detectedDevice = when {
            headphones -> AudioEnhanceConfig.DeviceKind.HEADPHONES
            multichannelCapable -> AudioEnhanceConfig.DeviceKind.SOUNDBAR
            AudioEnhanceConfig.isTvDevice() -> AudioEnhanceConfig.DeviceKind.TV_SPEAKER
            else -> AudioEnhanceConfig.DeviceKind.PHONE_SPEAKER
        }
        // Consultar Spatializer del sistema (API 33+): si está activo y disponible,
        // el procesamiento espacial lo gestiona el HAL en vez de nuestro VirtualSurround.
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val sp = am.getSpatializer()
                spatializerAvailable = sp.isEnabled && sp.isAvailable
            } catch (t: Throwable) {
                Log.w("AudioEnhance", "Spatializer query fallo: ${t.message}")
            }
        }
        Log.i("AudioEnhance", "salida: auriculares=$headphones multi5.1=$multichannelCapable spatializer=$spatializerAvailable dispositivo=$detectedDevice")
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        stereo = channels >= 2
        mono = channels == 1
        detectOutput()
        route = when {
            channels >= 6 && headphones -> {
                if (spatializerAvailable && AudioEnhanceConfig.useSystemSpatializer())
                    Route.MULTI   // sacamos 6ch → Spatializer del sistema (HAL) hace el spatial
                else
                    Route.BINAURAL // sin Spatializer → nuestro VirtualSurround binaural
            }
            channels >= 6 -> Route.MULTI
            headphones -> Route.BINAURAL
            multichannelCapable -> Route.UPMIX
            else -> Route.STEREO
        }
        outChannels = when (route) {
            Route.UPMIX, Route.MULTI -> 6
            else -> 2
        }
        reverbL = SimpleReverb(sampleRate, 0)
        reverbR = SimpleReverb(sampleRate, 8)
        subSynth = SubharmonicSynth(sampleRate)
        compAttack = Math.exp(-1.0 / (0.030 * sampleRate))
        compRelease = Math.exp(-1.0 / (0.250 * sampleRate))
        compSmooth = Math.exp(-1.0 / (0.025 * sampleRate))
        vs.configure(sampleRate)
        fieldDelayMax = (sampleRate * 0.02).toInt().coerceAtLeast(8)
        fieldDelayL = DoubleArray(fieldDelayMax)
        fieldDelayR = DoubleArray(fieldDelayMax)
        fieldIdxL = 0
        fieldIdxR = 0
        limStereo.configure(sampleRate, 2f, 100f, 0.9)
        lastParams = null
        lastVolume = Float.NaN
        lastIr = AudioEnhanceConfig.IrPreset.NONE
        lastIrMix = Float.NaN
        convL.reset()
        convR.reset()
        val lat = convL.latencySamples()
        dryDelayL = DoubleArray(lat)
        dryDelayR = DoubleArray(lat)
        dryIdxL = 0
        dryIdxR = 0
        dryFill = 0
        mcCenter = if (route == Route.MULTI || route == Route.UPMIX) MonoChain() else null
        mcRearL = if (route == Route.MULTI || route == Route.UPMIX) MonoChain() else null
        mcRearR = if (route == Route.MULTI || route == Route.UPMIX) MonoChain() else null
        mcBinaural = if (route == Route.UPMIX || route == Route.BINAURAL) Array(5) { MonoChain() } else emptyArray()
        lfeLp = BiquadFilter()
        centerLp = BiquadFilter()
        rearDelayL = RingDelay()
        rearDelayR = RingDelay()
        virtual = VirtualSurround()
        virtual.configure(sampleRate)
        rearDelayL.configure((0.020 * sampleRate).toInt().coerceAtLeast(4))
        rearDelayR.configure((0.023 * sampleRate).toInt().coerceAtLeast(4))
        lfeLp.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 120f, 0f, 0.707f)
        centerLp.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 600f, 0f, 0.707f)
        Log.i("AudioEnhance", "config fs=$sampleRate ch=$channels out=$outChannels ruta=$route enc=$encoding")
        return AudioProcessor.AudioFormat(sampleRate, outChannels, encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            bypass(inputBuffer)
            return
        }
        if (!AudioEnhanceConfig.isEnabled() || AudioEnhanceConfig.preset() == AudioEnhanceConfig.Preset.OFF) {
            bypass(inputBuffer)
            return
        }
        val raw = AudioEnhanceConfig.params()
        val params = effectiveParams(raw)
        val volume = AudioEnhanceConfig.getPlaybackVolume()
        if (params != lastParams || volume != lastVolume) {
            try {
                ensureConfigured(params, volume)
            } catch (t: Throwable) {
                // Nunca dejar que una reconfiguración (alocaciones IR, etc.) tumbe el hilo de audio.
                Log.w("AudioEnhance", "reconfigure fallo: ${t.message}")
            }
            lastParams = params
            lastVolume = volume
            currentVolume = volume
            Log.i("AudioEnhance", "dsp activo preset=${params.preset} bass=${params.bassGain} treble=${params.trebleGain} subbass=${params.subBassGain} presence=${params.presenceGain} surround=${params.surroundWidth} field=${params.fieldSurround} exciter=${params.exciterAmount} harmbass=${params.harmonicBass} compression=${params.compression} reverb=${params.reverbMix} master=${params.masterGain} tube=${params.tubeDrive} dynbass=${params.dynamicBass} peq=${params.parametricEq?.size ?: 0} ir=${params.irType} userIr=${params.userIrName} eq10=${params.eq10 != null} ruta=$route vol=$volume")
        }
        masterGain = params.masterGain.toDouble()
        // Precompute per-buffer invariants (evita recalcular cada sample)
        limStereo.setThreshold((0.90 / params.masterGain).coerceIn(0.5, 0.97))
        if (subSynth != null && params.subharmonicMix > 0f) {
            val volFactor = (1.0f - currentVolume.coerceIn(0.05f, 1f)).coerceIn(0f, 1f)
            val adaptiveMix = params.subharmonicMix * (1.0f + volFactor * 0.3f)
            subSynth!!.setMix(adaptiveMix)
        }
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frames = inputBuffer.remaining() / (bytesPerSample * channels)
        val out = replaceOutputBuffer(frames * bytesPerSample * outChannels)
        try {
            when (route) {
                Route.STEREO -> processStereo(params, inputBuffer, out, frames, bytesPerSample)
                Route.MULTI -> processMulti(params, inputBuffer, out, frames, bytesPerSample)
                Route.UPMIX -> processUpmix(params, inputBuffer, out, frames, bytesPerSample)
                Route.BINAURAL -> processBinaural(params, inputBuffer, out, frames, bytesPerSample)
            }
        } catch (t: Throwable) {
            Log.w("AudioEnhance", "dsp error: ${t.message}")
        }
        out.flip()
    }

    private fun processStereo(params: AudioEnhanceConfig.Params, inputBuffer: ByteBuffer, out: ByteBuffer, frames: Int, bps: Int) {
        val res = doubleArrayOf(0.0, 0.0)
        val isMono = mono
        val chans = channels
        val vProc = vs
        val vsw = when {
            params.preset == AudioEnhanceConfig.Preset.DIALOGUE -> 0f
            isSpeakerLike(activeDevice) -> 0.25f
            else -> params.surroundWidth.coerceIn(0f, 1f)
        }
        if (vsw > 0f) {
            for (f in 0 until frames) {
                val l0 = readSample(inputBuffer)
                val r0 = if (isMono) l0 else readSample(inputBuffer)
                val pair = vProc.process(l0.toDouble(), r0.toDouble(), vsw)
                tonalStereo(params, pair.first, pair.second, res)
                writeSample(out, res[0])
                writeSample(out, res[1])
                for (c in 2 until chans) readSample(inputBuffer)
            }
        } else {
            for (f in 0 until frames) {
                val l0 = readSample(inputBuffer)
                val r0 = if (isMono) l0 else readSample(inputBuffer)
                val m = (l0 + r0) * 0.5
                val s = (l0 - r0) * 0.5
                tonalStereo(params, m + s, m - s, res)
                writeSample(out, res[0])
                writeSample(out, res[1])
                for (c in 2 until chans) readSample(inputBuffer)
            }
        }
    }

    private fun processMulti(params: AudioEnhanceConfig.Params, inputBuffer: ByteBuffer, out: ByteBuffer, frames: Int, bps: Int) {
        val res = doubleArrayOf(0.0, 0.0)
        val mc = mcCenter
        val mrl = mcRearL
        val mrr = mcRearR
        for (f in 0 until frames) {
            val l0 = readSample(inputBuffer)
            val r0 = readSample(inputBuffer)
            val c0 = readSample(inputBuffer)
            val lfe0 = readSample(inputBuffer)
            val bl0 = readSample(inputBuffer)
            val br0 = readSample(inputBuffer)
            tonalStereo(params, l0.toDouble(), r0.toDouble(), res)
            var c = mc!!.process(c0.toDouble(), params)
            var bl = mrl!!.process(bl0.toDouble(), params)
            var br = mrr!!.process(br0.toDouble(), params)
            var lfe = lfeLp.process(lfe0.toDouble())
            if (c * c > 4.0) c = c / (1.0 + (abs(c) - 2.0)) * 0.5
            if (lfe * lfe > 4.0) lfe = lfe / (1.0 + (abs(lfe) - 2.0)) * 0.5
            if (bl * bl > 4.0) bl = bl / (1.0 + (abs(bl) - 2.0)) * 0.5
            if (br * br > 4.0) br = br / (1.0 + (abs(br) - 2.0)) * 0.5
            writeSample(out, res[0])
            writeSample(out, res[1])
            writeSample(out, c)
            writeSample(out, lfe)
            writeSample(out, bl)
            writeSample(out, br)
            for (c in 6 until channels) readSample(inputBuffer)
        }
    }

    private fun processUpmix(params: AudioEnhanceConfig.Params, inputBuffer: ByteBuffer, out: ByteBuffer, frames: Int, bps: Int) {
        val res = doubleArrayOf(0.0, 0.0)
        val mc = mcCenter
        val mrl = mcRearL
        val mrr = mcRearR
        for (f in 0 until frames) {
            val l0 = readSample(inputBuffer)
            val r0 = if (mono) l0 else readSample(inputBuffer)
            val m = (l0 + r0) * 0.5
            val s = (l0 - r0) * 0.5
            val c = centerLp.process(m) * 1.0
            val lf = l0.toDouble() - c * 0.5
            val rf = r0.toDouble() - c * 0.5
            var lfe = lfeLp.process(m) * 0.5
            var ls = rearDelayL.process(s) * 0.9
            var rs = rearDelayR.process(-s) * 0.9
            tonalStereo(params, lf, rf, res)
            var cc = mc!!.process(c, params)
            var bl = mrl!!.process(ls, params)
            var br = mrr!!.process(rs, params)
            if (cc * cc > 4.0) cc = cc / (1.0 + (abs(cc) - 2.0)) * 0.5
            if (lfe * lfe > 4.0) lfe = lfe / (1.0 + (abs(lfe) - 2.0)) * 0.5
            if (bl * bl > 4.0) bl = bl / (1.0 + (abs(bl) - 2.0)) * 0.5
            if (br * br > 4.0) br = br / (1.0 + (abs(br) - 2.0)) * 0.5
            writeSample(out, res[0])
            writeSample(out, res[1])
            writeSample(out, cc)
            writeSample(out, lfe)
            writeSample(out, bl)
            writeSample(out, br)
            for (c in 2 until channels) readSample(inputBuffer)
        }
    }

    private fun processBinaural(params: AudioEnhanceConfig.Params, inputBuffer: ByteBuffer, out: ByteBuffer, frames: Int, bps: Int) {
        val feeds = DoubleArray(5)
        val chans = channels
        val isMono = mono
        val mcb = mcBinaural
        val clp = centerLp
        val llp = lfeLp
        val rdl = rearDelayL
        val rdr = rearDelayR
        val vir = virtual
        val lim = limStereo
        val mg = masterGain
        for (f in 0 until frames) {
            if (chans >= 6) {
                val l0 = readSample(inputBuffer)
                val r0 = readSample(inputBuffer)
                val c0 = readSample(inputBuffer)
                val lfe0 = readSample(inputBuffer)
                val bl0 = readSample(inputBuffer)
                val br0 = readSample(inputBuffer)
                feeds[0] = mcb[0].process(l0.toDouble(), params)
                feeds[1] = mcb[1].process(r0.toDouble(), params)
                feeds[2] = mcb[2].process(c0.toDouble() + lfe0.toDouble() * 0.3, params)
                feeds[3] = mcb[3].process(bl0.toDouble(), params)
                feeds[4] = mcb[4].process(br0.toDouble(), params)
                for (c in 6 until chans) readSample(inputBuffer)
            } else {
                val l0 = readSample(inputBuffer)
                val r0 = if (isMono) l0 else readSample(inputBuffer)
                val m = (l0 + r0) * 0.5
                val s = (l0 - r0) * 0.5
                val c = clp.process(m) * 1.0
                val lf = l0.toDouble() - c * 0.5
                val rf = r0.toDouble() - c * 0.5
                feeds[0] = mcb[0].process(lf, params)
                feeds[1] = mcb[1].process(rf, params)
                feeds[2] = mcb[2].process(c, params)
                feeds[3] = mcb[3].process(s, params)
                feeds[4] = mcb[4].process(-s, params)
                for (c in 2 until chans) readSample(inputBuffer)
            }
            val pair = vir.process(feeds)
            val pl = lim.process(pair.first * mg, pair.second * mg)
            writeSample(out, pl.first)
            writeSample(out, pl.second)
        }
    }

    private fun tonalStereo(params: AudioEnhanceConfig.Params, l: Double, r: Double, res: DoubleArray) {
        var lo = l
        var ro = r
        if (params.reverbMix > 0f) {
            val rm = params.reverbMix.toDouble() * 0.8
            reverbL?.let { lo += rm * it.process(lo) }
            reverbR?.let { ro += rm * it.process(ro) }
        }
        if (params.harmonicBass > 0f) {
            val hl = vbL.process(lo)
            lo += bassBoost(params.harmonicBass * vbL.gainFactor(), hl)
            val hr = vbR.process(ro)
            ro += bassBoost(params.harmonicBass * vbR.gainFactor(), hr)
        }
        if (subSynth != null && params.subharmonicMix > 0f) {
            lo = subSynth!!.process(lo)
            ro = subSynth!!.process(ro)
        }
        lo = excite(lo, exciteLpL, exciteHpL, params.exciterAmount)
        ro = excite(ro, exciteLpR, exciteHpR, params.exciterAmount)
        for (i in eqL.indices) {
            lo = eqL[i].process(lo)
            ro = eqR[i].process(ro)
        }
        if (params.deBoxing > 0f) {
            lo = deBoxL.process(lo)
            ro = deBoxR.process(ro)
        }
        if (params.tubeDrive > 0f) {
            lo = tubeDrive(lo, tubeDcL, params.tubeDrive)
            ro = tubeDrive(ro, tubeDcR, params.tubeDrive)
        }
        for (i in paramEqL.indices) {
            lo = paramEqL[i].process(lo)
            ro = paramEqR[i].process(ro)
        }
        val field = params.fieldSurround
        if (field > 0f) {
            val fk = field.toDouble()
            val bcl = fieldLp.process(lo)
            val bcr = fieldLp.process(ro)
            val center = (bcl + bcr) * 0.5
            lo += center - bcl
            ro += center - bcr
            val dn = ((0.004 + 0.011 * field) * sampleRate).toInt().coerceIn(1, fieldDelayMax - 1)
            val dl = fieldDelayL[(fieldIdxL + fieldDelayMax - dn) % fieldDelayMax]
            val dr = fieldDelayR[(fieldIdxR + fieldDelayMax - dn) % fieldDelayMax]
            fieldDelayL[fieldIdxL] = lo
            fieldDelayR[fieldIdxR] = ro
            fieldIdxL = (fieldIdxL + 1) % fieldDelayMax
            fieldIdxR = (fieldIdxR + 1) % fieldDelayMax
            lo += dr * 0.30 * fk
            ro += dl * 0.30 * fk
        }
        compressStereo(lo, ro, params.compression, res)
        // Bass tightening: compresor multibanda dedicado a graves (< 150 Hz).
        // Ataque rápido (2 ms) y ratio alto (6:1) controlan el boom de
        // bocinas baratas sin afectar el resto del espectro.
        if (isSpeakerLike(activeDevice)) {
            val bL = bassTightLp.process(res[0])
            val bR = bassTightRp.process(res[1])
            val aL = abs(bL)
            val aR = abs(bR)
            val a = max(aL, aR)
            // Detector con ataque/release lentos: el anterior (coef 0.1/muestra,
            // ~0.2 ms) seguía el ripple del rectificado a 2×f (110 Hz con bajo de
            // 55 Hz) y modulaba la ganancia → intermodulación/distorsión en graves.
            bassTightEnv = if (a > bassTightEnv) bassTightEnv * 0.9994 + 0.0006 * a else bassTightEnv * 0.9998 + 0.0002 * a
            val threshold = 0.08
            if (bassTightEnv > threshold) {
                val over = bassTightEnv / threshold
                val gr = 1.0 / (1.0 + (over - 1.0) * 5.0) // ratio ~6:1
                bassTightGain += (gr - bassTightGain) * 0.001
            } else {
                bassTightGain += (1.0 - bassTightGain) * 0.0005
            }
            // Crossfade SOLO de la banda de graves (< 150 Hz): la ganancia del
            // tightening escala únicamente el bajo, los medios/agudos pasan intactos.
            // Antes era res*gain + bL*(1-gain), que duplicaba TODA la señal cuando
            // llegaba una patada de graves (bombeo audible del volumen general).
            res[0] = res[0] + bL * (bassTightGain - 1.0)
            res[1] = res[1] + bR * (bassTightGain - 1.0)
        }
        if (params.loudnessComp) {
            res[0] = loudHpL.process(loudLpL.process(res[0]))
            res[1] = loudHpR.process(loudLpR.process(res[1]))
        }
        if (params.speechClarity) {
            res[0] = scL.process(res[0])
            res[1] = scR.process(res[1])
        }
        if (isSpeakerLike(activeDevice) && params.surfaceResonance) {
            res[0] = surfResoL.process(res[0])
            res[1] = surfResoR.process(res[1])
        }
        if (lastIr != AudioEnhanceConfig.IrPreset.NONE && params.irMix > 0f) {
            val irMix = params.irMix.toDouble()
            val lat = dryDelayL.size
            val dryL: Double
            val dryR: Double
            if (dryFill < lat) {
                // El convolver aún está llenando su pipeline (wet = 0): pase directo.
                dryL = res[0]
                dryR = res[1]
                dryFill++
            } else {
                dryL = dryDelayL[dryIdxL]
                dryR = dryDelayR[dryIdxR]
            }
            dryDelayL[dryIdxL] = res[0]
            dryDelayR[dryIdxR] = res[1]
            dryIdxL = (dryIdxL + 1) % lat
            dryIdxR = (dryIdxR + 1) % lat
            res[0] = dryL + irMix * convL.process(res[0])
            res[1] = dryR + irMix * convR.process(res[1])
        }
        val pl = limStereo.process(res[0] * masterGain, res[1] * masterGain)
        res[0] = pl.first
        res[1] = pl.second
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
        val ca = compAttack
        val cr = compRelease
        val cs = compSmooth
        for (i in 0 until 3) {
            val bl = if (i == 0) ll else if (i == 1) lm else lh
            val br = if (i == 0) rl else if (i == 1) rm else rh
            val a = max(abs(bl), abs(br))
            compEnv[i] = if (a > compEnv[i]) compEnv[i] * ca + (1 - ca) * a else compEnv[i] * cr + (1 - cr) * a
            val g = softKneeGain(compEnv[i], strength)
            compSm[i] = compSm[i] * cs + (1 - cs) * g
            ol += bl * compSm[i]
            or += br * compSm[i]
        }
        res[0] = ol
        res[1] = or
    }

    private fun bypass(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (outChannels == channels) {
            // Copia byte a byte: correcta para CUALQUIER encoding (16/24/32-bit, float),
            // sin reinterpretar muestras.
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }
        val bps = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frames = remaining / (bps * channels)
        val out = replaceOutputBuffer(frames * bps * outChannels)
        for (f in 0 until frames) {
            when {
                channels == 1 && outChannels == 2 -> {
                    val x = readSample(inputBuffer).toDouble()
                    writeSample(out, x)
                    writeSample(out, x)
                }
                channels == 1 && outChannels == 6 -> {
                    val x = readSample(inputBuffer).toDouble()
                    writeSample(out, x)
                    writeSample(out, x)
                    writeSample(out, x)
                    writeSample(out, 0.0)
                    writeSample(out, 0.0)
                    writeSample(out, 0.0)
                }
                channels == 2 && outChannels == 6 -> {
                    val l = readSample(inputBuffer).toDouble()
                    val r = readSample(inputBuffer).toDouble()
                    writeSample(out, l)
                    writeSample(out, r)
                    writeSample(out, (l + r) * 0.5)
                    writeSample(out, 0.0)
                    writeSample(out, 0.0)
                    writeSample(out, 0.0)
                }
                channels >= 6 && outChannels == 2 -> {
                    val l = readSample(inputBuffer).toDouble()
                    val r = readSample(inputBuffer).toDouble()
                    var lf = l
                    var rf = r
                    for (c in 2 until channels) {
                        val x = readSample(inputBuffer).toDouble()
                        if (c == 2) {
                            lf += x * 0.5
                            rf += x * 0.5
                        } else if (c == 4) {
                            lf += x * 0.5
                        } else if (c == 5) {
                            rf += x * 0.5
                        }
                    }
                    writeSample(out, lf)
                    writeSample(out, rf)
                }
                channels > 2 && outChannels == 6 -> {
                    for (c in 0 until 6) writeSample(out, readSample(inputBuffer).toDouble())
                    for (c in 6 until channels) readSample(inputBuffer)
                }
                else -> {
                    for (c in 0 until channels) writeSample(out, readSample(inputBuffer).toDouble())
                    for (c in channels until outChannels) writeSample(out, 0.0)
                }
            }
        }
        out.flip()
    }

    private fun ensureConfigured(params: AudioEnhanceConfig.Params, volume: Float) {
        val gains = params.eq10?.copyOf() ?: AudioEnhanceConfig.deriveEq10(params)
        // Compensación de sonoridad para bocinas chicas a volumen bajo: si el
        // usuario tiene activado loudnessComp, los shelves de abajo ya lo hacen;
        // solo aplicamos el boost por eq10 cuando loudnessComp está apagado,
        // para no sumar la misma compensación dos veces.
        if (isSpeakerLike(activeDevice) && !params.loudnessComp) {
            val loud = (1.0f - volume.coerceIn(0.05f, 1f)).coerceIn(0f, 1f)
            gains[2] += 6f * loud
            gains[3] += 4f * loud
            gains[8] += 4f * loud
            gains[9] += 2f * loud
        }
        // Compensación de sonoridad (todos los presets): a volumen bajo el oído
        // pierde graves y agudos (curvas de igual sonoridad). Subimos shelves de
        // ~120 Hz y ~6 kHz según cuán bajo sea el volumen efectivo.
        if (params.loudnessComp) {
            val loud = (1.0f - volume.coerceIn(0f, 1f)).coerceIn(0f, 1f)
            val bassDb = 3f * loud
            val trebleDb = 2f * loud
            loudLpL.configure(BiquadFilter.Kind.LOWSHELF, sampleRate, 120f, bassDb, 0.7f)
            loudLpR.configure(BiquadFilter.Kind.LOWSHELF, sampleRate, 120f, bassDb, 0.7f)
            loudHpL.configure(BiquadFilter.Kind.HIGHSHELF, sampleRate, 6000f, trebleDb, 0.7f)
            loudHpR.configure(BiquadFilter.Kind.HIGHSHELF, sampleRate, 6000f, trebleDb, 0.7f)
        } else {
            loudLpL.configure(BiquadFilter.Kind.LOWSHELF, sampleRate, 120f, 0f, 0.7f)
            loudLpR.configure(BiquadFilter.Kind.LOWSHELF, sampleRate, 120f, 0f, 0.7f)
            loudHpL.configure(BiquadFilter.Kind.HIGHSHELF, sampleRate, 6000f, 0f, 0.7f)
            loudHpR.configure(BiquadFilter.Kind.HIGHSHELF, sampleRate, 6000f, 0f, 0.7f)
        }
        val surf = if (isSpeakerLike(activeDevice)) 0.15f else 0f
        surfResoL.configure(sampleRate, surf)
        surfResoR.configure(sampleRate, surf)
        val scAmt = when (activeDevice) {
            AudioEnhanceConfig.DeviceKind.PHONE_SPEAKER -> 0.4f
            AudioEnhanceConfig.DeviceKind.TV_SPEAKER -> 0.4f
            AudioEnhanceConfig.DeviceKind.SOUNDBAR -> 0.35f
            else -> 0.3f
        }
        scL.configure(sampleRate, scAmt)
        scR.configure(sampleRate, scAmt)
        // De-boxing: corte de resonancia de caja en 300 Hz para eliminar el sonido
        // "plástico/cajoso" de las bocinas baratas de TV. La cantidad escala con
        // el preset deBoxing (0 = sin filtro, 1 = corte máximo de -6 dB).
        val deBoxGain = -6f * params.deBoxing
        deBoxL.configure(BiquadFilter.Kind.PEAKING, sampleRate, 300f, deBoxGain, 1.2f)
        deBoxR.configure(BiquadFilter.Kind.PEAKING, sampleRate, 300f, deBoxGain, 1.2f)
        val eqFreqs = AudioEnhanceConfig.EQ_FREQS
        val maxFreq = 0.45f * sampleRate
        for (i in 0 until 10) {
            val g = if (eqFreqs[i] >= maxFreq) 0f else gains[i]
            eqL[i].configure(BiquadFilter.Kind.PEAKING, sampleRate, eqFreqs[i], g, 0.8f)
            eqR[i].configure(BiquadFilter.Kind.PEAKING, sampleRate, eqFreqs[i], g, 0.8f)
        }
        val vbXover = when (activeDevice) {
            AudioEnhanceConfig.DeviceKind.TV_SPEAKER -> 120f
            AudioEnhanceConfig.DeviceKind.PHONE_SPEAKER -> 120f
            AudioEnhanceConfig.DeviceKind.HEADPHONES -> 100f
            AudioEnhanceConfig.DeviceKind.SOUNDBAR -> 120f
            else -> 120f
        }
        vbL.configure(sampleRate, vbXover, params.dynamicBass)
        vbR.configure(sampleRate, vbXover, params.dynamicBass)
        exciteLpL.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 250f, 0f, 0.707f)
        exciteLpR.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 250f, 0f, 0.707f)
        exciteHpL.configure(BiquadFilter.Kind.HIGHPASS, sampleRate, 4000f, 0f, 0.707f)
        exciteHpR.configure(BiquadFilter.Kind.HIGHPASS, sampleRate, 4000f, 0f, 0.707f)
        fieldLp.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 200f, 0f, 0.707f)
        tubeDcL.configure(BiquadFilter.Kind.HIGHPASS, sampleRate, 25f, 0f, 0.707f)
        tubeDcR.configure(BiquadFilter.Kind.HIGHPASS, sampleRate, 25f, 0f, 0.707f)
        compLp.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 220f, 0f, 0.707f)
        compHp.configure(BiquadFilter.Kind.HIGHPASS, sampleRate, 3200f, 0f, 0.707f)
        compLpR.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 220f, 0f, 0.707f)
        compHpR.configure(BiquadFilter.Kind.HIGHPASS, sampleRate, 3200f, 0f, 0.707f)
        // Bass tightening: LPF a 150 Hz para extraer la banda de graves y
        // comprimirla con ataque rápido (2 ms) y ratio alto (6:1) para
        // controlar el boom de bocinas baratas de TV.
        bassTightLp.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 150f, 0f, 0.707f)
        bassTightRp.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 150f, 0f, 0.707f)

        // EQ paramétrica (curvas AutoEQ importadas)
        val peq = params.parametricEq
        if (peq.isNullOrEmpty()) {
            paramEqL = Array(0) { BiquadFilter() }
            paramEqR = Array(0) { BiquadFilter() }
        } else {
            paramEqL = Array(peq.size) { BiquadFilter() }
            paramEqR = Array(peq.size) { BiquadFilter() }
            val maxF = 0.45f * sampleRate
            for (i in peq.indices) {
                val f = minOf(peq[i].freqHz, maxF)
                paramEqL[i].configure(peq[i].kind, sampleRate, f, peq[i].gainDb, peq[i].q)
                paramEqR[i].configure(peq[i].kind, sampleRate, f, peq[i].gainDb, peq[i].q)
            }
        }

        mcCenter?.configure(sampleRate, gains, 2f, params.reverbMix, params.compression, params.dynamicBass, params.parametricEq, volume, params.loudnessComp, scAmt, params.masterGain)
        mcRearL?.configure(sampleRate, gains, 0f, params.reverbMix * 1.4f, params.compression, params.dynamicBass, params.parametricEq, volume, params.loudnessComp, scAmt, params.masterGain)
        mcRearR?.configure(sampleRate, gains, 0f, params.reverbMix * 1.4f, params.compression, params.dynamicBass, params.parametricEq, volume, params.loudnessComp, scAmt, params.masterGain)
        for (i in 0 until mcBinaural.size) {
            mcBinaural[i].configure(sampleRate, gains, 0f, params.reverbMix * 0.5f, params.compression, params.dynamicBass, params.parametricEq, volume, params.loudnessComp, scAmt, params.masterGain)
        }

        val ir = params.irType
        if (ir != lastIr || params.irMix != lastIrMix) {
            lastIr = ir
            lastIrMix = params.irMix
            if (ir != AudioEnhanceConfig.IrPreset.NONE && params.irMix > 0f) {
                val pair = if (ir == AudioEnhanceConfig.IrPreset.USER) {
                    val user = AudioEnhanceConfig.userIr()
                    if (user != null && user.second.isNotEmpty()) {
                        val f = ImpulseResponses.resample(user.second, user.first, sampleRate)
                        f to f
                    } else {
                        FloatArray(0) to FloatArray(0)
                    }
                } else {
                    ImpulseResponses.pair(ir, sampleRate)
                }
                convL.setImpulseResponse(pair.first)
                convR.setImpulseResponse(pair.second)
                dryFill = 0
                Log.i("AudioEnhance", "IR cargado: $ir mix=${params.irMix} len=${pair.first.size}")
            } else {
                convL.setImpulseResponse(FloatArray(0))
                convR.setImpulseResponse(FloatArray(0))
            }
        }
    }

    private inline fun readSample(buf: ByteBuffer): Float {
        return if (encoding == C.ENCODING_PCM_FLOAT) buf.float else buf.short.toFloat() * SHORT_SCALE
    }

    private inline fun writeSample(out: ByteBuffer, v: Double) {
        if (encoding == C.ENCODING_PCM_FLOAT) {
            out.putFloat(v.toFloat())
        } else {
            val d1 = nextDither() * 0.5
            val d2 = nextDither() * 0.5
            val q = (v * 32768.0 + 0.5 + d1 + d2).toInt()
            out.putShort((if (q > 32767) 32767 else if (q < -32768) -32768 else q).toShort())
        }
    }

    override fun onFlush() {
        ditherState = 0xC0FFEE17L
        for (b in eqL) b.reset()
        for (b in eqR) b.reset()
        vbL.reset()
        vbR.reset()
        exciteLpL.reset()
        exciteLpR.reset()
        exciteHpL.reset()
        exciteHpR.reset()
        vs.reset()
        tubeDcL.reset()
        tubeDcR.reset()
        for (b in paramEqL) b.reset()
        for (b in paramEqR) b.reset()
        limStereo.reset()
        loudLpL.reset()
        loudLpR.reset()
        loudHpL.reset()
        loudHpR.reset()
        surfResoL.reset()
        surfResoR.reset()
        scL.reset()
        scR.reset()
        reverbL?.reset()
        reverbR?.reset()
        compLp.reset()
        compHp.reset()
        compLpR.reset()
        compHpR.reset()
        bassTightLp.reset()
        bassTightRp.reset()
        bassTightEnv = 0.0
        bassTightGain = 1.0
        deBoxL.reset()
        deBoxR.reset()
        compEnv.fill(0.0)
        compSm.fill(1.0)
        fieldLp.reset()
        fieldDelayL.fill(0.0)
        fieldDelayR.fill(0.0)
        fieldIdxL = 0
        fieldIdxR = 0
        convL.reset()
        convR.reset()
        dryDelayL.fill(0.0)
        dryDelayR.fill(0.0)
        dryIdxL = 0
        dryIdxR = 0
        dryFill = 0
        mcCenter?.reset()
        mcRearL?.reset()
        mcRearR?.reset()
        for (c in mcBinaural) c.reset()
        lfeLp.reset()
        centerLp.reset()
        rearDelayL.reset()
        rearDelayR.reset()
        virtual.reset()
        subSynth?.reset()
    }

    override fun onReset() {
        onFlush()
    }
}

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

internal class MonoChain {
    val eq = Array(10) { BiquadFilter() }
    val vb = VirtualBass()
    val exciteLp = BiquadFilter()
    val exciteHp = BiquadFilter()
    var reverb = SimpleReverb(48000)
    private val tubeDc = BiquadFilter()
    private var peq = Array(0) { BiquadFilter() }
    private val lim = LookaheadLimiter()
    private val loudLp = BiquadFilter()
    private val loudHp = BiquadFilter()
    private val compLp = BiquadFilter()
    private val compHp = BiquadFilter()
    private val sc = SpeechClarity()
    private val compEnv = DoubleArray(3)
    private val compSm = DoubleArray(3) { 1.0 }
    private var compAttack = 0.0
    private var compRelease = 0.0
    private var compSmooth = 0.0

    fun configure(
        fs: Int,
        gains: FloatArray,
        presenceOffset: Float,
        reverbMix: Float,
        compression: Float,
        dynamicBass: Boolean,
        parametric: List<AudioEnhanceConfig.ParamBand>?,
        volume: Float,
        loudnessComp: Boolean,
        speech: Float,
        masterGain: Float
    ) {
        reverb = SimpleReverb(fs)
        compAttack = Math.exp(-1.0 / (0.030 * fs))
        compRelease = Math.exp(-1.0 / (0.250 * fs))
        compSmooth = Math.exp(-1.0 / (0.025 * fs))
        val maxFreq = 0.45f * fs
        val freqs = AudioEnhanceConfig.EQ_FREQS
        for (i in 0 until 10) {
            val g = if (freqs[i] >= maxFreq) 0f else gains[i] + (if (i == 7) presenceOffset else 0f)
            eq[i].configure(BiquadFilter.Kind.PEAKING, fs, freqs[i], g, 0.8f)
        }
        vb.configure(fs, dynamic = dynamicBass)
        exciteLp.configure(BiquadFilter.Kind.LOWPASS, fs, 250f, 0f, 0.707f)
        exciteHp.configure(BiquadFilter.Kind.HIGHPASS, fs, 4000f, 0f, 0.707f)
        tubeDc.configure(BiquadFilter.Kind.HIGHPASS, fs, 25f, 0f, 0.707f)
        if (parametric.isNullOrEmpty()) {
            peq = Array(0) { BiquadFilter() }
        } else {
            peq = Array(parametric.size) { BiquadFilter() }
            val maxF = 0.45f * fs
            for (i in parametric.indices) {
                val f = minOf(parametric[i].freqHz, maxF)
                peq[i].configure(parametric[i].kind, fs, f, parametric[i].gainDb, parametric[i].q)
            }
        }
        lim.configure(fs, 2f, 100f, (0.95 / masterGain).coerceIn(0.5, 0.99))
        val loud = if (loudnessComp) (1.0f - volume.coerceIn(0f, 1f)).coerceIn(0f, 1f) else 0f
        loudLp.configure(BiquadFilter.Kind.LOWSHELF, fs, 120f, 3f * loud, 0.7f)
        loudHp.configure(BiquadFilter.Kind.HIGHSHELF, fs, 6000f, 2f * loud, 0.7f)
        compLp.configure(BiquadFilter.Kind.LOWPASS, fs, 220f, 0f, 0.707f)
        compHp.configure(BiquadFilter.Kind.HIGHPASS, fs, 3200f, 0f, 0.707f)
        sc.configure(fs, speech)
    }

    fun process(x: Double, params: AudioEnhanceConfig.Params): Double {
        var v = x
        if (params.harmonicBass > 0f) {
            val h = vb.process(v)
            v += bassBoost(params.harmonicBass * vb.gainFactor(), h)
        }
        v = excite(v, exciteLp, exciteHp, params.exciterAmount)
        for (b in eq) v = b.process(v)
        if (params.tubeDrive > 0f) v = tubeDrive(v, tubeDc, params.tubeDrive)
        for (b in peq) v = b.process(v)
        v = compress(v, params.compression)
        if (params.loudnessComp) v = loudHp.process(loudLp.process(v))
        if (params.speechClarity) v = sc.process(v)
        if (params.reverbMix > 0f) v += params.reverbMix.toDouble() * reverb.process(v) * 0.8
        lim.setThreshold((0.90 / params.masterGain).coerceIn(0.5, 0.97))
        return lim.process(v * params.masterGain.toDouble())
    }

    private fun compress(x: Double, strength: Float): Double {
        val ll = compLp.process(x)
        val lh = compHp.process(x)
        val lm = x - ll - lh
        var o = 0.0
        for (i in 0 until 3) {
            val b = if (i == 0) ll else if (i == 1) lm else lh
            val a = abs(b)
            compEnv[i] = if (a > compEnv[i]) compEnv[i] * compAttack + (1 - compAttack) * a else compEnv[i] * compRelease + (1 - compRelease) * a
            val g = softKnee(compEnv[i], strength)
            compSm[i] = compSm[i] * compSmooth + (1 - compSmooth) * g
            o += b * compSm[i]
        }
        return o
    }

    // Compresor de rodilla suave (curva 2R de Zölzer) por banda: la zona de
    // transición de ±knee/2 dB evita los "bandazos" audibles del hard-knee al
    // cruzar el umbral. makeup escala con strength (naturaleza de nivelación:
    // los pasajes bajos suben, los picos bajan). ratio/threshold parametrizan
    // el grado de intervención por preset.
    private fun softKnee(env: Double, strength: Float): Double =
        softKneeGain(env, strength)

    fun reset() {
        for (b in eq) b.reset()
        vb.reset()
        exciteLp.reset()
        exciteHp.reset()
        reverb.reset()
        tubeDc.reset()
        for (b in peq) b.reset()
        lim.reset()
        loudLp.reset()
        loudHp.reset()
        sc.reset()
        compLp.reset()
        compHp.reset()
        compEnv.fill(0.0)
        compSm.fill(1.0)
    }
}

// Curva de rodilla suave (compartida por compress y compressStereo).
internal fun softKneeGain(env: Double, strength: Float): Double {
    val threshold = 0.10           // ~ -20 dBFS
    val ratio = 1.0 + 7.0 * strength
    val knee = 12.0                // dB de transición
    val makeup = 1.0 + 0.15 * strength
    val xdb = 20.0 * log10((env / threshold).coerceAtLeast(1e-9))
    val r = 1.0 - 1.0 / ratio
    val grDb = when {
        xdb <= -knee / 2.0 -> 0.0
        xdb >= knee / 2.0 -> r * xdb
        else -> r * (xdb + knee / 2.0) * (xdb + knee / 2.0) / (2.0 * knee)
    }
    return makeup * 10.0.pow(-grDb / 20.0)
}

// Limiter con lookahead (~2 ms) a nivel de muestra. El buffer de retardo retrasa
// la salida N muestras; la envolvente mira "hacia delante" (los picos que aún no
// han salido) y reduce la ganancia antes de que lleguen → sin overshoot de corta
// duración ni inter-sample clipping. La ganancia se suaviza para no "bombear".
internal class LookaheadLimiter {
    private var buf = DoubleArray(1)
    private var idx = 0
    private var env = 0.0
    private var gain = 1.0
    private var threshold = 0.95
    private var release = 0.0
    private var smooth = 0.0

    fun configure(fs: Int, lookaheadMs: Float, releaseMs: Float, threshold: Double) {
        val n = (fs * lookaheadMs / 1000f).toInt().coerceAtLeast(1)
        buf = DoubleArray(n)
        idx = 0
        this.threshold = threshold
        release = Math.exp(-1.0 / (releaseMs * fs / 1000f))
        smooth = Math.exp(-1.0 / (0.3 * fs / 1000f))
        env = 0.0
        gain = 1.0
    }

    fun process(x: Double): Double {
        val delayed = buf[idx]
        buf[idx] = x
        idx = (idx + 1) % buf.size
        val a = abs(x)
        env = if (a > env) a else env * release
        val target = if (env > threshold) threshold / env else 1.0
        gain += (target - gain) * (1 - smooth)
        return delayed * gain
    }

    fun reset() {
        buf.fill(0.0)
        idx = 0
        env = 0.0
        gain = 1.0
    }

    fun setThreshold(threshold: Double) {
        this.threshold = threshold
    }
}

// Variante estéreo con detección linkeada (misma ganancia para L y R, tomando
// el pico de ambos) para no desplazar la imagen estéreo bajo limitación fuerte.
internal class LookaheadLimiterPair {
    private var bufL = DoubleArray(1)
    private var bufR = DoubleArray(1)
    private var idx = 0
    private var env = 0.0
    private var gain = 1.0
    private var threshold = 0.95
    private var release = 0.0
    private var smooth = 0.0

    fun configure(fs: Int, lookaheadMs: Float, releaseMs: Float, threshold: Double) {
        val n = (fs * lookaheadMs / 1000f).toInt().coerceAtLeast(1)
        bufL = DoubleArray(n)
        bufR = DoubleArray(n)
        idx = 0
        this.threshold = threshold
        release = Math.exp(-1.0 / (releaseMs * fs / 1000f))
        smooth = Math.exp(-1.0 / (0.3 * fs / 1000f))
        env = 0.0
        gain = 1.0
    }

    fun process(l: Double, r: Double): Pair<Double, Double> {
        val ol = bufL[idx]
        val or = bufR[idx]
        bufL[idx] = l
        bufR[idx] = r
        idx = (idx + 1) % bufL.size
        val a = max(abs(l), abs(r))
        env = if (a > env) a else env * release
        val target = if (env > threshold) threshold / env else 1.0
        gain += (target - gain) * (1 - smooth)
        return ol * gain to or * gain
    }

    fun reset() {
        bufL.fill(0.0)
        bufR.fill(0.0)
        idx = 0
        env = 0.0
        gain = 1.0
    }

    fun setThreshold(threshold: Double) {
        this.threshold = threshold
    }
}

internal class RingDelay {
    private var buf = DoubleArray(4)
    private var idx = 0
    fun configure(len: Int) {
        buf = DoubleArray(len.coerceAtLeast(1))
        idx = 0
    }
    fun process(x: Double): Double {
        val d = buf[idx]
        buf[idx] = x
        idx = (idx + 1) % buf.size
        return d
    }
    fun reset() {
        buf.fill(0.0)
        idx = 0
    }
}

internal class Allpass {
    var g = 0.0
    private var x1 = 0.0
    private var y1 = 0.0
    fun process(x: Double): Double {
        val y = -g * x + x1 + g * y1
        x1 = x
        y1 = y
        return y
    }
    fun reset() {
        x1 = 0.0
        y1 = 0.0
    }
}

// Virtualización de altavoces para TV/bocinas estéreo (estilo Dolby Virtual Speaker):
// convierte estéreo en un "5.1 fantasma" reproducido por 2 bocinas chicas.
//   - Los diálogos (mid) quedan anclados al centro de forma natural (L y R llevan el
//     mismo contenido central).
//   - El lateral (L-R) se bandlimita (sin graves que "vagarían"), se decorela con un
//     allpass y se virtualiza con dos taps de delay (9/12 ms) + reflexión invertida
//     (16 ms), empujando el campo sonoro hacia los lados y el fondo.
internal class VirtualSpeaker {
    private val ambHp = BiquadFilter()
    private val ambLp = BiquadFilter()
    private val ap = Allpass()
    private var buf = DoubleArray(2048)
    private var len = 2048
    private var idx = 0
    private var tapL = 0
    private var tapR = 0
    private var tapRef = 0
    private var kSm = 0.0

    fun configure(fs: Int) {
        len = ((fs * 0.022).toInt()).coerceAtLeast(64)
        if (buf.size != len) buf = DoubleArray(len)
        idx = 0
        ambHp.configure(BiquadFilter.Kind.HIGHPASS, fs, 140f, 0f, 0.707f)
        ambLp.configure(BiquadFilter.Kind.LOWPASS, fs, 4500f, 0f, 0.707f)
        ap.g = 0.55
        tapL = (fs * 0.009).toInt().coerceAtLeast(2)
        tapR = (fs * 0.012).toInt().coerceAtLeast(2)
        tapRef = (fs * 0.016).toInt().coerceAtLeast(2)
        kSm = 0.0
    }

    fun reset() {
        buf.fill(0.0)
        idx = 0
        ambHp.reset()
        ambLp.reset()
        ap.reset()
        kSm = 0.0
    }

    fun process(l: Double, r: Double, strength: Float): Pair<Double, Double> {
        val amb = ambLp.process(ambHp.process((l - r) * 0.5))
        val dec = ap.process(amb)
        buf[idx] = dec
        val i = idx
        idx = (idx + 1) % len
        val aL = buf[(i + len - tapL) % len]
        val aR = buf[(i + len - tapR) % len]
        val aRef = buf[(i + len - tapRef) % len]
        val ambL = aL - 0.3 * aRef
        val ambR = aR - 0.3 * aRef
        kSm += (0.75 * strength.toDouble() - kSm) * 0.0006
        return Pair(l + kSm * ambL, r + kSm * ambR)
    }
}

// Renderizador binaural de 5 altavoces virtuales (L, C, R, Ls, Rs) hacia 2 oídos.
internal class VirtualSurround {
    private var fs = 48000

    private class EarPath(
        var buf: DoubleArray,
        var idx: Int,
        val lp: BiquadFilter,
        val pinna: Array<BiquadFilter>,
        val ap: Allpass,
        var gain: Double
    ) {
        fun process(x: Double): Double {
            val d = buf[idx]
            buf[idx] = x
            idx = (idx + 1) % buf.size
            // Cascada de notches de pinna (perfil por altavoz): son las muescas
            // que más convencen al cerebro de que el sonido viene de fuera.
            var v = lp.process(ap.process(d))
            for (p in pinna) v = p.process(v)
            return v * gain
        }
        fun reset() {
            buf.fill(0.0)
            idx = 0
            lp.reset()
            for (p in pinna) p.reset()
            ap.reset()
        }
    }

    private var earsL = Array(5) { EarPath(DoubleArray(1), 0, BiquadFilter(), emptyArray(), Allpass(), 1.0) }
    private var earsR = Array(5) { EarPath(DoubleArray(1), 0, BiquadFilter(), emptyArray(), Allpass(), 1.0) }

    // Config por altavoz: [delayL, lpLHz, gainL, apL, delayR, lpRHz, gainR, apR] (delay en muestras a 48k)
    private val table = arrayOf(
        doubleArrayOf(0.0, 20000.0, 1.00, 0.00, 6.0, 7500.0, 0.90, 0.00),  // Frente L
        doubleArrayOf(6.0, 7500.0, 0.90, 0.00, 0.0, 20000.0, 1.00, 0.00),  // Frente R
        doubleArrayOf(0.0, 20000.0, 0.95, 0.00, 0.0, 20000.0, 0.95, 0.00), // Centro
        doubleArrayOf(2.0, 6000.0, 1.00, 0.30, 13.0, 3400.0, 0.80, 0.42),  // Trasero L
        doubleArrayOf(13.0, 3400.0, 0.80, 0.42, 2.0, 6000.0, 1.00, 0.30)   // Trasero R
    )

    // Notches de pinna por altavoz: [freq Hz, gain dB, Q]. Frente con notches
    // suaves, centro casi sin filtrado (sin ITD ni sombreado), traseros con
    // notches profundos (fuerte coloración de pabellón = exteriorización).
    private fun pinnaProfile(i: Int): Array<Triple<Float, Float, Float>> = when (i) {
        2 -> arrayOf(Triple(7200f, -3f, 1.5f))
        0, 1 -> arrayOf(Triple(6200f, -4f, 1.4f), Triple(8200f, -5f, 1.6f), Triple(9800f, -3f, 1.6f))
        else -> arrayOf(
            Triple(5200f, -7f, 1.3f),
            Triple(7400f, -6f, 1.5f),
            Triple(9000f, -5f, 1.5f),
            Triple(10500f, -4f, 1.5f)
        )
    }

    fun configure(fs: Int) {
        this.fs = fs
        val k = fs / 48000.0
        earsL = Array(5) { i -> makePath(i, table[i][0], table[i][1], table[i][2], table[i][3], k, fs) }
        earsR = Array(5) { i -> makePath(i, table[i][4], table[i][5], table[i][6], table[i][7], k, fs) }
    }

    private fun makePath(speaker: Int, delaySamples: Double, lpHz: Double, gain: Double, apGain: Double, k: Double, fs: Int): EarPath {
        val len = (delaySamples * k).toInt().coerceAtLeast(1)
        val pinna = pinnaProfile(speaker).map { (f, g, q) ->
            BiquadFilter().apply { configure(BiquadFilter.Kind.PEAKING, fs, f, g, q) }
        }.toTypedArray()
        return EarPath(
            DoubleArray(len),
            0,
            BiquadFilter(),
            pinna,
            Allpass().apply { g = apGain },
            gain
        )
    }

    fun process(feeds: DoubleArray): Pair<Double, Double> {
        var ol = 0.0
        var or = 0.0
        for (i in 0 until 5) {
            ol += earsL[i].process(feeds[i])
            or += earsR[i].process(feeds[i])
        }
        return ol to or
    }

    fun reset() {
        for (e in earsL) e.reset()
        for (e in earsR) e.reset()
    }
}

// Sintetizador subarmónico: detecta el fundamental de la banda de graves
// y genera una onda sinusoidal a la mitad de frecuencia (una octava abajo).
// Esto hace que bocinas pequeñas de TV "sientan" graves que físicamente
// no pueden reproducir, simulando un subwoofer real.
//
// Algoritmo: autocorrelación en buffer circular sobre señal filtrada a
// < 180 Hz → estima periodo fundamental → oscilador a f/2 con mezcla
// controlada. Actualización cada 256 muestras (~5.3 ms a 48 kHz).
internal class SubharmonicSynth(fs: Int) {
    private val fs = fs
    private val histSize = fs * 80 / 1000  // ~80 ms: 2x el periodo de 25 Hz (mínimo que se busca)
    private val buf = DoubleArray(histSize)
    private var bufIdx = 0
    private var bufCount = 0
    // Detección de pitch cada ~40 ms: el autocorrelador barre lag 367..1764, se acota para
    // no saturar CPU en bocinas/TV box baratas (antes corría cada 256 muestras).
    private val detectEvery = fs * 40 / 1000
    private var detectAcc = 0

    // LPF que aisla la banda de graves para la detección de pitch
    private val bassLp = BiquadFilter().apply {
        configure(BiquadFilter.Kind.LOWPASS, fs, 180f, 0f, 0.707f)
    }

    // Oscilador sinusoidal del subarmónico
    private var oscPhase = 0.0
    private var currentFreq = 0.0

    // Ganancia del sub mezclada (0..1), actualizada suavemente
    private var targetMix = 0.0
    private var smoothMix = 0.0

    fun setMix(m: Float) {
        targetMix = m.toDouble()
    }

    fun process(x: Double): Double {
        val bass = bassLp.process(x)
        buf[bufIdx] = bass
        bufIdx = (bufIdx + 1) % buf.size
        if (bufCount < buf.size) bufCount++

        // Actualizar pitch cada ~40 ms una vez el historial está lleno
        if (bufCount >= buf.size && ++detectAcc >= detectEvery) {
            detectAcc = 0
            currentFreq = detectFundamental()
        }

        // Gate: solo se genera sub cuando hay un fundamental detectado y señal de
        // graves real; si no (silencio, música sin sub-graves, pitch no fiable) el
        // sub se apaga en vez de emitir un tono constante (zumbido/distorsión).
        val hasFreq = currentFreq > 0.0
        val subFreq = if (hasFreq) (currentFreq / 2.0).coerceIn(15.0, 60.0) else 0.0
        if (subFreq > 0.0) {
            val phaseInc = 2.0 * PI * subFreq / fs
            oscPhase += phaseInc
            if (oscPhase >= 2.0 * PI) oscPhase -= 2.0 * PI
        }
        val sub = if (hasFreq) sin(oscPhase) else 0.0

        if (hasFreq) smoothMix += (targetMix - smoothMix) * 0.01
        else smoothMix *= 0.99

        return x + sub * smoothMix * 0.3
    }

    // Autocorrelación sobre el buffer de graves para estimar el periodo
    // fundamental. Retorna la frecuencia en Hz (0 si no se detecta).
    private fun detectFundamental(): Double {
        val n = buf.size
        val maxLag = n / 2
        // Corregido: rango práctico 40 Hz ~ 120 Hz (fundamental de bajo).
        // 40 Hz = ~1200 samples @ 48 kHz, 120 Hz = ~400 samples.
        // Reducción de ~1520 lags a ~800 (47% menos iteraciones).
        val minLag = (fs / 120.0).toInt().coerceAtLeast(1)
        val maxLagClamped = (fs / 40.0).toInt().coerceAtMost(maxLag)

        if (minLag >= maxLagClamped) return 0.0

        // Early exit: si el sample más reciente está cerca del silencio, no hay pitch
        val center = buf[(bufIdx - 1 + n) % n]
        if (center * center < 1e-10) return 0.0

        // Autocorrelación parcial (buscamos el primer pico después de minLag)
        var bestLag = minLag
        var bestVal = -1.0

        for (lag in minLag..maxLagClamped) {
            var sum = 0.0
            val start = (bufIdx - lag + n) % n
            for (i in 0 until minOf(lag, n - start)) {
                sum += buf[start + i] * buf[(start + i + lag) % n]
            }
            if (sum > bestVal) {
                bestVal = sum
                bestLag = lag
            }
        }

        // Normalizar respecto a la energía (0 = silencio, 1 = tono puro)
        val energy = buf.sumOf { it * it } / n
        val confidence = if (energy < 1e-12) 0.0 else bestVal / (energy * minLag)

        return if (confidence > 0.1) fs.toDouble() / bestLag else 0.0
    }

    fun reset() {
        buf.fill(0.0)
        bufIdx = 0
        bufCount = 0
        detectAcc = 0
        oscPhase = 0.0
        currentFreq = 0.0
        targetMix = 0.0
        smoothMix = 0.0
        bassLp.reset()
    }
}

// Reverb de convolución densa estilo Freeverb (8 combos con damping + 4 allpass):
// cola mucho más suave y natural que el Schroeder de 4 combos. Cada instancia
// lleva un "variation" para decorrelar L/R (anchura estéreo real del tail).
internal class SimpleReverb(fs: Int, variation: Int = 0) {
    private val scale = fs / 44100.0
    private val combTuning = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val apTuning = intArrayOf(556, 441, 341, 225)
    private val combBuf = Array(8) { i -> DoubleArray(scale(combTuning[i] + variation * 5)) }
    private val combIdx = IntArray(8)
    private val combDamp = Array(8) { DoubleArray(2) } // [lowpass store, filter store]
    private val apInBuf = Array(4) { i -> DoubleArray(scale(apTuning[i] + variation * 2)) }
    private val apOutBuf = Array(4) { i -> DoubleArray(scale(apTuning[i] + variation * 2)) }
    private val apIdx = IntArray(4)
    private val feedback = 0.84
    private val damp = 0.25
    private val apGain = 0.5

    private fun scale(samples: Int): Int = ((samples * scale).toInt()).coerceAtLeast(1)

    fun process(x: Double): Double {
        var out = 0.0
        for (i in 0 until 8) {
            val buf = combBuf[i]
            val st = combDamp[i]
            val idx = combIdx[i]
            val delayed = buf[idx]
            // Damping: lowpass de 1 polo en el camino de feedback (colas opacas).
            val filtered = delayed * (1.0 - damp) + st[0] * damp
            st[0] = filtered
            val v = x + filtered * feedback
            buf[idx] = if (abs(v) < 1e-25) 0.0 else v
            combIdx[i] = (idx + 1) % buf.size
            out += delayed
        }
        out *= 0.125
        for (i in 0 until 4) {
            val inBuf = apInBuf[i]
            val outBuf = apOutBuf[i]
            val idx = apIdx[i]
            val bufout = outBuf[idx]
            val y = -apGain * out + inBuf[idx] + apGain * bufout
            inBuf[idx] = out
            outBuf[idx] = y
            out = y
            apIdx[i] = (idx + 1) % inBuf.size
        }
        return out
    }

    fun reset() {
        for (b in combBuf) b.fill(0.0)
        for (d in combDamp) d.fill(0.0)
        combIdx.fill(0)
        for (b in apInBuf) b.fill(0.0)
        for (b in apOutBuf) b.fill(0.0)
        apIdx.fill(0)
    }
}
