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
import kotlin.math.max
import kotlin.math.tanh
import kotlin.text.lowercase

class AudioEnhanceProcessor(context: Context) : BaseAudioProcessor() {
    companion object {
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

    private var sampleRate = 48000
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var outChannels = 2
    private var mono = false

    private var lastParams: AudioEnhanceConfig.Params? = null
    private var lastVolume = Float.NaN

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
    private var eqBands = 10

    /** Bandas de EQ realmente renderizadas ahora (5 en bocinas/TV, 10 por defecto). */
    val activeEqBands: Int get() = eqBands
    private var vbL = VirtualBass()
    private var vbR = VirtualBass()
    private var exciteLpL = BiquadFilter()
    private var exciteLpR = BiquadFilter()
    private var vs = VirtualSpeaker()

    private var fieldLpL = BiquadFilter()
    private var fieldLpR = BiquadFilter()
    private var fieldDelayMax = 8
    private var fieldDelayL = DoubleArray(8)
    private var fieldDelayR = DoubleArray(8)
    private var fieldIdxL = 0
    private var fieldIdxR = 0

    private var reverbL: SimpleReverb? = null
    private var reverbR: SimpleReverb? = null

    private var convL = NativeConvolver()
    private var convR = NativeConvolver()
    private var lastIr = AudioEnhanceConfig.IrPreset.NONE
    private var lastIrMix = Float.NaN
    private var dryDelayL = DoubleArray(1)
    private var dryDelayR = DoubleArray(1)
    private var dryIdxL = 0
    private var dryIdxR = 0
    private var dryFill = 0

    // LCG para dither TPDF: 5-10x más rápido que kotlin.random.Random.nextDouble()
    private var ditherState = 0xC0FFEE17L

    private fun nextDither(): Double {
        ditherState = ditherState * 0x5DEECE66DL + 0xBL
        return ((ditherState shr 17).toInt() and 0x7FFF).toDouble() / 16384.0 - 1.0
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

    // Investigación "alta calidad": graves anclados al centro + separación
    // percuativo/armónico + ataques + de-enmascarado + despeje de explosiones.
    private var subAnchor = SubAnchorCenter()
    private var beatL = BeatBoostMono()
    private var beatR = BeatBoostMono()
    private var punchL = TransientPunch()
    private var punchR = TransientPunch()
    private var clarityL = SpectralClarity()
    private var clarityR = SpectralClarity()
    private var duckL = ExplosionDucker()
    private var duckR = ExplosionDucker()

    // Limiter maestro con lookahead (~2 ms) y detección linkeada estéreo.
    // Reemplaza al softLimit: anticipa los picos gracias al buffer de retardo.
    private val limStereo = LookaheadLimiterPair()

    // Rutas multicanal / virtual
    private var mcCenter = MonoChain()
    private var mcRearL = MonoChain()
    private var mcRearR = MonoChain()
    private var mcBinaural = Array(5) { MonoChain() }
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
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_HEARING_AID -> headphones = true
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
                        AudioDeviceInfo.TYPE_HDMI_EARC,
                        AudioDeviceInfo.TYPE_AUX_LINE,
                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_ACCESSORY,
                        AudioDeviceInfo.TYPE_DOCK,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER,
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
        compAttack = Math.exp(-1.0 / (0.010 * sampleRate))
        compRelease = Math.exp(-1.0 / (0.150 * sampleRate))
        compSmooth = Math.exp(-1.0 / (0.025 * sampleRate))
        vs.configure(sampleRate)
        fieldDelayMax = (sampleRate * 0.02).toInt().coerceAtLeast(8)
        fieldDelayL = DoubleArray(fieldDelayMax)
        fieldDelayR = DoubleArray(fieldDelayMax)
        fieldIdxL = 0
        fieldIdxR = 0
        limStereo.configure(sampleRate, 2f, 100f, 0.95)
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
        mcCenter = MonoChain()
        mcRearL = MonoChain()
        mcRearR = MonoChain()
        mcBinaural = Array(5) { MonoChain() }
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
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_24BIT &&
            encoding != C.ENCODING_PCM_32BIT && encoding != C.ENCODING_PCM_FLOAT
        ) {
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
            Log.i("AudioEnhance", "dsp activo preset=${params.preset} bass=${params.bassGain} treble=${params.trebleGain} subbass=${params.subBassGain} presence=${params.presenceGain} surround=${params.surroundWidth} field=${params.fieldSurround} exciter=${params.exciterAmount} harmbass=${params.harmonicBass} compression=${params.compression} reverb=${params.reverbMix} master=${params.masterGain} tube=${params.tubeDrive} dynbass=${params.dynamicBass} peq=${params.parametricEq?.size ?: 0} ir=${params.irType} eq10=${params.eq10 != null} ruta=$route vol=$volume")
        }
        masterGain = params.masterGain.toDouble()
        val bytesPerSample = bytesPerSample()
        val frames = inputBuffer.remaining() / (bytesPerSample * channels)
        val out = replaceOutputBuffer(frames * bytesPerSample * outChannels)
        try {
            when (route) {
                Route.STEREO -> processStereo(params, inputBuffer, out, frames)
                Route.MULTI -> processMulti(params, inputBuffer, out, frames)
                Route.UPMIX -> processUpmix(params, inputBuffer, out, frames)
                Route.BINAURAL -> processBinaural(params, inputBuffer, out, frames)
            }
        } catch (t: Throwable) {
            Log.w("AudioEnhance", "dsp error: ${t.message}")
        }
        out.flip()
    }

    private fun processStereo(params: AudioEnhanceConfig.Params, inputBuffer: ByteBuffer, out: ByteBuffer, frames: Int) {
        val res = doubleArrayOf(0.0, 0.0)
        // Los presets de bocina dejan surroundWidth en 0 (mono-ish); el
        // VirtualSpeaker debe activarse igual para abrir el escenario 5.1 fantasma.
        // Excepción: Diálogos pide foco central mono, no se ensancha.
        val vsw = when {
            params.preset == AudioEnhanceConfig.Preset.DIALOGUE -> 0f
            isSpeakerLike(activeDevice) -> max(params.surroundWidth.coerceIn(0f, 1f), 0.6f)
            else -> params.surroundWidth.coerceIn(0f, 1f)
        }
        for (f in 0 until frames) {
            val l0 = readSample(inputBuffer)
            val r0 = if (mono) l0 else readSample(inputBuffer)
            var l: Double
            var r: Double
            if (vsw > 0f) {
                // Virtualización de altavoces: los diálogos quedan anclados al centro
                // y el lateral se virtualiza hacia los lados/fondo (engaño 5.1).
                val pair = vs.process(l0.toDouble(), r0.toDouble(), vsw)
                l = pair.first
                r = pair.second
            } else {
                l = l0.toDouble()
                r = r0.toDouble()
            }
            tonalStereo(params, l, r, res)
            writeSample(out, res[0])
            writeSample(out, res[1])
            for (c in 2 until channels) readSample(inputBuffer)
        }
    }

    private fun processMulti(params: AudioEnhanceConfig.Params, inputBuffer: ByteBuffer, out: ByteBuffer, frames: Int) {
        val res = doubleArrayOf(0.0, 0.0)
        for (f in 0 until frames) {
            val l0 = readSample(inputBuffer)
            val r0 = readSample(inputBuffer)
            val c0 = readSample(inputBuffer)
            val lfe0 = readSample(inputBuffer)
            val bl0 = readSample(inputBuffer)
            val br0 = readSample(inputBuffer)
            tonalStereo(params, l0.toDouble(), r0.toDouble(), res)
            var c = mcCenter.process(c0.toDouble(), params)
            var bl = mcRearL.process(bl0.toDouble(), params)
            var br = mcRearR.process(br0.toDouble(), params)
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

    private fun processUpmix(params: AudioEnhanceConfig.Params, inputBuffer: ByteBuffer, out: ByteBuffer, frames: Int) {
        val res = doubleArrayOf(0.0, 0.0)
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
            var cc = mcCenter.process(c, params)
            var bl = mcRearL.process(ls, params)
            var br = mcRearR.process(rs, params)
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

    private fun processBinaural(params: AudioEnhanceConfig.Params, inputBuffer: ByteBuffer, out: ByteBuffer, frames: Int) {
        val feeds = DoubleArray(5)
        for (f in 0 until frames) {
            if (channels >= 6) {
                val l0 = readSample(inputBuffer)
                val r0 = readSample(inputBuffer)
                val c0 = readSample(inputBuffer)
                val lfe0 = readSample(inputBuffer)
                val bl0 = readSample(inputBuffer)
                val br0 = readSample(inputBuffer)
                feeds[0] = mcBinaural[0].process(l0.toDouble(), params)
                feeds[1] = mcBinaural[1].process(r0.toDouble(), params)
                feeds[2] = mcBinaural[2].process(c0.toDouble() + lfe0.toDouble() * 0.3, params)
                feeds[3] = mcBinaural[3].process(bl0.toDouble(), params)
                feeds[4] = mcBinaural[4].process(br0.toDouble(), params)
                for (c in 6 until channels) readSample(inputBuffer)
            } else {
                val l0 = readSample(inputBuffer)
                val r0 = if (mono) l0 else readSample(inputBuffer)
                val m = (l0 + r0) * 0.5
                val s = (l0 - r0) * 0.5
                val c = centerLp.process(m) * 1.0
                val lf = l0.toDouble() - c * 0.5
                val rf = r0.toDouble() - c * 0.5
                feeds[0] = mcBinaural[0].process(lf, params)
                feeds[1] = mcBinaural[1].process(rf, params)
                feeds[2] = mcBinaural[2].process(c, params)
                feeds[3] = mcBinaural[3].process(s, params)
                feeds[4] = mcBinaural[4].process(-s, params)
                for (c in 2 until channels) readSample(inputBuffer)
            }
            val pair = virtual.process(feeds)
            limStereo.setThreshold(0.97)
            val pl = limStereo.process(pair.first * masterGain, pair.second * masterGain)
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
        // Sub Anchor: colapsa el bajo al centro (MonoSub). Los graves < 150 Hz
        // comparten fase L/R → anclados, definidos y con mejor "punch" en bocinas
        // que no pueden separar el extremo inferior del estéreo.
        if (params.subAnchor > 0f) {
            val pair = subAnchor.process(lo, ro, params.subAnchor.toDouble())
            lo = pair.first
            ro = pair.second
        }
        if (params.harmonicBass > 0f) {
            val hl = vbL.process(lo)
            lo += bassBoost(params.harmonicBass * vbL.gainFactor(), hl)
            val hr = vbR.process(ro)
            ro += bassBoost(params.harmonicBass * vbR.gainFactor(), hr)
        }
        // Beat Boost: realza el golpe percuativo del bombo (kick) sobre el muro
        // armónico. El kick es una ráfaga (transient) dentro de la banda de
        // graves; es precisamente lo que la separación percuativo/armónico (HPSS)
        // aísla. Refuerzo solo el pico del golpe, no el cuerpo que lo rodea.
        if (params.beatBoost > 0f) {
            lo = beatL.process(lo)
            ro = beatR.process(ro)
        }
        lo = excite(lo, exciteLpL, params.exciterAmount)
        ro = excite(ro, exciteLpR, params.exciterAmount)
        for (i in 0 until eqBands) {
            lo = eqL[i].process(lo)
            ro = eqR[i].process(ro)
        }
        if (params.tubeDrive > 0f) {
            lo = tubeDrive(lo, tubeDcL, params.tubeDrive)
            ro = tubeDrive(ro, tubeDcR, params.tubeDrive)
        }
        for (i in paramEqL.indices) {
            lo = paramEqL[i].process(lo)
            ro = paramEqR[i].process(ro)
        }
        // Spectral Clarity: de-enmascarado por bandas (UNMASK simplificado).
        // Un instrumento que toca fuerte "tapa" a los que suenan más suave en la
        // misma región. Detecto las bandas dominantes y les doy un tirón suave
        // hacia abajo mientras levanto las enterradas; así cada capa emerge.
        if (params.spectralClarity > 0f) {
            lo = clarityL.process(lo)
            ro = clarityR.process(ro)
        }
        // Transient Punch: resalta los ataques (el inicio de cada nota/golpe).
        // El cerebro identifica qué instrumento es por los primeros ms; al
        // devolver algo de "chispa" de transiente, cada beat se destaca del resto.
        if (params.transientPunch > 0f) {
            lo = punchL.process(lo)
            ro = punchR.process(ro)
        }
        val field = params.fieldSurround
        if (field > 0f) {
            val fk = field.toDouble()
            val bcl = fieldLpL.process(lo)
            val bcr = fieldLpR.process(ro)
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
            lo += dr * 0.38 * fk
            ro += dl * 0.38 * fk
        }
        compressStereo(lo, ro, params.compression, res)
        if (params.loudnessComp) {
            res[0] = loudHpL.process(loudLpL.process(res[0]))
            res[1] = loudHpR.process(loudLpR.process(res[1]))
        }
        if (params.speechClarity) {
            res[0] = scL.process(res[0])
            res[1] = scR.process(res[1])
        }
        // Explosion Ducling: cuando el sub golpea (explosión), despejo
        // selectivamente la banda 150–700 Hz para que el impacto se sienta
        // gigante y DEFINIDO, sin que el "muro" embarrado de frecuencias medias
        // tape lo demás. Sidechain suave: solo actúa mientras dura el golpe.
        res[0] = duckL.process(res[0])
        res[1] = duckR.process(res[1])
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
        // Limiter maestro con lookahead (linkeado L/R): reemplaza al softLimit.
        // Techo FIJO (~ -0.3 dBTP): ya no se baja al subir masterGain, porque la
        // detección true-peak (inter-sample) garantiza que nada recorte. Así el
        // masterGain funciona como pre-ganancia pura: más volumen SIN distorsión.
        limStereo.setThreshold(0.97)
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
        for (i in 0 until 3) {
            val bl = if (i == 0) ll else if (i == 1) lm else lh
            val br = if (i == 0) rl else if (i == 1) rm else rh
            val a = max(abs(bl), abs(br))
            compEnv[i] = if (a > compEnv[i]) compEnv[i] * compAttack + (1 - compAttack) * a else compEnv[i] * compRelease + (1 - compRelease) * a
            val g = softKneeGain(compEnv[i], strength)
            compSm[i] = compSm[i] * compSmooth + (1 - compSmooth) * g
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
        val bps = bytesPerSample()
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
            val bassDb = 9f * loud
            val trebleDb = 6f * loud
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
        val surf = if (isSpeakerLike(activeDevice)) 0.4f else 0f
        surfResoL.configure(sampleRate, surf)
        surfResoR.configure(sampleRate, surf)
        val scAmt = when (activeDevice) {
            AudioEnhanceConfig.DeviceKind.PHONE_SPEAKER -> 0.7f
            AudioEnhanceConfig.DeviceKind.TV_SPEAKER -> 0.6f
            AudioEnhanceConfig.DeviceKind.SOUNDBAR -> 0.5f
            else -> 0.4f
        }
        scL.configure(sampleRate, scAmt)
        scR.configure(sampleRate, scAmt)
        subAnchor.configure(sampleRate)
        beatL.configure(sampleRate, params.beatBoost)
        beatR.configure(sampleRate, params.beatBoost)
        punchL.configure(sampleRate, params.transientPunch)
        punchR.configure(sampleRate, params.transientPunch)
        clarityL.configure(sampleRate, params.spectralClarity)
        clarityR.configure(sampleRate, params.spectralClarity)
        duckL.configure(sampleRate, params.explosionDucking)
        duckR.configure(sampleRate, params.explosionDucking)
        // EQ: bocinas humildes (TV/celular) renderizan 5 bandas al centro del rango
        // útil (menos fase acumulada, menos resonancia en driver barato); el
        // resto usa las 10 bandas completas. Los datos de eq10 siempre se
        // conservan tal cual.
        var eqFreqs = AudioEnhanceConfig.EQ_FREQS
        val eqGains: FloatArray
        var eqCount = eqFreqs.size
        if (isSpeakerLike(activeDevice)) {
            val cheap = AudioEnhanceConfig.cheapGains5(gains)
            if (cheap != null) {
                eqFreqs = AudioEnhanceConfig.CHEAP_FREQS
                eqGains = cheap
                eqCount = eqFreqs.size
            } else {
                eqGains = gains
            }
        } else {
            eqGains = gains
        }
        if (eqL.size != eqCount) {
            eqL = Array(eqCount) { BiquadFilter() }
            eqR = Array(eqCount) { BiquadFilter() }
        }
        val maxFreq = 0.45f * sampleRate
        eqBands = eqCount
        for (i in eqFreqs.indices) {
            val g = if (eqFreqs[i] >= maxFreq) 0f else eqGains[i]
            eqL[i].configure(BiquadFilter.Kind.PEAKING, sampleRate, eqFreqs[i], g, 0.8f)
            eqR[i].configure(BiquadFilter.Kind.PEAKING, sampleRate, eqFreqs[i], g, 0.8f)
        }
        val vbXover = when (activeDevice) {
            AudioEnhanceConfig.DeviceKind.TV_SPEAKER -> 150f
            AudioEnhanceConfig.DeviceKind.PHONE_SPEAKER -> 150f
            AudioEnhanceConfig.DeviceKind.HEADPHONES -> 120f
            AudioEnhanceConfig.DeviceKind.SOUNDBAR -> 140f
            else -> 150f
        }
        vbL.configure(sampleRate, vbXover, params.dynamicBass)
        vbR.configure(sampleRate, vbXover, params.dynamicBass)
        exciteLpL.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 1400f, 0f, 0.707f)
        exciteLpR.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 1400f, 0f, 0.707f)
        fieldLpL.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 200f, 0f, 0.707f)
        fieldLpR.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 200f, 0f, 0.707f)
        tubeDcL.configure(BiquadFilter.Kind.HIGHPASS, sampleRate, 25f, 0f, 0.707f)
        tubeDcR.configure(BiquadFilter.Kind.HIGHPASS, sampleRate, 25f, 0f, 0.707f)
        compLp.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 220f, 0f, 0.707f)
        compHp.configure(BiquadFilter.Kind.HIGHPASS, sampleRate, 3200f, 0f, 0.707f)
        compLpR.configure(BiquadFilter.Kind.LOWPASS, sampleRate, 220f, 0f, 0.707f)
        compHpR.configure(BiquadFilter.Kind.HIGHPASS, sampleRate, 3200f, 0f, 0.707f)

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

        mcCenter.configure(sampleRate, gains, 2f, params.reverbMix, params.compression, params.dynamicBass, params.parametricEq, volume, params.loudnessComp, scAmt, params.masterGain)
        mcRearL.configure(sampleRate, gains, 0f, params.reverbMix * 1.4f, params.compression, params.dynamicBass, params.parametricEq, volume, params.loudnessComp, scAmt, params.masterGain)
        mcRearR.configure(sampleRate, gains, 0f, params.reverbMix * 1.4f, params.compression, params.dynamicBass, params.parametricEq, volume, params.loudnessComp, scAmt, params.masterGain)
        for (i in 0 until 5) {
            mcBinaural[i].configure(sampleRate, gains, 0f, params.reverbMix * 0.5f, params.compression, params.dynamicBass, params.parametricEq, volume, params.loudnessComp, scAmt, params.masterGain)
        }

        val ir = params.irType
        if (ir != lastIr || params.irMix != lastIrMix) {
            lastIr = ir
            lastIrMix = params.irMix
            if (ir != AudioEnhanceConfig.IrPreset.NONE && params.irMix > 0f) {
                val pair = ImpulseResponses.pair(ir, sampleRate)
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

    private fun bytesPerSample(): Int = when (encoding) {
        C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
        C.ENCODING_PCM_24BIT -> 3
        else -> 2
    }

    private fun readSample(buf: ByteBuffer): Float = when (encoding) {
        C.ENCODING_PCM_FLOAT -> buf.float
        C.ENCODING_PCM_24BIT -> {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt() and 0xFF
            val raw = b0 or (b1 shl 8) or (b2 shl 16)
            val sign24 = raw shl 8 shr 8
            sign24.toFloat() / 8388608f
        }
        C.ENCODING_PCM_32BIT -> buf.int / 2147483648f
        else -> buf.short.toFloat() / 32768f
    }

    private fun writeSample(out: ByteBuffer, v: Double) {
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> out.putFloat(v.toFloat())
            C.ENCODING_PCM_24BIT -> {
                val q = Math.round(v * 8388608.0).toInt().coerceIn(-8388608, 8388607)
                out.put((q and 0xFF).toByte())
                out.put(((q shr 8) and 0xFF).toByte())
                out.put(((q shr 16) and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT -> {
                val q = Math.round(v * 2147483648.0).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                out.putInt(q)
            }
            else -> {
                // Dither TPDF (±1 LSB) antes de cuantizar a 16-bit: decorrela el error
                // de cuantización y evita que el ruido de redondeo recorte en baja señal.
                val d1 = nextDither() * 0.5
                val d2 = nextDither() * 0.5
                val q = v * 32768.0 + 0.5 + d1 + d2
                out.putShort(q.toInt().coerceIn(-32768, 32767).toShort())
            }
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
        subAnchor.reset()
        beatL.reset()
        beatR.reset()
        punchL.reset()
        punchR.reset()
        clarityL.reset()
        clarityR.reset()
        duckL.reset()
        duckR.reset()
        reverbL?.reset()
        reverbR?.reset()
        compLp.reset()
        compHp.reset()
        compLpR.reset()
        compHpR.reset()
        compEnv.fill(0.0)
        compSm.fill(1.0)
        fieldLpL.reset()
        fieldLpR.reset()
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
        mcCenter.reset()
        mcRearL.reset()
        mcRearR.reset()
        for (c in mcBinaural) c.reset()
        lfeLp.reset()
        centerLp.reset()
        rearDelayL.reset()
        rearDelayR.reset()
        virtual.reset()
    }

    override fun onReset() {
        onFlush()
    }
}

private fun bassBoost(amt: Double, harm: Double): Double {
    val boost = amt * harm
    val a = abs(boost)
    // Techo más permisivo que el antiguo 0.35: permite que el VirtualBass aporte
    // un bajo percibido real en bocinas chicas. Lo acompañan el SubAnchor (graves
    // al centro), el compresor y el limiter true-peak final, que ya protegen del
    // recorte sin apagar el golpe.
    return if (a > 0.6) {
        val s = if (boost > 0) 1.0 else -1.0
        s * (0.6 + (a - 0.6) / (1.0 + (a - 0.6)))
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
// (para no ensuciar/recortar). Implementación propia.
// Claridad de voz: la banda 1.1–4.5 kHz es donde vive la articulación del habla y
// donde una bocina chica de TV suena "opaca". Aquí se realza dinámicamente:
//   - Env que sube con la articulación (attack 4 ms, release 150 ms).
//   - Umbral adaptativo (thr) que sigue el piso de ruido; solo se realza cuando hay
//     contenido real en la banda (evita subir silencios y siseo de fondo).
//   - De-esser (7 kHz): si la sibilancia domina, baja el boost para no crispar.
// La mezcla es aditiva (x + banda·(g−1)): con g=1 es bypass exacto.
private class SpeechClarity {
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
        pHp.configure(BiquadFilter.Kind.HIGHPASS, fs, 900f, 0f, 0.707f)
        pLp.configure(BiquadFilter.Kind.LOWPASS, fs, 6000f, 0f, 0.707f)
        dHp.configure(BiquadFilter.Kind.HIGHPASS, fs, 7000f, 0f, 0.707f)
        aA = Math.exp(-1.0 / (0.003 * fs))
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
        val t = if (ratio > 2.0) 1.0 + 0.55 * amount else 1.0 + 0.15 * amount
        gain += (t - gain) * 0.0008
        val s = abs(dHp.process(x))
        sEnv = if (s > sEnv) sEnv * aA + (1 - aA) * s else sEnv * aR + (1 - aR) * s
        val sr = sEnv / (env + 1e-5)
        val ess = if (sr > 1.2) 1.0 - 0.5 * ((sr - 1.2) / 0.8).coerceIn(0.0, 1.0) else 1.0
        return x + pres * (gain * ess - 1.0)
    }
}

// Emulación de "bocina sobre superficie" (refuerzo de graves resonante del mueble, en DSP):
// una bocina chica apoyada en un rack/mesa gana cuerpo porque la superficie actúa
// como baffle (refuerzo de graves) y como caja resonante. Aquí:
//   1) shelf de boundary ~240 Hz (+4.5 dB máx) → el "baffle".
//   2) modo resonante de cavidad ~150 Hz Q≈5 que "canta" con los transientes de
//      graves → el "cajón". b0 normalizado para ganancia de pico = 1.0.
private class SurfaceResonator {
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

private class VirtualBass {
    private val lp = BiquadFilter()
    private val smooth = BiquadFilter()
    private val hp = BiquadFilter()
    private var dynamic = true
    private var env = 0.0
    private var dynGain = 1.0
    private var attack = 0.0
    private var release = 0.0
    private var smoothG = 0.0

    fun configure(fs: Int, crossover: Float = 150f, dynamic: Boolean = true) {
        this.dynamic = dynamic
        lp.configure(BiquadFilter.Kind.LOWPASS, fs, crossover, 0f, 0.707f)
        smooth.configure(BiquadFilter.Kind.LOWPASS, fs, crossover * 8f, 0f, 0.707f)
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
            var target = 1.0
            if (env < 0.05) target = 1.0 + (0.05 - env) / 0.05 * 0.6
            else if (env > 0.40) target = 1.0 - (env - 0.40) / 0.40 * 0.5
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
private fun tubeDrive(x: Double, dc: BiquadFilter, drive: Float): Double {
    if (drive <= 0f) return x
    val g = 1.0 + 2.2 * drive.toDouble()
    val a = 0.22 * drive.toDouble()
    val b = 1.8 * drive.toDouble()
    val v = g * x
    val y = (v + a * v * v) / (1.0 + b * v * v)
    val makeup = 1.0 / (1.0 + 0.35 * drive.toDouble())
    return dc.process(y) * makeup
}

private fun excite(x: Double, lp: BiquadFilter, amt: Float): Double {
    if (amt <= 0.0f) return x
    val lpOut = lp.process(x)
    val high = x - lpOut
    val shaped = tanh(high * 4.0) * 0.4
    return x + amt * 0.7 * shaped
}

private class MonoChain {
    val eq = Array(10) { BiquadFilter() }
    val vb = VirtualBass()
    val exciteLp = BiquadFilter()
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
        compAttack = Math.exp(-1.0 / (0.010 * fs))
        compRelease = Math.exp(-1.0 / (0.150 * fs))
        compSmooth = Math.exp(-1.0 / (0.025 * fs))
        val maxFreq = 0.45f * fs
        val freqs = AudioEnhanceConfig.EQ_FREQS
        for (i in 0 until 10) {
            val g = if (freqs[i] >= maxFreq) 0f else gains[i] + (if (i == 7) presenceOffset else 0f)
            eq[i].configure(BiquadFilter.Kind.PEAKING, fs, freqs[i], g, 0.8f)
        }
        vb.configure(fs, dynamic = dynamicBass)
        exciteLp.configure(BiquadFilter.Kind.LOWPASS, fs, 1400f, 0f, 0.707f)
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
        loudLp.configure(BiquadFilter.Kind.LOWSHELF, fs, 120f, 9f * loud, 0.7f)
        loudHp.configure(BiquadFilter.Kind.HIGHSHELF, fs, 6000f, 6f * loud, 0.7f)
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
        v = excite(v, exciteLp, params.exciterAmount)
        for (b in eq) v = b.process(v)
        if (params.tubeDrive > 0f) v = tubeDrive(v, tubeDc, params.tubeDrive)
        for (b in peq) v = b.process(v)
        v = compress(v, params.compression)
        if (params.loudnessComp) v = loudHp.process(loudLp.process(v))
        if (params.speechClarity) v = sc.process(v)
        if (params.reverbMix > 0f) v += params.reverbMix.toDouble() * reverb.process(v) * 0.8
        lim.setThreshold(0.97)
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
private fun softKneeGain(env: Double, strength: Float): Double {
    val threshold = 0.10           // ~ -20 dBFS
    val ratio = 1.0 + 7.0 * strength
    val knee = 12.0                // dB de transición
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

// Limiter con lookahead (~2 ms) a nivel de muestra. El buffer de retardo retrasa
// la salida N muestras; la envolvente mira "hacia delante" (los picos que aún no
// han salido) y reduce la ganancia antes de que lleguen → sin overshoot de corta
// duración ni inter-sample clipping. La ganancia se suaviza para no "bombear".
//
// Detección TRUE-PEAK (BS.1770-like): además del valor por muestra se estima el
// pico que ocurre ENTRE muestras interpolando con una cúbica de Hermite sobre el
// par vecino (pasado + futuro del buffer de lookahead). Así un limiter que
// "no muestra clipping" de todas formas lo previene en el DAC.
private class LookaheadLimiter {
    private var buf = DoubleArray(1)
    private var idx = 0
    private var env = 0.0
    private var gain = 1.0
    private var threshold = 0.95
    private var release = 0.0
    private var smooth = 0.0
    private var prev = 0.0
    private var prev2 = 0.0

    fun configure(fs: Int, lookaheadMs: Float, releaseMs: Float, threshold: Double) {
        val n = (fs * lookaheadMs / 1000f).toInt().coerceAtLeast(1)
        buf = DoubleArray(n)
        idx = 0
        this.threshold = threshold
        release = Math.exp(-1.0 / (releaseMs * fs / 1000f))
        smooth = Math.exp(-1.0 / (1.5 * fs / 1000f))
        env = 0.0
        gain = 1.0
        prev = 0.0
        prev2 = 0.0
    }

    fun process(x: Double): Double {
        val delayed = buf[idx]
        buf[idx] = x
        idx = (idx + 1) % buf.size
        // Pico inter-sample del segmento entre prev y x, con el siguiente sample
        // (futuro del buffer) y el anterior (prev2) como soporte de la cúbica.
        val future = buf[idx]
        val a = truePeak(prev2, prev, x, future)
        prev2 = prev
        prev = x
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
        prev = 0.0
        prev2 = 0.0
    }

    fun setThreshold(threshold: Double) {
        this.threshold = threshold
    }
}

// Variante estéreo con detección linkeada (misma ganancia para L y R, tomando
// el pico true-peak de ambos) para no desplazar la imagen estéreo bajo limitación fuerte.
private class LookaheadLimiterPair {
    private var bufL = DoubleArray(1)
    private var bufR = DoubleArray(1)
    private var idx = 0
    private var env = 0.0
    private var gain = 1.0
    private var threshold = 0.95
    private var release = 0.0
    private var smooth = 0.0
    private var prevL = 0.0
    private var prevR = 0.0
    private var prev2L = 0.0
    private var prev2R = 0.0

    fun configure(fs: Int, lookaheadMs: Float, releaseMs: Float, threshold: Double) {
        val n = (fs * lookaheadMs / 1000f).toInt().coerceAtLeast(1)
        bufL = DoubleArray(n)
        bufR = DoubleArray(n)
        idx = 0
        this.threshold = threshold
        release = Math.exp(-1.0 / (releaseMs * fs / 1000f))
        smooth = Math.exp(-1.0 / (1.5 * fs / 1000f))
        env = 0.0
        gain = 1.0
        prevL = 0.0
        prevR = 0.0
        prev2L = 0.0
        prev2R = 0.0
    }

    fun process(l: Double, r: Double): Pair<Double, Double> {
        val ol = bufL[idx]
        val or = bufR[idx]
        bufL[idx] = l
        bufR[idx] = r
        idx = (idx + 1) % bufL.size
        val fL = bufL[idx]
        val fR = bufR[idx]
        val aL = truePeak(prev2L, prevL, l, fL)
        val aR = truePeak(prev2R, prevR, r, fR)
        prev2L = prevL
        prevL = l
        prev2R = prevR
        prevR = r
        val a = max(aL, aR)
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
        prevL = 0.0
        prevR = 0.0
        prev2L = 0.0
        prev2R = 0.0
    }

    fun setThreshold(threshold: Double) {
        this.threshold = threshold
    }
}

// Estimador de pico inter-sample (true-peak) sobre el intervalo entre las
// muestras m1 y m2. Interpola con la cúbica de Hermite (variante Catmull-Rom)
// usando las vecinas m0 (pasada) y m3 (futura) y evalúa en 3 fases; devuelve
// el máximo absoluto entre los bordes y las interpoladas. Sirve para que el
// limiter prevenga el clipping que ocurre ENTRE muestras en el DAC (BS.1770).
private fun truePeak(m0: Double, m1: Double, m2: Double, m3: Double): Double {
    // Bordes
    var p = max(abs(m1), abs(m2))
    // Términos de Catmull-Rom
    val a0 = 2.0 * m1
    val a1 = -m0 + m2
    val a2 = 2.0 * m0 - 5.0 * m1 + 4.0 * m2 - m3
    val a3 = -m0 + 3.0 * m1 - 3.0 * m2 + m3
    // 3 fases: t = 0.25, 0.5, 0.75
    for (t in doubleArrayOf(0.25, 0.5, 0.75)) {
        val v = 0.5 * (a0 + a1 * t + a2 * t * t + a3 * t * t * t)
        val av = abs(v)
        if (av > p) p = av
    }
    return p
}

private class RingDelay {
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

private class Allpass {
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

// Virtualización de altavoces para TV/bocinas estéreo (ambiente y crosstalk sintetizado):
// convierte estéreo en un "5.1 fantasma" reproducido por 2 bocinas chicas.
// Esto NO es un simple "ensanchar el estéreo". Es la virtualización por bocinas real
// (SRS TruSurround HD, Dolby Virtual, DTS Virtual:X) modelada sobre tres pilares
// físicos/fisiológicos (Bauer 1961 ▪ Gardner "3D Audio Using Loudspeakers" 1997 ▪
// Blauert "Spatial Hearing"):
//   1) Diálogos ANCLADOS al centro: el mid (L+R) es el MISMO en ambas bocinas → la
//      voz queda fija en el centro sin "hueco" ni flanging (nunca se toca el mid).
//      Si el contenido central se virtualizara como los lados, la voz se partiría:
//      ese era el problema del widened antiguo.
//   2) ITD REAL (diferencia interaural de tiempo) con retardo CORTO contralateral
//      (~0.55 y ~0.75 ms → δ≈0.2 ms/difusión): es lo que el cerebro usa para
//      lateralizar. Los taps largos de 9-16 ms NO lateralizan: activan precedencia
//      (Haas) y suenan a "eco de pasillo/elevador" — por eso el anterior no se sentía
//      como 3D sino como reverberación artificial.
//   3) CROSSTALK con SOMBRA ESPECTRAL DE CABEZA: la señal que cruza al oído lejano va
//      por un LOWPASS de ~2.8 kHz (la cabeza bloquea los agudos del lado opuesto) y
//      con POLARIDAD INVERTIDA y factor -0.55. Esto "exterioriza" el sonido: si solo
//      usáramos delays, sonaría "dentro de la boca"; con la sombra espectral + ITD
//      corta, el cerebro ubica fuentes LATERALES/TRASERAS fuera del eje de la bocina.
//   - El lateral se bandlimita SIN graves (no "vagarían") y a <6.4 kHz, se decorela
//     con un allpass y una reflexión corta (1.8 ms), que añade fondo difuso sin pasillo.
private class VirtualSpeaker {
    private val ambHp = BiquadFilter()
    private val ambLp = BiquadFilter()
    private val xTalkLpL = BiquadFilter()
    private val xTalkLpR = BiquadFilter()
    private val ap = Allpass()
    private var buf = DoubleArray(2048)
    private var len = 2048
    private var idx = 0
    private var tapL = 0
    private var tapR = 0
    private var tapRef = 0
    private var kSm = 0.0

    fun configure(fs: Int) {
        len = ((fs * 0.013).toInt()).coerceAtLeast(64)
        if (buf.size != len) buf = DoubleArray(len)
        idx = 0
        ambHp.configure(BiquadFilter.Kind.HIGHPASS, fs, 120f, 0f, 0.707f)
        ambLp.configure(BiquadFilter.Kind.LOWPASS, fs, 6000f, 0f, 0.707f)
        ap.g = 0.5
        // Crosstalk-Cancellation (Bauer 1961 / Gardner 1997) REAL:
        //   - tapL (0.55 ms) y tapR (1.10 ms) → ITD ≈ 0.55 ms = diferencia interaural
        //     fisiológica que lateraliza el lateral FUERA del eje de la bocina (no es
        //     un "delay de Haas" de 9/16 ms que dispara precedencia y suena a pasillo).
        //   - tapRef = 1.15 ms, POLARIDAD INVERTIDA y con SOMBRA DE CABEZA (LP ~2.8 kHz
        //     contralateral): lo que cruza al oído lejano llega apagado y con retardo
        //     corto → el cerebro lo ubica "afuera", no "dentro del pecho".
        xTalkLpR.configure(BiquadFilter.Kind.LOWPASS, fs, 2800f, 0f, 0.707f)
        xTalkLpL.configure(BiquadFilter.Kind.LOWPASS, fs, 2800f, 0f, 0.707f)
        tapL = (fs * 0.00055).toInt().coerceAtLeast(2)
        tapR = (fs * 0.00110).toInt().coerceAtLeast(2)
        tapRef = (fs * 0.00115).toInt().coerceAtLeast(2)
        // Crosstalk-cancellation (Bauer 1961 / Gardner 1997) — sombra espectral de la
        // cabeza entre 2.6 y 3.0 kHz (banda de transición suave por pendiente 0.707):
        // el camino contralateral llega apagado y DÉBIL, y es lo que exterioriza el
        // sonido "fuera" de la bocina (pinna occultation + shadowing).
        kSm = 0.0
    }

    fun reset() {
        buf.fill(0.0)
        idx = 0
        ambHp.reset()
        ambLp.reset()
        xTalkLpL.reset()
        xTalkLpR.reset()
        ap.reset()
        kSm = 0.0
    }

    fun process(l: Double, r: Double, strength: Float): Pair<Double, Double> {
        // Señal lateral bandlimitada (sin graves que "vagarían") y decorrelada.
        val amb = ambLp.process(ambHp.process((l - r) * 0.5))
        val dec = ap.process(amb)
        buf[idx] = dec
        val i = idx
        idx = (idx + 1) % buf.size
        // Crosstalk-cancellation tipo Bauer: en vez de dos "ecos" largos (9/12 ms, que
        // suenan a pasillo), usamos una DIFERENCIA INTERAURAL CORTA (0.45/0.68 ms →
        // 0.23 ms de lateralización) que el cerebro es capaz de fisiológicamente
        // interpretar como "fuera del eje de las bocinas".
        val aL = buf[(i + buf.size - tapL) % buf.size]  // oído ipsilateral
        val aR = buf[(i + buf.size - tapR) % buf.size]  // oído contralateral (ITD)
        val aRef = buf[(i + buf.size - tapRef) % buf.size] // reflexión difusa corta
        // Sombreado espectral de la cabeza (pinna occultation): el camino contralateral
        // que "atraviesa la cabeza" pierde agudos >~2.8 kHz y algo de nivel (crosstalk
        // real: el oído lejano escucha débil y apagado). Eso exterioriza el sonido
        // "fuera" de la bocina — sin esto solo se escucha un delay (eco).
        val cR = xTalkLpR.process(aR) * -0.55   // contralateral con sombra de cabeza (LP 2.8k)
        val cL = xTalkLpL.process(aL) * -0.55
        val ambL = aL + 0.45 * cR + 0.25 * aRef
        val ambR = aR + 0.45 * cL + 0.25 * aRef
        kSm += (1.0 * strength.toDouble() - kSm) * 0.0006
        return Pair(l + kSm * ambL, r + kSm * ambR)
    }
}

// Renderizador binaural de 5 altavoces virtuales (L, C, R, Ls, Rs) hacia 2 oídos.
private class VirtualSurround {
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
        doubleArrayOf(0.0, 20000.0, 1.00, 0.00, 7.0, 7000.0, 0.90, 0.00),  // Frente L
        doubleArrayOf(7.0, 7000.0, 0.90, 0.00, 0.0, 20000.0, 1.00, 0.00),  // Frente R
        doubleArrayOf(0.0, 20000.0, 0.95, 0.00, 0.0, 20000.0, 0.95, 0.00), // Centro
        doubleArrayOf(3.0, 5500.0, 1.00, 0.35, 16.0, 3000.0, 0.75, 0.45),  // Trasero L
        doubleArrayOf(16.0, 3000.0, 0.75, 0.45, 3.0, 5500.0, 1.00, 0.35)   // Trasero R
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

// Reverb de convolución densa (8 filtros comb con damping + 4 allpass):
// cola mucho más suave y natural que el Schroeder de 4 combos. Cada instancia
// lleva un "variation" para decorrelar L/R (anchura estéreo real del tail).
private class SimpleReverb(fs: Int, variation: Int = 0) {
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

// === Investigación "alta calidad": módulos de claridad y definición ===

// Seguimiento de envolvente (fast/slow attack/release). El clásico "attack
// inmediato, liberación suave": toma el pico al instante y cae lento; sirve
// para aislar ráfagas (golpes, ataques, sub explosivo) del cuerpo sostenido.
private fun envelopeFollow(x: Double, prev: Double, attackCoeff: Double, releaseCoeff: Double): Double {
    return if (x > prev) prev + (x - prev) * attackCoeff else prev + (x - prev) * releaseCoeff
}

// SubAnchorCenter: colapsa el bajo al centro (MonoSub). Los graves < 150 Hz
// tienden a deslocalizarse y a cancelarse en estéreo; al mandar solo la porción
// baja al centro, el kick y el sub quedan anclados y definidos en cualquier
// bocina. El resto del espectro permanece intacto.
private class SubAnchorCenter {
    private var lpL = BiquadFilter()
    private var lpR = BiquadFilter()
    private var ready = false

    fun configure(fs: Int) {
        lpL.configure(BiquadFilter.Kind.LOWPASS, fs, 150f, 0f, 0.707f)
        lpR.configure(BiquadFilter.Kind.LOWPASS, fs, 150f, 0f, 0.707f)
        ready = true
    }

    fun process(l: Double, r: Double, amt: Double): Pair<Double, Double> {
        val bl = if (ready) lpL.process(l) else l
        val br = if (ready) lpR.process(r) else r
        // Mezcla el contenido grave hacia la suma mono (media), manteniendo
        // ligero "aire" estéreo según amt.
        val m = (bl + br) * 0.5
        val k = amt.coerceIn(0.0, 1.0)
        val newL = l - bl + (bl * (1.0 - k) + m * k)
        val newR = r - br + (br * (1.0 - k) + m * k)
        return Pair(newL, newR)
    }

    fun reset() {
        lpL.reset()
        lpR.reset()
    }
}

// BeatBoost: realza el golpe percuativo del bombo (kick) dentro de la banda de
// graves. HPSS-ligero: separo la ráfaga de inicio (parte percusiva, rápida
// subida) del cuerpo sostenido (armónico, más lento) y refuerzo solo el golpe.
private class BeatBoostMono {
    private var lp = BiquadFilter()
    private var fastE = 0.0
    private var slowE = 0.0
    private var amount = 0.0
    private var ready = false

    fun configure(fs: Int, amount: Float) {
        this.amount = amount.toDouble()
        lp.configure(BiquadFilter.Kind.LOWPASS, fs, 140f, 0f, 0.707f)
        ready = true
    }

    fun process(x: Double): Double {
        if (!ready || amount <= 0.0) return x
        val bass = lp.process(x)
        val fast = envelopeFollow(abs(bass), fastE, 0.35, 0.012)
        val slow = envelopeFollow(abs(bass), slowE, 0.03, 0.08)
        fastE = fast
        slowE = slow
        // perc > 0 solo en el inicio del golpe (cuando fast supera a slow)
        var perc = fast - slow
        if (perc <= 0.0) perc = 0.0
        // Cuánto del golpe apoyar, limitado para no distorsionar
        val g = (perc / (slow + 1e-9)).coerceIn(0.0, 8.0) * 0.15 * amount
        return x + bass * g
    }

    fun reset() {
        lp.reset()
        fastE = 0.0
        slowE = 0.0
    }
}

// TransientPunch: resalta el ataque de cada nota/golpe. Detecta subidas rápidas
// de energía (el primer ms de un sonido) y añade un realce limitado: el cerebro
// identifica cada instrumento por ese ataque, así que al subirlo los beats se
// separan de la mezcla sin cambiar el cuerpo.
private class TransientPunch {
    private var hp = BiquadFilter()
    private var fastE = 0.0
    private var slowE = 0.0
    private var amount = 0.0
    private var ready = false

    fun configure(fs: Int, amount: Float) {
        this.amount = amount.toDouble()
        // Tracking sobre la banda de presencia (1.2 kHz) donde viven los ataques
        hp.configure(BiquadFilter.Kind.HIGHPASS, fs, 1200f, 0f, 0.707f)
        ready = true
    }

    fun process(x: Double): Double {
        if (!ready || amount <= 0.0) return x
        val presence = hp.process(x)
        val fast = envelopeFollow(abs(presence), fastE, 0.65, 0.004)
        val slow = envelopeFollow(abs(presence), slowE, 0.008, 0.07)
        fastE = fast
        slowE = slow
        var att = fast - slow
        if (att <= 0.0) att = 0.0
        val g = (att / (slow + 1e-9)).coerceIn(0.0, 12.0) * 0.09 * amount
        return x + presence * g
    }

    fun reset() {
        hp.reset()
        fastE = 0.0
        slowE = 0.0
    }
}

// SpectralClarity: de-enmascarado ligero (UNMASK simplificado) en 4 bandas.
// Cuando un instrumento domina su región, los que tocan ahí quedan tapados;
// aplico una compresión muy suave por banda hacia un objetivo común (en dB)
// para "aplanar" el espectro y dejar oír las capas. Cuidadoso con potencias.
private class SpectralClarity {
    private val lpA = BiquadFilter()
    private val lpB = BiquadFilter()
    private val lpC = BiquadFilter()
    private val env = DoubleArray(4)
    private var amount = 0.0
    private var ready = false

    fun configure(fs: Int, amount: Float) {
        this.amount = amount.toDouble()
        lpA.configure(BiquadFilter.Kind.LOWPASS, fs, 250f, 0f, 0.707f)
        lpB.configure(BiquadFilter.Kind.LOWPASS, fs, 1000f, 0f, 0.707f)
        lpC.configure(BiquadFilter.Kind.LOWPASS, fs, 4000f, 0f, 0.707f)
        ready = true
    }

    fun process(x: Double): Double {
        if (!ready || amount <= 0.0) return x
        // Cruce en cascada por sustracción -> bandas complementarias sin pérdida
        val a = lpA.process(x)          // < 250 Hz
        val restA = x - a
        val b = lpB.process(restA)      // 250–1000 Hz
        val restB = restA - b
        val c = lpC.process(restB)      // 1000–4000 Hz
        val d = restB - c               // > 4000 Hz
        val bands = doubleArrayOf(a, b, c, d)
        // Envolventes por banda (suaves, media-vida ~40 ms)
        for (i in 0 until 4) {
            env[i] = envelopeFollow(abs(bands[i]), env[i], 0.015, 0.06)
        }
        // Objetivo: media geométrica (en dB) con pequeño sesgo por región
        var logSum = 0.0
        for (i in 0 until 4) logSum += log10(env[i] + 1e-12)
        val target = logSum / 4.0
        // Ganancia por banda: comprime hacia el objetivo (factor limitado)
        var out = 0.0
        for (i in 0 until 4) {
            val dB = log10(env[i] + 1e-12)
            val diff = target - dB  // positivo = banda enterrada, negativo = dominante
            val g = 1.0 + (diff.coerceIn(-1.5, 1.5) / 8.0) * amount
            out += bands[i] * g
        }
        return out
    }

    fun reset() {
        lpA.reset()
        lpB.reset()
        lpC.reset()
        env.fill(0.0)
    }
}

// ExplosionDucker: cuando el sub golpea de repente (explosión, impacto LFE),
// despejo suavemente la banda de "masa" (150–700 Hz) para que el burum se
// sienta potente y DEFINIDO, y deje respirar la mezcla. Es un sidechain a la
// inversa: el sub empuja, la media baja. Se retira solo al terminar el golpe.
private class ExplosionDucker {
    private var subLp = BiquadFilter()
    private var bodyLp = BiquadFilter()
    private var envBase = 0.0
    private var envSub = 0.0
    private var amount = 0.0
    private var ready = false

    fun configure(fs: Int, amount: Float) {
        this.amount = amount.toDouble()
        subLp.configure(BiquadFilter.Kind.LOWPASS, fs, 90f, 0f, 0.707f)
        bodyLp.configure(BiquadFilter.Kind.LOWPASS, fs, 700f, 0f, 0.707f)
        ready = true
    }

    fun process(x: Double): Double {
        if (!ready || amount <= 0.0) return x
        val sub = subLp.process(x)
        // Base lenta para "normalizar" qué tan fuerte es el sub relativo a la señal
        envBase = envelopeFollow(abs(sub), envBase, 0.0005, 0.002)
        envSub = envelopeFollow(abs(sub), envSub, 0.02, 0.25)
        val base = envBase + 1e-9
        val threshold = 1.6 * base
        var duck = (envSub - threshold) / (threshold + 1e-9)
        if (duck < 0.0) duck = 0.0
        duck = duck.coerceIn(0.0, 1.0)
        val bodyLow = bodyLp.process(x)          // componente 0–700 Hz
        val bodyHigh = x - bodyLow               // resto
        val band = bodyLow - sub                  // 90–700 Hz ("masa")
        return bodyHigh + band * (1.0 - duck * 0.6 * amount) + sub
    }

    fun reset() {
        subLp.reset()
        bodyLp.reset()
        envBase = 0.0
        envSub = 0.0
    }
}
