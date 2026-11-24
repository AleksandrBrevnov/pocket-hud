package com.aleksandr.pockethud

import java.security.SecureRandom

/**
 * Builds the 0B/8B possession-proof envelope described for D3200 firmware 03.66.
 *
 * The user's official Soundcore 5.0.39 capture on firmware 03.56 has the same
 * ciphertext/nonce lengths (44/16), but its plaintext/key semantics have NOT
 * yet been independently validated. This helper is deliberately not wired to
 * D3200Session: it must not transmit until the existing binding credential
 * has been legitimately provisioned and the proof checked on the device.
 *
 * Never log accountId, bindingKey, nonce or the resulting envelope.
 */
internal object D3200Authentication {
    private const val UID_BYTES = 40
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 16

    fun envelope(
        accountId: String,
        bindingKey: ByteArray,
        timestampSeconds: Long,
        nonce: ByteArray
    ): ByteArray {
        require(accountId.length == UID_BYTES && accountId.all { c ->
            c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
        }) { "D3200 account identifier must be 40 hexadecimal ASCII characters" }
        require(bindingKey.size == KEY_BYTES) { "D3200 binding-key length must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "D3200 authentication nonce must be 16 bytes" }
        require(timestampSeconds in 0..0xffff_ffffL) { "D3200 timestamp must fit uint32" }

        val plain = accountId.toByteArray(Charsets.US_ASCII) + byteArrayOf(
            timestampSeconds.toByte(),
            (timestampSeconds ushr 8).toByte(),
            (timestampSeconds ushr 16).toByte(),
            (timestampSeconds ushr 24).toByte()
        )
        try {
            // AES-CTR encryption/decryption are the same operation.
            val encrypted = D3200Crypto.ctr(bindingKey, nonce, plain)
            try {
                return byteArrayOf(encrypted.size.toByte()) + encrypted +
                    byteArrayOf(nonce.size.toByte()) + nonce
            } finally {
                encrypted.fill(0)
            }
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Builds a complete 0B/8B request frame for OFFLINE verification.
     * It is intentionally not called by the BLE transport/session.
     */
    fun requestFrame(
        accountId: String,
        bindingKey: ByteArray,
        timestampSeconds: Long,
        nonce: ByteArray
    ): ByteArray {
        val payload = envelope(accountId, bindingKey, timestampSeconds, nonce)
        try {
            val totalLength = 10 + payload.size
            check(totalLength == 72) { "Unexpected D3200 authentication frame length" }
            val header = byteArrayOf(
                0x08, 0xee.toByte(), 0, 0, 0,
                0x0b, 0x8b.toByte(),
                totalLength.toByte(), (totalLength ushr 8).toByte()
            )
            val bytesWithoutChecksum = header + payload
            val checksum = bytesWithoutChecksum.fold(0) { sum, value ->
                (sum + (value.toInt() and 0xff)) and 0xff
            }.toByte()
            return bytesWithoutChecksum + checksum
        } finally {
            payload.fill(0)
        }
    }

    /** Create one fresh random nonce per request, never replay a captured nonce. */
    fun requestFrameWithFreshNonce(
        accountId: String,
        bindingKey: ByteArray,
        timestampSeconds: Long
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        SecureRandom().nextBytes(nonce)
        return try {
            requestFrame(accountId, bindingKey, timestampSeconds, nonce)
        } finally {
            nonce.fill(0)
        }
    }

    fun envelopeWithFreshNonce(
        accountId: String,
        bindingKey: ByteArray,
        timestampSeconds: Long
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        SecureRandom().nextBytes(nonce)
        return try {
            envelope(accountId, bindingKey, timestampSeconds, nonce)
        } finally {
            nonce.fill(0)
        }
    }
}

/**
 * Ephemeral credentials supplied explicitly by an authorized local provider.
 *
 * The official Soundcore application's Android Keystore is not readable by
 * Pocket HUD. This class does not extract, log, store or provision keys.
 * Call close() after building a single proof to wipe its private key copy.
 */
internal class D3200AuthCredentials(
    val accountId: String,
    bindingKey: ByteArray
) : AutoCloseable {
    private val key = bindingKey.copyOf()
    private var closed = false

    fun createRequest(timestampSeconds: Long): ByteArray {
        check(!closed) { "D3200 auth credentials already closed" }
        return D3200Authentication.requestFrameWithFreshNonce(
            accountId, key, timestampSeconds
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        key.fill(0)
    }

    override fun toString(): String = "D3200AuthCredentials([redacted])"
}
