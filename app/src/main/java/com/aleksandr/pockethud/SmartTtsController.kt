package com.aleksandr.pockethud

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Режимы воспроизведения синтеза речи (Smart TTS).
 * - GIST_ONLY: Извлечение 2-4 опорных слов сути фразы в паузах между репликами.
 * - FULL_PHRASE: Озвучивание всей фразы целиком строго после паузы тишины (VAD).
 * - MUTED: Без звука (только визуальный вывод на экран).
 */
enum class TtsMode {
    GIST_ONLY,
    FULL_PHRASE,
    MUTED
}

/**
 * Smart TTS Engine (interfaces.md §2.2)
 *
 * Владеет: Воспроизведением перевода в наушник через системный TextToSpeech без глушения микрофона.
 *
 * Инварианты:
 * - В наушник озвучивается шепотом/кратко без отключения SpeechRecognizer (R01, G01).
 * - Локальный синтез Android TTS обеспечивает задержку <60 мс при бюджете €0.00 (G03).
 */
class SmartTtsController {
    companion object {
        private const val TAG = "SmartTtsController"

        private val defaultInstance = SmartTtsController()
        fun getInstance(): SmartTtsController = defaultInstance

        fun initialize(context: Context, onReady: (Boolean) -> Unit = {}) = defaultInstance.initialize(context, onReady)
        fun speak(text: String, mode: TtsMode = TtsMode.GIST_ONLY, isEarphoneConnected: Boolean = true) =
            defaultInstance.speak(text, mode, isEarphoneConnected)
        fun stop() = defaultInstance.stop()
        fun shutdown() = defaultInstance.shutdown()
        fun isSpeaking(): Boolean = defaultInstance.isSpeaking()
        fun isReady(): Boolean = defaultInstance.isReady()
        fun isEarphoneConnected(context: Context): Boolean = defaultInstance.isEarphoneConnected(context)

        // Стоп-слова и служебные части речи русского языка для извлечения сути (Gist-Only)
        private val STOP_WORDS = setOf(
            // Предлоги
            "в", "во", "на", "с", "со", "из", "из-за", "из-под", "к", "ко", "по", "о", "об", "обо",
            "при", "для", "до", "от", "ото", "под", "подо", "над", "надо", "за", "без", "через",
            "сквозь", "между", "среди", "около", "вокруг", "после", "перед", "ради", "у",
            // Союзы
            "и", "а", "но", "да", "или", "либо", "что", "чтобы", "как", "если", "то", "хотя",
            "будто", "словно", "также", "тоже", "потому",
            // Частицы
            "не", "ни", "бы", "б", "ли", "ль", "же", "ж", "вот", "вон", "даже", "лишь", "только",
            "ведь", "уже", "еще", "ещё",
            // Местоимения
            "я", "мы", "ты", "вы", "он", "она", "оно", "они", "меня", "нас", "тебя", "вас", "его",
            "ее", "её", "их", "мне", "нам", "тебе", "вам", "ему", "ей", "им", "мной", "нами",
            "тобой", "вами", "ею", "ими", "мой", "твой", "наш", "ваш", "свой", "этот", "эта",
            "это", "эти", "тот", "та", "то", "те", "такой", "такая", "такое", "такие", "сам",
            "весь", "вся", "всё", "все", "каждый", "любой", "кто", "что", "какой", "который",
            // Связки и глаголы бытия
            "быть", "был", "была", "было", "были", "будет", "будут", "есть", "является",
            // Вежливые маркеры и филлеры
            "пожалуйста", "спасибо", "здравствуйте", "привет", "ладно", "хорошо"
        )

        /**
         * Алгоритм извлечения сути: 2-4 ключевых опорных слова фразы перевода.
         */
        fun extractGist(text: String): String {
            if (text.isBlank()) return ""
            val clean = text.replace(Regex("""[«»"'\*,\.\[\]{}()!?:;—–\-]"""), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val tokens = clean.split(" ").filter { it.isNotBlank() }
            if (tokens.size <= 3) {
                return tokens.joinToString(" ")
            }

            // Фильтрация стоп-слов с сохранением одиночных цифр и буквенных маркеров (терминалы, номера)
            val contentWords = tokens.filter { word ->
                val lower = word.lowercase(Locale.ROOT)
                lower !in STOP_WORDS && (lower.length > 1 || lower.all { it.isDigit() } || (lower.length == 1 && word.all { it.isLetter() }))
            }

            // Если осталось 2-4 слова — возвращаем их
            if (contentWords.size in 2..4) {
                return contentWords.joinToString(" ")
            }
            // Если больше 4 — берем первые 4 опорных слова
            if (contentWords.size > 4) {
                return contentWords.take(4).joinToString(" ")
            }
            // Если все слова оказались стоп-словами, оставляем первые 2-3 слова исходной фразы
            val fallback = tokens.filter { it.length > 1 || it.all { c -> c.isDigit() } }.take(3)
            return if (fallback.isNotEmpty()) fallback.joinToString(" ") else tokens.take(2).joinToString(" ")
        }
    }

    @Volatile private var textToSpeech: TextToSpeech? = null
    @Volatile private var isTtsReady = false
    @Volatile private var isSpeakingNow = false

    @Synchronized
    fun initialize(context: Context, onReady: (Boolean) -> Unit = {}) {
        if (textToSpeech != null && isTtsReady) {
            onReady(true)
            return
        }
        try {
            textToSpeech = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = textToSpeech?.setLanguage(Locale("ru", "RU"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Русский язык TTS не поддерживается на данном устройстве")
                        isTtsReady = false
                        onReady(false)
                    } else {
                        isTtsReady = true
                        textToSpeech?.setSpeechRate(1.1f)
                        textToSpeech?.setPitch(1.0f)
                        Log.i(TAG, "SmartTtsController успешно инициализирован (ru-RU, 1.1x)")
                        onReady(true)
                    }
                } else {
                    isTtsReady = false
                    onReady(false)
                }
            }

            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeakingNow = true
                }

                override fun onDone(utteranceId: String?) {
                    isSpeakingNow = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeakingNow = false
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    isSpeakingNow = false
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации TextToSpeech: ${e.message}", e)
            isTtsReady = false
            onReady(false)
        }
    }

    @Synchronized
    fun speak(text: String, mode: TtsMode = TtsMode.GIST_ONLY, isEarphoneConnected: Boolean = true) {
        if (mode == TtsMode.MUTED || text.isBlank() || !isTtsReady || textToSpeech == null) {
            return
        }

        val speechText = when (mode) {
            TtsMode.GIST_ONLY -> extractGist(text)
            TtsMode.FULL_PHRASE -> text
            TtsMode.MUTED -> return
        }

        val cleanSpeech = speechText.replace(Regex("[«»\"'*\\[\\]{}()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleanSpeech.isBlank()) return

        try {
            val utteranceId = "pockethud_smart_tts_${System.currentTimeMillis()}"
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            textToSpeech?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            Log.d(TAG, "SmartTts speak: \"$cleanSpeech\", mode=$mode, earphone=$isEarphoneConnected")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка воспроизведения SmartTts: ${e.message}", e)
        }
    }

    fun isEarphoneConnected(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                devices.any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
            }
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            textToSpeech?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка остановки TTS: ${e.message}")
        } finally {
            isSpeakingNow = false
        }
    }

    @Synchronized
    fun shutdown() {
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка завершения TTS: ${e.message}")
        } finally {
            textToSpeech = null
            isTtsReady = false
            isSpeakingNow = false
        }
    }

    fun isSpeaking(): Boolean = isSpeakingNow
    fun isReady(): Boolean = isTtsReady
}
