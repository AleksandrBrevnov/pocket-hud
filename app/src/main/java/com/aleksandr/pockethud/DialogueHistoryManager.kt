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
    val id: String,
    val timestamp: Long,
    val formattedDate: String,
    val turnCount: Int,
    val preview: String
)

/**
 * Менеджер сохранения и управления историей диалогов PocketHUD.
 * Гарантирует надежное оффлайн-хранение всех разговоров пользователя в JSON формате.
 */
object DialogueHistoryManager {
    private const val TAG = "DialogueHistory"
    private const val DIR_NAME = "saved_dialogues"
    private const val CURRENT_SESSION_FILE = "current_dialogue.json"
    private const val MAX_SAVED_SESSIONS = 100

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Получить каталог для хранения архивных диалогов
     */
    fun getHistoryDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Сохранить текущий диалог как отдельную сессию.
     * Вызывается при остановке сервиса, нажатии "Очистить" или ручном сохранении.
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
            val file = File(dir, "dialogue_$timeStr.json")
            val tmpFile = File(dir, "dialogue_$timeStr.tmp")

            val json = serializeTurnsToJson(meaningfulTurns, now)

            FileOutputStream(tmpFile).use { fos ->
                fos.write(json.toString(2).toByteArray(Charsets.UTF_8))
            }

            if (tmpFile.renameTo(file)) {
                Log.d(TAG, "Сессия диалога сохранена (${meaningfulTurns.size} реплик): ${file.name}")
                // Также обновляем кэш текущей сессии
                saveCurrentSessionCache(context, meaningfulTurns)
                // Ограничиваем количество сохранённых сессий (не более 100)
                pruneOldSessions(dir)
                return file
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения сессии диалога: ${e.message}", e)
        }
        return null
    }

    /**
     * Сохранить кэш активного диалога (для восстановления после поворота экрана или закрытия приложения)
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
            try {
                val json = JSONObject(f.readText(Charsets.UTF_8))
                val ts = json.optLong("timestamp", f.lastModified())
                val formatted = dateFormat.format(Date(ts))
                val turnCount = json.optInt("turnCount", 0)
                val preview = json.optString("preview", "Диалог ($turnCount реплик)")
                list.add(
                    SavedSessionInfo(
                        file = f,
                        id = json.optString("id", f.nameWithoutExtension),
                        timestamp = ts,
                        formattedDate = formatted,
                        turnCount = turnCount,
                        preview = preview
                    )
                )
            } catch (e: Exception) {
                // Если файл поврежден, показываем базовую информацию по дате файла
                list.add(
                    SavedSessionInfo(
                        file = f,
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
     * Загрузить реплики из выбранного файла сессии
     */
    @Synchronized
    fun loadSessionTurns(file: File): List<DialogueTurn> {
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText(Charsets.UTF_8)
            parseTurnsFromJson(JSONObject(content))
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения файла сессии ${file.name}: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Удалить выбранный файл сессии
     */
    @Synchronized
    fun deleteSession(file: File): Boolean {
        return try {
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
     * Форматировать список реплик в аккуратный текстовый блок для копирования
     */
    fun formatDialogueText(turns: List<DialogueTurn>, includeOriginal: Boolean, sessionDate: String? = null): String {
        val meaningful = turns.filter { it.italianText.isNotBlank() || it.russianText.isNotBlank() }
        if (meaningful.isEmpty()) return ""

        val sb = StringBuilder()
        val title = if (sessionDate != null) "=== POCKET HUD ДИАЛОГ ($sessionDate) ===" else "=== POCKET HUD ДИАЛОГ ==="
        sb.appendLine(title)
        for (t in meaningful) {
            if (includeOriginal && t.italianText.isNotBlank()) {
                sb.appendLine("🇮🇹/🇬🇧 ${t.italianText}")
            }
            if (t.russianText.isNotBlank()) {
                sb.appendLine("➔ 🇷🇺 ${t.russianText}")
            }
            sb.appendLine()
        }
        sb.appendLine("=========================================")
        return sb.toString().trim()
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
                f.delete()
            }
        }
    }
}
