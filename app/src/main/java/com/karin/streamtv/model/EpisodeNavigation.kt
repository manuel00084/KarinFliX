package com.karin.streamtv.model

data class EpisodeNavigation(
    val prevUrl: String? = null,
    val prevTitle: String? = null,
    val nextUrl: String? = null,
    val nextTitle: String? = null,
    val listUrl: String? = null
)
