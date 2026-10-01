package com.karin.streamtv.karinlink.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * RFC 6455 frame codec for the KARIN Link transport.
 *
 * Extracted from [com.karin.streamtv.karinlink.LinkServer] and
 * [com.karin.streamtv.karinlink.LinkClient] so both ends share one
 * implementation and it can be tested without a socket. The previous inline
 * version mishandled fragmentation: it treated a continuation frame (`0x0`) as
 * a complete message, so any payload split across frames was delivered as
 * several truncated JSON documents and silently dropped.
 */
object WsOpcode {
    const val CONTINUATION = 0x0
    const val TEXT = 0x1
    const val BINARY = 0x2
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA
}

/**
 * Incremental WebSocket frame parser.
 *
 * Feed it socket bytes with [feed]; it emits whole messages as they complete.
 * Not thread safe: drive it from a single reader thread, as the transport does.
 */
class WsFrameParser(
    /** Frames larger than this are rejected, to bound memory from a hostile peer. */
    private val maxPayloadBytes: Int = 10 * 1024 * 1024,
    private val maxMessageBytes: Int = 12 * 1024 * 1024,
    /**
     * What the far end is required to do, per RFC 6455 section 5.1.
     *
     * A client must mask every frame it sends and a server must mask none. The
     * masking exists to stop a poisoned cache on an intermediary from
     * rewriting traffic, which only works if both ends enforce it, so a frame
     * that breaks the rule tears the connection down rather than being
     * tolerated. Leave null to accept either, which is only right for a
     * transport that genuinely does not know its role.
     */
    private val expectMasked: Boolean? = null
) {

    sealed class Event {
        data class Text(val message: String) : Event()
        data class Binary(val payload: ByteArray) : Event()
        data class Ping(val payload: ByteArray) : Event()
        data class Pong(val payload: ByteArray) : Event()
        data object Closed : Event()
        data class ProtocolError(val reason: String) : Event()
    }

    private var buffer = ByteArray(0)

    /** Read cursor into [buffer]. Everything before it has been consumed. */
    private var pos = 0

    private val fragments = ByteArrayOutputStream()

    /** Opcode of the message currently being assembled. */
    private var messageOpcode = -1

    /** True once a close frame has been seen, so further data is ignored. */
    private var closed = false

    fun feed(data: ByteArray): List<Event> {
        if (data.isEmpty() || closed) return emptyList()
        appendToBuffer(data)
        val events = ArrayList<Event>()

        while (true) {
            val frame = readFrame() ?: break
            when (val outcome = frame) {
                is ReadOutcome.Error -> {
                    events.add(Event.ProtocolError(outcome.reason))
                    // The stream position is no longer trustworthy after a
                    // framing error; drop everything buffered and stop.
                    buffer = ByteArray(0)
                    pos = 0
                    closed = true
                    return events
                }

                is ReadOutcome.Control -> when (outcome.opcode) {
                    WsOpcode.PING -> events.add(Event.Ping(outcome.payload))
                    WsOpcode.PONG -> events.add(Event.Pong(outcome.payload))
                    WsOpcode.CLOSE -> {
                        events.add(Event.Closed)
                        closed = true
                        return events
                    }
                }

                is ReadOutcome.Data -> {
                    val event = append(outcome.opcode, outcome.payload, outcome.fin) ?: continue
                    events.add(event)
                }
            }
        }
        return events
    }

    /** Appends [data], compacting away the already-consumed prefix. */
    private fun appendToBuffer(data: ByteArray) {
        val remaining = available()
        val needed = remaining + data.size
        if (buffer.size < needed) {
            buffer = buffer.copyOfRange(pos, pos + remaining) + data
        } else {
            // Room exists: shift the unread tail down, then append in place.
            System.arraycopy(buffer, pos, buffer, 0, remaining)
            System.arraycopy(data, 0, buffer, remaining, data.size)
        }
        pos = 0
    }

    private fun available(): Int = buffer.size - pos

    /**
     * Adds a data frame to the current message.
     *
     * @return the completed message, or null when more fragments are expected.
     */
    private fun append(opcode: Int, payload: ByteArray, fin: Boolean): Event? {
        when (opcode) {
            WsOpcode.TEXT, WsOpcode.BINARY -> {
                if (messageOpcode != -1) {
                    // A new data frame started before the previous message was
                    // finished: the peer broke the contract, so surface it
                    // rather than silently interleaving the two messages.
                    resetMessage()
                    return Event.ProtocolError("new data frame before previous message completed")
                }
                if (payload.size > maxMessageBytes) {
                    resetMessage()
                    return Event.ProtocolError("message of ${payload.size} bytes exceeds the limit")
                }
                if (fin) {
                    return finish(opcode, payload)
                }
                messageOpcode = opcode
                fragments.write(payload)
                return null
            }

            WsOpcode.CONTINUATION -> {
                if (messageOpcode == -1) {
                    return Event.ProtocolError("continuation frame with no message to continue")
                }
                if (fragments.size() + payload.size > maxMessageBytes) {
                    resetMessage()
                    return Event.ProtocolError("fragmented message exceeds the limit")
                }
                fragments.write(payload)
                if (!fin) return null
                return finish(messageOpcode, fragments.toByteArray())
            }

            else -> return Event.ProtocolError("unexpected data opcode $opcode")
        }
    }

    private fun finish(opcode: Int, payload: ByteArray): Event {
        resetMessage()
        return if (opcode == WsOpcode.TEXT) {
            Event.Text(String(payload, Charsets.UTF_8))
        } else {
            Event.Binary(payload)
        }
    }

    private fun resetMessage() {
        messageOpcode = -1
        fragments.reset()
    }

    // ── Frame reading ─────────────────────────────────────────────

    private sealed class ReadOutcome {
        data class Data(val opcode: Int, val payload: ByteArray, val fin: Boolean) : ReadOutcome()
        data class Control(val opcode: Int, val payload: ByteArray) : ReadOutcome()
        data class Error(val reason: String) : ReadOutcome()
    }

    /**
     * Reads one frame, or null when the buffered bytes do not yet contain a
     * whole one.
     *
     * Nothing is consumed until the entire frame is present. An earlier version
     * consumed the header and mask as it read them and then returned null when
     * the payload was still incomplete, which silently destroyed the frame and
     * made the parser resume mid-frame on the next read. Since TCP splits
     * frames across segments as a matter of course, that broke ordinary
     * streaming, not just an edge case.
     */
    private fun readFrame(): ReadOutcome? {
        if (available() < 2) return null
        val b0 = peek(0).toInt() and 0xFF
        val b1 = peek(1).toInt() and 0xFF

        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0

        var offset = 2
        var length = (b1 and 0x7F).toLong()
        when (length) {
            126L -> {
                if (available() < offset + 2) return null
                length = (((peek(offset).toInt() and 0xFF) shl 8) or (peek(offset + 1).toInt() and 0xFF)).toLong()
                offset += 2
            }

            127L -> {
                if (available() < offset + 8) return null
                length = 0
                for (i in 0 until 8) {
                    length = (length shl 8) or (peek(offset + i).toLong() and 0xFF)
                }
                offset += 8
            }
        }

        if (length < 0 || length > maxPayloadBytes) {
            return ReadOutcome.Error("frame of $length bytes exceeds the ${maxPayloadBytes} byte limit")
        }

        if (expectMasked != null && masked != expectMasked) {
            val who = if (expectMasked) "a client" else "a server"
            return ReadOutcome.Error(
                if (masked) "a server must not mask its frames" else "$who must mask every frame it sends"
            )
        }

        // offset is now the end of the header; the mask, if present, follows.
        val maskBytes = if (masked) 4 else 0
        val total = offset + maskBytes + length
        if (available() < total) return null

        // The whole frame is buffered, so it is now safe to consume.
        consume(offset)
        val mask = if (masked) take(4) else null
        val payload = if (length == 0L) ByteArray(0) else take(length.toInt()) ?: return null
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor (mask[i % 4].toInt() and 0xFF)).toByte()
            }
        }

        return when (opcode) {
            WsOpcode.CLOSE, WsOpcode.PING, WsOpcode.PONG -> ReadOutcome.Control(opcode, payload)
            WsOpcode.CONTINUATION, WsOpcode.TEXT, WsOpcode.BINARY -> ReadOutcome.Data(opcode, payload, fin)
            else -> ReadOutcome.Error("reserved opcode $opcode")
        }
    }

    /** Reads the byte at [index] bytes ahead of the cursor. */
    private fun peek(index: Int): Byte = buffer[pos + index]

    private fun consume(n: Int) {
        pos += n
        if (pos >= buffer.size) {
            buffer = ByteArray(0)
            pos = 0
        }
    }

    /** Removes and returns [n] bytes, or null when fewer than [n] are buffered. */
    private fun take(n: Int): ByteArray? {
        if (available() < n) return null
        val out = buffer.copyOfRange(pos, pos + n)
        pos += n
        if (pos == buffer.size) {
            buffer = ByteArray(0)
            pos = 0
        }
        return out
    }

    // ── Frame writing ─────────────────────────────────────────────

    /**
     * Encodes one frame.
     *
     * @param fin false for the opening frame of a fragmented message.
     * @param mask a 4-byte key for client frames, or null for server frames.
     *   Clients MUST mask; servers MUST NOT.
     */
    fun encode(opcode: Int, payload: ByteArray, mask: ByteArray? = null, fin: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write((if (fin) 0x80 else 0x00) or opcode)

        val len = payload.size
        when {
            len < 126 -> out.write((if (mask != null) 0x80 else 0x00) or len)
            len < 65536 -> {
                out.write((if (mask != null) 0x80 else 0x00) or 126)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }

            else -> {
                out.write((if (mask != null) 0x80 else 0x00) or 127)
                var l = len.toLong()
                for (i in 7 downTo 0) out.write(((l ushr (8 * i)) and 0xFF).toInt())
            }
        }

        if (mask != null) {
            out.write(mask)
            for (i in payload.indices) {
                out.write((payload[i].toInt() xor (mask[i % 4].toInt() and 0xFF)).toByte().toInt())
            }
        } else {
            out.write(payload)
        }
        return out.toByteArray()
    }

    companion object {
        /**
         * RFC 6455 section 1.3 handshake GUID.
         *
         * Verified against the worked example: the SHA-1 of
         * `dGhlIHNhbXBsZSBub25jZQ==` + this GUID is `b37a4f2c...`, base64
         * `s3pPLMBiTxaQ9kYGzzhZRbK+xOo=`. The value previously in this codebase
         * ended `C5AB0DC11B67` instead of `C5AB0DC85B11`, which produces a
         * different hash, so every client would have rejected the handshake and
         * no session could ever be established.
         */
        const val HANDSHAKE_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /**
         * Computes the `Sec-WebSocket-Accept` value for a client key.
         *
         * @throws IllegalArgumentException if [key] is not a valid base64 key.
         */
        fun acceptFor(key: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-1")
            val hash = digest.digest((key + HANDSHAKE_GUID).toByteArray(Charsets.US_ASCII))
            return Base64Codec.encode(hash)
        }
    }
}

/** Thrown when a peer disconnects mid-frame. */
class WsClosedException(message: String) : EOFException(message)
