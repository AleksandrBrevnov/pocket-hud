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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    SMARTPHONE, // 📱 Встроенный микрофон смартфона (AudioSource.VOICE_RECOGNITION, Hardware AGC, Soft-knee DSP)
    BLUETOOTH   // 🎧 Микрофон гарнитуры (VOICE_COMMUNICATION, Bluetooth SCO)
}

enum class TranslationEngine {
    CASCADED_REST,     // Вариант А: Continuous SpeechRecognizer (it-IT) + REST Flash + TTS (по умолчанию)
    LIVE_WEBSOCKET     // Вариант Б: Gemini Live WebSocket (models/gemini-3.5-live-translate-preview)
}

private val dialogueTurnIdCounter = AtomicLong(0L)

fun updateDialogueTurnIdCounter(maxId: Long) {
    var cur = dialogueTurnIdCounter.get()
    while (maxId >= cur) {
        if (dialogueTurnIdCounter.compareAndSet(cur, maxId + 1)) {
            break
        }
        cur = dialogueTurnIdCounter.get()
    }
}

data class DialogueTurn(
    val id: Long = dialogueTurnIdCounter.incrementAndGet(),
    var italianText: String = "",
    var russianText: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    var lastActivityTime: Long = System.currentTimeMillis(),
    var isFinalized: Boolean = false
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
                        // Если тег не закрыт в пределах 60 символов, это не тег, а обычный символ '<'
                        if (buffer.length - i > 60) {
                            output.append(ch)
                            i++
                            continue
                        }
                        break
                    }
                } else if (ch == '{') {
                    val closeBrace = buffer.indexOf('}', i)
                    if (closeBrace != -1) {
                        i = closeBrace + 1
                        continue
                    } else {
                        // Если фигурная скобка не закрыта в пределах 30 символов, это не тег
                        if (buffer.length - i > 30) {
                            output.append(ch)
                            i++
                            continue
                        }
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
    @Volatile private var isRunning = false
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // Аппаратные эффекты Android DSP
    private var hwAgc: AutomaticGainControl? = null
    private var hwNs: NoiseSuppressor? = null

    // Флаг готовности WebSocket сессии к приему аудио (защита от отправки до setupComplete)
    @Volatile private var isSetupComplete = false

    // Таймер Watchdog для ожидания setupComplete от Google Live (защита от зависания сессии)
    private var setupWatchdogRunnable: Runnable? = null

    // Задача отложенного переподключения при ротации сессии (code=1000)
    private var sessionRotationRunnable: Runnable? = null

    // Флаг ожидания завершения фразы перед ротацией (Speech-Aware Graceful Rotation)
    @Volatile private var isRotationPending = false

    // Активная карточка диалога (Single Active Card) и стриминг перевода
    @Volatile private var activeDialogueTurn: DialogueTurn? = null
    @Volatile private var activeTranslationTurnId: Long? = null

    // Очереди и байты для Audio Latency Drift Catch-Up (24 кГц 16-бит mono = 48000 байт/сек)
    private val queuedPlaybackBytes = AtomicLong(0L)
    @Volatile private var isPlaybackSpeedBoosted = false

    // Задача отложенного закрытия при сигнале goAway
    private var goAwayRunnable: Runnable? = null

    // Потоковые очистители неполных тегов
    private val inputTagCleaner = StreamingTagCleaner()
    private val outputTagCleaner = StreamingTagCleaner()

    // Флаг ожидания перевода для зафиксированной входной речи (для детекции TRANSLATION_EMPTY)
    @Volatile private var hasPendingTranslation = false
    @Volatile private var currentTurnInputSpeech: String? = null

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
    // Attention Sink Watchdog: детекция галлюцинаторного зацикливания ASR в тишине
    @Volatile private var lastAsrPhrase: String? = null
    @Volatile private var consecutiveAsrRepeatCount = 0
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
    private val simulIdleRunnable = Runnable { finalizeCurrentTurn() }

    // Option A: Continuous Speech Recognition & REST Translation & TTS
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    @Volatile private var isRecognitionPaused = false
    @Volatile private var consecutiveSpeechErrors = 0
    @Volatile private var lastSpeechRecognitionError = 0

    private var tts: TextToSpeech? = null
    @Volatile private var isTtsInitialized = false
    @Volatile private var isTtsSpeaking = false
    private val ttsWatchdogRunnable = Runnable {
        if (isTtsSpeaking) {
            Log.w(tag, "TTS Watchdog: сброс зависшего флага isTtsSpeaking")
            isTtsSpeaking = false
            resumeRecognition()
        }
    }

    @Volatile private var activeRestModel: String = "gemini-2.0-flash"

    private val restHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    companion object {
        const val CHANNEL_SERVICE_ID = "pocket_hud_service_v2"
        const val CHANNEL_SUBTITLES_ID = "pocket_hud_subtitles_v2"
        const val NOTIFICATION_ID = 1001
        const val SUBTITLE_NOTIFICATION_ID = 1002
        const val SIMUL_IDLE_DELAY_MS = 3500L

        const val EXTRA_API_KEY = "extra_api_key"
        const val EXTRA_IS_MUTED = "extra_is_muted"
        const val EXTRA_MIC_SOURCE = "extra_mic_source"
        const val EXTRA_TRANSLATION_ENGINE = "extra_translation_engine"
        const val EXTRA_REST_MODEL = "extra_rest_model"

        // Wear OS / Xiaomi Watch 2 Companion Control Actions
        const val ACTION_TOGGLE_MUTE = "com.aleksandr.pockethud.ACTION_TOGGLE_MUTE"
        const val ACTION_SWITCH_MIC = "com.aleksandr.pockethud.ACTION_SWITCH_MIC"
        const val ACTION_CLEAR_SUBTITLES = "com.aleksandr.pockethud.ACTION_CLEAR_SUBTITLES"
        const val ACTION_STOP_SERVICE = "com.aleksandr.pockethud.ACTION_STOP_SERVICE"
        const val ACTION_SWITCH_ENGINE = "com.aleksandr.pockethud.ACTION_SWITCH_ENGINE"

        val REST_FLASH_MODELS = listOf(
            "gemini-2.0-flash",
            "gemini-1.5-flash"
        )

        @Volatile
        var isServiceActive = false

        @Volatile
        var selectedEngine: TranslationEngine = TranslationEngine.CASCADED_REST
            set(value) {
                field = value
                currentInstance?.applyEngineChange(value)
            }

        @Volatile
        var selectedRestModel: String = "gemini-2.0-flash"
            set(value) {
                field = value
                currentInstance?.applyRestModelChange(value)
            }

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

        // Коллбэки в UI (Volatile для потокобезопасности: устанавливаются в UI-потоке, вызываются из OkHttp)
        @Volatile var onStatusChanged: ((String) -> Unit)? = null
        @Volatile var onTextReceived: ((String) -> Unit)? = null
        @Volatile var onInputTextReceived: ((String) -> Unit)? = null
        @Volatile var onDialogueTurnUpdated: ((turnIndex: Int, turn: DialogueTurn, isNew: Boolean) -> Unit)? = null
        @Volatile var onTelemetryUpdated: ((micAmp: Int, chunks: Long, responses: Long) -> Unit)? = null
        @Volatile var onMuteStateChanged: ((Boolean) -> Unit)? = null
        @Volatile var onMicSourceChanged: ((MicSource) -> Unit)? = null
        @Volatile var onEngineChanged: ((TranslationEngine) -> Unit)? = null
        @Volatile var onRestModelChanged: ((String) -> Unit)? = null

        // Диалог текущей сессии (Google Translate Conversation Mode)
        private val sessionDialogueTurns = ArrayList<DialogueTurn>()

        fun getSessionDialogueTurns(): List<DialogueTurn> {
            synchronized(sessionDialogueTurns) {
                return sessionDialogueTurns.map { it.copy() }
            }
        }

        fun clearDialogueTurns(context: Context? = null) {
            if (context != null) {
                // Перед очисткой экрана архивируем сессию в историю, чтобы ничего не потерялось
                synchronized(sessionDialogueTurns) {
                    DialogueHistoryManager.saveSession(context, sessionDialogueTurns)
                }
            }
            synchronized(sessionDialogueTurns) {
                sessionDialogueTurns.clear()
            }
            context?.let { DialogueHistoryManager.clearCurrentSessionCache(it) }
            currentInstance?.clearActiveDialogueTurn()
        }

        fun restoreDialogueTurns(turns: List<DialogueTurn>) {
            synchronized(sessionDialogueTurns) {
                sessionDialogueTurns.clear()
                sessionDialogueTurns.addAll(turns)
                val maxId = turns.maxOfOrNull { it.id } ?: 0L
                updateDialogueTurnIdCounter(maxId)
            }
            synchronized(sessionItalianBuilder) {
                sessionItalianBuilder.setLength(0)
                for (t in turns) {
                    if (t.italianText.isNotBlank()) appendToSessionBuilder(sessionItalianBuilder, t.italianText)
                }
            }
            synchronized(sessionRussianBuilder) {
                sessionRussianBuilder.setLength(0)
                for (t in turns) {
                    if (t.russianText.isNotBlank()) appendToSessionBuilder(sessionRussianBuilder, t.russianText)
                }
            }
            currentInstance?.clearActiveDialogueTurn()
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
            return "=== POCKET HUD СВОДКА ДИАГНОСТИКИ ===\nВерсия: v1.0.43\nСтатус: СЕРВИС ОСТАНОВЛЕН (Сессия ещё не запускалась)\nДвижок: $selectedEngine (REST модель: $selectedRestModel)\nРежим микрофона: $selectedMicSource\nРежим звука: ${if (isAudioMuted) "🔇 ВЫКЛЮЧЕН" else "🔊 ВКЛЮЧЕН"}\n======================================"
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
        val action = intent?.action
        if (action != null) {
            if (!isRunning && action != ACTION_STOP_SERVICE) {
                stopSelf()
                return START_NOT_STICKY
            }
            when (action) {
                ACTION_TOGGLE_MUTE -> {
                    isAudioMuted = !isAudioMuted
                    triggerWearHapticFeedback()
                    logEvent("WEAR_ACTION_TOGGLE_MUTE", "(isMuted=$isAudioMuted)")
                    onMuteStateChanged?.invoke(isAudioMuted)
                    return START_STICKY
                }
                ACTION_SWITCH_MIC -> {
                    val nextSource = if (selectedMicSource == MicSource.SMARTPHONE) MicSource.BLUETOOTH else MicSource.SMARTPHONE
                    triggerWearHapticFeedback()
                    logEvent("WEAR_ACTION_SWITCH_MIC", "(nextSource=$nextSource)")
                    selectedMicSource = nextSource
                    onMicSourceChanged?.invoke(nextSource)
                    return START_STICKY
                }
                ACTION_SWITCH_ENGINE -> {
                    val nextEngine = if (selectedEngine == TranslationEngine.CASCADED_REST) {
                        TranslationEngine.LIVE_WEBSOCKET
                    } else {
                        TranslationEngine.CASCADED_REST
                    }
                    triggerWearHapticFeedback()
                    logEvent("WEAR_ACTION_SWITCH_ENGINE", "(nextEngine=$nextEngine)")
                    selectedEngine = nextEngine
                    onEngineChanged?.invoke(nextEngine)
                    return START_STICKY
                }
                ACTION_CLEAR_SUBTITLES -> {
                    triggerWearHapticFeedback()
                    logEvent("WEAR_ACTION_CLEAR_SUBTITLES")
                    finalizeCurrentTurn()
                    mainHandler.removeCallbacks(notificationFlushRunnable)
                    pendingNotificationLatest = null
                    pendingNotificationHistory = null
                    recentTranslations.setLength(0)
                    lastPostedSubtitleText = "🧹 Экран очищен"
                    lastPostedSubtitleHistory = "Субтитры очищены. Ожидание новой реплики собеседника..."
                    updateOngoingNotification(
                        lastPostedSubtitleText,
                        lastPostedSubtitleHistory
                    )
                    return START_STICKY
                }
                ACTION_STOP_SERVICE -> {
                    triggerWearHapticFeedback()
                    logEvent("WEAR_ACTION_STOP_SERVICE")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        val apiKey = intent?.getStringExtra(EXTRA_API_KEY) ?: ""
        if (apiKey.isBlank()) {
            if (isRunning) {
                return START_STICKY
            }
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

        val engineExtra = intent?.getStringExtra(EXTRA_TRANSLATION_ENGINE)
        if (engineExtra != null) {
            selectedEngine = try {
                TranslationEngine.valueOf(engineExtra)
            } catch (e: Exception) {
                TranslationEngine.CASCADED_REST
            }
        }

        val restModelExtra = intent?.getStringExtra(EXTRA_REST_MODEL)
        val validRestModel = if (!restModelExtra.isNullOrBlank() && restModelExtra in REST_FLASH_MODELS) {
            restModelExtra
        } else {
            "gemini-2.0-flash"
        }
        selectedRestModel = validRestModel
        activeRestModel = validRestModel

        activeApiKey = apiKey
        reconnectAttempts = 0
        currentInstance = this
        clearSessionTexts()
        serviceStartTime = System.currentTimeMillis()
        connectionState = "STARTING"

        logEvent("SERVICE_START", "(engine=$selectedEngine, mic=$selectedMicSource, isAudioMuted=$isAudioMuted)")

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

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketHUD::WakeLock").apply {
            acquire(24 * 60 * 60 * 1000L)
        }

        isRunning = true
        isServiceActive = true

        if (selectedEngine == TranslationEngine.CASCADED_REST) {
            // Немедленно выводим карточку субтитров для часов Xiaomi Watch 2 (Wear OS 5)
            updateOngoingNotification(
                "⚡ Подключение...",
                "PocketHUD v1.0.43 активен (Каскадный ASR + REST Flash). Слушаю итальянскую речь..."
            )
            onStatusChanged?.invoke("⚡ Каскадный режим активен (ASR + REST Flash)")
            setupAudioRouting()
            initTts()
            initSpeechRecognizer()
            startListeningSafe()
        } else {
            // Немедленно выводим карточку субтитров для часов Xiaomi Watch 2 (Wear OS 5)
            updateOngoingNotification(
                "🔵 Подключение...",
                "PocketHUD v1.0.43 активен. Ожидание подключения к Gemini Live API..."
            )
            setupAudioRouting()
            onStatusChanged?.invoke("🔵 Подключение к Google Gemini Live API...")
            initAudioOutput()
            connectWebSocket(apiKey)
        }

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
        if (selectedEngine == TranslationEngine.CASCADED_REST) {
            if (muted) {
                try {
                    tts?.stop()
                } catch (e: Exception) {}
                isTtsSpeaking = false
                resumeRecognition()
            }
        } else {
            if (muted) {
                audioPlaybackQueue.clear()
                queuedPlaybackBytes.set(0L)
                isPlaybackSpeedBoosted = false
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
        }

        // Обновляем текст постоянного сервиса при переключении звука
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val modeStr = if (muted) "Карманный суфлёр (🔇 Режим только субтитров)" else "Карманный суфлёр (🔊 Звук в наушник)"
            val updatedFg = buildNotification(modeStr)
            nm?.notify(NOTIFICATION_ID, updatedFg)
            if (lastPostedSubtitleText.isNotBlank()) {
                updateOngoingNotification(lastPostedSubtitleText, lastPostedSubtitleHistory)
            }
        } catch (e: Exception) {
            Log.w(tag, "Ошибка обновления уведомления при смене mute: ${e.message}")
        }
    }

    private val micSwitchLock = Any()

    fun applyMicSourceChange(newSource: MicSource) {
        logEvent("MIC_SOURCE_CHANGE_REQUESTED", "newSource=$newSource")
        if (selectedMicSource != newSource) {
            selectedMicSource = newSource
            return
        }
        if (!isRunning) return

        if (selectedEngine == TranslationEngine.CASCADED_REST) {
            setupAudioRouting()
            restartSpeechRecognizer()
            mainHandler.post {
                val modeStr = if (isAudioMuted) "Карманный суфлёр (🔇 Режим только субтитров)" else "Карманный суфлёр (🔊 Звук в наушник)"
                val updatedFg = buildNotification(modeStr)
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, updatedFg)
                if (lastPostedSubtitleText.isNotBlank()) {
                    updateOngoingNotification(lastPostedSubtitleText, lastPostedSubtitleHistory)
                }
            }
            return
        }

        Thread {
            synchronized(micSwitchLock) {
                try {
                    recordThread?.interrupt()
                    audioRecord?.stop()
                    recordThread?.join(500)
                } catch (e: Exception) {
                    Log.w(tag, "Ошибка ожидания завершения recordThread: ${e.message}")
                }
                recordThread = null

                releaseHardwareEffects()
                try {
                    audioRecord?.release()
                } catch (e: Exception) {
                    Log.w(tag, "Ошибка освобождения AudioRecord: ${e.message}")
                }
                audioRecord = null
                audioSendQueue.clear()

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
                        if (isRunning) {
                            startRecordThread()
                        }
                        mainHandler.post {
                            val modeStr = if (isAudioMuted) "Карманный суфлёр (🔇 Режим только субтитров)" else "Карманный суфлёр (🔊 Звук в наушник)"
                            val updatedFg = buildNotification(modeStr)
                            val nm = getSystemService(NotificationManager::class.java)
                            nm?.notify(NOTIFICATION_ID, updatedFg)
                            if (lastPostedSubtitleText.isNotBlank()) {
                                updateOngoingNotification(lastPostedSubtitleText, lastPostedSubtitleHistory)
                            }
                        }
                    } catch (e: Exception) {
                        logEvent("MIC_SOURCE_START_FAILED", "${e.message}")
                    }
                } else {
                    val err = "🔴 Ошибка переключения микрофона ($newSource)"
                    lastError = err
                    onStatusChanged?.invoke(err)
                    logEvent("MIC_SOURCE_INIT_FAILED")
                }
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

    private fun triggerWearHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(35)
                }
            }
        } catch (e: Exception) {
            // Ignore haptic errors
        }
    }

    private fun cleanServerTags(raw: String): String {
        return outputTagCleaner.clean(raw)
    }

    private val ABBREVIATIONS = setOf(
        "sig.", "dott.", "prof.", "ing.", "avv.", "ecc.", "etc.", "e.g.", "i.e.",
        "т.д.", "т.п.", "г.", "руб.", "ул.", "д.", "кв.", "стр.", "корп."
    )

    private fun isSentenceEnding(text: String): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return false
        val s = trimmed.trimEnd('"', '\'', '»', '«', ')', ']', '}', '”', '’')
        if (s.isEmpty()) return false

        // Многоточие (...) или знак эллипсиса (…) — это пауза размышления / заминка, а не завершение мысли
        if (s.endsWith("...") || s.endsWith("…")) return false

        // Проверка на частые аббревиатуры в итальянском и русском
        val lastWord = s.substringAfterLast(' ').lowercase()
        if (lastWord in ABBREVIATIONS) return false

        // Проверка: внутренняя точка в числе (например "1." перед цифрой)
        if (s.endsWith('.') && s.length >= 2 && s[s.length - 2].isDigit()) {
            val wordBefore = s.substringAfterLast(' ')
            if (wordBefore.dropLast(1).all { it.isDigit() }) return false
        }

        return s.endsWith('.') || s.endsWith('!') || s.endsWith('?')
    }

    private fun isContinuation(text: String): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return false
        val s = trimmed.trimEnd('"', '\'', '»', '«', ')', ']', '}', '”', '’')
        return s.endsWith(',') || s.endsWith(';') || s.endsWith(':') || s.endsWith('-') || s.endsWith('—')
    }

    private fun appendWithPunctuationAwareSpace(existing: String, addition: String): String {
        val trimmedAdd = addition.trim()
        if (trimmedAdd.isEmpty()) return existing
        if (existing.isBlank()) return trimmedAdd

        val startsWithPunctuation = trimmedAdd[0] in listOf('.', ',', '!', '?', ';', ':', '…', ')', ']', '}', '»', '"', '\'', '”', '’')
        val endsWithOpeningQuoteOrBracket = existing.lastOrNull() in listOf('(', '[', '{', '«', '"', '“', '‘')

        return if (existing.endsWith(" ") || startsWithPunctuation || endsWithOpeningQuoteOrBracket) {
            existing + trimmedAdd
        } else {
            "$existing $trimmedAdd"
        }
    }

    private fun isPrefixExpansion(longer: String, shorter: String): Boolean {
        val l = longer.trim()
        val s = shorter.trim()
        if (s.isEmpty() || l.isEmpty()) return false
        if (l.equals(s, ignoreCase = true)) return true
        if (l.length > s.length && l.startsWith(s, ignoreCase = true)) {
            val nextChar = l[s.length]
            if (nextChar.isWhitespace() || nextChar in ",.;:!?'\"-…") return true
        }
        val sNorm = s.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"', '-', '…')
        if (sNorm.isNotEmpty() && l.length > sNorm.length && l.startsWith(sNorm, ignoreCase = true)) {
            val nextChar = l[sNorm.length]
            if (nextChar.isWhitespace() || nextChar in ",.;:!?'\"-…") return true
        }
        return false
    }

    private fun splitSentences(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val rawParts = trimmed.split(Regex("(?<=[.?!])\\s+"))
        if (rawParts.size <= 1) return listOf(trimmed)

        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        for (part in rawParts) {
            if (current.isEmpty()) {
                current.append(part)
            } else {
                current.append(" ").append(part)
            }
            val currentStr = current.toString()
            if (isSentenceEnding(currentStr)) {
                sentences.add(currentStr)
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) {
            sentences.add(current.toString())
        }
        return sentences
    }

    private fun scheduleNotificationUpdate(latestText: String, fullHistory: String) {
        pendingNotificationLatest = latestText
        pendingNotificationHistory = fullHistory

        val now = System.currentTimeMillis()
        val elapsed = now - lastNotificationTime

        val isSentenceBoundary = isSentenceEnding(latestText)

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

    fun clearActiveDialogueTurn() {
        activeDialogueTurn = null
    }

    private fun finalizeCurrentTurn() {
        mainHandler.removeCallbacks(simulIdleRunnable)
        activeTranslationTurnId = null
        activeDialogueTurn = null
        synchronized(sessionDialogueTurns) {
            for (i in 0 until sessionDialogueTurns.size) {
                val turn = sessionDialogueTurns[i]
                if (!turn.isFinalized) {
                    turn.isFinalized = true
                    // Idle timer finalization preserves original speech timestamp to prevent zombie resurrection
                    if (turn.russianText.isBlank()) {
                        val origPrompt = turn.italianText.ifBlank { null }
                            ?: currentTurnInputSpeech
                            ?: if (lastRecognizedInput.isBlank()) "..." else lastRecognizedInput
                        logEvent("TRANSLATION_EMPTY", "ORIG=\"$origPrompt\"")
                        Log.d(tag, "Речь зафиксирована ($origPrompt), но перевод не сгенерирован (TRANSLATION_EMPTY)")
                    }
                    val turnCopy = turn.copy()
                    onDialogueTurnUpdated?.invoke(i, turnCopy, false)
                }
            }
            hasPendingTranslation = false
            currentTurnInputSpeech = null

            // Автосохранение кэша и архива сессии при финализации реплики
            try {
                DialogueHistoryManager.saveCurrentSessionCache(this@PocketService, sessionDialogueTurns)
                DialogueHistoryManager.saveSession(this@PocketService, sessionDialogueTurns, isAutoArchive = true)
            } catch (e: Exception) {
                // Игнорируем ошибки дискового ввода-вывода в фоновом цикле
            }
        }

        if (isRotationPending) {
            Log.d(tag, "Пауза в речи (idle timer), выполняется запланированная ротация goAway...")
            goAwayRunnable?.let { mainHandler.removeCallbacks(it) }
            goAwayRunnable = null
            isRotationPending = false
            val currentWs = webSocket
            if (isRunning && currentWs != null) {
                try {
                    isSetupComplete = false
                    currentWs.close(1000, "Clean session rotation")
                } catch (e: Exception) {
                    currentWs.cancel()
                }
            }
        }
    }

    private fun recordInputSpeech(cleanedInput: String) {
        val trimmedInput = cleanedInput.trim()
        if (trimmedInput.isNotEmpty()) {
            if (trimmedInput.equals(lastAsrPhrase, ignoreCase = true) && micPeakAmplitude <= 1) {
                consecutiveAsrRepeatCount++
                if (consecutiveAsrRepeatCount >= 3) {
                    Log.w(tag, "Attention Sink Watchdog: фраза '$trimmedInput' повторилась $consecutiveAsrRepeatCount раз подряд при micPeakAmplitude <= 1%. Запуск ротации сессии...")
                    logEvent("ATTENTION_SINK_WATCHDOG", "phrase=\"$trimmedInput\", count=$consecutiveAsrRepeatCount, mic_peak=$micPeakAmplitude%")
                    consecutiveAsrRepeatCount = 0
                    lastAsrPhrase = null
                    val currentWs = webSocket
                    if (isRunning && currentWs != null) {
                        try {
                            isSetupComplete = false
                            currentWs.close(1000, "Attention sink watchdog session rotation")
                        } catch (e: Exception) {
                            currentWs.cancel()
                        }
                    }
                    return
                }
            } else {
                lastAsrPhrase = trimmedInput
                consecutiveAsrRepeatCount = if (micPeakAmplitude <= 1) 1 else 0
            }
        }

        Log.d(tag, "Распознана речь (IT/EN): $cleanedInput")
        logEvent("INPUT_SPEECH:", "\"$cleanedInput\"")
        appendToSessionBuilder(sessionItalianBuilder, cleanedInput)
        onInputTextReceived?.invoke(cleanedInput)
        mainHandler.removeCallbacks(simulIdleRunnable)
        mainHandler.postDelayed(simulIdleRunnable, SIMUL_IDLE_DELAY_MS)

        // Обновление диалога в режиме Google Translate Conversation (Single Active Card / Speaker Turn):
        // Речь накапливается в активной карточке, авторская пунктуация сохраняется.
        synchronized(sessionDialogueTurns) {
            val now = System.currentTimeMillis()

            // Мягкий перенос карточки (Soft Wrap) происходит только при комфортном объеме текста:
            // turn.italianText.length >= 200 && isSentenceEnding(turn.italianText) && !isContinuation(turn.italianText)
            val currentActive = activeDialogueTurn
            if (currentActive != null && currentActive.italianText.length >= 200 &&
                isSentenceEnding(currentActive.italianText) && !isContinuation(currentActive.italianText)) {
                currentActive.isFinalized = true
                currentActive.lastActivityTime = now
                val idx = sessionDialogueTurns.indexOf(currentActive)
                if (idx != -1) {
                    onDialogueTurnUpdated?.invoke(idx, currentActive.copy(), false)
                }
                if (activeTranslationTurnId == currentActive.id) {
                    activeTranslationTurnId = null
                }
                activeDialogueTurn = null
            }

            val lastTurn = activeDialogueTurn
            if (lastTurn != null) {
                val currentText = lastTurn.italianText.trim()
                val inputTrim = cleanedInput.trim()
                lastTurn.italianText = when {
                    currentText.isEmpty() -> cleanedInput
                    isPrefixExpansion(inputTrim, currentText) -> cleanedInput
                    isPrefixExpansion(currentText, inputTrim) -> lastTurn.italianText
                    else -> appendWithPunctuationAwareSpace(lastTurn.italianText, cleanedInput)
                }
                lastTurn.lastActivityTime = now
                lastRecognizedInput = lastTurn.italianText
                currentTurnInputSpeech = lastTurn.italianText
                hasPendingTranslation = true
                val idx = sessionDialogueTurns.indexOf(lastTurn)
                if (idx != -1) {
                    val turnCopy = lastTurn.copy()
                    onDialogueTurnUpdated?.invoke(idx, turnCopy, false)
                }
            } else {
                for (i in 0 until sessionDialogueTurns.size) {
                    val turn = sessionDialogueTurns[i]
                    if (!turn.isFinalized && turn.russianText.isNotBlank()) {
                        if (isSentenceEnding(turn.russianText) &&
                            !isContinuation(turn.russianText) &&
                            !isContinuation(turn.italianText)) {
                            turn.isFinalized = true
                            val prevCopy = turn.copy()
                            onDialogueTurnUpdated?.invoke(i, prevCopy, false)
                        }
                    }
                }
                val newTurn = DialogueTurn(
                    italianText = cleanedInput,
                    russianText = "",
                    timestamp = now,
                    lastActivityTime = now,
                    isFinalized = false
                )
                lastRecognizedInput = cleanedInput
                currentTurnInputSpeech = cleanedInput
                hasPendingTranslation = true
                sessionDialogueTurns.add(newTurn)
                if (sessionDialogueTurns.size > 200) {
                    sessionDialogueTurns.removeAt(0)
                }
                activeDialogueTurn = newTurn
                val idx = sessionDialogueTurns.size - 1
                val turnCopy = newTurn.copy()
                onDialogueTurnUpdated?.invoke(idx, turnCopy, true)
            }
            try {
                DialogueHistoryManager.saveCurrentSessionCache(this@PocketService, sessionDialogueTurns)
            } catch (e: Exception) {
            }
        }
    }

    private fun recordTranslationOutput(cleanedText: String) {
        serverTextChunksReceived++
        lastServerText = cleanedText
        hasPendingTranslation = false
        currentTurnInputSpeech = null

        // Обновление диалога в режиме Google Translate Conversation
        val currentSentence: String
        var targetTurnIdx = -1
        synchronized(sessionDialogueTurns) {
            var targetTurn: DialogueTurn? = null

            // 1. Если есть активная карточка в процессе стриминга — продолжаем направлять чанки в неё
            if (activeTranslationTurnId != null) {
                targetTurn = sessionDialogueTurns.find { it.id == activeTranslationTurnId && !it.isFinalized }
                if (targetTurn == null) {
                    activeTranslationTurnId = null
                }
            }

            // 2. Если активной карточки нет, ищем подходящую по приоритетам:
            if (targetTurn == null) {
                val nowTime = System.currentTimeMillis()
                // Приоритет 1: Активная карточка речи, которая уже находится в процессе стриминга перевода (перевод ещё не завершен)
                var targetIdx = sessionDialogueTurns.indexOfFirst {
                    it.italianText.isNotBlank() && it.russianText.isNotBlank() && !it.isFinalized &&
                    (!isSentenceEnding(it.russianText) || isContinuation(it.russianText))
                }
                // Приоритет 2: Самая ранняя (FIFO) нефинализированная карточка речи, ожидающая перевод
                if (targetIdx == -1) {
                    targetIdx = sessionDialogueTurns.indexOfFirst {
                        it.italianText.isNotBlank() && it.russianText.isBlank() && !it.isFinalized
                    }
                }
                // Приоритет 3: Активная диалоговая карточка (Single Active Card)
                val curActive = activeDialogueTurn
                if (targetIdx == -1 && curActive != null && !curActive.isFinalized) {
                    targetIdx = sessionDialogueTurns.indexOf(curActive)
                }
                // Приоритет 4: Любая последняя нефинализированная карточка
                if (targetIdx == -1) {
                    targetIdx = sessionDialogueTurns.indexOfLast { !it.isFinalized }
                }

                if (targetIdx != -1) {
                    targetTurn = sessionDialogueTurns[targetIdx]
                }
            }

            if (targetTurn != null) {
                val targetIdx = sessionDialogueTurns.indexOf(targetTurn)
                targetTurnIdx = targetIdx

                // Защита Priority 1: если карточка была финализирована таймером тишины (задержанный перевод),
                // сохраняем isFinalized = true, предотвращая её захват последующими переводами чужих реплик
                val wasFinalized = targetTurn.isFinalized
                if (!wasFinalized) {
                    targetTurn.isFinalized = false
                }
                targetTurn.russianText = appendWithPunctuationAwareSpace(targetTurn.russianText, cleanedText)
                targetTurn.lastActivityTime = System.currentTimeMillis()

                val isEnding = isSentenceEnding(targetTurn.russianText) && !isContinuation(targetTurn.russianText)
                if (isEnding) {
                    activeTranslationTurnId = null
                    val nextWaitingTurnExists = sessionDialogueTurns.any {
                        it !== targetTurn && it.italianText.isNotBlank() && it.russianText.isBlank()
                    }
                    if (nextWaitingTurnExists && !isContinuation(targetTurn.italianText)) {
                        targetTurn.isFinalized = true
                    }
                } else {
                    activeTranslationTurnId = targetTurn.id
                }

                val turnCopy = targetTurn.copy()
                onDialogueTurnUpdated?.invoke(targetIdx, turnCopy, false)
                currentSentence = targetTurn.russianText
            } else {
                val now = System.currentTimeMillis()
                val isEnding = isSentenceEnding(cleanedText) && !isContinuation(cleanedText)
                val newTurn = DialogueTurn(
                    italianText = "",
                    russianText = cleanedText,
                    timestamp = now,
                    lastActivityTime = now,
                    isFinalized = false
                )
                sessionDialogueTurns.add(newTurn)
                if (sessionDialogueTurns.size > 200) {
                    sessionDialogueTurns.removeAt(0)
                }
                val idx = sessionDialogueTurns.size - 1
                targetTurnIdx = idx
                if (isEnding) {
                    activeTranslationTurnId = null
                } else {
                    activeTranslationTurnId = newTurn.id
                }
                val turnCopy = newTurn.copy()
                onDialogueTurnUpdated?.invoke(idx, turnCopy, true)
                currentSentence = cleanedText
            }
        }

        val origPrompt = synchronized(sessionDialogueTurns) {
            if (targetTurnIdx in sessionDialogueTurns.indices) {
                sessionDialogueTurns[targetTurnIdx].italianText.ifBlank { null }
            } else {
                sessionDialogueTurns.findLast { it.italianText.isNotBlank() }?.italianText?.ifBlank { null }
            }
        } ?: if (lastRecognizedInput.isBlank()) "..." else lastRecognizedInput
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
        try {
            DialogueHistoryManager.saveCurrentSessionCache(this@PocketService, sessionDialogueTurns)
        } catch (e: Exception) {
        }

        scheduleNotificationUpdate(currentSentence, recentTranslations.toString().trim())
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

    private data class NotificationActionsBundle(
        val muteAction: NotificationCompat.Action,
        val switchMicAction: NotificationCompat.Action,
        val clearAction: NotificationCompat.Action,
        val stopAction: NotificationCompat.Action
    )

    private fun createNotificationActions(): NotificationActionsBundle {
        val muteIntent = Intent(this, PocketService::class.java).apply { action = ACTION_TOGGLE_MUTE }
        val mutePendingIntent = PendingIntent.getService(
            this,
            101,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muteTitle = if (isAudioMuted) "🎙️ Включить" else "🔇 Заглушить"
        val muteAction = NotificationCompat.Action.Builder(
            if (isAudioMuted) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_lock_silent_mode,
            muteTitle,
            mutePendingIntent
        ).build()

        val switchMicIntent = Intent(this, PocketService::class.java).apply { action = ACTION_SWITCH_MIC }
        val switchMicPendingIntent = PendingIntent.getService(
            this,
            102,
            switchMicIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val micTitle = if (selectedMicSource == MicSource.SMARTPHONE) "🎧 Гарнитура" else "📱 Смартфон"
        val switchMicAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_btn_speak_now,
            micTitle,
            switchMicPendingIntent
        ).build()

        val clearIntent = Intent(this, PocketService::class.java).apply { action = ACTION_CLEAR_SUBTITLES }
        val clearPendingIntent = PendingIntent.getService(
            this,
            103,
            clearIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val clearAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_delete,
            "🧹 Очистить",
            clearPendingIntent
        ).build()

        val stopIntent = Intent(this, PocketService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPendingIntent = PendingIntent.getService(
            this,
            104,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "⏹️ Стоп",
            stopPendingIntent
        ).build()

        return NotificationActionsBundle(muteAction, switchMicAction, clearAction, stopAction)
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

        val actions = createNotificationActions()

        val wearableExtender = NotificationCompat.WearableExtender()
            .setContentIntentAvailableOffline(false)
            .setHintShowBackgroundOnly(false)
            .setBridgeTag("pockethud_wear")
            .addAction(actions.muteAction)
            .addAction(actions.switchMicAction)
            .addAction(actions.stopAction)

        return NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("PocketHUD — Ушной суфлёр v1.0.43")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(actions.muteAction)
            .addAction(actions.switchMicAction)
            .addAction(actions.stopAction)
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

        val actions = createNotificationActions()

        val wearableExtender = NotificationCompat.WearableExtender()
            .setContentIntentAvailableOffline(false)
            .setHintShowBackgroundOnly(false)
            .setBridgeTag("pockethud_wear")
            .addAction(actions.muteAction)
            .addAction(actions.switchMicAction)
            .addAction(actions.clearAction)
            .addAction(actions.stopAction)

        // 1. Обновляем статус постоянного сервиса
        val micStatus = if (selectedMicSource == MicSource.SMARTPHONE) "📱 Смартфон" else "🎧 Гарнитура"
        val fgTitle = if (isAudioMuted) "PocketHUD — MUTE" else "PocketHUD — Перевод ($micStatus)"
        val fgNotification = NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle(fgTitle)
            .setContentText(latestText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(actions.muteAction)
            .addAction(actions.switchMicAction)
            .addAction(actions.stopAction)
            .extend(wearableExtender)
            .build()
        nm.notify(NOTIFICATION_ID, fgNotification)

        // 2. Карточка субтитров для часов Xiaomi Watch 2 (Wear OS 5)
        // setOngoing(false) критично: Wear OS 5 и Mi Fitness скрывают ongoing карточки из карусели!
        val watchTitle = if (isAudioMuted) "PocketHUD [🔇 MUTE]" else "PocketHUD [🎙️ $micStatus]"
        val watchBody = if (fullHistory.isNotBlank()) fullHistory else latestText
        val subtitleNotification = NotificationCompat.Builder(this, CHANNEL_SUBTITLES_ID)
            .setContentTitle(watchTitle)
            .setContentText(latestText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(watchBody))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(false) // КРИТИЧНО для Wear OS 5!
            .setOnlyAlertOnce(true) // Без повторной вибрации при каждом слове
            .setLocalOnly(false) // Разрешено зеркалирование на часы
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(actions.muteAction)
            .addAction(actions.switchMicAction)
            .addAction(actions.clearAction)
            .addAction(actions.stopAction)
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
            appendLine("Версия: v1.0.43")
            appendLine("Время работы: %02d:%02d".format(mm, ss))
            appendLine("--- ДВИЖОК ПЕРЕВОДА ---")
            appendLine("Активный движок: ${selectedEngine.name}")
            if (selectedEngine == TranslationEngine.CASCADED_REST) {
                appendLine("Модель REST Flash: $activeRestModel (резерв: ${REST_FLASH_MODELS.joinToString(", ")})")
                appendLine("TTS активен: $isTtsInitialized (воспроизведение: $isTtsSpeaking)")
                appendLine("ASR распознавание: ${if (isRecognitionPaused) "ПАУЗА (TTS)" else "СЛУШАЕТ it-IT"} (ошибок: $consecutiveSpeechErrors)")
            }
            appendLine("Статус сокета: $connectionState")
            appendLine("Сессионная ротация (Resumption): отключена (нативный Live Translate)")
            appendLine("Модель: models/gemini-3.5-live-translate-preview")
            appendLine("Режим микрофона: $selectedMicSource (${if (selectedMicSource == MicSource.SMARTPHONE) "Встроенный + Hardware DSP + Soft-knee AGC" else "Гарнитура / Bluetooth SCO"})")
            appendLine("Hardware DSP: AGC=${hwAgc?.enabled ?: false}, NS=${hwNs?.enabled ?: false}")
            appendLine("Режим звука: ${if (isAudioMuted) "🔇 ВЫКЛЮЧЕН (Только субтитры)" else "🔊 ВКЛЮЧЕН (Голос в наушник)"}")
            appendLine("Источник микрофона: $activeAudioSource (16 кГц Mono PCM16)")
            appendLine("Языки распознавания: Live Translate -> RU")
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
            isPlaybackSpeedBoosted = false
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
                                if (!isPlaybackSpeedBoosted && remainingBytes > 120_000L) {
                                    try {
                                        val params = track.playbackParams ?: PlaybackParams()
                                        track.playbackParams = params.setSpeed(1.15f)
                                        isPlaybackSpeedBoosted = true
                                        logEvent("LATENCY_CATCHUP_ENGAGED", "queuedBytes=$remainingBytes (~${"%.2f".format(Locale.US, remainingBytes / 48000.0)}s), speed=1.15x")
                                    } catch (e: Exception) {
                                        Log.w(tag, "Ошибка установки 1.15x: ${e.message}")
                                    }
                                } else if (isPlaybackSpeedBoosted && remainingBytes < 38_400L) { // Когда очередь сократилась до < 0.8 сек (< 38,400 байт) — возвращаем 1.0x
                                    try {
                                        val params = track.playbackParams ?: PlaybackParams()
                                        track.playbackParams = params.setSpeed(1.0f)
                                        isPlaybackSpeedBoosted = false
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
        sessionRotationRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionRotationRunnable = null
        lastAsrPhrase = null
        consecutiveAsrRepeatCount = 0
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
                if (webSocket !== ws) return
                Log.d(tag, "WebSocket открыт. Отправка Setup...")
                isSetupComplete = false
                connectionState = "OPEN (Sending Setup)"
                logEvent("WEBSOCKET_OPEN")
                onStatusChanged?.invoke("🔵 WebSocket соединён. Инициализация Gemini Live Translate...")
                sendSetup(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (webSocket !== ws) return
                handleServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (webSocket !== ws) return
                handleServerMessage(bytes.utf8())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) {
                    Log.d(tag, "Игнорирование onFailure для устаревшего сокета: ${t.message}")
                    return
                }
                isSetupComplete = false
                setupWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                setupWatchdogRunnable = null
                hasPendingTranslation = false
                currentTurnInputSpeech = null
                activeTranslationTurnId = null
                lastAsrPhrase = null
                consecutiveAsrRepeatCount = 0
                isRotationPending = false
                sessionRotationRunnable?.let { mainHandler.removeCallbacks(it) }
                sessionRotationRunnable = null
                mainHandler.removeCallbacks(simulIdleRunnable)
                goAwayRunnable?.let { mainHandler.removeCallbacks(it) }
                goAwayRunnable = null
                val httpCode = response?.code ?: run {
                    val msg = t.message ?: ""
                    val m = Regex("""\b(400|401|403)\b""").find(msg)
                    m?.value?.toIntOrNull()
                }
                if (httpCode == 400 || httpCode == 401 || httpCode == 403) {
                    val errorLabel = if (httpCode == 400) {
                        "Некорректный запрос (HTTP 400)"
                    } else {
                        "Ошибка авторизации (HTTP $httpCode): проверьте API ключ"
                    }
                    connectionState = "FATAL_ERROR ($httpCode)"
                    lastError = "Фатальная ошибка HTTP $httpCode: ${t.message}"
                    logEvent("HTTP_ERROR_FATAL", "code=$httpCode, msg=${t.message}")
                    onStatusChanged?.invoke("🔴 $errorLabel")
                    isRunning = false
                    isServiceActive = false
                    try {
                        audioRecord?.stop()
                    } catch (e: Exception) {
                        Log.w(tag, "Ошибка остановки audioRecord: ${e.message}")
                    }
                    triggerFailureHaptic()
                    try {
                        val nm = getSystemService(NotificationManager::class.java)
                        val fatalFg = buildNotification("🔴 $errorLabel")
                        nm?.notify(NOTIFICATION_ID, fatalFg)
                    } catch (e: Exception) {
                        Log.w(tag, "Ошибка обновления уведомления: ${e.message}")
                    }
                    stopSelf()
                    return
                }
                handleConnectionDrop("Сбой сети: ${t.message}")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                Log.d(tag, "WebSocket закрывается ($code: $reason)")
                isSetupComplete = false
                setupWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                setupWatchdogRunnable = null
                try {
                    ws.close(code, reason)
                } catch (e: Exception) {}
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws && code != 1000) {
                    Log.d(tag, "Игнорирование onClosed для устаревшего сокета ($code: $reason)")
                    return
                }
                Log.d(tag, "WebSocket закрыт ($code: $reason)")
                isSetupComplete = false
                setupWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                setupWatchdogRunnable = null
                hasPendingTranslation = false
                currentTurnInputSpeech = null
                activeTranslationTurnId = null
                isRotationPending = false
                sessionRotationRunnable?.let { mainHandler.removeCallbacks(it) }
                sessionRotationRunnable = null
                goAwayRunnable?.let { mainHandler.removeCallbacks(it) }
                goAwayRunnable = null
                mainHandler.removeCallbacks(simulIdleRunnable)
                lastAsrPhrase = null
                consecutiveAsrRepeatCount = 0
                if (isRunning) {
                    if (code == 1000) {
                        // Google 10-15 min Session Rotation: DO NOT call stopSelf()!
                        connectionState = "RECONNECTING (Rotation)"
                        activeTranslationTurnId = null
                        webSocket = null
                        audioSendQueue.clear()
                        logEvent("SESSION_ROTATION_RECONNECT", "code=$code, reason=$reason")
                        onStatusChanged?.invoke("🔄 Ротация сессии Google (15 мин). Мгновенное возобновление...")
                        val rotationTask = Runnable {
                            sessionRotationRunnable = null
                            if (isRunning && activeApiKey.isNotBlank()) {
                                audioSendQueue.clear()
                                connectWebSocket(activeApiKey)
                            }
                        }
                        sessionRotationRunnable = rotationTask
                        // Выдерживаем паузу 1.5с для освобождения сессии и квоты бэкендом Google Live
                        mainHandler.postDelayed(rotationTask, 1500L)
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
        isSetupComplete = false
        setupWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        setupWatchdogRunnable = null
        hasPendingTranslation = false
        currentTurnInputSpeech = null
        activeTranslationTurnId = null
        lastAsrPhrase = null
        consecutiveAsrRepeatCount = 0
        isRotationPending = false
        sessionRotationRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionRotationRunnable = null
        mainHandler.removeCallbacks(simulIdleRunnable)
        goAwayRunnable?.let { mainHandler.removeCallbacks(it) }
        goAwayRunnable = null
        audioSendQueue.clear()
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
        audioSendQueue.clear()
        val setupObj = JSONObject().apply {
            put("model", "models/gemini-3.5-live-translate-preview")
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", "Puck")
                        })
                    })
                })
                put("translationConfig", JSONObject().apply {
                    put("targetLanguageCode", "ru")
                    put("echoTargetLanguage", false)
                })
            })
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            put("realtimeInputConfig", JSONObject().apply {
                put("activityHandling", "NO_INTERRUPTION")
                put("automaticActivityDetection", JSONObject().apply {
                    put("disabled", false)
                    put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                    put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                    put("silenceDurationMs", 800)
                    put("prefixPaddingMs", 150)
                })
            })
        }

        val setupJson = JSONObject().apply {
            put("setup", setupObj)
        }
        val enqueued = ws.send(setupJson.toString())
        logEvent("SETUP_SENT", "model=models/gemini-3.5-live-translate-preview, target=ru, vad=[silence=800ms, NO_INTERRUPTION, sensitivity=LOW]")
        if (!enqueued) {
            Log.e(tag, "Ошибка отправки Setup: ws.send вернул false!")
            logEvent("SETUP_SEND_FAILED")
            handleConnectionDrop("Сбой отправки Setup в сокет")
            return
        }

        // Запуск Watchdog таймера на случай зависания Google при инициализации сессии (5 сек)
        setupWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        val watchdog = Runnable {
            if (isRunning && !isSetupComplete && webSocket === ws) {
                Log.w(tag, "Setup Complete Watchdog: ответ setupComplete не получен в течение 5с. Переподключение...")
                logEvent("SETUP_TIMEOUT", "Waited 5s for setupComplete")
                webSocket = null
                try {
                    ws.cancel()
                } catch (e: Exception) {}
                handleConnectionDrop("Таймаут инициализации Gemini Live (setupComplete не получен)")
            }
        }
        setupWatchdogRunnable = watchdog
        mainHandler.postDelayed(watchdog, 5000L)
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            serverMessagesReceived++
            val root = JSONObject(jsonText)

            // 1. Сигнал ротации goAway от сервера Google Live
            if (root.has("goAway")) {
                val goAwayObj = root.optJSONObject("goAway")
                val timeLeft = goAwayObj?.optString("timeLeft", "") ?: ""
                logEvent("SERVER_GO_AWAY", "timeLeft=$timeLeft")
                Log.w(tag, "Получен сигнал goAway от Google Live (timeLeft=$timeLeft). Планирование чистой ротации...")
                val currentWs = webSocket
                isRotationPending = true
                goAwayRunnable?.let { mainHandler.removeCallbacks(it) }

                fun performGracefulClose() {
                    if (isRunning && webSocket === currentWs) {
                        try {
                            isSetupComplete = false
                            currentWs?.close(1000, "Clean session rotation")
                        } catch (e: Exception) {
                            currentWs?.cancel()
                        }
                    }
                }

                if (activeTranslationTurnId != null || hasPendingTranslation) {
                    val runnable = Runnable { performGracefulClose() }
                    goAwayRunnable = runnable
                    mainHandler.postDelayed(runnable, 8000L)
                } else {
                    val runnable = Runnable { performGracefulClose() }
                    goAwayRunnable = runnable
                    mainHandler.postDelayed(runnable, 2000L)
                }
            }

            // 3. Подтверждение готовности setupComplete
            if (root.has("setupComplete")) {
                setupWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                setupWatchdogRunnable = null
                isSetupComplete = true
                Log.d(tag, "Setup завершён! Запуск микрофона...")
                connectionState = "READY (Streaming)"
                if (reconnectAttempts > 0) {
                    logEvent("RECONNECT_SUCCESS", "attempts=$reconnectAttempts")
                    reconnectAttempts = 0
                }
                logEvent("SETUP_COMPLETE", "(model=gemini-3.5-live-translate-preview, mic=$activeAudioSource)")
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

            // 3.1 Обработка серверных ошибок Google Live
            if (root.has("error")) {
                val errObj = root.optJSONObject("error")
                val code = errObj?.optInt("code", 0) ?: 0
                val msg = errObj?.optString("message", "") ?: errObj?.toString() ?: "Unknown error"
                val errText = "Ошибка сервера Google Live ($code): $msg"
                lastError = errText
                isSetupComplete = false
                mainHandler.removeCallbacks(simulIdleRunnable)
                logEvent("SERVER_ERROR", "code=$code, message=$msg")
                onStatusChanged?.invoke("🔴 $errText")
                Log.e(tag, errText)
                triggerFailureHaptic()
                try {
                    webSocket?.cancel()
                } catch (e: Exception) {}
                webSocket = null
                if (code == 400 || code == 401 || code == 403) {
                    // Неисправимая ошибка (неверный запрос или доступ запрещён) — полная остановка
                    isRunning = false
                    isServiceActive = false
                    try {
                        audioRecord?.stop()
                    } catch (e: Exception) {}
                    updateOngoingNotification("🔴 Ошибка $code", errText)
                    stopSelf()
                } else {
                    // Восстановимая ошибка (429 rate limit, 5xx) — переподключение
                    handleConnectionDrop(errText)
                }
                return
            }

            val serverContent = root.optJSONObject("serverContent")
            if (serverContent == null && !root.has("outputTranscription") && !root.has("outputAudioTranscription") && !root.has("turnComplete") && !root.has("finished")) {
                return
            }

            // Мгновенный Barge-in: сбрасываем звук при прерывании
            if (serverContent?.optBoolean("interrupted", false) == true || root.optBoolean("interrupted", false)) {
                hasPendingTranslation = false
                currentTurnInputSpeech = null
                activeTranslationTurnId = null
                activeDialogueTurn = null
                audioPlaybackQueue.clear()
                queuedPlaybackBytes.set(0L)
                isPlaybackSpeedBoosted = false
                mainHandler.removeCallbacks(simulIdleRunnable)
                finalizeCurrentTurn()
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
            val inputObj = serverContent?.optJSONObject("inputTranscription")
                ?: serverContent?.optJSONObject("inputAudioTranscription")
                ?: serverContent?.optJSONObject("interimInputTranscription")
                ?: root.optJSONObject("inputTranscription")
                ?: root.optJSONObject("inputAudioTranscription")
            var inputText = inputObj?.optString("text", "") ?: ""

            // Fallback на userTurn при наличии
            if (inputText.isEmpty() && serverContent != null) {
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
            val outputObj = serverContent?.optJSONObject("outputTranscription")
                ?: serverContent?.optJSONObject("outputAudioTranscription")
                ?: serverContent?.optJSONObject("interimOutputTranscription")
                ?: root.optJSONObject("outputTranscription")
                ?: root.optJSONObject("outputAudioTranscription")
            val outputText = outputObj?.optString("text", "") ?: ""
            val isFinished = outputObj?.optBoolean("finished", false) == true ||
                             serverContent?.optBoolean("finished", false) == true ||
                             root.optBoolean("finished", false)

            var translationReceived = false

            if (outputText.isNotEmpty()) {
                val cleanedOutput = cleanServerTags(outputText)
                if (cleanedOutput.isNotEmpty()) {
                    recordTranslationOutput(cleanedOutput)
                    translationReceived = true
                }
            }

            // 6. Аудио PCM и возможные текстовые фрагменты из modelTurn.parts
            val modelTurn = serverContent?.optJSONObject("modelTurn")
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
                            val cleanedText = cleanServerTags(text)
                            if (cleanedText.isNotEmpty()) {
                                recordTranslationOutput(cleanedText)
                                translationReceived = true
                            }
                        }
                    }
                }
            }

            // Управление скользящим таймером бездействия при получении чанков перевода
            if (translationReceived) {
                mainHandler.removeCallbacks(simulIdleRunnable)
                mainHandler.postDelayed(simulIdleRunnable, SIMUL_IDLE_DELAY_MS)
            }

            // 7. Проверка завершения хода (turnComplete / finished) и финализация карточки
            val turnComplete = isFinished ||
                               serverContent?.optBoolean("turnComplete", false) == true ||
                               root.optBoolean("turnComplete", false) ||
                               (modelTurn?.optBoolean("turnComplete", false) == true)
            if (turnComplete) {
                activeTranslationTurnId = null
                synchronized(sessionDialogueTurns) {
                    for (i in 0 until sessionDialogueTurns.size) {
                        val turn = sessionDialogueTurns[i]
                        // Финализируем ход только если перевод уже получен и грамматически завершен (. ! ?)
                        // Если русский перевод ещё пуст (в полёте по сети), КАТЕГОРИЧЕСКИ НЕЛЬЗЯ финализировать!
                        if (!turn.isFinalized && turn.russianText.isNotBlank()) {
                            val isEnding = isSentenceEnding(turn.russianText) &&
                                !isContinuation(turn.russianText) &&
                                !isContinuation(turn.italianText)
                            if (isEnding) {
                                turn.isFinalized = true
                                turn.lastActivityTime = System.currentTimeMillis()
                                val turnCopy = turn.copy()
                                onDialogueTurnUpdated?.invoke(i, turnCopy, false)
                            }
                        }
                    }
                }
                if (isRotationPending) {
                    Log.d(tag, "Ход завершён, выполняется запланированная ротация goAway...")
                    goAwayRunnable?.let { mainHandler.removeCallbacks(it) }
                    goAwayRunnable = null
                    isRotationPending = false
                    val currentWs = webSocket
                    if (isRunning && currentWs != null) {
                        try {
                            isSetupComplete = false
                            currentWs.close(1000, "Clean session rotation")
                        } catch (e: Exception) {
                            currentWs.cancel()
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

    private fun attachHardwareEffects(sessionId: Int, skipNoiseSuppressor: Boolean = false) {
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

        if (skipNoiseSuppressor) {
            logEvent("HW_NS_SKIPPED", "VOICE_RECOGNITION active (room mic speech preserved)")
            Log.d(tag, "NoiseSuppressor пропущен для VOICE_RECOGNITION (сохранение дальней речи)")
            return
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
        var peak = 0
        for (i in 0 until length) {
            val absVal = kotlin.math.abs(buffer[i].toInt())
            if (absVal > peak) peak = absVal
        }
        val peakRatio = peak.toFloat() / 32768.0f
        val gateLow = 0.010f  // 1.0% (~328)
        val gateHigh = 0.014f // 1.4% (~458)

        // Мягкий Noise Gate: если пик чанка ниже 1.0%–1.4% (фоновый шум комнаты, музыка, тишина),
        // плавно ослабляем сигнал вместо усиления на +14 дБ.
        // Это позволяет серверному VAD Gemini мгновенно распознавать паузы между предложениями.
        if (peakRatio < gateLow) {
            val attenuation = 0.25f // -12 дБ подавление фонового шума
            for (i in 0 until length) {
                buffer[i] = (buffer[i] * attenuation).toInt().toShort()
            }
            return
        }

        val baseGain = 4.85f // +13.7 dB boost (~4.5x–5.0x) для тихой речи (<10%)
        val effectiveGain = if (peakRatio < gateHigh) {
            // Плавный переход от подавления к полному AGC без щелчков на границе гейта
            val t = (peakRatio - gateLow) / (gateHigh - gateLow)
            0.25f + t * (baseGain - 0.25f)
        } else {
            baseGain
        }

        for (i in 0 until length) {
            val s = buffer[i].toFloat() / 32768.0f
            val scaled = s * effectiveGain
            val saturated = kotlin.math.tanh(scaled.toDouble()).toFloat()
            val sampleOut = (saturated * 32767.0f).toInt()
            buffer[i] = sampleOut.coerceIn(-32768, 32767).toShort()
        }
    }

    private fun createAudioRecord(sampleRate: Int, bufferSize: Int): Pair<AudioRecord?, String> {
        val sources = if (selectedMicSource == MicSource.SMARTPHONE) {
            listOf(
                Pair(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION (Распознавание речи)"),
                Pair(MediaRecorder.AudioSource.MIC, "MIC (Встроенный микрофон смартфона)"),
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
                    val skipNs = (source == MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    attachHardwareEffects(record.audioSessionId, skipNoiseSuppressor = skipNs)
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

    private fun startRecordThread() {
        recordThread = Thread {
            // Чанк 100 мс при 16 кГц Mono 16-бит = 1600 сэмплов (Shorts) = 3200 байт
            val shortBuffer = ShortArray(1600)

            while (isRunning && !Thread.currentThread().isInterrupted) {
                try {
                    val read = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    if (Thread.currentThread().isInterrupted) break
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
                        if (read < 0) {
                            audioReadErrors++
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
        startRecordThread()

        // Поток 2: Отдельный независимый поток отправки аудиочанков в WebSocket
        sendThread = Thread {
            var lastTelemetryEmit = 0L
            var lastLogStreamEmit = 0L

            while (isRunning) {
                try {
                    // Защита: не отправляем аудио в сокет, пока сервер не прислал setupComplete.
                    // Удерживаем чанки в audioSendQueue до подтверждения готовности сессии.
                    if (!isSetupComplete) {
                        try {
                            Thread.sleep(40)
                        } catch (e: InterruptedException) {
                            break
                        }
                        continue
                    }

                    val chunk = audioSendQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue

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

    fun applyEngineChange(newEngine: TranslationEngine) {
        logEvent("ENGINE_SWITCH_REQUESTED", "newEngine=$newEngine")
        if (!isRunning) return

        mainHandler.post {
            if (newEngine == TranslationEngine.CASCADED_REST) {
                // Shut down WebSocket Live components
                try {
                    sessionRotationRunnable?.let { mainHandler.removeCallbacks(it) }
                    setupWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                    goAwayRunnable?.let { mainHandler.removeCallbacks(it) }
                    webSocket?.close(1000, "Switching to Cascaded REST")
                } catch (e: Exception) {
                    webSocket?.cancel()
                }
                webSocket = null
                isSetupComplete = false
                isRotationPending = false

                try {
                    recordThread?.interrupt()
                    audioRecord?.stop()
                } catch (e: Exception) {}
                recordThread = null

                try {
                    sendThread?.interrupt()
                } catch (e: Exception) {}
                sendThread = null

                releaseHardwareEffects()
                try {
                    audioRecord?.release()
                } catch (e: Exception) {}
                audioRecord = null
                audioSendQueue.clear()

                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (e: Exception) {}
                audioTrack = null

                // Start Cascaded REST components
                setupAudioRouting()
                initTts()
                initSpeechRecognizer()
                startListeningSafe()
                val statusMsg = "⚡ Каскадный режим активен (ASR + REST Flash)"
                onStatusChanged?.invoke(statusMsg)
                updateOngoingNotification(
                    "⚡ Каскадный ASR+REST",
                    "PocketHUD v1.0.43: переключено на каскадный перевод (ASR + REST Flash)"
                )
            } else {
                // Shut down Cascaded REST components
                mainHandler.removeCallbacks(ttsWatchdogRunnable)
                try {
                    speechRecognizer?.stopListening()
                    speechRecognizer?.destroy()
                } catch (e: Exception) {}
                speechRecognizer = null

                try {
                    tts?.stop()
                } catch (e: Exception) {}
                isTtsSpeaking = false
                isRecognitionPaused = false

                // Start WebSocket Live components
                setupAudioRouting()
                initAudioOutput()
                val statusMsg = "🔵 Подключение к Google Gemini Live API..."
                onStatusChanged?.invoke(statusMsg)
                updateOngoingNotification(
                    "🔵 Gemini Live WS",
                    "PocketHUD v1.0.43: переключено на Gemini Live WebSocket..."
                )
                connectWebSocket(activeApiKey)
            }
            onEngineChanged?.invoke(newEngine)
        }
    }

    fun applyRestModelChange(newModel: String) {
        logEvent("REST_MODEL_CHANGED", "newModel=$newModel")
        activeRestModel = newModel
    }

    private fun initTts() {
        if (tts != null) return
        try {
            tts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale("ru", "RU"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(tag, "Русский язык TTS не поддерживается или отсутствуют данные")
                        logEvent("TTS_LANG_UNSUPPORTED", "lang=ru-RU")
                    } else {
                        isTtsInitialized = true
                        tts?.setSpeechRate(1.1f)
                        tts?.setPitch(1.0f)
                        logEvent("TTS_INITIALIZED", "lang=ru-RU, rate=1.1")
                    }
                } else {
                    Log.w(tag, "Ошибка инициализации TextToSpeech: статус $status")
                    logEvent("TTS_INIT_FAILED", "status=$status")
                }
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isTtsSpeaking = true
                    mainHandler.removeCallbacks(ttsWatchdogRunnable)
                    mainHandler.postDelayed(ttsWatchdogRunnable, 12000L)
                }

                override fun onDone(utteranceId: String?) {
                    isTtsSpeaking = false
                    mainHandler.removeCallbacks(ttsWatchdogRunnable)
                    mainHandler.postDelayed({
                        if (isRunning && selectedEngine == TranslationEngine.CASCADED_REST && !isTtsSpeaking) {
                            resumeRecognition()
                        }
                    }, 150L)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isTtsSpeaking = false
                    mainHandler.removeCallbacks(ttsWatchdogRunnable)
                    mainHandler.post {
                        if (isRunning && selectedEngine == TranslationEngine.CASCADED_REST) {
                            resumeRecognition()
                        }
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    isTtsSpeaking = false
                    mainHandler.removeCallbacks(ttsWatchdogRunnable)
                    mainHandler.post {
                        if (isRunning && selectedEngine == TranslationEngine.CASCADED_REST) {
                            resumeRecognition()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(tag, "Исключение TextToSpeech: ${e.message}")
            logEvent("TTS_EXCEPTION", "${e.message}")
        }
    }

    private fun speakTts(russianText: String) {
        if (isAudioMuted || !isTtsInitialized || tts == null || russianText.isBlank()) return
        val cleanSpeech = sanitizeTtsSpeech(russianText)
        if (cleanSpeech.isBlank()) return

        isTtsSpeaking = true
        pauseRecognition()

        val utteranceId = "pockethud_tts_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val result = tts?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        logEvent("TTS_SPEAK", "utteranceId=$utteranceId, res=$result, text=\"$cleanSpeech\"")
        if (result != TextToSpeech.SUCCESS) {
            Log.w(tag, "tts.speak вернул код ошибки: $result, сброс состояния")
            isTtsSpeaking = false
            mainHandler.removeCallbacks(ttsWatchdogRunnable)
            resumeRecognition()
        }
    }

    private fun sanitizeTtsSpeech(raw: String): String {
        return raw.replace(Regex("[«»\"'*\\[\\]{}()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            val err = "🔴 Ошибка: SpeechRecognizer недоступен на данном устройстве"
            logEvent("SPEECH_RECOGNIZER_UNAVAILABLE")
            onStatusChanged?.invoke(err)
            return
        }

        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {}

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    consecutiveSpeechErrors = 0
                    onStatusChanged?.invoke("⚡ Слушаю итальянскую речь (Каскадный ASR)...")
                }

                override fun onBeginningOfSpeech() {
                    logEvent("ASR_SPEECH_START")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    val amp = ((rmsdB + 2f) * 8.33f).toInt().coerceIn(0, 100)
                    micPeakAmplitude = amp
                    onTelemetryUpdated?.invoke(amp, chunksSent, serverTextChunksReceived)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    logEvent("ASR_SPEECH_END")
                }

                override fun onError(error: Int) {
                    lastSpeechRecognitionError = error
                    val errName = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                        else -> "ERROR_$error"
                    }
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        consecutiveSpeechErrors++
                    } else {
                        consecutiveSpeechErrors = 0
                    }
                    logEvent("ASR_ERROR", "code=$error, name=$errName, repeats=$consecutiveSpeechErrors")

                    if (isRunning && selectedEngine == TranslationEngine.CASCADED_REST && !isTtsSpeaking) {
                        val delayMs = when {
                            consecutiveSpeechErrors > 5 -> 1000L
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT -> 300L
                            else -> 50L
                        }
                        mainHandler.postDelayed({
                            startListeningSafe()
                        }, delayMs)
                    }
                }

                override fun onResults(results: Bundle?) {
                    consecutiveSpeechErrors = 0
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val finalText = matches?.firstOrNull()?.trim()
                    if (!finalText.isNullOrBlank()) {
                        logEvent("ASR_FINAL_SPEECH", "\"$finalText\"")
                        val turnId = commitFinalizedSpeech(finalText)
                        requestRestTranslation(finalText, turnId)
                    }
                    if (isRunning && selectedEngine == TranslationEngine.CASCADED_REST && !isTtsSpeaking) {
                        mainHandler.postDelayed({
                            startListeningSafe()
                        }, 50L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partialText = matches?.firstOrNull()?.trim()
                    if (!partialText.isNullOrBlank()) {
                        updateInterimSpeech(partialText)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListeningSafe() {
        if (!isRunning || selectedEngine != TranslationEngine.CASCADED_REST || isTtsSpeaking || isRecognitionPaused) {
            return
        }
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    initSpeechRecognizer()
                }
                val intent = recognizerIntent ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                speechRecognizer?.cancel()
                speechRecognizer?.startListening(intent)
                logEvent("ASR_LISTENING_START", "lang=it-IT")
            } catch (e: Exception) {
                Log.w(tag, "Ошибка startListening: ${e.message}")
                logEvent("ASR_START_EXCEPTION", "${e.message}")
                mainHandler.postDelayed({
                    if (isRunning && selectedEngine == TranslationEngine.CASCADED_REST && !isTtsSpeaking) {
                        try {
                            speechRecognizer?.cancel()
                            speechRecognizer?.startListening(recognizerIntent)
                        } catch (ex: Exception) {}
                    }
                }, 400L)
            }
        }
    }

    private fun pauseRecognition() {
        isRecognitionPaused = true
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                logEvent("ASR_PAUSED")
            } catch (e: Exception) {
                Log.w(tag, "Ошибка pauseRecognition: ${e.message}")
            }
        }
    }

    private fun resumeRecognition() {
        isRecognitionPaused = false
        logEvent("ASR_RESUMED")
        if (isRunning && selectedEngine == TranslationEngine.CASCADED_REST && !isTtsSpeaking) {
            startListeningSafe()
        }
    }

    private fun restartSpeechRecognizer() {
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {}
            if (isRunning && selectedEngine == TranslationEngine.CASCADED_REST && !isTtsSpeaking) {
                initSpeechRecognizer()
                startListeningSafe()
            }
        }
    }

    private fun updateInterimSpeech(interim: String) {
        onInputTextReceived?.invoke(interim)
        synchronized(sessionDialogueTurns) {
            val now = System.currentTimeMillis()
            val curTurn = activeDialogueTurn
            if (curTurn != null && !curTurn.isFinalized) {
                curTurn.italianText = interim
                curTurn.lastActivityTime = now
                val idx = sessionDialogueTurns.indexOf(curTurn)
                if (idx != -1) {
                    onDialogueTurnUpdated?.invoke(idx, curTurn.copy(), false)
                }
            } else {
                val newTurn = DialogueTurn(
                    italianText = interim,
                    russianText = "",
                    timestamp = now,
                    lastActivityTime = now,
                    isFinalized = false
                )
                sessionDialogueTurns.add(newTurn)
                if (sessionDialogueTurns.size > 200) {
                    sessionDialogueTurns.removeAt(0)
                }
                activeDialogueTurn = newTurn
                val idx = sessionDialogueTurns.size - 1
                onDialogueTurnUpdated?.invoke(idx, newTurn.copy(), true)
            }
            try {
                DialogueHistoryManager.saveCurrentSessionCache(this@PocketService, sessionDialogueTurns)
            } catch (e: Exception) {
            }
        }
        scheduleNotificationUpdate(
            "🗣️ $interim",
            if (lastPostedSubtitleHistory.isNotBlank()) lastPostedSubtitleHistory else interim
        )
    }

    private fun commitFinalizedSpeech(finalText: String): Long {
        lastRecognizedInput = finalText
        appendToSessionBuilder(sessionItalianBuilder, finalText)
        onInputTextReceived?.invoke(finalText)

        synchronized(sessionDialogueTurns) {
            val now = System.currentTimeMillis()
            val curTurn = activeDialogueTurn
            val targetTurn = if (curTurn != null && !curTurn.isFinalized) {
                curTurn.italianText = finalText
                curTurn.lastActivityTime = now
                val idx = sessionDialogueTurns.indexOf(curTurn)
                if (idx != -1) {
                    onDialogueTurnUpdated?.invoke(idx, curTurn.copy(), false)
                }
                curTurn
            } else {
                val newTurn = DialogueTurn(
                    italianText = finalText,
                    russianText = "",
                    timestamp = now,
                    lastActivityTime = now,
                    isFinalized = false
                )
                sessionDialogueTurns.add(newTurn)
                if (sessionDialogueTurns.size > 200) {
                    sessionDialogueTurns.removeAt(0)
                }
                val idx = sessionDialogueTurns.size - 1
                onDialogueTurnUpdated?.invoke(idx, newTurn.copy(), true)
                newTurn
            }
            activeDialogueTurn = null
            try {
                DialogueHistoryManager.saveCurrentSessionCache(this@PocketService, sessionDialogueTurns)
            } catch (e: Exception) {}
            return targetTurn.id
        }
    }

    private fun requestRestTranslation(italianText: String, turnId: Long) {
        if (activeApiKey.isBlank()) {
            logEvent("REST_TRANSLATE_NO_KEY")
            return
        }

        logEvent("REST_TRANSLATE_REQUEST", "model=$activeRestModel, turnId=$turnId, text=\"$italianText\"")

        fun executeTranslationCall(modelIndex: Int) {
            if (modelIndex >= REST_FLASH_MODELS.size) {
                logEvent("REST_TRANSLATE_ALL_MODELS_FAILED", "turnId=$turnId, text=\"$italianText\"")
                mainHandler.post {
                    synchronized(sessionDialogueTurns) {
                        val turn = sessionDialogueTurns.find { it.id == turnId }
                        if (turn != null && !turn.isFinalized) {
                            turn.isFinalized = true
                            val idx = sessionDialogueTurns.indexOf(turn)
                            if (idx != -1) {
                                onDialogueTurnUpdated?.invoke(idx, turn.copy(), false)
                            }
                        }
                    }
                }
                return
            }

            val candidateModel = REST_FLASH_MODELS[modelIndex]
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:generateContent?key=$activeApiKey"

            val promptText = "Translate the following Italian text to Russian. Return ONLY the translation, nothing else. No explanations.\n\n$italianText"

            val jsonPayload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", promptText)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.3)
                    put("maxOutputTokens", 256)
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonPayload.toString().toRequestBody(mediaType)
            val req = Request.Builder().url(url).post(body).build()

            restHttpClient.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    Log.e(tag, "REST translation network error with model $candidateModel: ${e.message}")
                    logEvent("REST_TRANSLATE_FAIL", "model=$candidateModel, err=${e.message}")
                    executeTranslationCall(modelIndex + 1)
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use { resp ->
                        val respCode = resp.code
                        val respString = resp.body?.string() ?: ""

                        if (respCode == 200) {
                            try {
                                val root = JSONObject(respString)
                                val candidates = root.optJSONArray("candidates")
                                if (candidates != null && candidates.length() > 0) {
                                    val cand = candidates.getJSONObject(0)
                                    val content = cand.optJSONObject("content")
                                    val parts = content?.optJSONArray("parts")
                                    if (parts != null && parts.length() > 0) {
                                        val rawText = parts.getJSONObject(0).optString("text", "").trim()
                                        val cleaned = sanitizeRestTranslation(rawText)
                                        if (cleaned.isNotBlank()) {
                                            activeRestModel = candidateModel
                                            mainHandler.post {
                                                deliverRestTranslation(cleaned, turnId)
                                            }
                                            return
                                        }
                                    }
                                }
                                logEvent("REST_TRANSLATE_EMPTY_RESPONSE", "model=$candidateModel")
                            } catch (e: Exception) {
                                Log.e(tag, "Ошибка парсинга ответа REST перевода: ${e.message}")
                            }
                        } else if (respCode == 404 || respCode == 400) {
                            Log.w(tag, "Модель $candidateModel вернула HTTP $respCode, fallback на следующую модель...")
                            logEvent("REST_MODEL_NOT_FOUND", "model=$candidateModel, http=$respCode")
                            executeTranslationCall(modelIndex + 1)
                            return
                        } else {
                            Log.w(tag, "REST translation HTTP error: $respCode")
                            logEvent("REST_TRANSLATE_HTTP_ERROR", "model=$candidateModel, code=$respCode")
                            executeTranslationCall(modelIndex + 1)
                            return
                        }
                    }
                }
            })
        }

        val initialIdx = REST_FLASH_MODELS.indexOf(activeRestModel).coerceAtLeast(0)
        executeTranslationCall(initialIdx)
    }

    private fun deliverRestTranslation(russianTranslation: String, turnId: Long) {
        Log.d(tag, "Доставлен REST перевод: $russianTranslation (turnId=$turnId)")
        serverTextChunksReceived++
        lastServerText = russianTranslation

        val origItalian: String
        synchronized(sessionDialogueTurns) {
            var targetTurn = sessionDialogueTurns.find { it.id == turnId }
            if (targetTurn == null) {
                targetTurn = sessionDialogueTurns.findLast { it.russianText.isBlank() }
            }

            if (targetTurn != null) {
                targetTurn.russianText = russianTranslation
                targetTurn.isFinalized = true
                targetTurn.lastActivityTime = System.currentTimeMillis()
                origItalian = targetTurn.italianText.ifBlank { lastRecognizedInput }
                val idx = sessionDialogueTurns.indexOf(targetTurn)
                if (idx != -1) {
                    onDialogueTurnUpdated?.invoke(idx, targetTurn.copy(), false)
                }
            } else {
                origItalian = lastRecognizedInput
                val newTurn = DialogueTurn(
                    italianText = origItalian,
                    russianText = russianTranslation,
                    timestamp = System.currentTimeMillis(),
                    lastActivityTime = System.currentTimeMillis(),
                    isFinalized = true
                )
                sessionDialogueTurns.add(newTurn)
                if (sessionDialogueTurns.size > 200) {
                    sessionDialogueTurns.removeAt(0)
                }
                val idx = sessionDialogueTurns.size - 1
                onDialogueTurnUpdated?.invoke(idx, newTurn.copy(), true)
            }
            if (activeDialogueTurn?.id == turnId) {
                activeDialogueTurn = null
            }
            try {
                DialogueHistoryManager.saveCurrentSessionCache(this@PocketService, sessionDialogueTurns)
            } catch (e: Exception) {}
        }

        logEvent("TRANSLATION:", "ORIG=\"$origItalian\" -> RU=\"$russianTranslation\"")
        recentTranslations.append(russianTranslation).append(" ")
        if (recentTranslations.length > 800) {
            val cutIndex = recentTranslations.indexOf(". ", recentTranslations.length - 450)
            if (cutIndex != -1) {
                recentTranslations.delete(0, cutIndex + 2)
            } else {
                recentTranslations.delete(0, recentTranslations.length - 450)
            }
        }
        appendToSessionBuilder(sessionRussianBuilder, russianTranslation)
        onTextReceived?.invoke(russianTranslation)

        scheduleNotificationUpdate(russianTranslation, recentTranslations.toString().trim())

        if (!isAudioMuted && isTtsInitialized && russianTranslation.isNotBlank()) {
            speakTts(russianTranslation)
        }
    }

    fun sanitizeRestTranslation(raw: String): String {
        var text = raw.trim()
        text = text.removeSurrounding("\"").removeSurrounding("«", "»").trim()
        if (text.startsWith("Перевод:", ignoreCase = true)) {
            text = text.substring("Перевод:".length).trim()
        }
        if (text.startsWith("**Перевод:**", ignoreCase = true)) {
            text = text.substring("**Перевод:**".length).trim()
        }
        return text.trim()
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

        mainHandler.removeCallbacks(ttsWatchdogRunnable)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {}
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {}
        isTtsInitialized = false
        isTtsSpeaking = false
        isRecognitionPaused = false

        // Сохраняем полный финальный снимок диагностики перед обнулением instance
        lastDiagnosticSnapshot = buildDiagnosticSnapshot()

        // Автоматически архивируем завершённую сессию диалога в постоянную историю
        try {
            DialogueHistoryManager.saveSession(this, sessionDialogueTurns)
        } catch (e: Exception) {
            Log.e(tag, "Ошибка архивации диалога при остановке сервиса: ${e.message}", e)
        }

        restoreAudioRouting()
        releaseHardwareEffects()

        inputTagCleaner.reset()
        outputTagCleaner.reset()
        hasPendingTranslation = false
        currentTurnInputSpeech = null
        activeTranslationTurnId = null
        activeDialogueTurn = null
        queuedPlaybackBytes.set(0L)
        isPlaybackSpeedBoosted = false

        goAwayRunnable?.let { mainHandler.removeCallbacks(it) }
        goAwayRunnable = null
        setupWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        setupWatchdogRunnable = null
        sessionRotationRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionRotationRunnable = null
        isRotationPending = false
        mainHandler.removeCallbacks(simulIdleRunnable)

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
        nm?.cancel(NOTIFICATION_ID)
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        onStatusChanged?.invoke("⚪ Суфлёр остановлен.")
        currentInstance = null
        super.onDestroy()
    }
}
