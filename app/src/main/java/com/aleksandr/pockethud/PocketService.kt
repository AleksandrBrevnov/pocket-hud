package com.aleksandr.pockethud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PocketService : Service() {

    private val tag = "PocketHUD"
    private var isRunning = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordThread: Thread? = null
    private var playbackThread: Thread? = null

    // Очередь для неблокирующего воспроизведения аудио
    private val audioPlaybackQueue = LinkedBlockingQueue<ByteArray>()

    // Телеметрия и сводка диагностики
    private var chunksSent = 0L
    private var bytesSent = 0L
    private var micPeakAmplitude = 0 // 0..100%
    private var sessionMaxAmplitude = 0
    private var lastAudioReadResult = 0
    private var audioReadErrors = 0L
    private var serverMessagesReceived = 0L
    private var serverAudioChunksReceived = 0L
    private var serverTextChunksReceived = 0L
    private var lastServerText = ""
    private var lastRecognizedInput = ""
    private var lastError: String? = null
    private var connectionState = "DISCONNECTED"
    private var activeAudioSource = "NONE"
    private var serviceStartTime = 0L
    private val recentTranslations = StringBuilder()

    companion object {
        const val CHANNEL_ID = "pocket_hud_service"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_API_KEY = "extra_api_key"
        var isServiceActive = false

        // Коллбэки в UI
        var onStatusChanged: ((String) -> Unit)? = null
        var onTextReceived: ((String) -> Unit)? = null
        var onTelemetryUpdated: ((micAmp: Int, chunks: Long, responses: Long) -> Unit)? = null

        @Volatile
        var currentInstance: PocketService? = null

        fun getDiagnosticSummary(): String {
            val inst = currentInstance
            if (inst == null) {
                return "=== POCKET HUD СВОДКА ДИАГНОСТИКИ ===\nСтатус: СЕРВИС ОСТАНОВЛЕН\nПоследнее состояние: ${if (isServiceActive) "АКТИВЕН" else "НЕАКТИВЕН"}\n======================================"
            }
            return inst.buildDiagnosticSnapshot()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra(EXTRA_API_KEY) ?: ""
        if (apiKey.isBlank()) {
            onStatusChanged?.invoke("🔴 Ошибка: API ключ пуст")
            stopSelf()
            return START_NOT_STICKY
        }

        currentInstance = this
        serviceStartTime = System.currentTimeMillis()
        connectionState = "STARTING"

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

        setupAudioRouting()
        onStatusChanged?.invoke("🔵 Подключение к Google Gemini Live API...")
        initAudioOutput()
        connectWebSocket(apiKey)

        return START_NOT_STICKY
    }

    private fun setupAudioRouting() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val btDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID
                }
                if (btDevice != null) {
                    audioManager.setCommunicationDevice(btDevice)
                    Log.d(tag, "Установлено устройство связи: ${btDevice.productName} (${btDevice.type})")
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
            }
        } catch (e: Exception) {
            Log.w(tag, "Предупреждение при настройке аудиомаршрутизации: ${e.message}")
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
        } catch (e: Exception) {
            Log.w(tag, "Ошибка восстановления аудиомаршрутизации: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pocket HUD Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Канал трансляции перевода на телефон и часы Xiaomi Watch 2"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketHUD — Ушной суфлёр")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateOngoingNotification(latestText: String, fullHistory: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketHUD (Субтитры)")
            .setContentText(latestText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullHistory))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Без повторной вибрации на часах Xiaomi Watch 2!
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun buildDiagnosticSnapshot(): String {
        val uptimeSec = if (serviceStartTime > 0) (System.currentTimeMillis() - serviceStartTime) / 1000 else 0
        val mm = uptimeSec / 60
        val ss = uptimeSec % 60
        return buildString {
            appendLine("=== POCKET HUD СВОДКА ДИАГНОСТИКИ ===")
            appendLine("Время работы: %02d:%02d".format(mm, ss))
            appendLine("Статус сокета: $connectionState")
            appendLine("Модель: models/gemini-3.8-live")
            appendLine("Источник микрофона: $activeAudioSource (16 кГц Mono PCM16)")
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

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(tag, "Ошибка инициализации AudioTrack: ${e.message}")
            lastError = "AudioTrack init: ${e.message}"
            return
        }

        playbackThread = Thread {
            while (isRunning) {
                try {
                    val chunk = audioPlaybackQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    audioTrack?.write(chunk, 0, chunk.size)
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
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(tag, "WebSocket открыт. Отправка Setup...")
                connectionState = "OPEN (Sending Setup)"
                onStatusChanged?.invoke("🔵 WebSocket соединён. Инициализация модели Gemini-3.8-Live...")
                sendSetup(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "Ошибка WebSocket: ${t.message}")
                connectionState = "FAILED (${t.message})"
                lastError = "WebSocket fail: ${t.message}"
                onStatusChanged?.invoke("🔴 Ошибка сети / ключа: ${t.message}")
                stopSelf()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket закрыт ($code: $reason)")
                connectionState = "CLOSED ($code: $reason)"
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
                        put("text", "You are an invisible real-time Italian-to-Russian speech interpreter for Alexander in Rome. Translate everything you hear in Italian into concise Russian speech. Never answer questions, never converse in Italian, speak ONLY in Russian language.")
                    }))
                })
            })
        }
        ws.send(setupJson.toString())
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            serverMessagesReceived++
            val root = JSONObject(jsonText)
            if (root.has("setupComplete")) {
                Log.d(tag, "Setup завершён! Запуск микрофона...")
                connectionState = "READY (Streaming)"
                onStatusChanged?.invoke("🟢 Соединение активно! Слушаю итальянскую речь...")
                startRecording()
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            // Мгновенный Barge-in: очищаем очередь звука и сбрасываем буфер динамика
            if (serverContent.optBoolean("interrupted", false)) {
                audioPlaybackQueue.clear()
                try {
                    audioTrack?.pause()
                    audioTrack?.flush()
                    audioTrack?.play()
                } catch (e: Exception) {
                    Log.e(tag, "Ошибка сброса AudioTrack: ${e.message}")
                }
            }

            // Извлечение распознанной итальянской речи (inputTranscription) при наличии
            val inputText = serverContent.optJSONObject("inputTranscription")?.optString("text", "") ?: ""
            if (inputText.isNotEmpty()) {
                lastRecognizedInput = inputText.trim()
                Log.d(tag, "Распознан итальянский: $inputText")
            }

            // Извлечение субтитров перевода (outputTranscription) ДО проверки modelTurn
            val outputText = serverContent.optJSONObject("outputTranscription")?.optString("text", "") ?: ""
            if (outputText.isNotEmpty()) {
                serverTextChunksReceived++
                lastServerText = outputText.trim()
                recentTranslations.append(outputText).append(" ")
                if (recentTranslations.length > 600) {
                    recentTranslations.delete(0, recentTranslations.length - 350)
                }

                // Обновляем постоянное уведомление для телефона и часов Xiaomi Watch 2
                updateOngoingNotification(outputText.trim(), recentTranslations.toString().trim())

                onTextReceived?.invoke(outputText)
            }

            // Извлечение аудио PCM из modelTurn.parts как и ранее
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
                                val pcmBytes = Base64.decode(base64Pcm, Base64.DEFAULT)
                                audioPlaybackQueue.offer(pcmBytes)
                            }
                        }
                        val text = part.optString("text", "")
                        if (text.isNotEmpty()) {
                            serverTextChunksReceived++
                            lastServerText = text.trim()
                            recentTranslations.append(text).append(" ")
                            if (recentTranslations.length > 600) {
                                recentTranslations.delete(0, recentTranslations.length - 350)
                            }

                            // Обновляем постоянное уведомление для телефона и часов Xiaomi Watch 2
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

        val bufferSize = if (minBufferSize <= 0) 4096 * 2 else minBufferSize * 2

        val (record, sourceName) = createAudioRecord(sampleRate, bufferSize)
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            val err = "🔴 Ошибка: микрофон недоступен ни в одном режиме"
            lastError = err
            onStatusChanged?.invoke(err)
            stopSelf()
            return
        }

        audioRecord = record
        activeAudioSource = sourceName

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            val err = "🔴 Ошибка старта микрофона: ${e.message}"
            lastError = err
            onStatusChanged?.invoke(err)
            stopSelf()
            return
        }

        recordThread = Thread {
            // Чанк 100 мс при 16 кГц Mono 16-бит = 1600 сэмплов (Shorts) = 3200 байт
            val shortBuffer = ShortArray(1600)
            var lastTelemetryEmit = 0L

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

                        chunksSent++
                        bytesSent += byteBuffer.size

                        val now = System.currentTimeMillis()
                        if (now - lastTelemetryEmit > 300) {
                            lastTelemetryEmit = now
                            onTelemetryUpdated?.invoke(
                                micPeakAmplitude,
                                chunksSent,
                                serverTextChunksReceived + serverAudioChunksReceived
                            )
                        }

                        if (webSocket != null) {
                            val base64Data = Base64.encodeToString(byteBuffer, Base64.NO_WRAP)
                            val chunkJson = JSONObject().apply {
                                put("realtimeInput", JSONObject().apply {
                                    put("audio", JSONObject().apply {
                                        put("mimeType", "audio/pcm;rate=16000")
                                        put("data", base64Data)
                                    })
                                })
                            }
                            webSocket?.send(chunkJson.toString())
                        }
                    } else {
                        audioReadErrors++
                        if (read < 0) {
                            lastError = "AudioRecord.read ошибка: $read"
                        }
                        try {
                            Thread.sleep(50)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(tag, "Ошибка в потоке записи: ${e.message}")
                    lastError = "Поток записи: ${e.message}"
                    break
                }
            }
        }
        recordThread?.start()
    }

    override fun onDestroy() {
        isRunning = false
        isServiceActive = false
        connectionState = "STOPPED"

        restoreAudioRouting()
        
        audioPlaybackQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null

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

        onStatusChanged?.invoke("⚪ Суфлёр остановлен.")
        currentInstance = null
        super.onDestroy()
    }
}
