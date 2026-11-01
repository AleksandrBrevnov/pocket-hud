package com.aleksandr.pockethud

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import com.google.android.material.checkbox.MaterialCheckBox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    enum class ScreenMode {
        DIALOGUE,
        ITALIAN,
        RUSSIAN
    }

    private lateinit var apiKeyInput: EditText
    private lateinit var settingsToggleBtn: TextView
    private lateinit var apiSettingsContainer: LinearLayout

    private lateinit var micPhoneBtn: TextView
    private lateinit var micBluetoothBtn: TextView

    private lateinit var tabDialogueBtn: TextView
    private lateinit var tabItalianBtn: TextView
    private lateinit var tabRussianBtn: TextView

    private lateinit var statusBadgeText: TextView
    private lateinit var telemetryBadgeText: TextView

    private lateinit var conversationContainer: LinearLayout

    // Режим диалога (Google Translate Conversation Mode)
    private lateinit var dialogueCard: LinearLayout
    private lateinit var dialogueTitleText: TextView
    private lateinit var copyDialogueBtn: TextView
    private lateinit var clearDialogueBtn: TextView
    private lateinit var showOriginalCheckBox: MaterialCheckBox
    private lateinit var showOriginalBadge: TextView
    private lateinit var dialogueScrollView: ScrollView
    private lateinit var dialogueFeedLayout: LinearLayout
    private lateinit var dialogueEmptyPlaceholder: TextView

    private class TurnViewHolder(
        val container: View,
        val italianContainer: View,
        val italianText: TextView,
        val russianText: TextView,
        val ruBadge: TextView,
        val ruTimeView: TextView
    )
    private val turnViewHolders = mutableListOf<TurnViewHolder>()

    // Экраны отдельных языков
    private lateinit var italianCard: LinearLayout
    private lateinit var italianTitleText: TextView
    private lateinit var expandItalianBtn: TextView
    private lateinit var italianTranscriptText: TextView
    private lateinit var italianScrollView: ScrollView

    private lateinit var russianCard: LinearLayout
    private lateinit var russianTitleText: TextView
    private lateinit var expandRussianBtn: TextView
    private lateinit var russianTranscriptText: TextView
    private lateinit var russianScrollView: ScrollView

    private lateinit var toggleBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var shareLogBtn: Button
    private lateinit var copySummaryBtn: Button

    private val prefsName = "pocket_hud_prefs"
    private val keyApiKey = "api_key"
    private val keyAudioMuted = "audio_muted"
    private val keyScreenMode = "screen_mode"
    private val keyMicSource = "mic_source"
    private val keyShowOriginalText = "show_original_text"

    private var currentMode = ScreenMode.DIALOGUE
    private var currentMicSource = MicSource.SMARTPHONE
    private var showOriginalText = true

    companion object {
        private const val IT_PLACEHOLDER = "Слушаю итальянскую или английскую речь (собеседник, лекция)..."
        private const val RU_PLACEHOLDER = "Здесь в реальном времени появится полный русский перевод..."
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

        val savedMicStr = prefs.getString(keyMicSource, MicSource.SMARTPHONE.name) ?: MicSource.SMARTPHONE.name
        currentMicSource = try {
            MicSource.valueOf(savedMicStr)
        } catch (e: Exception) {
            MicSource.SMARTPHONE
        }
        PocketService.selectedMicSource = currentMicSource
        updateMicSourceStyles()

        val isMuted = prefs.getBoolean(keyAudioMuted, false)
        PocketService.isAudioMuted = isMuted
        updateMuteButtonUi()

        showOriginalText = prefs.getBoolean(keyShowOriginalText, true)
        showOriginalCheckBox.isChecked = showOriginalText
        updateShowOriginalBadge(showOriginalText)
        updateDialogueOriginalVisibility()

        showOriginalCheckBox.setOnCheckedChangeListener { _, isChecked ->
            showOriginalText = isChecked
            prefs.edit().putBoolean(keyShowOriginalText, isChecked).apply()
            updateShowOriginalBadge(isChecked)
            updateDialogueOriginalVisibility()
        }

        val savedModeStr = prefs.getString(keyScreenMode, ScreenMode.DIALOGUE.name) ?: ScreenMode.DIALOGUE.name
        val savedMode = when (savedModeStr) {
            "BOTH", "DIALOGUE" -> ScreenMode.DIALOGUE
            "ITALIAN" -> ScreenMode.ITALIAN
            "RUSSIAN" -> ScreenMode.RUSSIAN
            else -> ScreenMode.DIALOGUE
        }
        setScreenMode(savedMode)

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
                italianTranscriptText.text = "Слушаю итальянскую и английскую речь..."
                italianTranscriptText.setTextColor(0xFF8AB4F8.toInt())
                russianTranscriptText.text = "Ожидание перевода..."
                russianTranscriptText.setTextColor(0xFF81C995.toInt())
                restoreDialogueView()

                val intent = Intent(this, PocketService::class.java).apply {
                    putExtra(PocketService.EXTRA_API_KEY, key)
                    putExtra(PocketService.EXTRA_IS_MUTED, PocketService.isAudioMuted)
                    putExtra(PocketService.EXTRA_MIC_SOURCE, currentMicSource.name)
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
        updateMicSourceStyles()

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val savedShowOriginal = prefs.getBoolean(keyShowOriginalText, true)
        if (::showOriginalCheckBox.isInitialized && showOriginalCheckBox.isChecked != savedShowOriginal) {
            showOriginalCheckBox.isChecked = savedShowOriginal
            showOriginalText = savedShowOriginal
            updateShowOriginalBadge(savedShowOriginal)
        }

        restoreDialogueView()

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

    private fun setScreenMode(mode: ScreenMode) {
        currentMode = mode
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString(keyScreenMode, mode.name).apply()

        updateTabStyles()

        when (mode) {
            ScreenMode.DIALOGUE -> {
                dialogueCard.visibility = View.VISIBLE
                val diagParams = dialogueCard.layoutParams as LinearLayout.LayoutParams
                diagParams.weight = 1f
                diagParams.height = 0
                dialogueCard.layoutParams = diagParams

                italianCard.visibility = View.GONE
                russianCard.visibility = View.GONE

                dialogueScrollView.post { dialogueScrollView.fullScroll(View.FOCUS_DOWN) }
            }
            ScreenMode.ITALIAN -> {
                dialogueCard.visibility = View.GONE

                italianCard.visibility = View.VISIBLE
                val itParams = italianCard.layoutParams as LinearLayout.LayoutParams
                itParams.weight = 1f
                itParams.height = 0
                italianCard.layoutParams = itParams

                russianCard.visibility = View.GONE

                italianTitleText.text = "🇮🇹/🇬🇧 ЭКРАН 1: РЕЧЬ (IT / EN)"
                expandItalianBtn.text = "💬 Диалог"
                italianTranscriptText.textSize = 21f
                italianScrollView.post { italianScrollView.fullScroll(View.FOCUS_DOWN) }
            }
            ScreenMode.RUSSIAN -> {
                dialogueCard.visibility = View.GONE
                italianCard.visibility = View.GONE

                russianCard.visibility = View.VISIBLE
                val ruParams = russianCard.layoutParams as LinearLayout.LayoutParams
                ruParams.weight = 1f
                ruParams.height = 0
                russianCard.layoutParams = ruParams

                russianTitleText.text = "🇷🇺 ЭКРАН 2: РУССКИЙ ПЕРЕВОД"
                expandRussianBtn.text = "💬 Диалог"
                russianTranscriptText.textSize = 22f
                russianScrollView.post { russianScrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun setMicSource(source: MicSource) {
        currentMicSource = source
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString(keyMicSource, source.name).apply()
        PocketService.selectedMicSource = source
        updateMicSourceStyles()

        val toastMsg = if (source == MicSource.SMARTPHONE) {
            "📱 Включен микрофон смартфона (+2.8x усиление для зала/лекций)"
        } else {
            "🎧 Включен микрофон гарнитуры (Bluetooth SCO)"
        }
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }

    private fun updateMicSourceStyles() {
        if (!::micPhoneBtn.isInitialized || !::micBluetoothBtn.isInitialized) return

        val activePhoneBg = createRoundedDrawable(0xFF1E3A5F.toInt(), 0xFF4285F4.toInt(), 1, 12f)
        val activeBtBg = createRoundedDrawable(0xFF1A3B2A.toInt(), 0xFF34A853.toInt(), 1, 12f)
        val inactiveBg = createRoundedDrawable(0x00000000, cornerRadiusDp = 12f)

        if (currentMicSource == MicSource.SMARTPHONE) {
            micPhoneBtn.background = activePhoneBg
            micPhoneBtn.setTextColor(0xFF8AB4F8.toInt())
            micBluetoothBtn.background = inactiveBg
            micBluetoothBtn.setTextColor(0xFF9AA0A6.toInt())
        } else {
            micBluetoothBtn.background = activeBtBg
            micBluetoothBtn.setTextColor(0xFF81C995.toInt())
            micPhoneBtn.background = inactiveBg
            micPhoneBtn.setTextColor(0xFF9AA0A6.toInt())
        }
    }

    private fun updateTabStyles() {
        val activeBgDiag = createRoundedDrawable(0xFF28344D.toInt(), 0xFF5C6BC0.toInt(), 1, 14f)
        val activeBgIt = createRoundedDrawable(0xFF1E3A5F.toInt(), 0xFF4285F4.toInt(), 1, 14f)
        val activeBgRu = createRoundedDrawable(0xFF1A3B2A.toInt(), 0xFF34A853.toInt(), 1, 14f)
        val inactiveBg = createRoundedDrawable(0x00000000, cornerRadiusDp = 14f)

        when (currentMode) {
            ScreenMode.DIALOGUE -> {
                tabDialogueBtn.background = activeBgDiag
                tabDialogueBtn.setTextColor(0xFF8AB4F8.toInt())
                tabItalianBtn.background = inactiveBg
                tabItalianBtn.setTextColor(0xFF9AA0A6.toInt())
                tabRussianBtn.background = inactiveBg
                tabRussianBtn.setTextColor(0xFF9AA0A6.toInt())
            }
            ScreenMode.ITALIAN -> {
                tabItalianBtn.background = activeBgIt
                tabItalianBtn.setTextColor(0xFF8AB4F8.toInt())
                tabDialogueBtn.background = inactiveBg
                tabDialogueBtn.setTextColor(0xFF9AA0A6.toInt())
                tabRussianBtn.background = inactiveBg
                tabRussianBtn.setTextColor(0xFF9AA0A6.toInt())
            }
            ScreenMode.RUSSIAN -> {
                tabRussianBtn.background = activeBgRu
                tabRussianBtn.setTextColor(0xFF81C995.toInt())
                tabDialogueBtn.background = inactiveBg
                tabDialogueBtn.setTextColor(0xFF9AA0A6.toInt())
                tabItalianBtn.background = inactiveBg
                tabItalianBtn.setTextColor(0xFF9AA0A6.toInt())
            }
        }
    }

    private fun updateDialogueOriginalVisibility() {
        for (holder in turnViewHolders) {
            holder.italianContainer.visibility = if (showOriginalText) View.VISIBLE else View.GONE
            holder.ruBadge.text = if (showOriginalText) "➔ 🇷🇺 ПЕРЕВОД" else "🇷🇺 ПЕРЕВОД"
            holder.ruTimeView.visibility = if (showOriginalText) View.GONE else View.VISIBLE
        }
        if (::dialogueTitleText.isInitialized) {
            dialogueTitleText.text = if (showOriginalText) "💬 ДИАЛОГ (IT / EN ➔ RU)" else "💬 ДИАЛОГ (ТОЛЬКО ПЕРЕВОД)"
        }
        if (::dialogueEmptyPlaceholder.isInitialized && dialogueFeedLayout.childCount == 1 && dialogueEmptyPlaceholder.visibility == View.VISIBLE) {
            dialogueEmptyPlaceholder.text = if (showOriginalText) {
                "💬 Режим диалога (Google Translate)\n\nГоворите или слушайте речь на итальянском/английском языке.\nРеплики и синхронный перевод появятся здесь последовательно в единой прокручиваемой ленте."
            } else {
                "💬 Режим диалога (Только перевод)\n\nОригинал речи скрыт по настройке Google Translate.\nЗдесь появятся только карточки русского перевода в реальном времени."
            }
        }
        if (::dialogueScrollView.isInitialized) {
            dialogueScrollView.post { dialogueScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun updateShowOriginalBadge(isChecked: Boolean) {
        if (::showOriginalBadge.isInitialized) {
            if (isChecked) {
                showOriginalBadge.text = "🇮🇹 IT/EN + 🇷🇺 RU"
                showOriginalBadge.setTextColor(0xFF8AB4F8.toInt())
                showOriginalBadge.background = createRoundedDrawable(0xFF1E2838.toInt(), 0xFF2A3850.toInt(), 1, 6f)
            } else {
                showOriginalBadge.text = "🇷🇺 ТОЛЬКО ПЕРЕВОД"
                showOriginalBadge.setTextColor(0xFF81C995.toInt())
                showOriginalBadge.background = createRoundedDrawable(0xFF162A1F.toInt(), 0xFF1E402B.toInt(), 1, 6f)
            }
        }
        if (::dialogueTitleText.isInitialized) {
            dialogueTitleText.text = if (isChecked) "💬 ДИАЛОГ (IT / EN ➔ RU)" else "💬 ДИАЛОГ (ТОЛЬКО ПЕРЕВОД)"
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
        val wasAtBottom = !italianScrollView.canScrollVertically(1)
        val current = italianTranscriptText.text.toString()
        if (current == IT_PLACEHOLDER || current.startsWith("Слушаю") || current.startsWith("Говорите") || current.startsWith("Здесь")) {
            italianTranscriptText.text = text
            italianTranscriptText.setTextColor(0xFFE8EAED.toInt())
        } else {
            if (text.startsWith(" ") || current.endsWith(" ")) {
                italianTranscriptText.append(text)
            } else {
                italianTranscriptText.append(" $text")
            }
        }
        if (italianTranscriptText.length() > 25000) {
            val fullStr = italianTranscriptText.text.toString()
            val cutIndex = fullStr.indexOf(" ", fullStr.length - 18000)
            if (cutIndex != -1) {
                italianTranscriptText.text = fullStr.substring(cutIndex + 1)
            }
        }
        if (wasAtBottom) {
            italianScrollView.post { italianScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun appendRussianText(text: String) {
        val wasAtBottom = !russianScrollView.canScrollVertically(1)
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
        if (russianTranscriptText.length() > 25000) {
            val fullStr = russianTranscriptText.text.toString()
            val cutIndex = fullStr.indexOf(" ", fullStr.length - 18000)
            if (cutIndex != -1) {
                russianTranscriptText.text = fullStr.substring(cutIndex + 1)
            }
        }
        if (wasAtBottom) {
            russianScrollView.post { russianScrollView.fullScroll(View.FOCUS_DOWN) }
        }
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

        PocketService.onDialogueTurnUpdated = { turnIndex, turn, isNew ->
            onDialogueUpdated(turnIndex, turn, isNew)
        }
    }

    private fun createTurnViewHolder(turn: DialogueTurn): TurnViewHolder {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) }
            layoutParams = params
        }

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(turn.timestamp))

        // 1. Карточка речи собеседника (итальянский / английский)
        val itBubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(0xFF1E2638.toInt(), 0xFF2E3D5C.toInt(), 1, 14f)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(4)) }
            layoutParams = params
            visibility = if (showOriginalText) View.VISIBLE else View.GONE
        }

        val itHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(4)) }
            layoutParams = params
        }

        val itBadge = TextView(this).apply {
            text = "🇮🇹 РЕЧЬ (IT / EN)"
            textSize = 10.5f
            setTextColor(0xFF8AB4F8.toInt())
            setTypeface(null, Typeface.BOLD)
        }
        itHeader.addView(itBadge)

        val itSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        itHeader.addView(itSpacer)

        val timeView = TextView(this).apply {
            text = timeStr
            textSize = 10f
            setTextColor(0xFF7B889B.toInt())
        }
        itHeader.addView(timeView)
        itBubble.addView(itHeader)

        val itTv = TextView(this).apply {
            text = if (turn.italianText.isBlank()) "(распознавание...)" else turn.italianText
            textSize = 16.5f
            setTextColor(0xFFE8EAED.toInt())
            setLineSpacing(dpF(3f), 1.2f)
        }
        itBubble.addView(itTv)
        container.addView(itBubble)

        // 2. Карточка русского перевода
        val ruBubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(0xFF14241B.toInt(), 0xFF1E422C.toInt(), 1, 14f)
            setPadding(dp(12), dp(9), dp(12), dp(10))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
        }

        val ruHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(4)) }
            layoutParams = params
        }

        val ruBadge = TextView(this).apply {
            text = if (showOriginalText) "➔ 🇷🇺 ПЕРЕВОД" else "🇷🇺 ПЕРЕВОД"
            textSize = 11f
            setTextColor(0xFF81C995.toInt())
            setTypeface(null, Typeface.BOLD)
        }
        ruHeader.addView(ruBadge)

        val ruSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        ruHeader.addView(ruSpacer)

        val ruTimeView = TextView(this).apply {
            text = timeStr
            textSize = 10f
            setTextColor(0xFF7B889B.toInt())
            visibility = if (showOriginalText) View.GONE else View.VISIBLE
        }
        ruHeader.addView(ruTimeView)
        ruBubble.addView(ruHeader)

        val isRuBlank = turn.russianText.isBlank()
        val ruTv = TextView(this).apply {
            text = if (isRuBlank) "⏳ Ожидание перевода..." else turn.russianText
            textSize = 17.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isRuBlank) 0xFF4A6B56.toInt() else 0xFF81C995.toInt())
            setLineSpacing(dpF(4f), 1.25f)
        }
        ruBubble.addView(ruTv)
        container.addView(ruBubble)

        return TurnViewHolder(container, itBubble, itTv, ruTv, ruBadge, ruTimeView)
    }

    private fun onDialogueUpdated(turnIndex: Int, turn: DialogueTurn, isNew: Boolean) {
        runOnUiThread {
            if (::dialogueEmptyPlaceholder.isInitialized && dialogueEmptyPlaceholder.visibility == View.VISIBLE) {
                dialogueEmptyPlaceholder.visibility = View.GONE
            }

            // Sticky scroll: Autoscroll only if user was already at the bottom
            val wasAtBottom = !dialogueScrollView.canScrollVertically(1)

            if (isNew || turnIndex >= turnViewHolders.size) {
                val holder = createTurnViewHolder(turn)
                turnViewHolders.add(holder)
                dialogueFeedLayout.addView(holder.container)
                while (turnViewHolders.size > 200) {
                    val oldest = turnViewHolders.removeAt(0)
                    dialogueFeedLayout.removeView(oldest.container)
                }
            } else {
                val holder = turnViewHolders[turnIndex]
                holder.italianContainer.visibility = if (showOriginalText) View.VISIBLE else View.GONE
                holder.ruBadge.text = if (showOriginalText) "➔ 🇷🇺 ПЕРЕВОД" else "🇷🇺 ПЕРЕВОД"
                holder.ruTimeView.visibility = if (showOriginalText) View.GONE else View.VISIBLE
                if (turn.italianText.isNotBlank()) {
                    holder.italianText.text = turn.italianText
                }
                if (turn.russianText.isNotBlank()) {
                    holder.russianText.text = turn.russianText
                    holder.russianText.setTextColor(0xFF81C995.toInt())
                } else {
                    holder.russianText.text = "⏳ Ожидание перевода..."
                    holder.russianText.setTextColor(0xFF4A6B56.toInt())
                }
            }

            if (wasAtBottom) {
                dialogueScrollView.post { dialogueScrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun restoreDialogueView() {
        if (!::dialogueFeedLayout.isInitialized) return
        dialogueFeedLayout.removeAllViews()
        turnViewHolders.clear()

        val turns = PocketService.getSessionDialogueTurns()
        if (turns.isEmpty()) {
            dialogueEmptyPlaceholder.visibility = View.VISIBLE
            dialogueFeedLayout.addView(dialogueEmptyPlaceholder)
        } else {
            dialogueEmptyPlaceholder.visibility = View.GONE
            for (turn in turns) {
                val holder = createTurnViewHolder(turn)
                turnViewHolders.add(holder)
                dialogueFeedLayout.addView(holder.container)
            }
            dialogueScrollView.post { dialogueScrollView.fullScroll(View.FOCUS_DOWN) }
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
        val btConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
        return recordAudio && postNotif && btConnect
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (checkPermissions()) {
                Toast.makeText(this, "Разрешения предоставлены! Нажмите 'ЗАПУСТИТЬ СУФЛЁР'", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Требуются разрешения на запись звука для суфлёра", Toast.LENGTH_LONG).show()
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
            text = "v1.0.22"
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
        // 2. Селектор микрофона: Смартфон vs Гарнитура
        // ==========================================
        val micSelectorBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createRoundedDrawable(0xFF1A1D24.toInt(), 0xFF2B3240.toInt(), 1, 14f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
            layoutParams = params
        }

        micPhoneBtn = TextView(this).apply {
            text = "📱 Микрофон смартфона"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
            isClickable = true
            setOnClickListener {
                setMicSource(MicSource.SMARTPHONE)
            }
        }
        micSelectorBar.addView(micPhoneBtn)

        micBluetoothBtn = TextView(this).apply {
            text = "🎧 Микрофон гарнитуры (BT)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
            isClickable = true
            setOnClickListener {
                setMicSource(MicSource.BLUETOOTH)
            }
        }
        micSelectorBar.addView(micBluetoothBtn)
        root.addView(micSelectorBar)

        updateMicSourceStyles()

        // ========================================================
        // 3. Google Translate Языковая панель: Диалог / IT / RU
        // ========================================================
        val languageBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createRoundedDrawable(0xFF1A1D24.toInt(), 0xFF2B3240.toInt(), 1, 16f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
            layoutParams = params
        }

        tabDialogueBtn = TextView(this).apply {
            text = "💬 Режим диалога"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f)
            layoutParams = params
            isClickable = true
            setOnClickListener {
                setScreenMode(ScreenMode.DIALOGUE)
            }
        }
        languageBar.addView(tabDialogueBtn)

        tabItalianBtn = TextView(this).apply {
            text = "🇮🇹 Итальянский"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(7))
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.05f)
            layoutParams = params
            isClickable = true
            setOnClickListener {
                setScreenMode(ScreenMode.ITALIAN)
            }
        }
        languageBar.addView(tabItalianBtn)

        val swapArrowBtn = TextView(this).apply {
            text = "⇄"
            textSize = 15f
            setTextColor(0xFF80868B.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(7), dp(4), dp(7))
            isClickable = true
            setOnClickListener {
                when (currentMode) {
                    ScreenMode.ITALIAN -> setScreenMode(ScreenMode.RUSSIAN)
                    ScreenMode.RUSSIAN -> setScreenMode(ScreenMode.ITALIAN)
                    ScreenMode.DIALOGUE -> setScreenMode(ScreenMode.RUSSIAN)
                }
            }
        }
        languageBar.addView(swapArrowBtn)

        tabRussianBtn = TextView(this).apply {
            text = "🇷🇺 Русский"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(7))
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.05f)
            layoutParams = params
            isClickable = true
            setOnClickListener {
                setScreenMode(ScreenMode.RUSSIAN)
            }
        }
        languageBar.addView(tabRussianBtn)

        root.addView(languageBar)

        // ==========================================
        // 3. Индикаторы: Статус + Телеметрия
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
        // 4. Google Translate Conversation Container
        // ==========================================
        conversationContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { setMargins(0, 0, 0, dp(10)) }
            layoutParams = params
        }

        // ----------------------------------------------------
        // РЕЖИМ 1: 💬 Google Translate Conversation Mode (Диалог)
        // ----------------------------------------------------
        dialogueCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(0xFF16181F.toInt(), 0xFF242A38.toInt(), 1, 18f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutParams = params
        }

        val dialogueHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
            layoutParams = params
        }

        dialogueTitleText = TextView(this).apply {
            text = "💬 ДИАЛОГ (IT / EN ➔ RU)"
            textSize = 11.5f
            setTextColor(0xFF8AB4F8.toInt())
            setTypeface(null, Typeface.BOLD)
            background = createRoundedDrawable(0xFF20283C.toInt(), 0xFF313F5E.toInt(), 1, 8f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        dialogueHeader.addView(dialogueTitleText)

        val dialogueHeaderSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        dialogueHeader.addView(dialogueHeaderSpacer)

        copyDialogueBtn = TextView(this).apply {
            text = "📋 Копировать"
            textSize = 11f
            setTextColor(0xFFCCCCCC.toInt())
            background = createRoundedDrawable(0xFF252A36.toInt(), cornerRadiusDp = 6f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener {
                val turns = PocketService.getSessionDialogueTurns()
                if (turns.isEmpty()) {
                    Toast.makeText(this@MainActivity, "История диалога пуста", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val sb = StringBuilder()
                if (showOriginalText) {
                    sb.appendLine("=== POCKET HUD ДИАЛОГ ===")
                    for (t in turns) {
                        if (t.italianText.isNotBlank()) {
                            sb.appendLine("🇮🇹 ${t.italianText}")
                        }
                        if (t.russianText.isNotBlank()) {
                            sb.appendLine("➔ 🇷🇺 ${t.russianText}")
                        }
                        sb.appendLine()
                    }
                    sb.appendLine("=========================")
                } else {
                    val ruTurns = turns.filter { it.russianText.isNotBlank() }
                    if (ruTurns.isEmpty()) {
                        Toast.makeText(this@MainActivity, "Переводы ещё не получены", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    sb.appendLine("=== POCKET HUD ПЕРЕВОД ===")
                    for (t in ruTurns) {
                        sb.appendLine(t.russianText)
                    }
                    sb.appendLine("=========================")
                }
                copyToClipboard("PocketHUD Dialogue", sb.toString().trim())
                Toast.makeText(this@MainActivity, if (showOriginalText) "История диалога скопирована" else "Русский перевод диалога скопирован", Toast.LENGTH_SHORT).show()
            }
        }
        dialogueHeader.addView(copyDialogueBtn)

        clearDialogueBtn = TextView(this).apply {
            text = "🗑️ Очистить"
            textSize = 11f
            setTextColor(0xFFCCCCCC.toInt())
            background = createRoundedDrawable(0xFF252A36.toInt(), cornerRadiusDp = 6f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, 0, 0) }
            layoutParams = params
            isClickable = true
            setOnClickListener {
                PocketService.clearDialogueTurns()
                PocketService.clearSessionTexts()
                restoreDialogueView()
                resetItalianCard()
                resetRussianCard()
                Toast.makeText(this@MainActivity, "История диалога очищена", Toast.LENGTH_SHORT).show()
            }
        }
        dialogueHeader.addView(clearDialogueBtn)
        dialogueCard.addView(dialogueHeader)

        // Подпанель настроек диалога (Google Translate Conversation Mode: чекбокс скрытия оригинала)
        val dialogueOptionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createRoundedDrawable(0xFF1B202C.toInt(), 0xFF2A3448.toInt(), 1, 10f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
            layoutParams = params
            isClickable = true
            setOnClickListener {
                showOriginalCheckBox.isChecked = !showOriginalCheckBox.isChecked
            }
        }

        showOriginalCheckBox = MaterialCheckBox(this).apply {
            text = "Показывать оригинал"
            textSize = 12f
            setTextColor(0xFFE8EAED.toInt())
            isChecked = showOriginalText
            minHeight = 0
            minimumHeight = 0
            val states = arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            )
            val colors = intArrayOf(
                0xFF8AB4F8.toInt(),
                0xFF80868B.toInt()
            )
            buttonTintList = ColorStateList(states, colors)
            compoundDrawablePadding = dp(6)
        }
        dialogueOptionsRow.addView(showOriginalCheckBox)

        val optionsSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        dialogueOptionsRow.addView(optionsSpacer)

        showOriginalBadge = TextView(this).apply {
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener {
                showOriginalCheckBox.isChecked = !showOriginalCheckBox.isChecked
            }
        }
        updateShowOriginalBadge(showOriginalText)
        dialogueOptionsRow.addView(showOriginalBadge)

        dialogueCard.addView(dialogueOptionsRow)

        dialogueScrollView = ScrollView(this).apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutParams = params
            isFillViewport = true
        }

        dialogueFeedLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
        }

        dialogueEmptyPlaceholder = TextView(this).apply {
            text = "💬 Режим диалога (Google Translate)\n\nГоворите или слушайте речь на итальянском/английском языке.\nРеплики и синхронный перевод появятся здесь последовательно в единой прокручиваемой ленте."
            textSize = 14.5f
            setTextColor(0xFF70757A.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(40), dp(16), dp(40))
            setLineSpacing(dpF(4f), 1.25f)
        }
        dialogueFeedLayout.addView(dialogueEmptyPlaceholder)

        dialogueScrollView.addView(dialogueFeedLayout)
        dialogueCard.addView(dialogueScrollView)
        conversationContainer.addView(dialogueCard)

        // ------------------------------------------
        // ЭКРАН 1: 🇮🇹 ITALIANO (Речь собеседника)
        // ------------------------------------------
        italianCard = LinearLayout(this).apply {
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

        italianTitleText = TextView(this).apply {
            text = "🇮🇹 ITALIANO"
            textSize = 11.5f
            setTextColor(0xFF8AB4F8.toInt())
            setTypeface(null, Typeface.BOLD)
            background = createRoundedDrawable(0xFF28344D.toInt(), cornerRadiusDp = 8f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        italianHeader.addView(italianTitleText)

        val italianHeaderSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        italianHeader.addView(italianHeaderSpacer)

        expandItalianBtn = TextView(this).apply {
            text = "💬 Диалог"
            textSize = 11f
            setTextColor(0xFF8AB4F8.toInt())
            background = createRoundedDrawable(0xFF233045.toInt(), 0xFF354868.toInt(), 1, 6f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener {
                setScreenMode(ScreenMode.DIALOGUE)
            }
        }
        italianHeader.addView(expandItalianBtn)

        val copyItalianBtn = TextView(this).apply {
            text = "📋 Копировать"
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
                val txt = italianTranscriptText.text.toString()
                if (txt.isNotBlank() && txt != IT_PLACEHOLDER) {
                    copyToClipboard("PocketHUD Speech Transcript", txt)
                    Toast.makeText(this@MainActivity, "Текст речи (IT/EN) скопирован", Toast.LENGTH_SHORT).show()
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
                PocketService.clearItalianSessionText()
                Toast.makeText(this@MainActivity, "Текст речи очищен", Toast.LENGTH_SHORT).show()
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
            setLineSpacing(dpF(4f), 1.25f)
        }
        italianScrollView.addView(italianTranscriptText)
        italianCard.addView(italianScrollView)
        conversationContainer.addView(italianCard)

        // ------------------------------------------
        // ЭКРАН 2: 🇷🇺 РУССКИЙ (Перевод)
        // ------------------------------------------
        russianCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(0xFF14201A.toInt(), 0xFF1E3A2B.toInt(), 1, 18f)
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

        russianTitleText = TextView(this).apply {
            text = "🇷🇺 РУССКИЙ"
            textSize = 11.5f
            setTextColor(0xFF81C995.toInt())
            setTypeface(null, Typeface.BOLD)
            background = createRoundedDrawable(0xFF1A3828.toInt(), cornerRadiusDp = 8f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        russianHeader.addView(russianTitleText)

        val russianHeaderSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        russianHeader.addView(russianHeaderSpacer)

        expandRussianBtn = TextView(this).apply {
            text = "💬 Диалог"
            textSize = 11f
            setTextColor(0xFF81C995.toInt())
            background = createRoundedDrawable(0xFF1E3526.toInt(), 0xFF2D5539.toInt(), 1, 6f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener {
                setScreenMode(ScreenMode.DIALOGUE)
            }
        }
        russianHeader.addView(expandRussianBtn)

        val copyRussianBtn = TextView(this).apply {
            text = "📋 Копировать"
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
                PocketService.clearRussianSessionText()
                Toast.makeText(this@MainActivity, "Русский перевод очищен", Toast.LENGTH_SHORT).show()
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
            setLineSpacing(dpF(5f), 1.25f)
        }
        russianScrollView.addView(russianTranscriptText)
        russianCard.addView(russianScrollView)
        conversationContainer.addView(russianCard)

        root.addView(conversationContainer)

        // ==========================================
        // 5. Нижняя панель управления (Bottom Dock)
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
