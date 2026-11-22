package com.aleksandr.pockethud

import org.junit.Assert.*
import org.junit.Test

class D3200OfficialCaptureTest {
    @Test fun recognizesTheCapturedAuthEnvelopeWithoutReplayingIt() {
        // Synthetic only: these bytes are not from the user or the vendor app.
        val fakeCiphertext = ByteArray(44) { it.toByte() }
        val fakeNonce = ByteArray(16) { (it + 50).toByte() }
        val envelope = byteArrayOf(44) + fakeCiphertext + byteArrayOf(16) + fakeNonce

        assertEquals(D3200OfficialCapture.AuthEnvelopeShape(44, 16),
            D3200OfficialCapture.authenticationShape(envelope))
        assertTrue(D3200OfficialCapture.isObserved0356AuthShape(envelope))
        assertFalse(D3200OfficialCapture.isObserved0356AuthShape(
            byteArrayOf(23) + ByteArray(23) + byteArrayOf(16) + fakeNonce))
        assertNull(D3200OfficialCapture.authenticationShape(byteArrayOf(44, 1, 2)))
        assertNull(D3200OfficialCapture.authenticationShape(envelope + byteArrayOf(7)))
    }

    @Test fun requiresCorrectAuthResponseAndAcknowledgedA7() {
        val okAuth = D3200Protocol.Frame(1, 0x0b, 0x8b, byteArrayOf(0))
        assertTrue(D3200OfficialCapture.isSuccessfulAuthResult(okAuth))
        assertFalse(D3200OfficialCapture.isSuccessfulAuthResult(
            D3200Protocol.Frame(1, 0x0b, 0x8b, byteArrayOf(-1))))
        assertFalse(D3200OfficialCapture.isSuccessfulAuthResult(
            D3200Protocol.Frame(0, 0x0b, 0x8b, byteArrayOf(0))))
        assertFalse(D3200OfficialCapture.isSuccessfulAuthResult(
            D3200Protocol.Frame(1, 0x0b, 0x87, byteArrayOf(0))))

        assertTrue(D3200OfficialCapture.isObservedA7Request(byteArrayOf(1)))
        assertFalse(D3200OfficialCapture.isObservedA7Request(byteArrayOf(0)))
        assertFalse(D3200OfficialCapture.isObservedA7Request(byteArrayOf(1, 1)))
        assertTrue(D3200OfficialCapture.isSuccessfulA7Response(
            D3200Protocol.Frame(1, 0x1a, 0xa7, byteArrayOf())))
        assertFalse(D3200OfficialCapture.isSuccessfulA7Response(
            D3200Protocol.Frame(1, 0x1a, 0xa7, byteArrayOf(-1))))
    }

    @Test fun observationOnlyNoBindingCommandsAreAddedToOutboundAllowlist() {
        assertFalse(D3200Protocol.Command.entries.any {
            it.type == 0x0b || (it.type == 0x1a && it.id == 0xa7)
        })
        // This is important: the official app sent 1A/10 after transferring
        // its file, but Pocket HUD must never copy that destructive cleanup.
        assertFalse(D3200Protocol.Command.entries.any {
            it.type == 0x1a && it.id == 0x10
        })
    }
}
