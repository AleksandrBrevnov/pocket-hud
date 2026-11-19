package com.aleksandr.pockethud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipFile

class RecordingConversationExportTest {

    @Test
    fun batchZipSeparatesConversationsAndIncludesEachSelectedFileOnce() {
        val tempDir = Files.createTempDirectory("batch-zip-test").toFile()
        try {
            val first = File(tempDir, "record_alpha_0001.wav").apply { writeBytes(byteArrayOf(1, 2)) }
            val second = File(tempDir, "record_beta_0001.m4a").apply { writeBytes(byteArrayOf(3, 4)) }
            val extra = File(tempDir, "standalone.wav").apply { writeBytes(byteArrayOf(5, 6)) }
            val archive = RecordingConversationExport.writeBatchZip(
                RecordingConversationExport.group(listOf(first, second)),
                listOf(first, extra, extra),
                File(tempDir, "exports")
            )

            ZipFile(archive).use { zip ->
                val expected = mapOf(
                    "conversations/1_alpha/${first.name}" to first.readBytes(),
                    "conversations/2_beta/${second.name}" to second.readBytes(),
                    "recordings/${extra.name}" to extra.readBytes()
                )
                assertEquals(expected.keys, zip.entries().asSequence().map { it.name }.toSet())
                for ((name, bytes) in expected) {
                    assertArrayEquals(bytes, zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun groupOldFormatFilesByExactSessionId() {
        val f1 = File("record_20261115_131836_1_b48d65af_0001.wav")
        val f2 = File("record_20261115_131836_1_b48d65af_0002.wav")
        val conversations = RecordingConversationExport.group(listOf(f2, f1))

        assertEquals(1, conversations.size)
        val conv = conversations.first()
        assertEquals("20261115_131836_1_b48d65af", conv.key)
        assertEquals(listOf(f1, f2), conv.files)
        assertTrue("Label should contain human-readable date/time", conv.label.contains("2026-11-15"))
        assertTrue("Label should indicate part count", conv.label.contains("2"))
    }

    @Test
    fun groupNewFormatFilesWithDatePrefix() {
        val f1 = File("record_2026-11-15_14-18-36_sess_alpha_0001.wav")
        val f2 = File("record_2026-11-15_14-28-36_sess_alpha_0002.wav")
        val conversations = RecordingConversationExport.group(listOf(f1, f2))

        assertEquals(1, conversations.size)
        val conv = conversations.first()
        assertEquals("sess_alpha", conv.key)
        assertEquals(listOf(f1, f2), conv.files)
        assertTrue(conv.label.contains("2026-11-15"))
        assertTrue(conv.label.contains("2"))
    }

    @Test
    fun groupMixedOldAndNewFormatFilesWithSameSessionId() {
        val f1 = File("record_common_session_0001.wav")
        val f2 = File("record_2026-11-15_14-28-36_common_session_0002.wav")
        val conversations = RecordingConversationExport.group(listOf(f2, f1))

        assertEquals(1, conversations.size)
        val conv = conversations.first()
        assertEquals("common_session", conv.key)
        assertEquals(listOf(f1, f2), conv.files)
    }

    @Test
    fun differentSessionsAtSameSecondNotMerged() {
        val f1 = File("record_2026-11-15_14-00-00_20261115_140000_1_aaaa1111_0001.wav")
        val f2 = File("record_2026-11-15_14-00-00_20261115_140000_2_bbbb2222_0001.wav")
        val conversations = RecordingConversationExport.group(listOf(f1, f2))

        assertEquals(2, conversations.size)
        val keys = conversations.map { it.key }.toSet()
        assertEquals(
            setOf("20261115_140000_1_aaaa1111", "20261115_140000_2_bbbb2222"),
            keys
        )
    }

    @Test
    fun numericalPartOrderingNotLexicographical() {
        val f1 = File("record_sess_0001.wav")
        val f2 = File("record_sess_0002.wav")
        val f10 = File("record_sess_0010.wav")

        // Passed out of order
        val conversations = RecordingConversationExport.group(listOf(f10, f2, f1))
        assertEquals(1, conversations.size)
        assertEquals(listOf(f1, f2, f10), conversations.first().files)
    }

    @Test
    fun unknownFilesIsolatedSeparately() {
        val u1 = File("legacy_audio_1.wav")
        val u2 = File("legacy_audio_2.m4a")
        val u3 = File("standalone.wav")

        val conversations = RecordingConversationExport.group(listOf(u1, u2, u3))
        assertEquals(3, conversations.size)
        val fileSets = conversations.map { it.files }.toSet()
        assertEquals(setOf(listOf(u1), listOf(u2), listOf(u3)), fileSets)
    }

    @Test
    fun excludeIncompleteAndSidecarFiles() {
        val valid = File("record_2026-11-15_10-00-00_valid_0001.wav")
        val part = File("record_2026-11-15_10-00-00_valid_0002.wav.part")
        val incomplete = File("record_2026-11-15_10-00-00_valid_0003.wav.incomplete")
        val sidecarJson = File("record_2026-11-15_10-00-00_valid_0001.wav.json")
        val sidecarTxt = File("notes.txt")

        val conversations = RecordingConversationExport.group(listOf(valid, part, incomplete, sidecarJson, sidecarTxt))
        assertEquals(1, conversations.size)
        assertEquals(listOf(valid), conversations.first().files)
    }

    @Test
    fun labelDetectsGapsInParts() {
        val f1 = File("record_20261115_120000_1_uuid_0001.wav")
        val f3 = File("record_20261115_120000_1_uuid_0003.wav")

        val conversations = RecordingConversationExport.group(listOf(f1, f3))
        assertEquals(1, conversations.size)
        val conv = conversations.first()
        assertTrue("Label should note missing parts / gaps", conv.label.contains("пропуск"))
    }

    @Test
    fun writeZipVerifiesExactBytesAndEntryBasenames() {
        val tempDir = Files.createTempDirectory("zip_test").toFile()
        try {
            val audio1 = File(tempDir, "record_2026-11-15_15-00-00_session_abc_0001.wav")
            val audio2 = File(tempDir, "record_2026-11-15_15-10-00_session_abc_0002.wav")

            val bytes1 = ByteArray(1024) { (it % 256).toByte() }
            val bytes2 = ByteArray(2048) { ((it * 3) % 256).toByte() }

            FileOutputStream(audio1).use { it.write(bytes1) }
            FileOutputStream(audio2).use { it.write(bytes2) }

            val conv = RecordingConversationExport.Conversation(
                key = "session_abc",
                files = listOf(audio1, audio2),
                label = "2026-11-15 15:00:00 (2 части)"
            )

            val exportDir = File(tempDir, "exports")
            val zipFile = RecordingConversationExport.writeZip(conv, exportDir)

            assertTrue("Zip file should exist", zipFile.exists())
            assertTrue("Zip file size > 0", zipFile.length() > 0)

            ZipFile(zipFile).use { zf ->
                val entries = zf.entries().asSequence().toList()
                assertEquals(2, entries.size)

                val entryNames = entries.map { it.name }.toSet()
                assertEquals(setOf(audio1.name, audio2.name), entryNames)

                for (entry in entries) {
                    assertFalse("Entry name must not contain directory paths", entry.name.contains("/") || entry.name.contains("\\"))
                    val entryBytes = zf.getInputStream(entry).use { it.readBytes() }
                    val expectedBytes = if (entry.name == audio1.name) bytes1 else bytes2
                    assertArrayEquals("Entry bytes must match original 100%", expectedBytes, entryBytes)
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun writeZipRepeatedExportDoesNotOverwrite() {
        val tempDir = Files.createTempDirectory("zip_repeat_test").toFile()
        try {
            val audio = File(tempDir, "record_session_x_0001.wav")
            audio.writeBytes(byteArrayOf(1, 2, 3, 4))

            val conv = RecordingConversationExport.Conversation(
                key = "session_x",
                files = listOf(audio),
                label = "Session X"
            )

            val exportDir = File(tempDir, "exports")
            val zip1 = RecordingConversationExport.writeZip(conv, exportDir)
            val zip2 = RecordingConversationExport.writeZip(conv, exportDir)

            assertTrue(zip1.exists())
            assertTrue(zip2.exists())
            assertNotEquals("Repeated exports must generate unique filenames", zip1.absolutePath, zip2.absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun writeZipCleansUpTempFileOnFailure() {
        val tempDir = Files.createTempDirectory("zip_fail_test").toFile()
        try {
            val nonExistentAudio = File(tempDir, "does_not_exist_0001.wav")
            val conv = RecordingConversationExport.Conversation(
                key = "session_fail",
                files = listOf(nonExistentAudio),
                label = "Fail Session"
            )

            val exportDir = File(tempDir, "exports")
            try {
                RecordingConversationExport.writeZip(conv, exportDir)
                fail("Should fail when audio file does not exist")
            } catch (e: Exception) {
                // Expected failure
            }

            val filesAfterFailure = exportDir.listFiles() ?: emptyArray()
            val tempFiles = filesAfterFailure.filter { it.name.contains(".tmp") }
            assertTrue("No temp files should remain after failure", tempFiles.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeZipEmptyFilesThrows() {
        val tempDir = Files.createTempDirectory("zip_empty_test").toFile()
        try {
            val conv = RecordingConversationExport.Conversation(
                key = "empty",
                files = emptyList(),
                label = "Empty"
            )
            RecordingConversationExport.writeZip(conv, tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test(expected = IOException::class)
    fun writeZipIncompleteFileThrows() {
        val tempDir = Files.createTempDirectory("zip_incomp_test").toFile()
        try {
            val incompleteFile = File(tempDir, "record_0001.wav.incomplete")
            incompleteFile.writeBytes(byteArrayOf(1, 2, 3))
            val conv = RecordingConversationExport.Conversation(
                key = "incomp",
                files = listOf(incompleteFile),
                label = "Incomplete"
            )
            RecordingConversationExport.writeZip(conv, tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
