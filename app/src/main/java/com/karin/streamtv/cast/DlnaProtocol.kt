package com.karin.streamtv.cast

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DLNA / UPnP-AV por SSDP + SOAP, sin dependencias externas.
 *
 * Todo lo que es texto o cálculo vive aquí (puro, sin Android) para poder
 * probarlo en la JVM: el descubrimiento por sí solo no se puede probar sin
 * red, pero el M-SEARCH, el parseo de la descripción del dispositivo y los
 * envelopes SOAP sí.
 *
 * Flujo: M-SEARCH a 239.255.255.250:1900 → cabecera LOCATION → GET del XML →
 * serviceType AVTransport:1 → controlURL → SetAVTransportURI + Play.
 */
object DlnaProtocol {

    const val SSDP_HOST = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Receptor DLNA listo para recibir vídeo. */
    data class Renderer(
        val name: String,
        val manufacturer: String,
        val location: String,
        val controlUrl: String,
        val serviceType: String,
    )

    /** Cabeceras M-SEARCH de un MediaRenderer. */
    fun msearch(target: String = MEDIA_RENDERER, mx: Int = 3): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $mx\r\n")
        append("ST: $target\r\n")
        append("\r\n")
    }

    /** Cabeceras de un mensaje SSDP en mapa insensible a mayúsculas. */
    fun parseSsdpMessage(text: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        val lines = text.split("\r\n", "\n")
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) break
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            headers[line.substring(0, sep).trim()] = line.substring(sep + 1).trim()
        }
        return headers
    }

    /** LOCATION de una respuesta SSDP, o null si no la trae. */
    fun locationOf(headers: Map<String, String>): String? = headers.entries
        .firstOrNull { it.key.equals("location", ignoreCase = true) }
        ?.value?.trim()
        ?.takeIf { it.isNotEmpty() }

    /**
     * Extrae el AVTransport de la descripción UPnP del dispositivo.
     * Devuelve null si no es un MediaRenderer o si el XML no se puede leer.
     */
    fun parseRenderer(xml: String, location: String): Renderer? {
        if (xml.isBlank()) return null
        return try {
            val doc = secureDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
            val root = doc.documentElement ?: return null

            var name = ""
            var manufacturer = ""
            val devices = root.getElementsByTagName("device")
            for (i in 0 until devices.length) {
                val device = devices.item(i) as? Element ?: continue
                if (name.isEmpty()) name = childText(device, "friendlyName").orEmpty()
                if (manufacturer.isEmpty()) manufacturer = childText(device, "manufacturer").orEmpty()
            }

            var controlUrl: String? = null
            var serviceType: String? = null
            val services = root.getElementsByTagName("service")
            for (i in 0 until services.length) {
                val service = services.item(i) as? Element ?: continue
                val type = childText(service, "serviceType").orEmpty()
                if (type.isBlank() || !type.contains("AVTransport")) continue
                val control = childText(service, "controlURL")?.takeIf { it.isNotBlank() } ?: continue
                serviceType = type
                controlUrl = control
                break
            }
            if (controlUrl == null) return null

            Renderer(
                name = name.ifBlank { hostOf(location) },
                manufacturer = manufacturer,
                location = location,
                controlUrl = resolveUrl(location, controlUrl),
                serviceType = serviceType ?: AV_TRANSPORT,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * El XML viene de la red, así que se cierra el parser: sin DTD, sin
     * entidades externas ni XInclude. Si el parser de la plataforma no
     * soporta algún rasgo, se sigue intentando en lugar de romper la
     * lectura de un dispositivo legítimo.
     */
    private fun secureDocumentBuilder(): javax.xml.parsers.DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        for (feature in XXE_FEATURES) {
            try {
                factory.setFeature(feature, true)
            } catch (_: Exception) {
                // Rasgo no soportado por este parser: continuar con el resto.
            }
        }
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.isExpandEntityReferences = false }
        return factory.newDocumentBuilder()
    }

    private val XXE_FEATURES = listOf(
        "http://apache.org/xml/features/disallow-doctype-decl",
        "http://xml.org/sax/features/external-general-entities",
        "http://xml.org/sax/features/external-parameter-entities",
    )

    /** Resuelve una controlURL relativa (o absoluta) contra la LOCATION. */
    fun resolveUrl(base: String, ref: String): String {
        val r = ref.trim()
        if (r.startsWith("http://") || r.startsWith("https://")) return r
        val schemeSep = base.indexOf("://")
        if (schemeSep < 0) return r
        val pathStart = base.indexOf('/', schemeSep + 3)
        val origin = if (pathStart < 0) base else base.substring(0, pathStart)
        val joined = if (r.startsWith("/")) {
            origin + r
        } else {
            val dir = if (pathStart < 0) "$origin/" else base.substring(0, base.lastIndexOf('/') + 1)
            dir + r
        }
        return normalize(joined)
    }

    private fun normalize(url: String): String {
        val schemeSep = url.indexOf("://")
        val pathStart = url.indexOf('/', schemeSep + 3)
        if (pathStart < 0) return url
        val origin = url.substring(0, pathStart)
        val segments = ArrayList<String>()
        for (segment in url.substring(pathStart + 1).split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return origin + "/" + segments.joinToString("/")
    }

    private fun hostOf(url: String): String {
        val schemeSep = url.indexOf("://")
        val start = if (schemeSep < 0) 0 else schemeSep + 3
        val rest = url.substring(start)
        val end = rest.indexOfFirst { it == '/' || it == ':' || it == '?' }
        return if (end < 0) rest else rest.substring(0, end)
    }

    private fun childText(parent: Element, tag: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == tag) return node.textContent?.trim()
        }
        return null
    }

    /** MIME que el receptor debe anunciar para el contenido. */
    fun mimeTypeFor(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".avi") -> "video/x-msvideo"
            path.endsWith(".mov") -> "video/quicktime"
            path.endsWith(".mp4") -> "video/mp4"
            else -> "video/mp4"
        }
    }

    /** Metadatos DIDL-Lite que acompañan a la URL en SetAVTransportURI. */
    fun didlLite(uri: String, title: String, mimeType: String): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"")
        append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
        append(" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
        append("<dc:title>").append(escape(title)).append("</dc:title>")
        append("<upnp:class>object.item.videoItem</upnp:class>")
        append("<res protocolInfo=\"http-get:*:").append(mimeType).append(":*\">")
        append(escape(uri))
        append("</res></item></DIDL-Lite>")
    }

    fun setAvTransportXml(uri: String, metadata: String): String = soapEnvelope(
        serviceType = AV_TRANSPORT,
        action = "SetAVTransportURI",
        args = "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${escape(uri)}</CurrentURI>" +
            "<CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>",
    )

    fun playXml(speed: String = "1"): String = soapEnvelope(
        serviceType = AV_TRANSPORT,
        action = "Play",
        args = "<InstanceID>0</InstanceID><Speed>${escape(speed)}</Speed>",
    )

    fun pauseXml(): String = soapEnvelope(
        serviceType = AV_TRANSPORT,
        action = "Pause",
        args = "<InstanceID>0</InstanceID>",
    )

    fun stopXml(): String = soapEnvelope(
        serviceType = AV_TRANSPORT,
        action = "Stop",
        args = "<InstanceID>0</InstanceID>",
    )

    fun getTransportInfoXml(): String = soapEnvelope(
        serviceType = AV_TRANSPORT,
        action = "GetTransportInfo",
        args = "<InstanceID>0</InstanceID>",
    )

    /** Valor de la cabecera SOAPAction (con comillas, como exige UPnP). */
    fun soapAction(serviceType: String, action: String): String = "\"$serviceType#$action\""

    private fun soapEnvelope(serviceType: String, action: String, args: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
            "<s:Body><u:$action xmlns:u=\"$serviceType\">$args</u:$action></s:Body>\r\n" +
            "</s:Envelope>"

    /** Estado de transporte devuelto por GetTransportInfo. */
    fun parseTransportState(xml: String): String? {
        val match = REGEX_CURRENT_STATE.find(xml) ?: return null
        return match.groupValues[1].trim().ifBlank { null }
    }

    fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private val REGEX_CURRENT_STATE = Regex(
        "<CurrentTransportState>\\s*([^<]+?)\\s*</CurrentTransportState>",
        RegexOption.IGNORE_CASE,
    )
}
