package com.karin.streamtv.model

data class Anime(
    val title: String,
    val url: String,
    val imageUrl: String = "",
    val type: String = "",
    val episodes: Int = 0,
    val status: String = ""
)
