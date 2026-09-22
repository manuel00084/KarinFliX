package com.karin.streamtv.player

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Pasada RCAS dedicada (estilo madVR: afilar el resultado YA escalado).
 *
 * La compone SuperResolutionEffect cuando hay margen de pases: primero EASU
 * sube la resolución (sin RCAS dentro) y esta pasada aplica la nitidez
 * leyendo los vecinos del píxel real de salida, no una aproximación
 * bilineal del origen como el FSR single-pass.
 *
 * Normalmente 1:1 en salida (no cambia el tamaño). GLES2 compatible.
 */
class SuperResRcasEffect(
    private var sharpness: Float = 0.2f,
    private val demoSplit: Boolean = false,
    private val onConfigured: ((inW: Int, inH: Int, outW: Int, outH: Int) -> Unit)? = null,
) : GlEffect {

    private var program: SuperResRcasProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return SuperResRcasProgram(context, useHdr, sharpness, demoSplit, onConfigured).also {
            program = it
        }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = sharpness <= 0.01f

    /** Nitidez en vivo (0..1) para el slider. */
    fun updateSharpness(v: Float) {
        program?.updateSharpness(v)
    }
}

class SuperResRcasProgram(
    context: Context,
    useHdr: Boolean,
    private var sharpness: Float,
    demoSplit: Boolean,
    private val onConfigured: ((inW: Int, inH: Int, outW: Int, outH: Int) -> Unit)? = null,
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram

    init {
        try {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        glProgram.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        glProgram.setIntUniform("uDemoSplit", if (demoSplit) 1 else 0)
    }

    fun updateSharpness(v: Float) {
        sharpness = v.coerceIn(0f, 1f)
    }

    override fun release() {
        try {
            glProgram.delete()
        } catch (_: Exception) {
        }
        super.release()
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        Log.d("SuperResRcasEffect", "Pass2 configure in=${inputWidth}x${inputHeight} tx=" + (1f / inputWidth) + "x" + (1f / inputHeight))
        glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
        onConfigured?.invoke(inputWidth, inputHeight, inputWidth, inputHeight)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatUniform("uSharpness", (1f - sharpness) * 0.35f)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    companion object {
        private val VERTEX_SHADER = ShaderBlobs.rcasVertex

        private val FRAGMENT_SHADER = ShaderBlobs.rcasFragment
    }
}
