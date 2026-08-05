package com.karin.streamtv.player.dsp

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Parser de archivos WAV para IRs de usuario.
 *
 * Soporta PCM 16/24/32-bit y IEEE float 32, mono o multi-canal (los canales se
 * downmixean a mono). Decodifica, recorta silencio, limita la longitud y
 * normaliza el pico. El resultado se empaqueta en base64 para guardarlo en
 * SharedPreferences ([encode]/[decode]) y se carga en el [Convolver].
 */
object WavIr {
    fun displayName(resolver: ContentResolver, uri: Uri): String {
        var name: String? = null
        try {
            resolver.query(uri, null, null, null, null)?.let { c ->
                try {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
                } finally {
                    c.close()
                }
            }
        } catch (t: Throwable) {
            // ignorar: caemos al nombre del path
        }
        return name ?: uri.lastPathSegment ?: "IR"
    }

    /**
     * Decodifica un WAV completo y lo deja listo para el Convolver.
     * @return (fs, mono float[]) o null si el formato no es soportado.
     */
    fun parseAndPrepare(bytes: ByteArray, maxSeconds: Double = 2.0): Pair<Int, FloatArray>? {
        if (bytes.size < 44) return null
        if (bytes[0].toInt() != 'R'.code || bytes[1].toInt() != 'I'.code ||
            bytes[2].toInt() != 'F'.code || bytes[3].toInt() != 'F'.code
        ) return null
        if (bytes[8].toInt() != 'W'.code || bytes[9].toInt() != 'A'.code ||
            bytes[10].toInt() != 'V'.code || bytes[11].toInt() != 'E'.code
        ) return null
        var pos = 12
        var fmt: ByteArray? = null
        var data: ByteArray? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, StandardCharsets.US_ASCII)
            val size = readLeInt(bytes, pos + 4)
            if (size < 0 || pos + 8 + size > bytes.size) return null
            when (id) {
                "fmt " -> fmt = bytes.copyOfRange(pos + 8, pos + 8 + size)
                "data" -> data = bytes.copyOfRange(pos + 8, pos + 8 + size)
            }
            pos += 8 + size + (size and 1)
        }
        val f = fmt ?: return null
        val d = data ?: return null
        if (f.size < 16) return null
        var codec = readLeShort(f, 0)
        val channels = readLeShort(f, 2)
        val sampleRate = readLeInt(f, 4)
        val bits = readLeShort(f, 14)
        if (sampleRate <= 0 || channels <= 0 || d.isEmpty()) return null
        if (codec == 0xFFFE && f.size >= 18) {
            val sub = readLeShort(f, 16)
            if (sub >= 0 && sub < 256) codec = sub
        }
        val interleaved: FloatArray = when {
            codec == 1 && bits == 16 -> decodePcm16(d)
            codec == 1 && bits == 24 -> decodePcm24(d)
            codec == 1 && bits == 32 -> decodePcm32(d)
            codec == 3 && bits == 32 -> decodeFloat32(d)
            else -> return null
        }
        val mono = if (channels == 1) interleaved else downmix(interleaved, channels)
        return prepare(mono, sampleRate, maxSeconds)
    }

    /** Convierte y normaliza la IR: quita DC, recorta silencio/tail y normaliza pico. */
    private fun prepare(mono: FloatArray, fs: Int, maxSeconds: Double): Pair<Int, FloatArray> {
        // DC: quitar la media
        var mean = 0.0
        for (v in mono) mean += v.toDouble()
        mean /= mono.size.toDouble()
        var peak = 0.0
        for (i in mono.indices) {
            val v = (mono[i].toDouble() - mean)
            mono[i] = v.toFloat()
            if (abs(v) > peak) peak = abs(v)
        }
        if (peak <= 1e-6) return fs to FloatArray(0)
        // Recortar cola de silencio: desde el final hasta el último pico > 1e-4
        var end = mono.size - 1
        while (end > 0 && abs(mono[end].toDouble()) < 1e-4 * peak) end--
        var out = mono.copyOf(end + 1)
        // Longitud máxima
        val maxLen = (maxSeconds * fs).toInt().coerceAtLeast(1)
        if (out.size > maxLen) out = out.copyOf(maxLen)
        // Normalizar pico a 1.0
        peak = 0.0
        for (v in out) if (abs(v.toDouble()) > peak) peak = abs(v.toDouble())
        if (peak > 1e-9) {
            val s = 1.0 / peak
            for (i in out.indices) out[i] = (out[i] * s).toFloat()
        }
        return fs to out
    }

    private fun downmix(interleaved: FloatArray, channels: Int): FloatArray {
        val n = interleaved.size / channels
        val out = FloatArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (c in 0 until channels) s += interleaved[i * channels + c].toDouble()
            out[i] = (s / channels).toFloat()
        }
        return out
    }

    private fun decodePcm16(b: ByteArray): FloatArray {
        val n = b.size / 2
        val out = FloatArray(n)
        var p = 0
        for (i in 0 until n) {
            val v = (b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8)
            out[i] = v.toFloat() / 32768f
            p += 2
        }
        return out
    }

    private fun decodePcm24(b: ByteArray): FloatArray {
        val n = b.size / 3
        val out = FloatArray(n)
        var p = 0
        for (i in 0 until n) {
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() shl 16)
            out[i] = v.toFloat() / 8388608f
            p += 3
        }
        return out
    }

    private fun decodePcm32(b: ByteArray): FloatArray {
        val n = b.size / 4
        val out = FloatArray(n)
        var p = 0
        for (i in 0 until n) {
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or (b[p + 3].toInt() shl 24)
            out[i] = v.toFloat() / 2147483648f
            p += 4
        }
        return out
    }

    private fun decodeFloat32(b: ByteArray): FloatArray {
        val n = b.size / 4
        val out = FloatArray(n)
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        buf.get(out)
        return out
    }

    private fun readLeInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or (b[off + 3].toInt() shl 24)

    private fun readLeShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /** Empaqueta (fs + float[]) en base64 para guardarlo en SharedPreferences. */
    fun encode(fs: Int, floats: FloatArray): String {
        val buf = ByteBuffer.allocate(4 + floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(fs)
        for (f in floats) buf.putFloat(f)
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    /** Desempaqueta base64 → (fs, float[]). */
    fun decode(s: String?): Pair<Int, FloatArray>? {
        if (s.isNullOrBlank()) return null
        val raw = try {
            Base64.decode(s, Base64.NO_WRAP)
        } catch (t: Throwable) {
            return null
        }
        if (raw.size < 4) return null
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val fs = buf.int
        val n = (raw.size - 4) / 4
        val out = FloatArray(n)
        buf.asFloatBuffer().get(out)
        return fs to out
    }

    private fun abs(v: Double): Double = if (v < 0) -v else v
}
