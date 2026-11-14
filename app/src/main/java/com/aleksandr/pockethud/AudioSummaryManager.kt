package com.aleksandr.pockethud

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class SavedAudioSummary(
    val summaryFile: File,
    val sourceFileName: String,
    val sourceSha256: String,
    val rangeFormatted: String,
    val startSeconds: Double,
    val endSeconds: Double,
    val startFrame: Long = 0L,
    val endFrame: Long = 0L,
    val model: String,
    val createdAt: String,
    val summaryMarkdown: String,
    val cleanupWarning: String? = null
)

class AudioSummaryManager(
    private val api: AudioSummaryApi = AudioSummaryApi(),
    private val wavAudioClip: WavAudioClip = WavAudioClip,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val atomicMover: (Path, Path) -> Unit = { src, dst ->
        Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
) : AutoCloseable {
    companion object {
        @Volatile
        private var defaultInstance: AudioSummaryManager? = null

        fun getInstance(): AudioSummaryManager {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: AudioSummaryManager().also { defaultInstance = it }
            }
        }

        fun getInstanceOrNull(): AudioSummaryManager? = defaultInstance
    }

    private val currentRequestId = AtomicLong(0)
    private val stateLock = Any()
    @Volatile private var isBusy = false
    private var activeCall: okhttp3.Call? = null

    // Visible for test synchronization
    var preCommitHook: (() -> Unit)? = null

    fun isGenerating(): Boolean = isBusy

    /**
     * Cancels any active summary generation request.
     * Aborts active OkHttp Call, invalidates request identity, and resets busy state.
     */
    fun cancelCurrent() {
        synchronized(stateLock) {
            currentRequestId.incrementAndGet()
            try {
                activeCall?.cancel()
            } catch (ignored: Exception) {}
            activeCall = null
            isBusy = false
        }
    }

    override fun close() {
        cancelCurrent()
        executor.shutdownNow()
    }

    /**
     * Executes audio summary generation in background executor.
     * Enforces single active request, request identity guard, temp file cleanup, and sidecar persistence.
     */
    fun requestSummary(
        wavFile: File,
        startSeconds: Double,
        endSeconds: Double,
        apiKey: String,
        onStart: () -> Unit = {},
        onProgress: (String) -> Unit = {},
        onSuccess: (SavedAudioSummary) -> Unit,
        onError: (String) -> Unit
    ) {
        val reqId: Long
        synchronized(stateLock) {
            if (isBusy) {
                onError("Запрос уже выполняется. Дождитесь завершения или отмените текущий запрос.")
                return
            }
            isBusy = true
            reqId = currentRequestId.incrementAndGet()
        }

        onStart()

        executor.execute {
            var tempSlice: File? = null
            try {
                if (currentRequestId.get() != reqId) return@execute

                onProgress("Подготовка аудио...")
                val sliceDest = File.createTempFile("pockethud_slice_${reqId}_", ".wav")
                tempSlice = sliceDest
                wavAudioClip.extractClip(wavFile, startSeconds, endSeconds, sliceDest)

                if (currentRequestId.get() != reqId) return@execute

                val apiResult = api.generateSummary(
                    audioFile = sliceDest,
                    apiKey = apiKey,
                    onProgress = onProgress,
                    isCancelled = { currentRequestId.get() != reqId },
                    onCallCreated = { call ->
                        synchronized(stateLock) {
                            if (currentRequestId.get() == reqId) {
                                activeCall = call
                            } else {
                                call.cancel()
                            }
                        }
                    }
                )

                if (currentRequestId.get() != reqId) return@execute

                when (apiResult) {
                    is AudioSummaryResult.Success -> {
                        preCommitHook?.invoke()

                        val saved = commitSummary(
                            reqId = reqId,
                            wavFile = wavFile,
                            startSeconds = startSeconds,
                            endSeconds = endSeconds,
                            summaryMarkdown = apiResult.summaryMarkdown,
                            cleanupWarning = apiResult.cleanupWarning,
                            model = AudioSummaryApi.MODEL_NAME
                        )

                        if (saved != null && currentRequestId.get() == reqId) {
                            onSuccess(saved)
                        }
                    }
                    is AudioSummaryResult.Error -> {
                        if (currentRequestId.get() == reqId) {
                            val msg = if (apiResult.cleanupWarning != null) {
                                "${apiResult.userMessage}\n\n[${apiResult.cleanupWarning}]"
                            } else {
                                apiResult.userMessage
                            }
                            onError(msg)
                        }
                    }
                }
            } catch (e: Exception) {
                if (currentRequestId.get() == reqId) {
                    onError("Ошибка обработки аудио: ${e.message}")
                }
            } finally {
                try {
                    tempSlice?.delete()
                } catch (ignored: Exception) {}
                synchronized(stateLock) {
                    if (currentRequestId.get() == reqId) {
                        isBusy = false
                        activeCall = null
                    }
                }
            }
        }
    }

    /**
     * Atomically commits summary markdown sidecar under stateLock with identity check.
     */
    private fun commitSummary(
        reqId: Long,
        wavFile: File,
        startSeconds: Double,
        endSeconds: Double,
        summaryMarkdown: String,
        cleanupWarning: String?,
        model: String
    ): SavedAudioSummary? {
        val parentDir = wavFile.parentFile ?: File(".")
        val baseName = wavFile.nameWithoutExtension
        val info = wavAudioClip.parseHeader(wavFile)
        val bounds = wavAudioClip.calculateFrameBounds(info, startSeconds, endSeconds)
        val startFrame = bounds.startFrame
        val endFrame = bounds.endFrame
        val actualStartSeconds = bounds.startSeconds
        val actualEndSeconds = bounds.endSeconds

        val startFormatted = wavAudioClip.formatSecondsToTime(actualStartSeconds)
        val endFormatted = wavAudioClip.formatSecondsToTime(actualEndSeconds)
        val rangeHuman = "$startFormatted - $endFormatted (${String.format(Locale.US, "%.1f", actualStartSeconds)}s - ${String.format(Locale.US, "%.1f", actualEndSeconds)}s)"
        val sidecarName = "${baseName}_f${startFrame}-f${endFrame}.summary.md"
        val targetFile = File(parentDir, sidecarName)

        val sha256 = wavAudioClip.computeSha256(wavFile)
        val isoDateUtc = Instant.now().toString()

        val sidecarContent = buildString {
            append("<!--\n")
            append("source: ${wavFile.name}\n")
            append("sha256: $sha256\n")
            append("range: $rangeHuman\n")
            append("startFrame: $startFrame\n")
            append("endFrame: $endFrame\n")
            append("startSeconds: $actualStartSeconds\n")
            append("endSeconds: $actualEndSeconds\n")
            append("model: $model\n")
            append("created: $isoDateUtc\n")
            if (cleanupWarning != null) {
                append("warning: $cleanupWarning\n")
            }
            append("-->\n\n")
            append(summaryMarkdown.trim())
            append("\n")
        }

        val utf8Bytes = sidecarContent.toByteArray(StandardCharsets.UTF_8)
        val tempFile = File(parentDir, "$sidecarName.tmp.${System.nanoTime()}")
        try {
            tempFile.writeBytes(utf8Bytes)

            synchronized(stateLock) {
                if (currentRequestId.get() != reqId) {
                    tempFile.delete()
                    return null
                }

                try {
                    atomicMover(tempFile.toPath(), targetFile.toPath())
                } catch (e: Exception) {
                    tempFile.delete()
                    throw IOException("Не удалось сохранить конспект в ${targetFile.name}: ${e.message}", e)
                }

                if (currentRequestId.get() != reqId) {
                    return null
                }

                return SavedAudioSummary(
                    summaryFile = targetFile,
                    sourceFileName = wavFile.name,
                    sourceSha256 = sha256,
                    rangeFormatted = rangeHuman,
                    startSeconds = actualStartSeconds,
                    endSeconds = actualEndSeconds,
                    startFrame = startFrame,
                    endFrame = endFrame,
                    model = model,
                    createdAt = isoDateUtc,
                    summaryMarkdown = summaryMarkdown.trim(),
                    cleanupWarning = cleanupWarning
                )
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * Atomically saves summary markdown sidecar file next to the original WAV file.
     * UTF-8 No-BOM, frontmatter metadata (source, range, startFrame, endFrame, sha256, model, UTC timestamp).
     * Non-destructive: fails closed without overwriting/deleting old target on error.
     */
    fun saveSummary(
        wavFile: File,
        startSeconds: Double,
        endSeconds: Double,
        summaryMarkdown: String,
        model: String = AudioSummaryApi.MODEL_NAME,
        cleanupWarning: String? = null
    ): File {
        val parentDir = wavFile.parentFile ?: File(".")
        val baseName = wavFile.nameWithoutExtension
        val info = wavAudioClip.parseHeader(wavFile)
        val bounds = wavAudioClip.calculateFrameBounds(info, startSeconds, endSeconds)
        val startFrame = bounds.startFrame
        val endFrame = bounds.endFrame
        val actualStartSeconds = bounds.startSeconds
        val actualEndSeconds = bounds.endSeconds

        val startFormatted = wavAudioClip.formatSecondsToTime(actualStartSeconds)
        val endFormatted = wavAudioClip.formatSecondsToTime(actualEndSeconds)
        val rangeHuman = "$startFormatted - $endFormatted (${String.format(Locale.US, "%.1f", actualStartSeconds)}s - ${String.format(Locale.US, "%.1f", actualEndSeconds)}s)"
        val sidecarName = "${baseName}_f${startFrame}-f${endFrame}.summary.md"
        val targetFile = File(parentDir, sidecarName)

        val sha256 = wavAudioClip.computeSha256(wavFile)
        val isoDateUtc = Instant.now().toString()

        val sidecarContent = buildString {
            append("<!--\n")
            append("source: ${wavFile.name}\n")
            append("sha256: $sha256\n")
            append("range: $rangeHuman\n")
            append("startFrame: $startFrame\n")
            append("endFrame: $endFrame\n")
            append("startSeconds: $actualStartSeconds\n")
            append("endSeconds: $actualEndSeconds\n")
            append("model: $model\n")
            append("created: $isoDateUtc\n")
            if (cleanupWarning != null) {
                append("warning: $cleanupWarning\n")
            }
            append("-->\n\n")
            append(summaryMarkdown.trim())
            append("\n")
        }

        val utf8Bytes = sidecarContent.toByteArray(StandardCharsets.UTF_8)
        val tempFile = File(parentDir, "$sidecarName.tmp.${System.nanoTime()}")
        try {
            tempFile.writeBytes(utf8Bytes)
            try {
                atomicMover(tempFile.toPath(), targetFile.toPath())
            } catch (e: Exception) {
                tempFile.delete()
                throw IOException("Не удалось сохранить конспект в ${targetFile.name}: ${e.message}", e)
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }

        return targetFile
    }

    /**
     * Loads all existing summary sidecars for the given audio file.
     * Can be called offline without API key or active network service.
     */
    fun getSavedSummaries(audioFile: File): List<SavedAudioSummary> {
        val parentDir = audioFile.parentFile ?: return emptyList()
        val baseName = audioFile.nameWithoutExtension
        val files = parentDir.listFiles { _, name ->
            name.startsWith("${baseName}_") && name.endsWith(".summary.md")
        } ?: return emptyList()

        return files.mapNotNull { file ->
            loadSummary(file)
        }.sortedByDescending { it.summaryFile.lastModified() }
    }

    /**
     * Parses a single summary sidecar file.
     */
    fun loadSummary(summaryFile: File): SavedAudioSummary? {
        if (!summaryFile.exists() || summaryFile.length() == 0L) return null
        return try {
            val text = summaryFile.readText(StandardCharsets.UTF_8)
            var source = ""
            var sha256 = ""
            var range = ""
            var startSec = 0.0
            var endSec = 0.0
            var startFrame = 0L
            var endFrame = 0L
            var model = AudioSummaryApi.MODEL_NAME
            var created = ""
            var warning: String? = null

            val commentEnd = text.indexOf("-->")
            val rawBody = if (text.startsWith("<!--") && commentEnd != -1) {
                val header = text.substring(4, commentEnd)
                for (line in header.lines()) {
                    val trimmed = line.trim()
                    val colonIdx = trimmed.indexOf(":")
                    if (colonIdx > 0) {
                        val key = trimmed.substring(0, colonIdx).trim()
                        val value = trimmed.substring(colonIdx + 1).trim()
                        when (key) {
                            "source" -> source = value
                            "sha256" -> sha256 = value
                            "range" -> range = value
                            "startFrame" -> startFrame = value.toLongOrNull() ?: 0L
                            "endFrame" -> endFrame = value.toLongOrNull() ?: 0L
                            "startSeconds" -> startSec = value.toDoubleOrNull() ?: 0.0
                            "endSeconds" -> endSec = value.toDoubleOrNull() ?: 0.0
                            "model" -> model = value
                            "created" -> created = value
                            "warning" -> warning = value
                        }
                    }
                }
                text.substring(commentEnd + 3).trim()
            } else {
                text.trim()
            }

            val markdownBody = if (rawBody.startsWith("# AI-Конспект по аудио") && rawBody.contains("\n\n")) {
                rawBody.substring(rawBody.indexOf("\n\n") + 2).trim()
            } else {
                rawBody
            }

            SavedAudioSummary(
                summaryFile = summaryFile,
                sourceFileName = source.ifEmpty { summaryFile.name },
                sourceSha256 = sha256,
                rangeFormatted = range.ifEmpty { "Весь фрагмент" },
                startSeconds = startSec,
                endSeconds = endSec,
                startFrame = startFrame,
                endFrame = endFrame,
                model = model,
                createdAt = created,
                summaryMarkdown = markdownBody,
                cleanupWarning = warning
            )
        } catch (e: Exception) {
            null
        }
    }
}
