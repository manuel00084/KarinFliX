package com.karin.streamtv.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

object VideoDataSource {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun factory(referer: String = ""): DataSource.Factory {
        val headers = mutableMapOf<String, String>()
        if (referer.isNotBlank()) {
            headers["Referer"] = if (referer.startsWith("http")) referer else "https://$referer"
        }
        return DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
    }
}
