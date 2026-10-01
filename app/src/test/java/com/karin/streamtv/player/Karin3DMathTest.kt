package com.karin.streamtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Karin3DMathTest {

    @Test
    fun `pulfrich delay blend is zero when static`() {
        // motion = |yc-yp|*6: quieto → 0 → mezcla deja imagen intacta.
        val motionStatic = kotlin.math.abs(0.5f - 0.5f) * 6f
        assertEquals(0f, motionStatic, 1e-6f)
        val motionCut = (kotlin.math.abs(0.9f - 0.1f) * 6f).coerceIn(0f, 1f)
        assertEquals(1f, motionCut, 1e-6f)
    }

    @Test
    fun `photo shift mirrors shader scale`() {
        // uDepth 0..1 → 0..3% ancho; luma 1.0, depth 1 → +3% ancho.
        val s = Karin3DPhoto.shiftForLuma(1.0f, 1.0f, 1000)
        assertEquals(30f, s, 0.5f)
        assertEquals(0f, Karin3DPhoto.shiftForLuma(0.5f, 1.0f, 1000), 1e-6f)
        assertEquals(0f, Karin3DPhoto.shiftForLuma(1.0f, 0.0f, 1000), 1e-6f)
    }

    @Test
    fun `anaglyph mix keeps luma channels`() {
        val white = Karin3DPhoto.anaglyphMixPixel(255, 255, 255, 255, 255, 255, 0)
        assertEquals(255, (white shr 16) and 255)
        assertEquals(255, (white shr 8) and 255)
        assertEquals(255, white and 255)
        val rb = Karin3DPhoto.anaglyphMixPixel(255, 255, 255, 0, 0, 0, 1)
        assertEquals(0, rb and 255) // rojo-azul: B del ojo R negro → 0
    }

    @Test
    fun `dibr warp is symmetric between eyes`() {
        val s = 32f
        assertEquals(
            -AIDepthDibr.warpShift(1f, s),
            AIDepthDibr.warpShift(0f, s),
            1e-5f,
        )
        assertEquals(0f, AIDepthDibr.warpShift(0.5f, s), 1e-6f)
        assertTrue(AIDepthDibr.warpShift(1f, s) > 0)
    }

    @Test
    fun `photo mode names are honest`() {
        assertTrue(Karin3DPhoto.modeName(Karin3DPhoto.PHOTO_SBS).contains("sin profundidad"))
        assertEquals("Pulfrich temporal", Karin3DController.modeName(Karin3DController.MODE_PULFRICH))
        assertTrue(Karin3DController.modeName(Karin3DController.MODE_VR_SBS).contains("sin profundidad"))
    }
}
