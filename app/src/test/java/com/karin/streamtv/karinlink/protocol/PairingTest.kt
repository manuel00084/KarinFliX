package com.karin.streamtv.karinlink.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security-critical tests for pairing, key derivation and message signing.
 *
 * Pure JVM: no Android framework classes are touched, so no Robolectric.
 */
class PairingTest {

    // ── PIN generation ────────────────────────────────────────────

    @Test
    fun `generated pins have the right shape and alphabet`() {
        repeat(200) {
            val pin = Pairing.newPin()
            assertEquals("pin must be 6 chars: $pin", 6, pin.length)
            assertTrue("pin has ambiguous char: $pin", Pairing.isValidPinShape(pin))
        }
    }

    @Test
    fun `generated pins do not repeat`() {
        val pins = (1..300).map { Pairing.newPin() }.toSet()
        // 32^6 combinations, so a collision in 300 draws means the RNG is broken.
        assertEquals("pin generator is not random", 300, pins.size)
    }

    @Test
    fun `pin alphabet excludes visually ambiguous characters`() {
        repeat(500) {
            val pin = Pairing.newPin()
            listOf('0', 'O', '1', 'I', 'L').forEach { bad ->
                assertFalse("pin '$pin' must not contain '$bad'", pin.contains(bad))
            }
        }
    }

    @Test
    fun `isValidPinShape rejects wrong lengths and bad characters`() {
        assertFalse(Pairing.isValidPinShape(""))
        assertFalse(Pairing.isValidPinShape("ABC"))
        assertFalse(Pairing.isValidPinShape("ABCDEFG"))
        assertFalse(Pairing.isValidPinShape("ABCDE-"))
        assertFalse(Pairing.isValidPinShape("abcdef"))
    }

    @Test
    fun `normalizePin folds user typing mistakes to the same code`() {
        // Folding: 0/O -> 2, 1/I/L -> J.
        assertEquals(Pairing.normalizePin("K7M2QX"), Pairing.normalizePin("k7m2qx"))
        assertEquals("2JJ", Pairing.normalizePin("O1L"))
        assertEquals("2JJ", Pairing.normalizePin("0IL"))
        assertEquals("2J", Pairing.normalizePin("0I"))
    }

    @Test
    fun `normalizePin trims whitespace from paste`() {
        assertEquals(Pairing.normalizePin("K7M2QX"), Pairing.normalizePin("  K7M2QX\n"))
    }

    // ── Key derivation ────────────────────────────────────────────

    @Test
    fun `both sides derive the same key regardless of who initiated`() {
        val pin = Pairing.newPin()
        val fromPhone = Pairing.deriveKey(pin, "phone-abc", "tv-xyz")
        val fromTv = Pairing.deriveKey(pin, "tv-xyz", "phone-abc")

        assertArrayEquals("key derivation must be symmetric", fromPhone, fromTv)
        assertEquals(Pairing.KEY_LENGTH, fromPhone.size)
    }

    @Test
    fun `different pins derive different keys`() {
        val a = Pairing.deriveKey(Pairing.newPin(), "a", "b")
        val b = Pairing.deriveKey(Pairing.newPin(), "a", "b")
        assertFalse("distinct pins must not collide", a.contentEquals(b))
    }

    @Test
    fun `key is bound to the device pair`() {
        val pin = Pairing.newPin()
        assertFalse(Pairing.deriveKey(pin, "a", "b").contentEquals(Pairing.deriveKey(pin, "a", "c")))
        assertFalse(Pairing.deriveKey(pin, "a", "b").contentEquals(Pairing.deriveKey(pin, "b", "c")))
    }

    @Test
    fun `wrong pin fails verification against a paired peer`() {
        val real = Pairing.deriveKey(Pairing.newPin(), "phone", "tv")
        val attacker = Pairing.deriveKey(Pairing.newPin(), "phone", "tv")
        val env = Pairing.sign(Envelope(t = "play.request", from = "phone", to = "tv"), real)

        assertFalse("attacker key must not verify", Pairing.verify(env.sigData(), env.sig, attacker))
        assertTrue("real key must verify", Pairing.verify(env.sigData(), env.sig, real))
    }

    @Test
    fun `a peer cannot reuse its key with a different device id`() {
        val key = Pairing.deriveKey(Pairing.newPin(), "phone", "tv")
        val other = Pairing.deriveKey(Pairing.newPin(), "phone", "attacker")
        val env = Pairing.sign(Envelope(t = "ping", from = "phone", to = "attacker"), key)
        assertFalse(Pairing.verify(env.sigData(), env.sig, other))
    }

    // ── Signing / verification ────────────────────────────────────

    @Test
    fun `signature round trips`() {
        val key = Pairing.deriveKey(Pairing.newPin(), "a", "b")
        val sig = Pairing.sign("hello world", key)
        assertTrue(Pairing.verify("hello world", sig, key))
        assertFalse(Pairing.verify("hello world!", sig, key))
    }

    @Test
    fun `tampered envelope fails verification`() {
        val key = Pairing.deriveKey(Pairing.newPin(), "phone", "tv")
        val honest = Pairing.sign(
            Envelope(t = "play.request", from = "phone", to = "tv", ts = 1,
                d = buildJsonObject { put("url", JsonPrimitive("http://x/a.m3u8")) }),
            key
        )
        val tampered = honest.copy(
            d = buildJsonObject { put("url", JsonPrimitive("http://evil/b.m3u8")) }
        )

        assertFalse(
            "swapping the payload must invalidate the signature",
            Pairing.verified(tampered, key)
        )
    }

    @Test
    fun `replaying to a different recipient fails`() {
        val key = Pairing.deriveKey(Pairing.newPin(), "phone", "tv")
        val signed = Pairing.sign(Envelope(t = "remote.cmd", from = "phone", to = "tv"), key)
        assertFalse(Pairing.verified(signed.copy(to = "someone-else"), key))
    }

    @Test
    fun `unsigned envelope is never treated as verified`() {
        val key = Pairing.deriveKey(Pairing.newPin(), "a", "b")
        assertFalse(Pairing.verified(Envelope(t = "ping"), key))
        assertFalse(Pairing.verified(Envelope(t = "ping", sig = ""), key))
    }

    @Test
    fun `garbage signature is rejected not thrown`() {
        val key = Pairing.deriveKey(Pairing.newPin(), "a", "b")
        assertFalse(Pairing.verified(Envelope(t = "ping", sig = "!!!not base64!!!"), key))
    }

    @Test
    fun `signature survives a wire round trip`() {
        val key = Pairing.deriveKey(Pairing.newPin(), "phone", "tv")
        val signed = Pairing.sign(
            Envelope(t = "state", from = "phone", to = "tv", id = "m1", ts = 99,
                d = buildJsonObject { put("pos", JsonPrimitive(1234)) }),
            key
        )

        assertTrue(
            "signature must verify after JSON transport",
            Pairing.verified(Envelope.decode(signed.encode())!!, key)
        )
    }

    @Test
    fun `signature is deterministic for identical envelopes`() {
        val key = Pairing.deriveKey(Pairing.newPin(), "a", "b")
        val env = Envelope(t = "ping", id = "same", from = "a", to = "b", ts = 5)
        assertEquals(Pairing.sign(env.sigData(), key), Pairing.sign(env.sigData(), key))
    }

    // ── Key encoding ──────────────────────────────────────────────

    @Test
    fun `key encoding round trips`() {
        val key = Pairing.randomKey()
        assertArrayEquals(key, Pairing.decodeKey(Pairing.encodeKey(key)))
    }

    @Test
    fun `decodeKey returns null on garbage rather than throwing`() {
        assertNull(Pairing.decodeKey("!!!"))
        assertNull(Pairing.decodeKey("%%%"))
    }

    @Test
    fun `randomKey has the documented length and differs each call`() {
        assertEquals(Pairing.KEY_LENGTH, Pairing.randomKey().size)
        assertNotEquals(Pairing.encodeKey(Pairing.randomKey()), Pairing.encodeKey(Pairing.randomKey()))
    }

    // ── Base64 codec ──────────────────────────────────────────────

    @Test
    fun `base64 codec round trips across all input lengths`() {
        for (n in 0..64) {
            val data = ByteArray(n) { (it * 7 + 3).toByte() }
            assertArrayEquals("failed at length $n", data, Base64Codec.decode(Base64Codec.encode(data)))
        }
    }

    @Test
    fun `base64 codec matches known vectors`() {
        assertEquals("", Base64Codec.encode(ByteArray(0)))
        assertEquals("TQ==", Base64Codec.encode("M".toByteArray()))
        assertEquals("TWE=", Base64Codec.encode("Ma".toByteArray()))
        assertEquals("TWFu", Base64Codec.encode("Man".toByteArray()))
        assertEquals("aGVsbG8gd29ybGQ=", Base64Codec.encode("hello world".toByteArray()))
    }

    @Test
    fun `base64 decode ignores newlines`() {
        val data = "hello world, this is a longer payload".toByteArray()
        val wrapped = Base64Codec.encode(data).chunked(4).joinToString("\n")
        assertArrayEquals(data, Base64Codec.decode(wrapped))
    }
}
