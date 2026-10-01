package com.karin.streamtv.player.dsp.smartlite

import android.content.Context
import android.content.SharedPreferences
import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import com.karin.streamtv.player.dsp.AutoEqCatalog
import com.karin.streamtv.player.dsp.BiquadFilter
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/**
 * Preferencias y modo del DSP "Karin DSP Smart Lite".
 * Archivo propio ("karin_smartlite"): autónomo, sin dependencias del motor anterior.
 */
object SmartLiteConfig {

    enum class Engine(val label: String) {
        OFF("OFF"),
        SMART_LITE("DSP Smart Lite")
    }

    enum class SlPreset(val label: String) {
        AUTO("Automático"),
        PURE("Pure"),
        REFERENCE("Reference"),
        HIFI("Hi-Fi"),
        HEADPHONES("Headphones"),
        MOBILE_SPEAKER("Mobile Speaker"),
        TV_SPEAKER("TV Speaker"),
        CINEMA("Cinema"),
        ANIME("Anime"),
        POWER_BASS("Power Bass"),
        CLEAR_VOICE("Clear Voice"),
        MUSIC("Music")
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

        /** Curva de voicing por nivel (para SmartLiteSpeaker). */
        internal data class Curve(val bassDb: Float, val bodyDb: Float, val presenceDb: Float, val smoothDb: Float)

        internal fun curve(): Curve = when (this) {
            OFF -> Curve(0f, 0f, 0f, 0f)
            LIGHT -> Curve(3f, 1.5f, 2f, -1.5f)
            MEDIUM -> Curve(5f, 2.5f, 3f, -2.5f)
            STRONG -> Curve(7f, 3.5f, 4.5f, -4f)
        }

        private fun coerceAtLeast(minimum: SpeakerMode): SpeakerMode =
            if (ordinal >= minimum.ordinal) this else minimum

        private fun coerceAtMost(maximum: SpeakerMode): SpeakerMode =
            if (ordinal <= maximum.ordinal) this else maximum
    }

    /**
     * Salida física detectada automáticamente.
     *
     * No es un preset y no cambia el contenido elegido por el usuario: solo
     * describe el transductor/ruta probable para adaptar voicing y crossfeed.
     * Un DAC externo se deja transparente porque no sabemos si alimenta
     * audífonos, amplificador o monitores.
     */
    enum class PlaybackOutput(val label: String) {
        PHONE_SPEAKER("Bocina del celular"),
        TV_SPEAKER("Bocina de la TV"),
        WIRED_HEADPHONES("Audífonos con cable"),
        BLUETOOTH_HEADPHONES("Audífonos Bluetooth"),
        BLUETOOTH_SPEAKER("Bocina Bluetooth"),
        USB_SPEAKER("Bocina USB"),
        SOUNDBAR("Barra de sonido / salida externa amplificada"),
        AV_RECEIVER("Receptor AV / HDMI ARC"),
        EXTERNAL_DAC("DAC externo"),
        UNKNOWN("Salida desconocida")
    }

    /** Entrada pura para el clasificador: no expone AudioDeviceInfo a los tests. */
    data class OutputEndpoint(
        val type: Int,
        val productName: String,
        val isSink: Boolean = true
    )

    private val bluetoothHeadphoneTokens = listOf(
        "headphone", "headphones", "headset", "audifono", "audifonos",
        "auricular", "auriculares", "earphone", "earphones", "earbud", "earbuds",
        "buds", "airpod", "airpods", "galaxy buds", "pixel buds", "wh", "wf",
        "qc", "quietcomfort", "momentum", "hd", "dt", "ath", "kz", "blon",
        "iem", "in ear", "on ear", "over ear", "tws"
    )
    private val soundbarTokens = listOf(
        "soundbar", "sound bar", "barra de sonido", "earc", "beam", "ray",
        "sonos", "ht", "hw", "yas", "hts", "sb", "st"
    )
    private val speakerTokens = soundbarTokens + listOf(
        "speaker", "boom", "boombox", "megaboom", "charge", "flip", "go",
        "partybox", "party", "jbl", "ue", "home", "echo", "nest", "arc",
        "roam", "tv", "television", "televisor", "monitor", "projector",
        "proyector", "receiver", "receptor", "amplifier", "amplificador",
        "stereo", "dock", "box", "barra", "bocina", "parlante", "altavoz"
    )
    private val dacTokens = listOf(
        "dac", "d a", "usb dac", "dragonfly", "cobalt", "dacport", "fiio",
        "topping", "smsl", "schiit", "chord", "mojo", "hugo", "ibasso",
        "questyle", "hidizs", "ddhifi", "ka17", "fc3", "fc4", "dawn",
        "sonata", "abigail", "avani", "ua2", "ua3", "apple usb", "usb c",
        "jack adapter", "adaptador", "convertidor", "externo", "external"
    )

    private fun normalizedOutputName(raw: String): String =
        raw.lowercase()
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun outputNameHasToken(name: String, token: String): Boolean {
        val normalizedToken = normalizedOutputName(token)
        if (normalizedToken.isEmpty()) return false
        if (normalizedToken.any { !it.isLetterOrDigit() }) return name.contains(normalizedToken)
        return (" $name ").contains(" $normalizedToken ")
    }

    private fun outputNameHasAnyToken(name: String, tokens: List<String>): Boolean =
        tokens.any { outputNameHasToken(name, it) }

    // TYPE_BLE_* (API 31) y TYPE_HDMI_ARC/EARC van inlined como int:
    // la comparación es segura con minSdk 23.
    @SuppressLint("InlinedApi")
    private fun categorizeOutputEndpoint(
        endpoint: OutputEndpoint,
        isTvDevice: Boolean
    ): PlaybackOutput? {
        if (!endpoint.isSink) return null
        val name = normalizedOutputName(endpoint.productName)
        val isHeadphones = outputNameHasAnyToken(name, bluetoothHeadphoneTokens)
        val isSoundbar = outputNameHasAnyToken(name, soundbarTokens)
        val isSpeaker = outputNameHasAnyToken(name, speakerTokens)
        val isDac = outputNameHasAnyToken(name, dacTokens)
        return when (endpoint.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ->
                if (isTvDevice) PlaybackOutput.TV_SPEAKER else PlaybackOutput.PHONE_SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET -> PlaybackOutput.WIRED_HEADPHONES
            AudioDeviceInfo.TYPE_BLE_HEADSET -> PlaybackOutput.BLUETOOTH_HEADPHONES
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> PlaybackOutput.BLUETOOTH_SPEAKER
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> when {
                isHeadphones -> PlaybackOutput.BLUETOOTH_HEADPHONES
                isSoundbar -> PlaybackOutput.SOUNDBAR
                isSpeaker -> PlaybackOutput.BLUETOOTH_SPEAKER
                // Sin nombre útil, la ruta segura es tratarlo como audífonos:
                // no se aplica voicing de bocina a un transductor desconocido.
                else -> PlaybackOutput.BLUETOOTH_HEADPHONES
            }
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_DOCK -> PlaybackOutput.SOUNDBAR
            // ARC/eARC implica retorno de audio hacia una barra o receptor con
            // procesado propio: se trata como sistema externo capaz, no como
            // bocina pequeña. Los formatos codificados además ya hacen
            // passthrough antes del motor (no se remezclan).
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC -> PlaybackOutput.AV_RECEIVER
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> when {
                isHeadphones -> PlaybackOutput.WIRED_HEADPHONES
                isSoundbar -> PlaybackOutput.SOUNDBAR
                isSpeaker -> PlaybackOutput.USB_SPEAKER
                isDac -> PlaybackOutput.EXTERNAL_DAC
                // Un dispositivo USB de audio sin nombre útil suele ser un DAC
                // o interfaz; se deja transparente en vez de adivinar bocina.
                else -> PlaybackOutput.EXTERNAL_DAC
            }
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> when {
                isHeadphones -> PlaybackOutput.WIRED_HEADPHONES
                isSoundbar || isSpeaker -> PlaybackOutput.SOUNDBAR
                else -> PlaybackOutput.EXTERNAL_DAC
            }
            else -> null
        }
    }

    private fun playbackOutputPriority(output: PlaybackOutput): Int = when (output) {
        PlaybackOutput.WIRED_HEADPHONES -> 70
        PlaybackOutput.BLUETOOTH_HEADPHONES -> 60
        PlaybackOutput.BLUETOOTH_SPEAKER, PlaybackOutput.USB_SPEAKER -> 50
        PlaybackOutput.SOUNDBAR, PlaybackOutput.AV_RECEIVER -> 40
        PlaybackOutput.EXTERNAL_DAC -> 30
        PlaybackOutput.PHONE_SPEAKER, PlaybackOutput.TV_SPEAKER -> 10
        PlaybackOutput.UNKNOWN -> 0
    }

    /**
     * Clasifica la salida física probable a partir de los sinks conectados.
     * Es pura y testeable: Android solo aporta enteros y nombres.
     */
    fun classifyPlaybackOutput(
        endpoints: List<OutputEndpoint>,
        isTvDevice: Boolean
    ): PlaybackOutput {
        var best = PlaybackOutput.UNKNOWN
        var bestPriority = -1
        for (endpoint in endpoints) {
            val output = categorizeOutputEndpoint(endpoint, isTvDevice) ?: continue
            val priority = playbackOutputPriority(output)
            if (priority > bestPriority) {
                best = output
                bestPriority = priority
            }
        }
        return best
    }

    /**
     * Adaptación profesional por salida física.
     *
     * No cambia el preset seleccionado, la EQ ni el objetivo de loudness. Sí
     * puede activar o limitar lo que depende del transductor: voicing,
     * extensión/gestión de graves, dinámica de protección y techo true-peak.
     * Los mínimos/máximos son conservadores: un altavoz pequeño recibe cuerpo
     * y control sin empujarlo a la distorsión, y una salida capaz conserva la
     * transparencia.
     */
    private data class SmallOutputAdaptation(
        val bassDefaultAmount: Float,
        val bassMaxAmount: Float,
        val trueBassDefaultLevel: Float,
        val trueBassMaxLevel: Float,
        val safestCeilingDb: Float
    )

    private fun smallOutputAdaptation(output: PlaybackOutput): SmallOutputAdaptation? = when (output) {
        PlaybackOutput.PHONE_SPEAKER -> SmallOutputAdaptation(
            bassDefaultAmount = 0.35f,
            bassMaxAmount = 0.50f,
            trueBassDefaultLevel = 0.55f,
            trueBassMaxLevel = 0.65f,
            safestCeilingDb = -1.0f
        )
        PlaybackOutput.TV_SPEAKER -> SmallOutputAdaptation(
            bassDefaultAmount = 0.45f,
            bassMaxAmount = 0.55f,
            trueBassDefaultLevel = 0.65f,
            trueBassMaxLevel = 0.75f,
            safestCeilingDb = -1.0f
        )
        PlaybackOutput.BLUETOOTH_SPEAKER,
        PlaybackOutput.USB_SPEAKER -> SmallOutputAdaptation(
            bassDefaultAmount = 0.30f,
            bassMaxAmount = 0.40f,
            trueBassDefaultLevel = 0.45f,
            trueBassMaxLevel = 0.55f,
            safestCeilingDb = -1.0f
        )
        PlaybackOutput.SOUNDBAR -> SmallOutputAdaptation(
            bassDefaultAmount = 0.25f,
            bassMaxAmount = 0.35f,
            trueBassDefaultLevel = 0.35f,
            trueBassMaxLevel = 0.45f,
            safestCeilingDb = -1.0f
        )
        PlaybackOutput.WIRED_HEADPHONES,
        PlaybackOutput.BLUETOOTH_HEADPHONES,
        PlaybackOutput.EXTERNAL_DAC,
        PlaybackOutput.AV_RECEIVER,
        PlaybackOutput.UNKNOWN -> null
    }

    fun withDetectedOutput(
        base: Params,
        output: PlaybackOutput,
        autoOutput: Boolean = isAutoOutput()
    ): Params {
        if (!autoOutput) return base
        if (base.preset == SlPreset.CLEAR_VOICE) {
            // Contrato del preset: la inteligibilidad manda. La adaptación no
            // le reinyecta síntesis de graves en ninguna salida; solo nivela
            // la voz (dinámica), mantiene presencia y protege el techo.
            // En audífonos además se quita el voicing; en bocinas se topa en
            // LIGHT para no embarrar con graves.
            val speaker = when (output) {
                PlaybackOutput.WIRED_HEADPHONES,
                PlaybackOutput.BLUETOOTH_HEADPHONES,
                PlaybackOutput.EXTERNAL_DAC,
                PlaybackOutput.AV_RECEIVER -> SpeakerMode.OFF
                else -> base.speakerMode.coerceAtMost(SpeakerMode.LIGHT)
            }
            return base.copy(
                speakerMode = speaker,
                crossfeed = CrossfeedMode.OFF,
                bassExtEnabled = false,
                trueBassEnabled = false,
                harmonicEnabled = false,
                dynamicsEnabled = true,
                truePeakCeilingDb = minOf(base.truePeakCeilingDb, -1.0f)
            )
        }
        if (output == PlaybackOutput.WIRED_HEADPHONES ||
            output == PlaybackOutput.BLUETOOTH_HEADPHONES
        ) {
            return base.copy(
                speakerMode = SpeakerMode.OFF,
                bassExtEnabled = false,
                trueBassEnabled = false,
                harmonicEnabled = false,
                crossfeed = if (base.crossfeed == CrossfeedMode.OFF) {
                    CrossfeedMode.LOW
                } else {
                    base.crossfeed
                },
                truePeakCeilingDb = minOf(base.truePeakCeilingDb, -1.0f)
            )
        }
        if (output == PlaybackOutput.EXTERNAL_DAC ||
            output == PlaybackOutput.AV_RECEIVER
        ) {
            // Sistema externo capaz (DAC o receptor con corrección propia): no
            // se le aplica voicing ni síntesis de graves para no pelear contra
            // su procesado. Los formatos codificados ya hacen passthrough.
            return base.copy(
                speakerMode = SpeakerMode.OFF,
                bassExtEnabled = false,
                trueBassEnabled = false,
                harmonicEnabled = false,
                truePeakCeilingDb = minOf(base.truePeakCeilingDb, -1.0f)
            )
        }
        val adaptation = smallOutputAdaptation(output) ?: return base
        val bassAmount = if (!base.bassExtEnabled) {
            adaptation.bassDefaultAmount
        } else {
            base.bassAmount.coerceIn(
                minOf(adaptation.bassDefaultAmount, adaptation.bassMaxAmount),
                adaptation.bassMaxAmount
            )
        }
        val trueBassLevel = if (!base.trueBassEnabled) {
            adaptation.trueBassDefaultLevel
        } else {
            base.trueBassLevel.coerceIn(
                minOf(adaptation.trueBassDefaultLevel, adaptation.trueBassMaxLevel),
                adaptation.trueBassMaxLevel
            )
        }
        val speakerMode = when (output) {
            PlaybackOutput.PHONE_SPEAKER ->
                base.speakerMode.coerceAtLeast(SpeakerMode.MEDIUM)
            PlaybackOutput.TV_SPEAKER ->
                base.speakerMode.coerceAtLeast(SpeakerMode.STRONG)
            PlaybackOutput.BLUETOOTH_SPEAKER,
            PlaybackOutput.USB_SPEAKER -> if (base.speakerMode == SpeakerMode.OFF) {
                SpeakerMode.LIGHT
            } else {
                base.speakerMode.coerceAtMost(SpeakerMode.MEDIUM)
            }
            // Una barra puede tener subwoofer: no se añade voicing a un preset
            // transparente, pero un preset de TV no sigue en STRONG contra ella.
            else -> base.speakerMode.coerceAtMost(SpeakerMode.LIGHT)
        }
        return base.copy(
            speakerMode = speakerMode,
            crossfeed = CrossfeedMode.OFF,
            bassExtEnabled = true,
            bassAmount = bassAmount,
            trueBassEnabled = true,
            trueBassLevel = trueBassLevel,
            dynamicsEnabled = true,
            truePeakCeilingDb = minOf(base.truePeakCeilingDb, adaptation.safestCeilingDb)
        )
    }

    /** Banda EQ smartlite: peaking/shelf/corte/notch, enabled individual. */
    data class Band(
        val freqHz: Float,
        val gainDb: Float = 0f,
        val q: Float = 0.707f,
        val kind: BiquadFilter.Kind = BiquadFilter.Kind.PEAKING,
        val enabled: Boolean = false
    )

    data class Params(
        val preset: SlPreset = SlPreset.PURE,
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
            h = 31 * h + bassAmount.hashCode()
            h = 31 * h + bassFreqHz.hashCode()
            h = 31 * h + bassHarmonicMix.hashCode()
            h = 31 * h + trueBassEnabled.hashCode()
            h = 31 * h + trueBassLevel.hashCode()
            h = 31 * h + transientEnabled.hashCode()
            h = 31 * h + transientAmount.hashCode()
            h = 31 * h + transientAttack.hashCode()
            h = 31 * h + transientRelease.hashCode()
            h = 31 * h + harmonicEnabled.hashCode()
            h = 31 * h + harmonicAmount.hashCode()
            h = 31 * h + harmonicDrive.hashCode()
            h = 31 * h + harmonicFreqHz.hashCode()
            h = 31 * h + harmonicMix.hashCode()
            h = 31 * h + crossfeed.hashCode()
            h = 31 * h + speakerMode.hashCode()
            h = 31 * h + truePeakCeilingDb.hashCode()
            h = 31 * h + outputGainDb.hashCode()
            h = 31 * h + ditherWhenNeeded.hashCode()
            return h
        }

        companion object {
            /**
             * 6 bandas por defecto: HP, low-shelf, 2 peaking, high-shelf, LP.
             *
             * ACTIVADAS por defecto (antes venían todas `enabled=false`, así que
             * el EQ era un no-op: el pipeline lo llama siempre, solo que no
             * había bandas que procesar). La curva es CORRECTIVA y deliberadamente
             * pequeña, con dos reglas:
             *
             *  1) Los valores forman una PENDIENTE CONTINUA, no un notch. Es la
             *     diferencia real entre un voicing que suena natural y uno que
             *     suena a caja. Un corte aislado rodeado de respuesta plana es
             *     un notch: el oído lo detecta como anomalía. Una rampa que sube
             *     o baja de forma monótona se percibe como tono.
             *
             *     El fallo anterior era un scoop: −1 dB a 200 Hz y +1 dB a 1 kHz
             *     miden un corte de −0.92 dB y una jiba de +0.96 dB en el mismo
             *     margen de una octava. El +1 de 1 kHz además se sumaba con el
             *     shelf de 10 kHz, así que era un hump local, no una pendiente.
             *     Con 1 kHz plano los valores suben de forma continua
             *     (60 → 200 → 1k → 4k → 10k) y el scoop desaparece.
             *
             *     Con solo 6 bandas a ~1.7 octavas de separación no se pueden
             *     tener filtros estrechos y sin huecos a la vez: Q alto deja
             *     zonas sin corregir entre bandas. De ahí que el ancho de banda
             *     se acepte y se compense con la continuidad de los valores.
             *     SmartLite se queda en 6 bandas a propósito (el DSP antiguo
             *     tiene un modo de 5, `CHEAP_FREQS`); 10 bandas son para
             *     corrección por medición (AutoEQ), no para este voicing.
             *
             *  2) El máximo de boost marca el preamp de headroom
             *     (`preamp = −max boost`), así que un boost alto costaría nivel
             *     global. Con +1.0 dB el coste es 1 dB, imperceptible.
             *
             * La banda 6 (HP 16 kHz) se deja DESACTIVADA a propósito: en
             * material de 44.1 kHz (Nyquist 22.05 kHz) un paso alto de 2º orden
             * a 16 kHz atenúa ~3 dB en 16 kHz y ~11 dB en 19 kHz, o sea borra
             * contenido real de la última octava. Sin editor de bandas en la UI
             * eso sería irreversible para el usuario, y es una decisión de
             * fidelidad, no una corrección.
             */
            fun defaultBands(): List<Band> = listOf(
                // Subgrave: ancla de peso. Los presets de altavoz pequeño ya
                // suman aquí vía TrueBass (+6 dB) y Speaker (+5/+7 dB), pero
                // están apagados en PURE, REFERENCE, HIFI y HEADPHONES.
                Band(60f, 1.0f, 0.707f, BiquadFilter.Kind.LOWSHELF, true),
                // 200 Hz: el "barro" de 200-300 Hz es el defecto más universal
                // del mastering loud. Es el punto más bajo de la sonrisa, así que
                // va suave (−0.7) y con Q 1.2 para que el lomo sea ancho y no un
                // notch. Sigue siendo la única corrección de graves medios.
                Band(200f, -0.7f, 1.2f, BiquadFilter.Kind.PEAKING, true),
                // 1 kHz: PLANA a propósito. Aquí estaba el +1 dB que formaba la
                // jiba que combaba el scoop contra el corte de 200 Hz. Sin este
                // boost la curva pasa de −0.7 a plano de forma continua, y la
                // voz conserva el cuerpo. VoicePresence ya cubre 1.5-4 kHz en los
                // presets que la necesitan.
                Band(1000f, 0.0f, 1.2f, BiquadFilter.Kind.PEAKING, true),
                // 4 kHz: la mitad alta de la pendiente, +0.7 dB. Continúa el
                // ascenso desde 1 kHz en vez de ser un pico, que es lo que
                // hace que la voz se entienda en una bocina pequeña sin que la
                // curva tenga holes.
                Band(4000f, 0.7f, 1.2f, BiquadFilter.Kind.PEAKING, true),
                // 10 kHz: micro-compensación de brillo. +0.5 dB es el umbral
                // perceptual del "aire" y no se percibe como coloración.
                Band(10000f, 0.5f, 0.707f, BiquadFilter.Kind.HIGHSHELF, true),
                // 16 kHz HP: desactivado a propósito (ver nota arriba).
                Band(16000f, 0f, 0.707f, BiquadFilter.Kind.HIGHPASS, false)
            )

            /**
             * Curva de Clear Voice: la misma estructura de 6 bandas, pero sin
             * ancla de subgrave (shelf 60 Hz a 0 dB), corte de barro más
             * profundo (−1 dB a 200 Hz) y presencia real a 4 kHz (+1 dB) para
             * inteligibilidad de diálogo. El boost máximo sigue en +1 dB, así
             * que el headroom no cambia.
             */
            fun clearVoiceBands(): List<Band> = listOf(
                Band(60f, 0.0f, 0.707f, BiquadFilter.Kind.LOWSHELF, true),
                Band(200f, -1.0f, 1.2f, BiquadFilter.Kind.PEAKING, true),
                Band(1000f, 0.0f, 1.2f, BiquadFilter.Kind.PEAKING, true),
                Band(4000f, 1.0f, 1.2f, BiquadFilter.Kind.PEAKING, true),
                Band(10000f, 0.5f, 0.707f, BiquadFilter.Kind.HIGHSHELF, true),
                Band(16000f, 0f, 0.707f, BiquadFilter.Kind.HIGHPASS, false)
            )

            /** Curva por defecto según el preset (solo Clear Voice difiere). */
            fun presetBands(preset: SlPreset): List<Band> = when (preset) {
                SlPreset.CLEAR_VOICE -> clearVoiceBands()
                else -> defaultBands()
            }
        }
    }

    private const val PREF_NAME = "karin_smartlite"
    private const val KEY_ENGINE = "sl_engine"
    private const val KEY_PRESET = "sl_preset"
    private const val KEY_AUTO_OUTPUT = "sl_auto_output"
    private const val KEY_AUTOEQ = "sl_autoeq"
    private const val KEY_BITPERFECT = "sl_bitperfect"
    private const val KEY_AB_BYPASS = "sl_ab_bypass"
    private const val KEY_AUTO_HEADROOM = "sl_auto_headroom"
    private const val KEY_EQ_ENABLED = "sl_eq_enabled"
    private const val KEY_EQ_BANDS = "sl_eq_bands"
    private const val KEY_LOUD_ENABLED = "sl_loud_enabled"
    private const val KEY_LOUD_TARGET = "sl_loud_target"
    private const val KEY_DYN_ENABLED = "sl_dyn_enabled"
    private const val KEY_BASS_ENABLED = "sl_bass_enabled"
    private const val KEY_BASS_AMOUNT = "sl_bass_amount"
    private const val KEY_BASS_FREQ = "sl_bass_freq"
    private const val KEY_BASS_HARM = "sl_bass_harm"
    private const val KEY_TRUEBASS_ENABLED = "sl_truebass_enabled"
    private const val KEY_TRUEBASS_LEVEL = "sl_truebass_level"
    private const val KEY_TRANS_ENABLED = "sl_trans_enabled"
    private const val KEY_TRANS_AMOUNT = "sl_trans_amount"
    private const val KEY_TRANS_ATK = "sl_trans_atk"
    private const val KEY_TRANS_REL = "sl_trans_rel"
    private const val KEY_HARM_ENABLED = "sl_harm_enabled"
    private const val KEY_HARM_AMOUNT = "sl_harm_amount"
    private const val KEY_HARM_DRIVE = "sl_harm_drive"
    private const val KEY_HARM_FREQ = "sl_harm_freq"
    private const val KEY_HARM_MIX = "sl_harm_mix"
    private const val KEY_CROSSFEED = "sl_crossfeed"
    private const val KEY_SPEAKER = "sl_speaker"
    private const val KEY_TP_CEILING = "sl_tp_ceiling"
    private const val KEY_OUT_GAIN = "sl_out_gain"
    private const val KEY_DITHER = "sl_dither"

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var appContext: Context? = null
    private val cacheGen = AtomicLong(0)
    @Volatile private var cached: Params? = null
    @Volatile private var cachedGen = -1L
    @Volatile private var abBypass = false
    @Volatile private var engine = Engine.SMART_LITE
    @Volatile private var detectedPlaybackOutput = PlaybackOutput.UNKNOWN
    @Volatile private var matchedAutoEqProfile: AutoEqCatalog.Profile? = null
    @Volatile private var btCodecLabel: String = "desconocido"

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs = p
        appContext = context.applicationContext
        engine = runCatching { Engine.valueOf(p.getString(KEY_ENGINE, Engine.SMART_LITE.name)!!) }
            .getOrDefault(Engine.SMART_LITE)
        abBypass = p.getBoolean(KEY_AB_BYPASS, false)
        p.registerOnSharedPreferenceChangeListener { _, key ->
            if (key != null) {
                cacheGen.incrementAndGet()
                if (key == KEY_ENGINE) {
                    engine = runCatching { Engine.valueOf(p.getString(KEY_ENGINE, Engine.SMART_LITE.name)!!) }
                        .getOrDefault(Engine.SMART_LITE)
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

    fun isAutoOutput(): Boolean = prefs?.getBoolean(KEY_AUTO_OUTPUT, true) ?: true
    fun setAutoOutput(on: Boolean) = saveBool(KEY_AUTO_OUTPUT, on)
    fun detectedPlaybackOutput(): PlaybackOutput = detectedPlaybackOutput
    internal fun updateDetectedPlaybackOutput(output: PlaybackOutput) {
        if (output == detectedPlaybackOutput) return
        detectedPlaybackOutput = output
        // El preset AUTO depende de la salida: invalida params().
        cacheGen.incrementAndGet()
    }

    /**
     * Perfil AutoEQ medido: "auto" (usa el mejor match del dispositivo
     * detectado), "none" (curva suave por defecto) o el nombre exacto de un
     * perfil del catálogo.
     */
    fun autoEqSetting(): String = prefs?.getString(KEY_AUTOEQ, "auto") ?: "auto"
    fun setAutoEqSetting(v: String) {
        prefs?.edit()?.putString(KEY_AUTOEQ, v)?.apply()
        cacheGen.incrementAndGet()
    }
    fun matchedAutoEqProfile(): AutoEqCatalog.Profile? = matchedAutoEqProfile
    internal fun updateMatchedAutoEqProfile(p: AutoEqCatalog.Profile?) {
        matchedAutoEqProfile = p
    }

    /** Bit-perfect formal: el motor copia bytes sin procesar nada. */
    fun isBitPerfect(): Boolean = prefs?.getBoolean(KEY_BITPERFECT, false) ?: false
    fun setBitPerfect(on: Boolean) = saveBool(KEY_BITPERFECT, on)

    /** Etiqueta del códec Bluetooth activo ("LDAC · 96 kHz · 32 bits", etc.). */
    fun btCodecLabel(): String = btCodecLabel
    internal fun updateBtCodecLabel(label: String) {
        btCodecLabel = label
    }

    /**
     * Nivel de calidad por gama del dispositivo. En gama baja se apaga el
     * Harmonic Enhancement (el módulo más prescindible y con más riesgo de
     * artefactos) para proteger la fluidez; el resto de la cadena no cambia.
     */
    fun applyQualityTier(base: Params, lowTier: Boolean, ultra: Boolean = false): Params {
        if (ultra) {
            return base.copy(
                harmonicEnabled = false,
                transientEnabled = false,
                trueBassEnabled = false,
                bassExtEnabled = false
            )
        }
        return if (lowTier) base.copy(harmonicEnabled = false) else base
    }

    /**
     * Convierte un perfil medido (10 bandas paramétricas) a las bandas del EQ
     * SmartLite. Se usan los primeros 5 filtros, que es la variante que la
     * propia fuente publica como válida ("filtros 1-5"); el headroom del motor
     * absorbe el preamp porque el boost máximo de esos 5 filtros lo aproxima.
     */
    fun autoEqBands(profile: AutoEqCatalog.Profile): List<Band> =
        profile.bands.take(5).map {
            Band(
                freqHz = it.freqHz,
                gainDb = it.gainDb,
                q = it.q,
                kind = it.kind,
                enabled = true
            )
        }

    /** ¿El usuario guardó una curva propia? Si sí, ningún preset la pisa. */
    fun hasCustomBands(): Boolean {
        val p = prefs ?: return false
        return parseBands(p.getString(KEY_EQ_BANDS, null)) != null
    }

    /**
     * Resuelve qué bandas EQ usar, en este orden:
     * 1) AutoEQ medido (elección explícita del usuario o match automático).
     * 2) Curva propia guardada por el usuario.
     * 3) Curva del preset (solo Clear Voice difiere de la suave global).
     */
    fun resolveEqBands(base: Params): Params {
        val setting = autoEqSetting()
        val profile = when (setting) {
            "none" -> null
            "auto" -> matchedAutoEqProfile()
            else -> AutoEqCatalog.findByModel(setting)
        }
        if (profile != null) return base.copy(bands = autoEqBands(profile))
        if (hasCustomBands()) return base
        return base.copy(bands = Params.presetBands(base.preset))
    }

    /** ¿Corre en una TV (Android TV) o en un celular/tablet? Por factor de forma. */
    fun isTvDevice(): Boolean {
        val ctx = appContext ?: return false
        val mode = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
        return mode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    /**
     * Preset concreto que toca cuando la preferencia es AUTO: se elige según la
     * salida detectada (nunca presets de contenido, que son decisión del
     * usuario). Se recalcula solo al cambiar de salida.
     */
    fun autoPresetFor(output: PlaybackOutput): SlPreset = when (output) {
        PlaybackOutput.WIRED_HEADPHONES,
        PlaybackOutput.BLUETOOTH_HEADPHONES,
        PlaybackOutput.EXTERNAL_DAC -> SlPreset.HEADPHONES
        PlaybackOutput.PHONE_SPEAKER,
        PlaybackOutput.BLUETOOTH_SPEAKER,
        PlaybackOutput.USB_SPEAKER -> SlPreset.MOBILE_SPEAKER
        PlaybackOutput.AV_RECEIVER -> SlPreset.CINEMA
        PlaybackOutput.TV_SPEAKER,
        PlaybackOutput.SOUNDBAR -> SlPreset.TV_SPEAKER
        PlaybackOutput.UNKNOWN -> SlPreset.PURE
    }

    /** Preset efectivo: AUTO se traduce al concreto de la salida actual. */
    fun resolvePreset(p: SlPreset): SlPreset =
        if (p == SlPreset.AUTO) autoPresetFor(detectedPlaybackOutput()) else p

    /** Preset que está sonando ahora mismo (preferencia ya resuelta). */
    fun effectivePreset(): SlPreset = resolvePreset(preset())

    fun preset(): SlPreset {
        val p = prefs ?: return SlPreset.AUTO
        return runCatching { SlPreset.valueOf(p.getString(KEY_PRESET, SlPreset.AUTO.name)!!) }
            .getOrDefault(SlPreset.AUTO)
    }

    fun setPreset(p: SlPreset) {
        prefs?.edit()?.putString(KEY_PRESET, p.name)?.apply()
        // Aplicar composición del preset en los toggles/params (en AUTO manda
        // el preset concreto de la salida actual).
        val base = presetParams(resolvePreset(p))
        saveBool(KEY_AUTO_HEADROOM, base.autoHeadroom)
        saveBool(KEY_EQ_ENABLED, base.eqEnabled)
        saveBool(KEY_LOUD_ENABLED, base.loudnessEnabled)
        saveFloat(KEY_LOUD_TARGET, base.loudnessTargetLufs)
        saveBool(KEY_DYN_ENABLED, base.dynamicsEnabled)
        saveBool(KEY_BASS_ENABLED, base.bassExtEnabled)
        saveFloat(KEY_BASS_AMOUNT, base.bassAmount)
        saveFloat(KEY_BASS_FREQ, base.bassFreqHz)
        saveFloat(KEY_BASS_HARM, base.bassHarmonicMix)
        saveBool(KEY_TRUEBASS_ENABLED, base.trueBassEnabled)
        saveFloat(KEY_TRUEBASS_LEVEL, base.trueBassLevel)
        saveBool(KEY_TRANS_ENABLED, base.transientEnabled)
        saveFloat(KEY_TRANS_AMOUNT, base.transientAmount)
        saveFloat(KEY_TRANS_ATK, base.transientAttack)
        saveFloat(KEY_TRANS_REL, base.transientRelease)
        saveBool(KEY_HARM_ENABLED, base.harmonicEnabled)
        saveFloat(KEY_HARM_AMOUNT, base.harmonicAmount)
        saveFloat(KEY_HARM_DRIVE, base.harmonicDrive)
        saveFloat(KEY_HARM_FREQ, base.harmonicFreqHz)
        saveFloat(KEY_HARM_MIX, base.harmonicMix)
        saveFloat(KEY_TP_CEILING, base.truePeakCeilingDb)
        saveFloat(KEY_OUT_GAIN, base.outputGainDb)
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
        val stored = preset()
        val resolved = resolvePreset(stored)
        val bands = parseBands(p.getString(KEY_EQ_BANDS, null)) ?: Params.defaultBands()
        val out = Params(
            preset = resolved,
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
            transientAttack = p.getFloat(KEY_TRANS_ATK, 0.5f),
            transientRelease = p.getFloat(KEY_TRANS_REL, 0.5f),
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
        val final = if (stored == SlPreset.AUTO) autoComposed(out, resolved) else out
        cached = final
        cachedGen = gen
        return final
    }

    /**
     * En modo AUTO la composición la manda el preset concreto de la salida
     * (toggles, voicing y crossfeed salen de él). El usuario solo conserva lo
     * que no decide el preset: sus bandas EQ, la potencia, el techo de
     * protección y el dither.
     */
    private fun autoComposed(base: Params, resolved: SlPreset): Params {
        val a = presetParams(resolved)
        return base.copy(
            autoHeadroom = a.autoHeadroom,
            eqEnabled = a.eqEnabled,
            loudnessEnabled = a.loudnessEnabled,
            loudnessTargetLufs = a.loudnessTargetLufs,
            dynamicsEnabled = a.dynamicsEnabled,
            bassExtEnabled = a.bassExtEnabled,
            bassAmount = a.bassAmount,
            bassFreqHz = a.bassFreqHz,
            bassHarmonicMix = a.bassHarmonicMix,
            trueBassEnabled = a.trueBassEnabled,
            trueBassLevel = a.trueBassLevel,
            transientEnabled = a.transientEnabled,
            transientAmount = a.transientAmount,
            transientAttack = a.transientAttack,
            transientRelease = a.transientRelease,
            harmonicEnabled = a.harmonicEnabled,
            harmonicAmount = a.harmonicAmount,
            harmonicDrive = a.harmonicDrive,
            harmonicFreqHz = a.harmonicFreqHz,
            harmonicMix = a.harmonicMix,
            crossfeed = a.crossfeed,
            speakerMode = a.speakerMode
        )
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

    /**
     * Composición de cada preset.
     *
     * Nota honesta de arquitectura: este motor no tiene sintetizador de
     * surround/width (eso vive en el DSP actual y en el spatializer del
     * sistema). Las fuentes multicanal pasan sus canales extra intactos; en
     * estéreo, "amplitud" significa preservar el estéreo completo (crossfeed
     * OFF) + dinámica que no aplasta transitorios. Los presets de contenido
     * optimizan diálogo, dinámica y graves, no inventan canales.
     */
    fun presetParams(p: SlPreset): Params = when (p) {
        // Defensa: nunca se pide la composición sin resolver; si llega AUTO,
        // se delega al preset concreto de la salida actual.
        SlPreset.AUTO -> presetParams(resolvePreset(p))
        SlPreset.PURE -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = false, dynamicsEnabled = false,
            bassExtEnabled = false, harmonicEnabled = false,
            transientEnabled = false, crossfeed = CrossfeedMode.OFF,
            truePeakCeilingDb = -1.0f, outputGainDb = 0f
        )
        SlPreset.REFERENCE -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = true, loudnessTargetLufs = -16f,
            dynamicsEnabled = false, bassExtEnabled = false,
            harmonicEnabled = false, transientEnabled = false,
            crossfeed = CrossfeedMode.OFF, truePeakCeilingDb = -1.0f
        )
        SlPreset.HIFI -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = true, loudnessTargetLufs = -16f,
            dynamicsEnabled = true, bassExtEnabled = false,
            harmonicEnabled = false, transientEnabled = false,
            crossfeed = CrossfeedMode.LOW, truePeakCeilingDb = -1.0f
        )
        SlPreset.HEADPHONES -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = false, dynamicsEnabled = false,
            bassExtEnabled = false, harmonicEnabled = false,
            transientEnabled = false, crossfeed = CrossfeedMode.MEDIUM,
            truePeakCeilingDb = -1.0f
        )
        SlPreset.MOBILE_SPEAKER -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = true, loudnessTargetLufs = -14f,
            dynamicsEnabled = false, bassExtEnabled = true,
            bassAmount = 0.50f, trueBassEnabled = true, trueBassLevel = 0.86f,
            harmonicEnabled = false,
            transientEnabled = false, crossfeed = CrossfeedMode.OFF,
            speakerMode = SpeakerMode.MEDIUM,
            truePeakCeilingDb = -1.5f
        )
        SlPreset.TV_SPEAKER -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = true, loudnessTargetLufs = -14f,
            dynamicsEnabled = true, bassExtEnabled = true,
            bassAmount = 0.55f, trueBassEnabled = true, trueBassLevel = 0.9f,
            harmonicEnabled = true,
            harmonicAmount = 0.2f, transientEnabled = false,
            crossfeed = CrossfeedMode.OFF, speakerMode = SpeakerMode.STRONG,
            truePeakCeilingDb = -1.5f
        )
        // CINEMA: películas/series. Dinámica suave que pega susurros y
        // explosiones, graves gestionados en mono + síntesis moderada, voicing
        // LIGHT para diálogo (+2 dB presencia) y loudness a −16 para que el
        // nivel no salte entre escenas. Sin crossfeed: el estéreo queda intacto
        // y el multicanal pasa sus canales extra sin tocar.
        SlPreset.CINEMA -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = true, loudnessTargetLufs = -16f,
            dynamicsEnabled = true, bassExtEnabled = true,
            bassAmount = 0.45f, trueBassEnabled = true, trueBassLevel = 0.65f,
            harmonicEnabled = false, transientEnabled = false,
            crossfeed = CrossfeedMode.OFF, speakerMode = SpeakerMode.LIGHT,
            truePeakCeilingDb = -1.5f
        )
        // ANIME: voces al frente sobre OST. Voicing LIGHT (presencia de voz),
        // graves controlados con tope (no tapan el diálogo), dinámica suave y
        // estéreo completo preservado.
        SlPreset.ANIME -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = true, loudnessTargetLufs = -16f,
            dynamicsEnabled = true, bassExtEnabled = true,
            bassAmount = 0.35f, trueBassEnabled = true, trueBassLevel = 0.55f,
            harmonicEnabled = false, transientEnabled = false,
            crossfeed = CrossfeedMode.OFF, speakerMode = SpeakerMode.LIGHT,
            truePeakCeilingDb = -1.0f
        )
        // POWER_BASS: acción/música. Extensión y TrueBass altos pero dentro de
        // los topes medidos, dinámica de pegamento, loudness a −14 y techo a
        // −1.5: golpe sin clipping. Los armónicos generados son intencionales.
        SlPreset.POWER_BASS -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = true, loudnessTargetLufs = -14f,
            dynamicsEnabled = true, bassExtEnabled = true,
            bassAmount = 0.60f, trueBassEnabled = true, trueBassLevel = 0.70f,
            harmonicEnabled = false, transientEnabled = false,
            crossfeed = CrossfeedMode.OFF, speakerMode = SpeakerMode.MEDIUM,
            truePeakCeilingDb = -1.5f
        )
        // CLEAR_VOICE: diálogos. Curva propia sin subgrave y con presencia a
        // 4 kHz (ver clearVoiceBands), voicing LIGHT para cuerpo de voz,
        // dinámica suave que nivela susurros y techo a −1.0. Sin síntesis de
        // graves: aquí el grave es el enemigo de la inteligibilidad.
        SlPreset.CLEAR_VOICE -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = true, loudnessTargetLufs = -16f,
            dynamicsEnabled = true, bassExtEnabled = false,
            trueBassEnabled = false, harmonicEnabled = false,
            transientEnabled = false, crossfeed = CrossfeedMode.OFF,
            speakerMode = SpeakerMode.LIGHT,
            truePeakCeilingDb = -1.0f
        )
        // MUSIC: estéreo completo (sin crossfeed), dinámica intacta salvo el
        // pegamento suave, loudness a −16 para nivel parejo entre temas y un
        // toque de aire con Harmonic al mínimo. Difiere de HIFI en que no
        // estrecha la imagen, y de PURE en nivel + dinámica + aire.
        SlPreset.MUSIC -> Params(
            preset = p, autoHeadroom = true, eqEnabled = true,
            loudnessEnabled = true, loudnessTargetLufs = -16f,
            dynamicsEnabled = true, bassExtEnabled = false,
            trueBassEnabled = false, harmonicEnabled = true,
            harmonicAmount = 0.1f, transientEnabled = false,
            crossfeed = CrossfeedMode.OFF, speakerMode = SpeakerMode.OFF,
            truePeakCeilingDb = -1.0f
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

/**
 * Sesión ABX pura y testeable: X es A o B al azar en cada ensayo, el usuario
 * responde y al final se calcula el valor p binomial (probabilidad de acertar
 * así por azar). Con 8 ensayos, 8/8 da p = 0.004 (significativo); 6/8 da
 * p = 0.145 (no significativo).
 */
class AbxSession(
    val totalTrials: Int = 8,
    random: kotlin.random.Random = kotlin.random.Random.Default
) {
    private val key: List<Boolean> = List(totalTrials) { random.nextBoolean() }
    var current: Int = 0
        private set
    var correct: Int = 0
        private set

    /** En el ensayo actual, ¿X es A? (true) o B (false). */
    fun currentIsA(): Boolean = key[current.coerceAtMost(totalTrials - 1)]

    /** Registra "X era A" (`xIsA=true`) o "X era B". Devuelve si acertó. */
    fun answer(xIsA: Boolean): Boolean {
        if (done) return false
        val ok = xIsA == key[current]
        if (ok) correct++
        current++
        return ok
    }

    val done: Boolean get() = current >= totalTrials

    /** P(X >= aciertos | azar): suma binomial exacta con n <= 16. */
    fun pValue(): Double {
        var p = 0.0
        var c = 1L
        val n = totalTrials
        for (k in 0..n) {
            if (k > 0) c = c * (n - k + 1) / k
            if (k >= correct) p += c.toDouble() / (1L shl n).toDouble()
        }
        return p.coerceIn(0.0, 1.0)
    }

    fun verdict(): String = when {
        !done -> "en curso ($correct/$current)"
        pValue() < 0.05 -> "diferencia audible ($correct/$totalTrials, p=${"%.3f".format(pValue())})"
        else -> "sin evidencia ($correct/$totalTrials, p=${"%.3f".format(pValue())})"
    }
}

/**
 * Reporte exportable del estado del DSP: configuración efectiva + métricas
 * medidas. No mide nada nuevo; formatea lo que el monitor ya publica, para
 * poder pegarlo en un reporte o guardarlo junto a una prueba de escucha.
 */
object SmartLiteReport {
    fun format(
        params: SmartLiteConfig.Params,
        metrics: SmartLiteMetrics,
        outputLabel: String,
        codecLabel: String = SmartLiteConfig.btCodecLabel()
    ): String = buildString {
        appendLine("Karin SmartLite DSP — reporte")
        appendLine("Preset: ${params.preset.label}")
        appendLine("Salida: $outputLabel")
        appendLine("Códec BT: $codecLabel")
        appendLine("Bit-perfect: ${SmartLiteConfig.isBitPerfect()}")
        appendLine("AutoEQ: ${SmartLiteConfig.autoEqSetting()}")
        appendLine(
            "Módulos: eq=${params.eqEnabled} loud=${params.loudnessEnabled} " +
                "dyn=${params.dynamicsEnabled} bass=${params.bassExtEnabled} " +
                "truebass=${params.trueBassEnabled} harm=${params.harmonicEnabled} " +
                "speaker=${params.speakerMode} xf=${params.crossfeed}"
        )
        appendLine("Techo true-peak: ${params.truePeakCeilingDb} dBTP")
        appendLine("LUFS medido: ${metrics.lufs}")
        appendLine("Pico salida: ${metrics.outputPeakDb} dBFS")
        appendLine("True-peak: ${metrics.truePeakDbtp} dBTP")
        appendLine("Rango dinámico: ${metrics.dynamicRangeDb} dB")
        appendLine("Correlación L/R: ${metrics.correlation}")
        appendLine("CPU DSP: ${metrics.cpuPercent} %")
    }
}
