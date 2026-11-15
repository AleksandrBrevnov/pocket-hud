package com.aleksandr.pockethud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class RecordingCleanupTest {

    @Test
    fun planAudioCleanupExcludesNonAudioAndIncompleteFiles() {
        val tempDir = Files.createTempDirectory("plan_audio_filter_test").toFile()
        try {
            val audio1 = File(tempDir, "record_2026-11-15_12-00-00_sessA_0001.wav").apply {
                writeBytes(ByteArray(1000))
            }
            val audio2 = File(tempDir, "record_2026-11-15_12-10-00_sessA_0002.m4a").apply {
                writeBytes(ByteArray(2000))
            }
            val partFile = File(tempDir, "record_2026-11-15_12-20-00_sessA_0003.wav.part").apply {
                writeBytes(ByteArray(500))
            }
            val incompleteFile = File(tempDir, "record_2026-11-15_12-30-00_sessA_0004.incomplete.wav").apply {
                writeBytes(ByteArray(500))
            }
            val summaryMd = File(tempDir, "record_2026-11-15_12-00-00_sessA_0001.wav.summary_1.md").apply {
                writeText("# Summary")
            }
            val sidecarJson = File(tempDir, "metadata.json").apply {
                writeText("{}")
            }

            val conv = RecordingConversationExport.Conversation(
                key = "sessA",
                files = listOf(audio1, audio2, partFile, incompleteFile, summaryMd, sidecarJson),
                label = "Session A"
            )

            val plan = RecordingCleanup.planConversationAudioCleanup(conv, tempDir)
            assertEquals(2, plan.fileCount)
            assertEquals(listOf(audio1, audio2), plan.files)
            assertEquals(3000L, plan.totalBytes)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun selectedSessionDeletedOtherSessionsAndFilesPreserved() {
        val tempDir = Files.createTempDirectory("cleanup_session_isolation_test").toFile()
        try {
            val sessA1 = File(tempDir, "record_2026-11-15_10-00-00_sess_alpha_0001.wav").apply {
                writeBytes(ByteArray(1200))
            }
            val sessA2 = File(tempDir, "record_2026-11-15_10-10-00_sess_alpha_0002.wav").apply {
                writeBytes(ByteArray(1800))
            }
            val sessB1 = File(tempDir, "record_2026-11-16_14-00-00_sess_beta_0001.wav").apply {
                writeBytes(ByteArray(2500))
            }
            val summaryMd = File(tempDir, "record_2026-11-15_10-00-00_sess_alpha_0001.wav.summary_1.md").apply {
                writeText("Saved summary text for session alpha")
            }
            val partFile = File(tempDir, "record_2026-11-15_10-20-00_sess_alpha_0003.wav.part").apply {
                writeBytes(ByteArray(300))
            }

            val convAlpha = RecordingConversationExport.Conversation(
                key = "sess_alpha",
                files = listOf(sessA1, sessA2),
                label = "2026-11-15 10:00:00 (2 части)"
            )

            val plan = RecordingCleanup.planConversationAudioCleanup(convAlpha, tempDir)
            assertEquals(2, plan.fileCount)
            assertEquals(3000L, plan.totalBytes)

            val result = RecordingCleanup.executePlan(plan, tempDir)
            assertEquals(2, result.deleted)
            assertEquals(0, result.missing)
            assertEquals(0, result.failed)
            assertEquals(3000L, result.bytesFreed)
            assertTrue(result.isSuccess)

            assertFalse("Session A part 1 must be deleted", sessA1.exists())
            assertFalse("Session A part 2 must be deleted", sessA2.exists())

            assertTrue("Session B from another day must be preserved", sessB1.exists())
            assertEquals(2500L, sessB1.length())

            assertTrue("Summary MD sidecar must be preserved", summaryMd.exists())
            assertEquals("Saved summary text for session alpha", summaryMd.readText())

            assertTrue("Part file must be preserved", partFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun zipCleanupDeletesOnlyZipsInExportsDirAndPreservesAudio() {
        val rootDir = Files.createTempDirectory("zip_cleanup_isolation_test").toFile()
        try {
            val audioDir = File(rootDir, "audio_records").apply { mkdirs() }
            val exportsDir = File(rootDir, "conversation_exports").apply { mkdirs() }

            val audio = File(audioDir, "record_2026-11-15_10-00-00_sess1_0001.wav").apply {
                writeBytes(ByteArray(4000))
            }
            val zip1 = File(exportsDir, "conversation_sess1.zip").apply {
                writeBytes(ByteArray(5000))
            }
            val zip2 = File(exportsDir, "conversation_sess2.zip").apply {
                writeBytes(ByteArray(7000))
            }
            val tmpFile = File(exportsDir, "conversation_sess3.zip.tmp_12345").apply {
                writeBytes(ByteArray(600))
            }
            val readme = File(exportsDir, "readme.txt").apply {
                writeText("Keep this note")
            }

            val plan = RecordingCleanup.planExportZipCleanup(exportsDir)
            assertEquals(2, plan.fileCount)
            assertEquals(setOf(zip1, zip2), plan.files.toSet())
            assertEquals(12000L, plan.totalBytes)

            val result = RecordingCleanup.executePlan(plan, exportsDir)
            assertEquals(2, result.deleted)
            assertEquals(0, result.missing)
            assertEquals(0, result.failed)
            assertEquals(12000L, result.bytesFreed)
            assertTrue(result.isSuccess)

            assertFalse("Zip 1 must be deleted", zip1.exists())
            assertFalse("Zip 2 must be deleted", zip2.exists())

            assertTrue("Tmp export file must NOT be deleted", tmpFile.exists())
            assertTrue("Txt file must NOT be deleted", readme.exists())
            assertTrue("Audio in audio_records must NOT be touched", audio.exists())
            assertEquals(4000L, audio.length())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun emptyPlanForNonExistentOrEmptyExportsDir() {
        val tempDir = Files.createTempDirectory("empty_plan_test").toFile()
        try {
            val nonExistentDir = File(tempDir, "does_not_exist")
            val emptyPlan1 = RecordingCleanup.planExportZipCleanup(nonExistentDir)
            assertEquals(0, emptyPlan1.fileCount)
            assertEquals(0L, emptyPlan1.totalBytes)

            val emptyDir = File(tempDir, "empty_dir").apply { mkdirs() }
            val emptyPlan2 = RecordingCleanup.planExportZipCleanup(emptyDir)
            assertEquals(0, emptyPlan2.fileCount)
            assertEquals(0L, emptyPlan2.totalBytes)

            val result = RecordingCleanup.executePlan(emptyPlan2, emptyDir)
            assertEquals(0, result.deleted)
            assertEquals(0, result.missing)
            assertEquals(0, result.failed)
            assertEquals(0L, result.bytesFreed)
            assertTrue(result.isSuccess)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun missingFileBeforeExecutionCountedAsMissingNotFailed() {
        val tempDir = Files.createTempDirectory("missing_file_test").toFile()
        try {
            val f1 = File(tempDir, "record_sess_0001.wav").apply { writeBytes(ByteArray(1000)) }
            val f2 = File(tempDir, "record_sess_0002.wav").apply { writeBytes(ByteArray(2000)) }

            val conv = RecordingConversationExport.Conversation(
                key = "sess",
                files = listOf(f1, f2),
                label = "Session"
            )
            val plan = RecordingCleanup.planConversationAudioCleanup(conv, tempDir)
            assertEquals(2, plan.fileCount)
            assertEquals(3000L, plan.totalBytes)

            // External process removed f2 before cleanup execution
            f2.delete()
            assertFalse(f2.exists())

            val result = RecordingCleanup.executePlan(plan, tempDir)
            assertEquals(1, result.deleted)
            assertEquals(1, result.missing)
            assertEquals(0, result.failed)
            assertEquals(1000L, result.bytesFreed)
            assertTrue("Missing file is not an error/failure", result.isSuccess)
            assertFalse(f1.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun outOfRootFileOrSymlinkOrDirectoryRejected() {
        val tempDir = Files.createTempDirectory("security_boundary_test").toFile()
        try {
            val recordsDir = File(tempDir, "records").apply { mkdirs() }
            val outsideFile = File(tempDir, "outside_record.wav").apply {
                writeBytes(ByteArray(1000))
            }
            val subDir = File(recordsDir, "nested_dir.wav").apply {
                mkdirs()
            }

            // Outside file in conversation
            val convOutside = RecordingConversationExport.Conversation(
                key = "outside",
                files = listOf(outsideFile),
                label = "Outside"
            )
            try {
                RecordingCleanup.planConversationAudioCleanup(convOutside, recordsDir)
                fail("Expected IllegalArgumentException for out-of-root file in plan")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("outside", ignoreCase = true) == true)
            }

            // Directory in conversation
            val convDir = RecordingConversationExport.Conversation(
                key = "dir",
                files = listOf(subDir),
                label = "Directory"
            )
            try {
                RecordingCleanup.planConversationAudioCleanup(convDir, recordsDir)
                fail("Expected IllegalArgumentException for directory in conversation")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("directory", ignoreCase = true) == true)
            }

            // Outside file in plan execution directly
            val illegalPlan = RecordingCleanup.DeletionPlan(files = listOf(outsideFile), totalBytes = 1000L)
            try {
                RecordingCleanup.executePlan(illegalPlan, recordsDir)
                fail("Expected IllegalArgumentException for out-of-root file in executePlan")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("outside", ignoreCase = true) == true)
            }
            assertTrue("Outside file must NOT be deleted", outsideFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun existingFileInsteadOfDirectoryRootRejected() {
        val tempDir = Files.createTempDirectory("invalid_root_test").toFile()
        try {
            val fakeDirAsFile = File(tempDir, "not_a_dir").apply {
                writeText("I am a file, not a directory")
            }

            try {
                RecordingCleanup.planExportZipCleanup(fakeDirAsFile)
                fail("Expected IllegalArgumentException when root is a file")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("not a directory", ignoreCase = true) == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun partialFailureReturnsAccurateStatsAndIsNotSuccess() {
        val tempDir = Files.createTempDirectory("partial_failure_test").toFile()
        try {
            val f1 = File(tempDir, "record_sess_0001.wav").apply { writeBytes(ByteArray(1000)) }
            val f2 = File(tempDir, "record_sess_0002.wav").apply { writeBytes(ByteArray(2000)) }

            val plan = RecordingCleanup.DeletionPlan(files = listOf(f1, f2), totalBytes = 3000L)

            // Deterministic deleter: fails for f2, succeeds for f1
            val result = RecordingCleanup.executePlan(plan, tempDir, deleter = { file ->
                if (file.name == f2.name) {
                    false
                } else {
                    file.delete()
                }
            })

            assertEquals(1, result.deleted)
            assertEquals(0, result.missing)
            assertEquals(1, result.failed)
            assertEquals(1000L, result.bytesFreed)
            assertFalse("Partial failure must not declare complete success", result.isSuccess)

            assertFalse("f1 should be deleted", f1.exists())
            assertTrue("f2 should still exist after failed deletion", f2.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun zipCleanupDoesNotRecurseIntoSubdirectories() {
        val tempDir = Files.createTempDirectory("no_recursion_test").toFile()
        try {
            val topZip = File(tempDir, "conversation_top.zip").apply { writeBytes(ByteArray(500)) }
            val subDir = File(tempDir, "subfolder").apply { mkdirs() }
            val nestedZip = File(subDir, "conversation_nested.zip").apply { writeBytes(ByteArray(700)) }

            val plan = RecordingCleanup.planExportZipCleanup(tempDir)
            assertEquals(1, plan.fileCount)
            assertEquals(listOf(topZip), plan.files)

            val result = RecordingCleanup.executePlan(plan, tempDir)
            assertEquals(1, result.deleted)
            assertTrue(result.isSuccess)

            assertFalse("Top zip must be deleted", topZip.exists())
            assertTrue("Nested zip in subfolder must NOT be touched", nestedZip.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
