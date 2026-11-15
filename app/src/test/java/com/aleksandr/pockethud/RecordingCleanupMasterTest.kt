package com.aleksandr.pockethud

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecordingCleanupMasterTest {
    @Test fun executionCannotDeleteASavedSummaryEvenFromAnInvalidPlan() {
        val dir = Files.createTempDirectory("cleanup-summary").toFile()
        try {
            val summary = File(dir, "audio_f0-f1.summary.md").apply { writeText("Saved information") }
            val result = RecordingCleanup.executePlan(
                RecordingCleanup.DeletionPlan(listOf(summary), summary.length()), dir)
            assertEquals(0, result.deleted)
            assertEquals(1, result.failed)
            assertFalse(result.isSuccess)
            assertEquals("Saved information", summary.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun symbolicLinksInsideTheRootAreNotAudioDeletionTargets() {
        val dir = Files.createTempDirectory("cleanup-link-inside").toFile()
        try {
            val target = File(dir, "another-session.wav").apply { writeBytes(byteArrayOf(1, 2)) }
            val link = File(dir, "selected-session.wav")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            var rejected = false
            try { RecordingCleanup.planConversationAudioCleanup(listOf(link), dir) }
            catch (e: IllegalArgumentException) { rejected = true }
            assertTrue("Links must not be counted as independently stored audio", rejected)
            assertArrayEquals(byteArrayOf(1, 2), target.readBytes())
            assertTrue(Files.isSymbolicLink(link.toPath()))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aPathReplacedAfterConfirmationProducesAccuratePartialFailure() {
        val base = Files.createTempDirectory("cleanup-changed-path").toFile()
        try {
            val dir = File(base, "records").apply { mkdir() }
            val first = File(dir, "first.wav").apply { writeBytes(byteArrayOf(1, 2)) }
            val second = File(dir, "second.wav").apply { writeBytes(byteArrayOf(3, 4)) }
            val outside = File(base, "other.wav").apply { writeBytes(byteArrayOf(5, 6)) }
            val plan = RecordingCleanup.planConversationAudioCleanup(listOf(first, second), dir)
            assertTrue(second.delete())
            Files.createSymbolicLink(second.toPath(), outside.toPath())
            val result = RecordingCleanup.executePlan(plan, dir)
            assertEquals(1, result.deleted)
            assertEquals(1, result.failed)
            assertEquals(2L, result.bytesFreed)
            assertFalse(result.isSuccess)
            assertArrayEquals(byteArrayOf(5, 6), outside.readBytes())
            assertTrue(Files.isSymbolicLink(second.toPath()))
        } finally { base.deleteRecursively() }
    }
}
