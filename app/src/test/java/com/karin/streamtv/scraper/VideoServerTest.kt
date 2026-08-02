package com.karin.streamtv.scraper

import com.karin.streamtv.model.VideoServer
import org.junit.Assert.*
import org.junit.Test

class VideoServerTest {

    @Test
    fun `detectServer identifies DsvPlay`() {
        assertEquals(VideoServer.DSVPLAY, VideoServer.detectServer("https://dsvplay.com/embed/video123"))
    }

    @Test
    fun `detectServer identifies VOE`() {
        assertEquals(VideoServer.VOE, VideoServer.detectServer("https://voe.sx/e/abc123"))
    }

    @Test
    fun `detectServer identifies Mp4Upload`() {
        assertEquals(VideoServer.MP4UPLOAD, VideoServer.detectServer("https://www.mp4upload.com/embed-0eill84h6nux.html"))
    }

    @Test
    fun `detectServer identifies Hexload`() {
        assertEquals(VideoServer.HEXLOAD, VideoServer.detectServer("https://hexload.com/embed/xyz"))
    }

    @Test
    fun `detectServer identifies MixDrop`() {
        assertEquals(VideoServer.MIXDROP, VideoServer.detectServer("https://mixdrop.co/embed/video"))
    }

    @Test
    fun `detectServer identifies Streamtape`() {
        assertEquals(VideoServer.STREAMTAPE, VideoServer.detectServer("https://streamtape.com/v/abc123"))
    }

    @Test
    fun `detectServer identifies DoodStream`() {
        assertEquals(VideoServer.DOODSTREAM, VideoServer.detectServer("https://d0000d.com/embed/xyz"))
    }

    @Test
    fun `detectServer identifies Fembed`() {
        assertEquals(VideoServer.FEMBED, VideoServer.detectServer("https://fembed.com/v/12345"))
    }

    @Test
    fun `detectServer identifies SaveFiles`() {
        assertEquals(VideoServer.SAVEFILES, VideoServer.detectServer("https://savefiles.com/embed/video"))
    }

    @Test
    fun `detectServer identifies Mega`() {
        assertEquals(VideoServer.MEGA, VideoServer.detectServer("https://mega.nz/file/abc123"))
    }

    @Test
    fun `detectServer identifies Byse`() {
        assertEquals(VideoServer.BYSE, VideoServer.detectServer("https://byse.to/embed/video"))
    }

    @Test
    fun `detectServer identifies Generic for unknown URL`() {
        assertEquals(VideoServer.GENERIC, VideoServer.detectServer("https://unknown-server.com/video"))
    }

    @Test
    fun `detectServer is case insensitive`() {
        assertEquals(VideoServer.DSVPLAY, VideoServer.detectServer("https://DSVPLAY.com/embed/video"))
        assertEquals(VideoServer.VOE, VideoServer.detectServer("https://VOE.SX/e/test"))
        assertEquals(VideoServer.MP4UPLOAD, VideoServer.detectServer("https://MP4UPLOAD.com/embed/test"))
    }

    @Test
    fun `detectServer identifies StreamWish`() {
        assertEquals(VideoServer.STREAMWISH, VideoServer.detectServer("https://streamwish.com/embed/video"))
    }

    @Test
    fun `detectServer identifies FileMoon`() {
        assertEquals(VideoServer.FILEMOON, VideoServer.detectServer("https://filemoon.sx/e/abc"))
    }

    @Test
    fun `all LatAnime servers are detected`() {
        val testUrls = mapOf(
            "https://dsvplay.com/embed/test" to VideoServer.DSVPLAY,
            "https://byse.to/embed/test" to VideoServer.BYSE,
            "https://hexload.com/embed/test" to VideoServer.HEXLOAD,
            "https://savefiles.com/embed/test" to VideoServer.SAVEFILES,
            "https://mega.nz/embed/test" to VideoServer.MEGA,
            "https://mixdrop.co/embed/test" to VideoServer.MIXDROP,
            "https://voe.sx/e/test" to VideoServer.VOE,
            "https://mp4upload.com/embed/test" to VideoServer.MP4UPLOAD
        )

        for ((url, expected) in testUrls) {
            assertEquals("Failed for $url", expected, VideoServer.detectServer(url))
        }
    }
}
