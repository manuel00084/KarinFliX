package com.karin.streamtv.model

import java.util.UUID

data class SiteConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val icon: String = name.firstOrNull()?.uppercase() ?: "?",
    val isActive: Boolean = true,
    val lastVisited: Long = System.currentTimeMillis()
)
