package com.karin.streamtv.extractor

interface ServerExtractor {
    val serverName: String
    val priority: Int
    fun extract(html: String, embedUrl: String, context: Any? = null): String?
}
