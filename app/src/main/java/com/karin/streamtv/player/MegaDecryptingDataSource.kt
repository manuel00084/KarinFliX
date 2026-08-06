package com.karin.streamtv.player

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * DataSource that streams a MEGA download URL and decrypts the AES-128-CTR
 * content on the fly (MEGA chunk scheme, continuous 128-bit big-endian counter).
 *
 * The network engine is injected via [upstream] so the same underlying
 * DataSource factory (e.g. Cronet) is reused across all downloads. This wrapper
 * only decrypts the bytes; it does not own the HTTP layer.
 */
@UnstableApi
class MegaDecryptingDataSource(
    private val key: ByteArray,
    private val ctrStart: Long,
    private val upstream: DataSource
) : DataSource {

    private val TAG = "MegaDataSource"

    private val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    private val keySpec = SecretKeySpec(key, "AES")

    private var currentBlock = -1L
    private var keystream = ByteArray(16)
    private var keystreamIdx = 0
    private var position = 0L

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val resolved = upstream.open(dataSpec)
        position = dataSpec.position
        currentBlock = -1
        keystreamIdx = (dataSpec.position % 16).toInt()
        Log.i(TAG, "open position=${dataSpec.position} length=$resolved")
        return resolved
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n <= 0) return n
        for (i in 0 until n) {
            val block = position / 16
            if (block != currentBlock) {
                currentBlock = block
                genKeystream(block)
            }
            val cipherByte = buffer[offset + i]
            buffer[offset + i] = (cipherByte.toInt() xor keystream[keystreamIdx].toInt()).toByte()
            keystreamIdx++
            if (keystreamIdx == 16) keystreamIdx = 0
            position++
        }
        return n
    }

    override fun getUri(): android.net.Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }

    private fun genKeystream(block: Long) {
        val counter = BigInteger.valueOf(ctrStart)
            .shiftLeft(64)
            .add(BigInteger.valueOf(block))
        val ivBytes = ByteArray(16)
        val cb = counter.toByteArray()
        val copyLen = minOf(cb.size, 16)
        System.arraycopy(cb, cb.size - copyLen, ivBytes, 16 - copyLen, copyLen)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivBytes))
        keystream = cipher.update(ByteArray(16))
        keystreamIdx = 0
    }
}
