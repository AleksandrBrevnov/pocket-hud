package com.aleksandr.pockethud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
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

    // Потокобезопасная очередь для неблокирующего воспроизведения
    private val audioPlaybackQueue = LinkedBlockingQueue<ByteArray>()

    companion object {
        const val CHANNEL_ID = "pocket_hud_service"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_API_KEY = "extra_api_key"
        var isServiceActive = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra(EXTRA_API_KEY) ?: ""
        if (apiKey.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

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

        initAudioOutput()
        connectWebSocket(apiKey)

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pocket HUD Service",
                NotificationManager.IMPORTANCE_LOW
            )
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
            .build()
    }

    private fun initAudioOutput() {
        val sampleRate = 24000
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Защита от крэша при недопустимом буфере аудиочипа
        val bufferSize = if (minBufferSize <= 0) 4096 * 2 else minBufferSize * 2

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
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
            return
        }

        // Отдельный поток воспроизведения — OkHttp поток сокета никогда не блокируется
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
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(tag, "WebSocket открыт. Отправка Setup...")
                sendSetup(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "Ошибка WebSocket: ${t.message}")
                stopSelf()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket закрыт ($code: $reason)")
                stopSelf()
            }
        })
    }

    private fun sendSetup(ws: WebSocket) {
        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/gemini-2.0-flash-exp")
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
                        put("text", "Ты — синхронный ушной суфлёр для Александра в Риме. Слушай итальянскую речь вокруг и шепчи краткий русский перевод чанками по 3-6 слов. Не здоровайся, не отвечай на вопросы, переводи только смысл.")
                    }))
                })
            })
        }
        ws.send(setupJson.toString())
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            val root = JSONObject(jsonText)
            if (root.has("setupComplete")) {
                Log.d(tag, "Setup завершён! Запуск микрофона...")
                startRecording()
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            // Мгновенный Barge-in: очищаем очередь звука и сбрасываем буфер динамика
            if (serverContent.optBoolean("interrupted", false)) {
                audioPlaybackQueue.clear()
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()
            }

            val modelTurn = serverContent.optJSONObject("modelTurn") ?: return
            val parts = modelTurn.optJSONArray("parts") ?: return

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val inlineData = part.optJSONObject("inlineData") ?: continue
                val base64Pcm = inlineData.optString("data", "")
                if (base64Pcm.isNotEmpty()) {
                    val pcmBytes = Base64.decode(base64Pcm, Base64.DEFAULT)
                    // Неблокирующее добавление в очередь воспроизведения
                    audioPlaybackQueue.offer(pcmBytes)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Ошибка парсинга: ${e.message}")
        }
    }

    private fun startRecording() {
        val sampleRate = 16000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufferSize = if (minBufferSize <= 0) 4096 * 2 else minBufferSize * 2

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "AudioRecord не инициализирован")
                stopSelf()
                return
            }

            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(tag, "Ошибка старта AudioRecord: ${e.message}")
            stopSelf()
            return
        }

        recordThread = Thread {
            val buffer = ByteArray(1024)
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read <= 0) {
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }

                if (webSocket != null) {
                    val base64Data = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    val chunkJson = JSONObject().apply {
                        put("realtimeInput", JSONObject().apply {
                            put("mediaChunks", JSONArray().put(JSONObject().apply {
                                put("mimeType", "audio/pcm;rate=16000")
                                put("data", base64Data)
                            }))
                        })
                    }
                    webSocket?.send(chunkJson.toString())
                }
            }
        }
        recordThread?.start()
    }

    override fun onDestroy() {
        isRunning = false
        isServiceActive = false
        
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

        super.onDestroy()
    }
}
