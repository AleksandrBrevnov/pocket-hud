package com.aleksandr.pockethud

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

sealed class AudioSummaryResult {
    abstract val cleanupWarning: String?

    data class Success(
        val summaryMarkdown: String,
        override val cleanupWarning: String? = null
    ) : AudioSummaryResult()

    data class Error(
        val userMessage: String,
        val cause: Throwable? = null,
        override val cleanupWarning: String? = null
    ) : AudioSummaryResult()
}

class AudioSummaryApi(
    client: OkHttpClient = defaultOkHttpClient(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val uploadBaseUrl: String = "https://generativelanguage.googleapis.com",
    private val maxPollAttempts: Int = 30,
    private val pollIntervalMs: Long = 1000L
) {
    // Enforce redirects disabled even if injected client had them enabled
    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    companion object {
        const val MODEL_NAME = "gemini-3.8-flash"
        private val FILE_NAME_REGEX = Regex("^files/[a-z0-9-]+$")

        val SUMMARY_PROMPT = """Сделай структурированный конспект прикреплённой аудиозаписи на русском языке.

Формат:
### О чём разговор
[краткая тема встречи в 1-2 предложениях]

### Главное
[ключевые тезисы диалога]

### Договорённости / действия
[кто, что и к какому сроку делает; конкретные задачи и сроки]

### Неясно
[неразборчивые фразы, сомнения или открытые вопросы]

Требования:
- Точно сохраняй отрицания, суммы, имена и даты.
- Ничего не додумывай и не добавляй от себя.
- Для сомнительных или неразборчивых фрагментов используй слово «неразборчиво».""".trimIndent()

        private fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }

    /**
     * Uploads WAV audio to Gemini Files API, waits for ACTIVE state, calls generateContent,
     * and performs DELETE cleanup of the uploaded file.
     * Supports active OkHttp cancellation checks and delivers cleanup warnings on both Success and Error.
     */
    fun generateSummary(
        audioFile: File,
        apiKey: String,
        onProgress: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        onCallCreated: (okhttp3.Call) -> Unit = {}
    ): AudioSummaryResult {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return AudioSummaryResult.Error("Укажите API-ключ Gemini в настройках приложения")
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return AudioSummaryResult.Error("Файл аудиозаписи пуст или не найден: ${audioFile.name}")
        }

        var uploadedFileName: String? = null
        val result: AudioSummaryResult = try {
            val (pipelineResult, fileName) = executePipeline(
                audioFile = audioFile,
                cleanKey = cleanKey,
                onProgress = onProgress,
                isCancelled = isCancelled,
                onCallCreated = onCallCreated,
                onUploadedFile = { name -> uploadedFileName = name }
            )
            if (uploadedFileName == null) {
                uploadedFileName = fileName
            }
            pipelineResult
        } catch (e: CancellationException) {
            AudioSummaryResult.Error("Формирование конспекта отменено", e)
        } catch (e: IOException) {
            AudioSummaryResult.Error("Сетевая ошибка при обращении к Gemini API: ${e.message}", e)
        } catch (e: Exception) {
            AudioSummaryResult.Error("Ошибка при получении сути аудио: ${e.message}", e)
        }

        // Guaranteed cleanup of uploaded file in Gemini, using a fresh non-cancelled call
        val cleanupWarning = uploadedFileName?.let { name -> performDeleteCleanup(name, cleanKey) }

        return if (cleanupWarning != null) {
            when (result) {
                is AudioSummaryResult.Success -> result.copy(cleanupWarning = cleanupWarning)
                is AudioSummaryResult.Error -> result.copy(cleanupWarning = cleanupWarning)
            }
        } else {
            result
        }
    }

    private fun executePipeline(
        audioFile: File,
        cleanKey: String,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean,
        onCallCreated: (okhttp3.Call) -> Unit,
        onUploadedFile: (String) -> Unit
    ): Pair<AudioSummaryResult, String?> {
        checkCancelled(isCancelled)
        onProgress("Инициализация загрузки аудио...")

        // Step 1: Start Resumable Upload
        val startUrl = "$uploadBaseUrl/upload/v1beta/files"
        val startMetadata = JSONObject().put("file", JSONObject().put("display_name", audioFile.name))
        val startReq = Request.Builder()
            .url(startUrl)
            .header("x-goog-api-key", cleanKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", audioFile.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", "audio/wav")
            .post(startMetadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val uploadUrl: String
        val startCall = client.newCall(startReq)
        onCallCreated(startCall)
        startCall.execute().use { resp ->
            if (!resp.isSuccessful) {
                return Pair(handleHttpError(resp, "создания сессии загрузки"), null)
            }
            uploadUrl = resp.header("X-Goog-Upload-URL")
                ?: resp.header("x-goog-upload-url")
                ?: return Pair(AudioSummaryResult.Error("Gemini API не вернул URL для загрузки файла"), null)
        }

        checkCancelled(isCancelled)

        // Validate upload URL
        val parsedUploadUrl = uploadUrl.toHttpUrlOrNull()
        if (parsedUploadUrl == null || !isTrustedUploadUrl(parsedUploadUrl)) {
            val hostDisplay = parsedUploadUrl?.host ?: "неизвестный"
            return Pair(AudioSummaryResult.Error("Небезопасный URL для загрузки аудио (host: $hostDisplay)"), null)
        }

        // Step 2: Upload audio bytes
        onProgress("Загрузка аудио в Gemini (${audioFile.length() / 1024} КБ)...")
        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .header("Content-Length", audioFile.length().toString())
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(audioFile.asRequestBody("audio/wav".toMediaType()))
            .build()

        var uploadedFileName: String? = null
        var fileUri: String
        var fileState: String
        val uploadCall = client.newCall(uploadReq)
        onCallCreated(uploadCall)
        uploadCall.execute().use { resp ->
            if (!resp.isSuccessful) {
                return Pair(handleHttpError(resp, "загрузки данных аудио"), null)
            }
            val respBody = resp.body?.string() ?: ""
            val json = try { JSONObject(respBody) } catch (e: Exception) {
                return Pair(AudioSummaryResult.Error("Gemini API вернул невалидный JSON при загрузке аудио"), null)
            }
            val fileObj = json.optJSONObject("file") ?: json
            val name = fileObj.optString("name")
            if (name.isBlank() || !FILE_NAME_REGEX.matches(name)) {
                return Pair(AudioSummaryResult.Error("Gemini API вернул некорректный дескриптор файла"), null)
            }
            uploadedFileName = name
            onUploadedFile(name)

            val rawUri = fileObj.optString("uri")
            if (rawUri.isBlank()) {
                return Pair(AudioSummaryResult.Error("Gemini API не вернул обязательный fileUri"), uploadedFileName)
            }
            val parsedUri = rawUri.toHttpUrlOrNull()
            if (parsedUri == null || !isTrustedUploadUrl(parsedUri)) {
                return Pair(AudioSummaryResult.Error("Gemini API вернул недоверенный fileUri"), uploadedFileName)
            }
            fileUri = rawUri
            fileState = fileObj.optString("state", "STATE_UNSPECIFIED")
        }

        checkCancelled(isCancelled)

        // Step 3: Polling for ACTIVE state
        var attempts = 0
        while (fileState == "PROCESSING" && attempts < maxPollAttempts) {
            attempts++
            onProgress("Обработка аудио в Gemini (попытка $attempts)...")
            try {
                Thread.sleep(pollIntervalMs)
            } catch (e: InterruptedException) {
                throw CancellationException("Ожидание обработки аудио было прервано")
            }

            checkCancelled(isCancelled)

            val pollReq = Request.Builder()
                .url("$baseUrl/v1beta/$uploadedFileName")
                .header("x-goog-api-key", cleanKey)
                .get()
                .build()

            val pollCall = client.newCall(pollReq)
            onCallCreated(pollCall)
            pollCall.execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Pair(handleHttpError(resp, "проверки состояния файла"), uploadedFileName)
                }
                val body = resp.body?.string() ?: ""
                val json = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    return Pair(AudioSummaryResult.Error("Не удалось разобрать статус файла в Gemini: невалидный JSON"), uploadedFileName)
                }
                val fileObj = json.optJSONObject("file") ?: json
                fileState = fileObj.optString("state", "STATE_UNSPECIFIED")
                val updatedUri = fileObj.optString("uri")
                if (updatedUri.isNotBlank()) {
                    val parsedUpdated = updatedUri.toHttpUrlOrNull()
                    if (parsedUpdated != null && isTrustedUploadUrl(parsedUpdated)) {
                        fileUri = updatedUri
                    }
                }
                if (fileState == "FAILED") {
                    val errMsg = fileObj.optJSONObject("error")?.optString("message") ?: "FAILED"
                    return Pair(AudioSummaryResult.Error("Обработка аудиофайла в Gemini завершилась с ошибкой: $errMsg"), uploadedFileName)
                }
            }
        }

        if (fileState == "PROCESSING") {
            return Pair(AudioSummaryResult.Error("Время ожидания обработки аудио в Gemini истекло (файл остался в состоянии PROCESSING)"), uploadedFileName)
        }
        if (fileState != "ACTIVE") {
            return Pair(AudioSummaryResult.Error("Файл аудио перешёл в недопустимое состояние: $fileState (ожидался ACTIVE)"), uploadedFileName)
        }

        checkCancelled(isCancelled)

        // Step 4: generateContent with Gemini 3.8 Flash
        onProgress("Формирование AI-конспекта встречи через $MODEL_NAME...")
        val genContentUrl = "$baseUrl/v1beta/models/$MODEL_NAME:generateContent"
        val genBody = JSONObject().put(
            "contents", JSONArray().put(
                JSONObject().put(
                    "parts", JSONArray()
                        .put(JSONObject().put("fileData", JSONObject().put("mimeType", "audio/wav").put("fileUri", fileUri)))
                        .put(JSONObject().put("text", SUMMARY_PROMPT))
                )
            )
        )

        val genReq = Request.Builder()
            .url(genContentUrl)
            .header("x-goog-api-key", cleanKey)
            .post(genBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val summaryText: String
        val genCall = client.newCall(genReq)
        onCallCreated(genCall)
        genCall.execute().use { resp ->
            if (!resp.isSuccessful) {
                return Pair(handleHttpError(resp, "генерации конспекта"), uploadedFileName)
            }
            val body = resp.body?.string() ?: ""
            val json = try { JSONObject(body) } catch (e: Exception) {
                return Pair(AudioSummaryResult.Error("Не удалось разобрать ответ Gemini: невалидный JSON"), uploadedFileName)
            }

            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val blockReason = json.optJSONObject("promptFeedback")?.optString("blockReason")
                return Pair(AudioSummaryResult.Error("Запрос заблокирован политиками Gemini (${blockReason ?: "пустой ответ"})"), uploadedFileName)
            }

            val candidate = candidates.getJSONObject(0)
            val finishReason = candidate.optString("finishReason", "")
            if (finishReason != "STOP") {
                val msg = when (finishReason) {
                    "MAX_TOKENS" -> "Ответ Gemini превысил лимит токенов (MAX_TOKENS). Выберите меньший интервал записи."
                    "SAFETY", "RECITATION" -> "Ответ заблокирован фильтрами Gemini ($finishReason)"
                    "BLOCKLIST", "PROHIBITED_CONTENT" -> "Ответ заблокирован политиками Gemini ($finishReason)"
                    "" -> "Gemini вернул ответ без указания finishReason"
                    else -> "Генерация конспекта завершилась со статусом: $finishReason"
                }
                return Pair(AudioSummaryResult.Error(msg), uploadedFileName)
            }

            val content = candidate.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts == null || parts.length() == 0) {
                return Pair(AudioSummaryResult.Error("Gemini вернул пустой результат без текста"), uploadedFileName)
            }

            val sb = java.lang.StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                // Discard thoughts if model returns reasoning traces
                if (part.optBoolean("thought", false)) continue
                val text = part.optString("text", "")
                if (text.isNotEmpty()) sb.append(text)
            }

            val extracted = sb.toString().trim()
            if (extracted.isEmpty()) {
                return Pair(AudioSummaryResult.Error("Gemini вернул пустой текст конспекта"), uploadedFileName)
            }
            summaryText = extracted
        }

        return Pair(AudioSummaryResult.Success(summaryText), uploadedFileName)
    }

    private fun performDeleteCleanup(fileName: String, cleanKey: String): String? {
        return try {
            val deleteReq = Request.Builder()
                .url("$baseUrl/v1beta/$fileName")
                .header("x-goog-api-key", cleanKey)
                .delete()
                .build()

            client.newCall(deleteReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    "Не удалось удалить временный файл из Gemini: HTTP ${resp.code}"
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            "Ошибка при удалении временного файла из Gemini: ${e.message}"
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) {
            throw CancellationException("Операция отменена пользователем")
        }
    }

    private fun handleHttpError(resp: okhttp3.Response, stage: String): AudioSummaryResult.Error {
        val code = resp.code
        val body = resp.body?.string() ?: ""
        val errorMsg = try {
            JSONObject(body).optJSONObject("error")?.optString("message")
        } catch (ignored: Exception) {
            null
        }

        val localized = when (code) {
            401, 403 -> "Ошибка авторизации: проверьте API-ключ Gemini в настройках (HTTP $code: ${errorMsg ?: "Доступ запрещен"})"
            404 -> "Модель или ресурс Gemini не найдены (HTTP 404: ${errorMsg ?: "Not Found"})"
            429 -> "Превышен лимит запросов к Gemini API (429 Resource Exhausted). Повторите попытку позже."
            in 500..599 -> "Ошибка сервера Gemini API (HTTP $code). Повторите попытку позже."
            else -> "Ошибка Gemini API на этапе $stage (HTTP $code: ${errorMsg ?: "код $code"})"
        }
        return AudioSummaryResult.Error(localized)
    }

    private fun isTrustedUploadUrl(url: HttpUrl): Boolean {
        val base = baseUrl.toHttpUrlOrNull() ?: return false
        val baseHost = base.host.lowercase(Locale.US)
        val isBaseLoopback = (baseHost == "localhost" || baseHost == "127.0.0.1")

        val targetHost = url.host.lowercase(Locale.US)
        val targetScheme = url.scheme.lowercase(Locale.US)

        if (isBaseLoopback) {
            return targetScheme == base.scheme && targetHost == baseHost && url.port == base.port
        }

        // Production rejects loopback / localhost
        if (targetHost == "localhost" || targetHost == "127.0.0.1") {
            return false
        }

        // Production requires HTTPS and Google API domains
        if (targetScheme != "https") {
            return false
        }

        return targetHost == "generativelanguage.googleapis.com" ||
                targetHost.endsWith(".googleapis.com") ||
                targetHost.endsWith(".google.com")
    }
}
