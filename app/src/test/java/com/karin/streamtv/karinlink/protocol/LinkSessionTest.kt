package com.karin.streamtv.karinlink.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security rules for [LinkSession].
 *
 * Each test here corresponds to a way the old socket-loop implementation could
 * be driven by a device that had no business issuing the command.
 */
class LinkSessionTest {

    // ── Fixture ───────────────────────────────────────────────────

    /**
     * Two devices that share a pairing code, plus both sessions.
     *
     * The clock is real time on purpose: [Envelope] stamps `ts` with
     * [System.currentTimeMillis], so a frozen fixture clock would place every
     * message far outside the skew window and be dropped as stale.
     */
    private class Fixture(private val now: () -> Long = { System.currentTimeMillis() }) {
        val serverRegistry = PeerRegistry(InMemoryTrustStore(), { "Server" }, now)
        val clientRegistry = PeerRegistry(InMemoryTrustStore(), { "Client" }, now)

        // Read the generated ids so both sides key off the real values.
        val serverId = serverRegistry.deviceId
        val clientId = clientRegistry.deviceId

        val serverInfo = PeerInfo(
            deviceId = serverId,
            deviceName = "Server",
            appVersion = "1.0",
            capabilities = setOf(Capability.PLAY)
        )
        val clientInfo = PeerInfo(
            deviceId = clientId,
            deviceName = "Client",
            appVersion = "1.0",
            capabilities = setOf(Capability.PROXY)
        )

        val server = LinkSession(serverInfo, serverRegistry, now)
        val client = LinkSession(clientInfo, clientRegistry, now)

        /** The key both sides independently derive from the code. */
        val key: ByteArray get() = Pairing.deriveKey(PIN, serverId, clientId)

        /** Runs the full handshake, with a pairing code pending on both ends. */
        fun pair() {
            serverRegistry.pendingPin = PIN
            clientRegistry.pendingPin = PIN
            client.beginHandshake()?.let { hello ->
                val ack = server.receive(hello).filterIsInstance<LinkSession.Outcome.Respond>().first()
                client.receive(ack.envelope)
            }
        }

        /** Pairs, then returns the two established sessions. */
        fun paired(): Fixture = apply { pair() }
    }

    private fun signed(
        f: Fixture,
        type: String,
        payload: JsonObject = buildJsonObject { put("episodeUrl", "https://x/1.mkv") },
        to: String = Envelope.Type.BROADCAST
    ): Envelope = Pairing.sign(Envelope.of(type, f.clientId, to, payload), f.key)

    // ── Handshake ─────────────────────────────────────────────────

    @Test
    fun `pairing on a shared code establishes a ready session on both ends`() {
        val f = Fixture().paired()

        assertTrue(f.server.isReady)
        assertTrue(f.client.isReady)
        assertEquals(f.clientId, f.server.remoteId)
        assertEquals(f.serverId, f.client.remoteId)
        assertTrue(f.serverRegistry.isTrusted(f.clientId))
        assertTrue(f.clientRegistry.isTrusted(f.serverId))
    }

    @Test
    fun `the handshake records what each side can do`() {
        val f = Fixture().paired()

        // The server learned the client's capabilities from hello, the client
        // the server's from the signed ack. The UI needs these to decide whether
        // a peer can be asked to play, proxy or transcode.
        assertEquals(setOf(Capability.PROXY), f.serverRegistry.capabilitiesOf(f.clientId))
        assertEquals(setOf(Capability.PLAY), f.clientRegistry.capabilitiesOf(f.serverId))
    }

    @Test
    fun `completing a pairing clears the pending code`() {
        val f = Fixture().paired()

        assertFalse(f.serverRegistry.isAwaitingPairing)
        assertFalse(f.clientRegistry.isAwaitingPairing)
    }

    @Test
    fun `a known peer reconnects with no code pending`() {
        val f = Fixture().paired()

        // Second connection, as happens on every discovery pass after the first
        // pairing. No code is shown, so it must still be accepted.
        val now = { System.currentTimeMillis() }
        val server = LinkSession(f.serverInfo, f.serverRegistry, now = now)
        val client = LinkSession(f.clientInfo, f.clientRegistry, now = now)
        assertFalse(f.serverRegistry.isAwaitingPairing)

        val hello = client.beginHandshake()!!
        val outcomes = server.receive(hello)
        val ack = outcomes.filterIsInstance<LinkSession.Outcome.Respond>().first().envelope

        val paired = outcomes.filterIsInstance<LinkSession.Outcome.Paired>().single()
        assertTrue(paired.alreadyKnown)
        assertTrue(client.receive(ack).any { it is LinkSession.Outcome.Paired })
        assertTrue(server.isReady)
    }

    @Test
    fun `an unknown peer with no code pending is refused`() {
        val f = Fixture()
        val hello = f.client.beginHandshake()!!

        val outcome = f.server.receive(hello).single() as LinkSession.Outcome.Reject

        assertEquals(RejectReason.NOT_PAIRED, outcome.reason)
        assertFalse(f.server.isReady)
    }

    @Test
    fun `a peer speaking another protocol version is refused`() {
        val f = Fixture()
        f.serverRegistry.pendingPin = PIN
        val old = f.clientInfo.copy(protocolVersion = 1)
        val hello = Envelope.of(
            Envelope.Type.HELLO,
            f.clientId,
            Envelope.Type.BROADCAST,
            HelloPayload(peer = old).toJson()
        )

        val outcome = f.server.receive(hello).single() as LinkSession.Outcome.Reject

        assertEquals(RejectReason.PROTOCOL_MISMATCH, outcome.reason)
    }

    @Test
    fun `a device cannot open a session with itself`() {
        val f = Fixture()
        f.serverRegistry.pendingPin = PIN
        val hello = Envelope.of(
            Envelope.Type.HELLO,
            f.serverId,
            Envelope.Type.BROADCAST,
            HelloPayload(peer = f.serverInfo).toJson()
        )

        val outcome = f.server.receive(hello).single() as LinkSession.Outcome.Reject

        assertEquals(RejectReason.BLOCKED, outcome.reason)
    }

    @Test
    fun `a second hello on a live session is a protocol error`() {
        val f = Fixture().paired()
        val hello = Envelope.of(
            Envelope.Type.HELLO,
            f.clientId,
            Envelope.Type.BROADCAST,
            HelloPayload(peer = f.clientInfo).toJson()
        )

        val outcome = f.server.receive(hello).single() as LinkSession.Outcome.Malformed

        assertTrue(outcome.detail.contains("unexpected hello"))
    }

    @Test
    fun `beginHandshake only works once`() {
        val f = Fixture()

        assertNotNull(f.client.beginHandshake())
        assertNull(f.client.beginHandshake())
    }

    // ── The check the old server never made ───────────────────────

    @Test
    fun `a command before the handshake is refused`() {
        val f = Fixture()
        val smuggled = Pairing.sign(
            Envelope.of("sync", f.clientId, Envelope.Type.BROADCAST, JsonObject(emptyMap())),
            f.key
        )

        val outcome = f.server.receive(smuggled).single() as LinkSession.Outcome.Malformed

        assertTrue(outcome.detail.contains("before hello"))
    }

    @Test
    fun `an unsigned command after the handshake is refused`() {
        val f = Fixture().paired()
        val unsigned = Envelope.of("sync", f.clientId, Envelope.Type.BROADCAST, JsonObject(emptyMap()))

        val outcome = f.client.receive(unsigned).single() as LinkSession.Outcome.Reject

        assertEquals(RejectReason.BAD_SIGNATURE, outcome.reason)
    }

    @Test
    fun `a command signed with the wrong key is refused`() {
        val f = Fixture().paired()
        val wrongKey = Pairing.deriveKey("ZZZZZZ", f.serverId, f.clientId)
        val forged = Pairing.sign(
            Envelope.of("sync", f.clientId, Envelope.Type.BROADCAST, JsonObject(emptyMap())),
            wrongKey
        )

        val outcome = f.client.receive(forged).single() as LinkSession.Outcome.Reject

        assertEquals(RejectReason.BAD_SIGNATURE, outcome.reason)
    }

    @Test
    fun `a payload edited after signing is refused`() {
        val f = Fixture().paired()
        val genuine = signed(f, "sync", buildJsonObject { put("episodeUrl", "https://x/1.mkv") })
        // Same id, same signature, different destination.
        val tampered = genuine.copy(d = buildJsonObject { put("episodeUrl", "https://evil/1.mkv") })

        val outcome = f.client.receive(tampered).single() as LinkSession.Outcome.Reject

        assertEquals(RejectReason.BAD_SIGNATURE, outcome.reason)
    }

    @Test
    fun `a genuinely signed command is accepted`() {
        val f = Fixture().paired()
        val env = signed(f, "sync")

        val outcome = f.client.receive(env).single() as LinkSession.Outcome.Deliver

        assertEquals("sync", outcome.envelope.t)
        assertEquals("https://x/1.mkv", outcome.envelope.d["episodeUrl"].toString().trim('"'))
    }

    @Test
    fun `a command for somebody else is dropped without ending the session`() {
        val f = Fixture().paired()
        val env = signed(f, "sync", to = "some-other-device")

        val outcome = f.client.receive(env).single() as LinkSession.Outcome.Drop

        assertEquals("addressed to some-other-device", outcome.detail)
        assertTrue(f.client.isReady)
    }

    @Test
    fun `a replayed command is dropped the second time`() {
        val f = Fixture().paired()
        val env = signed(f, "sync")

        assertTrue(f.client.receive(env).single() is LinkSession.Outcome.Deliver)
        val replay = f.client.receive(env).single() as LinkSession.Outcome.Drop

        assertTrue(replay.detail.startsWith("duplicate id"))
    }

    @Test
    fun `a message from further in the past than the skew window is dropped`() {
        var now = System.currentTimeMillis()
        val f = Fixture { now }
        f.pair()
        val stale = Pairing.sign(
            Envelope(
                t = "sync",
                id = "old-1",
                from = f.clientId,
                to = Envelope.Type.BROADCAST,
                ts = now - STALE_BY
            ),
            f.key
        )

        val outcome = f.client.receive(stale).single() as LinkSession.Outcome.Drop

        assertEquals("replayed or stale timestamp", outcome.detail)
    }

    @Test
    fun `an edited payload on the raw text form is refused`() {
        // Guards the whole path a transport actually takes: frame text, not a
        // pre-parsed object, so a field rename cannot slip past unnoticed.
        val f = Fixture().paired()
        val genuine = signed(f, "sync", buildJsonObject { put("episodeUrl", "https://x/1.mkv") })
        val raw = genuine.encode().replace("https://x/1.mkv", "https://evil/1.mkv")

        val outcome = f.client.receive(raw).single() as LinkSession.Outcome.Reject

        assertEquals(RejectReason.BAD_SIGNATURE, outcome.reason)
    }

    // ── Ack verification ──────────────────────────────────────────

    @Test
    fun `an ack signed with the wrong key closes the session`() {
        val f = Fixture()
        f.serverRegistry.pendingPin = PIN
        f.clientRegistry.pendingPin = PIN
        f.client.beginHandshake()

        val impostor = Pairing.deriveKey("QQQQQQ", f.serverId, f.clientId)
        val ack = Pairing.sign(
            Envelope.of(
                Envelope.Type.HELLO_ACK,
                f.serverId,
                f.clientId,
                HelloAckPayload(peer = f.serverInfo).toJson()
            ),
            impostor
        )

        val outcome = f.client.receive(ack).single() as LinkSession.Outcome.Reject

        assertEquals(RejectReason.BAD_SIGNATURE, outcome.reason)
        assertFalse(f.client.isReady)
    }

    @Test
    fun `an unsigned ack closes the session`() {
        val f = Fixture()
        f.clientRegistry.pendingPin = PIN
        f.client.beginHandshake()
        val ack = Envelope.of(
            Envelope.Type.HELLO_ACK,
            f.serverId,
            f.clientId,
            HelloAckPayload(peer = f.serverInfo).toJson()
        )

        val outcome = f.client.receive(ack).single() as LinkSession.Outcome.Reject

        assertEquals(RejectReason.BAD_SIGNATURE, outcome.reason)
    }

    @Test
    fun `a refusal from the responder is reported with its reason`() {
        val f = Fixture()
        f.client.beginHandshake()
        val reject = Envelope.of(
            Envelope.Type.HELLO_REJECT,
            f.serverId,
            f.clientId,
            buildJsonObject { put("reason", RejectReason.NOT_PAIRED) }
        )

        val outcome = f.client.receive(reject).single() as LinkSession.Outcome.Reject

        assertEquals(RejectReason.NOT_PAIRED, outcome.reason)
        assertEquals(RejectReason.NOT_PAIRED, f.client.closedReason)
    }

    // ── Keepalive ─────────────────────────────────────────────────

    @Test
    fun `a ping is answered with a signed pong echoing its id`() {
        val f = Fixture().paired()
        val ping = Pairing.sign(
            Envelope(t = Envelope.Type.PING, id = "ping-7", from = f.clientId, to = Envelope.Type.BROADCAST),
            f.key
        )

        val pong = f.client.receive(ping).filterIsInstance<LinkSession.Outcome.Respond>().single()

        assertEquals(Envelope.Type.PONG, pong.envelope.t)
        assertTrue(Pairing.verified(pong.envelope, f.key))
        assertEquals("\"ping-7\"", pong.envelope.d["echo"].toString())
    }

    @Test
    fun `a replayed ping is not answered twice`() {
        val f = Fixture().paired()
        val ping = Pairing.sign(
            Envelope(t = Envelope.Type.PING, id = "ping-1", from = f.clientId, to = Envelope.Type.BROADCAST),
            f.key
        )

        val first = f.client.receive(ping).filterIsInstance<LinkSession.Outcome.Respond>()
        val second = f.client.receive(ping).single() as LinkSession.Outcome.Drop

        assertEquals(1, first.size)
        assertTrue(second.detail.startsWith("duplicate id"))
    }

    // ── Direction of each outcome ─────────────────────────────────

    @Test
    fun `an inbound command is delivered and never offered as a reply`() {
        val f = Fixture().paired()

        val outcomes = f.client.receive(signed(f, "sync"))

        // One entry, and it is a command to act on rather than something to
        // send back. A transport that treated these the same would rebroadcast
        // the command it had just been told to obey.
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is LinkSession.Outcome.Deliver)
    }

    @Test
    fun `the ack is offered as a reply and never as a command`() {
        val f = Fixture()
        f.serverRegistry.pendingPin = PIN
        f.client.beginHandshake()

        val outcomes = f.server.receive(
            Envelope.of(
                Envelope.Type.HELLO,
                f.clientId,
                Envelope.Type.BROADCAST,
                HelloPayload(peer = f.clientInfo).toJson()
            )
        )

        assertEquals(1, outcomes.filterIsInstance<LinkSession.Outcome.Respond>().size)
        assertTrue(outcomes.none { it is LinkSession.Outcome.Deliver })
    }

    // ── Outbound ──────────────────────────────────────────────────

    @Test
    fun `send returns null until the session is ready`() {
        val f = Fixture()
        val payload = buildJsonObject { put("x", 1) }

        assertNull(f.client.send("sync", payload))
        f.pair()
        assertNotNull(f.client.send("sync", payload))
    }

    @Test
    fun `outbound messages are signed and addressed to the peer`() {
        val f = Fixture().paired()
        val env = f.client.send("sync", buildJsonObject { put("episodeUrl", "u") })!!

        assertEquals(f.serverId, env.to)
        assertTrue(Pairing.verified(env, f.key))
    }

    @Test
    fun `messages after close are ignored`() {
        val f = Fixture().paired()
        f.client.close("user left")

        val outcome = f.client.receive(signed(f, "sync")).single() as LinkSession.Outcome.Drop

        assertEquals("session closed", outcome.detail)
    }

    private companion object {
        /** Comfortably past the five minute skew window. */
        const val STALE_BY = 60L * 60 * 1000
        const val PIN = "K7M2QX"
    }
}
