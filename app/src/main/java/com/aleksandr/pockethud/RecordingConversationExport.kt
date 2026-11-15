package com.aleksandr.pockethud

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure JVM helper for grouping and streaming ZIP export of audio recordings.
 *
 * Invariants:
 * - Pure JVM, no Android framework dependencies.
 * - Exact grouping by sessionId without heuristic truncation of archiveSegment or UUID.
 * - Numerical sorting of parts by index.
 * - Isolation of unknown/legacy files into separate single-item groups.
 * - Exclusion of .part, .incomplete, and sidecar files.
 * - Atomic ZIP creation via temporary file and streaming buffered I/O.
 * - Non-overwriting unique output naming and cleanup on failure.
 */
object RecordingConversationExport {

    data class Conversation(
        val key: String,
        val files: List<File>,
        val label: String
    )

    private val NEW_FORMAT_REGEX = Regex(
        """^record_(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})_(.+)_(0*(\d+))\.(wav|m4a)$""",
        RegexOption.IGNORE_CASE
    )

    private val OLD_FORMAT_REGEX = Regex(
        """^record_(.+)_(0*(\d+))\.(wav|m4a)$""",
        RegexOption.IGNORE_CASE
    )

    private val TIMESTAMP_COMPACT_REGEX = Regex(
        """^(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})"""
    )

    private val TIMESTAMP_HYPHEN_REGEX = Regex(
        """^(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})"""
    )

    private data class ParsedFile(
        val file: File,
        val sessionId: String?,
        val datePrefix: String?,
        val index: Int?
    )

    fun group(files: List<File>): List<Conversation> {
        val validFiles = files.filter { file ->
            val name = file.name
            val lower = name.lowercase(Locale.US)
            val isAudio = lower.endsWith(".wav") || lower.endsWith(".m4a")
            val isIncomplete = lower.endsWith(".part") ||
                lower.endsWith(".incomplete") ||
                lower.contains(".part.") ||
                lower.contains(".incomplete.")
            isAudio && !isIncomplete
        }

        if (validFiles.isEmpty()) return emptyList()

        val parsedList = validFiles.map { file ->
            parseFileInfo(file)
        }

        val knownGroups = mutableMapOf<String, MutableList<ParsedFile>>()
        val unknownFiles = mutableListOf<ParsedFile>()

        for (parsed in parsedList) {
            if (parsed.sessionId != null) {
                knownGroups.getOrPut(parsed.sessionId) { mutableListOf() }.add(parsed)
            } else {
                unknownFiles.add(parsed)
            }
        }

        val result = mutableListOf<Conversation>()

        for ((sessionId, groupFiles) in knownGroups) {
            val sortedFiles = groupFiles.sortedWith(
                compareBy<ParsedFile> { it.index ?: Int.MAX_VALUE }
                    .thenBy { it.file.name }
            )
            val rawFiles = sortedFiles.map { it.file }
            val label = formatLabel(sessionId, sortedFiles)
            result.add(Conversation(key = sessionId, files = rawFiles, label = label))
        }

        for (unknown in unknownFiles) {
            val file = unknown.file
            val key = "file:${file.name}"
            val label = formatUnknownLabel(file)
            result.add(Conversation(key = key, files = listOf(file), label = label))
        }

        return result
    }

    private fun parseFileInfo(file: File): ParsedFile {
        val name = file.name

        val newMatch = NEW_FORMAT_REGEX.matchEntire(name)
        if (newMatch != null) {
            val datePrefix = newMatch.groupValues[1]
            val sessionId = newMatch.groupValues[2]
            val index = newMatch.groupValues[4].toIntOrNull()
            return ParsedFile(file, sessionId = sessionId, datePrefix = datePrefix, index = index)
        }

        val oldMatch = OLD_FORMAT_REGEX.matchEntire(name)
        if (oldMatch != null) {
            val sessionId = oldMatch.groupValues[1]
            val index = oldMatch.groupValues[3].toIntOrNull()
            return ParsedFile(file, sessionId = sessionId, datePrefix = null, index = index)
        }

        return ParsedFile(file, sessionId = null, datePrefix = null, index = null)
    }

    private fun formatLabel(sessionId: String, files: List<ParsedFile>): String {
        val dateStr = extractDateTime(sessionId, files)
        val count = files.size
        val countStr = formatPartsCount(count)

        val indices = files.mapNotNull { it.index }
        val hasGaps = if (indices.size == count && count > 0) {
            indices.first() != 1 || indices != (1..count).toList()
        } else {
            true
        }

        val gapSuffix = if (hasGaps) ", есть пропуски" else ""
        return "$dateStr ($countStr$gapSuffix)"
    }

    private fun formatUnknownLabel(file: File): String {
        val dateStr = if (file.lastModified() > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))
        } else {
            null
        }
        return if (dateStr != null) {
            "$dateStr (${file.name}, 1 файл)"
        } else {
            "${file.name} (1 файл)"
        }
    }

    private fun extractDateTime(sessionId: String, files: List<ParsedFile>): String {
        val compactMatch = TIMESTAMP_COMPACT_REGEX.find(sessionId)
        if (compactMatch != null) {
            val (y, m, d, hh, mm, ss) = compactMatch.destructured
            return "$y-$m-$d $hh:$mm:$ss"
        }

        val hyphenMatch = TIMESTAMP_HYPHEN_REGEX.find(sessionId)
        if (hyphenMatch != null) {
            val (ymd, hh, mm, ss) = hyphenMatch.destructured
            return "$ymd $hh:$mm:$ss"
        }

        val firstDatePrefix = files.firstOrNull { it.datePrefix != null }?.datePrefix
        if (firstDatePrefix != null) {
            val prefixMatch = TIMESTAMP_HYPHEN_REGEX.find(firstDatePrefix)
            if (prefixMatch != null) {
                val (ymd, hh, mm, ss) = prefixMatch.destructured
                return "$ymd $hh:$mm:$ss"
            }
        }

        val firstFile = files.firstOrNull()?.file
        if (firstFile != null && firstFile.lastModified() > 0L) {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(firstFile.lastModified()))
        }

        return sessionId
    }

    private fun formatPartsCount(count: Int): String {
        val rem10 = count % 10
        val rem100 = count % 100
        val word = when {
            rem100 in 11..19 -> "частей"
            rem10 == 1 -> "часть"
            rem10 in 2..4 -> "части"
            else -> "частей"
        }
        return "$count $word"
    }

    @Throws(IOException::class)
    fun writeZip(conversation: Conversation, outputDir: File): File {
        require(conversation.files.isNotEmpty()) { "Conversation has no files to export" }

        for (file in conversation.files) {
            val lower = file.name.lowercase(Locale.US)
            if (lower.endsWith(".part") || lower.endsWith(".incomplete")) {
                throw IOException("Cannot export incomplete file: ${file.name}")
            }
            if (!file.exists() || !file.canRead()) {
                throw IOException("File does not exist or cannot be read: ${file.absolutePath}")
            }
        }

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        check(outputDir.isDirectory) { "Output directory is not a directory: ${outputDir.absolutePath}" }

        val totalBytes = conversation.files.sumOf { it.length() }
        val usable = outputDir.usableSpace
        if (usable > 0L && usable < totalBytes + 1024L * 1024L) {
            throw IOException("Not enough free space on storage (required ~${totalBytes + 1024 * 1024} bytes, available $usable)")
        }

        val safeKey = conversation.key
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .ifEmpty { "conversation" }

        var targetFile = File(outputDir, "conversation_${safeKey}.zip")
        var counter = 1
        while (targetFile.exists()) {
            targetFile = File(outputDir, "conversation_${safeKey}_$counter.zip")
            counter++
        }

        val tempFile = File(
            outputDir,
            "${targetFile.name}.tmp_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        )

        var success = false
        try {
            FileOutputStream(tempFile).buffered().use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val buffer = ByteArray(64 * 1024)
                    for (file in conversation.files) {
                        val entry = ZipEntry(file.name)
                        entry.time = file.lastModified()
                        zos.putNextEntry(entry)
                        FileInputStream(file).buffered().use { fis ->
                            var bytesRead: Int
                            while (fis.read(buffer).also { bytesRead = it } != -1) {
                                zos.write(buffer, 0, bytesRead)
                            }
                        }
                        zos.closeEntry()
                    }
                    zos.finish()
                }
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = false)
                tempFile.delete()
            }
            success = true
            return targetFile
        } finally {
            if (!success && tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
