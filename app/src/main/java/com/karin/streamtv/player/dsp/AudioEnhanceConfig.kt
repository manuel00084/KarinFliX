package com.karin.streamtv.player.dsp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.text.lowercase

object AudioEnhanceConfig {

    enum class Preset(val label: String) {
        OFF("Apagado (sin DSP)"),
        ANIME("Anime"),
        SURROUND_ENVOLVENTE("Surround Envolvente"),
        BASS_BOOST("Bass Boost"),
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
        STUDIO("Estudio / Sala húmeda")
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
        val fieldSurround: Float = 0f,  // 0..1.0 (Haas + panorama + bass centrado)
        val exciterAmount: Float = 0f,  // 0..1.0 (armónicos agudos)
        val harmonicBass: Float = 0f,   // 0..1.0 (saturation graves)
        val compression: Float = 0f,    // 0..1.0 (dynamic range)
        val reverbMix: Float = 0f,      // 0..0.5
        val masterGain: Float = 1.0f,   // 0.5..4.0 (el limiter true-peak evita recorte)
        val irType: IrPreset = IrPreset.NONE,
        val irMix: Float = 0f,          // 0..1.0 (wet del convolver)
        val useSystemSpatializer: Boolean = true, // delegar al Spatializer del sistema (API 33+)
        val tubeDrive: Float = 0f,      // 0..1.0 (saturación analógica)
        val dynamicBass: Boolean = true, // bass adaptativo (envolvente)
        val loudnessComp: Boolean = true, // compensación de sonoridad (sube graves/agudos a volumen bajo)
        val surfaceResonance: Boolean = true, // resonancia de caja/superficie (TV en rack)
        val speechClarity: Boolean = true, // realce dinámico de voz (diálogos claros)
        val parametricEq: List<ParamBand>? = null, // curvas AutoEQ paramétricas
        val eq10: FloatArray? = null,   // 10 bandas ISO dB (31..16k); null = derivar del preset
        val subAnchor: Float = 0f,       // 0..1: ancla los graves al centro (MonoSub) → bajo definido
        val beatBoost: Float = 0f,       // 0..1: realce del golpe percuativo del bajo (kick distinto del muro)
        val transientPunch: Float = 0f,  // 0..1: resalte de ataques (cada instrumento emerge)
        val spectralClarity: Float = 0f, // 0..1: de-enmascarador dinámico (levantar lo tapado/domar lo que tapa)
        val explosionDucking: Float = 0f, // 0..1: despeja 150-700 Hz cuando golpea un sub (explosión/kick)
        val loudnessNorm: Float = 0.5f,  // 0..1: nivelación EBU R128 (volumen nivelado entre contenidos)
        val ddc: Boolean = false,        // corrección por medición del altavoz (DRC por IR)
        val useHrtf: Boolean = false,    // HRTF medido/real para binaural (loader + ITD Woodworth)
        val rearDelayMs: Float = 20f,    // 0..30: retardo de canal trasero (alineación/espaciado de ambiente)
        val rearPhaseInvert: Boolean = false // invierte la fase de los canales traseros/laterales
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
                parametricEq == other.parametricEq &&
                eq10.contentEquals(other.eq10) &&
                subAnchor == other.subAnchor &&
                beatBoost == other.beatBoost &&
                transientPunch == other.transientPunch &&
                spectralClarity == other.spectralClarity &&
                explosionDucking == other.explosionDucking &&
                loudnessNorm == other.loudnessNorm &&
                ddc == other.ddc &&
                useHrtf == other.useHrtf &&
                rearDelayMs == other.rearDelayMs &&
                rearPhaseInvert == other.rearPhaseInvert
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
            h = 31 * h + (parametricEq?.hashCode() ?: 0)
            h = 31 * h + (eq10?.contentHashCode() ?: 0)
            h = 31 * h + subAnchor.hashCode()
            h = 31 * h + beatBoost.hashCode()
            h = 31 * h + transientPunch.hashCode()
            h = 31 * h + spectralClarity.hashCode()
            h = 31 * h + explosionDucking.hashCode()
            h = 31 * h + loudnessNorm.hashCode()
            h = 31 * h + ddc.hashCode()
            h = 31 * h + useHrtf.hashCode()
            h = 31 * h + rearDelayMs.hashCode()
            h = 31 * h + rearPhaseInvert.hashCode()
            return h
        }
        fun withPreset(p: Preset): Params = when (p) {
            Preset.OFF -> Params(
                Preset.OFF, false, autoDevice = false,
                tubeDrive = 0f,
                dynamicBass = false,
                loudnessComp = false,
                surfaceResonance = false,
                speechClarity = false,
                useSystemSpatializer = false,
                loudnessNorm = 0f
            )
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
                masterGain = 1.08f,     // volumen pleno: el true-peak del limiter protege
                irType = IrPreset.SPEAKER_CAB,
                irMix = 0.25f,          // cuerpo de cabina para driver chico
                tubeDrive = 0.06f,
                dynamicBass = true,
                loudnessComp = true,
                surfaceResonance = true,
                speechClarity = true,
                useSystemSpatializer = true,
                // FX de alta resolución: OST/kicks anclados, golpe y ataques
                // definidos, de-enmascarado de voz y despeje cuando hay FX.
                subAnchor = 0.5f,
                beatBoost = 0.45f,
                transientPunch = 0.5f,
                spectralClarity = 0.4f,
                explosionDucking = 0.5f,
                loudnessNorm = 0.5f     // nivelación media (susurros ↔ gritos)
            )
            // BASS_BOOST — Musical: graves ajustados, rápidos, armónicamente ricos
            Preset.BASS_BOOST -> Params(
                Preset.BASS_BOOST, true, autoDevice = false,
                bassGain = +3.0f,       // 80-150 Hz: "punch" musical
                trebleGain = +0.8f,     // compensa masking de graves
                subBassGain = +1.0f,    // sub controlado (no retumba)
                presenceGain = +1.8f,   // voz presente sobre el grave
                surroundWidth = 0.15f,
                fieldSurround = 0.05f,
                exciterAmount = 0.06f,  // claridad en ataque de bombo/bajo
                harmonicBass = 0.3f,    // síntesis armónica: graves "más grandes"
                compression = 0.25f,    // dinámica musical preservada
                reverbMix = 0.0f,
                masterGain = 1.0f,      // uso completo del techo true-peak
                irType = IrPreset.ROOM,
                irMix = 0.08f,
                tubeDrive = 0.12f,
                dynamicBass = false,
                loudnessComp = false,
                surfaceResonance = false,
                speechClarity = false,
                useSystemSpatializer = false,
                // El preset de graves lleva los FX de golpe más fuertes:
                // kick/sub anclados al centro y separados del muro armónico.
                subAnchor = 0.85f,
                beatBoost = 0.85f,
                transientPunch = 0.6f,
                spectralClarity = 0.3f,
                explosionDucking = 0.4f,
                loudnessNorm = 0.4f     // preserva la dinámica musical
            )
            // DIALOGUE — Noticias/presentadores/podcasts/audiolibros: inteligibilidad
            Preset.DIALOGUE -> Params(
                Preset.DIALOGUE, true, autoDevice = false,
                bassGain = -3.0f,       // elimina rumble/musica de fondo
                trebleGain = +1.5f,     // aire/consonantes
                subBassGain = -4.0f,
                presenceGain = +6.0f,   // 900-6 kHz: zona crítica habla
                surroundWidth = 0.0f,   // mono perfecto: foco central
                fieldSurround = 0.0f,
                exciterAmount = 0.16f,  // nitidez consonantes
                harmonicBass = 0.0f,
                compression = 0.7f,     // nivelación fuerte: voz constante
                reverbMix = 0.0f,
                masterGain = 1.05f,     // compensación corte graves
                irType = IrPreset.NONE,
                irMix = 0.0f,
                tubeDrive = 0f,
                dynamicBass = false,
                loudnessComp = true,
                surfaceResonance = false,
                speechClarity = true,
                useSystemSpatializer = false,
                // Habla: FX orientados a inteligibilidad — ataques de consonantes,
                // de-enmascarado de voz y despeje de FX fuertes bajo el diálogo.
                subAnchor = 0.2f,
                beatBoost = 0f,
                transientPunch = 0.4f,
                spectralClarity = 0.6f,
                explosionDucking = 0.7f,
                loudnessNorm = 0.7f     // nivelación fuerte: voz siempre constante
            )
            // MUSIC — Musical: cuerpo, detalle y estéreo natural
            Preset.MUSIC -> Params(
                Preset.MUSIC, true, autoDevice = false,
                bassGain = +2.6f,       // punch del bajo/boom, cuerpo golpe
                trebleGain = +1.4f,     // aire 8-16 kHz sin sibilancias
                subBassGain = +1.8f,    // extensión grave audible
                presenceGain = +1.3f,   // cada instrumento definido en medios
                surroundWidth = 0.55f,  // imagen estéreo más amplia y natural
                fieldSurround = 0.35f,  // profundidad Haas en pads/traseros
                exciterAmount = 0.18f,  // armónicos: separa y define timbres
                harmonicBass = 0.32f,   // TruBass: el beat se siente en TV/cel
                compression = 0.22f,    // nivelación leve: todo audible sin aplastar
                reverbMix = 0.06f,      // sala ROOM presente
                masterGain = 1.0f,      // uso completo del techo true-peak
                irType = IrPreset.ROOM,
                irMix = 0.16f,
                tubeDrive = 0.10f,
                dynamicBass = true,
                loudnessComp = true,
                surfaceResonance = false,
                speechClarity = true,
                useSystemSpatializer = true,
                // Investigación "alta calidad": graves anclados al centro (Sub Anchor),
                // kick percusivo separado (BeatBoost), ataques que emergen (TransientPunch),
                // de-enmascarado espectral (SpectralClarity) y despeje de la banda media
                // cuando golpea el sub (ExplosionDucking). Valores pensados para que la
                // mezcla suene "cristalina" sin perder el calor de la música.
                subAnchor = 0.8f,
                beatBoost = 0.7f,
                transientPunch = 0.6f,
                spectralClarity = 0.5f,
                explosionDucking = 0.6f,
                loudnessNorm = 0.3f     // preserva la dinámica de la masterización
            )
            // SPEAKER / TRUE MAXBASS — Bocina chica: máximo grave percibido
            Preset.SPEAKER -> Params(
                Preset.SPEAKER, true, autoDevice = false,
                bassGain = +3.0f,       // medio-bajo: donde el driver rinde
                trebleGain = -0.3f,     // doma resonancias metálicas
                subBassGain = -5.0f,    // elimina sub real (distorsiona driver)
                presenceGain = +1.8f,   // claridad vocal
                surroundWidth = 0.0f,
                fieldSurround = 0.0f,
                exciterAmount = 0.04f,
                harmonicBass = 0.75f,   // síntesis armónica fuerte: percibido "max bass"
                compression = 0.35f,    // protege driver, pero no aplasta el golpe
                reverbMix = 0.0f,
                masterGain = 0.97f,     // speech aún más audible: true-peak protege
                irType = IrPreset.SPEAKER_CAB,
                irMix = 0.28f,          // cuerpo de cabina sin empujar el pico
                tubeDrive = 0.10f,
                dynamicBass = true,
                loudnessComp = true,
                surfaceResonance = true,
                speechClarity = true,
                useSystemSpatializer = false,
                // El kick y el sub percibido se anclan al centro y el golpe se
                // separa del muro armónico: el "True MaxBass" se SIENTE.
                subAnchor = 0.9f,
                beatBoost = 0.8f,
                transientPunch = 0.5f,
                spectralClarity = 0.4f,
                explosionDucking = 0.5f,
                loudnessNorm = 0.5f
            )
            Preset.SURROUND_ENVOLVENTE -> Params(
                Preset.SURROUND_ENVOLVENTE, true, autoDevice = false,
                // Fusión: sala/ambiente de Envolvente + direccionalidad de
                // Surround + crossfeed binaural → cualquier fuente (2.0 ó 5.1,
                // video o música) suena a surround de verdad: avión de atrás,
                // disparo lateral, música al frente.
                bassGain = +4.0f,       // impacto de Envolvente (punch)
                trebleGain = +1.0f,     // apertura espacial
                subBassGain = +2.5f,    // sub visible en 5.1/2.1 (recortado en TV/cel)
                presenceGain = +1.5f,   // diálogos/front limpios
                surroundWidth = 1.0f,   // virtualización lateral al máximo
                fieldSurround = 0.9f,   // campo Haas muy profundo: traseros fantasma
                exciterAmount = 0.15f,  // detalle espacial en FX
                harmonicBass = 0.45f,   // TruBass medio-fuerte
                compression = 0.3f,     // protege sin apagar la dinámica direccional
                reverbMix = 0.17f,      // sala que envuelve
                masterGain = 1.02f,     // volumen pleno con protección true-peak
                irType = IrPreset.CROSSFEED,  // binaural: convierte 5.1→2ch en audífonos
                irMix = 0.25f,
                tubeDrive = 0.05f,
                dynamicBass = true,
                loudnessComp = true,
                surfaceResonance = false,
                speechClarity = true,
                useSystemSpatializer = true,
                subAnchor = 0.6f,
                beatBoost = 0.6f,
                transientPunch = 0.5f,
                spectralClarity = 0.5f,
                explosionDucking = 0.6f,
                loudnessNorm = 0.4f     // contenido mixto (pelis/series): nivelación media
            )
        }
    }

    // Frecuencias ISO de la EQ de 10 bandas (Hz)
    val EQ_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    // EQ reducida para bocinas humildes (TV/celular): 5 bandas al centro del
    // rango que el driver sí reproduce. Los datos de la EQ de 10 bandas se
    // conservan intactos; aquí solo se muestrean (interpolación logarítmica)
    // los 5 puntos que importan, para no apilar 10 Biquads por muestra.
    val CHEAP_FREQS = floatArrayOf(60f, 250f, 1000f, 4000f, 12000f)

    // Convierte (por interpolación logarítmica en frecuencia) las ganancias de
    // 10 bandas a los 5 puntos de CHEAP_FREQS. Devuelve null para usar 10.
    fun cheapGains5(gains10: FloatArray): FloatArray? {
        if (gains10.size != EQ_FREQS.size) return null
        val out = FloatArray(CHEAP_FREQS.size)
        for (k in CHEAP_FREQS.indices) {
            val f = CHEAP_FREQS[k]
            var lo = 0
            var hi = EQ_FREQS.lastIndex
            while (lo < hi - 1) {
                val mid = (lo + hi) ushr 1
                if (EQ_FREQS[mid] <= f) lo = mid else hi = mid
            }
            val fLo = EQ_FREQS[lo]
            val fHi = EQ_FREQS[hi]
            val t = if (fHi <= fLo) 0f else
                (Math.log(f / fLo.toDouble()) / Math.log(fHi / fLo.toDouble())).toFloat()
            out[k] = gains10[lo] + (gains10[hi] - gains10[lo]) * t.coerceIn(0f, 1f)
        }
        return out
    }

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
        HeadphoneProfile("ANC Over-ear Premium", floatArrayOf(0f, 0f, -3f, -2f, 0f, 1f, 2f, 1.5f, -1f, -1f)),
        HeadphoneProfile("Over-ear Cómodos", floatArrayOf(1f, 1f, 0f, 1f, 2f, 3f, 3f, 3f, 3f, 4f)),
        HeadphoneProfile("Open-back Neutros", floatArrayOf(4f, 3f, 2f, 0f, 0f, 0f, -2f, -1f, 0f, 0f)),
        HeadphoneProfile("Open-back Cálidos", floatArrayOf(5f, 4f, 2f, 1f, 0f, 1f, 2f, 1f, 0f, -1f)),
        HeadphoneProfile("Closed-back Monitor", floatArrayOf(-4f, -4f, -3f, -1f, 2f, 3f, 3f, 2f, -4f, -3f)),
        HeadphoneProfile("Open-back Brillantes", floatArrayOf(-3f, -3f, -2f, 0f, 1f, 2f, 1f, 0f, -4f, -4f)),
        HeadphoneProfile("Closed-back Firma V", floatArrayOf(-3f, -2f, -1f, 0f, 1f, 2f, 3f, 2f, -2f, -2f)),
        HeadphoneProfile("TWS Boosts", floatArrayOf(1f, 1f, 1f, 1f, 1f, 0f, 0f, -1f, -1f, -1f)),
        HeadphoneProfile("TWS Neutros", floatArrayOf(-2f, -1f, 0f, 1f, 2f, 3f, 2f, 1f, -2f, -2f)),
        HeadphoneProfile("IEM V-shape", floatArrayOf(-3f, -2f, 0f, 1f, 2f, 3f, 3f, 2f, -3f, -4f))
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
// Nivelación de sonoridad EBU R128 (0..1; 0 = fuera)
private const val KEY_LOUDNESS_NORM = "dsp_loudness_norm"
// Corrección por medición del altavoz (DRC / DDC por IR)
private const val KEY_DDC = "dsp_ddc"
// HRTF medido/real para el renderizado binaural
private const val KEY_HRTF = "dsp_hrtf"
// Retardo de canal trasero (ms) e inversión de fase trasera
private const val KEY_REAR_DELAY = "dsp_rear_delay"
private const val KEY_REAR_PHASE = "dsp_rear_phase"
// Asistencia de audición (superpuesta en params(); dialogo de asistencia)
private const val KEY_AUD_SPEECH = "dsp_aud_speech"
private const val KEY_AUD_LOSS = "dsp_aud_loss"
    // Capa de usuario "Ajuste rápido": deltas sobre el preset base (no se pierden
    // al cambiar de perfil).
    private const val KEY_QA_BASS = "dsp_qa_bass"
    private const val KEY_QA_TREBLE = "dsp_qa_treble"
    private const val KEY_QA_PRESENCE = "dsp_qa_presence"
    private const val KEY_QA_SURROUND = "dsp_qa_surround"
    private const val KEY_QA_MASTER = "dsp_qa_master"

    /** Prefijo que identifica en [setHeadphone] los modelos de [AutoEqCatalog] (medición real). */
    const val AUTO_EQ_PREFIX = "AutoEQ: "

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
        appVolume = v.coerceIn(0.05f, 1f)
        refreshPlaybackVolume()
    }

    fun refreshPlaybackVolume() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        val frac = (cur.toFloat() / max).coerceIn(0.02f, 1f)
        playbackVolume = (appVolume * frac).coerceIn(0.05f, 1f)
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var cachedParams: Params? = null

    // Generación del caché: sube en cada invalidación. params() solo escribe
    // el caché si nadie lo invalidó mientras construía (evita la carrera
    // listener vs hilo de audio de dos hilos escribiendo params distintos).
    private val cacheGen = AtomicLong(0)

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cacheGen.incrementAndGet()
        cachedParams = null
    }

    // El volumen del mando de la TV cambia fuera de params(); sin esto
    // playbackVolume se quedaba con el valor del arranque hasta que alguien
    // tocara el volumen de la app. (Las constantes son @hide en el SDK, pero
    // los valores string son estables desde hace años.)
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION" &&
                intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC
            ) {
                refreshPlaybackVolume()
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(prefsListener)
        }
        cachedParams = null
        refreshPlaybackVolume()
        try {
            appContext?.registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        } catch (t: Throwable) {
            // Algunos ROM restringen receivers: el fallback sigue siendo setAppVolume().
        }
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
    fun setMaster(v: Float) { prefs?.edit()?.putFloat(KEY_MASTER, v.coerceIn(0.5f, 4f))?.apply() }

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
            if (name.startsWith(AUTO_EQ_PREFIX)) e.remove(KEY_PARAMETRIC)
        }
        e.apply()
    }

    /** Perfil AutoEQ medido activo (si [headphone] apunta a [AutoEqCatalog]), o null. */
    fun autoEqProfile(): AutoEqCatalog.Profile? {
        val name = headphone() ?: return null
        if (!name.startsWith(AUTO_EQ_PREFIX)) return null
        return AutoEqCatalog.findByModel(name.removePrefix(AUTO_EQ_PREFIX))
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

/** Fuerza de nivelación de sonoridad EBU R128 (0 = desactivada). */
fun getLoudnessNorm(): Float = prefs?.getFloat(KEY_LOUDNESS_NORM, 0.5f) ?: 0.5f
fun setLoudnessNorm(v: Float) { prefs?.edit()?.putFloat(KEY_LOUDNESS_NORM, v.coerceIn(0f, 1f))?.apply() }
fun getDdc(): Boolean = prefs?.getBoolean(KEY_DDC, false) ?: false
fun setDdc(v: Boolean) { prefs?.edit()?.putBoolean(KEY_DDC, v)?.apply() }
fun getUseHrtf(): Boolean = prefs?.getBoolean(KEY_HRTF, false) ?: false
fun setUseHrtf(v: Boolean) { prefs?.edit()?.putBoolean(KEY_HRTF, v)?.apply() }
fun getRearDelayMs(): Float = prefs?.getFloat(KEY_REAR_DELAY, 20f) ?: 20f
fun setRearDelayMs(v: Float) { prefs?.edit()?.putFloat(KEY_REAR_DELAY, v.coerceIn(0f, 30f))?.apply() }
fun getRearPhaseInvert(): Boolean = prefs?.getBoolean(KEY_REAR_PHASE, false) ?: false
fun setRearPhaseInvert(v: Boolean) { prefs?.edit()?.putBoolean(KEY_REAR_PHASE, v)?.apply() }

/** Asistencia de audición: claridad de diálogo 0..1 (0 = apagado). */
fun getAudSpeech(): Float = ((prefs?.getInt(KEY_AUD_SPEECH, 0) ?: 0).coerceIn(0, 100)) / 100f
fun setAudSpeech(v: Float) { prefs?.edit()?.putInt(KEY_AUD_SPEECH, (v.coerceIn(0f, 1f) * 100).toInt())?.apply() }
/** Asistencia de audición: realce de agudos (presbicia) 0..1 (0 = apagado). */
fun getAudLoss(): Float = ((prefs?.getInt(KEY_AUD_LOSS, 0) ?: 0).coerceIn(0, 100)) / 100f
fun setAudLoss(v: Float) { prefs?.edit()?.putInt(KEY_AUD_LOSS, (v.coerceIn(0f, 1f) * 100).toInt())?.apply() }

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

    fun params(): Params {
        cachedParams?.let { return it }
        val g = cacheGen.get()
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
            parametricEq = getParametric(),
            eq10 = getEq10(),
            loudnessNorm = getLoudnessNorm(),
            ddc = getDdc(),
            useHrtf = getUseHrtf(),
            rearDelayMs = getRearDelayMs(),
            rearPhaseInvert = getRearPhaseInvert()
        )
        // Los FX del preset (subAnchor/beatBoost/transientPunch/spectralClarity/
        // explosionDucking) solo viven en withPreset(): sin este overlay
        // params() los dejaba siempre en 0 y el DSP no recibía la mejora.
        val fx = Params().withPreset(p.preset)
        val p0 = p.copy(
            subAnchor = fx.subAnchor,
            beatBoost = fx.beatBoost,
            transientPunch = fx.transientPunch,
            spectralClarity = fx.spectralClarity,
            explosionDucking = fx.explosionDucking
        )
        // Perfil AutoEQ medido: la curva paramétrica real reemplaza banda gráfica y
        // el preamp (headroom contra el clip) se aplica atenuando la ganancia master.
        val auto = autoEqProfile()
        val built0 = if (auto != null) {
            val lin = 10f.pow(auto.preampDb / 20f)
            p0.copy(
                parametricEq = auto.bands,
                eq10 = null,
                masterGain = (p0.masterGain * lin).coerceIn(0.15f, 4f)
            )
        } else p0
        // Asistencia de audición: va la ÚLTIMA para que gane sobre preset/AutoEQ.
        val sp = getAudSpeech()
        val lo = getAudLoss()
        val built = if (sp > 0f || lo > 0f) hearingAssist(built0, sp, lo) else built0
        if (cacheGen.get() == g) cachedParams = built
        return built
    }

    /**
     * Superposición de asistencia de audición sobre el preset activo:
     * - Claridad de diálogo (speech): sube presencia, recorta graves que tapan
     *   la voz, comprime el rango, despeja FX fuertes (ducking) y nivela R128.
     * - Pérdida de agudos / presbicia (loss): realce de treble/presencia y
     *   armónicos, más volumen extra (el limiter true-peak evita el recorte).
     * Asistencia práctica; no sustituye aparato auditivo.
     */
    private fun hearingAssist(p: Params, speech: Float, loss: Float): Params {
        var presence = p.presenceGain
        var treble = p.trebleGain
        var bass = p.bassGain
        var comp = p.compression
        var duck = p.explosionDucking
        var clarity = p.speechClarity
        var loud = p.loudnessNorm
        var exciter = p.exciterAmount
        var master = p.masterGain
        if (speech > 0f) {
            presence += 4.5f * speech
            bass -= 1.5f * speech
            comp = maxOf(comp, 0.5f + 0.3f * speech)
            duck = maxOf(duck, 0.7f * speech)
            clarity = true
            loud = maxOf(loud, 0.55f + 0.25f * speech)
        }
        if (loss > 0f) {
            treble += 3.5f * loss
            presence += 3f * loss
            exciter = maxOf(exciter, 0.15f + 0.2f * loss)
            master = (master * (1f + 0.35f * loss)).coerceIn(0.15f, 4f)
        }
        return p.copy(
            presenceGain = presence.coerceIn(-12f, 12f),
            trebleGain = treble.coerceIn(-12f, 12f),
            bassGain = bass.coerceIn(-12f, 12f),
            compression = comp.coerceIn(0f, 1f),
            explosionDucking = duck.coerceIn(0f, 1f),
            speechClarity = clarity,
            loudnessNorm = loud.coerceIn(0f, 1f),
            exciterAmount = exciter.coerceIn(0f, 1f),
            masterGain = master.coerceIn(0.15f, 4f),
        )
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
        setIrPreset(p.irType)
        setIrMix(p.irMix)
        setUseSystemSpatializer(p.useSystemSpatializer)
        setTube(p.tubeDrive)
        setDynamicBass(p.dynamicBass)
        setLoudnessComp(p.loudnessComp)
        setSurfaceResonance(p.surfaceResonance)
        setSpeechClarity(p.speechClarity)
        setLoudnessNorm(p.loudnessNorm)
        setDdc(p.ddc)
        setUseHrtf(p.useHrtf)
        setRearDelayMs(p.rearDelayMs)
        setRearPhaseInvert(p.rearPhaseInvert)
        setAutoDevice(p.autoDevice)
        // Con un perfil AutoEQ medido activo, master y bandas se derivan del catálogo
        // (no se persisten para que no se acumulen al recalcular en params()).
        if (autoEqProfile() != null) {
            setParametric(null)
            setMaster(1f)
        } else {
            setParametric(p.parametricEq)
            setMaster(p.masterGain)
        }
        setEq10(p.eq10)
        setHeadphone(null)
    }

    /** Preset base "puro" (sin la capa de Ajuste rápido). */
    fun presetBase(preset: Preset = preset()): Params = Params().withPreset(preset)

    fun getQuickAdjBass(): Float = prefs?.getFloat(KEY_QA_BASS, 0f) ?: 0f
    fun setQuickAdjBass(v: Float) { prefs?.edit()?.putFloat(KEY_QA_BASS, v)?.apply() }
    fun getQuickAdjTreble(): Float = prefs?.getFloat(KEY_QA_TREBLE, 0f) ?: 0f
    fun setQuickAdjTreble(v: Float) { prefs?.edit()?.putFloat(KEY_QA_TREBLE, v)?.apply() }
    fun getQuickAdjPresence(): Float = prefs?.getFloat(KEY_QA_PRESENCE, 0f) ?: 0f
    fun setQuickAdjPresence(v: Float) { prefs?.edit()?.putFloat(KEY_QA_PRESENCE, v)?.apply() }
    fun getQuickAdjSurround(): Float = prefs?.getFloat(KEY_QA_SURROUND, 0f) ?: 0f
    fun setQuickAdjSurround(v: Float) { prefs?.edit()?.putFloat(KEY_QA_SURROUND, v)?.apply() }
    fun getQuickAdjMaster(): Float = prefs?.getFloat(KEY_QA_MASTER, 0f) ?: 0f
    fun setQuickAdjMaster(v: Float) { prefs?.edit()?.putFloat(KEY_QA_MASTER, v)?.apply() }

    fun clearQuickAdjust() {
        prefs?.edit()
            ?.remove(KEY_QA_BASS)
            ?.remove(KEY_QA_TREBLE)
            ?.remove(KEY_QA_PRESENCE)
            ?.remove(KEY_QA_SURROUND)
            ?.remove(KEY_QA_MASTER)
            ?.apply()
    }

    /**
     * Aplica un preset como "carácter base" conservando la capa de usuario:
     * EQ de 10 bandas, EQ paramétrica, AutoEQ y los deltas de Ajuste rápido.
     */
    fun applyPreset(preset: Preset) {
        val base = Params().withPreset(preset)
        val eff = base.copy(
            bassGain = (base.bassGain + getQuickAdjBass()).coerceIn(-12f, 12f),
            trebleGain = (base.trebleGain + getQuickAdjTreble()).coerceIn(-12f, 12f),
            presenceGain = (base.presenceGain + getQuickAdjPresence()).coerceIn(-12f, 12f),
            surroundWidth = (base.surroundWidth + getQuickAdjSurround()).coerceIn(0f, 1.5f),
            masterGain = (base.masterGain + getQuickAdjMaster()).coerceIn(0.5f, 4f)
        )
        setPreset(eff.preset)
        setEnabled(eff.enabled)
        setAutoDevice(eff.autoDevice)
        setBass(eff.bassGain)
        setTreble(eff.trebleGain)
        setSubBass(eff.subBassGain)
        setPresence(eff.presenceGain)
        setSurround(eff.surroundWidth)
        setField(eff.fieldSurround)
        setExciter(eff.exciterAmount)
        setHarmbass(eff.harmonicBass)
        setCompression(eff.compression)
        setReverb(eff.reverbMix)
        setIrPreset(eff.irType)
        setIrMix(eff.irMix)
        setUseSystemSpatializer(eff.useSystemSpatializer)
        setTube(eff.tubeDrive)
        setDynamicBass(eff.dynamicBass)
        setLoudnessComp(eff.loudnessComp)
        setSurfaceResonance(eff.surfaceResonance)
        setSpeechClarity(eff.speechClarity)
        setLoudnessNorm(eff.loudnessNorm)
        setMaster(eff.masterGain)
        // eq10 / parametricEq / AutoEQ se preservan (no se tocan aquí).
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
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_HEARING_AID -> hp = true
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
                        AudioDeviceInfo.TYPE_HDMI_EARC,
                        AudioDeviceInfo.TYPE_AUX_LINE,
                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_ACCESSORY,
                        AudioDeviceInfo.TYPE_DOCK,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER,
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
        // En ENVOLVENTE el corte de sub es más suave (-0.5) y se refuerza el
        // VirtualBass para que el grave se perciba aunque el driver no baje.
        DeviceKind.TV_SPEAKER -> {
            val envolv = base.preset == Preset.SURROUND_ENVOLVENTE
            base.copy(
                trebleGain = (base.trebleGain - 0.3f).coerceIn(-12f, 12f),   // reduce resonancias metálicas
                subBassGain = (base.subBassGain - if (envolv) 0.5f else 1.5f).coerceIn(-12f, 12f),
                presenceGain = (base.presenceGain + 0.6f).coerceIn(-12f, 12f), // diálogos
                harmonicBass = (base.harmonicBass + if (envolv) 0.3f else 0.2f).coerceIn(0f, 1f),
                reverbMix = 0f,
                surfaceResonance = true  // activa resonancia de caja/mesa
            )
        }
        // Auriculares: respuesta plana, estéreo real, nearfield.
        // En ENVOLVENTE se conmuta a crossfeed binaural: el surround se oye
        // fuera de la cabeza (parlantes fantasma), no pegado al oído.
        DeviceKind.HEADPHONES -> {
            val envolv = base.preset == Preset.SURROUND_ENVOLVENTE
            base.copy(
                surroundWidth = (base.surroundWidth + 0.2f).coerceIn(0f, 1.5f), // abre escenario
                subBassGain = (base.subBassGain + 0.3f).coerceIn(-12f, 12f),   // extensión real
                trebleGain = (base.trebleGain + 0.2f).coerceIn(-12f, 12f),     // aire
                reverbMix = (base.reverbMix + 0.015f).coerceIn(0f, 0.5f),      // ambience natural
                fieldSurround = (base.fieldSurround + 0.1f).coerceAtMost(0.6f), // profundidad sin eco
                irType = if (envolv) IrPreset.CROSSFEED else base.irType,
                irMix = if (envolv) 0.25f else base.irMix
            )
        }
        // Soundbar/HT/bocinas dedicadas: drivers dedicados, subwoofer real,
        // placement fijo. En ENVOLVENTE se aprovecha el sub real, pero el
        // campo Haas se topea en 0.6: más alto se oía un slap/eco en el
        // retardo cruzado, sobre todo en 2.1 con crossovers de sub.
        DeviceKind.SOUNDBAR -> {
            val envolv = base.preset == Preset.SURROUND_ENVOLVENTE
            base.copy(
                subBassGain = (base.subBassGain + if (envolv) 0.8f else 0.5f).coerceIn(-12f, 12f),
                fieldSurround = (base.fieldSurround + if (envolv) 0.15f else 0.1f).coerceAtMost(0.6f),
                reverbMix = (base.reverbMix + 0.02f).coerceIn(0f, 0.5f),
                surroundWidth = (base.surroundWidth + if (envolv) 0.15f else 0.1f).coerceIn(0f, 1.5f)
            )
        }
    }
}
