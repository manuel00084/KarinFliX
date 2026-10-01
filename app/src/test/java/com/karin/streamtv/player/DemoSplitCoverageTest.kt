package com.karin.streamtv.player

import com.karin.streamtv.enhancer.gpu.KarinLightBoostEffect
import com.karin.streamtv.enhancer.parameters.KarinLightBoostParameters
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red de seguridad del modo demo split-screen.
 *
 * El demo solo funciona si CADA efecto de la cadena conserva la mitad
 * izquierda intacta. El bug que motivó este test: el Upscaler (y Visión)
 * no tenían soporte demo, así que en videos SD/720p la izquierda dejaba
 * de ser el original sin que nada avisara. Si alguien añade un efecto a
 * la cadena sin `demoSplit`, el test de contrato no compila; si añade un
 * fragmento al upscaler sin los marcadores, falla el test de inyección.
 *
 * Solo construcción Kotlin + strings (sin GL): corre en JVM.
 */
class DemoSplitCoverageTest {

    /** Los 6 fragmentos reales del upscaler aceptan la inyección demo. */
    @Test
    fun upscalerFragmentsAcceptDemoInjection() {
        val fragments = listOf(
            ShaderBlobs.superresFsr,
            ShaderBlobs.superresFsrEasu,
            ShaderBlobs.superresAnime4k,
            ShaderBlobs.superresKarinEco,
            ShaderBlobs.superresKarin,
            ShaderBlobs.superresKarinEasu,
        )
        fragments.forEachIndexed { i, f ->
            val out = SuperResProgram.withDemoSplit(f)
            assertNotNull("fragmento upscaler #$i sin punto de demo", out)
            val injected = out!!
            assertTrue(
                "fragmento #$i: falta la declaración del uniform",
                injected.contains("uniform int uDemoSplit;"),
            )
            // El early-out va ANTES de cualquier tap: el primer gl_FragColor
            // del main tiene que ser el del demo (izquierda = bilineal).
            val afterMain = injected.substring(injected.indexOf("void main() {"))
            val firstOut = afterMain.indexOf("gl_FragColor")
            assertTrue("fragmento #$i: main sin salida", firstOut >= 0)
            assertTrue(
                "fragmento #$i: el primer gl_FragColor no es el del demo",
                afterMain.substring(0, firstOut).contains("uDemoSplit == 1"),
            )
            assertTrue(
                "fragmento #$i: la izquierda demo no es bilineal directo",
                afterMain.contains("texture2D(uTexSampler, vTexCoord).rgb, 1.0); return;"),
            )
        }
    }

    /** Sin marcadores no hay inyección (el efecto sigue sin demo, sin crash). */
    @Test
    fun injectionRejectsFragmentWithoutMarkers() {
        assertNull(
            SuperResProgram.withDemoSplit("void main() { gl_FragColor = vec4(1.0); }"),
        )
        assertNull(SuperResProgram.withDemoSplit("precisión rota sin main"))
    }

    /**
     * Contrato: cada efecto de la cadena acepta `demoSplit`.
     * Compila = cumple. Si alguien quita el parámetro de un efecto,
     * esto deja de compilar y CI lo frena antes de llegar al TV.
     * (Solo construye los objetos; no toca GL.)
     */
    @Test
    fun chainEffectsExposeDemoSplit() {
        RestoreBoostEffect(0.5f, 0.5f, 0.5f, 0.3f, false, true)
        KarinLightBoostEffect(KarinLightBoostParameters(), true)
        SuperResolutionEffect(demoSplit = true)
        SuperResRcasEffect(demoSplit = true)
        MotionX2BoostEffect(demoSplit = true)
        CineBoostEffect(0.5f, true)
        BwBoostEffect(0.5f, true)
        AnimeLineBoostEffect(0.5f, true)
        AdaptiveSharpenEffect(0.5f, true)
        FilmGrainEffect(0.5f, true)
        CrtBoostEffect(0.5f, true)
        VisionAssistEffect(VisionAssistSettings(), true)
        Karin3DEffect(demoSplit = true)
    }
}
