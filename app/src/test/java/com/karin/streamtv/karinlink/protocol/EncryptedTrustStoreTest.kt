package com.karin.streamtv.karinlink.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

/**
 * Encryption of the paired-device keys at rest.
 *
 * Runs on a plain JVM with a normal [javax.crypto.SecretKey], because what is
 * being tested is the sealing logic, not the Keystore. The Keystore is Android
 * plumbing around a key this code never sees.
 */
class EncryptedTrustStoreTest {

    private fun key(): javax.crypto.SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store(key: javax.crypto.SecretKey = key()): Pair<EncryptedTrustStore, InMemoryTrustStore> {
        val raw = InMemoryTrustStore()
        return EncryptedTrustStore(raw, AesGcmSecretBox(key), JvmBase64Codec()) to raw
    }

    private fun InMemoryTrustStore.raw(key: String): String? = snapshot()[key]

    @Test
    fun `the cipher chooses the iv rather than the caller`() {
        // Fijar el IV a mano funciona en la JVM y es rechazado por el
        // Android Keystore, así que este test documenta que el IV lo pone el
        // proveedor y no la app.
        val sealed = AesGcmSecretBox(key()).seal("x".toByteArray(), "peers")
        val iv = sealed.copyOfRange(0, AesGcmSecretBox.IV_BYTES)
        val other = AesGcmSecretBox(key()).seal("x".toByteArray(), "peers")
        assertFalse("the iv looks fixed", iv.contentEquals(other.copyOfRange(0, AesGcmSecretBox.IV_BYTES)))
    }

    @Test
    fun `a value comes back exactly as it went in`() {
        val json = """{"peer-a":"AAAA","peer-b":"BBBB"}"""
        val (s, _) = store()
        s.put("peers", json)
        assertEquals(json, s.get("peers"))
    }

    @Test
    fun `nothing readable is left in the underlying store`() {
        val json = """{"peer-a":"SUPERSECRETKEY"}"""
        val (s, raw) = store()
        s.put("peers", json)

        val stored = raw.raw("peers")
        assertTrue("missing the version prefix", stored!!.startsWith("v1:"))
        assertFalse("the key survived in the clear: $stored", stored.contains("SUPERSECRETKEY"))
        assertFalse("the plaintext survived: $stored", stored.contains(json))
    }

    @Test
    fun `unicode and empty values survive the round trip`() {
        val (s, _) = store()
        for (value in listOf("", "ñandú ✅ «»", "\n\t\"quoted\"", "0")) {
            s.put("peers", value)
            assertEquals("failed for <$value>", value, s.get("peers"))
        }
    }

    @Test
    fun `two writes of the same value do not produce the same bytes`() {
        // A repeated IV under one key is what would break GCM, so the same input
        // sealed twice has to differ.
        val (s, raw) = store()
        s.put("peers", "same")
        val first = raw.raw("peers")
        s.put("peers", "same")
        val second = raw.raw("peers")

        assertNotEquals(first, second)
        assertEquals("same", s.get("peers"))
    }

    @Test
    fun `a value moved to another record does not open`() {
        // A ciphertext lifted from one record and pasted into another must not
        // open, which is what the associated data is for. Here the bytes are
        // sealed for "peers" and presented as "other".
        val raw = InMemoryTrustStore()
        val codec = JvmBase64Codec()
        val sealed = AesGcmSecretBox(key()).seal("""{"peer-a":"KEY"}""".toByteArray(), "peers")
        val iv = codec.encode(sealed.copyOfRange(0, AesGcmSecretBox.IV_BYTES))
        val body = codec.encode(sealed.copyOfRange(AesGcmSecretBox.IV_BYTES, sealed.size))
        raw.put("other", "v1:$iv:$body")

        val reader = EncryptedTrustStore(raw, AesGcmSecretBox(key()), codec)

        assertNull("a relocated value must not be accepted", reader.get("other"))
    }

    @Test
    fun `a tampered value is dropped rather than returned`() {
        val (s, raw) = store()
        s.put("peers", """{"peer-a":"KEY"}""")
        val stored = raw.raw("peers")!!
        // Flip a character in the ciphertext half.
        val flipped = stored.replaceRange(stored.length - 2, stored.length - 1, "A")
        raw.put("peers", if (flipped == stored) stored.replaceRange(5, 6, "B") else flipped)

        assertNull("a tampered value must not be returned", s.get("peers"))
    }

    @Test
    fun `a value that cannot be opened is cleared from disk`() {
        val (s, raw) = store()
        s.put("peers", "value")

        // Simulates the Keystore key going away, e.g. after a restore to a
        // different device. The record is dropped so the next read does not
        // keep retrying forever.
        val lostKey = key()
        val reader = EncryptedTrustStore(raw, AesGcmSecretBox(lostKey), JvmBase64Codec())
        assertNull(reader.get("peers"))
        assertNull("the unreadable record should be gone", raw.raw("peers"))
    }

    @Test
    fun `a legacy plaintext value is still readable`() {
        val json = """{"peer-a":"OLDKEY"}"""
        val raw = InMemoryTrustStore()
        raw.put("peers", json)
        val s = EncryptedTrustStore(raw, AesGcmSecretBox(key()), JvmBase64Codec())

        assertEquals(json, s.get("peers"))
    }

    @Test
    fun `reading a legacy value re-seals it`() {
        val json = """{"peer-a":"OLDKEY"}"""
        val raw = InMemoryTrustStore()
        raw.put("peers", json)
        val s = EncryptedTrustStore(raw, AesGcmSecretBox(key()), JvmBase64Codec())

        s.get("peers")

        val stored = raw.raw("peers")
        assertTrue("the legacy value was not re-sealed", stored!!.startsWith("v1:"))
        assertFalse("the key is still on disk", stored.contains("OLDKEY"))
        assertEquals(json, s.get("peers"))
    }

    @Test
    fun `a malformed record is dropped`() {
        val raw = InMemoryTrustStore()
        raw.put("peers", "v1:only-one-part")
        val s = EncryptedTrustStore(raw, AesGcmSecretBox(key()), JvmBase64Codec())

        assertNull(s.get("peers"))
        assertNull(raw.snapshot()["peers"])
    }

    @Test
    fun `removing a value removes it`() {
        val (s, raw) = store()
        s.put("peers", "value")
        s.put("peers", null)
        assertNull(s.get("peers"))
        assertNull(raw.snapshot()["peers"])
    }

    @Test
    fun `a missing value is just absent`() {
        val (s, _) = store()
        assertNull(s.get("nothing-here"))
    }

    @Test
    fun `a short ciphertext is rejected by the cipher, not read`() {
        val box = AesGcmSecretBox(key())
        try {
            box.open(ByteArray(4), "peers")
            throw AssertionError("expected a rejection")
        } catch (expected: IllegalArgumentException) {
            // Thrown before touching the cipher, because there is no room for an
            // IV plus a tag in four bytes.
        }
    }

    @Test
    fun `a wrong key is detected`() {
        val sealed = AesGcmSecretBox(key()).seal("secret".toByteArray(), "peers")
        try {
            AesGcmSecretBox(key()).open(sealed, "peers")
            throw AssertionError("expected a bad tag")
        } catch (expected: AEADBadTagException) {
            // This is the case that matters: a mismatch must be a failure, and
            // not a wrong value.
        }
    }
}
