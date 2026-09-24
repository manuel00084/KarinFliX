package com.karin.streamtv.player.dsp.audiophile

import android.content.Context
import android.content.SharedPreferences
import com.karin.streamtv.player.dsp.BiquadFilter
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/**
 * Preferencias y modo del DSP experimental "Karin Audiophile".
 * Archivo propio ("karin_audiophile"): se puede borrar todo el paquete sin
 * tocar el DSP actual (AudioEnhanceConfig sigue en "karin_audio_dsp").
 */
object AudiophileConfig {

    enum class Engine(val label: String) {
        OFF("OFF"),
        CURRENT("DSP actual"),
        AUDIOPHILE("Karin Audiophile DSP (Experimental)")
    }

    enum class ApPreset(val label: String) {
        PURE("Pure"),
        REFERENCE("Reference"),
        HIFI("Hi-Fi"),
        HEADPHONES("Headphones"),
        MOBILE_SPEAKER("Mobile Speaker"),
        TV_SPEAKER("TV Speaker")
    }

    enum class CrossfeedMode(val label: String) {
        OFF("OFF"),
        LOW("LOW"),
        MEDIUM("MEDIUM"),
        HIGH("HIGH")
    }

    enum class SpeakerMode(val label: String) {
        OFF("OFF"),
        LIGHT("LIGHT"),
        MEDIUM("MEDIUM"),
        STRONG("STRONG");

        /** Curva de voicing por nivel (para AudiophileSpeaker). */
        internal data class Curve(val bassDb: Float, val bodyDb: Float, val presenceDb: Float, val smoothDb: Float)

        internal fun curve(): Curve = when (this) {
            OFF -> Curve(0f, 0f, 0f, 0f)
            LIGHT -> Curve(3f, 1.5f, 2f, -1.5f)
            MEDIUM -> Curve(5f, 2.5f, 3f, -2.5f)
            STRONG -> Curve(7f, 3.5f, 4.5f, -4f)
        }
    }

    /** Banda EQ audiophile: peaking/shelf/corte/notch, enabled individual. */
    data class Band(
        val freqHz: Float,
        val gainDb: Float = 0f,
        val q: Float = 0.707f,
        val kind: BiquadFilter.Kind = BiquadFilter.Kind.PEAKING,
        val enabled: Boolean = false
    )

    data class Params(
        val preset: ApPreset = ApPreset.PURE,
        val autoHeadroom: Boolean = true,
        val eqEnabled: Boolean = true,
        val bands: List<Band> = defaultBands(),
        val loudnessEnabled: Boolean = false,
        val loudnessTargetLufs: Float = -16f,
        val dynamicsEnabled: Boolean = false,
        val bassExtEnabled: Boolean = false,
        val bassAmount: Float = 0.35f,
        val bassFreqHz: Float = 90f,
        val bassHarmonicMix: Float = 0.4f,
        val trueBassEnabled: Boolean = false,
        val trueBassLevel: Float = 0.5f,
        val transientEnabled: Boolean = false,
        val transientAmount: Float = 0.25f,
        val transientAttack: Float = 0.5f,
        val transientRelease: Float = 0.5f,
        val harmonicEnabled: Boolean = false,
        val harmonicAmount: Float = 0.15f,
        val harmonicDrive: Float = 0.3f,
        val harmonicFreqHz: Float = 3500f,
        val harmonicMix: Float = 0.5f,
        val crossfeed: CrossfeedMode = CrossfeedMode.OFF,
        val speakerMode: SpeakerMode = SpeakerMode.OFF,
        val truePeakCeilingDb: Float = -1.0f,
        val outputGainDb: Float = 0f,
        val ditherWhenNeeded: Boolean = true
    ) {
        /** Ganancia (lineal) del preamp de headroom si está activo. */
        fun headroomPreampDb(): Float {
            if (!autoHeadroom) return 0f
            var maxBoost = 0f
            if (eqEnabled) {
                for (b in bands) {
                    if (b.enabled && b.gainDb > maxBoost) maxBoost = b.gainDb
                }
            }
            return if (maxBoost > 0f) -maxBoost else 0f
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Params) return false
            return preset == other.preset && autoHeadroom == other.autoHeadroom &&
                eqEnabled == other.eqEnabled && bands == other.bands &&
                loudnessEnabled == other.loudnessEnabled &&
                loudnessTargetLufs == other.loudnessTargetLufs &&
                dynamicsEnabled == other.dynamicsEnabled &&
                bassExtEnabled == other.bassExtEnabled && bassAmount == other.bassAmount &&
                bassFreqHz == other.bassFreqHz && bassHarmonicMix == other.bassHarmonicMix &&
                trueBassEnabled == other.trueBassEnabled && trueBassLevel == other.trueBassLevel &&
                transientEnabled == other.transientEnabled && transientAmount == other.transientAmount &&
                transientAttack == other.transientAttack && transientRelease == other.transientRelease &&
                harmonicEnabled == other.harmonicEnabled && harmonicAmount == other.harmonicAmount &&
                harmonicDrive == other.harmonicDrive && harmonicFreqHz == other.harmonicFreqHz &&
                harmonicMix == other.harmonicMix && crossfeed == other.crossfeed &&
                speakerMode == other.speakerMode &&
                truePeakCeilingDb == other.truePeakCeilingDb && outputGainDb == other.outputGainDb &&
                ditherWhenNeeded == other.ditherWhenNeeded
        }

        override fun hashCode(): Int {
            var h = preset.hashCode()
            h = 31 * h + autoHeadroom.hashCode()
            h = 31 * h + eqEnabled.hashCode()
            h = 31 * h + bands.hashCode()
            h = 31 * h + loudnessEnabled.hashCode()
            h = 31 * h + loudnessTargetLufs.hashCode()
            h = 31 * h + dynamicsEnabled.hashCode()
            h = 31 * h + bassExtEnabled.hashCode()
            h = 31 * h + crossfeed.hashCode()
            h = 31 * h + truePeakCeilingDb.hashCode()
            h = 31 * h + outputGainDb.hashCode()
            return h
        }

        companion object {
            /** 6 bandas por defecto: HP, low-shelf, 2 peaking, high-shelf, LP. */
            fun defaultBands(): List<Band> = listOf(
                Band(60f, 0f, 0.707f, BiquadFilter.Kind.LOWSHELF, false),
                Band(200f, 0f, 1.0f, BiquadFilter.Kind.PEAKING, false),
                Band(1000f, 0f, 1.0f, BiquadFilter.Kind.PEAKING, false),
                Band(4000f, 0f, 1.0f, BiquadFilter.Kind.PEAKING, false),
                Band(10000f, 0f, 0.707f, BiquadFilter.Kind.HIGHSHELF, false),
                Band(16000f, 0f, 0.707f, BiquadFilter.Kind.HIGHPASS, false)
            )
        }
    }

    private const val PREF_NAME = "karin_audiophile"
    private const val KEY_ENGINE = "ap_engine"
    private const val KEY_PRESET = "ap_preset"
    private const val KEY_AB_BYPASS = "ap_ab_bypass"
    private const val KEY_AUTO_HEADROOM = "ap_auto_headroom"
    private const val KEY_EQ_ENABLED = "ap_eq_enabled"
    private const val KEY_EQ_BANDS = "ap_eq_bands"
    private const val KEY_LOUD_ENABLED = "ap_loud_enabled"
    private const val KEY_LOUD_TARGET = "ap_loud_target"
    private const val KEY_DYN_ENABLED = "ap_dyn_enabled"
    private const val KEY_BASS_ENABLED = "ap_bass_enabled"
    private const val KEY_BASS_AMOUNT = "ap_bass_amount"
    private const val KEY_BASS_FREQ = "ap_bass_freq"
    private const val KEY_BASS_HARM = "ap_bass_harm"
    private const val KEY_TRUEBASS_ENABLED = "ap_truebass_enabled"
    private const val KEY_TRUEBASS_LEVEL = "ap_truebass_level"
    private const val KEY_TRANS_ENABLED = "ap_trans_enabled"
    private const val KEY_TRANS_AMOUNT = "ap_trans_amount"
    private const val KEY_HARM_ENABLED = "ap_harm_enabled"
    private const val KEY_HARM_AMOUNT = "ap_harm_amount"
    private const val KEY_HARM_DRIVE = "ap_harm_drive"
    private const val KEY_HARM_FREQ = "ap_harm_freq"
    private const val KEY_HARM_MIX = "ap_harm_mix"
    private const val KEY_CROSSFEED = "ap_crossfeed"
    private const val KEY_SPEAKER = "ap_speaker"
    private const val KEY_TP_CEILING = "ap_tp_ceiling"
    private const val KEY_OUT_GAIN = "ap_out_gain"
    private const val KEY_DITHER = "ap_dither"

    @Volatile private var prefs: SharedPreferences? = null
    private val cacheGen = AtomicLong(0)
    @Volatile private var cached: Params? = null
    @Volatile private var cachedGen = -1L
    @Volatile private var abBypass = false
    @Volatile private var engine = Engine.AUDIOPHILE

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs = p
        engine = runCatching { Engine.valueOf(p.getString(KEY_ENGINE, Engine.AUDIOPHILE.name)!!) }
            .getOrDefault(Engine.AUDIOPHILE)
        abBypass = p.getBoolean(KEY_AB_BYPASS, false)
        p.registerOnSharedPreferenceChangeListener { _, key ->
            if (key != null) {
                cacheGen.incrementAndGet()
                if (key == KEY_ENGINE) {
                    engine = runCatching { Engine.valueOf(p.getString(KEY_ENGINE, Engine.AUDIOPHILE.name)!!) }
                        .getOrDefault(Engine.AUDIOPHILE)
                }
                if (key == KEY_AB_BYPASS) abBypass = p.getBoolean(KEY_AB_BYPASS, false)
            }
        }
    }

    /** Engine leído en el hilo de audio (volatile, sin prefs por muestra). */
    fun engine(): Engine = engine
    fun setEngine(e: Engine) {
        prefs?.edit()?.putString(KEY_ENGINE, e.name)?.apply()
        engine = e
    }

    fun isAbBypass(): Boolean = abBypass
    fun setAbBypass(on: Boolean) {
        prefs?.edit()?.putBoolean(KEY_AB_BYPASS, on)?.apply()
        abBypass = on
    }

    fun preset(): ApPreset {
        val p = prefs ?: return ApPreset.PURE
        return runCatching { ApPreset.valueOf(p.getString(KEY_PRESET, ApPreset.PURE.name)!!) }
            .getOrDefault(ApPreset.PURE)
    }

    fun setPreset(p: ApPreset) {
        prefs?.edit()?.putString(KEY_PRESET, p.name)?.apply()
        // Aplicar composición del preset en los toggles/params
        val base = presetParams(p)
        saveBool(KEY_AUTO_HEADROOM, base.autoHeadroom)
        saveBool(KEY_EQ_ENABLED, base.eqEnabled)
        saveBool(KEY_LOUD_ENABLED, base.loudnessEnabled)
        saveFloat(KEY_LOUD_TARGET, base.loudnessTargetLufs)
        saveBool(KEY_DYN_ENABLED, base.dynamicsEnabled)
        saveBool(KEY_BASS_ENABLED, base.bassExtEnabled)
        saveFloat(KEY_BASS_AMOUNT, base.bassAmount)
        saveBool(KEY_TRUEBASS_ENABLED, base.trueBassEnabled)
        saveFloat(KEY_TRUEBASS_LEVEL, base.trueBassLevel)
        saveBool(KEY_TRANS_ENABLED, base.transientEnabled)
        saveFloat(KEY_TRANS_AMOUNT, base.transientAmount)
        saveBool(KEY_HARM_ENABLED, base.harmonicEnabled)
        saveFloat(KEY_HARM_AMOUNT, base.harmonicAmount)
        prefs?.edit()?.putInt(KEY_CROSSFEED, base.crossfeed.ordinal)?.apply()
        prefs?.edit()?.putInt(KEY_SPEAKER, base.speakerMode.ordinal)?.apply()
        cacheGen.incrementAndGet()
    }

    /** Params agregados desde prefs (con cache por generation). */
    fun params(): Params {
        val p = prefs ?: return Params()
        val gen = cacheGen.get()
        val c = cached
        if (c != null && cachedGen == gen) return c
        val bands = parseBands(p.getString(KEY_EQ_BANDS, null)) ?: Params.defaultBands()
        val out = Params(
            preset = preset(),
            autoHeadroom = p.getBoolean(KEY_AUTO_HEADROOM, true),
            eqEnabled = p.getBoolean(KEY_EQ_ENABLED, true),
            bands = bands,
            loudnessEnabled = p.getBoolean(KEY_LOUD_ENABLED, false),
            loudnessTargetLufs = p.getFloat(KEY_LOUD_TARGET, -16f),
            dynamicsEnabled = p.getBoolean(KEY_DYN_ENABLED, false),
            bassExtEnabled = p.getBoolean(KEY_BASS_ENABLED, false),
            bassAmount = p.getFloat(KEY_BASS_AMOUNT, 0.35f),
            bassFreqHz = p.getFloat(KEY_BASS_FREQ, 90f),
            bassHarmonicMix = p.getFloat(KEY_BASS_HARM, 0.4f),
            trueBassEnabled = p.getBoolean(KEY_TRUEBASS_ENABLED, false),
            trueBassLevel = p.getFloat(KEY_TRUEBASS_LEVEL, 0.5f),
            transientEnabled = p.getBoolean(KEY_TRANS_ENABLED, false),
            transientAmount = p.getFloat(KEY_TRANS_AMOUNT, 0.25f),
            transientAttack = p.getFloat("ap_trans_atk", 0.5f),
            transientRelease = p.getFloat("ap_trans_rel", 0.5f),
            harmonicEnabled = p.getBoolean(KEY_HARM_ENABLED, false),
            harmonicAmount = p.getFloat(KEY_HARM_AMOUNT, 0.15f),
            harmonicDrive = p.getFloat(KEY_HARM_DRIVE, 0.3f),
            harmonicFreqHz = p.getFloat(KEY_HARM_FREQ, 3500f),
            harmonicMix = p.getFloat(KEY_HARM_MIX, 0.5f),
            crossfeed = CrossfeedMode.entries.getOrElse(
                p.getInt(KEY_CROSSFEED, 0)
            ) { CrossfeedMode.OFF },
            speakerMode = SpeakerMode.entries.getOrElse(
                p.getInt(KEY_SPEAKER, 0)
            ) { SpeakerMode.OFF },
            truePeakCeilingDb = p.getFloat(KEY_TP_CEILING, -1.0f),
            outputGainDb = p.getFloat(KEY_OUT_GAIN, 0f),
            ditherWhenNeeded = p.getBoolean(KEY_DITHER, true)
        )
        cached = out
        cachedGen = gen
        return out
    }

    fun setBands(bands: List<Band>) {
        prefs?.edit()?.putString(KEY_EQ_BANDS, serializeBands(bands))?.apply()
        cacheGen.incrementAndGet()
    }

    fun setAutoHeadroom(on: Boolean) = saveBool(KEY_AUTO_HEADROOM, on)
    fun setEqEnabled(on: Boolean) = saveBool(KEY_EQ_ENABLED, on)
    fun setLoudnessEnabled(on: Boolean) = saveBool(KEY_LOUD_ENABLED, on)
    fun setLoudnessTarget(lufs: Float) = saveFloat(KEY_LOUD_TARGET, lufs)
    fun setDynamicsEnabled(on: Boolean) = saveBool(KEY_DYN_ENABLED, on)
    fun setBassEnabled(on: Boolean) = saveBool(KEY_BASS_ENABLED, on)
    fun setBassAmount(v: Float) = saveFloat(KEY_BASS_AMOUNT, v.coerceIn(0f, 1f))
    fun setTrueBassEnabled(on: Boolean) = saveBool(KEY_TRUEBASS_ENABLED, on)
    fun setTrueBassLevel(v: Float) = saveFloat(KEY_TRUEBASS_LEVEL, v.coerceIn(0f, 1f))
    fun setTransientEnabled(on: Boolean) = saveBool(KEY_TRANS_ENABLED, on)
    fun setTransientAmount(v: Float) = saveFloat(KEY_TRANS_AMOUNT, v.coerceIn(0f, 1f))
    fun setHarmonicEnabled(on: Boolean) = saveBool(KEY_HARM_ENABLED, on)
    fun setHarmonicAmount(v: Float) = saveFloat(KEY_HARM_AMOUNT, v.coerceIn(0f, 1f))
    fun setCrossfeed(m: CrossfeedMode) {
        prefs?.edit()?.putInt(KEY_CROSSFEED, m.ordinal)?.apply()
        cacheGen.incrementAndGet()
    }
    fun setSpeakerMode(m: SpeakerMode) {
        prefs?.edit()?.putInt(KEY_SPEAKER, m.ordinal)?.apply()
        cacheGen.incrementAndGet()
    }
    fun setTruePeakCeiling(db: Float) = saveFloat(KEY_TP_CEILING, db.coerceIn(-3f, -0.1f))
    fun setOutputGainDb(db: Float) = saveFloat(KEY_OUT_GAIN, db.coerceIn(-12f, 12f))

    /** Composición de cada preset (solo los 5 pedidos). */
    fun presetParams(p: ApPreset): Params = when (p) {
        ApPreset.PURE -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = false, dynamicsEnabled = false,
            bassExtEnabled = false, harmonicEnabled = false,
            transientEnabled = false, crossfeed = CrossfeedMode.OFF,
            truePeakCeilingDb = -1.0f, outputGainDb = 0f
        )
        ApPreset.REFERENCE -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = true, loudnessTargetLufs = -16f,
            dynamicsEnabled = false, bassExtEnabled = false,
            harmonicEnabled = false, transientEnabled = false,
            crossfeed = CrossfeedMode.OFF, truePeakCeilingDb = -1.0f
        )
        ApPreset.HIFI -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = true, loudnessTargetLufs = -16f,
            dynamicsEnabled = true, bassExtEnabled = false,
            harmonicEnabled = false, transientEnabled = false,
            crossfeed = CrossfeedMode.LOW, truePeakCeilingDb = -1.0f
        )
        ApPreset.HEADPHONES -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = false, dynamicsEnabled = false,
            bassExtEnabled = false, harmonicEnabled = false,
            transientEnabled = false, crossfeed = CrossfeedMode.MEDIUM,
            truePeakCeilingDb = -1.0f
        )
        ApPreset.MOBILE_SPEAKER -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = true, loudnessTargetLufs = -14f,
            dynamicsEnabled = false, bassExtEnabled = true,
            bassAmount = 0.50f, trueBassEnabled = true, trueBassLevel = 0.86f,
            harmonicEnabled = false,
            transientEnabled = false, crossfeed = CrossfeedMode.OFF,
            speakerMode = SpeakerMode.MEDIUM,
            truePeakCeilingDb = -1.5f
        )
        ApPreset.TV_SPEAKER -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = true, loudnessTargetLufs = -14f,
            dynamicsEnabled = true, bassExtEnabled = true,
            bassAmount = 0.55f, trueBassEnabled = true, trueBassLevel = 0.9f,
            harmonicEnabled = true,
            harmonicAmount = 0.2f, transientEnabled = false,
            crossfeed = CrossfeedMode.OFF, speakerMode = SpeakerMode.STRONG,
            truePeakCeilingDb = -1.5f
        )
    }

    private fun saveBool(key: String, v: Boolean) {
        prefs?.edit()?.putBoolean(key, v)?.apply()
        cacheGen.incrementAndGet()
    }

    private fun saveFloat(key: String, v: Float) {
        prefs?.edit()?.putFloat(key, v)?.apply()
        cacheGen.incrementAndGet()
    }

    private fun serializeBands(bands: List<Band>): String =
        bands.joinToString(";") {
            "${it.freqHz},${it.gainDb},${it.q},${it.kind.ordinal},${if (it.enabled) 1 else 0}"
        }

    private fun parseBands(raw: String?): List<Band>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            raw.split(";").map { s ->
                val p = s.split(",")
                Band(
                    freqHz = p[0].toFloat(),
                    gainDb = p[1].toFloat(),
                    q = p[2].toFloat(),
                    kind = BiquadFilter.Kind.entries.getOrElse(p[3].toInt()) { BiquadFilter.Kind.PEAKING },
                    enabled = p[4] == "1"
                )
            }
        }.getOrNull()
    }

    /** Lineal de dB. */
    fun dbToLin(db: Float): Double = 10.0.pow(db / 20.0)
}
