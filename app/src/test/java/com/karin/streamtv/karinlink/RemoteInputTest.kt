package com.karin.streamtv.karinlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Escritura en vivo y límite de frames del control remoto.
 *
 * Pure JVM: no Android framework classes are touched, so no Robolectric.
 */
class RemoteInputTest {

    // ── TextEdit: qué mandar cuando cambia el campo ──────────────

    @Test
    fun `typing a character sends only that character`() {
        val edits = TextEdit.diff(start = 0, before = 0, count = 1, text = "a")
        assertEquals(listOf<TextEdit.Edit>(TextEdit.Edit.Typed("a")), edits)
    }

    @Test
    fun `typing appends send only the new tail`() {
        // "h" ya estaba; el usuario teclea "e".
        val edits = TextEdit.diff(start = 1, before = 0, count = 1, text = "he")
        assertEquals(listOf<TextEdit.Edit>(TextEdit.Edit.Typed("e")), edits)
    }

    @Test
    fun `backspace sends a delete instead of resending the field`() {
        val edits = TextEdit.diff(start = 2, before = 1, count = 0, text = "h")
        assertEquals(listOf<TextEdit.Edit>(TextEdit.Edit.Deleted(1)), edits)
    }

    @Test
    fun `paste sends the whole chunk as one frame`() {
        val chunk = "una frase pegada entera aqui"
        val edits = TextEdit.diff(start = 0, before = 0, count = chunk.length, text = chunk)
        assertEquals(listOf<TextEdit.Edit>(TextEdit.Edit.Typed(chunk)), edits)
    }

    @Test
    fun `replacing a selection deletes first then types`() {
        // Selección de 5 caracteres en el centro, sustituida por "NUEVO".
        val edits = TextEdit.diff(start = 2, before = 5, count = 5, text = "abNUEVOfgh")
        assertEquals(
            listOf<TextEdit.Edit>(TextEdit.Edit.Deleted(5), TextEdit.Edit.Typed("NUEVO")),
            edits,
        )
    }

    @Test
    fun `delete burst is capped so a long wipe cannot flood the socket`() {
        val edits = TextEdit.diff(start = 0, before = 5_000, count = 0, text = "")
        assertEquals(listOf<TextEdit.Edit>(TextEdit.Edit.Deleted(TextEdit.MAX_DELETE)), edits)
    }

    @Test
    fun `no change produces no frames`() {
        assertTrue(TextEdit.diff(0, 0, 0, "abc").isEmpty())
        assertTrue(TextEdit.diff(0, 0, 0, null).isEmpty())
    }

    @Test
    fun `out of range indices are ignored instead of sending garbage`() {
        // count se sale del texto: no se puede recortar, así que no se teclea.
        assertTrue(TextEdit.diff(start = 9, before = 0, count = 3, text = "ab").isEmpty())
        assertTrue(TextEdit.diff(start = -1, before = 0, count = 2, text = "ab").isEmpty())
    }

    // ── FrameThrottle: presupuesto compartido de frames ──────────

    @Test
    fun `the first frame always goes through`() {
        val throttle = FrameThrottle(40)
        assertTrue(throttle.allow(now = 1_000L))
    }

    @Test
    fun `frames inside the interval are dropped`() {
        val throttle = FrameThrottle(40)
        assertTrue(throttle.allow(now = 1_000L))
        assertFalse("se solapan", throttle.allow(now = 1_001L))
        assertFalse("sigue dentro", throttle.allow(now = 1_039L))
    }

    @Test
    fun `the next frame is allowed once the interval has elapsed`() {
        val throttle = FrameThrottle(40)
        assertTrue(throttle.allow(now = 1_000L))
        assertFalse(throttle.allow(now = 1_020L))
        assertTrue(throttle.allow(now = 1_040L))
    }

    @Test
    fun `cursor and scroll share one budget instead of doubling the rate`() {
        val throttle = FrameThrottle(40)
        assertTrue("cursor", throttle.allow(now = 1_000L))
        assertFalse("scroll en el mismo instante", throttle.allow(now = 1_000L))
        assertTrue("scroll al terminar el intervalo", throttle.allow(now = 1_040L))
    }

    @Test
    fun `reset restarts the budget`() {
        val throttle = FrameThrottle(40)
        assertTrue(throttle.allow(now = 1_000L))
        assertFalse(throttle.allow(now = 1_010L))
        throttle.reset()
        assertTrue(throttle.allow(now = 1_011L))
    }

    @Test
    fun `an interval of zero never blocks`() {
        val throttle = FrameThrottle(0)
        repeat(10) { assertTrue(throttle.allow(now = 5L)) }
    }
}
