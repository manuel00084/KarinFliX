package com.karin.streamtv.model

data class VideoSource(
    val name: String,
    val serverUrl: String,
    val quality: String = "",
    val language: String = "Latino",
    val isPreferred: Boolean = false,
    val supportsResolutionChange: Boolean = false,
    val speedRating: Int = 1,
    val pingMs: Long = -1
) {
    companion object {
        fun create(server: VideoServer, url: String, name: String? = null, isPreferred: Boolean? = null): VideoSource = VideoSource(
            name = name ?: server.displayName,
            serverUrl = url,
            supportsResolutionChange = server.supportsResolution,
            speedRating = server.speedRating,
            isPreferred = isPreferred ?: server in listOf(
                VideoServer.FEMBED, VideoServer.STREAMTAPE,
                VideoServer.DOODSTREAM, VideoServer.VOE
            )
        )
    }
}

enum class VideoServer(
    val displayName: String,
    val patterns: List<String>,
    val supportsResolution: Boolean = false,
    val speedRating: Int = 1,
    val priority: Int = 0,
    val webViewOnly: Boolean = false
) {
    STREAMTAPE(
        displayName = "Streamtape",
        patterns = listOf("streamtape", "stape", "stp"),
        supportsResolution = true,
        speedRating = 5,
        priority = 100
    ),
    DOODSTREAM(
        displayName = "DoodStream",
        patterns = listOf("doodstream", "dood", "d0000d", "dwsfx", "dooood"),
        supportsResolution = true,
        speedRating = 5,
        priority = 95
    ),
    FEMBED(
        displayName = "Fembed",
        patterns = listOf("fembed", "fem", "24hd", "feurl", "vcdn"),
        supportsResolution = true,
        speedRating = 4,
        priority = 90
    ),
    VOE(
        displayName = "VOE",
        patterns = listOf("voe", "voe.sx"),
        supportsResolution = true,
        speedRating = 5,
        priority = 85
    ),
    NUPLOAD(
        displayName = "Nuuuppp",
        patterns = listOf("nupload", "nuuuppp"),
        supportsResolution = true,
        speedRating = 4,
        priority = 82
    ),
    STREAMSB(
        displayName = "StreamSB",
        patterns = listOf("streamsb", "sbplay", "sblong", "sbfull", "sbembed"),
        supportsResolution = true,
        speedRating = 3,
        priority = 75
    ),
    MEGA(
        displayName = "Mega",
        patterns = listOf("mega.nz", "mega.co"),
        supportsResolution = false,
        speedRating = 4,
        priority = 70,
        webViewOnly = true
    ),
    STREAMWISH(
        displayName = "StreamWish",
        patterns = listOf("streamwish", "wish", "swish", "embedwish"),
        supportsResolution = true,
        speedRating = 3,
        priority = 60
    ),
    YOURUPLOAD(
        displayName = "YourUpload",
        patterns = listOf("yourupload"),
        supportsResolution = false,
        speedRating = 3,
        priority = 55
    ),
    OKRU(
        displayName = "OK.ru",
        patterns = listOf("ok.ru", "odnoklassniki"),
        supportsResolution = true,
        speedRating = 2,
        priority = 50
    ),
    VK(
        displayName = "VK",
        patterns = listOf("vk.com", "vkvd", "vkvideo"),
        supportsResolution = true,
        speedRating = 3,
        priority = 45
    ),
    MP4UPLOAD(
        displayName = "Mp4Upload",
        patterns = listOf("mp4upload"),
        supportsResolution = false,
        speedRating = 2,
        priority = 40
    ),
    SENDVID(
        displayName = "SendVid",
        patterns = listOf("sendvid"),
        supportsResolution = false,
        speedRating = 3,
        priority = 35
    ),
    RUTUBE(
        displayName = "Rutube",
        patterns = listOf("rutube"),
        supportsResolution = true,
        speedRating = 2,
        priority = 30
    ),
    NETU(
        displayName = "NetU",
        patterns = listOf("netu", "netutv", "hqq"),
        supportsResolution = false,
        speedRating = 2,
        priority = 25
    ),
    UQLOAD(
        displayName = "Uqload",
        patterns = listOf("uqload"),
        supportsResolution = false,
        speedRating = 2,
        priority = 20
    ),
    GURO(
        displayName = "Guro",
        patterns = listOf("guro", "gurou"),
        supportsResolution = false,
        speedRating = 2,
        priority = 15
    ),
    DIRECT(
        displayName = "Directo",
        patterns = listOf(".mp4", ".m3u8", ".webm"),
        supportsResolution = true,
        speedRating = 5,
        priority = 200
    ),
    DSVPLAY(
        displayName = "DsvPlay",
        patterns = listOf("dsvplay"),
        supportsResolution = true,
        speedRating = 3,
        priority = 80
    ),
    BYSE(
        displayName = "Byse",
        patterns = listOf("byse", "bysekoze"),
        supportsResolution = false,
        speedRating = 3,
        priority = 75,
        webViewOnly = true
    ),
    HEXLOAD(
        displayName = "Hexload",
        patterns = listOf("hexload"),
        supportsResolution = false,
        speedRating = 3,
        priority = 65,
        webViewOnly = true
    ),
    SAVEFILES(
        displayName = "SaveFiles",
        patterns = listOf("savefiles"),
        supportsResolution = false,
        speedRating = 2,
        priority = 55
    ),
    MIXDROP(
        displayName = "MixDrop",
        patterns = listOf("mixdrop"),
        supportsResolution = false,
        speedRating = 3,
        priority = 50
    ),
    FILEMOON(
        displayName = "FileMoon",
        patterns = listOf("filemoon", "filemoon.sx", "filemoon.to", "filemoon.nu"),
        supportsResolution = true,
        speedRating = 4,
        priority = 92
    ),
    LULU(
        displayName = "Lulu",
        patterns = listOf("lulu", "lulustream", "lulu.to", "luluvdo"),
        supportsResolution = false,
        speedRating = 3,
        priority = 68
    ),
    MXDROP(
        displayName = "MXDrop",
        patterns = listOf("mxdrop", "mxdplayer"),
        supportsResolution = false,
        speedRating = 3,
        priority = 52
    ),
    DORAMASYT_REPRODUCTOR(
        displayName = "DoramasYT",
        patterns = listOf("doramasyt.com/reproductor"),
        supportsResolution = false,
        speedRating = 3,
        priority = 88,
        webViewOnly = true
    ),
    TOROPLAY_EMBED(
        displayName = "Reproductor",
        patterns = listOf("trembed="),
        supportsResolution = false,
        speedRating = 2,
        priority = 87,
        webViewOnly = true
    ),

    GENERIC(
        displayName = "Servidor",
        patterns = listOf(),
        supportsResolution = false,
        speedRating = 1,
        priority = 0
    );

    companion object {
        fun detectServer(url: String): VideoServer {
            val lower = url.lowercase()
            return entries.firstOrNull { server ->
                server.patterns.any { pattern -> lower.contains(pattern) }
            } ?: GENERIC
        }

        fun detectFromIframeSrc(src: String): VideoServer {
            return detectServer(src)
        }
    }
}
