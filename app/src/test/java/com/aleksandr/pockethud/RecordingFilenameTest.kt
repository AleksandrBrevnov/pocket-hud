package com.aleksandr.pockethud

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

class RecordingFilenameTest {
    @Test fun eachPartHasReadableLocalDateAndItsOwnStartTime() {
        val dir = Files.createTempDirectory("recording-name").toFile()
        try {
            val archive = PcmArchive(dir, "unique-session", maxDataBytes = 32000,
                startedAtMillis = Instant.parse("2026-11-15T13:59:59Z").toEpochMilli(),
                zoneId = ZoneId.of("Europe/Rome"))
            assertTrue(archive.offer(ByteArray(32000)))
            assertTrue(archive.offer(byteArrayOf(42, 0)))
            assertTrue(archive.close())
            assertNull(archive.failure)
            val files = dir.listFiles()!!.filter { it.extension == "wav" }.sortedBy { it.name }
            assertEquals(listOf(
                "record_2026-11-15_14-59-59_unique-session_0001.wav",
                "record_2026-11-15_15-00-00_unique-session_0002.wav"
            ), files.map { it.name })
            assertEquals(32044L, files[0].length())
            assertArrayEquals(byteArrayOf(42, 0), files[1].readBytes().drop(44).toByteArray())
        } finally { dir.deleteRecursively() }
    }

    @Test fun partTimeUsesZoneRulesAtDaylightSavingBoundary() {
        val dir = Files.createTempDirectory("recording-dst").toFile()
        try {
            val archive = PcmArchive(dir, "dst-session", maxDataBytes = 32000,
                startedAtMillis = Instant.parse("2026-10-25T00:59:59Z").toEpochMilli(),
                zoneId = ZoneId.of("Europe/Rome"))
            assertTrue(archive.offer(ByteArray(32002)))
            assertTrue(archive.close())
            assertNull(archive.failure)
            assertEquals(setOf(
                "record_2026-10-25_02-59-59_dst-session_0001.wav",
                "record_2026-10-25_02-00-00_dst-session_0002.wav"
            ), dir.listFiles()!!.map { it.name }.toSet())
        } finally { dir.deleteRecursively() }
    }
}
