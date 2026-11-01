package com.aleksandr.pockethud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class MicSource {
    SMARTPHONE, // 📱 Встроенный микрофон смартфона (AudioSource.MIC, Hardware AGC/NS, Soft-knee DSP)
    BLUETOOTH   // 🎧 Микрофон гарнитуры (VOICE_COMMUNICATION, Bluetooth SCO)
}

data class DialogueTurn(
    val id: Long = System.currentTimeMillis(),
    var italianText: String = "",
    var russianText: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Потоковый очиститель тегов Gemini Live.
 * Буферизирует неполные теги на стыках WebSocket чанков (например, "<no " и "speech>{pause}"),
 * гарантируя 100% удаление служебных маркеров из вывода UI и уведомлений часов.
 */
class StreamingTagCleaner {
    private val buffer = StringBuilder()
    private var inThought = false

    @Synchronized
    fun clean(chunk: String): String {
        if (chunk.isEmpty()) return ""
        buffer.append(chunk)

        val output = StringBuilder()
        var i = 0
        val len = buffer.length

        while (i < len) {
            if (inThought) {
                val closeIdx = buffer.indexOf("</thought>", i, ignoreCase = true)
                if (closeIdx != -1) {
                    inThought = false
                    i = closeIdx + 10 // length of "</thought>"
                } else {
                    val keepLen = minOf(buffer.length - i, 12)
                    val remaining = buffer.substring(buffer.length - keepLen)
                    buffer.setLength(0)
                    buffer.append(remaining)
                    return sanitizeText(output.toString())
                }
            } else {
                val ch = buffer[i]
                if (ch == '<') {
                    if (buffer.startsWith("<thought>", i, ignoreCase = true) || buffer.startsWith("<thought", i, ignoreCase = true)) {
                        val closeBracket = buffer.indexOf('>', i)
                        if (closeBracket != -1) {
                            inThought = true
                            i = closeBracket + 1
                            continue
                        } else {
                            break
                        }
                    }

                    val closeBracket = buffer.indexOf('>', i)
                    if (closeBracket != -1) {
                        i = closeBracket + 1
                        continue
                    } else {
                        break
                    }
                } else if (ch == '{') {
                    val closeBrace = buffer.indexOf('}', i)
                    if (closeBrace != -1) {
                        i = closeBrace + 1
                        continue
                    } else {
                        break
                    }
                } else {
                    output.append(ch)
                    i++
                }
            }
        }

        if (i < buffer.length) {
            val tail = buffer.substring(i)
            buffer.setLength(0)
            if (tail.length < 500) {
                buffer.append(tail)
            }
        } else {
            buffer.setLength(0)
        }

        return sanitizeText(output.toString())
    }

    private fun sanitizeText(str: String): String {
        var s = str.replace("<no speech detected>", "", ignoreCase = true)
        s = s.replace("<no speech>", "", ignoreCase = true)
        s = s.replace(Regex("""\s+"""), " ")
        return s.trim()
    }

    @Synchronized
    fun reset() {
        buffer.setLength(0)
        inThought = false
    }
}

class PocketService : Service() {

    private val tag = "PocketHUD"
    private var isRunning = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // Аппаратные эффекты Android DSP
    private var hwAgc: AutomaticGainControl? = null
    private var hwNs: NoiseSuppressor? = null

    // Сессионный хэндл Google Live (Session Resumption across 10-15 min rotations)
    @Volatile private var resumptionHandle: String? = null

    // Флаг готовности WebSocket сессии к приему аудио (защита от отправки до setupComplete)
    @Volatile private var isSetupComplete = false

    // Очереди и байты для Audio Latency Drift Catch-Up (24 кГц 16-бит mono = 48000 байт/сек)
    private val queuedPlaybackBytes = AtomicLong(0L)

    // Потоковые очистители неполных тегов
    private val inputTagCleaner = StreamingTagCleaner()
    private val outputTagCleaner = StreamingTagCleaner()

    // Потоки
    private var recordThread: Thread? = null
    private var sendThread: Thread? = null
    private var playbackThread: Thread? = null

    // Очереди: захват микрофона и воспроизведение аудио
    private val audioSendQueue = LinkedBlockingQueue<ByteArray>(60)
    private val audioPlaybackQueue = LinkedBlockingQueue<ByteArray>()

    // Телеметрия и сводка диагностики (Volatile для потокобезопасности между потоками mic, send, okhttp и UI)
    @Volatile private var chunksSent = 0L
    @Volatile private var bytesSent = 0L
    @Volatile private var micPeakAmplitude = 0 // 0..100%
    @Volatile private var sessionMaxAmplitude = 0
    @Volatile private var lastAudioReadResult = 0
    @Volatile private var audioReadErrors = 0L
    @Volatile private var serverMessagesReceived = 0L
    @Volatile private var serverAudioChunksReceived = 0L
    @Volatile private var serverTextChunksReceived = 0L
    @Volatile private var lastServerText = ""
    @Volatile private var lastRecognizedInput = ""
    @Volatile private var lastError: String? = null
    @Volatile private var connectionState = "DISCONNECTED"
    @Volatile private var activeAudioSource = "NONE"
    @Volatile private var serviceStartTime = 0L
    private val recentTranslations = StringBuilder()

    // Состояние переподключения и троттлинга
    @Volatile private var activeApiKey: String = ""
    @Volatile private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val mainHandler = Handler(Looper.getMainLooper())

    // Строгий троттлинг обновлений уведомлений для часов Xiaomi Watch 2 (Wear OS 5):
    // не чаще 1 раза в 2.5–3.0 секунды или на границах предложений (. ! ?)
    @Volatile private var lastNotificationTime = 0L
    @Volatile private var pendingNotificationLatest: String? = null
    @Volatile private var pendingNotificationHistory: String? = null
    @Volatile private var lastPostedSubtitleText: String = ""
    @Volatile private var lastPostedSubtitleHistory: String = ""
    private val notificationFlushRunnable = Runnable { flushPendingNotification() }

    private val tagRegex = Regex("""<thought>.*?</thought>|<[^>]+>|\{[^}]+\}""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    companion object {
        const val CHANNEL_SERVICE_ID = "pocket_hud_service_v2"
        const val CHANNEL_SUBTITLES_ID = "pocket_hud_subtitles_v2"
        const val NOTIFICATION_ID = 1001
        const val SUBTITLE_NOTIFICATION_ID = 1002

        const val EXTRA_API_KEY = "extra_api_key"
        const val EXTRA_IS_MUTED = "extra_is_muted"
        const val EXTRA_MIC_SOURCE = "extra_mic_source"

        var isServiceActive = false

        @Volatile
        var isAudioMuted: Boolean = false
            set(value) {
                field = value
                currentInstance?.applyMuteState(value)
            }

        @Volatile
        var selectedMicSource: MicSource = MicSource.SMARTPHONE
            set(value) {
                field = value
                currentInstance?.applyMicSourceChange(value)
            }

        // Коллбэки в UI
        var onStatusChanged: ((String) -> Unit)? = null
        var onTextReceived: ((String) -> Unit)? = null
        var onInputTextReceived: ((String) -> Unit)? = null
        var onDialogueTurnUpdated: ((turnIndex: Int, turn: DialogueTurn, isNew: Boolean) -> Unit)? = null
        var onTelemetryUpdated: ((micAmp: Int, chunks: Long, responses: Long) -> Unit)? = null

        // Диалог текущей сессии (Google Translate Conversation Mode)
        private val sessionDialogueTurns = ArrayList<DialogueTurn>()

        fun getSessionDialogueTurns(): List<DialogueTurn> {
            synchronized(sessionDialogueTurns) {
                return sessionDialogueTurns.map { it.copy() }
            }
        }

        fun clearDialogueTurns() {
            synchronized(sessionDialogueTurns) {
                sessionDialogueTurns.clear()
            }
        }

        // Буферы текущей активной сессии для восстановления в UI при разблокировке экрана
        private const val MAX_SESSION_BUILDER_LENGTH = 30000
        private val sessionItalianBuilder = StringBuilder()
        private val sessionRussianBuilder = StringBuilder()

        fun getSessionItalianText(): String = synchronized(sessionItalianBuilder) { sessionItalianBuilder.toString() }
        fun getSessionRussianText(): String = synchronized(sessionRussianBuilder) { sessionRussianBuilder.toString() }
        fun clearSessionTexts() {
            synchronized(sessionItalianBuilder) { sessionItalianBuilder.setLength(0) }
            synchronized(sessionRussianBuilder) { sessionRussianBuilder.setLength(0) }
            synchronized(sessionDialogueTurns) { sessionDialogueTurns.clear() }
        }
        fun clearItalianSessionText() {
            synchronized(sessionItalianBuilder) { sessionItalianBuilder.setLength(0) }
        }
        fun clearRussianSessionText() {
            synchronized(sessionRussianBuilder) { sessionRussianBuilder.setLength(0) }
        }

        fun appendToSessionBuilder(builder: StringBuilder, text: String) {
            synchronized(builder) {
                if (builder.isNotEmpty() && !builder.endsWith(" ") && !text.startsWith(" ")) {
                    builder.append(" ")
                }
                builder.append(text)
                if (builder.length > MAX_SESSION_BUILDER_LENGTH) {
                    val cutIndex = builder.indexOf(" ", builder.length - 20000)
                    if (cutIndex != -1) {
                        builder.delete(0, cutIndex + 1)
                    } else {
                        builder.delete(0, builder.length - 20000)
                    }
                }
            }
        }

        @Volatile
        var currentInstance: PocketService? = null

        @Volatile
        var lastDiagnosticSnapshot: String = ""

        fun getLogFile(context: Context): File {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            return File(dir, "pocket_hud_session.log")
        }

        fun getDiagnosticSummary(): String {
            val inst = currentInstance
            if (inst != null) {
                val snapshot = inst.buildDiagnosticSnapshot()
                lastDiagnosticSnapshot = snapshot
                return snapshot
            }
            if (lastDiagnosticSnapshot.isNotBlank()) {
                return lastDiagnosticSnapshot
            }
            return "=== POCKET HUD СВОДКА ДИАГНОСТИКИ ===\nВерсия: v1.0.22\nСтатус: СЕРВИС ОСТАНОВЛЕН (Сессия ещё не запускалась)\nРежим микрофона: $selectedMicSource\nРежим звука: ${if (isAudioMuted) "🔇 ВЫКЛЮЧЕН" else "🔊 ВКЛЮЧЕН"}\n======================================"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        createNotificationChannels()
        logEvent("SERVICE_CREATE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra(EXTRA_API_KEY) ?: ""
        if (apiKey.isBlank()) {
            onStatusChanged?.invoke("🔴 Ошибка: API ключ пуст")
            stopSelf()
            return START_NOT_STICKY
        }

        val startMuted = intent?.getBooleanExtra(EXTRA_IS_MUTED, isAudioMuted) ?: isAudioMuted
        isAudioMuted = startMuted

        val micExtra = intent?.getStringExtra(EXTRA_MIC_SOURCE)
        if (micExtra != null) {
            selectedMicSource = try {
                MicSource.valueOf(micExtra)
            } catch (e: Exception) {
                MicSource.BLUETOOTH
            }
        }

        activeApiKey = apiKey
        reconnectAttempts = 0
        currentInstance = this
        clearSessionTexts()
        serviceStartTime = System.currentTimeMillis()
        connectionState = "STARTING"

        logEvent("SERVICE_START", "(mic=$selectedMicSource, isAudioMuted=$isAudioMuted)")

        val notification = buildNotification("Карманный суфлёр активен (экран можно выключить)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Немедленно выводим карточку субтитров для часов Xiaomi Watch 2 (Wear OS 5)
        updateOngoingNotification(
            "🔵 Подключение...",
            "PocketHUD v1.0.22 активен. Ожидание подключения к Gemini Live API..."
        )

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketHUD::WakeLock").apply {
            acquire(24 * 60 * 60 * 1000L)
        }

        isRunning = true
        isServiceActive = true

        setupAudioRouting()
        onStatusChanged?.invoke("🔵 Подключение к Google Gemini Live API...")
        initAudioOutput()
        connectWebSocket(apiKey)

        return START_NOT_STICKY
    }

    @Synchronized
    fun logEvent(event: String, details: String = "") {
        try {
            val timestamp = logDateFormat.format(Date())
            val line = if (details.isBlank()) "[$timestamp] $event\n" else "[$timestamp] $event $details\n"
            val file = getLogFile(this)

            // Защита от переполнения: ротация при размере > 3 МБ
            if (file.exists() && file.length() > 3 * 1024 * 1024L) {
                val oldFile = File(file.parentFile, "pocket_hud_session.old.log")
                if (oldFile.exists()) oldFile.delete()
                file.renameTo(oldFile)
            }

            file.appendText(line, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(tag, "Ошибка записи лога: ${e.message}")
        }
    }

    fun applyMuteState(muted: Boolean) {
        logEvent("MUTE_STATE_CHANGED", "(isMuted=$muted)")
        if (muted) {
            audioPlaybackQueue.clear()
            queuedPlaybackBytes.set(0L)
            try {
                audioTrack?.pause()
                audioTrack?.flush()
            } catch (e: Exception) {
                Log.e(tag, "Ошибка паузы AudioTrack при отключении звука: ${e.message}")
            }
        } else {
            try {
                audioTrack?.play()
            } catch (e: Exception) {
                Log.e(tag, "Ошибка возобновления AudioTrack при включении звука: ${e.message}")
            }
        }

        // Обновляем текст постоянного сервиса при переключении звука
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val modeStr = if (muted) "Карманный суфлёр (🔇 Режим только субтитров)" else "Карманный суфлёр (🔊 Звук в наушник)"
            val updatedFg = buildNotification(modeStr)
            nm?.notify(NOTIFICATION_ID, updatedFg)
        } catch (e: Exception) {
            Log.w(tag, "Ошибка обновления уведомления при смене mute: ${e.message}")
        }
    }

    fun applyMicSourceChange(newSource: MicSource) {
        logEvent("MIC_SOURCE_CHANGE_REQUESTED", "newSource=$newSource")
        if (!isRunning) return

        Thread {
            releaseHardwareEffects()
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.w(tag, "Ошибка остановки AudioRecord: ${e.message}")
            }
            audioRecord = null

            setupAudioRouting()

            val sampleRate = 16000
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize * 4, sampleRate * 2 * 2)
            val (record, sourceName) = createAudioRecord(sampleRate, bufferSize)
            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord = record
                activeAudioSource = sourceName
                try {
                    record.startRecording()
                    logEvent("MIC_SOURCE_RESTARTED", "source=$activeAudioSource")
                } catch (e: Exception) {
                    logEvent("MIC_SOURCE_START_FAILED", "${e.message}")
                }
            } else {
                logEvent("MIC_SOURCE_INIT_FAILED")
            }
        }.start()
    }

    private fun triggerFailureHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 200, 100, 200), -1)
            }
        } catch (e: Exception) {
            Log.w(tag, "Не удалось выполнить виброотклик: ${e.message}")
        }
    }

    private fun cleanServerTags(raw: String): String {
        return outputTagCleaner.clean(raw)
    }

    private fun scheduleNotificationUpdate(latestText: String, fullHistory: String) {
        pendingNotificationLatest = latestText
        pendingNotificationHistory = fullHistory

        val now = System.currentTimeMillis()
        val elapsed = now - lastNotificationTime

        val trimmed = latestText.trim()
        val isSentenceBoundary = trimmed.isNotEmpty() && (
            trimmed.endsWith(".") || trimmed.endsWith("!") ||
            trimmed.endsWith("?") || trimmed.endsWith(";") ||
            trimmed.endsWith(":") || trimmed.endsWith("\n")
        )

        // Строгий троттлинг для Xiaomi Watch 2: на границе фразы обновляем не ранее чем через 2.0 сек
        if (isSentenceBoundary && elapsed >= 2000L) {
            mainHandler.removeCallbacks(notificationFlushRunnable)
            flushPendingNotification()
        } else {
            // При непрерывном потоке слов обновляем не чаще 1 раза в 2.8 сек
            val targetDelay = if (isSentenceBoundary) {
                maxOf(2000L - elapsed, 300L)
            } else {
                maxOf(2800L - elapsed, 2000L)
            }
            mainHandler.removeCallbacks(notificationFlushRunnable)
            mainHandler.postDelayed(notificationFlushRunnable, targetDelay)
        }
    }

    private fun flushPendingNotification() {
        val latest = pendingNotificationLatest ?: return
        val history = pendingNotificationHistory ?: latest

        // Защита от повторного вызова notify() при идентичном тексте (исключает сброс скролла на часах)
        if (latest == lastPostedSubtitleText && history == lastPostedSubtitleHistory) {
            return
        }

        lastNotificationTime = System.currentTimeMillis()
        lastPostedSubtitleText = latest
        lastPostedSubtitleHistory = history

        updateOngoingNotification(latest, history)
    }

    private fun recordInputSpeech(cleanedInput: String) {
        lastRecognizedInput = cleanedInput
        Log.d(tag, "Распознана речь (IT/EN): $cleanedInput")
        logEvent("INPUT_SPEECH:", "\"$lastRecognizedInput\"")
        appendToSessionBuilder(sessionItalianBuilder, cleanedInput)
        onInputTextReceived?.invoke(cleanedInput)

        // Обновление диалога в режиме Google Translate Conversation
        synchronized(sessionDialogueTurns) {
            val lastTurn = sessionDialogueTurns.lastOrNull()
            val now = System.currentTimeMillis()
            // Если в последней реплике еще нет перевода и прошло меньше 8 секунд - дополняем ее
            if (lastTurn != null && lastTurn.russianText.isBlank() && (now - lastTurn.timestamp < 8000L)) {
                if (lastTurn.italianText.isNotBlank() && !lastTurn.italianText.endsWith(" ") && !cleanedInput.startsWith(" ")) {
                    lastTurn.italianText += " $cleanedInput"
                } else {
                    lastTurn.italianText += cleanedInput
                }
                val idx = sessionDialogueTurns.size - 1
                val turnCopy = lastTurn.copy()
                onDialogueTurnUpdated?.invoke(idx, turnCopy, false)
            } else {
                // Создаем новую пару диалога
                val newTurn = DialogueTurn(
                    italianText = cleanedInput,
                    russianText = "",
                    timestamp = now
                )
                sessionDialogueTurns.add(newTurn)
                if (sessionDialogueTurns.size > 200) {
                    sessionDialogueTurns.removeAt(0)
                }
                val idx = sessionDialogueTurns.size - 1
                val turnCopy = newTurn.copy()
                onDialogueTurnUpdated?.invoke(idx, turnCopy, true)
            }
        }
    }

    private fun recordTranslationOutput(cleanedText: String) {
        serverTextChunksReceived++
        lastServerText = cleanedText
        val origPrompt = if (lastRecognizedInput.isBlank()) "..." else lastRecognizedInput
        logEvent("TRANSLATION:", "ORIG=\"$origPrompt\" -> RU=\"$lastServerText\"")

        recentTranslations.append(cleanedText).append(" ")
        if (recentTranslations.length > 800) {
            val cutIndex = recentTranslations.indexOf(". ", recentTranslations.length - 450)
            if (cutIndex != -1) {
                recentTranslations.delete(0, cutIndex + 2)
            } else {
                recentTranslations.delete(0, recentTranslations.length - 450)
            }
        }

        appendToSessionBuilder(sessionRussianBuilder, cleanedText)
        onTextReceived?.invoke(cleanedText)

        // Обновление диалога в режиме Google Translate Conversation
        synchronized(sessionDialogueTurns) {
            val lastTurn = sessionDialogueTurns.lastOrNull()
            if (lastTurn != null && (lastTurn.russianText.isBlank() || lastTurn.italianText.isNotBlank())) {
                if (lastTurn.russianText.isNotBlank() && !lastTurn.russianText.endsWith(" ") && !cleanedText.startsWith(" ")) {
                    lastTurn.russianText += " $cleanedText"
                } else {
                    lastTurn.russianText += cleanedText
                }
                val idx = sessionDialogueTurns.size - 1
                val turnCopy = lastTurn.copy()
                onDialogueTurnUpdated?.invoke(idx, turnCopy, false)
            } else {
                val newTurn = DialogueTurn(
                    italianText = "",
                    russianText = cleanedText,
                    timestamp = System.currentTimeMillis()
                )
                sessionDialogueTurns.add(newTurn)
                if (sessionDialogueTurns.size > 200) {
                    sessionDialogueTurns.removeAt(0)
                }
                val idx = sessionDialogueTurns.size - 1
                val turnCopy = newTurn.copy()
                onDialogueTurnUpdated?.invoke(idx, turnCopy, true)
            }
        }

        scheduleNotificationUpdate(cleanedText, recentTranslations.toString().trim())
    }

    private fun setupAudioRouting() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            if (selectedMicSource == MicSource.BLUETOOTH) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = false

                var selectedDeviceName = "DEFAULT_COMMUNICATION"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val btDevice = devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_HEARING_AID
                    }
                    if (btDevice != null) {
                        audioManager.setCommunicationDevice(btDevice)
                        selectedDeviceName = "${btDevice.productName} (${btDevice.type})"
                        Log.d(tag, "Установлено устройство связи: $selectedDeviceName")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                    selectedDeviceName = "Bluetooth SCO (Legacy)"
                }

                logEvent("AUDIO_ROUTING_SETUP", "BLUETOOTH: $selectedDeviceName")
            } else {
                // Режим SMARTPHONE: встроенный микрофон телефона с широкой диаграммой для комнаты/зала
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    if (audioManager.isBluetoothScoOn) {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                    }
                }
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = false
                logEvent("AUDIO_ROUTING_SETUP", "SMARTPHONE_MIC (MODE_NORMAL)")
            }
        } catch (e: Exception) {
            Log.w(tag, "Предупреждение при настройке аудиомаршрутизации: ${e.message}")
            logEvent("AUDIO_ROUTING_WARN", "${e.message}")
        }
    }

    private fun restoreAudioRouting() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoOn) {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                }
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            logEvent("AUDIO_ROUTING_RESTORED")
        } catch (e: Exception) {
            Log.w(tag, "Ошибка восстановления аудиомаршрутизации: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return

            // Канал 1: Фоновая служба
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "Pocket HUD — Фоновый сервис",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Статус фоновой работы переводчика PocketHUD"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(serviceChannel)

            // Канал 2: Субтитры для экрана и часов (Xiaomi Watch 2, Wear OS 5)
            val subtitlesChannel = NotificationChannel(
                CHANNEL_SUBTITLES_ID,
                "Pocket HUD — Субтитры (Часы и Экран)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Трансляция живого перевода на часы Xiaomi Watch 2 (Wear OS 5)"
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(subtitlesChannel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wearableExtender = NotificationCompat.WearableExtender()
            .setContentIntentAvailableOffline(false)
            .setHintShowBackgroundOnly(false)

        return NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("PocketHUD — Ушной суфлёр v1.0.22")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .extend(wearableExtender)
            .build()
    }

    private fun updateOngoingNotification(latestText: String, fullHistory: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return

        lastPostedSubtitleText = latestText
        lastPostedSubtitleHistory = fullHistory

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wearableExtender = NotificationCompat.WearableExtender()
            .setContentIntentAvailableOffline(false)
            .setHintShowBackgroundOnly(false)

        // 1. Обновляем статус постоянного сервиса
        val fgNotification = NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("PocketHUD — Перевод")
            .setContentText(latestText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .extend(wearableExtender)
            .build()
        nm.notify(NOTIFICATION_ID, fgNotification)

        // 2. Карточка субтитров для часов Xiaomi Watch 2 (Wear OS 5)
        // setOngoing(false) критично: Wear OS 5 и Mi Fitness скрывают ongoing карточки из карусели!
        val subtitleNotification = NotificationCompat.Builder(this, CHANNEL_SUBTITLES_ID)
            .setContentTitle("PocketHUD (Субтитры)")
            .setContentText(latestText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullHistory))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(false) // КРИТИЧНО для Wear OS 5!
            .setOnlyAlertOnce(true) // Без повторной вибрации при каждом слове
            .setLocalOnly(false) // Разрешено зеркалирование на часы
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .extend(wearableExtender)
            .build()
        nm.notify(SUBTITLE_NOTIFICATION_ID, subtitleNotification)
    }

    fun buildDiagnosticSnapshot(): String {
        val uptimeSec = if (serviceStartTime > 0) (System.currentTimeMillis() - serviceStartTime) / 1000 else 0
        val mm = uptimeSec / 60
        val ss = uptimeSec % 60
        val logFile = getLogFile(this)
        val qBytes = queuedPlaybackBytes.get()
        return buildString {
            appendLine("=== POCKET HUD СВОДКА ДИАГНОСТИКИ ===")
            appendLine("Версия: v1.0.22")
            appendLine("Время работы: %02d:%02d".format(mm, ss))
            appendLine("Статус сокета: $connectionState")
            appendLine("Сессионная ротация (Resumption): ${if (resumptionHandle.isNullOrBlank()) "нет" else "активна (${resumptionHandle?.take(16)}...)"}")
            appendLine("Модель: models/gemini-3.8-live")
            appendLine("Режим микрофона: $selectedMicSource (${if (selectedMicSource == MicSource.SMARTPHONE) "Встроенный + Hardware DSP + Soft-knee AGC" else "Гарнитура / Bluetooth SCO"})")
            appendLine("Hardware DSP: AGC=${hwAgc?.enabled ?: false}, NS=${hwNs?.enabled ?: false}")
            appendLine("Режим звука: ${if (isAudioMuted) "🔇 ВЫКЛЮЧЕН (Только субтитры)" else "🔊 ВКЛЮЧЕН (Голос в наушник)"}")
            appendLine("Источник микрофона: $activeAudioSource (16 кГц Mono PCM16)")
            appendLine("Языки распознавания: [it-IT, en-US] -> RU")
            appendLine("Попыток переподключения: $reconnectAttempts/$maxReconnectAttempts")
            appendLine("--- ОЧЕРЕДИ АУДИО ---")
            appendLine("Очередь отправки mic (SendQueue): ${audioSendQueue.size} чанков")
            appendLine("Очередь вывода (PlaybackQueue): ${audioPlaybackQueue.size} чанков ($qBytes байт, ~${"%.2f".format(Locale.US, qBytes / 48000.0)}с)")
            appendLine("--- МИКРОФОН ---")
            appendLine("Текущая громкость: $micPeakAmplitude% (Макс за сессию: $sessionMaxAmplitude%)")
            appendLine("Последний read результат: $lastAudioReadResult")
            appendLine("Ошибок чтения mic: $audioReadErrors")
            appendLine("Отправлено чанков: $chunksSent ($bytesSent байт)")
            appendLine("--- GEMINI LIVE СЕРВЕР ---")
            appendLine("Сообщений получено: $serverMessagesReceived")
            appendLine("Аудио-ответов (звук в ухо): $serverAudioChunksReceived")
            appendLine("Текстовых ответов (HUD/Часы): $serverTextChunksReceived")
            appendLine("Последний распознанный (IT/EN): ${if (lastRecognizedInput.isBlank()) "нет" else "\"$lastRecognizedInput\""}")
            appendLine("Последний текст RU: ${if (lastServerText.isBlank()) "нет" else "\"$lastServerText\""}")
            appendLine("--- WEAR OS 5 & ЛОГИ ---")
            appendLine("Канал часов: $CHANNEL_SUBTITLES_ID (IMPORTANCE_HIGH)")
            appendLine("Файл логов: ${logFile.absolutePath} (${logFile.length()} байт)")
            appendLine("--- ОШИБКИ ---")
            appendLine("Последняя ошибка: ${lastError ?: "нет"}")
            appendLine("======================================")
        }
    }

    private fun initAudioOutput() {
        val sampleRate = 24000
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBufferSize <= 0) 4096 * 2 else minBufferSize * 2

        val audioUsage = if (selectedMicSource == MicSource.BLUETOOTH) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(audioUsage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (selectedMicSource == MicSource.SMARTPHONE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val outDevices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    val a2dp = outDevices?.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_HEARING_AID
                    }
                    if (a2dp != null) {
                        val bound = audioTrack?.setPreferredDevice(a2dp)
                        logEvent("A2DP_PREFERRED_OUTPUT_SET", "device=${a2dp.productName} (${a2dp.type}), bound=$bound")
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Ошибка назначения A2DP устройства: ${e.message}")
                }
            }

            if (!isAudioMuted) {
                audioTrack?.play()
            }
        } catch (e: Exception) {
            Log.e(tag, "Ошибка инициализации AudioTrack: ${e.message}")
            lastError = "AudioTrack init: ${e.message}"
            logEvent("AUDIOTRACK_INIT_ERROR", "${e.message}")
            return
        }

        playbackThread = Thread {
            var isSpeedBoosted = false
            while (isRunning) {
                try {
                    val chunk = audioPlaybackQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    val remainingBytes = queuedPlaybackBytes.addAndGet(-chunk.size.toLong()).coerceAtLeast(0L)

                    if (!isAudioMuted) {
                        // Audio Latency Drift Catch-Up: 24 кГц 16-бит mono = 48000 байт/сек
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val track = audioTrack
                            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                                // Если очередь > 2.5 сек (> 120,000 байт) — ускоряем воспроизведение на 15% (1.15x) для ликвидации лага
                                if (!isSpeedBoosted && remainingBytes > 120_000L) {
                                    try {
                                        val params = track.playbackParams ?: PlaybackParams()
                                        track.playbackParams = params.setSpeed(1.15f)
                                        isSpeedBoosted = true
                                        logEvent("LATENCY_CATCHUP_ENGAGED", "queuedBytes=$remainingBytes (~${"%.2f".format(Locale.US, remainingBytes / 48000.0)}s), speed=1.15x")
                                    } catch (e: Exception) {
                                        Log.w(tag, "Ошибка установки 1.15x: ${e.message}")
                                    }
                                } else if (isSpeedBoosted && remainingBytes < 38_400L) { // Когда очередь сократилась до < 0.8 сек (< 38,400 байт) — возвращаем 1.0x
                                    try {
                                        val params = track.playbackParams ?: PlaybackParams()
                                        track.playbackParams = params.setSpeed(1.0f)
                                        isSpeedBoosted = false
                                        logEvent("LATENCY_CATCHUP_NORMALIZED", "queuedBytes=$remainingBytes (~${"%.2f".format(Locale.US, remainingBytes / 48000.0)}s), speed=1.0x")
                                    } catch (e: Exception) {
                                        Log.w(tag, "Ошибка возврата 1.0x: ${e.message}")
                                    }
                                }
                            }
                        }

                        audioTrack?.write(chunk, 0, chunk.size)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(tag, "Ошибка воспроизведения аудио: ${e.message}")
                }
            }
        }
        playbackThread?.start()
    }

    private fun connectWebSocket(apiKey: String) {
        if (!isRunning) return
        connectionState = if (reconnectAttempts > 0) "RECONNECTING ($reconnectAttempts)" else "CONNECTING"
        logEvent("WEBSOCKET_CONNECTING", "(attempt=$reconnectAttempts)")

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(tag, "WebSocket открыт. Отправка Setup...")
                isSetupComplete = false
                connectionState = "OPEN (Sending Setup)"
                logEvent("WEBSOCKET_OPEN")
                onStatusChanged?.invoke("🔵 WebSocket соединён. Инициализация Gemini-3.8-Live...")
                sendSetup(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isSetupComplete = false
                val httpCode = response?.code
                if (httpCode == 401 || httpCode == 403) {
                    connectionState = "AUTH_ERROR ($httpCode)"
                    lastError = "Ошибка авторизации: HTTP $httpCode"
                    logEvent("AUTH_ERROR_FATAL", "code=$httpCode, msg=${t.message}")
                    onStatusChanged?.invoke("🔴 Ошибка авторизации (HTTP $httpCode): проверьте API ключ")
                    triggerFailureHaptic()
                    stopSelf()
                    return
                }
                handleConnectionDrop("Сбой сети: ${t.message}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket закрыт ($code: $reason)")
                isSetupComplete = false
                if (isRunning) {
                    if (code == 1000) {
                        // Google 10-15 min Session Rotation: DO NOT call stopSelf()!
                        connectionState = "RECONNECTING (Rotation)"
                        logEvent("SESSION_ROTATION_RECONNECT", "code=$code, reason=$reason, handle=$resumptionHandle")
                        onStatusChanged?.invoke("🔄 Ротация сессии Google (15 мин). Мгновенное возобновление...")
                        mainHandler.post {
                            if (isRunning && activeApiKey.isNotBlank()) {
                                try {
                                    webSocket?.cancel()
                                } catch (e: Exception) {}
                                webSocket = null
                                connectWebSocket(activeApiKey)
                            }
                        }
                    } else {
                        handleConnectionDrop("Сервер закрыл сокет ($code: $reason)")
                    }
                } else {
                    connectionState = "CLOSED ($code: $reason)"
                    logEvent("WEBSOCKET_CLOSED", "code=$code, reason=$reason")
                    onStatusChanged?.invoke("⚪ Суфлёр остановлен.")
                    stopSelf()
                }
            }
        })
    }

    private fun handleConnectionDrop(reason: String) {
        if (!isRunning) return
        Log.e(tag, "Потеря соединения: $reason")
        logEvent("CONNECTION_DROP", reason)
        triggerFailureHaptic()

        if (reconnectAttempts < maxReconnectAttempts && isRunning) {
            reconnectAttempts++
            val delaySec = minOf(reconnectAttempts * 2, 8)
            connectionState = "RECONNECTING ($reconnectAttempts/$maxReconnectAttempts in ${delaySec}s)"
            onStatusChanged?.invoke("🟠 Связь прервана ($reason). Авто-переподключение через ${delaySec}с ($reconnectAttempts/$maxReconnectAttempts)...")

            mainHandler.postDelayed({
                if (isRunning && activeApiKey.isNotBlank()) {
                    try {
                        webSocket?.cancel()
                    } catch (e: Exception) {}
                    webSocket = null
                    connectWebSocket(activeApiKey)
                }
            }, delaySec * 1000L)
        } else {
            connectionState = "FAILED (Max reconnects reached)"
            lastError = "Связь потеряна: $reason"
            logEvent("RECONNECT_FAILED_FINAL")
            onStatusChanged?.invoke("🔴 Соединение разорвано: $reason")
            stopSelf()
        }
    }

    private fun sendSetup(ws: WebSocket) {
        val setupObj = JSONObject().apply {
            put("model", "models/gemini-3.8-live")
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", "Puck")
                        })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", "You are an invisible real-time speech interpreter into Russian for Alexander living in Rome. " +
                                "CRITICAL TRANSLATION RULES: " +
                                "1. Translate IMMEDIATELY and CONTINUOUSLY sentence-by-sentence whenever you hear Italian OR English speech. " +
                                "Provide a COMPLETE sentence-by-sentence translation into Russian without omitting details, numbers, nuances, or technical context. Do not summarize or abbreviate. " +
                                "2. Output ONLY the Russian translation. " +
                                "3. NEVER converse, NEVER explain, NEVER reply in the original language, NEVER answer questions. Your ONLY job is faithful, instantaneous speech translation into Russian. " +
                                "4. Ignore background noise and street/cafe ambiance — focus on human voices. If someone speaks Russian, stay silent. Translate ALL Italian and English speech into Russian.")
                }))
            })
            put("realtimeInputConfig", JSONObject().apply {
                put("activityHandling", "NO_INTERRUPTION")
                put("automaticActivityDetection", JSONObject().apply {
                    put("disabled", false)
                    put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                    put("silenceDurationMs", 600)
                    put("prefixPaddingMs", 200)
                })
            })
            put("inputAudioTranscription", JSONObject().apply {
                put("languageCodes", JSONArray().apply {
                    put("it-IT")
                    put("en-US")
                })
            })
            put("outputAudioTranscription", JSONObject())
            val resumptionObj = JSONObject()
            val handle = resumptionHandle
            if (!handle.isNullOrBlank()) {
                resumptionObj.put("handle", handle)
            }
            put("sessionResumption", resumptionObj)
        }

        val setupJson = JSONObject().apply {
            put("setup", setupObj)
        }
        ws.send(setupJson.toString())
        logEvent("SETUP_SENT", "model=models/gemini-3.8-live, languages=[it-IT, en-US], resumed=${!resumptionHandle.isNullOrBlank()}")
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            serverMessagesReceived++
            val root = JSONObject(jsonText)

            // 1. Извлечение хэндла возобновления сессии (Session Resumption Update)
            val resumptionObj = root.optJSONObject("sessionResumptionUpdate")
            if (resumptionObj != null) {
                val handle = resumptionObj.optString("newHandle", "").ifEmpty { resumptionObj.optString("handle", "") }
                if (handle.isNotEmpty()) {
                    resumptionHandle = handle
                    logEvent("SESSION_RESUMPTION_UPDATE", "handle=$handle")
                }
            }

            // 2. Сигнал ротации goAway от сервера Google Live
            if (root.has("goAway")) {
                val goAwayObj = root.optJSONObject("goAway")
                val timeLeft = goAwayObj?.optString("timeLeft", "") ?: ""
                logEvent("SERVER_GO_AWAY", "timeLeft=$timeLeft")
                Log.w(tag, "Получен сигнал goAway от Google Live (timeLeft=$timeLeft). Планирование чистой ротации...")
                mainHandler.postDelayed({
                    if (isRunning) {
                        try {
                            webSocket?.close(1000, "Clean session rotation")
                        } catch (e: Exception) {
                            webSocket?.cancel()
                        }
                    }
                }, 1500L)
            }

            // 3. Подтверждение готовности setupComplete
            if (root.has("setupComplete")) {
                isSetupComplete = true
                Log.d(tag, "Setup завершён! Запуск микрофона...")
                connectionState = "READY (Streaming)"
                if (reconnectAttempts > 0) {
                    logEvent("RECONNECT_SUCCESS", "attempts=$reconnectAttempts")
                    reconnectAttempts = 0
                }
                logEvent("SETUP_COMPLETE", "(model=gemini-3.8-live, mic=$activeAudioSource)")
                onStatusChanged?.invoke("🟢 Соединение активно! Слушаю итальянскую и английскую речь...")
                updateOngoingNotification(
                    "🟢 Слушаю речь вокруг...",
                    "PocketHUD готов к переводу. Слушаю итальянскую и английскую речь..."
                )
                if (audioRecord == null || audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    startRecording()
                }
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            // Мгновенный Barge-in: сбрасываем звук при прерывании
            if (serverContent.optBoolean("interrupted", false)) {
                audioPlaybackQueue.clear()
                queuedPlaybackBytes.set(0L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        audioTrack?.playbackParams = audioTrack?.playbackParams?.setSpeed(1.0f) ?: PlaybackParams().setSpeed(1.0f)
                    } catch (e: Exception) {}
                }
                try {
                    audioTrack?.pause()
                    audioTrack?.flush()
                    if (!isAudioMuted) {
                        audioTrack?.play()
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Ошибка сброса AudioTrack: ${e.message}")
                }
            }

            // 4. Распознанная речь (inputAudioTranscription / inputTranscription / interimInputTranscription / userTurn)
            val inputObj = serverContent.optJSONObject("inputTranscription")
                ?: serverContent.optJSONObject("inputAudioTranscription")
                ?: serverContent.optJSONObject("interimInputTranscription")
            var inputText = inputObj?.optString("text", "") ?: ""

            // Fallback на userTurn при наличии
            if (inputText.isEmpty()) {
                val userTurn = serverContent.optJSONObject("userTurn")
                val userParts = userTurn?.optJSONArray("parts")
                if (userParts != null) {
                    val sb = StringBuilder()
                    for (i in 0 until userParts.length()) {
                        val pt = userParts.getJSONObject(i).optString("text", "")
                        if (pt.isNotEmpty()) sb.append(pt)
                    }
                    inputText = sb.toString()
                }
            }

            if (inputText.isNotEmpty()) {
                val cleanedInput = inputTagCleaner.clean(inputText)
                if (cleanedInput.isNotEmpty()) {
                    recordInputSpeech(cleanedInput)
                }
            }

            // 5. Субтитры перевода (outputAudioTranscription / outputTranscription / interimOutputTranscription)
            val outputObj = serverContent.optJSONObject("outputTranscription")
                ?: serverContent.optJSONObject("outputAudioTranscription")
                ?: serverContent.optJSONObject("interimOutputTranscription")
            val outputText = outputObj?.optString("text", "") ?: ""
            if (outputText.isNotEmpty()) {
                val cleanedOutput = outputTagCleaner.clean(outputText)
                if (cleanedOutput.isNotEmpty()) {
                    recordTranslationOutput(cleanedOutput)
                }
            }

            // 6. Аудио PCM и возможные текстовые фрагменты из modelTurn.parts
            val modelTurn = serverContent.optJSONObject("modelTurn")
            if (modelTurn != null) {
                val parts = modelTurn.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null) {
                            val base64Pcm = inlineData.optString("data", "")
                            if (base64Pcm.isNotEmpty()) {
                                serverAudioChunksReceived++
                                if (!isAudioMuted) {
                                    val pcmBytes = Base64.decode(base64Pcm, Base64.DEFAULT)
                                    audioPlaybackQueue.offer(pcmBytes)
                                    queuedPlaybackBytes.addAndGet(pcmBytes.size.toLong())
                                }
                            }
                        }
                        val text = part.optString("text", "")
                        if (text.isNotEmpty()) {
                            val cleanedText = outputTagCleaner.clean(text)
                            if (cleanedText.isNotEmpty()) {
                                recordTranslationOutput(cleanedText)
                            }
                        }
                    }
                }
            }

            onTelemetryUpdated?.invoke(micPeakAmplitude, chunksSent, serverTextChunksReceived + serverAudioChunksReceived)
        } catch (e: Exception) {
            Log.e(tag, "Ошибка парсинга: ${e.message}")
            lastError = "Парсинг ответа: ${e.message}"
            logEvent("PARSE_ERROR", "${e.message}")
        }
    }

    private fun attachHardwareEffects(sessionId: Int) {
        releaseHardwareEffects()
        try {
            if (AutomaticGainControl.isAvailable()) {
                hwAgc = AutomaticGainControl.create(sessionId)?.apply {
                    enabled = true
                    Log.d(tag, "Hardware AutomaticGainControl активирован (sessionId=$sessionId)")
                    logEvent("HW_AGC_ENABLED", "sessionId=$sessionId")
                }
            } else {
                logEvent("HW_AGC_UNAVAILABLE")
            }
        } catch (e: Exception) {
            Log.w(tag, "Ошибка активации HW AGC: ${e.message}")
        }

        try {
            if (NoiseSuppressor.isAvailable()) {
                hwNs = NoiseSuppressor.create(sessionId)?.apply {
                    enabled = true
                    Log.d(tag, "Hardware NoiseSuppressor активирован (sessionId=$sessionId)")
                    logEvent("HW_NS_ENABLED", "sessionId=$sessionId")
                }
            } else {
                logEvent("HW_NS_UNAVAILABLE")
            }
        } catch (e: Exception) {
            Log.w(tag, "Ошибка активации HW NS: ${e.message}")
        }
    }

    private fun releaseHardwareEffects() {
        try {
            hwAgc?.release()
        } catch (e: Exception) {}
        hwAgc = null
        try {
            hwNs?.release()
        } catch (e: Exception) {}
        hwNs = null
    }

    private fun applyDynamicSoftKneeAgc(buffer: ShortArray, length: Int) {
        val gain = 4.85f // +13.7 dB boost (~4.5x–5.0x) для тихой речи (<10%)
        for (i in 0 until length) {
            val s = buffer[i].toFloat() / 32768.0f
            val scaled = s * gain
            val saturated = kotlin.math.tanh(scaled.toDouble()).toFloat()
            val sampleOut = (saturated * 32767.0f).toInt()
            buffer[i] = sampleOut.coerceIn(-32768, 32767).toShort()
        }
    }

    private fun createAudioRecord(sampleRate: Int, bufferSize: Int): Pair<AudioRecord?, String> {
        val sources = if (selectedMicSource == MicSource.SMARTPHONE) {
            listOf(
                Pair(MediaRecorder.AudioSource.MIC, "MIC (Встроенный микрофон смартфона)"),
                Pair(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED (Прямой микрофон без системной компрессии)"),
                Pair(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION (Распознавание речи)"),
                Pair(MediaRecorder.AudioSource.DEFAULT, "DEFAULT")
            )
        } else {
            listOf(
                Pair(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION (Гарнитура/Bluetooth SCO)"),
                Pair(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION (Распознавание речи)"),
                Pair(MediaRecorder.AudioSource.MIC, "MIC (Стандартный микрофон)"),
                Pair(MediaRecorder.AudioSource.DEFAULT, "DEFAULT")
            )
        }

        for ((source, name) in sources) {
            try {
                val record = AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(tag, "AudioRecord инициализирован с источником: $name")
                    if (selectedMicSource == MicSource.SMARTPHONE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                            val inputDevices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                            val builtinMic = inputDevices?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                            if (builtinMic != null) {
                                val ok = record.setPreferredDevice(builtinMic)
                                Log.d(tag, "AudioRecord.setPreferredDevice(TYPE_BUILTIN_MIC): $ok")
                                logEvent("PREFERRED_MIC_DEVICE_SET", "device=${builtinMic.productName}, ok=$ok")
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "Ошибка setPreferredDevice: ${e.message}")
                        }
                    }
                    attachHardwareEffects(record.audioSessionId)
                    return Pair(record, name)
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(tag, "Не удалось инициализировать AudioRecord с $name: ${e.message}")
            }
        }
        return Pair(null, "FAILED")
    }

    private fun startRecording() {
        val sampleRate = 16000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // Буфер не менее 2 секунд (64 КБ), чтобы гарантировать отсутствие переполнения
        val bufferSize = maxOf(minBufferSize * 4, sampleRate * 2 * 2)

        val (record, sourceName) = createAudioRecord(sampleRate, bufferSize)
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            val err = "🔴 Ошибка: микрофон недоступен ни в одном режиме"
            lastError = err
            logEvent("MIC_INIT_FAILED")
            onStatusChanged?.invoke(err)
            stopSelf()
            return
        }

        audioRecord = record
        activeAudioSource = sourceName
        logEvent("RECORDING_START", "source=$activeAudioSource, bufferSize=$bufferSize")

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            val err = "🔴 Ошибка старта микрофона: ${e.message}"
            lastError = err
            logEvent("MIC_START_ERROR", "${e.message}")
            onStatusChanged?.invoke(err)
            stopSelf()
            return
        }

        // Поток 1: Неблокирующий захват PCM звука с микрофона в audioSendQueue
        recordThread = Thread {
            // Чанк 100 мс при 16 кГц Mono 16-бит = 1600 сэмплов (Shorts) = 3200 байт
            val shortBuffer = ShortArray(1600)

            while (isRunning) {
                try {
                    val read = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    lastAudioReadResult = read

                    if (read > 0) {
                        // Если выбран микрофон смартфона, применяем динамический soft-knee AGC/лимитер (+14 дБ для тихой речи с мягким насыщением)
                        if (selectedMicSource == MicSource.SMARTPHONE) {
                            applyDynamicSoftKneeAgc(shortBuffer, read)
                        }

                        var maxVal = 0
                        for (i in 0 until read) {
                            val absVal = kotlin.math.abs(shortBuffer[i].toInt())
                            if (absVal > maxVal) maxVal = absVal
                        }
                        micPeakAmplitude = ((maxVal * 100) / 32767).coerceIn(0, 100)
                        if (micPeakAmplitude > sessionMaxAmplitude) {
                            sessionMaxAmplitude = micPeakAmplitude
                        }

                        // Конвертация ShortArray в little-endian ByteArray
                        val byteBuffer = ByteArray(read * 2)
                        for (i in 0 until read) {
                            val s = shortBuffer[i].toInt()
                            byteBuffer[i * 2] = (s and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }

                        // Неблокирующее помещение в очередь отправки.
                        // Если очередь переполнена (задержка сети), вытесняем старейший чанк для сохранения реал-тайма.
                        if (!audioSendQueue.offer(byteBuffer)) {
                            audioSendQueue.poll()
                            audioSendQueue.offer(byteBuffer)
                        }
                    } else {
                        audioReadErrors++
                        if (read < 0) {
                            lastError = "AudioRecord.read ошибка: $read"
                            logEvent("MIC_READ_ERROR", "readResult=$read")
                        }
                        try {
                            Thread.sleep(20)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(tag, "Ошибка в потоке записи: ${e.message}")
                    lastError = "Поток записи: ${e.message}"
                    logEvent("RECORD_THREAD_ERROR", "${e.message}")
                    break
                }
            }
        }
        recordThread?.start()

        // Поток 2: Отдельный независимый поток отправки аудиочанков в WebSocket
        sendThread = Thread {
            var lastTelemetryEmit = 0L
            var lastLogStreamEmit = 0L

            while (isRunning) {
                try {
                    val chunk = audioSendQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue

                    // Защита: не отправляем аудио в сокет, пока сервер не прислал setupComplete
                    if (!isSetupComplete) {
                        continue
                    }

                    val ws = webSocket
                    if (ws != null) {
                        chunksSent++
                        bytesSent += chunk.size

                        val base64Data = Base64.encodeToString(chunk, Base64.NO_WRAP)
                        val chunkJson = JSONObject().apply {
                            put("realtimeInput", JSONObject().apply {
                                put("audio", JSONObject().apply {
                                    put("mimeType", "audio/pcm;rate=16000")
                                    put("data", base64Data)
                                })
                            })
                        }
                        ws.send(chunkJson.toString())
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastTelemetryEmit > 300) {
                        lastTelemetryEmit = now
                        onTelemetryUpdated?.invoke(
                            micPeakAmplitude,
                            chunksSent,
                            serverTextChunksReceived + serverAudioChunksReceived
                        )
                    }

                    // Периодический лог стриминга каждые 15 сек
                    if (now - lastLogStreamEmit > 15000) {
                        lastLogStreamEmit = now
                        logEvent(
                            "CHUNK_STREAMING",
                            "(chunks=$chunksSent, mic_peak=$micPeakAmplitude%)"
                        )
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(tag, "Ошибка в потоке отправки аудио: ${e.message}")
                    lastError = "Поток отправки: ${e.message}"
                    logEvent("SEND_THREAD_ERROR", "${e.message}")
                }
            }
        }
        sendThread?.start()
    }

    override fun onDestroy() {
        isRunning = false
        isServiceActive = false
        isSetupComplete = false
        connectionState = "STOPPED"

        val uptimeSec = if (serviceStartTime > 0) (System.currentTimeMillis() - serviceStartTime) / 1000 else 0
        val totalResp = serverAudioChunksReceived + serverTextChunksReceived
        logEvent(
            "SERVICE_STOP",
            "(uptime=${uptimeSec}s, total_chunks=$chunksSent, total_resp=$totalResp)"
        )

        // Сохраняем полный финальный снимок диагностики перед обнулением instance
        lastDiagnosticSnapshot = buildDiagnosticSnapshot()

        restoreAudioRouting()
        releaseHardwareEffects()

        inputTagCleaner.reset()
        outputTagCleaner.reset()
        queuedPlaybackBytes.set(0L)

        audioPlaybackQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null

        audioSendQueue.clear()
        sendThread?.interrupt()
        sendThread = null

        recordThread?.interrupt()
        recordThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {}

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {}

        webSocket?.close(1000, "Service stopped")
        webSocket = null

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        mainHandler.removeCallbacks(notificationFlushRunnable)
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(SUBTITLE_NOTIFICATION_ID)

        onStatusChanged?.invoke("⚪ Суфлёр остановлен.")
        currentInstance = null
        super.onDestroy()
    }
}
