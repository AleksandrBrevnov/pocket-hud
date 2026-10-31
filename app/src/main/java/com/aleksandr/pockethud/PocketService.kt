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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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

class PocketService : Service() {

    private val tag = "PocketHUD"
    private var isRunning = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

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

    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    companion object {
        const val CHANNEL_SERVICE_ID = "pocket_hud_service_v2"
        const val CHANNEL_SUBTITLES_ID = "pocket_hud_subtitles_v2"
        const val NOTIFICATION_ID = 1001
        const val SUBTITLE_NOTIFICATION_ID = 1002

        const val EXTRA_API_KEY = "extra_api_key"
        const val EXTRA_IS_MUTED = "extra_is_muted"

        var isServiceActive = false

        @Volatile
        var isAudioMuted: Boolean = false
            set(value) {
                field = value
                currentInstance?.applyMuteState(value)
            }

        // Коллбэки в UI
        var onStatusChanged: ((String) -> Unit)? = null
        var onTextReceived: ((String) -> Unit)? = null
        var onInputTextReceived: ((String) -> Unit)? = null
        var onTelemetryUpdated: ((micAmp: Int, chunks: Long, responses: Long) -> Unit)? = null

        // Буферы текущей активной сессии для восстановления в UI при разблокировке экрана
        private const val MAX_SESSION_BUILDER_LENGTH = 30000
        private val sessionItalianBuilder = StringBuilder()
        private val sessionRussianBuilder = StringBuilder()

        fun getSessionItalianText(): String = synchronized(sessionItalianBuilder) { sessionItalianBuilder.toString() }
        fun getSessionRussianText(): String = synchronized(sessionRussianBuilder) { sessionRussianBuilder.toString() }
        fun clearSessionTexts() {
            synchronized(sessionItalianBuilder) { sessionItalianBuilder.setLength(0) }
            synchronized(sessionRussianBuilder) { sessionRussianBuilder.setLength(0) }
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
            return "=== POCKET HUD СВОДКА ДИАГНОСТИКИ ===\nВерсия: v1.0.17\nСтатус: СЕРВИС ОСТАНОВЛЕН (Сессия ещё не запускалась)\nРежим звука: ${if (isAudioMuted) "🔇 ВЫКЛЮЧЕН" else "🔊 ВКЛЮЧЕН"}\n======================================"
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

        currentInstance = this
        clearSessionTexts()
        serviceStartTime = System.currentTimeMillis()
        connectionState = "STARTING"

        logEvent("SERVICE_START", "(isAudioMuted=$isAudioMuted)")

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
            "PocketHUD активен. Ожидание подключения к Gemini Live API..."
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

    private fun setupAudioRouting() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            var selectedDeviceName = "DEFAULT"

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

            logEvent("AUDIO_ROUTING_SETUP", selectedDeviceName)
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
            .setContentTitle("PocketHUD — Ушной суфлёр v1.0.17")
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
        return buildString {
            appendLine("=== POCKET HUD СВОДКА ДИАГНОСТИКИ ===")
            appendLine("Версия: v1.0.17")
            appendLine("Время работы: %02d:%02d".format(mm, ss))
            appendLine("Статус сокета: $connectionState")
            appendLine("Модель: models/gemini-3.8-live")
            appendLine("Режим звука: ${if (isAudioMuted) "🔇 ВЫКЛЮЧЕН (Только субтитры)" else "🔊 ВКЛЮЧЕН (Голос в наушник)"}")
            appendLine("Источник микрофона: $activeAudioSource (16 кГц Mono PCM16)")
            appendLine("--- ОЧЕРЕДИ АУДИО ---")
            appendLine("Очередь отправки mic (SendQueue): ${audioSendQueue.size} чанков")
            appendLine("Очередь вывода (PlaybackQueue): ${audioPlaybackQueue.size} чанков")
            appendLine("--- МИКРОФОН ---")
            appendLine("Текущая громкость: $micPeakAmplitude% (Макс за сессию: $sessionMaxAmplitude%)")
            appendLine("Последний read результат: $lastAudioReadResult")
            appendLine("Ошибок чтения mic: $audioReadErrors")
            appendLine("Отправлено чанков: $chunksSent ($bytesSent байт)")
            appendLine("--- GEMINI LIVE СЕРВЕР ---")
            appendLine("Сообщений получено: $serverMessagesReceived")
            appendLine("Аудио-ответов (звук в ухо): $serverAudioChunksReceived")
            appendLine("Текстовых ответов (HUD/Часы): $serverTextChunksReceived")
            appendLine("Последний распознанный IT: ${if (lastRecognizedInput.isBlank()) "нет" else "\"$lastRecognizedInput\""}")
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

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            while (isRunning) {
                try {
                    val chunk = audioPlaybackQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (!isAudioMuted) {
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
        connectionState = "CONNECTING"
        logEvent("WEBSOCKET_CONNECTING")

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(tag, "WebSocket открыт. Отправка Setup...")
                connectionState = "OPEN (Sending Setup)"
                logEvent("WEBSOCKET_OPEN")
                onStatusChanged?.invoke("🔵 WebSocket соединён. Инициализация модели Gemini-3.8-Live...")
                sendSetup(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "Ошибка WebSocket: ${t.message}")
                connectionState = "FAILED (${t.message})"
                lastError = "WebSocket fail: ${t.message}"
                logEvent("WEBSOCKET_FAILURE", "${t.message}")
                onStatusChanged?.invoke("🔴 Ошибка сети / ключа: ${t.message}")
                stopSelf()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket закрыт ($code: $reason)")
                connectionState = "CLOSED ($code: $reason)"
                logEvent("WEBSOCKET_CLOSED", "code=$code, reason=$reason")
                if (code != 1000) {
                    lastError = "WebSocket closed: $code $reason"
                    onStatusChanged?.invoke("🔴 Сервер закрыл соединение ($code: $reason)")
                } else {
                    onStatusChanged?.invoke("⚪ Суфлёр остановлен.")
                }
                stopSelf()
            }
        })
    }

    private fun sendSetup(ws: WebSocket) {
        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
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
                        put("text", "You are an invisible ultra-fast real-time speech interpreter from Italian to Russian for Alexander living in Rome. " +
                                    "CRITICAL RULES: " +
                                    "1. Translate IMMEDIATELY and CONTINUOUSLY phrase-by-phrase as soon as you hear Italian speech. " +
                                    "Do not wait for long pauses or for the speaker to finish long sentences. " +
                                    "2. Output ONLY the Russian translation. Be concise, fast, and accurate. " +
                                    "3. NEVER converse, NEVER explain, NEVER reply in Italian, NEVER answer questions. Your ONLY job is instantaneous Italian-to-Russian translation. " +
                                    "4. Ignore background noise and street/cafe ambiance — focus on human voices. If someone speaks Russian, stay silent. Translate ONLY Italian speech into Russian.")
                    }))
                })
                put("inputAudioTranscription", JSONObject().apply {
                    put("languageCodes", JSONArray().put("it-IT"))
                })
                put("outputAudioTranscription", JSONObject())
            })
        }
        ws.send(setupJson.toString())
        logEvent("SETUP_SENT", "model=models/gemini-3.8-live, lang=it-IT")
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            serverMessagesReceived++
            val root = JSONObject(jsonText)
            if (root.has("setupComplete")) {
                Log.d(tag, "Setup завершён! Запуск микрофона...")
                connectionState = "READY (Streaming)"
                logEvent("SETUP_COMPLETE", "(model=gemini-3.8-live, mic=$activeAudioSource)")
                onStatusChanged?.invoke("🟢 Соединение активно! Слушаю итальянскую речь...")
                updateOngoingNotification(
                    "🟢 Слушаю речь вокруг...",
                    "PocketHUD готов к переводу. Слушаю итальянскую речь..."
                )
                startRecording()
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            // Мгновенный Barge-in: сбрасываем звук при прерывании
            if (serverContent.optBoolean("interrupted", false)) {
                audioPlaybackQueue.clear()
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

            // 1. Распознанная итальянская речь (inputAudioTranscription / inputTranscription / interimInputTranscription / userTurn)
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
                lastRecognizedInput = inputText.trim()
                Log.d(tag, "Распознан итальянский: $inputText")
                logEvent("INPUT_IT:", "\"$lastRecognizedInput\"")
                appendToSessionBuilder(sessionItalianBuilder, inputText)
                onInputTextReceived?.invoke(inputText)
            }

            // 2. Субтитры перевода (outputAudioTranscription / outputTranscription / interimOutputTranscription)
            val outputObj = serverContent.optJSONObject("outputTranscription")
                ?: serverContent.optJSONObject("outputAudioTranscription")
                ?: serverContent.optJSONObject("interimOutputTranscription")
            val outputText = outputObj?.optString("text", "") ?: ""
            if (outputText.isNotEmpty()) {
                serverTextChunksReceived++
                lastServerText = outputText.trim()
                val itPrompt = if (lastRecognizedInput.isBlank()) "..." else lastRecognizedInput
                logEvent("TRANSLATION:", "IT=\"$itPrompt\" -> RU=\"$lastServerText\"")
                recentTranslations.append(outputText).append(" ")
                if (recentTranslations.length > 600) {
                    val cutIndex = recentTranslations.indexOf(' ', recentTranslations.length - 350)
                    if (cutIndex != -1) {
                        recentTranslations.delete(0, cutIndex + 1)
                    } else {
                        recentTranslations.delete(0, recentTranslations.length - 350)
                    }
                }

                appendToSessionBuilder(sessionRussianBuilder, outputText)

                updateOngoingNotification(outputText.trim(), recentTranslations.toString().trim())
                onTextReceived?.invoke(outputText)
            }

            // 3. Аудио PCM и возможные текстовые фрагменты из modelTurn.parts
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
                                }
                            }
                        }
                        val text = part.optString("text", "")
                        if (text.isNotEmpty()) {
                            serverTextChunksReceived++
                            lastServerText = text.trim()
                            logEvent("TRANSLATION_PART:", "RU=\"$lastServerText\"")
                            recentTranslations.append(text).append(" ")
                            if (recentTranslations.length > 600) {
                                val cutIndex = recentTranslations.indexOf(' ', recentTranslations.length - 350)
                                if (cutIndex != -1) {
                                    recentTranslations.delete(0, cutIndex + 1)
                                } else {
                                    recentTranslations.delete(0, recentTranslations.length - 350)
                                }
                            }

                            appendToSessionBuilder(sessionRussianBuilder, text)

                            updateOngoingNotification(text.trim(), recentTranslations.toString().trim())
                            onTextReceived?.invoke(text)
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

    private fun createAudioRecord(sampleRate: Int, bufferSize: Int): Pair<AudioRecord?, String> {
        val sources = listOf(
            Pair(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION (Гарнитура/Bluetooth SCO)"),
            Pair(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION (Распознавание речи)"),
            Pair(MediaRecorder.AudioSource.MIC, "MIC (Стандартный микрофон)"),
            Pair(MediaRecorder.AudioSource.DEFAULT, "DEFAULT")
        )

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

        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(SUBTITLE_NOTIFICATION_ID)

        onStatusChanged?.invoke("⚪ Суфлёр остановлен.")
        currentInstance = null
        super.onDestroy()
    }
}
