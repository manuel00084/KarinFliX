package com.karin.streamtv.karinlink.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * KARIN Link wire protocol, version 2.
 *
 * Single envelope for every message, both directions. This replaces the
 * previous situation where the Python backend and the Kotlin client each
 * defined their own shape (`{"type","payload"}` vs `{"type","data"}`), which
 * made them unable to talk to each other.
 *
 * Transport: raw WebSocket text frames, one JSON envelope per frame.
 */
object LinkProtocol {

    /** Bumped on any breaking change. Peers with a different major are rejected. */
    const val VERSION = 2

    /** Bumped for backwards-compatible additions. */
    const val REVISION = 0

    const val WS_PATH = "/ws"

/**
 * HTTP endpoint for the shared folders.
 *
 * Plain HTTP rather than a WebSocket frame, because the other device mostly
 * wants to pull bytes. It carries its own token: the pairing key is tied to a
 * live socket, and a file transfer outlives that.
 */
const val FS_PATH = "/fs"

/**
 * Subida de un vídeo para reproducirlo aquí y borrarlo después.
 *
 * Va aparte de [FS_PATH] a propósito: `/fs` es de solo lectura y este es el
 * único endpoint por el que la app acepta escribir algo en el disco. Comparte
 * su token porque la escritura es todavía más delicada que la lectura, y un
 * segundo secreto sería un sitio más donde equivocarse.
 */
const val PUSH_PATH = "/push"

    /**
     * Service type advertised over mDNS. This is the value peers browse for.
     * The previous Android build used `_karinflinx._tcp.` while the Python
     * backend used `_karinflix._tcp.local.`; the two never matched, so no
     * device was ever discovered. Both sides now use exactly this string.
     */
    const val SERVICE_TYPE = "_karinflix._tcp."

    /**
     * TXT record published with the service. Kept short: total TXT payload is
     * capped at 1300 bytes by many mDNS responders, and older Android TV boxes
     * drop the whole record if it is larger.
     */
    object Txt {
        const val DEVICE_ID = "id"
        const val DEVICE_NAME = "name"
        const val DEVICE_MODEL = "model"
        const val APP_VERSION = "v"
        const val PROTOCOL = "p"
        const val CAPS = "caps"
        const val PORT = "port"
    }

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

/** Capability bit flags advertised in `hello` and in the mDNS TXT record. */
@Serializable
enum class Capability(val flag: String) {
    /** Can receive a play request and play it with its own player. */
    PLAY("play"),

    /** Can act as a remote control target (dispatch dpad / media keys). */
    REMOTE("remote"),

    /** Can run a StreamServer and serve video bytes to another peer. */
    PROXY("proxy"),

    /** Can remux containers without re-encoding. */
    REMUX("remux"),

    /** Can transcode to a different codec/resolution. */
    TRANSCODE("transcode");

    companion object {
        fun encode(caps: Set<Capability>): String = caps.joinToString(",") { it.flag }

        fun decode(raw: String?): Set<Capability> {
            if (raw.isNullOrBlank()) return emptySet()
            val wanted = raw.split(',').map { it.trim() }.toSet()
            return entries.filterTo(LinkedHashSet()) { it.flag in wanted }
        }
    }
}

/**
 * Device metadata, shared between `hello`, `hello.ack` and discovery records.
 */
@Serializable
data class PeerInfo(
    val deviceId: String,
    val deviceName: String,
    val appVersion: String,
    val protocolVersion: Int = LinkProtocol.VERSION,
    val deviceModel: String = "",
    val capabilities: Set<Capability> = emptySet()
)

/**
 * The single envelope for all messages.
 *
 * @param v protocol version of the sender
 * @param t message type, see [Type]
 * @param id unique id, used to correlate a request with its reply
 * @param from sender device id
 * @param to recipient device id, or [Type.BROADCAST] to reach every peer
 * @param ts sender timestamp, epoch millis
 * @param sig HMAC-SHA256 of [sigData] using the shared pairing key. Empty
 *   during the unauthenticated `hello` exchange, verified on every later
 *   message so an unpaired peer cannot drive the session.
 * @param d type-specific payload
 */
@Serializable
data class Envelope(
    val v: Int = LinkProtocol.VERSION,
    val t: String,
    val id: String = UUID.randomUUID().toString(),
    val from: String = "",
    val to: String = "",
    val ts: Long = System.currentTimeMillis(),
    val sig: String = "",
    val d: JsonObject = JsonObject(emptyMap())
) {
    /** Canonical bytes covered by [sig]. Field order is fixed, not JSON order. */
    fun sigData(): String = "$v|$t|$id|$from|$to|$ts|${LinkProtocol.json.encodeToString(JsonObject.serializer(), d)}"

    object Type {
        const val BROADCAST = "*"

        /** Initiator -> responder, opens a session. Unauthenticated. */
        const val HELLO = "hello"

        /** Responder -> initiator, confirms and echoes capabilities. */
        const val HELLO_ACK = "hello.ack"

        /** Rejects a peer, with a machine-readable reason. */
        const val HELLO_REJECT = "hello.reject"

        /** Keepalive both ways; also refreshes liveness. */
        const val PING = "ping"
        const val PONG = "pong"

        /** Ask a peer to re-advertise its current capabilities/state. */
        const val STATE_REQUEST = "state.request"
        const val STATE = "state"
    }

    fun encode(): String = LinkProtocol.json.encodeToString(serializer(), this)

    companion object {
        fun decode(raw: String): Envelope? =
            runCatching { LinkProtocol.json.decodeFromString(serializer(), raw) }.getOrNull()

        fun of(type: String, from: String, to: String, payload: JsonObject): Envelope =
            Envelope(t = type, from = from, to = to, d = payload)
    }
}

/**
 * Payload of `hello`. Sent unauthenticated, so it carries no secrets.
 */
@Serializable
data class HelloPayload(
    val peer: PeerInfo,
    /** Highest protocol revision the sender understands. */
    val revision: Int = LinkProtocol.REVISION,
    /** True when the sender already has a pairing key for the responder. */
    val hasKey: Boolean = false
) {
    fun toJson(): JsonObject =
        LinkProtocol.json.encodeToJsonElement(serializer(), this) as JsonObject
}

/** Payload of `hello.ack`. */
@Serializable
data class HelloAckPayload(
    val peer: PeerInfo,
    @SerialName("accept") val accepted: Boolean = true,
    /** Populated when [accepted] is false. */
    val reason: String = ""
) {
    fun toJson(): JsonObject =
        LinkProtocol.json.encodeToJsonElement(serializer(), this) as JsonObject
}

/**
 * Machine-readable rejection reasons. Kept as constants so the UI can map them
 * to localized strings without string-parsing.
 */
object RejectReason {
    const val PROTOCOL_MISMATCH = "protocol_mismatch"
    const val ALREADY_PAIRED = "already_paired"
    const val NOT_PAIRED = "not_paired"
    const val BAD_SIGNATURE = "bad_signature"
    const val PIN_REQUIRED = "pin_required"
    const val PIN_MISMATCH = "pin_mismatch"
    const val BLOCKED = "blocked"
}
