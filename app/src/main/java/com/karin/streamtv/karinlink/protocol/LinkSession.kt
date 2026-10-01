package com.karin.streamtv.karinlink.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The authenticated half of a KARIN Link connection, with no socket in sight.
 *
 * [LinkServer] and [LinkClient] are both thin transports: they frame bytes and
 * hand whole messages here. Everything that decides whether a message is
 * allowed through lives in this class, so the security rules are unit testable
 * on a plain JVM. That split matters because the previous implementation kept
 * the checks inline in the socket loop, where nothing could assert them.
 *
 * The rules, in order:
 *  1. The first message must be `hello`. Nothing else opens a session, so a
 *     peer cannot skip the handshake and start issuing commands.
 *  2. `hello` is the only unsigned message. Everything after it must carry an
 *     HMAC over [Envelope.sigData] that verifies against the shared key, which
 *     is what stops an unpaired device on the LAN from driving this one.
 *  3. The sender must be a device we have paired, or must be pairing with us
 *     right now because the user typed a code.
 *  4. A message whose `to` names somebody else is not ours to act on.
 *  5. A message we have already processed is a replay, and is dropped. The
 *     timestamp window is a second line of defence, not a substitute: a peer
 *     inside the window can still re-send, hence the id cache.
 */
class LinkSession(
    private val localInfo: PeerInfo,
    private val registry: PeerRegistry,
    /** Injected so the timestamp window and pruning are testable without sleeping. */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** How far a message timestamp may drift before it is refused. */
    private val maxClockSkewMs: Long = DEFAULT_MAX_SKEW_MS
) {

    /** What the transport should do with an inbound message. */
    sealed class Outcome {
        /**
         * An envelope the transport must send back, e.g. `hello.ack` or a
         * `pong`. Kept separate from [Deliver] because one is outgoing and the
         * other is a command to act on; conflating them is how a transport ends
         * up broadcasting an ack or acting on its own reply.
         */
        data class Respond(val envelope: Envelope) : Outcome()

        /** An authorised inbound command. Safe to act on. */
        data class Deliver(val envelope: Envelope) : Outcome()

        /** A peer completed the handshake. [remote] is now usable. */
        data class Paired(val remote: PeerInfo, val alreadyKnown: Boolean) : Outcome()

        /** Refused, with a machine-readable [reason] from [RejectReason]. */
        data class Reject(val reason: String, val detail: String = "") : Outcome()

        /** Ignored without ending the session: wrong recipient, replay, stale. */
        data class Drop(val detail: String) : Outcome()

        /** Unparseable or structurally invalid. The transport should close. */
        data class Malformed(val detail: String) : Outcome()
    }

    private enum class State { AWAITING_HELLO, AWAITING_ACK, READY, CLOSED }

    private var state = State.AWAITING_HELLO

    /** The peer we are talking to, once known. */
    var remote: PeerInfo? = null
        private set

    /** Device id of the peer, or null before the handshake. */
    val remoteId: String? get() = remote?.deviceId

    val isReady: Boolean get() = state == State.READY

    /** Why the session ended, for the UI. */
    var closedReason: String = ""
        private set

    /** Processed message ids, in arrival order, so the oldest can be evicted. */
    private val seen = LinkedHashSet<String>()

    private var lastRemoteTs = 0L

    // ── Outbound ──────────────────────────────────────────────────

    /**
     * The opening message. Unsigned, because no key exists yet on either side.
     *
     * @return the `hello` envelope to send, or null when [state] has moved on.
     */
    fun beginHandshake(): Envelope? {
        if (state != State.AWAITING_HELLO) return null
        state = State.AWAITING_ACK
        val payload = HelloPayload(
            peer = localInfo,
            revision = LinkProtocol.REVISION,
            hasKey = false
        ).toJson()
        return Envelope.of(Envelope.Type.HELLO, localInfo.deviceId, Envelope.Type.BROADCAST, payload)
    }

    /**
     * Builds a signed message for the connected peer.
     *
     * @return null when the session is not ready or no key is available, which
     *   the caller must treat as "do not send" rather than sending unsigned.
     */
    fun send(type: String, payload: JsonObject): Envelope? {
        val peer = remote ?: return null
        if (state != State.READY) return null
        val key = registry.keyFor(peer.deviceId) ?: return null
        val env = Envelope.of(type, localInfo.deviceId, peer.deviceId, payload)
        return Pairing.sign(env, key)
    }

    /** Broadcast to every peer; used by the server side. */
    fun sendSigned(type: String, payload: JsonObject): Envelope? {
        val peer = remote ?: return null
        if (state != State.READY) return null
        val key = registry.keyFor(peer.deviceId) ?: return null
        val env = Envelope.of(type, localInfo.deviceId, Envelope.Type.BROADCAST, payload)
        return Pairing.sign(env, key)
    }

    // ── Inbound ───────────────────────────────────────────────────

    /** Handles one decoded text message. */
    fun receive(env: Envelope?): List<Outcome> {
        if (state == State.CLOSED) return listOf(Outcome.Drop("session closed"))
        if (env == null) return listOf(Outcome.Malformed("not a valid envelope"))

        return when (env.t) {
            Envelope.Type.HELLO -> onHello(env)
            Envelope.Type.HELLO_ACK -> onHelloAck(env)
            Envelope.Type.HELLO_REJECT -> onHelloReject(env)
            else -> onApplication(env)
        }
    }

    /** Convenience for transports holding the raw frame text. */
    fun receive(raw: String): List<Outcome> = receive(Envelope.decode(raw))

    private fun onHello(env: Envelope): List<Outcome> {
        if (state != State.AWAITING_HELLO) {
            return listOf(Outcome.Malformed("unexpected hello on an established session"))
        }

        val info = decodePeer(env.d) ?: return listOf(Outcome.Malformed("hello without peer info"))
        if (info.deviceId.isBlank()) return listOf(Outcome.Malformed("hello with a blank device id"))
        if (info.deviceId == localInfo.deviceId) {
            return listOf(Outcome.Reject(RejectReason.BLOCKED, "a device cannot connect to itself"))
        }

        if (info.protocolVersion != LinkProtocol.VERSION) {
            return listOf(Outcome.Reject(RejectReason.PROTOCOL_MISMATCH, "peer speaks v${info.protocolVersion}"))
        }

        val alreadyKnown = registry.isTrusted(info.deviceId)
        val key = if (alreadyKnown) {
            registry.keyFor(info.deviceId)
        } else {
            // Pairing in progress: the user typed a code on this device.
            val pin = registry.pendingPin
            if (pin.isNullOrBlank()) {
                return listOf(Outcome.Reject(RejectReason.NOT_PAIRED, "no pairing code pending"))
            }
            Pairing.deriveKey(pin, localInfo.deviceId, info.deviceId).also {
                registry.trust(pin, info.deviceId, info.deviceName, info.capabilities)
            }
        }
        if (key == null) return listOf(Outcome.Reject(RejectReason.NOT_PAIRED, "no key for peer"))

        remote = info
        state = State.READY

        val ack = HelloAckPayload(peer = localInfo, accepted = true).toJson()
        val signed = Pairing.sign(
            Envelope.of(Envelope.Type.HELLO_ACK, localInfo.deviceId, info.deviceId, ack),
            key
        )
        return listOf(Outcome.Paired(info, alreadyKnown), Outcome.Respond(signed))
    }

    private fun onHelloAck(env: Envelope): List<Outcome> {
        if (state != State.AWAITING_ACK) {
            return listOf(Outcome.Drop("hello.ack outside a handshake"))
        }

        val accepted = env.d["accept"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
        } ?: true

        // Read the verdict before the peer block: a refusal need not carry
        // peer info, and reporting it as malformed would hide the real reason.
        if (!accepted) {
            val reason = env.d["reason"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }.orEmpty()
            state = State.CLOSED
            closedReason = reason.ifBlank { RejectReason.BLOCKED }
            return listOf(Outcome.Reject(closedReason, "peer refused the session"))
        }

        val info = decodePeer(env.d) ?: return listOf(Outcome.Malformed("hello.ack without peer info"))

        // The responder signs the ack, so this is the first authenticated
        // message: verify it before trusting anything the peer claims.
        val key = registry.keyFor(info.deviceId) ?: run {
            val pin = registry.pendingPin
            if (pin.isNullOrBlank()) {
                state = State.CLOSED
                closedReason = RejectReason.NOT_PAIRED
                return listOf(Outcome.Reject(RejectReason.NOT_PAIRED, "no key to verify hello.ack"))
            }
            Pairing.deriveKey(pin, localInfo.deviceId, info.deviceId)
        }
        if (!Pairing.verified(env, key)) {
            state = State.CLOSED
            closedReason = RejectReason.BAD_SIGNATURE
            return listOf(Outcome.Reject(RejectReason.BAD_SIGNATURE, "hello.ack signature did not verify"))
        }

        val alreadyKnown = registry.isTrusted(info.deviceId)
        if (!alreadyKnown) registry.trustWithKey(info.deviceId, info.deviceName, key, info.capabilities)

        remote = info
        state = State.READY
        return listOf(Outcome.Paired(info, alreadyKnown))
    }

    private fun onHelloReject(env: Envelope): List<Outcome> {
        val reason = env.d["reason"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.orEmpty().ifBlank { RejectReason.BLOCKED }
        state = State.CLOSED
        closedReason = reason
        return listOf(Outcome.Reject(reason, "peer rejected the session"))
    }

    private fun onApplication(env: Envelope): List<Outcome> {
        if (state == State.AWAITING_HELLO) {
            return listOf(Outcome.Malformed("'$env.t' before hello"))
        }
        if (state == State.AWAITING_ACK) {
            return listOf(Outcome.Drop("'$env.t' before hello.ack"))
        }

        // Now the signature is mandatory. This is the check the old server
        // never made, so any device that completed the handshake could then
        // send arbitrary commands.
        val peer = remote ?: return listOf(Outcome.Malformed("application message with no peer"))
        val key = registry.keyFor(peer.deviceId)
            ?: return listOf(Outcome.Reject(RejectReason.NOT_PAIRED, "no key for peer"))
        if (!Pairing.verified(env, key)) {
            return listOf(Outcome.Reject(RejectReason.BAD_SIGNATURE, "unsigned or forged message"))
        }

        if (!isForUs(env)) return listOf(Outcome.Drop("addressed to ${env.to}"))

        if (!fresh(env)) return listOf(Outcome.Drop("replayed or stale timestamp"))

        if (!remember(env.id)) return listOf(Outcome.Drop("duplicate id ${env.id}"))

        when (env.t) {
            Envelope.Type.PING -> {
                lastRemoteTs = env.ts
                return listOf(
                    Outcome.Drop("ping"),
                    Outcome.Respond(
                        Pairing.sign(
                            Envelope.of(
                                Envelope.Type.PONG,
                                localInfo.deviceId,
                                env.from,
                                buildJsonObject { put("echo", env.id) }
                            ),
                            key
                        )
                    )
                )
            }

            Envelope.Type.PONG -> {
                lastRemoteTs = env.ts
                return listOf(Outcome.Drop("pong"))
            }

            else -> {
                lastRemoteTs = env.ts
                registry.touch(peer.deviceId, peer.deviceName, peer.capabilities)
                return listOf(Outcome.Deliver(env))
            }
        }
    }

    // ── Checks ────────────────────────────────────────────────────

    /** A message is ours when it is addressed to us or to everyone. */
    private fun isForUs(env: Envelope): Boolean =
        env.to.isBlank() || env.to == Envelope.Type.BROADCAST || env.to == localInfo.deviceId

    /**
     * Records [id] as handled.
     *
     * @return false when it was already present, i.e. the message is a replay.
     */
    private fun remember(id: String): Boolean {
        if (!seen.add(id)) return false
        if (seen.size > MAX_REMEMBERED_IDS) {
            val oldest = seen.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }

    /**
     * Rejects timestamps outside the skew window, and anything that goes
     * backwards relative to the last message from this peer, so an old capture
     * cannot be replayed later.
     */
    private fun fresh(env: Envelope): Boolean {
        val delta = now() - env.ts
        if (delta > maxClockSkewMs) return false
        if (-delta > maxClockSkewMs) return false
        if (env.ts < lastRemoteTs) return false
        return true
    }

    /** Reads the shared [PeerInfo] out of a hello or ack payload. */
    private fun decodePeer(payload: JsonObject): PeerInfo? = runCatching {
        LinkProtocol.json.decodeFromJsonElement(PeerInfo.serializer(), payload["peer"]!!)
    }.getOrNull()

    /**
     * Builds the refusal to send back before hanging up.
     *
     * Signed when a key exists, so a known peer gets an authenticated answer.
     * Unsigned otherwise, which is the common case: refusing is what happens
     * when no key could be found. Forging one can only make a client abandon a
     * connection it is free to retry, so that is an acceptable trade for the
     * peer at least learning that it was refused rather than seeing a socket
     * close with no explanation.
     */
    fun reject(reason: String): Envelope {
        val payload = buildJsonObject { put("reason", reason) }
        val env = Envelope.of(
            Envelope.Type.HELLO_REJECT,
            localInfo.deviceId,
            remoteId ?: Envelope.Type.BROADCAST,
            payload
        )
        val key = remoteId?.let { registry.keyFor(it) }
        return if (key != null) Pairing.sign(env, key) else env
    }

    /** Ends the session. The transport closes the socket. */
    fun close(reason: String = "") {
        state = State.CLOSED
        if (reason.isNotBlank()) closedReason = reason
    }

    companion object {
        private const val MAX_REMEMBERED_IDS = 512
        const val DEFAULT_MAX_SKEW_MS = 5L * 60 * 1000
    }
}
