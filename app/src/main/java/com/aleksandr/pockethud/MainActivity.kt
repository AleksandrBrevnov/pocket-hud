package com.aleksandr.pockethud

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var settingsToggleBtn: TextView
    private lateinit var apiSettingsContainer: LinearLayout

    private lateinit var statusBadgeText: TextView
    private lateinit var telemetryBadgeText: TextView

    private lateinit var italianTranscriptText: TextView
    private lateinit var italianScrollView: ScrollView
    private lateinit var russianTranscriptText: TextView
    private lateinit var russianScrollView: ScrollView

    private lateinit var toggleBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var shareLogBtn: Button
    private lateinit var copySummaryBtn: Button

    private val prefsName = "pocket_hud_prefs"
    private val keyApiKey = "api_key"
    private val keyAudioMuted = "audio_muted"

    companion object {
        private const val IT_PLACEHOLDER = "Здесь появится распознанная итальянская речь..."
        private const val RU_PLACEHOLDER = "Здесь в реальном времени появится русский перевод..."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createLayout())

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(keyApiKey, "") ?: ""
        apiKeyInput.setText(savedKey)

        // Сворачиваем блок настроек API ключа, если ключ уже сохранен
        if (savedKey.isNotBlank()) {
            apiSettingsContainer.visibility = View.GONE
            settingsToggleBtn.text = "⚙️ API Ключ ▾"
        } else {
            apiSettingsContainer.visibility = View.VISIBLE
            settingsToggleBtn.text = "⚙️ API Ключ ▴"
        }

        val isMuted = prefs.getBoolean(keyAudioMuted, false)
        PocketService.isAudioMuted = isMuted
        updateMuteButtonUi()

        setupServiceCallbacks()
        updateUi()

        toggleBtn.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isEmpty()) {
                apiSettingsContainer.visibility = View.VISIBLE
                settingsToggleBtn.text = "⚙️ API Ключ ▴"
                Toast.makeText(this, "Введите Google AI Studio API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().putString(keyApiKey, key).apply()

            if (!checkPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }

            if (PocketService.isServiceActive) {
                val intent = Intent(this, PocketService::class.java)
                stopService(intent)
                PocketService.isServiceActive = false
                updateUi()
            } else {
                statusBadgeText.text = "🔵 Запуск сервиса..."
                italianTranscriptText.text = "Слушаю итальянскую речь..."
                italianTranscriptText.setTextColor(0xFF8AB4F8.toInt())
                russianTranscriptText.text = "Ожидание перевода..."
                russianTranscriptText.setTextColor(0xFF81C995.toInt())

                val intent = Intent(this, PocketService::class.java).apply {
                    putExtra(PocketService.EXTRA_API_KEY, key)
                    putExtra(PocketService.EXTRA_IS_MUTED, PocketService.isAudioMuted)
                }
                ContextCompat.startForegroundService(this, intent)
                PocketService.isServiceActive = true
                updateUi()
            }
        }

        muteBtn.setOnClickListener {
            val newMuted = !PocketService.isAudioMuted
            PocketService.isAudioMuted = newMuted
            prefs.edit().putBoolean(keyAudioMuted, newMuted).apply()
            updateMuteButtonUi()

            val toastMsg = if (newMuted) {
                "🔇 Звук выключен (Режим только субтитров на часах и телефоне)"
            } else {
                "🔊 Звук включен (Голос в наушник)"
            }
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
        }

        shareLogBtn.setOnClickListener {
            shareLogFile()
        }

        copySummaryBtn.setOnClickListener {
            val summary = PocketService.getDiagnosticSummary()
            copyToClipboard("PocketHUD Diagnostics", summary)
            Toast.makeText(this, "Сводка скопирована в буфер обмена! Отправьте её ассистенту.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        setupServiceCallbacks()
        updateUi()
        updateMuteButtonUi()

        // Восстановление истории текущей сессии
        if (PocketService.isServiceActive) {
            val itText = PocketService.getSessionItalianText()
            if (itText.isNotBlank()) {
                italianTranscriptText.text = itText
                italianTranscriptText.setTextColor(0xFFE8EAED.toInt())
                italianScrollView.post { italianScrollView.fullScroll(View.FOCUS_DOWN) }
            }
            val ruText = PocketService.getSessionRussianText()
            if (ruText.isNotBlank()) {
                russianTranscriptText.text = ruText
                russianTranscriptText.setTextColor(0xFF81C995.toInt())
                russianScrollView.post { russianScrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

    private fun createRoundedDrawable(
        bgColor: Int,
        strokeColor: Int = 0,
        strokeWidthDp: Int = 0,
        cornerRadiusDp: Float = 16f
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(cornerRadiusDp)
            setColor(bgColor)
            if (strokeWidthDp > 0 && strokeColor != 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }
    }

    private fun createButtonDrawable(
        bgColor: Int,
        cornerRadiusDp: Float = 14f
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(cornerRadiusDp)
            setColor(bgColor)
        }
    }

    private fun updateMuteButtonUi() {
        if (PocketService.isAudioMuted) {
            muteBtn.text = "🔇 ЗВУК: ВЫКЛ"
            muteBtn.background = createButtonDrawable(0xFFE65100.toInt(), 14f)
        } else {
            muteBtn.text = "🔊 ЗВУК: ВКЛ"
            muteBtn.background = createButtonDrawable(0xFF1976D2.toInt(), 14f)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    private fun resetItalianCard() {
        italianTranscriptText.text = IT_PLACEHOLDER
        italianTranscriptText.setTextColor(0xFF70757A.toInt())
    }

    private fun resetRussianCard() {
        russianTranscriptText.text = RU_PLACEHOLDER
        russianTranscriptText.setTextColor(0xFF4A6B56.toInt())
    }

    private fun appendItalianText(text: String) {
        val current = italianTranscriptText.text.toString()
        if (current == IT_PLACEHOLDER || current.startsWith("Слушаю") || current.startsWith("Здесь")) {
            italianTranscriptText.text = text
            italianTranscriptText.setTextColor(0xFFE8EAED.toInt())
        } else {
            if (text.startsWith(" ") || current.endsWith(" ")) {
                italianTranscriptText.append(text)
            } else {
                italianTranscriptText.append(" $text")
            }
        }
        italianScrollView.post { italianScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendRussianText(text: String) {
        val current = russianTranscriptText.text.toString()
        if (current == RU_PLACEHOLDER || current.startsWith("Ожидание") || current.startsWith("Здесь")) {
            russianTranscriptText.text = text
            russianTranscriptText.setTextColor(0xFF81C995.toInt())
        } else {
            if (text.startsWith(" ") || current.endsWith(" ")) {
                russianTranscriptText.append(text)
            } else {
                russianTranscriptText.append(" $text")
            }
        }
        russianScrollView.post { russianScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun shareLogFile() {
        val logFile = PocketService.getLogFile(this)

        if (!logFile.exists() || logFile.length() == 0L) {
            val summary = PocketService.getDiagnosticSummary()
            try {
                logFile.writeText("=== POCKET HUD SESSION LOG ===\n$summary\n", Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w("PocketHUD", "Не удалось записать начальную сводку в лог: ${e.message}")
            }
        } else {
            PocketService.currentInstance?.logEvent("EXPORT_LOG_CLICKED")
        }

        if (!logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(this, "Файл лога пока пуст. Запустите суфлёр!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                logFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("PocketHUD Session Log", uri)
                putExtra(Intent.EXTRA_SUBJECT, "PocketHUD Session Log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Поделиться логом PocketHUD"))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка экспорта лога: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupServiceCallbacks() {
        PocketService.onStatusChanged = { status ->
            runOnUiThread {
                statusBadgeText.text = status
                if (status.contains("🟢") || status.contains("🔵")) {
                    toggleBtn.text = "⏹️ ОСТАНОВИТЬ СУФЛЁР"
                    toggleBtn.background = createButtonDrawable(0xFFD93025.toInt(), 26f)
                } else if (status.contains("🔴") || status.contains("⚪")) {
                    toggleBtn.text = "🎙️ ЗАПУСТИТЬ СУФЛЁР"
                    toggleBtn.background = createButtonDrawable(0xFF1E8E3E.toInt(), 26f)
                }
            }
        }

        PocketService.onTelemetryUpdated = { micAmp, chunks, responses ->
            runOnUiThread {
                telemetryBadgeText.text = "🎙️ $micAmp% | 📦 $chunks | ⚡ $responses"
            }
        }

        PocketService.onInputTextReceived = { text ->
            runOnUiThread {
                appendItalianText(text)
            }
        }

        PocketService.onTextReceived = { text ->
            runOnUiThread {
                appendRussianText(text)
            }
        }
    }

    private fun updateUi() {
        if (PocketService.isServiceActive) {
            toggleBtn.text = "⏹️ ОСТАНОВИТЬ СУФЛЁР"
            toggleBtn.background = createButtonDrawable(0xFFD93025.toInt(), 26f)
        } else {
            toggleBtn.text = "🎙️ ЗАПУСТИТЬ СУФЛЁР"
            toggleBtn.background = createButtonDrawable(0xFF1E8E3E.toInt(), 26f)
            statusBadgeText.text = "⚪ Суфлёр остановлен."
            telemetryBadgeText.text = "🎙️ 0% | 📦 0 | ⚡ 0"
        }
    }

    private fun checkPermissions(): Boolean {
        val recordAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val postNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return recordAudio && postNotif
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (checkPermissions()) {
                Toast.makeText(this, "Разрешения предоставлены! Нажмите 'ЗАПУСТИТЬ СУФЛЁР'", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Требуется разрешение на запись звука для суфлёра", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121316.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }

        // ==========================================
        // 1. Верхняя панель: Заголовок + Настройки API
        // ==========================================
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
            layoutParams = params
        }

        val appTitle = TextView(this).apply {
            text = "PocketHUD"
            textSize = 19f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
        }
        topBar.addView(appTitle)

        val versionBadge = TextView(this).apply {
            text = "v1.0.16"
            textSize = 10.5f
            setTextColor(0xFF9AA0A6.toInt())
            background = createRoundedDrawable(0xFF20232A.toInt(), cornerRadiusDp = 6f)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8), 0, 0, 0) }
            layoutParams = params
        }
        topBar.addView(versionBadge)

        val headerSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        topBar.addView(headerSpacer)

        settingsToggleBtn = TextView(this).apply {
            text = "⚙️ API Ключ ▾"
            textSize = 12f
            setTextColor(0xFF8AB4F8.toInt())
            setTypeface(null, Typeface.BOLD)
            background = createRoundedDrawable(0xFF1F2533.toInt(), 0xFF2A3449.toInt(), 1, 8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener {
                val isVisible = apiSettingsContainer.visibility == View.VISIBLE
                apiSettingsContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
                text = if (isVisible) "⚙️ API Ключ ▾" else "⚙️ API Ключ ▴"
            }
        }
        topBar.addView(settingsToggleBtn)
        root.addView(topBar)

        // Раскрывающийся блок ввода API ключа
        apiSettingsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(0xFF1A1D24.toInt(), 0xFF2E3440.toInt(), 1, 12f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
            layoutParams = params
        }

        apiKeyInput = EditText(this).apply {
            hint = "Введите Google AI Studio API Key"
            setHintTextColor(0xFF5F6368.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            background = createRoundedDrawable(0xFF12141A.toInt(), 0xFF3C4043.toInt(), 1, 8f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            textSize = 13.5f
        }
        apiSettingsContainer.addView(apiKeyInput)

        val apiHint = TextView(this).apply {
            text = "Ключ сохраняется локально на устройстве для Gemini Live WebSocket"
            textSize = 11f
            setTextColor(0xFF80868B.toInt())
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(2), dp(6), 0, 0) }
            layoutParams = params
        }
        apiSettingsContainer.addView(apiHint)
        root.addView(apiSettingsContainer)

        // ==========================================
        // 2. Индикаторы: Статус + Телеметрия
        // ==========================================
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) }
            layoutParams = params
        }

        statusBadgeText = TextView(this).apply {
            text = "⚪ Суфлёр остановлен."
            textSize = 12.5f
            setTextColor(0xFFCCCCCC.toInt())
            setTypeface(null, Typeface.BOLD)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
        }
        statusRow.addView(statusBadgeText)

        telemetryBadgeText = TextView(this).apply {
            text = "🎙️ 0% | 📦 0 | ⚡ 0"
            textSize = 12f
            setTextColor(0xFF00E5FF.toInt())
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            background = createRoundedDrawable(0xFF16222F.toInt(), 0xFF1C354A.toInt(), 1, 6f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        statusRow.addView(telemetryBadgeText)
        root.addView(statusRow)

        // ==========================================
        // 3. Google Translate Split Cards Container
        // ==========================================
        val conversationContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { setMargins(0, 0, 0, dp(10)) }
            layoutParams = params
        }

        // ------------------------------------------
        // КАРТОЧКА 1: 🇮🇹 ITALIANO (Речь собеседника)
        // ------------------------------------------
        val italianCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(0xFF1E2026.toInt(), 0xFF2D323F.toInt(), 1, 18f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutParams = params
        }

        val italianHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
            layoutParams = params
        }

        val italianFlagBadge = TextView(this).apply {
            text = "🇮🇹 ITALIANO"
            textSize = 11.5f
            setTextColor(0xFF8AB4F8.toInt())
            setTypeface(null, Typeface.BOLD)
            background = createRoundedDrawable(0xFF28344D.toInt(), cornerRadiusDp = 8f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        italianHeader.addView(italianFlagBadge)

        val italianHeaderSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        italianHeader.addView(italianHeaderSpacer)

        val copyItalianBtn = TextView(this).apply {
            text = "📋 Копировать"
            textSize = 11f
            setTextColor(0xFFCCCCCC.toInt())
            background = createRoundedDrawable(0xFF2A2E39.toInt(), cornerRadiusDp = 6f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener {
                val txt = italianTranscriptText.text.toString()
                if (txt.isNotBlank() && txt != IT_PLACEHOLDER) {
                    copyToClipboard("PocketHUD Italian Transcript", txt)
                    Toast.makeText(this@MainActivity, "Итальянский текст скопирован", Toast.LENGTH_SHORT).show()
                }
            }
        }
        italianHeader.addView(copyItalianBtn)

        val clearItalianBtn = TextView(this).apply {
            text = "🗑️ Очистить"
            textSize = 11f
            setTextColor(0xFFCCCCCC.toInt())
            background = createRoundedDrawable(0xFF2A2E39.toInt(), cornerRadiusDp = 6f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, 0, 0) }
            layoutParams = params
            isClickable = true
            setOnClickListener {
                resetItalianCard()
            }
        }
        italianHeader.addView(clearItalianBtn)
        italianCard.addView(italianHeader)

        italianScrollView = ScrollView(this).apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutParams = params
            isFillViewport = true
        }

        italianTranscriptText = TextView(this).apply {
            text = IT_PLACEHOLDER
            textSize = 17.5f
            setTextColor(0xFF70757A.toInt())
            setLineSpacing(dpF(4f), 1.2f)
        }
        italianScrollView.addView(italianTranscriptText)
        italianCard.addView(italianScrollView)
        conversationContainer.addView(italianCard)

        // ------------------------------------------
        // РАЗДЕЛИТЕЛЬ / ИНДИКАТОР ПЕРЕВОДА
        // ------------------------------------------
        val flowIndicator = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(5), 0, dp(5)) }
            layoutParams = params
        }

        val flowPill = TextView(this).apply {
            text = "🇮🇹  ➔  🇷🇺  СИНХРОННЫЙ ПЕРЕВОД"
            textSize = 10.5f
            setTextColor(0xFF7B889B.toInt())
            setTypeface(null, Typeface.BOLD)
            background = createRoundedDrawable(0xFF161920.toInt(), 0xFF242A36.toInt(), 1, 10f)
            setPadding(dp(12), dp(3), dp(12), dp(3))
        }
        flowIndicator.addView(flowPill)
        conversationContainer.addView(flowIndicator)

        // ------------------------------------------
        // КАРТОЧКА 2: 🇷🇺 РУССКИЙ (Перевод)
        // ------------------------------------------
        val russianCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(0xFF16231C.toInt(), 0xFF234431.toInt(), 1, 18f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutParams = params
        }

        val russianHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
            layoutParams = params
        }

        val russianFlagBadge = TextView(this).apply {
            text = "🇷🇺 РУССКИЙ"
            textSize = 11.5f
            setTextColor(0xFF81C995.toInt())
            setTypeface(null, Typeface.BOLD)
            background = createRoundedDrawable(0xFF1B3D2A.toInt(), cornerRadiusDp = 8f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        russianHeader.addView(russianFlagBadge)

        val russianHeaderSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        russianHeader.addView(russianHeaderSpacer)

        val copyRussianBtn = TextView(this).apply {
            text = "📋 Копировать"
            textSize = 11f
            setTextColor(0xFFCCCCCC.toInt())
            background = createRoundedDrawable(0xFF23352A.toInt(), cornerRadiusDp = 6f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener {
                val txt = russianTranscriptText.text.toString()
                if (txt.isNotBlank() && txt != RU_PLACEHOLDER) {
                    copyToClipboard("PocketHUD Russian Translation", txt)
                    Toast.makeText(this@MainActivity, "Русский перевод скопирован", Toast.LENGTH_SHORT).show()
                }
            }
        }
        russianHeader.addView(copyRussianBtn)

        val clearRussianBtn = TextView(this).apply {
            text = "🗑️ Очистить"
            textSize = 11f
            setTextColor(0xFFCCCCCC.toInt())
            background = createRoundedDrawable(0xFF23352A.toInt(), cornerRadiusDp = 6f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, 0, 0) }
            layoutParams = params
            isClickable = true
            setOnClickListener {
                resetRussianCard()
            }
        }
        russianHeader.addView(clearRussianBtn)
        russianCard.addView(russianHeader)

        russianScrollView = ScrollView(this).apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutParams = params
            isFillViewport = true
        }

        russianTranscriptText = TextView(this).apply {
            text = RU_PLACEHOLDER
            textSize = 18.5f
            setTextColor(0xFF4A6B56.toInt())
            setTypeface(null, Typeface.BOLD)
            setLineSpacing(dpF(5f), 1.22f)
        }
        russianScrollView.addView(russianTranscriptText)
        russianCard.addView(russianScrollView)
        conversationContainer.addView(russianCard)

        root.addView(conversationContainer)

        // ==========================================
        // 4. Нижняя панель управления (Bottom Dock)
        // ==========================================
        val dockLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
        }

        // Главная крупная кнопка запуска / остановки суфлёра
        toggleBtn = Button(this).apply {
            text = "🎙️ ЗАПУСТИТЬ СУФЛЁР"
            setTextColor(0xFFFFFFFF.toInt())
            background = createButtonDrawable(0xFF1E8E3E.toInt(), 26f)
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(14))
        }
        val toggleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(10)) }
        dockLayout.addView(toggleBtn, toggleParams)

        // Нижняя строка: Mute + Сводка + Лог
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        muteBtn = Button(this).apply {
            text = "🔊 ЗВУК: ВКЛ"
            setTextColor(0xFFFFFFFF.toInt())
            background = createButtonDrawable(0xFF1976D2.toInt(), 14f)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        val muteParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.3f
        ).apply { setMargins(0, 0, dp(6), 0) }
        bottomRow.addView(muteBtn, muteParams)

        copySummaryBtn = Button(this).apply {
            text = "📋 СВОДКА"
            setTextColor(0xFFFFFFFF.toInt())
            background = createButtonDrawable(0xFF2E333D.toInt(), 14f)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        val copySummaryParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.0f
        ).apply { setMargins(0, 0, dp(6), 0) }
        bottomRow.addView(copySummaryBtn, copySummaryParams)

        shareLogBtn = Button(this).apply {
            text = "📤 ЛОГ"
            setTextColor(0xFFFFFFFF.toInt())
            background = createButtonDrawable(0xFF1F3D2A.toInt(), 14f)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        val shareLogParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.0f
        )
        bottomRow.addView(shareLogBtn, shareLogParams)

        dockLayout.addView(bottomRow)
        root.addView(dockLayout)

        return root
    }
}
