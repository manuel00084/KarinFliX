package com.karin.streamtv.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que viaja por la red en una emisión DLNA.
 *
 * El descubrimiento real necesita un receptor en la red, así que aquí se cubre
 * lo que sí es determinista: el M-SEARCH, la lectura de la descripción del
 * dispositivo (de la que sale la controlURL), los envelopes SOAP y el escaping.
 */
class DlnaProtocolTest {

    private val description = """
        <?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Sala-Tv</friendlyName>
            <manufacturer>Fabricante</manufacturer>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/RenderingControl/control</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>/upnp/AVTransport/control</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    private val location = "http://192.168.1.70:1400/description.xml"

    @Test
    fun `m-search targets a media renderer`() {
        val request = DlnaProtocol.msearch()
        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1"))
        assertTrue(request.contains("HOST: 239.255.255.250:1900"))
        assertTrue(request.contains("""MAN: "ssdp:discover""""))
        assertTrue(request.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun `reads the location header whatever its case`() {
        val headers = DlnaProtocol.parseSsdpMessage(
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nlocation: http://192.168.1.70:1400/desc\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        )
        assertEquals("http://192.168.1.70:1400/desc", DlnaProtocol.locationOf(headers))
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", headers["ST"])
    }

    @Test
    fun `location is null when the answer carries none`() {
        val headers = DlnaProtocol.parseSsdpMessage("HTTP/1.1 200 OK\r\nST: ssdp:all\r\n\r\n")
        assertNull(DlnaProtocol.locationOf(headers))
    }

    @Test
    fun `reads the avtransport control url of a renderer`() {
        val renderer = DlnaProtocol.parseRenderer(description, location)!!
        assertEquals("Sala-Tv", renderer.name)
        assertEquals("Fabricante", renderer.manufacturer)
        assertEquals("http://192.168.1.70:1400/upnp/AVTransport/control", renderer.controlUrl)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", renderer.serviceType)
    }

    @Test
    fun `keeps an absolute control url untouched`() {
        val xml = description.replace(
            "/upnp/AVTransport/control",
            "http://192.168.1.70:1400/abs/control",
        )
        val renderer = DlnaProtocol.parseRenderer(xml, location)!!
        assertEquals("http://192.168.1.70:1400/abs/control", renderer.controlUrl)
    }

    @Test
    fun `reads services nested inside embedded devices`() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>Bar</friendlyName>
                <deviceList>
                  <device>
                    <friendlyName>Bar-oculto</friendlyName>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <controlURL>/t/control</controlURL>
                      </service>
                    </serviceList>
                  </device>
                </deviceList>
              </device>
            </root>
        """.trimIndent()
        val renderer = DlnaProtocol.parseRenderer(xml, location)!!
        assertEquals("Bar", renderer.name)
        assertEquals("http://192.168.1.70:1400/t/control", renderer.controlUrl)
    }

    @Test
    fun `rejects a device without avtransport`() {
        val xml = """
            <root><device><friendlyName>Router</friendlyName>
            <serviceList><service>
              <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
              <controlURL>/wan/control</controlURL>
            </service></serviceList></device></root>
        """.trimIndent()
        assertNull(DlnaProtocol.parseRenderer(xml, location))
    }

    @Test
    fun `rejects a broken description`() {
        assertNull(DlnaProtocol.parseRenderer("<root><device>", location))
        assertNull(DlnaProtocol.parseRenderer("", location))
    }

    @Test
    fun `resolves relative and root relative control urls`() {
        assertEquals(
            "http://192.168.1.70:1400/upnp/AVTransport/control",
            DlnaProtocol.resolveUrl(location, "/upnp/AVTransport/control"),
        )
        assertEquals(
            "http://192.168.1.70:1400/description.xml",
            DlnaProtocol.resolveUrl(location, "description.xml"),
        )
        // /upnp/x.xml + ../control → se quita el segmento actual y luego el padre.
        assertEquals(
            "http://192.168.1.70:1400/control",
            DlnaProtocol.resolveUrl("http://192.168.1.70:1400/upnp/x.xml", "../control"),
        )
    }

    @Test
    fun `set av transport carries the url and its metadata`() {
        val metadata = DlnaProtocol.didlLite(
            uri = "https://cdn.example.com/a.mp4?x=1&y=2",
            title = "Ep 1",
            mimeType = "video/mp4",
        )
        val envelope = DlnaProtocol.setAvTransportXml("https://cdn.example.com/a.mp4?x=1&y=2", metadata)

        assertTrue(envelope.contains("<u:SetAVTransportURI"))
        assertTrue(envelope.contains("<InstanceID>0</InstanceID>"))
        // La URL viaja escapada dentro del XML...
        assertTrue(envelope.contains("CurrentURI>https://cdn.example.com/a.mp4?x=1&amp;y=2<"))
        // ...y los metadatos, como texto, llevan su propio escaping.
        assertTrue(envelope.contains("&lt;DIDL-Lite"))
        assertFalse(envelope.contains("<DIDL-Lite"))
    }

    @Test
    fun `play uses speed 1 and pause needs no argument`() {
        val play = DlnaProtocol.playXml()
        assertTrue(play.contains("<u:Play"))
        assertTrue(play.contains("<Speed>1</Speed>"))

        val pause = DlnaProtocol.pauseXml()
        assertTrue(pause.contains("<u:Pause"))
        assertTrue(pause.contains("<InstanceID>0</InstanceID>"))
        assertFalse(pause.contains("<Speed>"))
    }

    @Test
    fun `soap action is quoted and namespaced`() {
        assertEquals(
            "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            DlnaProtocol.soapAction(DlnaProtocol.AV_TRANSPORT, "Play"),
        )
    }

    @Test
    fun `didl lite escapes title and url`() {
        val didl = DlnaProtocol.didlLite(
            uri = "http://a.b/c.mp4?a=1&b=2",
            title = "Tom & Jerry <3",
            mimeType = "video/mp4",
        )
        assertTrue(didl.contains("<dc:title>Tom &amp; Jerry &lt;3</dc:title>"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:video/mp4:*\""))
        assertTrue(didl.contains("http://a.b/c.mp4?a=1&amp;b=2"))
        assertTrue(didl.contains("object.item.videoItem"))
    }

    @Test
    fun `mime type follows the extension`() {
        assertEquals("application/vnd.apple.mpegurl", DlnaProtocol.mimeTypeFor("http://a/b.m3u8?token=1"))
        assertEquals("application/dash+xml", DlnaProtocol.mimeTypeFor("http://a/b.mpd"))
        assertEquals("video/webm", DlnaProtocol.mimeTypeFor("http://a/b.webm"))
        assertEquals("video/mp4", DlnaProtocol.mimeTypeFor("http://a/b.mp4"))
        assertEquals("video/mp4", DlnaProtocol.mimeTypeFor("/storage/sin-extension"))
    }

    @Test
    fun `reads the transport state back from the receiver`() {
        val response = """
            <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
              <CurrentTransportState>PLAYING</CurrentTransportState>
              <CurrentTransportStatus>OK</CurrentTransportStatus>
              <CurrentSpeed>1</CurrentSpeed>
            </u:GetTransportInfoResponse>
        """.trimIndent()
        assertEquals("PLAYING", DlnaProtocol.parseTransportState(response))
        assertNull(DlnaProtocol.parseTransportState("<u:Fault></u:Fault>"))
    }

    @Test
    fun `escapes the five xml specials`() {
        assertEquals(
            "&amp;&lt;&gt;&quot;&apos;",
            DlnaProtocol.escape("&<>\"'"),
        )
        assertEquals("sin&amp;", DlnaProtocol.escape("sin&"))
        assertNotNull(DlnaProtocol.escape("ok"))
    }
}
