package com.karin.streamtv.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Episode(
    val title: String,
    val url: String,
    val thumbnailUrl: String = "",
    val date: String = "",
    val siteName: String = "",
    val episodeNum: String = ""
) : Parcelable
