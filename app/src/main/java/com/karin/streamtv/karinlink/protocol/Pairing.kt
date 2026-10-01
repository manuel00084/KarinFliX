package com.karin.streamtv.karinlink.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Self-contained Base64 (RFC 4648) so the protocol package stays pure JVM.
 *
 * `android.util.Base64` is unavailable in plain JVM unit tests, and
 * `java.util.Base64` needs API 26 while this app supports minSdk 23. Neither
 * is acceptable for code that must be unit tested without Robolectric.
 */
internal object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                    ((data[i + 1].toInt() and 0xFF) shl 8) or
                    (data[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n ushr 18) and 0x3F])
            sb.append(ALPHABET[(n ushr 12) and 0x3F])
            sb.append(ALPHABET[(n ushr 6) and 0x3F])
            sb.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                sb.append(ALPHABET[(n ushr 12) and 0x3F])
                sb.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or
                        ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                sb.append(ALPHABET[(n ushr 12) and 0x3F])
                sb.append(ALPHABET[(n ushr 6) and 0x3F])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.filter { it != '=' && it != '\n' && it != '\r' }
        if (clean.isEmpty()) return ByteArray(0)
        val out = ByteArray(clean.length * 3 / 4)
        var acc = 0
        var bits = 0
        var o = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
            require(v >= 0) { "invalid base64 character: $ch" }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = ((acc ushr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(o)
    }
}

/**
 * Pairing keys and message signing.
 *
 * The previous implementation accepted any `device_uuid` a caller cared to
 * send and handed back a token, with no shared secret anywhere. Anyone on the
 * LAN could impersonate a device. Here, two devices that have been paired once
 * derive a shared key from the PIN and sign every subsequent frame with it, so
 * an unpaired peer cannot drive a session.
 *
 * Key derivation: HKDF-SHA256 over the sorted pair of device ids, so both sides
 * independently arrive at the same key without exchanging it.
 */
object Pairing {

    private const val PIN_LENGTH = 6
    private const val KEY_BYTES = 32
    private const val HKDF_SALT = "karin-link/v2"
    private const val HMAC_ALGO = "HmacSHA256"
    private const val SEP = 0x01

    private val random = SecureRandom()

    /** Characters with no 0/O/1/I/L ambiguity, so a code can be read aloud. */
    private const val PIN_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

    /**
     * A fresh 6-character pairing code, e.g. `K7M2QX`.
     * Uses [SecureRandom]; the previous code generator used java.util.Random,
     * which is not a CSPRNG.
     */
    fun newPin(): String = buildString(PIN_LENGTH) {
        repeat(PIN_LENGTH) { append(PIN_ALPHABET[random.nextInt(PIN_ALPHABET.length)]) }
    }

    /** True if [pin] has the shape this class generates. */
    fun isValidPinShape(pin: String): Boolean =
        pin.length == PIN_LENGTH && pin.all { it in PIN_ALPHABET }

    /**
     * Normalizes user-typed codes so `k7m2qx` and `K7M2QX` pair. Ambiguous
     * characters a user may substitute are folded to their canonical form.
     */
    fun normalizePin(pin: String): String = pin.trim().uppercase().map { ch ->
        when (ch) {
            '0', 'O' -> '2'
            '1', 'I', 'L' -> 'J'
            else -> ch
        }
    }.joinToString("")

    /**
     * Derives the shared key for a pair of devices from their pairing code.
     *
     * The two ids are sorted before use so both sides compute the same key
     * regardless of who initiated.
     */
    fun deriveKey(pin: String, localId: String, remoteId: String): ByteArray {
        val ids = listOf(localId, remoteId).sorted()
        val prk = hkdfExtract(
            ikm = normalizePin(pin).toByteArray(Charsets.UTF_8),
            salt = HKDF_SALT.toByteArray(Charsets.UTF_8)
        )
        return hkdfExpand(prk, ids[0] + SEP.toChar() + ids[1], KEY_BYTES)
    }

    /** Signs [data] with [key]. Returns base64 (no wrap). */
    fun sign(data: String, key: ByteArray): String =
        Base64Codec.encode(signRaw(data, key))

    private fun signRaw(data: String, key: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(key, HMAC_ALGO))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    /**
     * Constant-time verification. [Envelope.sigData] is the signed payload.
     *
     * The previous Python backend used hmac.compare_digest here, which was
     * correct; the Kotlin side had no signing at all. Never compare with ==.
     */
    fun verify(data: String, signature: String, key: ByteArray): Boolean {
        val expected = sign(data, key)
        val a = expected.toByteArray(Charsets.UTF_8)
        val b = signature.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(a, b)
    }

    /**
     * Convenience: sign an envelope in place.
     * Returns a copy carrying [Envelope.sig]; the signature is never computed
     * over the signature field itself.
     */
    fun sign(env: Envelope, key: ByteArray): Envelope = env.copy(sig = sign(env.sigData(), key))

    /** True when [env] carries a verifiable signature for [key]. */
    fun verified(env: Envelope, key: ByteArray): Boolean {
        if (env.sig.isBlank()) return false
        return verify(env.sigData(), env.sig, key)
    }

    /** A random key, used when pairing over the relay instead of via PIN. */
    fun randomKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    /** Base64 key for storage. */
    fun encodeKey(key: ByteArray): String = Base64Codec.encode(key)

    fun decodeKey(encoded: String): ByteArray? =
        runCatching { Base64Codec.decode(encoded) }.getOrNull()

    // ── HKDF ──────────────────────────────────────────────────────

    private fun hkdfExtract(ikm: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(salt, HMAC_ALGO))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: String, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(prk, HMAC_ALGO))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info.toByteArray(Charsets.UTF_8))
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, take)
            offset += take
            counter++
        }
        return out
    }

    /** Exposed for the registry's storage format. */
    const val KEY_LENGTH = KEY_BYTES
}

/**
 * One trusted peer.
 *
 * @param deviceId stable identity of the remote device
 * @param deviceName last known display name, for the UI only
 * @param key derived shared key, base64
 * @param lastSeen epoch millis of the last successful contact
 * @param capabilities last advertised capabilities, used to decide whether a
 *   peer can be asked to play, proxy or transcode before connecting
 */
data class TrustedPeer(
    val deviceId: String,
    val deviceName: String,
    val key: String,
    val lastSeen: Long = 0L,
    val capabilities: Set<Capability> = emptySet()
)
