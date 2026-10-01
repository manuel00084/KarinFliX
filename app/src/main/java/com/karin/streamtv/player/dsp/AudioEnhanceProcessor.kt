package com.karin.streamtv.player.dsp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.karin.streamtv.player.dsp.smartlite.SmartLiteConfig
import com.karin.streamtv.player.dsp.smartlite.SmartLiteDSP
import com.karin.streamtv.util.DeviceProfile
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.text.lowercase

class AudioEnhanceProcessor(context: Context) : BaseAudioProcessor() {
    companion object {
        private val TYPE_SOUNDBAR: Int = try {
            AudioDeviceInfo::class.java.getField("TYPE_SOUNDBAR").getInt(null)
        } catch (t: Throwable) {
            -1
        }

        /**
         * Cuantización simétrica a PCM de 16 bits.
         *
         * El valor de entrada ya incluye la escala por 32768, el desplazamiento
         * de +0.5 y el dither TPDF. `floor` es obligatorio: `toInt()` trunca
         * hacia cero y redondea los negativos hacia arriba, creando una zona
         * muerta asimétrica y distorsión en baja señal.
         */
        internal fun quantizePcm16Sample(scaledPlusDither: Double): Int =
            Math.floor(scaledPlusDither).toInt().coerceIn(-32768, 32767)
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val appContext = context.applicationContext
    private val appAudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var headphones = false
    private var multichannelCapable = false
    private var spatializerAvailable = false
    private var route = Route.STEREO
    @Volatile private var outputRescanRequested = false
    private var outputListenerRegistered = false
    private var lastSlEffective: SmartLiteConfig.Params? = null
    private var deviceTier: DeviceProfile.Tier = DeviceProfile.Tier.MID
    /** Ultra económico: sonido básico (sin armónicos, transitorios ni graves extra). */
    private var ultraAudio: Boolean = false
    private var btA2dpProxy: BluetoothProfile? = null
    private var btCodecReceiverRegistered = false

    private val btServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                btA2dpProxy = proxy
                refreshBtCapabilities()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) btA2dpProxy = null
        }
    }

    /**
     * Cambios de códec activo (vía pública: el stack BT emite el estado del
     * códec; la acción se usa por literal porque la constante no está en los
     * stubs del SDK, pero registrar un receiver inexistente es inocuo).
     */
    private val btCodecReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                @Suppress("DEPRECATION")
                val status = intent.getParcelableExtra(
                    BluetoothCodecStatus.EXTRA_CODEC_STATUS
                ) as? BluetoothCodecStatus ?: return
                val info = status.codecConfig ?: return
                if (info.codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID) return
                SmartLiteConfig.updateBtCodecLabel(
                    "Activo: " + BluetoothCodecMonitor.decodeCodec(
                        info.codecType, info.sampleRate, info.bitsPerSample
                    ).label()
                )
            } catch (t: Throwable) {
                Log.w("AudioEnhance", "bt codec broadcast fallo: ${t.message}")
            }
        }
    }

    // AudioDeviceCallback solo existe desde API 23 (M). minSdk es 23, pero el
    // lazy + la anotación evitan VerifyError si algún día se baja minSdk o el
    // classloader verifica antes del guard de ensureOutputListener().
    private val outputDeviceCallback: AudioDeviceCallback by lazy {
        createOutputDeviceCallback()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createOutputDeviceCallback(): AudioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                outputRescanRequested = true
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                outputRescanRequested = true
            }
        }

    private var sampleRate = 48000
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var outChannels = 2

    // Karin SmartLite DSP (experimental): pipeline secundario. Solo corre
    // cuando AudioEngine = SMART_LITE (exclusión mutua en queueInput).
    private val smartlite = SmartLiteDSP()
    private var slEngaged = false
    private var lastEngine: SmartLiteConfig.Engine? = null

    // Scratch para canales extra (>2) en la ruta smartlite (evita alloc).
    private val audioFileExtra = DoubleArray(8)

    // Scratch estéreo reutilizado: las funciones del camino caliente que antes
    // devolvían Pair<Double,Double> por muestra generaban ~9 MB/s de basura GC
    // justo en el hilo de audio (picos de GC = clicks).
    private val scratch2 = DoubleArray(2)

    // LCG para dither TPDF: 5-10x más rápido que kotlin.random.Random.nextDouble()
    private var ditherState = 0xC0FFEE17L

    private fun nextDither(): Double {
        ditherState = ditherState * 0x5DEECE66DL + 0xBL
        return ((ditherState shr 17).toInt() and 0x7FFF).toDouble() / 16384.0 - 1.0
    }

    // Dither + noise-shaping para la cuantización 24/32-bit en writeSample.
    private val ditherNs = NoiseShaper()

    private enum class Route { STEREO, MULTI, BINAURAL, UPMIX }

    // TYPE_BLE_*/TYPE_HEARING_AID/TYPE_SOUNDBAR son constantes inlined de
    // API 28/31: la comparación es segura en API 23 (solo int), pero se
    // suprime el lint InlinedApi de forma explícita.
    @SuppressLint("InlinedApi")
    private fun detectOutput() {
        headphones = false
        multichannelCapable = false
        spatializerAvailable = false
        val am = audioManager ?: return
        val playbackEndpoints = ArrayList<SmartLiteConfig.OutputEndpoint>()
        var headphoneProductName: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (!d.isSink) continue
                    val productName = d.productName?.toString().orEmpty()
                    playbackEndpoints.add(
                        SmartLiteConfig.OutputEndpoint(
                            type = d.type,
                            productName = productName,
                            isSink = true
                        )
                    )
                    when (d.type) {
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_HEARING_AID -> {
                            headphones = true
                            headphoneProductName = productName
                        }
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                            // Heurística por nombre: distinguir auriculares vs bocina BT clásico
                            val name = d.productName?.toString()?.lowercase() ?: ""
                            val isLikelySpeaker = name.contains("speaker") || name.contains("soundbar") || name.contains("tv ") || name.contains("tv-") || name.contains("tv_") || name.contains("box") || name.contains("home") || name.contains("receiver") || name.contains("amp")
                            val isLikelyHeadphone = name.contains("headphone") || name.contains("buds") || name.contains("headset") || name.contains("earphone") || name.contains("earbud") || name.contains("airpod") || name.contains("galaxy bud") || name.contains("pixel bud") || name.contains("wh-") || name.contains("wf-") || name.contains("qc35") || name.contains("qc45") || name.contains("momentum") || name.contains("pxc") || name.contains("hd ") || name.contains("dt ") || name.contains("ath-") || name.contains("kz ") || name.contains("blon")
                            if (isLikelySpeaker && !isLikelyHeadphone) multichannelCapable = true else {
                                headphones = true
                                headphoneProductName = productName
                            }
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
        val playbackOutput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            SmartLiteConfig.classifyPlaybackOutput(
                playbackEndpoints,
                SmartLiteConfig.isTvDevice()
            )
        } else if (headphones) {
            SmartLiteConfig.PlaybackOutput.WIRED_HEADPHONES
        } else if (SmartLiteConfig.isTvDevice()) {
            SmartLiteConfig.PlaybackOutput.TV_SPEAKER
        } else {
            SmartLiteConfig.PlaybackOutput.PHONE_SPEAKER
        }
        SmartLiteConfig.updateDetectedPlaybackOutput(playbackOutput)
        refreshBtCapabilities()
        // Si la salida ganadora es de audífonos, buscar un perfil medido por
        // nombre de producto para el modo AutoEQ "auto". En cualquier otra
        // salida se limpia el match para no aplicar curvas de audífonos.
        if (playbackOutput == SmartLiteConfig.PlaybackOutput.WIRED_HEADPHONES ||
            playbackOutput == SmartLiteConfig.PlaybackOutput.BLUETOOTH_HEADPHONES
        ) {
            SmartLiteConfig.updateMatchedAutoEqProfile(
                AutoEqCatalog.findBestMatch(headphoneProductName.orEmpty())
            )
        } else {
            SmartLiteConfig.updateMatchedAutoEqProfile(null)
        }
        // Spatializer existe desde API 32 (S_V2), no 33: el guard anterior
        // era conservador pero incorrecto por una versión.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            try {
                val sp = am.getSpatializer()
                spatializerAvailable = sp.isEnabled && sp.isAvailable
            } catch (t: Throwable) {
                Log.w("AudioEnhance", "Spatializer query fallo: ${t.message}")
            }
        }
        Log.i("AudioEnhance", "salida: auriculares=$headphones multi5.1=$multichannelCapable spatializer=$spatializerAvailable smartlite=${SmartLiteConfig.detectedPlaybackOutput().label}")
    }

    private fun ensureOutputListener() {
        if (outputListenerRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val am = appAudioManager ?: return
        try {
            am.registerAudioDeviceCallback(outputDeviceCallback, null)
            outputListenerRegistered = true
        } catch (t: Throwable) {
            Log.w("AudioEnhance", "output listener fallo: ${t.message}")
        }
        ensureBtProxy()
        ensureBtCodecReceiver()
    }

    private fun releaseOutputListener() {
        if (!outputListenerRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val am = appAudioManager ?: return
        try {
            am.unregisterAudioDeviceCallback(outputDeviceCallback)
        } catch (t: Throwable) {
            Log.w("AudioEnhance", "output listener release fallo: ${t.message}")
        } finally {
            outputListenerRegistered = false
        }
        releaseBtProxy()
        releaseBtCodecReceiver()
    }

    private fun ensureBtProxy() {
        if (btA2dpProxy != null) return
        // En API 31+ getProfileProxy sin BLUETOOTH_CONNECT lanza
        // SecurityException: se verifica antes para no spamear el log.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter: BluetoothAdapter? = manager?.adapter
            if (adapter == null || !adapter.isEnabled) return
            adapter.getProfileProxy(appContext, btServiceListener, BluetoothProfile.A2DP)
        } catch (t: Throwable) {
            // Sin permiso BLUETOOTH_CONNECT (API 31+) u otro fallo: el códec
            // queda como "desconocido" sin tumbar nada.
            Log.w("AudioEnhance", "bt proxy fallo: ${t.message}")
        }
    }

    private fun releaseBtProxy() {
        try {
            val proxy = btA2dpProxy ?: return
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy)
        } catch (t: Throwable) {
            Log.w("AudioEnhance", "bt proxy release fallo: ${t.message}")
        } finally {
            btA2dpProxy = null
        }
    }

    /** Capacidades del sink A2DP (SBC/AAC/aptX/LDAC…): vía pública, sin proxy de estado. */
    private fun refreshBtCapabilities() {
        try {
            val proxy = btA2dpProxy as? BluetoothA2dp ?: return
            val names = proxy.supportedCodecTypes.mapNotNull { it.codecName }.distinct()
            if (names.isNotEmpty()) {
                SmartLiteConfig.updateBtCodecLabel("Soporta: " + names.joinToString(" · "))
            }
        } catch (t: SecurityException) {
            SmartLiteConfig.updateBtCodecLabel("permiso Bluetooth requerido")
        } catch (t: Throwable) {
            Log.w("AudioEnhance", "bt caps fallo: ${t.message}")
        }
    }

    private fun ensureBtCodecReceiver() {
        if (btCodecReceiverRegistered) return
        try {
            val filter = IntentFilter(
                "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    btCodecReceiver, filter, Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(btCodecReceiver, filter)
            }
            btCodecReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w("AudioEnhance", "bt codec receiver fallo: ${t.message}")
        }
    }

    private fun releaseBtCodecReceiver() {
        if (!btCodecReceiverRegistered) return
        try {
            appContext.unregisterReceiver(btCodecReceiver)
        } catch (t: Throwable) {
            Log.w("AudioEnhance", "bt codec receiver release fallo: ${t.message}")
        } finally {
            btCodecReceiverRegistered = false
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        ensureOutputListener()
        deviceTier = runCatching { DeviceProfile.get(appContext).tier }
            .getOrDefault(DeviceProfile.Tier.MID)
        // Ultra económico: cuenta como gama baja para el audio aunque el
        // perfil diga otra cosa (mismo interruptor, sin tocar ajustes).
        ultraAudio = runCatching {
            com.karin.streamtv.util.AppPreferences.isUltraEconomyMode()
        }.getOrDefault(false)
        if (ultraAudio) deviceTier = DeviceProfile.Tier.LOW
        detectOutput()
        route = when {
            channels >= 6 && headphones -> {
                // Con spatializer del sistema, salen 6ch y el HAL hace el
                // spatial; sin él, folddown estéreo en processSmartLite.
                if (spatializerAvailable) Route.MULTI else Route.BINAURAL
            }
            channels >= 6 -> Route.MULTI
            headphones -> Route.BINAURAL
            multichannelCapable -> Route.UPMIX
            else -> Route.STEREO
        }
        outChannels = when (route) {
            // MULTI pasa los canales al HAL/Spatializer: 8 canales si la
            // fuente es realmente 7.1 (7.1 passthrough), 6 si es 5.1.
            Route.MULTI -> if (channels >= 8) 8 else 6
            Route.UPMIX -> 6
            else -> 2
        }
        // Audio codificado (passthrough AC3/EAC3/DTS): son bytes comprimidos,
        // no muestras; remezclarlos los corrompe. Se copian intactos con los
        // mismos canales de entrada (bypass() byte a byte).
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_24BIT &&
            encoding != C.ENCODING_PCM_32BIT && encoding != C.ENCODING_PCM_FLOAT
        ) {
            route = Route.STEREO
            outChannels = channels
        }
        // Preconfigurar el pipeline smartlite (inofensivo si no está activo).
        try {
            smartlite.configure(sampleRate, channels, SmartLiteConfig.params())
        } catch (t: Throwable) {
            Log.w("AudioEnhance", "smartlite configure: ${t.message}")
        }
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
        if (outputRescanRequested) {
            outputRescanRequested = false
            detectOutput()
        }
        // ── Audio Engine: OFF copia directo; todo lo demás es SmartLite
        // (único motor; el DSP antiguo fue eliminado).
        val engine = SmartLiteConfig.engine()
        if (lastEngine != engine) {
            val wasAp = slEngaged
            val nowAp = engine == SmartLiteConfig.Engine.SMART_LITE
            if (nowAp && !wasAp) {
                smartlite.setEngaged(true)
                slEngaged = true
                Log.i("AudioEnhance", "engine → SMART_LITE")
            } else if (!nowAp && wasAp) {
                smartlite.setEngaged(false)
                // El crossfade de salida lo completa smartlite.isFading();
                // mientras dura, seguimos en la ruta smartlite hasta mix=0.
                Log.i("AudioEnhance", "engine → $engine (saliendo de smartlite)")
            }
            lastEngine = engine
        }
        if (engine == SmartLiteConfig.Engine.OFF && !(slEngaged && smartlite.isFading())) {
            bypass(inputBuffer)
            return
        }
        processSmartLite(inputBuffer)
        if (!smartlite.isFading() && !slEngagedReady()) {
            // Terminó el fade de salida: liberar la ruta.
            slEngaged = false
        }
    }

    private fun slEngagedReady(): Boolean =
        SmartLiteConfig.engine() == SmartLiteConfig.Engine.SMART_LITE

    /**
     * Ruta del Karin SmartLite DSP (experimental). Lee params/A-B de
     * SmartLiteConfig (cacheado por buffer, no por muestra). Mantiene el
     * mismo formato de salida que onConfigure prometió (outChannels).
     * L/R pasan por el pipeline estéreo; canales extra se copian sin colorar
     * (transparencia multicanal). Mapeo de canales al estilo bypass().
     */
    private fun processSmartLite(inputBuffer: ByteBuffer) {
        // Bit-perfect formal: copia directa sin tocar ni una muestra, aunque el
        // motor SmartLite esté seleccionado. Verifica formato en el monitor.
        if (SmartLiteConfig.isBitPerfect()) {
            bypass(inputBuffer)
            return
        }
        val bytesPerSample = bytesPerSample()
        val frames = inputBuffer.remaining() / (bytesPerSample * channels)
        if (frames <= 0) return
        val out = replaceOutputBuffer(frames * bytesPerSample * outChannels)
        try {
            val withAutoEq = SmartLiteConfig.resolveEqBands(SmartLiteConfig.params())
            val withOutput = SmartLiteConfig.withDetectedOutput(
                withAutoEq,
                SmartLiteConfig.detectedPlaybackOutput()
            )
            val effectiveParams = SmartLiteConfig.applyQualityTier(
                withOutput,
                lowTier = deviceTier == DeviceProfile.Tier.LOW,
                ultra = ultraAudio
            )
            if (effectiveParams != lastSlEffective) {
                smartlite.updateIfChanged(effectiveParams, SmartLiteConfig.isAbBypass())
                lastSlEffective = effectiveParams
            }
            val t0 = System.nanoTime()
            val scratch = scratch2
            val extra = audioFileExtra
            for (f in 0 until frames) {
                var inL: Double
                var inR: Double
                if (channels == 1) {
                    val x = readSample(inputBuffer).toDouble()
                    inL = x; inR = x
                } else {
                    inL = readSample(inputBuffer).toDouble()
                    inR = readSample(inputBuffer).toDouble()
                    val extra = audioFileExtra
                    var c = 2
                    while (c < channels && c < extra.size) {
                        extra[c] = readSample(inputBuffer).toDouble()
                        c++
                    }
                    while (c < channels) {
                        readSample(inputBuffer); c++
                    }
                    if (channels > 2 && outChannels == 2) {
                        // Folddown simple a estéreo (el downmix binaural vivía
                        // en el DSP antiguo, eliminado): el centro va a ambos
                        // lados para no perder el diálogo, laterales a −3 dB y
                        // LFE a −6 dB. Orden SMPTE: L,R,C,LFE,BL,BR.
                        val cc = if (extra.size > 2) extra[2] else 0.0
                        val lfe = if (extra.size > 3) extra[3] else 0.0
                        val bl = if (extra.size > 4) extra[4] else 0.0
                        val br = if (extra.size > 5) extra[5] else 0.0
                        inL += 0.7071 * cc + 0.7071 * bl + 0.5 * lfe
                        inR += 0.7071 * cc + 0.7071 * br + 0.5 * lfe
                    }
                }
                smartlite.processFrame(inL, inR, scratch)
                when {
                    outChannels == 1 -> writeSample(out, (scratch[0] + scratch[1]) * 0.5)
                    channels == 1 -> {
                        writeSample(out, scratch[0]); writeSample(out, scratch[1])
                        for (c in 2 until outChannels) writeSample(out, 0.0)
                    }
                    channels == 2 && outChannels >= 6 -> {
                        writeSample(out, scratch[0]); writeSample(out, scratch[1])
                        writeSample(out, (scratch[0] + scratch[1]) * 0.5)
                        writeSample(out, 0.0); writeSample(out, 0.0); writeSample(out, 0.0)
                        for (c in 6 until outChannels) writeSample(out, 0.0)
                    }
                    else -> {
                        // outChannels == channels (>=2): L/R procesados, resto original.
                        writeSample(out, scratch[0])
                        writeSample(out, scratch[1])
                        var c = 2
                        while (c < outChannels) {
                            writeSample(out, if (c < extra.size) extra[c] else 0.0)
                            c++
                        }
                    }
                }
            }
            smartlite.reportBlockTime(System.nanoTime() - t0, frames)
        } catch (t: Throwable) {
            Log.w("AudioEnhance", "smartlite error: ${t.message}")
        }
        out.flip()
    }

    private fun bypass(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        // No-PCM (passthrough) o mismo nº de canales: copia byte a byte para
        // CUALQUIER encoding, sin reinterpretar muestras.
        val isPcm = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_24BIT ||
            encoding == C.ENCODING_PCM_32BIT || encoding == C.ENCODING_PCM_FLOAT
        if (outChannels == channels || !isPcm) {
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
                    // Antes leía 6 muestras fijas: con entradas de 3/4/5 canales
                    // consumía de más (BufferUnderflow) y desfasaba el resto.
                    val take = minOf(6, channels)
                    for (c in 0 until take) writeSample(out, readSample(inputBuffer).toDouble())
                    for (c in take until 6) writeSample(out, 0.0)
                    for (c in take until channels) readSample(inputBuffer)
                }
                else -> {
                    // outChannels==2 con 3/4/5ch: consumir TODA la entrada y
                    // escribir solo 2 muestras (antes escribía `channels` samples
                    // en un buffer de 2 → BufferOverflow).
                    val l = readSample(inputBuffer).toDouble()
                    val r = readSample(inputBuffer).toDouble()
                    writeSample(out, l)
                    writeSample(out, r)
                    for (c in 2 until channels) readSample(inputBuffer)
                }
            }
        }
        out.flip()
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
                // Dither TPDF (±1 LSB) + noise-shaping F-weighted de 4º orden:
                // en 24-bit el LSB ronda -138 dBFS, pero el shaping desplaza el
                // error de cuantización fuera de la banda más sensible (> 4 kHz).
                val s = v * 8388608.0 + (nextDither() - nextDither()) * 0.5
                val shaped = ditherNs.shaped(s)
                val q = Math.round(shaped).toInt().coerceIn(-8388608, 8388607)
                ditherNs.pushError(shaped, q.toDouble())
                out.put((q and 0xFF).toByte())
                out.put(((q shr 8) and 0xFF).toByte())
                out.put(((q shr 16) and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT -> {
                val s = v * 2147483648.0 + (nextDither() - nextDither()) * 0.5
                val shaped = ditherNs.shaped(s)
                val q = Math.round(shaped).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                ditherNs.pushError(shaped, q.toDouble())
                out.putInt(q)
            }
            else -> {
                // Dither TPDF (±1 LSB) antes de cuantizar a 16-bit: decorrela el error
                // de cuantización y evita que el ruido de redondeo recorte en baja señal.
                val d1 = nextDither() * 0.5
                val d2 = nextDither() * 0.5
                val q = quantizePcm16Sample(v * 32768.0 + 0.5 + d1 + d2)
                out.putShort(q.toShort())
            }
        }
    }

    override fun onFlush() {
        releaseOutputListener()
        ditherState = 0xC0FFEE17L
        ditherNs.reset()
        smartlite.reset()
    }

    override fun onReset() {
        onFlush()
    }
}

// Variante estéreo con detección linkeada (misma ganancia para L y R, tomando
// el pico true-peak de ambos) para no desplazar la imagen estéreo bajo limitación fuerte.
class LookaheadLimiterPair {
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

    fun process(l: Double, r: Double, out: DoubleArray) {
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
        out[0] = ol * gain
        out[1] = or * gain
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


/**
 * Monitor del códec Bluetooth A2DP activo. Solo lectura y degradación
 * elegante: sin permiso BLUETOOTH_CONNECT (API 31+) o sin proxy, todo es
 * "desconocido" y nada se rompe. `decodeCodec` es puro y testeable.
 */
object BluetoothCodecMonitor {

    data class CodecInfo(val codec: String, val sampleRate: String, val bits: String) {
        fun label(): String = "$codec, $sampleRate, $bits"
    }

    fun decodeCodec(type: Int, rateMask: Int, bitsMask: Int): CodecInfo {
        val codec = when (type) {
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "aptX"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3 -> "LC3"
            else -> "códec $type"
        }
        val rate = when {
            rateMask and BluetoothCodecConfig.SAMPLE_RATE_192000 != 0 -> "192 kHz"
            rateMask and BluetoothCodecConfig.SAMPLE_RATE_176400 != 0 -> "176.4 kHz"
            rateMask and BluetoothCodecConfig.SAMPLE_RATE_96000 != 0 -> "96 kHz"
            rateMask and BluetoothCodecConfig.SAMPLE_RATE_88200 != 0 -> "88.2 kHz"
            rateMask and BluetoothCodecConfig.SAMPLE_RATE_48000 != 0 -> "48 kHz"
            rateMask and BluetoothCodecConfig.SAMPLE_RATE_44100 != 0 -> "44.1 kHz"
            else -> "? kHz"
        }
        val bits = when {
            bitsMask and BluetoothCodecConfig.BITS_PER_SAMPLE_32 != 0 -> "32 bits"
            bitsMask and BluetoothCodecConfig.BITS_PER_SAMPLE_24 != 0 -> "24 bits"
            bitsMask and BluetoothCodecConfig.BITS_PER_SAMPLE_16 != 0 -> "16 bits"
            else -> "? bits"
        }
        return CodecInfo(codec, rate, bits)
    }
}
