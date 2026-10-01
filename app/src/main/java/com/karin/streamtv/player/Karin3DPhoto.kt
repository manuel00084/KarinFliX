package com.karin.streamtv.player

import android.graphics.Bitmap
import android.graphics.Canvas
import kotlin.math.roundToInt

/**
 * 3D para fotos (Galería Karin): CPU, sin GL.
 *
 * La entrada es siempre 2D, así que solo hay dos salidas honestas:
 * - ANAGLYPH_PSEUDO: pseudo-3D experimental por luma (igual que el
 *   shader de video: desplazamiento horizontal por brillo + mezcla
 *   anaglifo). depth=0 → imagen intacta.
 * - SBS_DUPLICATE: compatibilidad Cardboard (misma imagen en ambos
 *   ojos, disparidad 0, no es 3D).
 *
 * Pulfrich no aplica a fotos (necesita movimiento entre cuadros).
 * Para profundidad REAL ver [AIDepthDibr] (MiDaS-TFLite + DIBR).
 */
object Karin3DPhoto {

    const val PHOTO_OFF = 0
    const val PHOTO_ANAGLYPH = 1
    const val PHOTO_SBS = 2
    const val PHOTO_COUNT = 3

    fun modeName(mode: Int): String = when (mode) {
        PHOTO_ANAGLYPH -> "Anaglifo pseudo-3D (experimental)"
        PHOTO_SBS -> "SBS duplicado (sin profundidad)"
        else -> "2D"
    }

    /** Desplazamiento en px para una luma dada (espejo del shader). */
    fun shiftForLuma(luma: Float, depth01: Float, width: Int): Float {
        val uDepth = depth01.coerceIn(0f, 1f) * 0.03f
        return (luma - 0.5f) * uDepth * 2f * width
    }

    fun lumaOf(r: Int, g: Int, b: Int): Float =
        (0.299f * r + 0.587f * g + 0.114f * b) / 255f

    /** Mezcla anaglifo por luma (espejo del shader, testeable en JVM). */
    fun anaglyphMixPixel(lr: Int, lg: Int, lb: Int, rr: Int, rg: Int, rb: Int, type: Int): Int {
        val ll = 0.299f * lr + 0.587f * lg + 0.114f * lb
        val rl = 0.299f * rr + 0.587f * rg + 0.114f * rb
        return when (type) {
            Karin3DController.ANAG_RED_BLUE -> argb(255, ll.roundToInt(), 0, rl.roundToInt())
            Karin3DController.ANAG_RED_GREEN -> argb(255, ll.roundToInt(), rl.roundToInt(), 0)
            else -> argb(255, ll.roundToInt(), rl.roundToInt(), rl.roundToInt())
        }
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    /**
     * Aplica pseudo-anaglifo por luma. Escala la foto a [maxWidth] para
     * no atascar la UI (las drawables 1280x720 en bucle pixel a pixel
     * tardan ~100-300ms; a 640px <100ms).
     */
    fun applyPseudoAnaglyph(
        src: Bitmap,
        depth01: Float,
        anaglyphType: Int = Karin3DController.ANAG_RED_CYAN,
        maxWidth: Int = 640,
    ): Bitmap {
        val depth = depth01.coerceIn(0f, 1f)
        if (depth <= 0f) return src
        val scale = if (src.width > maxWidth) maxWidth.toFloat() / src.width else 1f
        val w = (src.width * scale).roundToInt().coerceAtLeast(2)
        val h = (src.height * scale).roundToInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        // Luma por pixel (para el shift) + canales (para la mezcla).
        val lumas = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            lumas[i] = lumaOf((c shr 16) and 255, (c shr 8) and 255, c and 255)
        }
        val maxShift = w * 0.03f * depth
        val out = IntArray(w * h)
        val amt = depth.coerceIn(0f, 1f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val shift = (lumas[i] - 0.5f) * 2f * maxShift
                val xl = (x - shift).roundToInt().coerceIn(0, w - 1)
                val xr = (x + shift).roundToInt().coerceIn(0, w - 1)
                val cl = px[y * w + xl]
                val cr = px[y * w + xr]
                val ana = anaglyphMixPixel(
                    (cl shr 16) and 255, (cl shr 8) and 255, cl and 255,
                    (cr shr 16) and 255, (cr shr 8) and 255, cr and 255,
                    anaglyphType,
                )
                val c = px[i]
                out[i] = if (amt >= 1f) ana else mixPixel(c, ana, amt)
            }
        }
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        res.setPixels(out, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        return res
    }

    private fun mixPixel(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int, e: Int): Int = (s + ((e - s) * t)).roundToInt().coerceIn(0, 255)
        return argb(
            255,
            ch((a shr 16) and 255, (b shr 16) and 255),
            ch((a shr 8) and 255, (b shr 8) and 255),
            ch(a and 255, b and 255),
        )
    }

    /** SBS duplicado: misma foto en ambas mitades (disparidad 0). */
    fun applySbsDuplicate(src: Bitmap, maxWidth: Int = 640): Bitmap {
        val scale = if (src.width > maxWidth) maxWidth.toFloat() / src.width else 1f
        val hw = (src.width * scale).roundToInt().coerceAtLeast(2)
        val h = (src.height * scale).roundToInt().coerceAtLeast(2)
        val half = Bitmap.createScaledBitmap(src, hw / 2, h, true)
        val res = Bitmap.createBitmap(hw, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(res)
        cv.drawBitmap(half, 0f, 0f, null)
        cv.drawBitmap(half, (hw / 2).toFloat(), 0f, null)
        if (half !== src) half.recycle()
        return res
    }
}
