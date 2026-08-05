package com.karin.streamtv.player.dsp

/**
 * Parser de curvas paramétricas estilo AutoEQ ("jaakkopasanen/AutoEq").
 *
 * Interpreta las líneas de texto típicas de las mediciones exportadas:
 *   Filter 1: ON PK Fc 105 Hz Gain -5.2 dB Q 1.21
 *   Filter 2: ON LSC Fc 30 Hz Gain 4.0 dB Q 0.71
 *   Filter 3: ON HSC Fc 10000 Hz Gain -2.5 dB Q 0.71
 * también soporta "Band N:", "Peaking filter: ..." y "Low shelf:".
 *
 * El usuario pega sus propias curvas (no se embebe ningún dato de terceros).
 */
object AutoEqParser {

    private val bandRe = Regex(
        """\b(PK|LSC|HSC|LP|HP|Peaking|Low shelf|High shelf)\b.*?\bFc\s*([0-9.]+)\s*Hz\s*Gain\s*(-?[0-9.]+)\s*dB(?:\s*Q\s*([0-9.]+))?""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String, maxBands: Int = 16): List<AudioEnhanceConfig.ParamBand>? {
        val bands = ArrayList<AudioEnhanceConfig.ParamBand>()
        for (raw in text.lines()) {
            if (bands.size >= maxBands) break
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//") ||
                line.startsWith("Preamp") || line.startsWith("preamp")
            ) continue
            val m = bandRe.find(line) ?: continue
            val freq = m.groupValues[2].toFloatOrNull()
            val gain = m.groupValues[3].toFloatOrNull()
            val q = m.groupValues[4].toFloatOrNull() ?: 0.707f
            if (freq == null || gain == null || freq <= 0f || q <= 0f) continue
            bands.add(
                AudioEnhanceConfig.ParamBand(
                    freqHz = freq,
                    gainDb = gain.coerceIn(-20f, 20f),
                    q = q.coerceIn(0.2f, 20f),
                    kind = detectKind(m.groupValues[1])
                )
            )
        }
        if (bands.isEmpty()) return null
        bands.sortBy { it.freqHz }
        return bands
    }

    fun countLabel(bands: List<AudioEnhanceConfig.ParamBand>?): String =
        if (bands.isNullOrEmpty()) "ninguna" else "${bands.size} bandas"

    private fun detectKind(token: String): BiquadFilter.Kind = when (token.uppercase()) {
        "LSC", "LOW SHELF" -> BiquadFilter.Kind.LOWSHELF
        "HSC", "HIGH SHELF" -> BiquadFilter.Kind.HIGHSHELF
        "LP" -> BiquadFilter.Kind.LOWPASS
        "HP" -> BiquadFilter.Kind.HIGHPASS
        else -> BiquadFilter.Kind.PEAKING
    }
}
