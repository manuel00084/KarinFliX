package com.karin.streamtv.util

/**
 * HTML entity decoder + URL resolver (equivalente a HTMLUtil / Url::Resolve de Kodi).
 * Los títulos sacados de atributos HTML (alt/title) llegan con &amp;,&quot;,&#039;,
 * y los thumbs/links vía data-src o JSON suelen ser relativos; aquí se limpian.
 */
object HtmlClean {

    private val NAMED = mapOf(
        "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
        "nbsp" to " ", "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…",
        "ndash" to "–", "mdash" to "—", "middot" to "·", "bull" to "•", "deg" to "°",
        "laquo" to "«", "raquo" to "»", "iexcl" to "¡", "iquest" to "¿", "times" to "×",
        "divide" to "÷", "plusmn" to "±", "sect" to "§", "para" to "¶", "cent" to "¢",
        "pound" to "£", "euro" to "€", "yen" to "¥", "agrave" to "à", "aacute" to "á",
        "acirc" to "â", "atilde" to "ã", "auml" to "ä", "aring" to "å", "aelig" to "æ",
        "ccedil" to "ç", "egrave" to "è", "eacute" to "é", "ecirc" to "ê", "euml" to "ë",
        "igrave" to "ì", "iacute" to "í", "icirc" to "î", "iuml" to "ï", "eth" to "ð",
        "ntilde" to "ñ", "ograve" to "ò", "oacute" to "ó", "ocirc" to "ô", "otilde" to "õ",
        "ouml" to "ö", "oslash" to "ø", "ugrave" to "ù", "uacute" to "ú", "ucirc" to "û",
        "uuml" to "ü", "yacute" to "ý", "thorn" to "þ", "szlig" to "ß", "shy" to ""
    )

    /** Decodifica entidades HTML y colapsa whitespace. No toca texto plano normal. */
    fun clean(input: String): String {
        if (input.isEmpty()) return input
        var s = input.replace(Regex("""<[^>]+>"""), " ")
        s = s.replace(Regex("""&#x([0-9a-fA-F]+);""")) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { it.toChar().toString() } ?: m.value
        }
        s = s.replace(Regex("""&#(\d+);""")) { m ->
            m.groupValues[1].toIntOrNull()?.let { it.toChar().toString() } ?: m.value
        }
        s = s.replace(Regex("""&([a-zA-Z][a-zA-Z0-9]{1,12});""")) { m ->
            NAMED[m.groupValues[1].lowercase()] ?: m.value
        }
        return s.trim().replace(Regex("""\s+"""), " ")
    }

    /**
     * Resuelve una referencia relativa contra una base (RFC 3986). Maneja
     * URLs absolutas, protocol-relative (//host) y relativas simples.
     */
    fun resolveUrl(base: String, ref: String): String {
        val r = ref.trim()
        if (r.isEmpty()) return r
        if (r.startsWith("//")) {
            val scheme = try { java.net.URI(base).scheme } catch (_: Exception) { null }
            return (scheme ?: "https") + ":" + r
        }
        if (r.contains("://") || r.startsWith("mailto:") || r.startsWith("data:")) return r
        if (base.isBlank()) return r
        return try {
            java.net.URI(base).resolve(r).toString()
        } catch (_: Exception) {
            val sep = if (base.endsWith("/")) "" else "/"
            base + sep + r.removePrefix("/")
        }
    }
}
