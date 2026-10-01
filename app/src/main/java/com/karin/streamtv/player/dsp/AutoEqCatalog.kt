package com.karin.streamtv.player.dsp

/**
 * Catálogo de corrección AutoEQ **medida** (fuente: oratory1990 / AutoEq,
 * mediciones públicas sobre targets Harman, https://github.com/jaakkopasanen/AutoEq).
 *
 * Cada perfil aquí es la curva paramétrica real derivada de la medición del modelo
 * concreto (frecuencia / ganancia / Q por banda + preamp de headroom contra el clip).
 *
 * Los perfiles se aplican vía SmartLite: bandas 1-5 como EQ paramétrico con Q
 * real, así que la corrección es exacta (no una aproximación gráfica).
 *
 * Para añadir más modelos: bajar de
 *   https://autoeq.app/predicted-ear/…  o  …/ParametricEQ.txt
 * (o resultados de REW/otros) y copiar las filas con su preamp a este catálogo.
 */

/** Banda paramétrica medida: frecuencia, ganancia, Q y tipo de filtro. */
data class ParamBand(
    val freqHz: Float,
    val gainDb: Float,
    val q: Float = 0.707f,
    val kind: BiquadFilter.Kind = BiquadFilter.Kind.PEAKING
)

object AutoEqCatalog {

    data class Profile(
        val name: String,
        val preampDb: Float,
        val bands: List<ParamBand>
    )

    private fun pk(f: Float, g: Float, q: Float) = ParamBand(f, g, q, BiquadFilter.Kind.PEAKING)
    private fun ls(f: Float, g: Float, q: Float = 0.7f) = ParamBand(f, g, q, BiquadFilter.Kind.LOWSHELF)
    private fun hs(f: Float, g: Float, q: Float = 0.7f) = ParamBand(f, g, q, BiquadFilter.Kind.HIGHSHELF)

    val profiles: List<Profile> = listOf(
        Profile("Sennheiser HD 650", -6.2f, listOf(
            ls(105f, 6.4f), pk(37f, 0.7f, 3.96f), pk(118f, -3.1f, 0.5f), pk(3169f, -1.7f, 3.89f), pk(8800f, 5.1f, 1.42f),
            pk(587f, 0.4f, 1.19f), pk(1227f, -1.2f, 2.53f), pk(2055f, 1.2f, 3.23f), pk(5332f, -1.1f, 5.75f), hs(10000f, -2.1f)
        )),
        Profile("Sennheiser HD 600", -6.4f, listOf(
            ls(105f, 6.5f), pk(125f, -2.7f, 0.55f), pk(522f, 0.7f, 1.02f), pk(1298f, -1.2f, 2.14f), pk(8445f, 3.3f, 1.61f),
            pk(2166f, 0.9f, 3.32f), pk(3158f, -1.8f, 3.67f), pk(5433f, -1.2f, 5.7f), pk(6639f, 2.2f, 5.82f), hs(10000f, -3.1f)
        )),
        Profile("Sennheiser HD 800 S", -6.3f, listOf(
            ls(105f, 6.6f), pk(142f, -2.3f, 0.3f), pk(212f, -0.5f, 2.36f), pk(1959f, 2.5f, 0.88f), pk(5679f, -4.4f, 4.36f),
            pk(1043f, -1.0f, 2.89f), pk(1445f, 1.0f, 3.43f), pk(2640f, -0.9f, 4.01f), pk(3421f, 1.3f, 5.47f), hs(10000f, -4.3f)
        )),
        Profile("Focal Clear", -5.9f, listOf(
            ls(105f, 7.3f), pk(73f, -3.6f, 0.36f), pk(1236f, -3.8f, 2.26f), pk(4557f, 3.8f, 4.14f), pk(8789f, 4.5f, 1.48f),
            pk(1563f, -0.9f, 4.71f), pk(2219f, 2.0f, 3.33f), pk(3136f, -1.1f, 3.33f), pk(5934f, -0.8f, 6.0f), hs(10000f, -1.6f)
        )),
        Profile("Audio-Technica ATH-M50x", -3.2f, listOf(
            ls(105f, 0.6f), pk(156f, -5.2f, 0.73f), pk(326f, 5.3f, 1.59f), pk(3483f, 2.1f, 5.82f), pk(7077f, 2.8f, 2.22f),
            pk(45f, -1.1f, 1.9f), pk(66f, 1.4f, 3.59f), pk(787f, -0.5f, 1.79f), pk(1640f, 0.9f, 3.41f), hs(10000f, -4.1f)
        )),
        Profile("AKG K702", -6.2f, listOf(
            ls(105f, 7.1f), pk(119f, -2.7f, 0.23f), pk(730f, 3.2f, 1.22f), pk(2241f, -3.5f, 3.8f), pk(9459f, 3.2f, 3.19f),
            pk(56f, -0.6f, 2.62f), pk(2633f, -2.0f, 5.29f), pk(3721f, 3.4f, 2.02f), pk(5483f, -4.7f, 4.79f), hs(10000f, -1.7f)
        )),
        Profile("Sony WH-1000XM4", -6.2f, listOf(
            ls(105f, -4.2f), pk(56f, 1.2f, 1.19f), pk(143f, -5.2f, 1.1f), pk(2289f, 6.1f, 1.57f), pk(5144f, -3.2f, 6.0f),
            pk(407f, 1.7f, 3.14f), pk(576f, -1.2f, 3.55f), pk(1007f, 1.0f, 3.41f), pk(6715f, 3.0f, 5.99f), hs(10000f, -1.0f)
        )),
        Profile("Moondrop Aria (IEM)", -3.5f, listOf(
            ls(105f, -0.2f), pk(164f, -2.6f, 0.92f), pk(659f, 1.4f, 1.59f), pk(3108f, 0.5f, 1.51f), pk(6460f, 3.5f, 2.43f),
            pk(31f, -0.4f, 2.21f), pk(54f, 0.9f, 5.32f), pk(1408f, -0.5f, 2.3f), pk(8665f, 2.1f, 2.79f), hs(10000f, -5.3f)
        )),
        Profile("Sennheiser HD 600", -6.4f, listOf(
            ls(105f, 6.5f), pk(125f, -2.7f, 0.55f), pk(522f, 0.7f, 1.02f), pk(1298f, -1.2f, 2.14f), pk(8445f, 3.3f, 1.61f),
            pk(2166f, 0.9f, 3.32f), pk(3158f, -1.8f, 3.67f), pk(5433f, -1.2f, 5.7f), pk(6639f, 2.2f, 5.82f), hs(10000f, -3.1f)
        )),
        Profile("Sony WF-1000XM4", -7.6f, listOf(
            ls(105f, -0.6f), pk(176f, -4.2f, 0.6f), pk(498f, -1.4f, 1.46f), pk(6316f, 4.4f, 0.91f), pk(9973f, 3.7f, 1.01f),
            pk(38f, -0.2f, 2.02f), pk(978f, -0.3f, 1.49f), pk(1732f, 1.2f, 2.28f), pk(3942f, -0.8f, 3.1f), hs(10000f, 3.7f)
        )),
        Profile("Samsung Galaxy Buds2 Pro", -2.4f, listOf(
            ls(105f, -2.3f), pk(64f, 2.2f, 1.18f), pk(266f, -0.8f, 1.35f), pk(3426f, -2.3f, 3.7f), pk(7526f, 2.2f, 2.78f),
            pk(977f, 1.0f, 5.18f), pk(2283f, 1.2f, 3.75f), pk(4955f, -1.2f, 5.11f), pk(8817f, 1.6f, 2.28f), hs(10000f, -3.8f)
        )),
    )

    fun findByModel(name: String): Profile? = profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun normName(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * Empareja un nombre de producto Bluetooth/USB ("WH-1000XM4", "Galaxy Buds2
     * Pro") con el perfil medido más probable. Puntúa tokens del nombre del
     * perfil presentes en el producto (los tokens con dígitos, o sea números de
     * modelo, pesan triple). Exige un número de modelo o al menos 2 palabras
     * para no corregir unos audífonos con la curva de otros.
     */
    fun findBestMatch(productName: String): Profile? {
        val name = normName(productName)
        if (name.isBlank()) return null
        var best: Profile? = null
        var bestScore = 0
        var bestDigitHit = false
        var bestCount = 0
        for (p in profiles) {
            val tokens = normName(p.name).split(" ").filter { it.length >= 2 }
            var score = 0
            var digitHit = false
            var count = 0
            for (t in tokens) {
                if ((" $name ").contains(" $t ")) {
                    count++
                    if (t.any { it.isDigit() }) {
                        score += 3
                        digitHit = true
                    } else {
                        score += 1
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestDigitHit = digitHit
                bestCount = count
                best = p
            }
        }
        if (bestScore >= 2 && (bestDigitHit || bestCount >= 2)) return best
        return null
    }
}