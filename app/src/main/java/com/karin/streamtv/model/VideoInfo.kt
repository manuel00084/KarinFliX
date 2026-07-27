package com.karin.streamtv.model

data class VideoInfo(
    val url: String,
    val title: String = "",
    val thumbnailUrl: String = "",
    val duration: Long = 0,
    val isDirectUrl: Boolean = false,
    val type: VideoType = VideoType.UNKNOWN
)

enum class VideoType {
    MP4,
    M3U8,
    DASH,
    YOUTUBE,
    EMBED,
    DIRECT,
    UNKNOWN
}
