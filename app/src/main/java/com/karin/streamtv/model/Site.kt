package com.karin.streamtv.model

data class Site(
    val name: String,
    val url: String,
    val description: String,
    val iconUrl: String = ""
)
