package com.aleksandr.pockethud

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dual-Track Audio Recorder (interfaces.md §2.1)
 *
 * Владеет: Сквозной фоновой записью чистого аудио диалога в формате .m4a (AAC, 44.1 kHz, mono)
 * в директорию context.getExternalFilesDir("audio_records").
 *
 * Инварианты:
 * - Аудиофайлы сохраняются бессрочно и не удаляются (R10).
 * - Безопасная инициализация: отлов исключений прав доступа без сбоя приложения (тестовый шов).
 */
class AudioRecordingManager {
    companion object {
        private const val TAG = "AudioRecordingManager"
        private const val DIR_NAME = "audio_records"

        private val defaultInstance = AudioRecordingManager()
        fun getInstance(): AudioRecordingManager = defaultInstance

        fun startRecording(context: Context, sessionId: String): File? = defaultInstance.startRecording(context, sessionId)
        fun stopRecording(): File? = defaultInstance.stopRecording()
        fun isRecording(): Boolean = defaultInstance.isRecording()
        fun getAudioFile(sessionId: String): File? = defaultInstance.getAudioFile(sessionId)
        fun getAudioFile(context: Context, sessionId: String): File? = defaultInstance.getAudioFile(context, sessionId)
        fun getRecordsDir(context: Context): File = defaultInstance.getRecordsDir(context)
        fun getAllAudioRecords(context: Context): List<File> = defaultInstance.getAllAudioRecords(context)
    }

    @Volatile
    private var isRecordingActive = false
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var lastSessionId: String? = null
    private var lastAppContext: Context? = null

    fun getRecordsDir(context: Context): File {
        val dir = context.getExternalFilesDir(DIR_NAME) ?: File(context.filesDir, DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    @Synchronized
    fun startRecording(context: Context, sessionId: String): File? {
        lastAppContext = context.applicationContext
        lastSessionId = sessionId
        if (isRecordingActive) {
            Log.w(TAG, "Audio recording already active; stopping previous session before starting new one.")
            stopRecording()
        }

        return try {
            val dir = getRecordsDir(context)
            val cleanSessionId = sessionId.trim().ifEmpty {
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            }
            val fileName = if (cleanSessionId.endsWith(".m4a", ignoreCase = true)) {
                cleanSessionId
            } else {
                "record_${cleanSessionId}.m4a"
            }
            val targetFile = File(dir, fileName)

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(64000)
            recorder.setAudioChannels(1)
            recorder.setOutputFile(targetFile.absolutePath)
            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            currentFile = targetFile
            isRecordingActive = true
            Log.i(TAG, "AudioRecordingManager: Started recording to ${targetFile.absolutePath}")
            targetFile
        } catch (e: Throwable) {
            Log.e(TAG, "AudioRecordingManager: Failed to start recording: ${e.message}", e)
            try {
                mediaRecorder?.release()
            } catch (ignored: Exception) {}
            mediaRecorder = null
            isRecordingActive = false
            currentFile = null
            null
        }
    }

    @Synchronized
    fun stopRecording(): File? {
        val file = currentFile
        if (!isRecordingActive && mediaRecorder == null) {
            return file
        }

        try {
            mediaRecorder?.let {
                try {
                    it.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecordingManager: Error stopping MediaRecorder: ${e.message}")
                }
                it.release()
            }
            Log.i(TAG, "AudioRecordingManager: Stopped recording. File saved: ${file?.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "AudioRecordingManager: Exception during stopRecording: ${e.message}", e)
        } finally {
            mediaRecorder = null
            isRecordingActive = false
        }
        return file
    }

    fun isRecording(): Boolean = isRecordingActive

    fun getAudioFile(sessionId: String): File? {
        val ctx = lastAppContext
        if (ctx != null) {
            return getAudioFile(ctx, sessionId)
        }
        if (currentFile != null && lastSessionId == sessionId) {
            return currentFile
        }
        return null
    }

    fun getAudioFile(context: Context, sessionId: String): File? {
        val dir = getRecordsDir(context)
        val clean = sessionId.trim()
        val candidates = listOf(
            File(dir, clean),
            File(dir, "$clean.m4a"),
            File(dir, "record_$clean.m4a"),
            File(dir, "record_$clean")
        )
        for (f in candidates) {
            if (f.exists()) return f
        }
        return dir.listFiles()?.firstOrNull { it.name.contains(clean, ignoreCase = true) }
    }

    fun getAllAudioRecords(context: Context): List<File> {
        val dir = getRecordsDir(context)
        return dir.listFiles { _, name -> name.endsWith(".m4a", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}
