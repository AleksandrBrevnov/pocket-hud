package com.aleksandr.pockethud

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Информация о сохранённой сессии диалога.
 */
data class SavedSessionInfo(
    val file: File,
    val textFile: File,
    val id: String,
    val timestamp: Long,
    val formattedDate: String,
    val turnCount: Int,
    val preview: String
)

/**
 * Менеджер сохранения и управления историей диалогов PocketHUD.
 *
 * Сохраняет чистые реплики диалога (время, оригинальная речь IT/EN, русский перевод RU)
 * на устройство в human-readable формате (.txt) и структурированном JSON (.json).
 * Без технической телеметрии (CHUNK_STREAMING, WEBSOCKET и т.д.).
 * Гарантирует надежное сохранение и восстановление истории диалогов при перезапуске приложения.
 */
object DialogueHistoryManager {
    private const val TAG = "DialogueHistory"
    private const val DIR_NAME = "dialogues"
    private const val CURRENT_SESSION_FILE = "current_dialogue.json"
    private const val MAX_SAVED_SESSIONS = 100

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * Получить каталог для хранения архивных диалогов.
     * Приоритет: context.getExternalFilesDir("dialogues"), fallback: filesDir/dialogues.
     * Также выполняет прозрачную миграцию старых записей из filesDir/saved_dialogues.
     */
    fun getHistoryDir(context: Context): File {
        val dir = context.getExternalFilesDir(DIR_NAME) ?: File(context.filesDir, DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        // Прозрачная миграция старых файлов из filesDir/saved_dialogues
        try {
            val oldDir = File(context.filesDir, "saved_dialogues")
            if (oldDir.exists() && oldDir.absolutePath != dir.absolutePath) {
                oldDir.listFiles()?.forEach { oldFile ->
                    val targetFile = File(dir, oldFile.name)
                    if (!targetFile.exists()) {
                        oldFile.renameTo(targetFile)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка миграции старых диалогов: ${e.message}")
        }

        return dir
    }

    /**
     * Сохранить текущий диалог как отдельную сессию.
     * Создает как human-readable .txt, так и .json для быстрого восстановления.
     * Возвращает .txt файл диалога, готовый для экспорта и отправки.
     */
    @Synchronized
    fun saveSession(context: Context, turns: List<DialogueTurn>, isAutoArchive: Boolean = false): File? {
        val meaningfulTurns = turns.filter { it.italianText.isNotBlank() || it.russianText.isNotBlank() }
        if (meaningfulTurns.isEmpty()) {
            return null
        }

        try {
            val dir = getHistoryDir(context)
            val now = System.currentTimeMillis()
            val timeStr = fileDateFormat.format(Date(now))
            val dateStr = dateFormat.format(Date(now))

            // 1. Сохранение структурированного JSON
            val jsonFile = File(dir, "dialogue_$timeStr.json")
            val tmpJson = File(dir, "dialogue_$timeStr.json.tmp")
            val json = serializeTurnsToJson(meaningfulTurns, now)
            FileOutputStream(tmpJson).use { fos ->
                fos.write(json.toString(2).toByteArray(Charsets.UTF_8))
            }
            tmpJson.renameTo(jsonFile)

            // 2. Сохранение чистого human-readable текста диалога (.txt)
            val txtFile = File(dir, "dialogue_$timeStr.txt")
            val tmpTxt = File(dir, "dialogue_$timeStr.txt.tmp")
            val textContent = formatDialogueText(meaningfulTurns, includeOriginal = true, sessionDate = dateStr)
            FileOutputStream(tmpTxt).use { fos ->
                fos.write(textContent.toByteArray(Charsets.UTF_8))
            }
            tmpTxt.renameTo(txtFile)

            Log.d(TAG, "Сессия диалога сохранена (${meaningfulTurns.size} реплик): ${txtFile.name}")

            // Также обновляем кэш текущей активной сессии
            saveCurrentSessionCache(context, meaningfulTurns)

            // Ограничиваем количество сохранённых сессий (не более 100)
            pruneOldSessions(dir)

            return txtFile
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения сессии диалога: ${e.message}", e)
        }
        return null
    }

    /**
     * Сохраняет чистый human-readable файл диалога для передачи (Share) пользователем.
     */
    @Synchronized
    fun saveSessionTextFile(context: Context, turns: List<DialogueTurn>, sessionDate: String? = null): File {
        val dir = getHistoryDir(context)
        val now = System.currentTimeMillis()
        val timeStr = fileDateFormat.format(Date(now))
        val file = File(dir, "dialogue_$timeStr.txt")
        val content = formatDialogueText(turns, includeOriginal = true, sessionDate = sessionDate)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    /**
     * Сохранить кэш активного диалога (для восстановления после закрытия приложения)
     */
    @Synchronized
    fun saveCurrentSessionCache(context: Context, turns: List<DialogueTurn>) {
        try {
            val meaningfulTurns = turns.filter { it.italianText.isNotBlank() || it.russianText.isNotBlank() }
            val file = File(context.filesDir, CURRENT_SESSION_FILE)
            if (meaningfulTurns.isEmpty()) {
                if (file.exists()) file.delete()
                return
            }
            val json = serializeTurnsToJson(meaningfulTurns, System.currentTimeMillis())
            val tmp = File(context.filesDir, "$CURRENT_SESSION_FILE.tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(json.toString().toByteArray(Charsets.UTF_8))
            }
            tmp.renameTo(file)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения кэша текущей сессии: ${e.message}", e)
        }
    }

    /**
     * Восстановить последнюю сохранённую сессию из кэша приложения
     */
    @Synchronized
    fun loadCurrentSessionCache(context: Context): List<DialogueTurn> {
        val file = File(context.filesDir, CURRENT_SESSION_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText(Charsets.UTF_8)
            parseTurnsFromJson(JSONObject(content))
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки кэша сессии: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Очистить кэш текущей сессии
     */
    @Synchronized
    fun clearCurrentSessionCache(context: Context) {
        try {
            val file = File(context.filesDir, CURRENT_SESSION_FILE)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка очистки кэша: ${e.message}", e)
        }
    }

    /**
     * Получить список всех сохранённых сессий (отсортированы от новых к старым)
     */
    @Synchronized
    fun listSavedSessions(context: Context): List<SavedSessionInfo> {
        val dir = getHistoryDir(context)
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()

        val list = mutableListOf<SavedSessionInfo>()
        for (f in files) {
            val txtFile = File(dir, f.nameWithoutExtension + ".txt")
            try {
                val json = JSONObject(f.readText(Charsets.UTF_8))
                val ts = json.optLong("timestamp", f.lastModified())
                val formatted = dateFormat.format(Date(ts))
                val turnCount = json.optInt("turnCount", 0)
                val preview = json.optString("preview", "Диалог ($turnCount реплик)")
                list.add(
                    SavedSessionInfo(
                        file = f,
                        textFile = txtFile,
                        id = json.optString("id", f.nameWithoutExtension),
                        timestamp = ts,
                        formattedDate = formatted,
                        turnCount = turnCount,
                        preview = preview
                    )
                )
            } catch (e: Exception) {
                list.add(
                    SavedSessionInfo(
                        file = f,
                        textFile = txtFile,
                        id = f.nameWithoutExtension,
                        timestamp = f.lastModified(),
                        formattedDate = dateFormat.format(Date(f.lastModified())),
                        turnCount = 0,
                        preview = "Сохранённая сессия"
                    )
                )
            }
        }
        return list.sortedByDescending { it.timestamp }
    }

    /**
     * Возвращает самый свежий текстовый файл диалога (.txt) для экспорта
     */
    fun getLatestDialogueTextFile(context: Context): File? {
        val dir = getHistoryDir(context)
        val files = dir.listFiles { f -> f.extension == "txt" && f.name.startsWith("dialogue_") } ?: return null
        return files.maxByOrNull { it.lastModified() }
    }

    /**
     * Сохранить сгенерированный AI-конспект встречи в Markdown (.md)
     */
    @Synchronized
    fun saveSessionSummary(context: Context, summaryMarkdown: String, sessionTimestamp: Long = System.currentTimeMillis()): File? {
        try {
            val dir = getHistoryDir(context)
            val timeStr = fileDateFormat.format(Date(sessionTimestamp))
            val summaryFile = File(dir, "summary_$timeStr.md")
            val tmp = File(dir, "summary_$timeStr.md.tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(summaryMarkdown.toByteArray(Charsets.UTF_8))
            }
            tmp.renameTo(summaryFile)
            Log.d(TAG, "AI-Конспект сохранен: ${summaryFile.name}")
            return summaryFile
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения AI-конспекта: ${e.message}", e)
            return null
        }
    }

    /**
     * Загрузить последний сохраненный AI-конспект (если есть)
     */
    @Synchronized
    fun loadLatestSummary(context: Context): Pair<String, String>? {
        val dir = getHistoryDir(context)
        val files = dir.listFiles { f -> f.name.startsWith("summary_") && f.extension == "md" }
            ?.sortedByDescending { it.lastModified() }
        val latest = files?.firstOrNull() ?: return null
        return try {
            val text = latest.readText(Charsets.UTF_8)
            val ts = latest.lastModified()
            val formatted = dateFormat.format(Date(ts))
            Pair(text, formatted)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Загрузить реплики из выбранного файла сессии (поддерживает .json)
     */
    @Synchronized
    fun loadSessionTurns(file: File): List<DialogueTurn> {
        if (!file.exists()) return emptyList()
        return try {
            val jsonFile = if (file.extension == "json") file else {
                val candidate = File(file.parentFile, file.nameWithoutExtension + ".json")
                if (candidate.exists()) candidate else file
            }
            val content = jsonFile.readText(Charsets.UTF_8)
            parseTurnsFromJson(JSONObject(content))
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения файла сессии ${file.name}: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Удалить выбранный файл сессии (удаляет и .json, и .txt)
     */
    @Synchronized
    fun deleteSession(file: File): Boolean {
        return try {
            val txtFile = File(file.parentFile, file.nameWithoutExtension + ".txt")
            if (txtFile.exists()) txtFile.delete()
            val jsonFile = File(file.parentFile, file.nameWithoutExtension + ".json")
            if (jsonFile.exists()) jsonFile.delete()
            file.delete()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Удалить все сохранённые сессии диалогов
     */
    @Synchronized
    fun clearAllSessions(context: Context): Boolean {
        return try {
            val dir = getHistoryDir(context)
            dir.listFiles()?.forEach { it.delete() }
            clearCurrentSessionCache(context)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Форматировать список реплик в аккуратный текстовый блок для копирования или отправки.
     * Без технической телеметрии (CHUNK_STREAMING, WEBSOCKET и т.д.).
     */
    fun formatDialogueText(turns: List<DialogueTurn>, includeOriginal: Boolean, sessionDate: String? = null): String {
        val meaningful = turns.filter { it.italianText.isNotBlank() || it.russianText.isNotBlank() }
        if (meaningful.isEmpty()) return ""

        val sb = StringBuilder()
        val title = if (sessionDate != null) "# 🎙️ PocketHUD — Диалог ($sessionDate)" else "# 🎙️ PocketHUD — Диалог"
        sb.appendLine(title)
        sb.appendLine("Всего реплик: ${meaningful.size}")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        for (t in meaningful) {
            val timeStr = timeFormat.format(Date(t.timestamp))
            if (includeOriginal) {
                sb.appendLine("[$timeStr]")
                if (t.italianText.isNotBlank()) {
                    sb.appendLine("🇮🇹 IT: ${t.italianText.trim()}")
                }
                if (t.russianText.isNotBlank()) {
                    sb.appendLine("➔ 🇷🇺 ${t.russianText.trim()}")
                } else if (t.isFinalized) {
                    sb.appendLine("⚠️ RU: (перевод не получен)")
                } else {
                    sb.appendLine("⏳ RU: (ожидание перевода...)")
                }
                sb.appendLine()
            } else {
                val ru = t.russianText.trim()
                if (ru.isNotBlank()) {
                    sb.appendLine("[$timeStr] 🇷🇺 $ru")
                    sb.appendLine()
                } else if (t.isFinalized) {
                    sb.appendLine("[$timeStr] ⚠️ (перевод не получен)")
                    sb.appendLine()
                }
            }
        }
        return sb.toString().trim() + "\n"
    }

    private fun serializeTurnsToJson(turns: List<DialogueTurn>, timestamp: Long): JSONObject {
        val root = JSONObject()
        root.put("id", "session_$timestamp")
        root.put("timestamp", timestamp)
        root.put("formattedDate", dateFormat.format(Date(timestamp)))
        root.put("turnCount", turns.size)

        // Создаем информативное превью (первые 2 реплики)
        val previewSb = StringBuilder()
        val sample = turns.take(2)
        for ((idx, t) in sample.withIndex()) {
            if (t.russianText.isNotBlank()) {
                previewSb.append("🇷🇺 ").append(t.russianText.take(45))
            } else if (t.italianText.isNotBlank()) {
                previewSb.append("🇮🇹 ").append(t.italianText.take(45))
            }
            if (idx < sample.size - 1) previewSb.append(" | ")
        }
        root.put("preview", previewSb.toString().ifBlank { "Диалог (${turns.size} реплик)" })

        val array = JSONArray()
        for (t in turns) {
            val turnObj = JSONObject()
            turnObj.put("id", t.id)
            turnObj.put("italianText", t.italianText)
            turnObj.put("russianText", t.russianText)
            turnObj.put("timestamp", t.timestamp)
            turnObj.put("lastActivityTime", t.lastActivityTime)
            turnObj.put("isFinalized", t.isFinalized)
            array.put(turnObj)
        }
        root.put("turns", array)
        return root
    }

    private fun parseTurnsFromJson(json: JSONObject): List<DialogueTurn> {
        val array = json.optJSONArray("turns") ?: return emptyList()
        val turns = mutableListOf<DialogueTurn>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val turn = DialogueTurn(
                id = obj.optLong("id", System.currentTimeMillis()),
                italianText = obj.optString("italianText", ""),
                russianText = obj.optString("russianText", ""),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                lastActivityTime = obj.optLong("lastActivityTime", System.currentTimeMillis()),
                isFinalized = obj.optBoolean("isFinalized", true)
            )
            turns.add(turn)
        }
        return turns
    }

    private fun pruneOldSessions(dir: File) {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return
        if (files.size > MAX_SAVED_SESSIONS) {
            val sorted = files.sortedBy { it.lastModified() }
            val toDelete = sorted.take(files.size - MAX_SAVED_SESSIONS)
            for (f in toDelete) {
                val txtFile = File(dir, f.nameWithoutExtension + ".txt")
                if (txtFile.exists()) txtFile.delete()
                f.delete()
            }
        }
    }
}
