package com.aleksandr.pockethud

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LiveAudioPipelineTest {
    @Test fun delayedSetupAndStaleReadyCannotSendAudioToNewSocket() {
        val gate = LiveSocketGate<String>()
        val old = gate.begin()
        assertTrue(gate.bind(old, "old"))
        assertTrue(gate.markReady(old, "old"))
        val sent = mutableListOf<String>()
        assertTrue(gate.send { sent.add(it); true })
        val next = gate.begin()
        assertTrue(gate.bind(next, "new"))
        assertFalse(gate.markReady(old, "old"))
        assertFalse(gate.send { sent.add(it); true })
        assertTrue(gate.markReady(next, "new"))
        assertTrue(gate.send { sent.add(it); true })
        assertEquals(listOf("old", "new"), sent)
        gate.pause()
        assertFalse(gate.send { true })
    }

    @Test fun staleCloseCannotClearNewReadyConnection() {
        val gate = LiveSocketGate<String>()
        val old = gate.begin()
        gate.bind(old, "old")
        val next = gate.begin()
        gate.bind(next, "new")
        gate.markReady(next, "new")
        assertFalse(gate.isCurrent(old, "old"))
        assertTrue(gate.send { it == "new" })
        assertFalse(gate.send { false })
    }

    @Test fun archiveCopiesInputDrainsAndRotatesValidWavFiles() {
        val dir = Files.createTempDirectory("pcm-test").toFile()
        try {
            val archive = PcmArchive(dir, "session", maxDataBytes = 8)
            val pcm = byteArrayOf(1, 2, 3, 4)
            assertTrue(archive.offer(pcm))
            pcm[0] = 99
            assertTrue(archive.offer(byteArrayOf(5, 6, 7, 8)))
            assertTrue(archive.offer(byteArrayOf(9, 10)))
            assertTrue(archive.close())
            assertFalse(archive.offer(byteArrayOf(0, 0)))
            assertNull(archive.failure)
            val files = dir.listFiles()!!.filter { it.extension == "wav" }.sortedBy { it.name }
            assertEquals(2, files.size)
            assertWav(files[0], byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
            assertWav(files[1], byteArrayOf(9, 10))
        } finally { dir.deleteRecursively() }
    }

    @Test fun fullArchiveQueueFailsExplicitlyWithoutBlockingCapture() {
        val dir = Files.createTempDirectory("pcm-full").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val archive = PcmArchive(dir, "session", capacity = 1, beforeWrite = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            })
            assertTrue(archive.offer(byteArrayOf(1, 2)))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(archive.offer(byteArrayOf(3, 4)))
            assertFalse(archive.offer(byteArrayOf(5, 6)))
            assertNotNull(archive.failure)
            release.countDown()
            assertTrue(archive.close())
            assertTrue(dir.listFiles()!!.any { it.name.endsWith(".incomplete") })
        } finally { release.countDown(); dir.deleteRecursively() }
    }

    @Test fun diskFailureDoesNotProduceACompletedRecording() {
        val dir = Files.createTempDirectory("pcm-io-error").toFile()
        try {
            val archive = PcmArchive(dir, "session", beforeWrite = { throw java.io.IOException("disk failure") })
            assertTrue(archive.offer(byteArrayOf(1, 2)))
            assertTrue(archive.close())
            assertEquals("disk failure", archive.failure)
            assertFalse(dir.listFiles()!!.any { it.extension == "wav" })
        } finally { dir.deleteRecursively() }
    }

    @Test fun blockedStorageCloseDoesNotClaimFinalization() {
        val dir = Files.createTempDirectory("pcm-close").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val archive = PcmArchive(dir, "session", beforeWrite = {
                entered.countDown()
                check(release.await(8, TimeUnit.SECONDS))
            })
            archive.offer(byteArrayOf(1, 2))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(archive.close())
            release.countDown()
            assertTrue(archive.close())
            assertWav(dir.listFiles()!!.single(), byteArrayOf(1, 2))
        } finally { release.countDown(); dir.deleteRecursively() }
    }

    @Test fun reconnectDoesNotInterruptLocalArchive() {
        val dir = Files.createTempDirectory("pcm-reconnect").toFile()
        try {
            val archive = PcmArchive(dir, "session")
            val gate = LiveSocketGate<String>()
            val first = gate.begin()
            gate.bind(first, "first")
            gate.markReady(first, "first")
            archive.offer(byteArrayOf(1, 2))
            assertTrue(gate.send { true })
            val next = gate.begin()
            archive.offer(byteArrayOf(3, 4))
            assertFalse(gate.send { true })
            gate.bind(next, "next")
            gate.markReady(next, "next")
            archive.offer(byteArrayOf(5, 6))
            assertTrue(gate.send { true })
            assertTrue(archive.close())
            assertWav(dir.listFiles()!!.single(), byteArrayOf(1, 2, 3, 4, 5, 6))
        } finally { dir.deleteRecursively() }
    }

    private fun assertWav(file: File, pcm: ByteArray) {
        val bytes = file.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, header.getInt(24))
        assertEquals(pcm.size, header.getInt(40))
        assertEquals(bytes.size - 8, header.getInt(4))
        assertArrayEquals(pcm, bytes.copyOfRange(44, bytes.size))
    }
}
