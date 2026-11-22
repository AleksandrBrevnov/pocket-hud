package com.aleksandr.pockethud

import org.junit.Assert.*
import org.junit.Test
import java.security.interfaces.ECPublicKey

class D3200SessionTest {
    @Test fun onlyLiveEngineAcceptsSoundcore() {
        assertEquals(TranslationEngine.LIVE_WEBSOCKET,
            MicSource.SOUNDCORE_D3200.resolveEngine(TranslationEngine.CASCADED_REST))
        for (source in listOf(MicSource.SMARTPHONE, MicSource.BLUETOOTH)) {
            for (engine in TranslationEngine.entries) assertEquals(engine, source.resolveEngine(engine))
        }
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

    @Test fun realtimeRejectionWaitsForStopThenRequestsCompletedFile() {
        val link = Link()
        link.handshake(recording = false)
        link.reply(0x18, 0x82)
        link.reply(0x1a, 6, byteArrayOf(42, 0, 0, 0))
        link.reply(0x1a, 7, byteArrayOf(-1))

        assertFalse(link.closed)
        assertEquals("1a07", link.lastCommand())
        link.reply(0x18, 0x82, byteArrayOf(0))

        assertFalse(link.closed)
        assertEquals("1a07", link.lastCommand()) // Still the rejected realtime request
        assertEquals(1, link.sent.last()[17].toInt())
        link.postStopLists()
        assertEquals("completed-file request must omit realtime flag", 18, D3200Protocol.u16(link.sent.last(), 7))
        assertFalse(link.sent.any { it.copyOfRange(5, 7).hex() in listOf("0b87", "0b8b", "0b8c") })
    }

    @Test fun completedFileProbeDecryptsOpusAndFinishesWithoutBindingCommands() {
        val link = Link()
        link.handshake(recording = false)
        link.reply(0x18, 0x82)
        link.reply(0x1a, 6, byteArrayOf(42, 0, 0, 0))
        link.reply(0x1a, 7, byteArrayOf(-1))
        link.reply(0x18, 0x82, byteArrayOf(0))
        link.postStopLists()

        val fileKey = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(16) { (it + 3).toByte() }
        link.header(42, fileKey, nonce, fileSize = 166)
        val opus = byteArrayOf(0xbc.toByte(), 12, 34).copyOf(160)
        link.audio(fileKey, nonce, 0, opus.copyOfRange(0, 3))
        link.reply(0x1a, 0x0a, link.complete(42))

        assertEquals(1, link.opus.size)
        assertArrayEquals(opus, link.opus.single())
        assertTrue(link.closed)
        assertFalse(link.sent.any { it.copyOfRange(5, 7).hex() in listOf("0b87", "0b8b", "0b8c") })
    }

    @Test fun completedFileRefusalRetriesOnceAfterASecondPause() {
        val link = Link()
        link.handshake(recording = false)
        link.reply(0x18, 0x82)
        link.reply(0x1a, 6, byteArrayOf(42, 0, 0, 0))
        link.reply(0x1a, 7, byteArrayOf(-1))
        link.reply(0x18, 0x82, byteArrayOf(0))
        link.postStopLists()
        val count = link.sent.size

        link.reply(0x1a, 7, byteArrayOf(-1))
        assertFalse(link.closed)
        assertEquals(count, link.sent.size)
        link.session.timeout()
        assertEquals(count + 1, link.sent.size)
        assertEquals("1a07", link.lastCommand())
        assertEquals(18, D3200Protocol.u16(link.sent.last(), 7))

        val error = assertThrows(IllegalStateException::class.java) {
            link.reply(0x1a, 7, byteArrayOf(-1))
        }
        assertTrue(error.message.orEmpty().contains("offlineAttempts=2"))
        assertEquals(count + 1, link.sent.size)
        link.session.stop()
        assertTrue(link.closed)
    }

    @Test fun postStopListTimeoutsStillReachReadOnlyFileProbe() {
        val link = Link()
        link.handshake(recording = false)
        link.reply(0x18, 0x82)
        link.reply(0x1a, 6, byteArrayOf(42, 0, 0, 0))
        link.reply(0x1a, 7, byteArrayOf(-1))
        link.reply(0x18, 0x82, byteArrayOf(0))
        link.session.timeout()
        assertEquals("1b0e", link.lastCommand())
        link.session.timeout()
        assertEquals("1a0e", link.lastCommand())
        link.session.timeout()
        assertEquals("1a07", link.lastCommand())
        assertEquals(18, D3200Protocol.u16(link.sent.last(), 7))
        assertFalse(link.closed)
    }

    @Test fun decryptsLiveAudioAndSuppressesRepeatedSlices() {
        val link = Link()
        link.handshake(recording = false)
        link.reply(0x18, 0x82)
        link.reply(0x1a, 6, byteArrayOf(42, 0, 0, 0))
        val fileKey = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(16) { (it + 3).toByte() }
        val sessionNonce = ByteArray(16) { (it + 5).toByte() }
        val wrapped = D3200Crypto.ctr(link.sessionKey, sessionNonce, "soundcored3200".toByteArray() + fileKey)
        val head = byteArrayOf(42, 0, 0, 0, -1, -1, -1, -1) + nonce + wrapped + sessionNonce + byteArrayOf(0)
        link.reply(0x1a, 7, head)
        val opus = byteArrayOf(0xbc.toByte(), 12, 34).copyOf(160)
        val encrypted = D3200Crypto.ctr(fileKey, D3200Crypto.counter(nonce, 5), opus)
        val slice = byteArrayOf(5, 0, 0, 0, 1) + encrypted + byteArrayOf(0)
        link.reply(0x1a, 8, slice + slice)
        assertEquals(1, link.opus.size)
        assertArrayEquals(opus, link.opus.single())
    }

    @Test fun recordingEventWithNonstandardStatusCanBeVerifiedAndOpened() {
        val link = Link()
        link.handshake(recording = false)
        link.reply(0x18, 0x82, byteArrayOf(1), status = 2)
        link.reply(0x1a, 6, byteArrayOf(42, 0, 0, 0), status = 2)
        assertEquals("0101", link.lastCommand())
        link.info(true)
        assertEquals("1a07", link.lastCommand())
        link.session.stop()
        assertTrue(link.closed)
    }

    @Test fun stoppedAndPausedNotificationsDoNotConfirmStart() {
        for (state in listOf(0, 2)) {
            val link = Link()
            link.handshake(recording = false)
            link.reply(0x18, 0x82, byteArrayOf(state.toByte()))
            link.session.stop()
            assertFalse(link.sent.any { it.copyOfRange(5, 7).hex() == "1882" && it[9] == 0.toByte() })
        }
    }

    @Test fun explicitStartRefusalTriggersStateProbeWithoutClaimingOwnership() {
        val link = Link()
        link.handshake(recording = false)
        link.reply(0x18, 0x82, byteArrayOf(-1))
        assertEquals("0101", link.lastCommand())
        link.info(false)
        assertFalse(link.closed)
        link.reply(0x1a, 6, byteArrayOf(42, 0, 0, 0))
        assertEquals("1a07", link.lastCommand())
        assertEquals(42, link.sent.last()[13].toInt())
        val commandCount = link.sent.size
        link.session.stop()
        assertEquals(commandCount, link.sent.size)
        assertTrue(link.closed)
    }

    @Test fun missingListsWaitForNewManualRecordingEvent() {
        val link = Link()
        link.handshake(recording = true)
        link.reply(0x1b, 0x0e, byteArrayOf(-1))
        link.reply(0x1a, 0x0e, byteArrayOf(0))
        assertFalse(link.closed)
        link.reply(0x18, 0x82, byteArrayOf(0))
        assertFalse(link.closed)
        link.reply(0x1a, 6, byteArrayOf(51, 0, 0, 0))
        assertEquals("1a07", link.lastCommand())
        assertEquals(51, link.sent.last()[13].toInt())
        assertFalse(link.sent.any { it.copyOfRange(5, 7).hex() == "1882" })
    }

    @Test fun nonstandardEmptyAckRequiresDeviceInfoBeforeStreaming() {
        val link = Link()
        link.handshake(recording = false)
        link.reply(0x18, 0x82, status = 3)
        link.reply(0x1a, 6, byteArrayOf(42, 0, 0, 0))
        link.session.timeout()
        assertEquals("0101", link.lastCommand())
        link.info(true)
        assertEquals("1a07", link.lastCommand())
        link.session.stop()
        assertTrue(link.closed)
    }

    @Test fun lostBleLinkClosesOnceWithoutIssuingStop() {
        val link = Link()
        link.handshake(recording = false)
        link.reply(0x18, 0x82, byteArrayOf(1))
        link.reply(0x1a, 6, byteArrayOf(42, 0, 0, 0))
        val count = link.sent.size
        link.session.disconnected()
        link.session.disconnected()
        link.session.stop()
        assertEquals(count, link.sent.size)
        assertEquals(1, link.closeCount)
    }


    @Test fun manualRecordingFollowsRealtimeFileRotationAndResetsSequence() {
        val link = Link()
        link.handshake(recording = true)
        link.reply(0x1b, 0x0e, byteArrayOf(-1))
        link.reply(0x1a, 0x0e, byteArrayOf(1, 0, 42, 0, 0, 0, 8, 0, 0, 0))
        assertEquals("1a07", link.lastCommand())

        val keyA = ByteArray(32) { (it + 1).toByte() }
        val nonceA = ByteArray(16) { (it + 3).toByte() }
        val opusA = byteArrayOf(0xbc.toByte(), 12, 34)
        link.header(42, keyA, nonceA)
        link.audio(keyA, nonceA, 5, opusA)
        assertEquals(1, link.opus.size)

        link.reply(0x1a, 0x06, link.rotation(43))
        assertEquals("1a07", link.lastCommand())
        assertEquals(43L, D3200Protocol.u32(link.sent.last(), 13))

        // The previous file's completion must not close the new file request.
        link.reply(0x1a, 0x0a, link.complete(42))
        val keyB = ByteArray(32) { (it + 41).toByte() }
        val nonceB = ByteArray(16) { (it + 61).toByte() }
        val opusB = byteArrayOf(0x91.toByte(), 23, 45)
        link.header(43, keyB, nonceB)
        link.audio(keyB, nonceB, 0, opusB)

        assertEquals(2, link.opus.size)
        assertArrayEquals(ByteArray(160).apply { opusA.copyInto(this) }, link.opus[0])
        assertArrayEquals(ByteArray(160).apply { opusB.copyInto(this) }, link.opus[1])
        assertFalse(link.closed)
    }

    @Test fun currentFileCompletionWaitsForNextRotationWithoutClosingGatt() {
        val link = Link()
        link.handshake(recording = true)
        link.reply(0x1b, 0x0e, byteArrayOf(-1))
        link.reply(0x1a, 0x0e, byteArrayOf(1, 0, 42, 0, 0, 0, 8, 0, 0, 0))

        val key = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(16) { (it + 2).toByte() }
        link.header(42, key, nonce)
        link.audio(key, nonce, 0, byteArrayOf(0xbc.toByte(), 1, 2))
        link.reply(0x1a, 0x0a, link.complete(42))

        assertFalse(link.closed)
        link.session.timeout()
        assertFalse(link.closed)

        link.reply(0x1a, 0x06, link.rotation(43))
        assertEquals("1a07", link.lastCommand())
        assertEquals(43L, D3200Protocol.u32(link.sent.last(), 13))
    }

    @Test fun rotationArrivingDuringHeaderRequestIsQueuedAndOpenedNext() {
        val link = Link()
        link.handshake(recording = false)
        link.reply(0x18, 0x82)
        link.reply(0x1a, 0x06, link.rotation(42))
        assertEquals(42L, D3200Protocol.u32(link.sent.last(), 13))

        link.reply(0x1a, 0x06, link.rotation(43))
        val keyA = ByteArray(32) { (it + 1).toByte() }
        val nonceA = ByteArray(16) { (it + 2).toByte() }
        link.header(42, keyA, nonceA)

        assertEquals("1a07", link.lastCommand())
        assertEquals(43L, D3200Protocol.u32(link.sent.last(), 13))
        val keyB = ByteArray(32) { (it + 31).toByte() }
        val nonceB = ByteArray(16) { (it + 51).toByte() }
        link.header(43, keyB, nonceB)
        assertFalse(link.closed)
    }

    private class Link {
        val sent = mutableListOf<ByteArray>()
        val opus = mutableListOf<ByteArray>()
        lateinit var sessionKey: ByteArray
        var closeCount = 0
        val closed get() = closeCount != 0
        val session = D3200Session({ sent.add(it) }, {}, { _, _ -> }, { opus.add(it) }, { closeCount++ })

        fun lastCommand() = sent.last().copyOfRange(5, 7).hex()

        fun postStopLists() {
            // WAIT_FILE_FLUSH: the device may still be committing the file.
            session.timeout()
            assertEquals("1b0e", lastCommand())
            reply(0x1b, 0x0e, byteArrayOf(-1))
            assertEquals("1a0e", lastCommand())
            reply(0x1a, 0x0e, byteArrayOf(-1))
            assertEquals("1a07", lastCommand())
            assertEquals(18, D3200Protocol.u16(sent.last(), 7))
        }

        fun rotation(fileId: Int): ByteArray = byteArrayOf(
            fileId.toByte(), 0, 0, 0, 0xE8.toByte(), 0x03, 0, 0, 1
        )

        fun complete(fileId: Int): ByteArray = byteArrayOf(
            0, 0xE8.toByte(), 0x03, 0, 0, fileId.toByte(), 0, 0, 0
        )

        fun header(fileId: Int, fileKey: ByteArray, nonce: ByteArray, fileSize: Long = 0xffffffffL) {
            val sessionNonce = ByteArray(16) { (it + 5).toByte() }
            val wrapped = D3200Crypto.ctr(
                sessionKey, sessionNonce, "soundcored3200".toByteArray() + fileKey
            )
            val id = ByteArray(4) { (fileId ushr (it * 8)).toByte() }
            val size = ByteArray(4) { (fileSize shr (it * 8)).toByte() }
            val payload = id + size + nonce + wrapped + sessionNonce + byteArrayOf(0)
            reply(0x1a, 0x07, payload)
        }

        fun audio(fileKey: ByteArray, nonce: ByteArray, sequence: Long, opusPrefix: ByteArray) {
            val packet = ByteArray(160)
            opusPrefix.copyInto(packet)
            val encrypted = D3200Crypto.ctr(
                fileKey, D3200Crypto.counter(nonce, sequence), packet
            )
            val seq = ByteArray(4) { (sequence shr (it * 8)).toByte() }
            reply(0x1a, 0x08, seq + byteArrayOf(1) + encrypted + byteArrayOf(0))
        }

        fun reply(type: Int, id: Int, payload: ByteArray = byteArrayOf(), status: Int = 1) {
            session.receive(D3200Protocol.Frame(status, type, id, payload))
        }

        fun info(recording: Boolean) {
            val info = ByteArray(51)
            "03.56".toByteArray().copyInto(info, 3)
            info[50] = if (recording) 1 else 0
            reply(1, 1, info)
        }

        fun handshake(recording: Boolean?) {
            session.begin()
            val local = sent.last().copyOfRange(9, 74)
            val peer = D3200Crypto.generate()
            val shared = D3200Crypto.shared(peer, local)
            val context = byteArrayOf(1, 2, 3)
            sessionKey = D3200Crypto.hkdf(shared, context, context)
            reply(0x2e, 1, D3200Crypto.encode(peer.public as ECPublicKey) + shared)
            if (recording != null) info(recording)
        }
    }
}
