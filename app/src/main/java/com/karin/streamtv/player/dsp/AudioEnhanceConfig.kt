package com.karin.streamtv.player.dsp

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlin.text.lowercase

object AudioEnhanceConfig {

    enum class Preset(val label: String) {
        OFF("Apagado (sin DSP)"),
        ANIME("Anime"),
        CINEMA("Cine/Películas"),
        BASS_BOOST("Bass Boost"),
        SURROUND("3D Surround"),
        DIALOGUE("Diálogos/Noticias"),
        MUSIC("Música"),
        SPEAKER("True MaxBass")
    }

    enum class IrPreset(val label: String) {
        NONE("Ninguno"),
        ROOM("Sala"),
        HALL("Cine / Sala grande"),
        CROSSFEED("Binaural (auriculares)"),
        SPEAKER_CAB("Cabina de bocina"),
        USER("Usuario (.wav)")
    }

    data class ParamBand(
        val freqHz: Float,
        val gainDb: Float,
        val q: Float = 0.707f,
        val kind: BiquadFilter.Kind = BiquadFilter.Kind.PEAKING
    )

    data class Params(
        val preset: Preset = Preset.ANIME,
        val enabled: Boolean = true,
        val autoDevice: Boolean = true, // preset automático según salida física (TV/audífonos/BT)
        val bassGain: Float = 0f,       // dB, -12..+12
        val trebleGain: Float = 0f,     // dB, -12..+12
        val subBassGain: Float = 0f,    // dB, -12..+12 (20-60Hz)
        val presenceGain: Float = 0f,   // dB, -12..+12 (2-6kHz)
        val surroundWidth: Float = 0f,  // 0..1.5
        val fieldSurround: Float = 0f,  // 0..1.0 (V4A: Haas + panorama + bass centrado)
        val exciterAmount: Float = 0f,  // 0..1.0 (armónicos agudos)
        val harmonicBass: Float = 0f,   // 0..1.0 (saturation graves)
        val compression: Float = 0f,    // 0..1.0 (dynamic range)
        val reverbMix: Float = 0f,      // 0..0.5
        val masterGain: Float = 1.0f,   // 0.5..2.0
        val irType: IrPreset = IrPreset.NONE,
        val irMix: Float = 0f,          // 0..1.0 (wet del convolver)
        val useSystemSpatializer: Boolean = true, // delegar al Spatializer del sistema (API 33+)
        val tubeDrive: Float = 0f,      // 0..1.0 (saturación analógica)
        val dynamicBass: Boolean = true, // bass adaptativo (envolvente)
        val loudnessComp: Boolean = true, // compensación de sonoridad (sube graves/agudos a volumen bajo)
        val surfaceResonance: Boolean = true, // resonancia de caja/superficie (TV en rack)
        val speechClarity: Boolean = true, // realce dinámico de voz (diálogos claros)
        val subharmonicMix: Float = 0f,  // 0..1.0 (sintetizador subarmónico para bocinas TV)
        val parametricEq: List<ParamBand>? = null, // curvas AutoEQ paramétricas
        val userIrName: String? = null, // nombre del IR de usuario cargado
        val eq10: FloatArray? = null    // 10 bandas ISO dB (31..16k); null = derivar del preset
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Params) return false
            return preset == other.preset &&
                enabled == other.enabled &&
                autoDevice == other.autoDevice &&
                bassGain == other.bassGain &&
                trebleGain == other.trebleGain &&
                subBassGain == other.subBassGain &&
                presenceGain == other.presenceGain &&
                surroundWidth == other.surroundWidth &&
                fieldSurround == other.fieldSurround &&
                exciterAmount == other.exciterAmount &&
                harmonicBass == other.harmonicBass &&
                compression == other.compression &&
                reverbMix == other.reverbMix &&
                masterGain == other.masterGain &&
                irType == other.irType &&
                irMix == other.irMix &&
                useSystemSpatializer == other.useSystemSpatializer &&
                tubeDrive == other.tubeDrive &&
                dynamicBass == other.dynamicBass &&
                loudnessComp == other.loudnessComp &&
                surfaceResonance == other.surfaceResonance &&
                 speechClarity == other.speechClarity &&
                 subharmonicMix == other.subharmonicMix &&
                 parametricEq == other.parametricEq &&
                userIrName == other.userIrName &&
                eq10.contentEquals(other.eq10)
        }

        override fun hashCode(): Int {
            var h = preset.hashCode()
            h = 31 * h + enabled.hashCode()
            h = 31 * h + autoDevice.hashCode()
            h = 31 * h + bassGain.hashCode()
            h = 31 * h + trebleGain.hashCode()
            h = 31 * h + subBassGain.hashCode()
            h = 31 * h + presenceGain.hashCode()
            h = 31 * h + surroundWidth.hashCode()
            h = 31 * h + fieldSurround.hashCode()
            h = 31 * h + exciterAmount.hashCode()
            h = 31 * h + harmonicBass.hashCode()
            h = 31 * h + compression.hashCode()
            h = 31 * h + reverbMix.hashCode()
            h = 31 * h + masterGain.hashCode()
            h = 31 * h + irType.hashCode()
            h = 31 * h + irMix.hashCode()
            h = 31 * h + useSystemSpatializer.hashCode()
            h = 31 * h + tubeDrive.hashCode()
            h = 31 * h + dynamicBass.hashCode()
            h = 31 * h + loudnessComp.hashCode()
            h = 31 * h + surfaceResonance.hashCode()
             h = 31 * h + speechClarity.hashCode()
            h = 31 * h + subharmonicMix.hashCode()
            h = 31 * h + (parametricEq?.hashCode() ?: 0)
            h = 31 * h + (userIrName?.hashCode() ?: 0)
            h = 31 * h + (eq10?.contentHashCode() ?: 0)
            return h
        }
        fun withPreset(p: Preset): Params = when (p) {
            Preset.OFF -> Params(Preset.OFF, false, autoDevice = false, subharmonicMix = 0f)
            // ANIME — TV speakers: diálogos nítidos, OST con cuerpo, sub-bass virtual
            Preset.ANIME -> Params(
                Preset.ANIME, true, autoDevice = false,
                bassGain = +2.0f,       // medio-bajo: peso a efectos/OST sin embarrar
                trebleGain = +0.8f,     // brillo suave, evita sibilancia en voces JP
                subBassGain = -1.5f,    // corta sub real; VirtualBass (harmonicBass) lo reconstruye
                presenceGain = +2.5f,   // 2-4 kHz: claridad máxima en voces/anime
                surroundWidth = 0.3f,   // ancho moderado para estéreo TV
                fieldSurround = 0.15f,  // Haas sutil: sensación de "delante"
                exciterAmount = 0.1f,   // armónicos agudos: detalle en OST
                harmonicBass = 0.4f,    // TruBass fuerte: sub virtual convincente
                compression = 0.45f,    // nivelación anime (susurros ↔ gritos)
                reverbMix = 0.02f,      // casi seco: TV pequeña no necesita sala
                masterGain = 1.0f,
                 irType = IrPreset.SPEAKER_CAB,
                 irMix = 0.25f,
                 subharmonicMix = 0.5f
             )
             // CINEMA — Soundbar/TV/5.1: experiencia cinematográfica real
             Preset.CINEMA -> Params(
                 Preset.CINEMA, true, autoDevice = false,
                 bassGain = +1.0f,       // soporte, no boom
                 trebleGain = +0.3f,     // aire sutil
                 subBassGain = +3.0f,    // LFE real (explosiones, banda sonora)
                 presenceGain = +1.2f,   // diálogos inteligibles sin dureza
                 surroundWidth = 0.4f,   // ancho cinematográfico
                 fieldSurround = 0.35f,  // campo V4A: Haas + bass centrado = 5.1 fantasma
                 exciterAmount = 0.04f,  // casi transparente
                 harmonicBass = 0.1f,    // ligero refuerzo armónico
                 compression = 0.35f,    // rango dinámico cine (respeta DR original)
                 reverbMix = 0.08f,      // sala de cine leve
                 masterGain = 0.98f,     // headroom para picos de LFE
                 irType = IrPreset.HALL,
                 irMix = 0.18f,
                 subharmonicMix = 0.4f
             )
             // BASS_BOOST — Musical: graves ajustados, rápidos, armónicamente ricos
             Preset.BASS_BOOST -> Params(
                 Preset.BASS_BOOST, true, autoDevice = false,
                 bassGain = +3.0f,       // 80-150 Hz: "punch" musical
                 trebleGain = +0.5f,     // compensa masking de graves
                 subBassGain = +1.5f,    // sub controlado (no retumba)
                 presenceGain = 0.0f,
                 surroundWidth = 0.15f,
                 fieldSurround = 0.05f,
                 exciterAmount = 0.06f,  // claridad en ataque de bombo/bajo
                 harmonicBass = 0.3f,    // síntesis armónica: graves "más grandes"
                 compression = 0.25f,    // dinámica musical preservada
                 reverbMix = 0.0f,
                 masterGain = 0.95f,     // headroom para boost
                 irType = IrPreset.ROOM,
                 irMix = 0.08f,
                 subharmonicMix = 0.2f
             )
             // SURROUND — Inmersivo: crossfeed binaural + Haas + reflexiones
             Preset.SURROUND -> Params(
                 Preset.SURROUND, true, autoDevice = false,
                 bassGain = +0.5f,
                 trebleGain = +1.0f,     // apertura espacial en agudos
                 subBassGain = +0.5f,
                 presenceGain = +0.8f,
                 surroundWidth = 0.85f,  // ancho máximo sin fase
                 fieldSurround = 0.6f,   // V4A fuerte: Haas 9/12ms + reflexión 16ms
                 exciterAmount = 0.15f,  // detalle espacial
                 harmonicBass = 0.08f,
                 compression = 0.2f,
                 reverbMix = 0.12f,      // ambiente envolvente
                 masterGain = 1.0f,
                 irType = IrPreset.CROSSFEED,
                 irMix = 0.2f,           // crossfeed binaural real
                 subharmonicMix = 0.1f
             )
             // DIALOGUE — Noticias/podcasts/audiolibros: inteligibilidad absoluta
             Preset.DIALOGUE -> Params(
                 Preset.DIALOGUE, true, autoDevice = false,
                 bassGain = -3.0f,       // elimina rumble/musica de fondo
                 trebleGain = +1.5f,     // aire/consonantes
                 subBassGain = -4.0f,
                 presenceGain = +5.0f,   // 1.5-4 kHz: zona crítica habla
                 surroundWidth = 0.0f,   // mono perfecto: foco central
                 fieldSurround = 0.0f,
                 exciterAmount = 0.12f,  // nitidez consonantes
                 harmonicBass = 0.0f,
                 compression = 0.7f,     // nivelación fuerte: voz constante
                 reverbMix = 0.0f,
                 masterGain = 1.05f,     // compensación corte graves
                 irType = IrPreset.NONE,
                 irMix = 0.0f,
                 subharmonicMix = 0.3f
             )
             // MUSIC — Audiófilo: respuesta plana + micro-mejora
             Preset.MUSIC -> Params(
                 Preset.MUSIC, true, autoDevice = false,
                 bassGain = +0.8f,       // compensación Fletcher-Munson suave
                 trebleGain = +0.8f,     // "aire" 8-16 kHz
                 subBassGain = +0.5f,    // extensión grave sutil
                 presenceGain = +0.3f,   // presencia vocal natural
                 surroundWidth = 0.25f,  // estéreo natural, no ensanchado artificial
                 fieldSurround = 0.1f,   // profundidad Haas leve
                 exciterAmount = 0.05f,  // armónicos: realismo timbre
                 harmonicBass = 0.12f,   // TruBass sutil: cuerpo sin colorar
                 compression = 0.1f,     // casi sin compresión: DR máximo
                 reverbMix = 0.02f,      // ambiente de sala real (IR ROOM)
                 masterGain = 1.0f,
                 irType = IrPreset.ROOM,
                 irMix = 0.12f,
                 subharmonicMix = 0.1f
             )
             // SPEAKER / TRUE MAXBASS — Bocina chica: máximo grave percibido
             Preset.SPEAKER -> Params(
                 Preset.SPEAKER, true, autoDevice = false,
                 bassGain = +3.5f,       // medio-bajo: donde el driver rinde
                 trebleGain = -0.3f,     // doma resonancias metálicas
                 subBassGain = -5.0f,    // elimina sub real (distorsiona driver)
                 presenceGain = +1.8f,   // claridad vocal
                 surroundWidth = 0.0f,
                 fieldSurround = 0.0f,
                 exciterAmount = 0.04f,
                 harmonicBass = 0.7f,    // síntesis armónica agresiva (2f, 3f, 4f)
                 compression = 0.5f,     // protege driver, nivelación fuerte
                 reverbMix = 0.0f,
                 masterGain = 1.0f,
                 irType = IrPreset.SPEAKER_CAB,
                 irMix = 0.35f,          // máximo cuerpo de cabina
                 subharmonicMix = 0.6f
             )
        }
    }

    // Frecuencias ISO de la EQ de 10 bandas (Hz)
    val EQ_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    // Curva de 10 bandas derivada de las perillas macro (bass/treble/subbass/presence).
    // El treble aterriza en 8 kHz (rango audible) con un toque en 16 kHz de "aire".
    fun deriveEq10(p: Params): FloatArray {
        val b = p.bassGain
        val t = p.trebleGain
        val sb = p.subBassGain
        val pr = p.presenceGain
        return floatArrayOf(
            sb,          // 31 Hz
            sb * 0.9f,   // 62 Hz
            b * 1.0f,    // 125 Hz
            b * 0.8f,    // 250 Hz
            b * 0.4f,    // 500 Hz
            pr * 0.2f,   // 1 kHz
            pr * 0.7f,   // 2 kHz
            pr * 1.0f,   // 4 kHz
            t * 1.0f,    // 8 kHz
            t * 0.5f     // 16 kHz
        )
    }

    data class HeadphoneProfile(val name: String, val gains: FloatArray)

    // Curvas de corrección AutoEQ embebidas (aproximadas a mediciones públicas,
    // 10 bandas: 31,62,125,250,500,1k,2k,4k,8k,16k. Positivo = subir hacia neutro).
    val headphoneProfiles: List<HeadphoneProfile> = listOf(
        HeadphoneProfile("Sony WH-1000XM4", floatArrayOf(0f, 0f, -3f, -2f, 0f, 1f, 2f, 1.5f, -1f, -1f)),
        HeadphoneProfile("Bose QC35 II", floatArrayOf(1f, 1f, 0f, 1f, 2f, 3f, 3f, 3f, 3f, 4f)),
        HeadphoneProfile("Sennheiser HD 600", floatArrayOf(4f, 3f, 2f, 0f, 0f, 0f, -2f, -1f, 0f, 0f)),
        HeadphoneProfile("Sennheiser HD 650/6XX", floatArrayOf(5f, 4f, 2f, 1f, 0f, 1f, 2f, 1f, 0f, -1f)),
        HeadphoneProfile("Beyerdynamic DT 770 Pro", floatArrayOf(-4f, -4f, -3f, -1f, 2f, 3f, 3f, 2f, -4f, -3f)),
        HeadphoneProfile("Beyerdynamic DT 990 Pro", floatArrayOf(-3f, -3f, -2f, 0f, 1f, 2f, 1f, 0f, -4f, -4f)),
        HeadphoneProfile("Audio-Technica ATH-M50x", floatArrayOf(-3f, -2f, -1f, 0f, 1f, 2f, 3f, 2f, -2f, -2f)),
        HeadphoneProfile("AirPods Pro 2", floatArrayOf(1f, 1f, 1f, 1f, 1f, 0f, 0f, -1f, -1f, -1f)),
        HeadphoneProfile("Samsung Galaxy Buds2 Pro", floatArrayOf(-2f, -1f, 0f, 1f, 2f, 3f, 2f, 1f, -2f, -2f)),
        HeadphoneProfile("KZ ZSN Pro X", floatArrayOf(-3f, -2f, 0f, 1f, 2f, 3f, 3f, 2f, -3f, -4f))
    )

    private const val PREF_NAME = "karin_audio_dsp"
    private const val KEY_PRESET = "dsp_preset"
    private const val KEY_ENABLED = "dsp_enabled"
    private const val KEY_AUTO = "dsp_auto"
    private const val KEY_DEVICE_PRESET = "dsp_device_preset"
    private const val KEY_BASS = "dsp_bass"
    private const val KEY_TREBLE = "dsp_treble"
    private const val KEY_SUBBASS = "dsp_subbass"
    private const val KEY_PRESENCE = "dsp_presence"
    private const val KEY_SURROUND = "dsp_surround"
    private const val KEY_EXCITER = "dsp_exciter"
    private const val KEY_HARMBASS = "dsp_harmbass"
    private const val KEY_COMPRESSION = "dsp_compression"
    private const val KEY_REVERB = "dsp_reverb"
    private const val KEY_MASTER = "dsp_master"
    private const val KEY_IR = "dsp_ir"
    private const val KEY_IRMIX = "dsp_irmix"
    private const val KEY_FIELD = "dsp_field"
    private const val KEY_EQ10 = "dsp_eq10"
    private const val KEY_HP = "dsp_headphone"
    private const val KEY_SPATIALIZER = "dsp_spatializer"
    private const val KEY_TUBE = "dsp_tube"
    private const val KEY_DYNBASS = "dsp_dynbass"
    private const val KEY_LOUDNESS = "dsp_loudness"
    private const val KEY_SURFACE = "dsp_surface"
    private const val KEY_SPEECH = "dsp_speech"
    private const val KEY_PARAMETRIC = "dsp_parametric"
    private const val KEY_USERIR = "dsp_user_ir"
    private const val KEY_USERIRNAME = "dsp_user_ir_name"

    @Volatile
    private var playbackVolume = 1.0f

    fun getPlaybackVolume(): Float = playbackVolume
    fun setPlaybackVolume(v: Float) { playbackVolume = v.coerceIn(0.05f, 3f) }

    // Volumen efectivo = volumen de la app × fracción del stream de música del sistema.
    // Así la compensación de sonoridad también reacciona al control remoto de la TV.
    private var appContext: Context? = null

    @Volatile
    private var appVolume = 1.0f

    fun setAppVolume(v: Float) {
        appVolume = v.coerceIn(0.05f, 3f)
        refreshPlaybackVolume()
    }

    fun refreshPlaybackVolume() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        val frac = (cur.toFloat() / max).coerceIn(0.02f, 1f)
        playbackVolume = (appVolume * frac).coerceIn(0.05f, 3f)
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var cachedParams: Params? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cachedParams = null
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(prefsListener)
        }
        cachedParams = null
        refreshPlaybackVolume()
    }

    fun isEnabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, true) ?: true
    fun setEnabled(v: Boolean) { prefs?.edit()?.putBoolean(KEY_ENABLED, v)?.apply() }

    fun isAutoDevice(): Boolean = prefs?.getBoolean(KEY_AUTO, true) ?: true
    fun setAutoDevice(v: Boolean) { prefs?.edit()?.putBoolean(KEY_AUTO, v)?.apply() }

    // Memoria por dispositivo: si el usuario eligió un preset a mano mientras
    // sonaba en X dispositivo, Auto lo recuerda para ese dispositivo.
    fun getDevicePreset(device: DeviceKind): Preset? {
        val s = prefs?.getString(KEY_DEVICE_PRESET, null) ?: return null
        for (pair in s.split(",")) {
            val kv = pair.split("=")
            if (kv.size == 2 && kv[0] == device.name) {
                return Preset.entries.getOrNull(kv[1].toIntOrNull() ?: -1)
            }
        }
        return null
    }

    fun setDevicePreset(device: DeviceKind, preset: Preset?) {
        val e = prefs?.edit() ?: return
        val map = LinkedHashMap<String, Int>()
        prefs?.getString(KEY_DEVICE_PRESET, null)?.let { old ->
            for (pair in old.split(",")) {
                val kv = pair.split("=")
                if (kv.size == 2) kv[0].let { k -> kv[1].toIntOrNull()?.let { v -> map[k] = v } }
            }
        }
        if (preset == null) map.remove(device.name) else map[device.name] = preset.ordinal
        if (map.isEmpty()) e.remove(KEY_DEVICE_PRESET)
        else e.putString(KEY_DEVICE_PRESET, map.entries.joinToString(",") { "${it.key}=${it.value}" })
        e.apply()
    }

    fun preset(): Preset {
        val idx = prefs?.getInt(KEY_PRESET, 1) ?: 1 // default ANIME
        return Preset.entries.getOrElse(idx.coerceIn(0, Preset.entries.size - 1)) { Preset.ANIME }
    }
    fun setPreset(p: Preset) { prefs?.edit()?.putInt(KEY_PRESET, p.ordinal)?.apply() }

    fun getBass(): Float = prefs?.getFloat(KEY_BASS, 0f) ?: 0f
    fun setBass(v: Float) { prefs?.edit()?.putFloat(KEY_BASS, v.coerceIn(-12f, 12f))?.apply() }

    fun getTreble(): Float = prefs?.getFloat(KEY_TREBLE, 0f) ?: 0f
    fun setTreble(v: Float) { prefs?.edit()?.putFloat(KEY_TREBLE, v.coerceIn(-12f, 12f))?.apply() }

    fun getSubBass(): Float = prefs?.getFloat(KEY_SUBBASS, 0f) ?: 0f
    fun setSubBass(v: Float) { prefs?.edit()?.putFloat(KEY_SUBBASS, v.coerceIn(-12f, 12f))?.apply() }

    fun getPresence(): Float = prefs?.getFloat(KEY_PRESENCE, 0f) ?: 0f
    fun setPresence(v: Float) { prefs?.edit()?.putFloat(KEY_PRESENCE, v.coerceIn(-12f, 12f))?.apply() }

    fun getSurround(): Float = prefs?.getFloat(KEY_SURROUND, 0f) ?: 0f
    fun setSurround(v: Float) { prefs?.edit()?.putFloat(KEY_SURROUND, v.coerceIn(0f, 1.5f))?.apply() }

    fun getExciter(): Float = prefs?.getFloat(KEY_EXCITER, 0f) ?: 0f
    fun setExciter(v: Float) { prefs?.edit()?.putFloat(KEY_EXCITER, v.coerceIn(0f, 1f))?.apply() }

    fun getHarmbass(): Float = prefs?.getFloat(KEY_HARMBASS, 0f) ?: 0f
    fun setHarmbass(v: Float) { prefs?.edit()?.putFloat(KEY_HARMBASS, v.coerceIn(0f, 1f))?.apply() }

    fun getCompression(): Float = prefs?.getFloat(KEY_COMPRESSION, 0f) ?: 0f
    fun setCompression(v: Float) { prefs?.edit()?.putFloat(KEY_COMPRESSION, v.coerceIn(0f, 1f))?.apply() }

    fun getReverb(): Float = prefs?.getFloat(KEY_REVERB, 0f) ?: 0f
    fun setReverb(v: Float) { prefs?.edit()?.putFloat(KEY_REVERB, v.coerceIn(0f, 0.5f))?.apply() }

    fun getMaster(): Float = prefs?.getFloat(KEY_MASTER, 1.0f) ?: 1.0f
    fun setMaster(v: Float) { prefs?.edit()?.putFloat(KEY_MASTER, v.coerceIn(0.5f, 2f))?.apply() }

    fun irPreset(): IrPreset {
        val idx = prefs?.getInt(KEY_IR, 0) ?: 0
        return IrPreset.entries.getOrElse(idx.coerceIn(0, IrPreset.entries.size - 1)) { IrPreset.NONE }
    }
    fun setIrPreset(p: IrPreset) { prefs?.edit()?.putInt(KEY_IR, p.ordinal)?.apply() }

    fun getIrMix(): Float = prefs?.getFloat(KEY_IRMIX, 0f) ?: 0f
    fun setIrMix(v: Float) { prefs?.edit()?.putFloat(KEY_IRMIX, v.coerceIn(0f, 1f))?.apply() }

    fun getField(): Float = prefs?.getFloat(KEY_FIELD, 0f) ?: 0f
    fun setField(v: Float) { prefs?.edit()?.putFloat(KEY_FIELD, v.coerceIn(0f, 1f))?.apply() }

    fun getEq10(): FloatArray? {
        val hp = headphone()
        if (hp != null) return headphoneProfiles.firstOrNull { it.name == hp }?.gains?.copyOf()
        val s = prefs?.getString(KEY_EQ10, null) ?: return null
        if (s.isBlank()) return null
        val parts = s.split(",")
        if (parts.size != 10) return null
        return FloatArray(10) { parts[it].toFloatOrNull() ?: 0f }
    }
    fun setEq10(v: FloatArray?) {
        val e = prefs?.edit() ?: return
        if (v == null) e.remove(KEY_EQ10) else e.putString(KEY_EQ10, v.joinToString(","))
        e.apply()
    }

    fun headphone(): String? = prefs?.getString(KEY_HP, null)
    fun setHeadphone(name: String?) {
        val e = prefs?.edit() ?: return
        if (name == null) e.remove(KEY_HP) else {
            e.putString(KEY_HP, name)
            e.remove(KEY_EQ10)
        }
        e.apply()
    }

    fun useSystemSpatializer(): Boolean = prefs?.getBoolean(KEY_SPATIALIZER, true) ?: true
    fun setUseSystemSpatializer(v: Boolean) { prefs?.edit()?.putBoolean(KEY_SPATIALIZER, v)?.apply() }

    fun getTube(): Float = prefs?.getFloat(KEY_TUBE, 0f) ?: 0f
    fun setTube(v: Float) { prefs?.edit()?.putFloat(KEY_TUBE, v.coerceIn(0f, 1f))?.apply() }

    fun getDynamicBass(): Boolean = prefs?.getBoolean(KEY_DYNBASS, true) ?: true
    fun setDynamicBass(v: Boolean) { prefs?.edit()?.putBoolean(KEY_DYNBASS, v)?.apply() }

    fun getLoudnessComp(): Boolean = prefs?.getBoolean(KEY_LOUDNESS, true) ?: true
    fun setLoudnessComp(v: Boolean) { prefs?.edit()?.putBoolean(KEY_LOUDNESS, v)?.apply() }

    fun getSurfaceResonance(): Boolean = prefs?.getBoolean(KEY_SURFACE, true) ?: true
    fun setSurfaceResonance(v: Boolean) { prefs?.edit()?.putBoolean(KEY_SURFACE, v)?.apply() }

    fun getSpeechClarity(): Boolean = prefs?.getBoolean(KEY_SPEECH, true) ?: true
    fun setSpeechClarity(v: Boolean) { prefs?.edit()?.putBoolean(KEY_SPEECH, v)?.apply() }
    private const val KEY_SUBHARMONIC = "dsp_subharmonic"
    fun getSubharmonicMix(): Float = prefs?.getFloat(KEY_SUBHARMONIC, 0f) ?: 0f
    fun setSubharmonicMix(v: Float) { prefs?.edit()?.putFloat(KEY_SUBHARMONIC, v.coerceIn(0f, 1f))?.apply() }

    fun getParametric(): List<ParamBand>? {
        val s = prefs?.getString(KEY_PARAMETRIC, null) ?: return null
        if (s.isBlank()) return null
        val list = ArrayList<ParamBand>()
        for (band in s.split("|")) {
            val p = band.split(";")
            if (p.size != 4) continue
            val f = p[1].toFloatOrNull() ?: continue
            val g = p[2].toFloatOrNull() ?: continue
            val q = p[3].toFloatOrNull() ?: continue
            val kind = when (p[0]) {
                "LSC" -> BiquadFilter.Kind.LOWSHELF
                "HSC" -> BiquadFilter.Kind.HIGHSHELF
                "LP" -> BiquadFilter.Kind.LOWPASS
                "HP" -> BiquadFilter.Kind.HIGHPASS
                else -> BiquadFilter.Kind.PEAKING
            }
            list.add(ParamBand(f, g, q, kind))
        }
        return if (list.isEmpty()) null else list
    }
    fun setParametric(bands: List<ParamBand>?) {
        val e = prefs?.edit() ?: return
        if (bands.isNullOrEmpty()) e.remove(KEY_PARAMETRIC)
        else e.putString(KEY_PARAMETRIC, bands.joinToString("|") { b ->
            val k = when (b.kind) {
                BiquadFilter.Kind.LOWSHELF -> "LSC"
                BiquadFilter.Kind.HIGHSHELF -> "HSC"
                BiquadFilter.Kind.LOWPASS -> "LP"
                BiquadFilter.Kind.HIGHPASS -> "HP"
                else -> "PK"
            }
            "$k;${b.freqHz};${b.gainDb};${b.q}"
        })
        e.apply()
    }

    fun userIrName(): String? = prefs?.getString(KEY_USERIRNAME, null)

    fun userIr(): Pair<Int, FloatArray>? = WavIr.decode(prefs?.getString(KEY_USERIR, null))

    /** Carga un IR de usuario desde bytes WAV; null → borra. Devuelve false si no es WAV soportado. */
    fun setUserIr(name: String?, bytes: ByteArray?): Boolean {
        val e = prefs?.edit() ?: return false
        if (bytes == null) {
            e.remove(KEY_USERIR).remove(KEY_USERIRNAME)
            e.apply()
            return true
        }
        val parsed = WavIr.parseAndPrepare(bytes) ?: return false
        e.putString(KEY_USERIR, WavIr.encode(parsed.first, parsed.second))
        e.putString(KEY_USERIRNAME, name)
        e.apply()
        return true
    }

    fun params(): Params {
        cachedParams?.let { return it }
        val p = Params(
            preset = preset(),
            enabled = isEnabled(),
            autoDevice = isAutoDevice(),
            bassGain = getBass(),
            trebleGain = getTreble(),
            subBassGain = getSubBass(),
            presenceGain = getPresence(),
            surroundWidth = getSurround(),
            fieldSurround = getField(),
            exciterAmount = getExciter(),
            harmonicBass = getHarmbass(),
            compression = getCompression(),
            reverbMix = getReverb(),
            masterGain = getMaster(),
            irType = irPreset(),
            irMix = getIrMix(),
            useSystemSpatializer = useSystemSpatializer(),
            tubeDrive = getTube(),
            dynamicBass = getDynamicBass(),
            loudnessComp = getLoudnessComp(),
            surfaceResonance = getSurfaceResonance(),
             speechClarity = getSpeechClarity(),
             subharmonicMix = getSubharmonicMix(),
             parametricEq = getParametric(),
            userIrName = userIrName(),
            eq10 = getEq10()
        )
        cachedParams = p
        return p
    }

    fun applyParams(p: Params) {
        setPreset(p.preset)
        setEnabled(p.enabled)
        setBass(p.bassGain)
        setTreble(p.trebleGain)
        setSubBass(p.subBassGain)
        setPresence(p.presenceGain)
        setSurround(p.surroundWidth)
        setField(p.fieldSurround)
        setExciter(p.exciterAmount)
        setHarmbass(p.harmonicBass)
        setCompression(p.compression)
        setReverb(p.reverbMix)
        setMaster(p.masterGain)
        setIrPreset(p.irType)
        setIrMix(p.irMix)
        setUseSystemSpatializer(p.useSystemSpatializer)
        setTube(p.tubeDrive)
        setDynamicBass(p.dynamicBass)
        setLoudnessComp(p.loudnessComp)
        setSurfaceResonance(p.surfaceResonance)
        setSpeechClarity(p.speechClarity)
        setSubharmonicMix(p.subharmonicMix)
        setAutoDevice(p.autoDevice)
        setParametric(p.parametricEq)
        setEq10(p.eq10)
        setHeadphone(null)
    }

    // --- Detección de salida física y preset automático ---

    private enum class OutputKind { TV_SPEAKER, HEADSET, MULTICHANNEL }

    // TYPE_SOUNDBAR es API 29; si no existe en runtime cae a -1.
    private val TYPE_SOUNDBAR: Int = try {
        AudioDeviceInfo::class.java.getField("TYPE_SOUNDBAR").getInt(null)
    } catch (t: Throwable) {
        -1
    }

    @Suppress("DEPRECATION")
    private fun outputKind(): OutputKind {
        val ctx = appContext ?: return OutputKind.TV_SPEAKER
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return OutputKind.TV_SPEAKER
        var hp = false
        var multi = false
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (!d.isSink) continue
                    when (d.type) {
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_HEADSET -> hp = true
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                            // Heurística por nombre de producto: distinguir auriculares vs bocina BT clásico
                            val name = d.productName?.toString()?.lowercase() ?: ""
                            val isLikelySpeaker = name.contains("speaker") || name.contains("soundbar") || name.contains("tv ") || name.contains("tv-") || name.contains("tv_") || name.contains("box") || name.contains("home") || name.contains("receiver") || name.contains("amp")
                            val isLikelyHeadphone = name.contains("headphone") || name.contains("buds") || name.contains("headset") || name.contains("earphone") || name.contains("earbud") || name.contains("airpod") || name.contains("galaxy bud") || name.contains("pixel bud") || name.contains("wh-") || name.contains("wf-") || name.contains("qc35") || name.contains("qc45") || name.contains("momentum") || name.contains("pxc") || name.contains("hd ") || name.contains("dt ") || name.contains("ath-") || name.contains("kz ") || name.contains("blon")
                            if (isLikelySpeaker && !isLikelyHeadphone) multi = true else hp = true
                        }
                        AudioDeviceInfo.TYPE_HDMI,
                        AudioDeviceInfo.TYPE_HDMI_ARC,
                        AudioDeviceInfo.TYPE_AUX_LINE,
                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_ACCESSORY,
                        AudioDeviceInfo.TYPE_DOCK,
                        TYPE_SOUNDBAR -> multi = true
                    }
                }
            } catch (t: Throwable) {
                // fallback: seguir con lo detectado hasta ahora
            }
        } else {
            hp = am.isWiredHeadsetOn || am.isBluetoothA2dpOn
        }
        return when {
            hp -> OutputKind.HEADSET
            multi -> OutputKind.MULTICHANNEL
            else -> OutputKind.TV_SPEAKER
        }
    }

    enum class DeviceKind { NEUTRAL, PHONE_SPEAKER, TV_SPEAKER, HEADPHONES, SOUNDBAR }

    // ¿Corre en una TV (Android TV) o en un celular/tablet?
    // La bocina interna es la misma categoría de audio en ambos, así que
    // distinguimos por el factor de forma del dispositivo.
    fun isTvDevice(): Boolean {
        val ctx = appContext ?: return false
        val mode = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
        return mode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun currentDeviceKind(): DeviceKind = when (outputKind()) {
        OutputKind.HEADSET -> DeviceKind.HEADPHONES
        OutputKind.MULTICHANNEL -> DeviceKind.SOUNDBAR
        OutputKind.TV_SPEAKER -> if (isTvDevice()) DeviceKind.TV_SPEAKER else DeviceKind.PHONE_SPEAKER
    }

    fun outputDeviceLabel(): String = when (outputKind()) {
        OutputKind.HEADSET -> "Audífonos (jack/BT)"
        OutputKind.MULTICHANNEL -> "Barra de sonido / 5.1 (HDMI/ARC)"
        OutputKind.TV_SPEAKER -> if (isTvDevice()) "Bocina de la TV" else "Bocina del celular"
    }

    // Ajustes por dispositivo (modo Auto): adaptan el preset base a la salida física.
    // Diseñados para ser musicales, no correctivos brutos.
    fun applyDeviceTuning(base: Params, device: DeviceKind): Params = when (device) {
        DeviceKind.NEUTRAL -> base
        // Celular: driver 10-15mm, mono, cerca de la oreja
        DeviceKind.PHONE_SPEAKER -> base.copy(
            subBassGain = (base.subBassGain - 3f).coerceIn(-12f, 12f),   // driver no reproduce <100Hz
            trebleGain = (base.trebleGain + 0.5f).coerceIn(-12f, 12f),   // compensa roll-off agudos
            presenceGain = (base.presenceGain + 0.8f).coerceIn(-12f, 12f), // claridad voz
            harmonicBass = (base.harmonicBass + 0.25f).coerceIn(0f, 1f),  // VirtualBass fuerte
            compression = (base.compression + 0.15f).coerceIn(0f, 1f),    // protege driver
            reverbMix = 0f,
            surroundWidth = (base.surroundWidth * 0.3f).coerceIn(0f, 1.5f), // mono real
            fieldSurround = 0f
        )
        // TV: drivers 2-3", caja sellada, placement variable
        DeviceKind.TV_SPEAKER -> base.copy(
            trebleGain = (base.trebleGain - 0.3f).coerceIn(-12f, 12f),   // reduce resonancias metálicas
            subBassGain = (base.subBassGain - 1.5f).coerceIn(-12f, 12f), // corte suave sub
            presenceGain = (base.presenceGain + 0.6f).coerceIn(-12f, 12f), // diálogos
            harmonicBass = (base.harmonicBass + 0.2f).coerceIn(0f, 1f),   // graves virtuales
            reverbMix = 0f,
            surfaceResonance = true  // activa resonancia de caja/mesa
        )
        // Auriculares: respuesta plana, estéreo real, nearfield
        DeviceKind.HEADPHONES -> base.copy(
            surroundWidth = (base.surroundWidth + 0.2f).coerceIn(0f, 1.5f), // abre escenario
            subBassGain = (base.subBassGain + 0.3f).coerceIn(-12f, 12f),   // extensión real
            trebleGain = (base.trebleGain + 0.2f).coerceIn(-12f, 12f),     // aire
            reverbMix = (base.reverbMix + 0.015f).coerceIn(0f, 0.5f),      // ambience natural
            fieldSurround = (base.fieldSurround + 0.1f).coerceIn(0f, 1f)   // profundidad
        )
        // Soundbar/HT: drivers dedicados, subwoofer real, placement fijo
        DeviceKind.SOUNDBAR -> base.copy(
            subBassGain = (base.subBassGain + 0.5f).coerceIn(-12f, 12f),  // sub real aprovecha
            fieldSurround = (base.fieldSurround + 0.2f).coerceIn(0f, 1f),  // V4A: surround real
            reverbMix = (base.reverbMix + 0.02f).coerceIn(0f, 0.5f),
            surroundWidth = (base.surroundWidth + 0.1f).coerceIn(0f, 1.5f)
        )
    }
}
