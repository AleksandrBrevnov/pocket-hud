package com.aleksandr.pockethud

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files

class ConversationExportMasterTest {
    @Test fun cancelledExportDoesNotPublishAnArchive() {
        val dir = Files.createTempDirectory("zip-cancel").toFile()
        try {
            val source = File(dir, "audio.wav").apply { writeBytes(ByteArray(65536)) }
            val output = File(dir, "exports")
            var cancelled = false
            Thread.currentThread().interrupt()
            try {
                RecordingConversationExport.writeZip(
                    RecordingConversationExport.Conversation("cancel", listOf(source), "test"), output)
            } catch (e: InterruptedIOException) {
                cancelled = true
            } finally { Thread.interrupted() }
            assertTrue("Cancelled work must throw InterruptedIOException", cancelled)
            assertTrue(output.listFiles()?.isEmpty() != false)
            assertEquals(65536L, source.length())
        } finally { Thread.interrupted(); dir.deleteRecursively() }
    }

    @Test fun failureAfterFirstZipEntryLeavesNoPublishedOrTemporaryArchive() {
        val dir = Files.createTempDirectory("zip-mid-failure").toFile()
        try {
            val first = File(File(dir, "a").apply { mkdir() }, "audio.wav").apply { writeBytes(byteArrayOf(1, 2)) }
            val second = File(File(dir, "b").apply { mkdir() }, "audio.wav").apply { writeBytes(byteArrayOf(3, 4)) }
            val output = File(dir, "exports")
            var failed = false
            try {
                RecordingConversationExport.writeZip(
                    RecordingConversationExport.Conversation("duplicate", listOf(first, second), "test"), output)
            } catch (e: IOException) { failed = true }
            assertTrue(failed)
            assertTrue(output.listFiles()?.isEmpty() != false)
            assertArrayEquals(byteArrayOf(1, 2), first.readBytes())
            assertArrayEquals(byteArrayOf(3, 4), second.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
