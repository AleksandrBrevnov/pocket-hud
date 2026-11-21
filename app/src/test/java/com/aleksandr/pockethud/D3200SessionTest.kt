package com.aleksandr.pockethud

import org.junit.Assert.*
import org.junit.Test
import java.security.interfaces.ECPublicKey

class D3200SessionTest {
    @Test fun onlyLiveEngineAcceptsSoundcore() {
        assertEquals(TranslationEngine.LIVE_WEBSOCKET,
            MicSource.SOUNDCORE_D3200.resolveEngine(TranslationEngine.CASCADED_REST))
    }

    @Test fun existingRecordingFallsBackAndNeverSendsStop() {
        val link = Link()
        link.handshake(recording = true)
        assertEquals("1b0e", link.lastCommand())
        link.reply(0x1b, 0x0e, byteArrayOf(-1))
        assertEquals("1a0e", link.lastCommand())
        val olderList = byteArrayOf(1, 0, 42, 0, 0, 0, 8, 0, 0, 0)
        link.reply(0x1a, 0x0e, olderList)
        assertEquals("1a07", link.lastCommand())
        assertEquals(42, link.sent.last()[13].toInt())
        val previousCount = link.sent.size
        link.session.stop()
        assertEquals(previousCount, link.sent.size)
        assertTrue(link.closed)
    }

    @Test fun ownRecordingUsesEventAndEndsWithStop() {
        val link = Link()
        link.handshake(recording = false)
        assertEquals(1, link.sent.last()[9].toInt())
        link.reply(0x1a, 6, byteArrayOf(42, 0, 0, 0))
        link.reply(0x18, 0x82)
        assertEquals("1a07", link.lastCommand())
        link.session.stop()
        assertEquals("1882", link.lastCommand())
        assertEquals(0, link.sent.last()[9].toInt())
        link.reply(0x18, 0x82, byteArrayOf(0))
        assertTrue(link.closed)
    }

    @Test fun stopDuringStartWaitsForAcknowledgement() {
        val link = Link()
        link.handshake(recording = false)
        val count = link.sent.size
        link.session.stop()
        assertEquals(count, link.sent.size)
        link.reply(0x18, 0x82)
        assertEquals(0, link.sent.last()[9].toInt())
    }

    @Test fun missingRecordingFlagCannotAuthorizeStart() {
        val link = Link()
        link.handshake(recording = null)
        assertThrows(IllegalStateException::class.java) { link.reply(1, 1, ByteArray(50)) }
        link.session.stop()
        assertEquals(listOf("2e01", "0101"), link.sent.map { it.copyOfRange(5, 7).hex() })
    }

    @Test fun realtimeRejectionCanStopOnlyOwnRecording() {
        val link = Link()
        link.handshake(recording = false)
        link.reply(0x18, 0x82)
        link.reply(0x1a, 6, byteArrayOf(42, 0, 0, 0))
        val error = assertThrows(IllegalStateException::class.java) { link.reply(0x1a, 7, byteArrayOf(-1)) }
        assertTrue(error.message!!.contains("realtime BLE"))
        link.session.stop()
        assertEquals(0, link.sent.last()[9].toInt())
    }

    private class Link {
        val sent = mutableListOf<ByteArray>()
        var closed = false
        val session = D3200Session({ sent.add(it) }, {}, { _, _ -> }, {}, { closed = true })

        fun lastCommand() = sent.last().copyOfRange(5, 7).hex()

        fun reply(type: Int, id: Int, payload: ByteArray = byteArrayOf()) {
            session.receive(D3200Protocol.Frame(1, type, id, payload))
        }

        fun handshake(recording: Boolean?) {
            session.begin()
            val local = sent.last().copyOfRange(9, 74)
            val peer = D3200Crypto.generate()
            reply(0x2e, 1, D3200Crypto.encode(peer.public as ECPublicKey) + D3200Crypto.shared(peer, local))
            if (recording != null) {
                val info = ByteArray(51)
                "03.56".toByteArray().copyInto(info, 3)
                info[50] = if (recording) 1 else 0
                reply(1, 1, info)
            }
        }
    }
}
