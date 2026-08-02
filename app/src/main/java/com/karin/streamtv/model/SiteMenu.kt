package com.karin.streamtv.model

data class SiteMenuItem(
    val name: String,
    val url: String,
    val section: MenuSection = MenuSection.OTHER
)

enum class MenuSection {
    NEW_EPISODES,
    DIRECTORY,
    GENRES,
    SCHEDULE,
    POPULAR,
    MOVIES,
    SERIES,
    ANIME,
    DONGHUA,
    DORAMA,
    SEASONAL,
    OTHER
}
