package com.karin.streamtv.karinlink.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the KARIN Link v2 envelope.
 *
 * These replace a placeholder suite that only asserted assertTrue(true) on
 * string literals and covered nothing.
 */
class LinkProtocolTest {

    private fun helloPayload(deviceId: String = "dev-1", caps: Set<Capability> = setOf(Capability.PLAY)) =
        HelloPayload(peer = PeerInfo(
            deviceId = deviceId,
            deviceName = "Living Room TV",
            appVersion = "1.4.0",
            capabilities = caps
        ))

    // ── Envelope round-trip ───────────────────────────────────────

    @Test
    fun `envelope survives encode decode round trip`() {
        val original = Envelope(
            t = Envelope.Type.HELLO,
            id = "msg-1",
            from = "dev-1",
            to = "dev-2",
            ts = 1_700_000_000_000,
            d = helloPayload().toJson()
        )

        val decoded = Envelope.decode(original.encode())

        assertNotNull("decoded envelope must not be null", decoded)
        assertEquals(LinkProtocol.VERSION, decoded!!.v)
        assertEquals(Envelope.Type.HELLO, decoded.t)
        assertEquals("msg-1", decoded.id)
        assertEquals("dev-1", decoded.from)
        assertEquals("dev-2", decoded.to)
        assertEquals(1_700_000_000_000, decoded.ts)
    }

    @Test
    fun `payload survives round trip with unicode device name`() {
        val payload = helloPayload(deviceId = "tv-ñ-1").copy(
            peer = helloPayload().peer.copy(deviceName = "Televisor Sala Ñoño 🎬")
        )
        val env = Envelope(t = Envelope.Type.HELLO, from = "a", to = "b", d = payload.toJson())

        val peer = Envelope.decode(env.encode())?.d?.get("peer")

        assertNotNull(peer)
        assertEquals(
            "Televisor Sala Ñoño 🎬",
            LinkProtocol.json.decodeFromString(PeerInfo.serializer(), peer.toString()).deviceName
        )
    }

    @Test
    fun `default id is generated and unique`() {
        val a = Envelope(t = "ping")
        val b = Envelope(t = "ping")

        assertFalse("envelope ids must not collide", a.id == b.id)
        assertEquals(36, a.id.length)
    }

    // ── Signature coverage ─────────────────────────────────────────

    @Test
    fun `sigData changes when any covered field changes`() {
        val base = Envelope(
            t = "state", id = "m1", from = "a", to = "b", ts = 100,
            d = buildJsonObject { put("x", JsonPrimitive(1)) }
        )

        assertFalse(base.sigData() == base.copy(t = "state2").sigData())
        assertFalse(base.sigData() == base.copy(id = "m2").sigData())
        assertFalse(base.sigData() == base.copy(from = "z").sigData())
        assertFalse(base.sigData() == base.copy(to = "z").sigData())
        assertFalse(base.sigData() == base.copy(ts = 101).sigData())
        assertFalse(base.sigData() == base.copy(d = buildJsonObject { put("x", JsonPrimitive(2)) }).sigData())
    }

    @Test
    fun `sigData is stable across encode decode so signatures verify`() {
        val env = Envelope(
            t = "play.request", id = "m7", from = "phone", to = "tv", ts = 555,
            d = buildJsonObject { put("url", JsonPrimitive("http://x/y.m3u8")) }
        )

        assertEquals(
            "a signature must be verifiable after a wire round trip",
            env.sigData(),
            Envelope.decode(env.encode())!!.sigData()
        )
    }

    @Test
    fun `sigData does not cover the signature field itself`() {
        val unsigned = Envelope(t = "ping", id = "a", from = "x", to = "y", ts = 1)
        assertEquals(unsigned.sigData(), unsigned.copy(sig = "deadbeef").sigData())
    }

    // ── Capabilities ──────────────────────────────────────────────

    @Test
    fun `capability encode decode round trip`() {
        val caps = setOf(Capability.PLAY, Capability.PROXY, Capability.REMOTE)
        assertEquals(caps, Capability.decode(Capability.encode(caps)))
    }

    @Test
    fun `capability decode ignores unknown flags from newer peers`() {
        assertEquals(setOf(Capability.PLAY, Capability.PROXY), Capability.decode("play,teleport,proxy"))
    }

    @Test
    fun `capability decode handles empty and null safely`() {
        assertEquals(emptySet<Capability>(), Capability.decode(null))
        assertEquals(emptySet<Capability>(), Capability.decode(""))
        assertEquals(emptySet<Capability>(), Capability.decode(",,"))
    }

    // ── Robustness against malformed input ────────────────────────

    @Test
    fun `decode returns null on garbage instead of throwing`() {
        assertNull(Envelope.decode("not json at all"))
        assertNull(Envelope.decode(""))
        assertNull(Envelope.decode("{}"))
        assertNull(Envelope.decode("[]"))
        assertNull(Envelope.decode("null"))
    }

    @Test
    fun `decode tolerates unknown fields from a newer peer revision`() {
        val raw = """
            {"v":2,"t":"state","id":"m","from":"a","to":"b","ts":1,
             "d":{"known":1,"somethingFromTheFuture":true},"futureTopLevel":42}
        """.trimIndent()

        val decoded = Envelope.decode(raw)

        assertNotNull(decoded)
        assertEquals("state", decoded!!.t)
        assertEquals(JsonPrimitive(true), decoded.d["somethingFromTheFuture"])
    }

    @Test
    fun `service type is the one both sides agree on`() {
        assertEquals(
            "mDNS type must not drift between peers",
            "_karinflix._tcp.",
            LinkProtocol.SERVICE_TYPE
        )
    }

    @Test
    fun `broadcast address is the wildcard recipient`() {
        assertEquals("*", Envelope.Type.BROADCAST)
    }

    @Test
    fun `rejection reasons are stable identifiers not prose`() {
        listOf(
            RejectReason.PROTOCOL_MISMATCH,
            RejectReason.ALREADY_PAIRED,
            RejectReason.NOT_PAIRED,
            RejectReason.BAD_SIGNATURE,
            RejectReason.PIN_REQUIRED,
            RejectReason.PIN_MISMATCH,
            RejectReason.BLOCKED
        ).forEach { reason ->
            assertTrue("reason must be snake_case id: $reason", reason.matches(Regex("[a-z_]+")))
        }
    }
}
