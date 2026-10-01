package com.karin.streamtv.player

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Prototipo IA 2D→3D REAL (depth + DIBR, offline).
 *
 * Estado: ANDAMIO FUNCIONAL sin modelo incluido. La matemática de
 * warping (DIBR simplificado) y el pre/post-procesado están listos y
 * testeados; la inferencia usa TFLite por REFLEXIÓN para no romper la
 * compilación si la dependencia no está instalada:
 *
 * 1. Descarga `midas_small_256_fp16.tflite` (MiDaS v2.1 small,
 *    ~33MB, MIT) y ponlo en `app/src/main/assets/`:
 *    https://huggingface.co/mlboydaisuke/midas-small-litert
 * 2. Añade a `app/build.gradle`:
 *    `implementation 'org.tensorflow:tensorflow-lite:2.14.0'`
 *    `implementation 'org.tensorflow:tensorflow-lite-gpu:2.14.0'`
 * 3. [estimateDepth] lo usará por GPU delegate si puede; si no,
 *    devuelve null y la UI debe seguir con el pseudo-3D por luma.
 *
 * Costo medido en la literatura: 1-3ms en Pixel 8a (GPU), ~22 FPS
 * en i7-1185G @640x480. En cajas TV modestas úsalo OFFLINE (una vez
 * por foto, o cada N frames) nunca por frame de video.
 */
object AIDepthDibr {

    const val MODEL_ASSET = "midas_small_256_fp16.tflite"
    const val MODEL_INPUT = 256

    /** Desplazamiento DIBR en px para una profundidad 0..1 (testeable). */
    fun warpShift(depth01: Float, strengthPx: Float): Float =
        (depth01.coerceIn(0f, 1f) - 0.5f) * 2f * strengthPx

    fun hasModel(context: Context): Boolean = try {
        context.assets.open(MODEL_ASSET).close()
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Estima profundidad relativa 0..1 (cerca=1) a [MODEL_INPUT]x[MODEL_INPUT].
     * Null si no hay modelo o no hay TFLite en runtime.
     */
    fun estimateDepth(context: Context, src: Bitmap): FloatArray? {
        if (!hasModel(context)) return null
        return try {
            runMidasTflite(context, src)
        } catch (_: Exception) {
            null
        }
    }

    // MiDaS small: entrada RGB 256x256 float32 NHWC normalizada ImageNet,
    // salida 1x256x256 inverse-depth relativo. Todo por reflexión para
    // compilación sin dependencia.
    private fun runMidasTflite(context: Context, src: Bitmap): FloatArray? {
        val scaled = Bitmap.createScaledBitmap(src, MODEL_INPUT, MODEL_INPUT, true)
        val input = Array(1) { Array(MODEL_INPUT) { Array(MODEL_INPUT) { FloatArray(3) } } }
        val px = IntArray(MODEL_INPUT * MODEL_INPUT)
        scaled.getPixels(px, 0, MODEL_INPUT, 0, 0, MODEL_INPUT, MODEL_INPUT)
        for (i in px.indices) {
            val c = px[i]
            val y = i / MODEL_INPUT
            val x = i % MODEL_INPUT
            input[0][y][x][0] = (((c shr 16) and 255) / 255f - 0.485f) / 0.229f
            input[0][y][x][1] = (((c shr 8) and 255) / 255f - 0.456f) / 0.224f
            input[0][y][x][2] = ((c and 255) / 255f - 0.406f) / 0.225f
        }
        val output = Array(1) { Array(MODEL_INPUT) { FloatArray(MODEL_INPUT) } }
        // Interpreter(modelBytes, options GPU) por reflexión.
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val byteBuffer = java.nio.ByteBuffer.allocateDirect(modelBytes.size)
            .order(java.nio.ByteOrder.nativeOrder())
        byteBuffer.put(modelBytes)
        byteBuffer.rewind()
        val interpCls = Class.forName("org.tensorflow.lite.Interpreter")
        // Options con GPU delegate si existe; si no, defaults.
        var options: Any? = null
        try {
            val optCls = Class.forName("org.tensorflow.lite.Interpreter\$Options")
            options = optCls.getDeclaredConstructor().newInstance()
            try {
                val delegateCls = Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
                val delegate = delegateCls.getDeclaredConstructor().newInstance()
                optCls.getMethod("addDelegate", Class.forName("org.tensorflow.lite.Delegate"))
                    .invoke(options, delegate)
            } catch (_: Exception) { }
        } catch (_: Exception) { }
        val ctor = if (options != null) {
            interpCls.getDeclaredConstructor(
                java.nio.ByteBuffer::class.java,
                Class.forName("org.tensorflow.lite.Interpreter\$Options"),
            )
        } else {
            interpCls.getDeclaredConstructor(java.nio.ByteBuffer::class.java)
        }
        ctor.isAccessible = true
        val interpreter = if (options != null) ctor.newInstance(byteBuffer, options)
        else ctor.newInstance(byteBuffer)
        try {
            interpCls.getMethod("run", Any::class.java, Any::class.java)
                .invoke(interpreter, input, output)
        } finally {
            try {
                interpCls.getMethod("close").invoke(interpreter)
            } catch (_: Exception) { }
        }
        if (scaled !== src) {
            try {
                if (!scaled.isRecycled) scaled.recycle()
            } catch (_: Exception) { }
        }
        // Min-max → 0..1 (cerca=1: MiDaS da inverse-depth, mayor=cerca).
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (y in 0 until MODEL_INPUT) for (x in 0 until MODEL_INPUT) {
            val v = output[0][y][x]
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val range = (mx - mn).takeIf { it > 1e-6f } ?: return null
        val depth = FloatArray(MODEL_INPUT * MODEL_INPUT)
        for (y in 0 until MODEL_INPUT) for (x in 0 until MODEL_INPUT) {
            depth[y * MODEL_INPUT + x] = ((output[0][y][x] - mn) / range).coerceIn(0f, 1f)
        }
        return depth
    }

    /**
     * DIBR simplificado a SBS: ojo L/R por warping del depth-map
     * (nearest + clamp; huecos se rellenan con el vecino válido).
     * [depthMap] de [estimateDepth] (256x256, cerca=1).
     */
    fun sbsFromDepth(src: Bitmap, depthMap: FloatArray, strengthPct: Float = 0.03f): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val strengthPx = w * strengthPct.coerceIn(0f, 0.06f)
        val hw = (w / 2).coerceAtLeast(2)
        val half = Bitmap.createScaledBitmap(src, hw, h, true)
        voidHalf(half)
        val left = warpHalf(px, w, h, depthMap, strengthPx, hw, h, -1f)
        val right = warpHalf(px, w, h, depthMap, strengthPx, hw, h, 1f)
        val res = Bitmap.createBitmap(hw * 2, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(res)
        cv.drawBitmap(left, 0f, 0f, null)
        cv.drawBitmap(right, hw.toFloat(), 0f, null)
        try {
            if (!left.isRecycled) left.recycle()
        } catch (_: Exception) { }
        try {
            if (!right.isRecycled) right.recycle()
        } catch (_: Exception) { }
        return res
    }

    private fun voidHalf(b: Bitmap) {
        try {
            if (!b.isRecycled) b.recycle()
        } catch (_: Exception) { }
    }

    private fun warpHalf(
        srcPx: IntArray, sw: Int, sh: Int,
        depthMap: FloatArray, strengthPx: Float,
        dw: Int, dh: Int, eyeSign: Float,
    ): Bitmap {
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val sy = (y.toFloat() * sh / dh).toInt().coerceIn(0, sh - 1)
            val dy = (y.toFloat() * MODEL_INPUT / dh).toInt().coerceIn(0, MODEL_INPUT - 1)
            for (x in 0 until dw) {
                val sxBase = (x.toFloat() * sw / dw).toInt().coerceIn(0, sw - 1)
                val dx = (x.toFloat() * MODEL_INPUT / dw).toInt().coerceIn(0, MODEL_INPUT - 1)
                val d = depthMap[(dy * MODEL_INPUT + dx).coerceIn(depthMap.indices)]
                val shift = warpShift(d, strengthPx) * eyeSign
                val sx = (sxBase + shift).roundToInt().coerceIn(0, sw - 1)
                out[y * dw + x] = srcPx[sy * sw + sx]
            }
        }
        return Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888).also {
            it.setPixels(out, 0, dw, 0, 0, dw, dh)
        }
    }

    /** Anaglifo desde depth-map (para lentes bicolor). */
    fun anaglyphFromDepth(
        src: Bitmap,
        depthMap: FloatArray,
        strengthPct: Float = 0.03f,
        anaglyphType: Int = Karin3DController.ANAG_RED_CYAN,
    ): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val strengthPx = w * strengthPct.coerceIn(0f, 0.06f)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val dy = (y.toFloat() * MODEL_INPUT / h).toInt().coerceIn(0, MODEL_INPUT - 1)
            for (x in 0 until w) {
                val dx = (x.toFloat() * MODEL_INPUT / w).toInt().coerceIn(0, MODEL_INPUT - 1)
                val d = depthMap[(dy * MODEL_INPUT + dx).coerceIn(depthMap.indices)]
                val shift = warpShift(d, strengthPx).roundToInt()
                val cl = px[y * w + (x - shift).coerceIn(0, w - 1)]
                val cr = px[y * w + (x + shift).coerceIn(0, w - 1)]
                out[y * w + x] = Karin3DPhoto.anaglyphMixPixel(
                    (cl shr 16) and 255, (cl shr 8) and 255, cl and 255,
                    (cr shr 16) and 255, (cr shr 8) and 255, cr and 255,
                    anaglyphType,
                )
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(out, 0, w, 0, 0, w, h)
        }
    }
}
