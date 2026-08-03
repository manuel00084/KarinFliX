package com.karin.streamtv.model

data class PlaylistItem(
    val title: String = "",
    val url: String = "",
    val embedUrl: String = "",
    val serverName: String = "",
    val episodeNumber: Int = 0
)
