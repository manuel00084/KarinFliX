package com.karin.streamtv.util

import com.karin.streamtv.model.MenuSection

/**
 * Secciones con URL directa conocida (capa UI; no toca scrapers).
 * Origen: menú del sitio cuando existe, si no esta tabla (verificada en vivo).
 */
object SiteSections {

    private val direct: Map<String, Map<MenuSection, String>> = mapOf(
        "PeliPops" to mapOf(
            MenuSection.MOVIES to "https://pelispop.mov/peliculas",
            MenuSection.SERIES to "https://pelispop.mov/series",
            MenuSection.ANIME to "https://pelispop.mov/animes",
            MenuSection.DORAMA to "https://pelispop.mov/generos/dorama",
        ),
        "CineCalidad" to mapOf(
            MenuSection.MOVIES to "https://cine-calidad.mx/peliculas",
            MenuSection.SERIES to "https://cine-calidad.mx/serie",
            MenuSection.ANIME to "https://cine-calidad.mx/anime",
        ),
        "AnimeFLV" to mapOf(
            MenuSection.ANIME to "https://vww.animeflv.one/animes",
            MenuSection.DIRECTORY to "https://vww.animeflv.one/animes",
        ),
        "DonghuaLife" to mapOf(
            MenuSection.DIRECTORY to "https://donghualife.com/donghuas",
            MenuSection.DONGHUA to "https://donghualife.com/donghuas",
            MenuSection.MOVIES to "https://donghualife.com/movies",
        ),
        "Pandrama" to mapOf(
            MenuSection.DORAMA to "https://www.pandrama.tv",
            MenuSection.DIRECTORY to "https://www.pandrama.tv",
        ),
    )

    /** URL directa de la sección, o null si el sitio no la declara. */
    fun directUrl(siteName: String, section: MenuSection): String? =
        direct[siteName]?.get(section)

    /** ¿El sitio ofrece la sección? URL directa válida (no la portada) o menú. */
    fun supported(
        siteName: String,
        section: MenuSection,
        homeUrl: String?,
        menuHasSection: Boolean,
    ): Boolean {
        val direct = directUrl(siteName, section)
        if (!direct.isNullOrBlank()) {
            if (homeUrl.isNullOrBlank()) return true
            return direct != homeUrl && direct != "$homeUrl/" && direct != "$homeUrl/index.php"
        }
        return menuHasSection
    }
}
