package com.karin.streamtv.karinlink.protocol

import android.content.Context
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Sealing of values at rest.
 *
 * Split out from [EncryptedTrustStore] so the parts that decide security are
 * testable on a plain JVM. The key lives in the Android Keystore, which is not
 * available off-device, but "seal, then open, then notice a tamper" is exactly
 * the logic worth testing and it does not need a device to run.
 */
interface SecretBox {
    /** Returns ciphertext with everything needed to open it. */
    fun seal(plain: ByteArray, aad: String): ByteArray

    /**
     * @throws javax.crypto.AEADBadTagException if the ciphertext was altered,
     *   truncated, or sealed under a different key or [aad].
     */
    fun open(sealed: ByteArray, aad: String): ByteArray
}

/**
 * AES-GCM with a fresh IV per value and the record name as associated data.
 *
 * The IV is prepended rather than derived, because reusing an IV under one key
 * is the one mistake that quietly breaks GCM. The associated data ties a
 * ciphertext to the name it was stored under, so a value cannot be moved from
 * one record to another to have it decrypted as something else.
 */
class AesGcmSecretBox(private val key: SecretKey) : SecretBox {

    override fun seal(plain: ByteArray, aad: String): ByteArray {
        // El IV lo elige el proveedor, nunca nosotros. Una clave del Android
        // Keystore rechaza un IV aportado por el llamante ("Caller-provided IV
        // not permitted"), y en una JVM normal lo acepta: fijarlo aquí pasaría
        // los tests y fallaría en el dispositivo.
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad.toByteArray(Charsets.UTF_8))
        }
        val iv = cipher.iv
        require(iv != null && iv.isNotEmpty()) { "the cipher did not produce an IV" }
        require(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray, aad: String): ByteArray {
        require(sealed.size > IV_BYTES) { "sealed value is too short to contain an IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES)
            )
            updateAAD(aad.toByteArray(Charsets.UTF_8))
        }
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * The production key: a non-exportable AES-256 key inside the Android Keystore.
 *
 * A key that never enters the app's address space cannot be read out of a
 * prefs backup or a memory dump, which is the whole point of using the
 * Keystore instead of hiding a hardcoded key somewhere less obvious.
 */
object KeystoreSecretKey {

    private const val TAG = "KarinLinkKeys"
    private const val ALIAS = "karin_link_trust_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    fun get(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
        Log.i(TAG, "Created trust key $ALIAS")
        return key
    }

    /** True when a key exists, so callers can tell "first run" from "re-created". */
    fun exists(): Boolean =
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(ALIAS)
        }.getOrDefault(false)
}

/**
 * Text encoding for the sealed bytes.
 *
 * Injected because `android.util.Base64` is not available in unit tests, and
 * `java.util.Base64` does not exist on the older devices this app still
 * supports.
 */
interface TextCodec {
    fun encode(bytes: ByteArray): String
    fun decode(text: String): ByteArray
}

class AndroidBase64Codec : TextCodec {
    override fun encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    override fun decode(text: String): ByteArray =
        android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
}

class JvmBase64Codec : TextCodec {
    override fun encode(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    override fun decode(text: String): ByteArray =
        java.util.Base64.getDecoder().decode(text)
}

/**
 * A [TrustStore] that leaves nothing readable in the prefs file.
 *
 * Values written by earlier versions were plain JSON. Those are still readable,
 * and are re-sealed the first time they are read, so upgrading does not drop
 * pairings. The prefix is what tells the two apart: every sealed value starts
 * with [PREFIX], and stored values are always JSON objects, which no plaintext
 * value can look like.
 */
class EncryptedTrustStore(
    private val delegate: TrustStore,
    private val box: SecretBox,
    private val codec: TextCodec = AndroidBase64Codec()
) : TrustStore {

    override fun get(key: String): String? {
        val stored = delegate.get(key) ?: return null
        if (!stored.startsWith(PREFIX)) {
            // Written before encryption existed. Readable, and worth re-sealing
            // so the plaintext does not sit on disk for another app update.
            Log.i(TAG, "Re-sealing legacy value for $key")
            put(key, stored)
            return stored
        }

        val body = stored.removePrefix(PREFIX)
        val separator = body.indexOf(SEPARATOR)
        if (separator <= 0) {
            Log.w(TAG, "Malformed sealed value for $key, dropping it")
            delegate.put(key, null)
            return null
        }

        return try {
            val sealed = codec.decode(body.substring(0, separator) + body.substring(separator + 1))
            String(box.open(sealed, key), Charsets.UTF_8)
        } catch (e: Exception) {
            // A tag mismatch means the ciphertext, the key, or the record name
            // changed. The honest answer is "this pairing is unknown", which
            // makes the user pair again, rather than a silent wrong value.
            Log.w(TAG, "Could not open $key (${e.javaClass.simpleName}); dropping it")
            delegate.put(key, null)
            null
        }
    }

    override fun put(key: String, value: String?) {
        if (value == null) {
            delegate.put(key, null)
            return
        }
        val sealed = box.seal(value.toByteArray(Charsets.UTF_8), key)
        val text = PREFIX + codec.encode(sealed.copyOfRange(0, AesGcmSecretBox.IV_BYTES)) +
            SEPARATOR + codec.encode(sealed.copyOfRange(AesGcmSecretBox.IV_BYTES, sealed.size))
        delegate.put(key, text)
    }

    companion object {
        private const val TAG = "KarinLinkKeys"
        private const val PREFIX = "v1:"
        private const val SEPARATOR = ":"
    }
}

/**
 * The trust store the app actually uses.
 *
 * If the Keystore cannot be reached, this deliberately keeps the old plaintext
 * behaviour and says so out loud, rather than quietly swapping in a key of its
 * own that would look like encryption while offering none of it. A device that
 * cannot make a hardware key should still be able to use the app, and a warning
 * in the log is the honest version of that.
 */
fun encryptedTrustStore(context: Context): TrustStore {
    val plain = PrefsTrustStore(context)
    val box = runCatching { AesGcmSecretBox(KeystoreSecretKey.get()) }.getOrNull()
    if (box == null) {
        Log.w("KarinLinkKeys", "Keystore unavailable; paired-device keys are stored unencrypted")
        return plain
    }
    return EncryptedTrustStore(plain, box, AndroidBase64Codec())
}
