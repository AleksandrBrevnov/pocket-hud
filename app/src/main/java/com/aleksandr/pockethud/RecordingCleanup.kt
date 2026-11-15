package com.aleksandr.pockethud

import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * Pure JVM helper for planning and executing safe deletion of recorded conversation audio
 * and exported ZIP archives.
 *
 * Invariants:
 * - Pure JVM, no Android framework dependencies.
 * - Exact deletion: only designated WAV/M4A audio files belonging to the selected conversation.
 * - Strict directory containment: canonical path verification ensures files reside directly
 *   inside the designated root directory without following symlinks outside.
 * - Directory rejection: directories are never deleted as audio or archive targets.
 * - Exclusion: .part, .incomplete, sidecars (.json, .txt), and summary documents (.md)
 *   are strictly excluded from audio deletion plans.
 * - Export cleanup isolation: ZIP cleanup targets only valid .zip archives in conversation_exports,
 *   preserving all original recordings, sidecars, and temporary files.
 * - Re-validation on execution: existence, path boundary, and size are re-checked at actual deletion time.
 * - Non-destructive missing handling: pre-missing files increment missingCount and do not register as failures.
 * - No heuristic wildcarding or neighbor file modification.
 */
object RecordingCleanup {

    data class DeletionPlan(
        val files: List<File>,
        val totalBytes: Long,
        val fileCount: Int = files.size
    )

    data class CleanupResult(
        val deleted: Int,
        val missing: Int,
        val failed: Int,
        val bytesFreed: Long
    ) {
        val isSuccess: Boolean get() = failed == 0
    }

    fun isAudioRecordFile(file: File): Boolean {
        val name = file.name
        val lower = name.lowercase(Locale.US)
        val isAudio = lower.endsWith(".wav") || lower.endsWith(".m4a")
        val isIncomplete = lower.endsWith(".part") ||
            lower.endsWith(".incomplete") ||
            lower.contains(".part.") ||
            lower.contains(".incomplete.")
        val isExcludedDocOrSidecar = lower.endsWith(".md") ||
            lower.endsWith(".json") ||
            lower.endsWith(".txt")
        return isAudio && !isIncomplete && !isExcludedDocOrSidecar
    }

    fun planConversationAudioCleanup(
        conversation: RecordingConversationExport.Conversation,
        recordsDir: File
    ): DeletionPlan {
        return planConversationAudioCleanup(conversation.files, recordsDir)
    }

    fun planConversationAudioCleanup(
        files: Collection<File>,
        recordsDir: File
    ): DeletionPlan {
        if (recordsDir.exists() && !recordsDir.isDirectory) {
            throw IllegalArgumentException("Records root is not a directory: ${recordsDir.absolutePath}")
        }
        if (!recordsDir.exists()) {
            return DeletionPlan(emptyList(), 0L)
        }

        val canonicalRoot = recordsDir.canonicalFile
        val validFiles = mutableListOf<File>()

        for (file in files) {
            if (file.isDirectory) {
                throw IllegalArgumentException("Directory cannot be included in audio cleanup plan: ${file.name}")
            }
            val canonicalFile = file.canonicalFile
            if (canonicalFile.parentFile != canonicalRoot) {
                throw IllegalArgumentException("File is outside records directory: ${file.name}")
            }
            if (canonicalFile.isDirectory) {
                throw IllegalArgumentException("Target is a directory: ${file.name}")
            }
            if (Files.isSymbolicLink(file.toPath())) {
                val realPath = try { file.toPath().toRealPath() } catch (_: Exception) { null }
                if (realPath == null || realPath.parent != canonicalRoot.toPath()) {
                    throw IllegalArgumentException("Symlink points outside records directory: ${file.name}")
                }
            }

            if (isAudioRecordFile(file)) {
                validFiles.add(file)
            }
        }

        val distinctFiles = validFiles.distinctBy { it.canonicalPath }
        val totalBytes = distinctFiles.filter { it.exists() && it.isFile }.sumOf { it.length() }
        return DeletionPlan(files = distinctFiles, totalBytes = totalBytes)
    }

    fun planExportZipCleanup(exportsDir: File): DeletionPlan {
        if (!exportsDir.exists()) {
            return DeletionPlan(emptyList(), 0L)
        }
        if (!exportsDir.isDirectory) {
            throw IllegalArgumentException("Exports root is not a directory: ${exportsDir.absolutePath}")
        }

        val canonicalRoot = exportsDir.canonicalFile
        val rawFiles = exportsDir.listFiles() ?: emptyArray()
        val validZips = mutableListOf<File>()

        for (file in rawFiles) {
            if (file.isDirectory) {
                continue
            }
            val name = file.name
            val lower = name.lowercase(Locale.US)
            if (!lower.endsWith(".zip")) {
                continue
            }
            if (lower.endsWith(".part") || lower.endsWith(".incomplete") || lower.contains(".tmp")) {
                continue
            }
            val canonicalFile = file.canonicalFile
            if (canonicalFile.parentFile != canonicalRoot || canonicalFile.isDirectory) {
                continue
            }
            if (Files.isSymbolicLink(file.toPath())) {
                val realPath = try { file.toPath().toRealPath() } catch (_: Exception) { null }
                if (realPath == null || realPath.parent != canonicalRoot.toPath()) {
                    continue
                }
            }
            validZips.add(file)
        }

        val sortedFiles = validZips.sortedBy { it.name }
        val totalBytes = sortedFiles.filter { it.exists() && it.isFile }.sumOf { it.length() }
        return DeletionPlan(files = sortedFiles, totalBytes = totalBytes)
    }

    fun executePlan(
        plan: DeletionPlan,
        rootDir: File,
        deleter: (File) -> Boolean = { it.delete() }
    ): CleanupResult {
        if (rootDir.exists() && !rootDir.isDirectory) {
            throw IllegalArgumentException("Root directory is not a directory: ${rootDir.absolutePath}")
        }
        val canonicalRoot = rootDir.canonicalFile

        var deletedCount = 0
        var missingCount = 0
        var failedCount = 0
        var bytesFreed = 0L

        for (file in plan.files) {
            val canonicalFile = file.canonicalFile
            if (canonicalFile.parentFile != canonicalRoot) {
                throw IllegalArgumentException("File is outside root directory: ${file.path}")
            }
            if (file.isDirectory || canonicalFile.isDirectory) {
                failedCount++
                continue
            }
            if (Files.isSymbolicLink(file.toPath())) {
                val realPath = try { file.toPath().toRealPath() } catch (_: Exception) { null }
                if (realPath == null || realPath.parent != canonicalRoot.toPath()) {
                    throw IllegalArgumentException("Symlink points outside root directory: ${file.path}")
                }
            }

            if (!file.exists()) {
                missingCount++
                continue
            }

            val size = file.length()
            val success = try {
                deleter(file)
            } catch (_: Exception) {
                false
            }

            if (success && !file.exists()) {
                deletedCount++
                bytesFreed += size
            } else {
                failedCount++
            }
        }

        return CleanupResult(
            deleted = deletedCount,
            missing = missingCount,
            failed = failedCount,
            bytesFreed = bytesFreed
        )
    }

    fun formatBytes(bytes: Long): String {
        return if (bytes < 1_000_000L) {
            String.format(Locale.US, "%.1f КБ", bytes / 1000.0)
        } else {
            String.format(Locale.US, "%.1f МБ", bytes / 1_000_000.0)
        }
    }
}
