package com.aleksandr.pockethud

/**
 * Read-only structure checks for the official Soundcore App 5.0.39 capture
 * observed on D3200 firmware 03.56.
 *
 * No bound-device authorization or A7 request is sent by Pocket HUD: these
 * helpers only recognize recorded envelopes and responses. In particular,
 * knowledge of ciphertext and nonce boundaries does not give us the existing
 * persistent binding credential, and replaying a captured request is unsafe.
 *
 * The meaning of 1A/A7 remains unverified.
 */
internal object D3200OfficialCapture {
    data class AuthEnvelopeShape(val encryptedLength: Int, val nonceLength: Int)

    /** 0B/8B TX: [encrypted_len:u8][encrypted][nonce_len:u8][nonce]. */
    fun authenticationShape(payload: ByteArray): AuthEnvelopeShape? {
        if (payload.size < 2) return null
        val encryptedLength = payload[0].toInt() and 0xff
        val nonceLengthOffset = 1 + encryptedLength
        if (nonceLengthOffset >= payload.size) return null
        val nonceLength = payload[nonceLengthOffset].toInt() and 0xff
        if (nonceLengthOffset + 1 + nonceLength != payload.size) return null
        return AuthEnvelopeShape(encryptedLength, nonceLength)
    }

    /** The observed 03.56 official-app envelope has 44 encrypted + 16 nonce bytes. */
    fun isObserved0356AuthShape(payload: ByteArray): Boolean =
        authenticationShape(payload) == AuthEnvelopeShape(44, 16)

    /** In the observed 0B/8B response, success status=1 and result byte=00. */
    fun isSuccessfulAuthResult(frame: D3200Protocol.Frame): Boolean =
        frame.type == 0x0b && frame.id == 0x8b &&
            !frame.isRejected && frame.payload.contentEquals(byteArrayOf(0))

    /** The official app sent 1A/A7 with one byte 01 three times. */
    fun isObservedA7Request(payload: ByteArray): Boolean =
        payload.contentEquals(byteArrayOf(1))

    /** Each observed 1A/A7 notification was an empty success response. */
    fun isSuccessfulA7Response(frame: D3200Protocol.Frame): Boolean =
        frame.type == 0x1a && frame.id == 0xa7 &&
            !frame.isRejected && frame.payload.isEmpty()
}
