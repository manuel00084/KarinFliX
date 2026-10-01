package com.karin.streamtv.karinlink.protocol

import com.karin.streamtv.karinlink.LinkServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * End-to-end checks over a real loopback socket.
 *
 * [LinkSessionTest] proves the rules in isolation and [WsFrameParserTest] proves
 * the codec, but neither shows the two agreeing: the handshake GUID, the client
 * masking its frames and the server refusing to do the same are all things that
 * only matter once bytes cross a socket. Three of the four defects found while
 * building this lived exactly in that seam.
 *
 * Runs on a loopback port, so no network is required.
 */
class LinkServerHandshakeTest {

    private val received = LinkedBlockingQueue<Envelope>()
    private val peerEvents = LinkedBlockingQueue<String>()
    private val pinned = CountDownLatch(1)

    private val serverRegistry = PeerRegistry(InMemoryTrustStore(), { "Server" })
    private val clientRegistry = PeerRegistry(InMemoryTrustStore(), { "Client" })

    private val serverInfo = PeerInfo(serverRegistry.deviceId, "Server", "1.0")
    private val clientInfo = PeerInfo(clientRegistry.deviceId, "Client", "1.0")

    private var port = 0

    @After
    fun tearDown() {
        LinkServer.stop()
    }

    /** Starts the server, or fails the test with the reason it did not bind. */
    private fun startServer(pendingPin: String? = null): Int {
        LinkServer.stop()
        assertTrue("configure() rejected the identity", LinkServer.configure(serverRegistry, serverInfo))
        serverRegistry.pendingPin = pendingPin

        LinkServer.addListener { received.put(it) }
        LinkServer.addPeerListener { p: PeerInfo? ->
            peerEvents.put(if (p == null) "left" else "paired:${p.deviceId}")
        }

        port = LinkServer.start(0)
        assertTrue("server did not bind", port > 0)
        return port
    }

    /** A raw WebSocket client, so the test controls every byte it sends. */
    private class RawClient(port: Int) {
        private val socket = Socket("127.0.0.1", port)
        private val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val reader = WsFrameParser(expectMasked = false)
        private val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        private val events = LinkedBlockingQueue<WsFrameParser.Event>()

        val accept: String
        val status: String

        init {
            socket.soTimeout = 10_000
            // A real, correctly formed handshake request.
            val key = "dGhlIHNhbXBsZSBub25jZQ=="
            socket.getOutputStream().write(
                (
                    "GET ${LinkProtocol.WS_PATH} HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:$port\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.US_ASCII)
            )
            socket.getOutputStream().flush()

            status = input.readLine()
            var acceptHeader: String? = null
            var line: String? = input.readLine()
            while (line != null && line.isNotEmpty()) {
                if (line!!.startsWith("Sec-WebSocket-Accept:", ignoreCase = true)) {
                    acceptHeader = line!!.substringAfter(':').trim()
                }
                line = input.readLine()
            }
            accept = acceptHeader.orEmpty()
        }

        fun send(text: String) {
            // A client must mask, so use the masking encoder.
            val frame = WsFrameParser().encode(WsOpcode.TEXT, text.toByteArray(), mask)
            socket.getOutputStream().write(frame)
            socket.getOutputStream().flush()
        }

        /** Sends without masking, which a server has to reject. */
        fun sendUnmasked(text: String) {
            socket.getOutputStream().write(
                WsFrameParser().encode(WsOpcode.TEXT, text.toByteArray(), mask = null)
            )
            socket.getOutputStream().flush()
        }

        fun nextEvent(seconds: Long = 5): WsFrameParser.Event? {
            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + seconds * 1000
            while (System.currentTimeMillis() < deadline) {
                val pending = events.poll()
                if (pending != null) return pending
                val read = try {
                    socket.getInputStream().read(buffer)
                } catch (e: Exception) {
                    null
                }
                if (read == null || read <= 0) return null
                events.addAll(reader.feed(buffer.copyOf(read)))
            }
            return events.poll()
        }

        fun nextText(seconds: Long = 5): String? {
            while (true) {
                when (val event = nextEvent(seconds)) {
                    is WsFrameParser.Event.Text -> return event.message
                    null -> return null
                    else -> Unit
                }
            }
        }

        fun isClosedByPeer(): Boolean = socket.isClosed || socket.isInputShutdown

        fun close() = runCatching { socket.close() }
    }

    @Test
    fun `a well formed handshake is accepted`() {
        val port = startServer(PIN)

        val client = RawClient(port)

        assertEquals("HTTP/1.1 101 Switching Protocols", client.status)
        // The exact value from RFC 6455 section 1.3. A wrong GUID here was one
        // of the original bugs and it looks like a connection timeout.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", client.accept)
        client.close()
    }

    @Test
    fun `a masked hello is answered with a signed ack`() {
        val port = startServer(PIN)
        val client = RawClient(port)

        client.send(
            Envelope.of(
                Envelope.Type.HELLO,
                clientInfo.deviceId,
                Envelope.Type.BROADCAST,
                HelloPayload(peer = clientInfo).toJson()
            ).encode()
        )

        val ackText = client.nextText()
        assertNotNull("no reply to hello", ackText)
        val ack = Envelope.decode(ackText!!)
        assertNotNull("the reply was not a valid envelope", ack)
        assertEquals(Envelope.Type.HELLO_ACK, ack!!.t)
        val key = Pairing.deriveKey(PIN, serverInfo.deviceId, clientInfo.deviceId)
        assertTrue("the ack was not signed with the derived key", Pairing.verified(ack, key))
        client.close()
    }

    @Test
    fun `a pairing registers the peer and delivers a later command`() {
        val port = startServer(PIN)
        val client = RawClient(port)
        val key = Pairing.deriveKey(PIN, serverInfo.deviceId, clientInfo.deviceId)

        client.send(
            Envelope.of(
                Envelope.Type.HELLO,
                clientInfo.deviceId,
                Envelope.Type.BROADCAST,
                HelloPayload(peer = clientInfo).toJson()
            ).encode()
        )
        client.nextText()
        assertEquals("paired:${clientInfo.deviceId}", peerEvents.poll(5, TimeUnit.SECONDS))

        client.send(
            Pairing.sign(
                Envelope.of("sync", clientInfo.deviceId, Envelope.Type.BROADCAST, buildJsonObject { put("k", "v") }),
                key
            ).encode()
        )

        val delivered = received.poll(5, TimeUnit.SECONDS)
        assertNotNull("the command never reached the listener", delivered)
        assertEquals("sync", delivered!!.t)
        assertEquals("v", delivered.d.str("k"))
        client.close()
    }

    @Test
    fun `an unmasked client frame is refused`() {
        val port = startServer(PIN)
        val client = RawClient(port)

        client.sendUnmasked(
            Envelope.of(
                Envelope.Type.HELLO,
                clientInfo.deviceId,
                Envelope.Type.BROADCAST,
                HelloPayload(peer = clientInfo).toJson()
            ).encode()
        )

        // The stream must end rather than carry on being parsed.
        assertTrue("an unmasked frame was tolerated", client.isClosedByPeer() || client.nextEvent(2) == null)
        client.close()
    }

    @Test
    fun `a command without a valid signature is refused`() {
        val port = startServer(PIN)
        val client = RawClient(port)
        val key = Pairing.deriveKey(PIN, serverInfo.deviceId, clientInfo.deviceId)

        client.send(
            Envelope.of(
                Envelope.Type.HELLO,
                clientInfo.deviceId,
                Envelope.Type.BROADCAST,
                HelloPayload(peer = clientInfo).toJson()
            ).encode()
        )
        client.nextText()

        // Correct peer, but signed with a key derived from another code.
        val wrong = Pairing.deriveKey("ZZZZZZ", serverInfo.deviceId, clientInfo.deviceId)
        client.send(
            Pairing.sign(
                Envelope.of("sync", clientInfo.deviceId, Envelope.Type.BROADCAST, buildJsonObject { put("k", "v") }),
                wrong
            ).encode()
        )

        assertTrue("a forged command was delivered", received.poll(2, TimeUnit.SECONDS) == null)
        assertNotNull("the peer was not told why", client.nextEvent(2))
        client.close()
        assertTrue(key.isNotEmpty())
    }

    @Test
    fun `an unknown peer with no code pending is refused`() {
        val port = startServer(pendingPin = null)
        val client = RawClient(port)

        client.send(
            Envelope.of(
                Envelope.Type.HELLO,
                clientInfo.deviceId,
                Envelope.Type.BROADCAST,
                HelloPayload(peer = clientInfo).toJson()
            ).encode()
        )

        val replyText = client.nextText()
        assertNotNull("the refusal never arrived", replyText)
        val reply = Envelope.decode(replyText!!)
        assertNotNull(reply)
        assertNotNull(reply)
        assertEquals(Envelope.Type.HELLO_REJECT, reply!!.t)
        assertEquals(RejectReason.NOT_PAIRED, reply.d.str("reason"))
        client.close()
    }

    @Test
    fun `a plain GET on the socket path is answered, not upgraded`() {
        val port = startServer(PIN)

        val plain = Socket("127.0.0.1", port)
        plain.soTimeout = 5_000
        plain.getOutputStream().write(
            "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.US_ASCII)
        )
        plain.getOutputStream().flush()
        val status = BufferedReader(InputStreamReader(plain.getInputStream())).readLine()

        assertEquals("HTTP/1.1 404 Not Found", status)
        plain.close()
    }

    @Test
    fun `the server refuses an identity that disagrees with the trust store`() {
        LinkServer.stop()
        val impostor = serverInfo.copy(deviceId = "some-other-device")

        // Otherwise every signature would be derived from the wrong id pair and
        // no handshake could ever succeed, so this has to be refused loudly.
        assertTrue(
            "configure() accepted a mismatched identity",
            !LinkServer.configure(serverRegistry, impostor)
        )
        assertTrue(LinkServer.configure(serverRegistry, serverInfo))
    }

    private companion object {
        const val PIN = "K7M2QX"
    }
}
