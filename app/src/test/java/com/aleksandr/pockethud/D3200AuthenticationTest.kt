package com.aleksandr.pockethud

import org.junit.Assert.*
import org.junit.Test

class D3200AuthenticationTest {
    private val syntheticUid = "0123456789abcdef".repeat(2) + "01234567"
    private val syntheticKey = ByteArray(32) { it.toByte() }
    private val syntheticNonce = ByteArray(16) { (it + 0x40).toByte() }

    @Test fun buildsObserved62ByteEnvelopeAndRoundTripsSyntheticPlaintext() {
        val time = 0x1234_5678L
        val payload = D3200Authentication.envelope(syntheticUid, syntheticKey, time, syntheticNonce)
        assertEquals(62, payload.size)
        assertEquals(44, payload[0].toInt() and 0xff)
        assertEquals(16, payload[45].toInt() and 0xff)
        assertArrayEquals(syntheticNonce, payload.copyOfRange(46, 62))
        assertTrue(D3200OfficialCapture.isObserved0356AuthShape(payload))

        // Offline deterministic test only; no actual account ID or binding key.
        val decrypted = D3200Crypto.ctr(syntheticKey, syntheticNonce, payload.copyOfRange(1, 45))
        assertArrayEquals(syntheticUid.toByteArray(Charsets.US_ASCII), decrypted.copyOfRange(0, 40))
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), decrypted.copyOfRange(40, 44))
    }

    @Test fun rejectsWrongIdentifierAndKeyLengths() {
        val validUid = syntheticUid
        assertThrows(IllegalArgumentException::class.java) {
            D3200Authentication.envelope("A".repeat(19), syntheticKey, 1, syntheticNonce)
        }
        assertThrows(IllegalArgumentException::class.java) {
            D3200Authentication.envelope("g".repeat(40), syntheticKey, 1, syntheticNonce)
        }
        assertThrows(IllegalArgumentException::class.java) {
            D3200Authentication.envelope(validUid, ByteArray(16), 1, syntheticNonce)
        }
        assertThrows(IllegalArgumentException::class.java) {
            D3200Authentication.envelope(validUid, syntheticKey, 1, ByteArray(12))
        }
    }

    @Test fun rejectsTimestampOutsideUnsignedInt32() {
        assertThrows(IllegalArgumentException::class.java) {
            D3200Authentication.envelope(syntheticUid, syntheticKey, -1, syntheticNonce)
        }
        assertThrows(IllegalArgumentException::class.java) {
            D3200Authentication.envelope(syntheticUid, syntheticKey, 0x1_0000_0000L, syntheticNonce)
        }
    }

    @Test fun generatedNonceVariesAndDoesNotAffectCallerOwnedKey() {
        val initialKey = syntheticKey.clone()
        val first = D3200Authentication.envelopeWithFreshNonce(syntheticUid, syntheticKey, 2)
        val second = D3200Authentication.envelopeWithFreshNonce(syntheticUid, syntheticKey, 2)
        assertEquals(62, first.size)
        assertEquals(62, second.size)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(initialKey, syntheticKey)
    }
}
