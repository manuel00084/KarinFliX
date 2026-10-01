package com.karin.streamtv.karinlink.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the WebSocket frame codec.
 *
 * The regression that matters: the old inline codec in LinkServer treated a
 * continuation frame as a complete message, so a payload split across frames was
 * delivered as several truncated JSON documents and dropped.
 */
class WsFrameParserTest {

    private val clientMask = byteArrayOf(0x37, 0xFA.toByte(), 0x21, 0x3D)

    private fun encode(opcode: Int, payload: ByteArray, mask: ByteArray? = clientMask, fin: Boolean = true): ByteArray =
        WsFrameParser().encode(opcode, payload, mask, fin)

    private fun textOf(events: List<WsFrameParser.Event>): List<String> =
        events.filterIsInstance<WsFrameParser.Event.Text>().map { it.message }

    // ── Round trip ────────────────────────────────────────────────

    @Test
    fun `unmasked text frame round trips`() {
        val parser = WsFrameParser()
        val events = parser.feed(encode(WsOpcode.TEXT, "hello".toByteArray(), mask = null))
        assertEquals(listOf("hello"), textOf(events))
    }

    @Test
    fun `masked text frame round trips`() {
        val parser = WsFrameParser()
        val events = parser.feed(encode(WsOpcode.TEXT, "hello".toByteArray()))
        assertEquals(listOf("hello"), textOf(events))
    }

    @Test
    fun `payloads across all three length encodings round trip`() {
        listOf(0, 1, 125, 126, 127, 1000, 65535, 65536, 70000).forEach { size ->
            val payload = ByteArray(size) { (it % 251).toByte() }
            val parser = WsFrameParser()
            val events = parser.feed(encode(WsOpcode.BINARY, payload))
            val got = events.filterIsInstance<WsFrameParser.Event.Binary>().single().payload
            assertArrayEquals("length $size", payload, got)
        }
    }

    @Test
    fun `unicode payloads survive the round trip`() {
        val parser = WsFrameParser()
        val text = "Televisor \u00D1o\u00F1o \uD83C\uDFAC"
        assertEquals(listOf(text), textOf(parser.feed(encode(WsOpcode.TEXT, text.toByteArray()))))
    }

    // ── Fragmentation: the bug this replaces ──────────────────────

    @Test
    fun `a fragmented text message is reassembled`() {
        val parser = WsFrameParser()
        val whole = "v".repeat(400) + "{\"t\":\"hello\"}"

        val first = encode(WsOpcode.TEXT, whole.substring(0, 200).toByteArray(), fin = false)
        // Opening frame carries fin=0, so nothing is delivered yet.
        val second = encode(WsOpcode.CONTINUATION, whole.substring(200).toByteArray())

        assertTrue("nothing is delivered until the final fragment", textOf(parser.feed(first)).isEmpty())
        assertEquals(listOf(whole), textOf(parser.feed(second)))
    }

    @Test
    fun `a message split across many fragments is reassembled`() {
        val parser = WsFrameParser()
        val whole = "x".repeat(1000)
        val chunk = 100

        var received = emptyList<String>()
        var offset = 0
        while (offset < whole.length) {
            val end = minOf(offset + chunk, whole.length)
            val opcode = if (offset == 0) WsOpcode.TEXT else WsOpcode.CONTINUATION
            val last = end == whole.length
            received = received + textOf(parser.feed(encode(opcode, whole.substring(offset, end).toByteArray(), fin = last)))
            offset = end
        }
        assertEquals(listOf(whole), received)
    }

    @Test
    fun `two fragmented messages in a row stay separate`() {
        val parser = WsFrameParser()
        val a = encode(WsOpcode.TEXT, "first-".toByteArray(), fin = false)
        val b = encode(WsOpcode.CONTINUATION, "half".toByteArray(), fin = false)
        val c = encode(WsOpcode.CONTINUATION, "done".toByteArray())
        val d = encode(WsOpcode.TEXT, "second".toByteArray())

        assertTrue(textOf(parser.feed(a)).isEmpty())
        assertTrue(textOf(parser.feed(b)).isEmpty())
        assertEquals(listOf("first-halfdone"), textOf(parser.feed(c)))
        assertEquals(listOf("second"), textOf(parser.feed(d)))
    }

    @Test
    fun `a continuation with no message to continue is a protocol error`() {
        val parser = WsFrameParser()
        val events = parser.feed(encode(WsOpcode.CONTINUATION, "orphan".toByteArray()))
        assertTrue(events.any { it is WsFrameParser.Event.ProtocolError })
    }

    @Test
    fun `a new data frame before the previous message finished is a protocol error`() {
        val parser = WsFrameParser()
        parser.feed(encode(WsOpcode.TEXT, "unfinished".toByteArray(), fin = false))
        val events = parser.feed(encode(WsOpcode.TEXT, "rude".toByteArray()))
        assertTrue(events.any { it is WsFrameParser.Event.ProtocolError })
    }

    @Test
    fun `an over long fragmented message is rejected`() {
        val parser = WsFrameParser(maxMessageBytes = 64)
        parser.feed(encode(WsOpcode.TEXT, ByteArray(40), fin = false))
        val events = parser.feed(encode(WsOpcode.CONTINUATION, ByteArray(40)))
        assertTrue(events.any { it is WsFrameParser.Event.ProtocolError })
    }

    // ── Partial reads ─────────────────────────────────────────────

    @Test
    fun `a frame split byte by byte is still delivered once`() {
        val parser = WsFrameParser()
        val frame = encode(WsOpcode.TEXT, "drip".toByteArray())
        val events = frame.flatMap { parser.feed(byteArrayOf(it)) }
        assertEquals(listOf("drip"), textOf(events))
    }

    @Test
    fun `several frames arriving in one read are all delivered`() {
        val parser = WsFrameParser()
        val batch = encode(WsOpcode.TEXT, "one".toByteArray()) +
            encode(WsOpcode.TEXT, "two".toByteArray()) +
            encode(WsOpcode.TEXT, "three".toByteArray())
        assertEquals(listOf("one", "two", "three"), textOf(parser.feed(batch)))
    }

    // ── Control frames ────────────────────────────────────────────

    @Test
    fun `ping and pong are surfaced with their payload`() {
        val parser = WsFrameParser()
        val events = parser.feed(encode(WsOpcode.PING, "hb".toByteArray())) +
            parser.feed(encode(WsOpcode.PONG, "hb".toByteArray()))
        assertEquals("hb", String(events.filterIsInstance<WsFrameParser.Event.Ping>().single().payload))
        assertEquals("hb", String(events.filterIsInstance<WsFrameParser.Event.Pong>().single().payload))
    }

    @Test
    fun `a ping in the middle of a fragmented message does not disturb it`() {
        val parser = WsFrameParser()
        parser.feed(encode(WsOpcode.TEXT, "frag".toByteArray(), fin = false))
        val ping = parser.feed(encode(WsOpcode.PING, byteArrayOf(1)))
        assertTrue(ping.any { it is WsFrameParser.Event.Ping })
        assertEquals(listOf("fragment"), textOf(parser.feed(encode(WsOpcode.CONTINUATION, "ment".toByteArray()))))
    }

    @Test
    fun `a close frame ends the stream`() {
        val parser = WsFrameParser()
        val events = parser.feed(encode(WsOpcode.CLOSE, ByteArray(0)))
        assertTrue(events.any { it is WsFrameParser.Event.Closed })
    }

    @Test
    fun `data after a close frame is ignored`() {
        val parser = WsFrameParser()
        parser.feed(encode(WsOpcode.CLOSE, ByteArray(0)))
        assertTrue(textOf(parser.feed(encode(WsOpcode.TEXT, "late".toByteArray()))).isEmpty())
    }

    // ── Hostile input ─────────────────────────────────────────────

    @Test
    fun `an oversized frame is rejected without allocating it`() {
        val parser = WsFrameParser(maxPayloadBytes = 1024)
        // Header claims 2 GB via the 64-bit length form.
        val header = byteArrayOf(0x81.toByte(), 0x7F, 0, 0, 0, 0, 0, 0, 0x08, 0x00)
        val events = parser.feed(header)
        assertTrue(events.any { it is WsFrameParser.Event.ProtocolError })
    }

    @Test
    fun `a reserved opcode is rejected`() {
        val parser = WsFrameParser()
        val events = parser.feed(byteArrayOf(0x83.toByte(), 0x00))
        assertTrue(events.any { it is WsFrameParser.Event.ProtocolError })
    }

    @Test
    fun `a server frame is never masked`() {
        val frame = WsFrameParser().encode(WsOpcode.TEXT, "abc".toByteArray(), mask = null)
        assertEquals("mask bit must be clear for server frames", 0, frame[1].toInt() and 0x80)
    }

    @Test
    fun `a client frame is always masked`() {
        val frame = WsFrameParser().encode(WsOpcode.TEXT, "abc".toByteArray(), clientMask)
        assertEquals("mask bit must be set for client frames", 0x80, frame[1].toInt() and 0x80)
    }

    // ── Masking rules (RFC 6455 section 5.1) ──────────────────────

    @Test
    fun `a server refuses an unmasked client frame`() {
        val parser = WsFrameParser(expectMasked = true)
        val unmasked = WsFrameParser().encode(WsOpcode.TEXT, "hi".toByteArray(), mask = null)

        val event = parser.feed(unmasked).single()

        assertTrue(event is WsFrameParser.Event.ProtocolError)
    }

    @Test
    fun `a client refuses a masked server frame`() {
        val parser = WsFrameParser(expectMasked = false)

        val event = parser.feed(
            WsFrameParser().encode(WsOpcode.TEXT, "hi".toByteArray(), clientMask)
        ).single()

        assertTrue(event is WsFrameParser.Event.ProtocolError)
    }

    @Test
    fun `each role accepts frames that follow the rule`() {
        val payload = "hi".toByteArray()

        val fromClient = WsFrameParser(expectMasked = true)
            .feed(WsFrameParser().encode(WsOpcode.TEXT, payload, clientMask))
        val fromServer = WsFrameParser(expectMasked = false)
            .feed(WsFrameParser().encode(WsOpcode.TEXT, payload, mask = null))

        assertEquals("hi", (fromClient.single() as WsFrameParser.Event.Text).message)
        assertEquals("hi", (fromServer.single() as WsFrameParser.Event.Text).message)
    }

    @Test
    fun `a broken masking rule ends the stream instead of resyncing`() {
        val parser = WsFrameParser(expectMasked = true)
        val good = WsFrameParser().encode(WsOpcode.TEXT, "one".toByteArray(), clientMask)
        val bad = WsFrameParser().encode(WsOpcode.TEXT, "two".toByteArray(), mask = null)

        parser.feed(good + bad)

        // A follow-up frame must not be read from a stream that already failed.
        assertTrue(parser.feed(good).isEmpty())
    }

    // ── Handshake ─────────────────────────────────────────────────

    @Test
    fun `accept value matches the RFC 6455 example`() {
        // The worked example from RFC 6455 section 1.3.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsFrameParser.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun `accept value is deterministic`() {
        val key = "x3JJHMbDL1EzLkh9GBhXDw=="
        assertEquals(WsFrameParser.acceptFor(key), WsFrameParser.acceptFor(key))
    }
}
