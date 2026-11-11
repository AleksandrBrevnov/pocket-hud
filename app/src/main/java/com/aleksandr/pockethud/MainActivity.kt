package com.aleksandr.pockethud

import android.Manifest
import android.app.Dialog
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
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
    private lateinit var shareDialogueBtn: TextView
    private lateinit var historyDialogueBtn: TextView
    private lateinit var aiSummaryBtn: TextView
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
    private lateinit var shareDialogueDockBtn: Button
    private lateinit var shareLogBtn: Button
    private lateinit var copySummaryBtn: Button
    private lateinit var engineSelectorBar: LinearLayout
    private lateinit var engineCascadedBtn: TextView
    private lateinit var engineLiveBtn: TextView
    private lateinit var overlayToggleBtn: Button
    private lateinit var ttsModeBtn: Button
    private lateinit var mlKitStatusBadge: TextView

    private val prefsName = "pocket_hud_prefs"
    private val keyApiKey = "api_key"
    private val keyAudioMuted = "audio_muted"
    private val keyScreenMode = "screen_mode"
    private val keyMicSource = "mic_source"
    private val keyShowOriginalText = "show_original_text"
    private val keyTranslationEngine = "translation_engine"
    private val keyRestModel = "rest_flash_model"
    private val keyTtsMode = "tts_mode"

    private var currentMode = ScreenMode.DIALOGUE
    private var currentMicSource = MicSource.SMARTPHONE
    private var currentEngine = TranslationEngine.CASCADED_REST
    private var showOriginalText = true
    private val restModelButtons = mutableMapOf<String, TextView>()

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

        val savedEngineStr = prefs.getString(keyTranslationEngine, TranslationEngine.CASCADED_REST.name) ?: TranslationEngine.CASCADED_REST.name
        currentEngine = try {
            TranslationEngine.valueOf(savedEngineStr)
        } catch (e: Exception) {
            TranslationEngine.CASCADED_REST
        }
        PocketService.selectedEngine = currentEngine
        updateEngineStyles()

        val savedModel = prefs.getString(keyRestModel, "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"
        val validModel = if (savedModel == "gemini-2.0-flash" || savedModel == "gemini-1.5-flash" || savedModel == "gemini-2.5-flash" || savedModel !in PocketService.REST_FLASH_MODELS) {
            prefs.edit().putString(keyRestModel, "gemini-3.5-flash-lite").apply()
            "gemini-3.5-flash-lite"
        } else {
            savedModel
        }
        PocketService.selectedRestModel = validModel

        val isMuted = prefs.getBoolean(keyAudioMuted, false)
        PocketService.isAudioMuted = isMuted
        updateMuteButtonUi()

        val savedTtsModeStr = prefs.getString(keyTtsMode, TtsMode.GIST_ONLY.name) ?: TtsMode.GIST_ONLY.name
        val savedTtsMode = try {
            TtsMode.valueOf(savedTtsModeStr)
        } catch (e: Exception) {
            TtsMode.GIST_ONLY
        }
        PocketService.selectedTtsMode = savedTtsMode
        updateTtsModeButtonUi()
        updateOverlayButtonUi()

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
                    putExtra(PocketService.EXTRA_TRANSLATION_ENGINE, currentEngine.name)
                    putExtra(PocketService.EXTRA_REST_MODEL, PocketService.selectedRestModel)
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
        currentMicSource = PocketService.selectedMicSource
        currentEngine = PocketService.selectedEngine
        setupServiceCallbacks()
        updateUi()
        updateMuteButtonUi()
        updateMicSourceStyles()
        updateEngineStyles()
        updateTtsModeButtonUi()
        updateOverlayButtonUi()
        updateMlKitStatusBadge(LocalTranslateManager.isModelReady(), LocalTranslateManager.isDownloadingModel())

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

    private fun updateEngineStyles() {
        if (!::engineCascadedBtn.isInitialized || !::engineLiveBtn.isInitialized) return

        val activeCascadedBg = createRoundedDrawable(0xFF2E2A14.toInt(), 0xFFFBC02D.toInt(), 1, 12f)
        val activeLiveBg = createRoundedDrawable(0xFF1E3A5F.toInt(), 0xFF4285F4.toInt(), 1, 12f)
        val inactiveBg = createRoundedDrawable(0x00000000, cornerRadiusDp = 12f)

        if (currentEngine == TranslationEngine.CASCADED_REST) {
            engineCascadedBtn.background = activeCascadedBg
            engineCascadedBtn.setTextColor(0xFFFDD835.toInt())
            engineLiveBtn.background = inactiveBg
            engineLiveBtn.setTextColor(0xFF9AA0A6.toInt())
        } else {
            engineLiveBtn.background = activeLiveBg
            engineLiveBtn.setTextColor(0xFF8AB4F8.toInt())
            engineCascadedBtn.background = inactiveBg
            engineCascadedBtn.setTextColor(0xFF9AA0A6.toInt())
        }
    }

    private fun setTranslationEngine(engine: TranslationEngine) {
        if (currentEngine == engine) return
        currentEngine = engine
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString(keyTranslationEngine, engine.name).apply()
        PocketService.selectedEngine = engine
        updateEngineStyles()
        updateTtsModeButtonUi()
        val msg = if (engine == TranslationEngine.CASCADED_REST) {
            "⚡ Включен каскадный режим (ASR it-IT + Flash REST + TTS)"
        } else {
            "🌐 Включен синхронный режим (Gemini Live WebSocket)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun setRestModel(model: String) {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString(keyRestModel, model).apply()
        PocketService.selectedRestModel = model
        updateRestModelStyles(model)
        Toast.makeText(this, "Выбрана модель REST: $model", Toast.LENGTH_SHORT).show()
    }

    private fun updateRestModelStyles(currentModel: String = PocketService.selectedRestModel) {
        val activeBg = createRoundedDrawable(0xFF1E3A8A.toInt(), 0xFF3B82F6.toInt(), 1, 8f)
        val inactiveBg = createRoundedDrawable(android.graphics.Color.TRANSPARENT, 0, 0, 8f)
        for ((model, btn) in restModelButtons) {
            if (model == currentModel) {
                btn.background = activeBg
                btn.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btn.background = inactiveBg
                btn.setTextColor(0xFF9AA0A6.toInt())
            }
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

    private fun updateTtsModeButtonUi() {
        if (!::ttsModeBtn.isInitialized) return
        if (currentEngine == TranslationEngine.LIVE_WEBSOCKET) {
            ttsModeBtn.text = "⚡ TTS: Gemini Live (нейро)"
            ttsModeBtn.background = createButtonDrawable(0xFF1E293B.toInt(), 14f)
            ttsModeBtn.alpha = 0.85f
            return
        }
        ttsModeBtn.alpha = 1.0f
        when (PocketService.selectedTtsMode) {
            TtsMode.GIST_ONLY -> {
                ttsModeBtn.text = "💡 TTS: СУТЬ (Gist)"
                ttsModeBtn.background = createButtonDrawable(0xFF0F766E.toInt(), 14f)
            }
            TtsMode.FULL_PHRASE -> {
                ttsModeBtn.text = "🗣️ TTS: ПОЛНАЯ"
                ttsModeBtn.background = createButtonDrawable(0xFF6D28D9.toInt(), 14f)
            }
            TtsMode.MUTED -> {
                ttsModeBtn.text = "🔇 TTS: ВЫКЛ"
                ttsModeBtn.background = createButtonDrawable(0xFF78350F.toInt(), 14f)
            }
        }
    }

    private fun updateMlKitStatusBadge(isReady: Boolean, isDownloading: Boolean) {
        if (!::mlKitStatusBadge.isInitialized) return
        runOnUiThread {
            when {
                isReady -> {
                    mlKitStatusBadge.text = "⚡ ML Kit: Готов"
                    mlKitStatusBadge.setTextColor(0xFF34D399.toInt())
                    mlKitStatusBadge.background = createRoundedDrawable(0xFF133E32.toInt(), 0xFF10B981.toInt(), 1, 6f)
                }
                isDownloading -> {
                    mlKitStatusBadge.text = "⏳ ML Kit: Загрузка..."
                    mlKitStatusBadge.setTextColor(0xFFFBBF24.toInt())
                    mlKitStatusBadge.background = createRoundedDrawable(0xFF382D13.toInt(), 0xFFF59E0B.toInt(), 1, 6f)
                }
                else -> {
                    mlKitStatusBadge.text = "ML Kit: Ожидание"
                    mlKitStatusBadge.setTextColor(0xFF9CA3AF.toInt())
                    mlKitStatusBadge.background = createRoundedDrawable(0xFF1F2937.toInt(), 0xFF374151.toInt(), 1, 6f)
                }
            }
        }
    }

    private fun updateOverlayButtonUi() {
        if (!::overlayToggleBtn.isInitialized) return
        if (FloatingWidgetService.isOverlayActive) {
            overlayToggleBtn.text = "🪟 ОВЕРЛЕЙ: ВКЛ"
            overlayToggleBtn.background = createButtonDrawable(0xFF15803D.toInt(), 14f)
        } else {
            overlayToggleBtn.text = "🪟 ОВЕРЛЕЙ: ВЫКЛ"
            overlayToggleBtn.background = createButtonDrawable(0xFF334155.toInt(), 14f)
        }
    }

    private fun toggleFloatingOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Предоставьте разрешение на отображение поверх других окон", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return
            }
        }

        if (FloatingWidgetService.isOverlayActive) {
            FloatingWidgetService.hideOverlay(this)
            Toast.makeText(this, "Плавающий оверлей скрыт", Toast.LENGTH_SHORT).show()
        } else {
            FloatingWidgetService.showOverlay(this)
            Toast.makeText(this, "Плавающий оверлей запущен", Toast.LENGTH_SHORT).show()
        }
        updateOverlayButtonUi()
    }

    private fun toggleTtsMode() {
        if (currentEngine == TranslationEngine.LIVE_WEBSOCKET) {
            Toast.makeText(this, "В режиме Gemini Live озвучка синтезируется напрямую нейросетью Google (PCM 24 кГц)", Toast.LENGTH_SHORT).show()
            return
        }
        val nextMode = when (PocketService.selectedTtsMode) {
            TtsMode.GIST_ONLY -> TtsMode.FULL_PHRASE
            TtsMode.FULL_PHRASE -> TtsMode.GIST_ONLY
            TtsMode.MUTED -> TtsMode.GIST_ONLY
        }
        PocketService.selectedTtsMode = nextMode
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString(keyTtsMode, nextMode.name).apply()
        updateTtsModeButtonUi()
        val msg = if (nextMode == TtsMode.GIST_ONLY) {
            "💡 Включен режим Gist-Only (шепот 2-4 опорных слова в паузе)"
        } else {
            "🗣️ Включен режим полной фразы (озвучивание предложения целиком)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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

    private fun shareDialogue(
        customTurns: List<DialogueTurn>? = null,
        customDate: String? = null
    ) {
        val turns = customTurns ?: PocketService.getSessionDialogueTurns()
        if (turns.isEmpty()) {
            Toast.makeText(this, "История диалога пуста. Запустите суфлёр!", Toast.LENGTH_SHORT).show()
            return
        }

        val textToShare = DialogueHistoryManager.formatDialogueText(turns, showOriginalText, customDate)
        val textFile = DialogueHistoryManager.saveSessionTextFile(this, turns, customDate)

        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                textFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, textToShare)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("PocketHUD Dialogue", uri)
                putExtra(Intent.EXTRA_SUBJECT, if (customDate != null) "PocketHUD Диалог ($customDate)" else "PocketHUD Диалог")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Поделиться диалогом PocketHUD"))
        } catch (e: Exception) {
            try {
                val textIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, textToShare)
                    putExtra(Intent.EXTRA_SUBJECT, "PocketHUD Диалог")
                }
                startActivity(Intent.createChooser(textIntent, "Поделиться диалогом PocketHUD"))
            } catch (e2: Exception) {
                Toast.makeText(this, "Ошибка отправки диалога: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestAiSummary() {
        val turns = PocketService.getSessionDialogueTurns()
        if (turns.isEmpty()) {
            Toast.makeText(this, "История диалога пуста. Запустите сервис и проведите диалог!", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "⚡ Формирование AI-конспекта встречи через Gemini 3.8 Flash...", Toast.LENGTH_LONG).show()

        PocketService.generateSessionAiSummary(this) { success, result ->
            runOnUiThread {
                if (success) {
                    showAiSummaryDialog(result)
                } else {
                    Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAiSummaryDialog(summaryMarkdown: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF131722.toInt())
            setPadding(dp(20), dp(20), dp(20), dp(20))

            addView(TextView(context).apply {
                text = "⚡ AI-Конспект встречи (Gemini 3.8 Flash)"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
            })

            val sv = ScrollView(context).apply {
                val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(360)
                ).apply { setMargins(0, dp(12), 0, dp(12)) }
                layoutParams = p
            }
            val contentTv = TextView(context).apply {
                text = summaryMarkdown
                textSize = 13f
                setTextColor(0xFFE2E8F0.toInt())
                setLineSpacing(dp(3).toFloat(), 1.0f)
                setTextIsSelectable(true)
            }
            sv.addView(contentTv)
            addView(sv)

            val btnBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }

            val copyBtn = Button(context).apply {
                text = "📋 Копировать"
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                background = createRoundedDrawable(0xFF10B981.toInt(), cornerRadiusDp = 8f)
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("PocketHUD AI Summary", summaryMarkdown))
                    Toast.makeText(context, "Конспект скопирован в буфер обмена!", Toast.LENGTH_SHORT).show()
                }
            }
            btnBar.addView(copyBtn)

            val shareBtn = Button(context).apply {
                text = "📤 Отправить"
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                background = createRoundedDrawable(0xFF3B82F6.toInt(), cornerRadiusDp = 8f)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), 0, 0, 0)
                }
                layoutParams = lp
                setOnClickListener {
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, summaryMarkdown)
                        type = "text/plain"
                    }
                    startActivity(Intent.createChooser(sendIntent, "Поделиться AI-конспектом"))
                }
            }
            btnBar.addView(shareBtn)

            val closeBtn = Button(context).apply {
                text = "Закрыть"
                textSize = 12f
                setTextColor(0xFFCCCCCC.toInt())
                background = createRoundedDrawable(0xFF374151.toInt(), cornerRadiusDp = 8f)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), 0, 0, 0)
                }
                layoutParams = lp
                setOnClickListener { dialog.dismiss() }
            }
            btnBar.addView(closeBtn)

            addView(btnBar)
        })
        dialog.show()
    }

    private val serviceListener = object : PocketService.PocketServiceListener {
        override fun onStatusChanged(status: String) {
            runOnUiThread {
                statusBadgeText.text = status
                if (status.contains("🟢") || status.contains("🔵") || status.contains("⚡")) {
                    toggleBtn.text = "⏹️ ОСТАНОВИТЬ СУФЛЁР"
                    toggleBtn.background = createButtonDrawable(0xFFD93025.toInt(), 26f)
                } else if (status.contains("🔴") || status.contains("⚪")) {
                    toggleBtn.text = "🎙️ ЗАПУСТИТЬ СУФЛЁР"
                    toggleBtn.background = createButtonDrawable(0xFF1E8E3E.toInt(), 26f)
                }
            }
        }

        override fun onTextReceived(text: String) {
            runOnUiThread {
                appendRussianText(text)
            }
        }
    }

    private fun setupServiceCallbacks() {
        PocketService.addListener(serviceListener)

        LocalTranslateManager.onStatusChanged = { isReady, isDownloading ->
            updateMlKitStatusBadge(isReady, isDownloading)
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

        PocketService.onDialogueTurnUpdated = { turnIndex, turn, isNew ->
            onDialogueUpdated(turnIndex, turn, isNew)
        }

        PocketService.onMuteStateChanged = { isMuted ->
            runOnUiThread {
                updateMuteButtonUi()
            }
        }

        PocketService.onMicSourceChanged = { source ->
            runOnUiThread {
                currentMicSource = source
                updateMicSourceStyles()
            }
        }

        PocketService.onEngineChanged = { engine ->
            runOnUiThread {
                currentEngine = engine
                updateEngineStyles()
            }
        }

        PocketService.onRestModelChanged = { model ->
            runOnUiThread {
                updateRestModelStyles(model)
            }
        }

        PocketService.onTtsModeChanged = { mode ->
            runOnUiThread {
                updateTtsModeButtonUi()
            }
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
            text = when {
                !isRuBlank -> turn.russianText
                turn.isFinalized -> "⚠️ Перевод не получен (шум / пауза)"
                else -> "⏳ Ожидание перевода..."
            }
            textSize = 17.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(when {
                !isRuBlank -> 0xFF81C995.toInt()
                turn.isFinalized -> 0xFFE5A93C.toInt()
                else -> 0xFF4A6B56.toInt()
            })
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

            // Sticky scroll: Autoscroll only if user was already at or near the bottom (40dp tolerance)
            val diff = dialogueFeedLayout.bottom - (dialogueScrollView.height + dialogueScrollView.scrollY)
            val wasAtBottom = diff <= dp(40) || !dialogueScrollView.canScrollVertically(1)

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
                } else if (turn.isFinalized) {
                    holder.russianText.text = "⚠️ Перевод не получен (шум / пауза)"
                    holder.russianText.setTextColor(0xFFE5A93C.toInt())
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

        if (turns.isNotEmpty()) {
            val itText = PocketService.getSessionItalianText()
            if (itText.isNotBlank() && (italianTranscriptText.text == IT_PLACEHOLDER || italianTranscriptText.text.isBlank())) {
                italianTranscriptText.text = itText
                italianTranscriptText.setTextColor(0xFFE8EAED.toInt())
            }
            val ruText = PocketService.getSessionRussianText()
            if (ruText.isNotBlank() && (russianTranscriptText.text == RU_PLACEHOLDER || russianTranscriptText.text.isBlank())) {
                russianTranscriptText.text = ruText
                russianTranscriptText.setTextColor(0xFF81C995.toInt())
            }
        }

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
            text = "v1.0.46"
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
            text = "Ключ сохраняется локально на устройстве для Gemini Live WebSocket и REST Flash"
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
        // 1.5. Селектор движка: Каскадный (ASR+REST) vs Live (WS)
        // ==========================================
        engineSelectorBar = LinearLayout(this).apply {
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

        engineCascadedBtn = TextView(this).apply {
            text = "⚡ Каскадный (ASR + Flash)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
            isClickable = true
            setOnClickListener {
                setTranslationEngine(TranslationEngine.CASCADED_REST)
            }
        }
        engineSelectorBar.addView(engineCascadedBtn)

        engineLiveBtn = TextView(this).apply {
            text = "🌐 Gemini Live (WebSocket)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
            isClickable = true
            setOnClickListener {
                setTranslationEngine(TranslationEngine.LIVE_WEBSOCKET)
            }
        }
        engineSelectorBar.addView(engineLiveBtn)
        root.addView(engineSelectorBar)

        updateEngineStyles()

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

        // Двухуровневый тулбар диалога: Уровень 1 - заголовок и ML Kit статус; Уровень 2 - HorizontalScrollView кнопок
        val dialogueHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
            layoutParams = params
        }

        val dialogueHeaderTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(6)) }
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
        dialogueHeaderTopRow.addView(dialogueTitleText)

        val dialogueHeaderSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        dialogueHeaderTopRow.addView(dialogueHeaderSpacer)

        mlKitStatusBadge = TextView(this).apply {
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        updateMlKitStatusBadge(LocalTranslateManager.isModelReady(), LocalTranslateManager.isDownloadingModel())
        dialogueHeaderTopRow.addView(mlKitStatusBadge)

        dialogueHeader.addView(dialogueHeaderTopRow)

        val dialogueActionsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
        }

        val dialogueActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
        }

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
                val textToCopy = DialogueHistoryManager.formatDialogueText(turns, showOriginalText)
                copyToClipboard("PocketHUD Dialogue", textToCopy.trim())
                Toast.makeText(this@MainActivity, if (showOriginalText) "История диалога скопирована" else "Русский перевод диалога скопирован", Toast.LENGTH_SHORT).show()
            }
        }
        dialogueActionsRow.addView(copyDialogueBtn)

        shareDialogueBtn = TextView(this).apply {
            text = "📤 Поделиться"
            textSize = 11f
            setTextColor(0xFF81C995.toInt())
            background = createRoundedDrawable(0xFF1B3828.toInt(), 0xFF2D5539.toInt(), 1, 6f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, 0, 0) }
            layoutParams = params
            isClickable = true
            setOnClickListener {
                shareDialogue()
            }
        }
        dialogueActionsRow.addView(shareDialogueBtn)

        historyDialogueBtn = TextView(this).apply {
            text = "📁 История"
            textSize = 11f
            setTextColor(0xFF8AB4F8.toInt())
            background = createRoundedDrawable(0xFF1F2B42.toInt(), cornerRadiusDp = 6f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, 0, 0) }
            layoutParams = params
            isClickable = true
            setOnClickListener {
                showSavedDialoguesDialog()
            }
        }
        dialogueActionsRow.addView(historyDialogueBtn)

        aiSummaryBtn = TextView(this).apply {
            text = "⚡ Конспект"
            textSize = 11f
            setTextColor(0xFF34D399.toInt())
            background = createRoundedDrawable(0xFF133E32.toInt(), cornerRadiusDp = 6f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, 0, 0) }
            layoutParams = params
            isClickable = true
            setOnClickListener {
                requestAiSummary()
            }
        }
        dialogueActionsRow.addView(aiSummaryBtn)

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
                PocketService.clearDialogueTurns(this@MainActivity)
                PocketService.clearSessionTexts()
                restoreDialogueView()
                resetItalianCard()
                resetRussianCard()
                Toast.makeText(this@MainActivity, "Диалог сохранён в историю и очищен с экрана", Toast.LENGTH_SHORT).show()
            }
        }
        dialogueActionsRow.addView(clearDialogueBtn)
        dialogueActionsScroll.addView(dialogueActionsRow)
        dialogueHeader.addView(dialogueActionsScroll)
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

        val quickControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
            layoutParams = lp
        }

        overlayToggleBtn = Button(this).apply {
            text = "🪟 ОВЕРЛЕЙ"
            setTextColor(0xFFFFFFFF.toInt())
            background = createButtonDrawable(0xFF334155.toInt(), 14f)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            setOnClickListener {
                toggleFloatingOverlay()
            }
        }
        val overlayLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, dp(6), 0)
        }
        quickControlsRow.addView(overlayToggleBtn, overlayLp)

        ttsModeBtn = Button(this).apply {
            text = "💡 TTS: СУТЬ"
            setTextColor(0xFFFFFFFF.toInt())
            background = createButtonDrawable(0xFF0F766E.toInt(), 14f)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            setOnClickListener {
                toggleTtsMode()
            }
        }
        val ttsLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        quickControlsRow.addView(ttsModeBtn, ttsLp)

        dockLayout.addView(quickControlsRow)
        updateOverlayButtonUi()
        updateTtsModeButtonUi()

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
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        val muteParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.1f
        ).apply { setMargins(0, 0, dp(6), 0) }
        bottomRow.addView(muteBtn, muteParams)

        shareDialogueDockBtn = Button(this).apply {
            text = "💬 ДИАЛОГ"
            setTextColor(0xFFFFFFFF.toInt())
            background = createButtonDrawable(0xFF1E3A5F.toInt(), 14f)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            setOnClickListener {
                shareDialogue()
            }
        }
        val shareDiagParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.1f
        ).apply { setMargins(0, 0, dp(6), 0) }
        bottomRow.addView(shareDialogueDockBtn, shareDiagParams)

        copySummaryBtn = Button(this).apply {
            text = "📋 СВОДКА"
            setTextColor(0xFFFFFFFF.toInt())
            background = createButtonDrawable(0xFF2E333D.toInt(), 14f)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        val copySummaryParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            0.9f
        ).apply { setMargins(0, 0, dp(6), 0) }
        bottomRow.addView(copySummaryBtn, copySummaryParams)

        shareLogBtn = Button(this).apply {
            text = "📤 ЛОГ"
            setTextColor(0xFFFFFFFF.toInt())
            background = createButtonDrawable(0xFF1F3D2A.toInt(), 14f)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        val shareLogParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            0.9f
        )
        bottomRow.addView(shareLogBtn, shareLogParams)

        dockLayout.addView(bottomRow)
        root.addView(dockLayout)

        return root
    }

    private fun showSavedDialoguesDialog() {
        val sessions = DialogueHistoryManager.listSavedSessions(this)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(0xFF131722.toInt(), 0xFF2A3448.toInt(), 2, 16f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Заголовок
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
            layoutParams = params
        }

        val title = TextView(this).apply {
            text = "📁 Сохранённые диалоги (${sessions.size})"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
        }
        header.addView(title)

        val latestSummary = DialogueHistoryManager.loadLatestSummary(this)
        if (latestSummary != null) {
            val summaryBadge = TextView(this).apply {
                text = "⚡ Конспект"
                textSize = 11f
                setTextColor(0xFF34D399.toInt())
                background = createRoundedDrawable(0xFF133E32.toInt(), cornerRadiusDp = 6f)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(8), 0) }
                layoutParams = params
                isClickable = true
                setOnClickListener {
                    showAiSummaryDialog(latestSummary.first)
                }
            }
            header.addView(summaryBadge)
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(closeBtn)
        root.addView(header)

        if (sessions.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Нет сохранённых диалогов.\nРазговоры сохраняются автоматически при остановке суфлёра или очистке экрана."
                textSize = 12.5f
                setTextColor(0xFF80868B.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(24), dp(12), dp(24))
            }
            root.addView(emptyText)
        } else {
            val scroll = ScrollView(this).apply {
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(420)
                ).apply { setMargins(0, 0, 0, dp(10)) }
                layoutParams = params
            }

            val listContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            for (session in sessions) {
                val itemCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = createRoundedDrawable(0xFF1C2230.toInt(), 0xFF2D384E.toInt(), 1, 12f)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, dp(8)) }
                    layoutParams = params
                }

                // Дата и число реплик
                val metaRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val dateText = TextView(this).apply {
                    text = "📅 ${session.formattedDate}"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF8AB4F8.toInt())
                    val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    layoutParams = params
                }
                metaRow.addView(dateText)

                val countBadge = TextView(this).apply {
                    text = "${session.turnCount} реплик"
                    textSize = 10f
                    setTextColor(0xFFA8C7FA.toInt())
                    background = createRoundedDrawable(0xFF28344E.toInt(), cornerRadiusDp = 6f)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                }
                metaRow.addView(countBadge)
                itemCard.addView(metaRow)

                // Превью
                val previewText = TextView(this).apply {
                    text = session.preview
                    textSize = 11.5f
                    setTextColor(0xFFD2E3FC.toInt())
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, dp(4), 0, dp(8)) }
                    layoutParams = params
                }
                itemCard.addView(previewText)

                // Кнопки действий
                val actionsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }

                val loadBtn = TextView(this).apply {
                    text = "📂 Загрузить"
                    textSize = 11f
                    setTextColor(0xFF81C995.toInt())
                    background = createRoundedDrawable(0xFF1C3829.toInt(), cornerRadiusDp = 6f)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    isClickable = true
                    setOnClickListener {
                        val turns = DialogueHistoryManager.loadSessionTurns(session.file)
                        if (turns.isNotEmpty()) {
                            PocketService.restoreDialogueTurns(turns)
                            DialogueHistoryManager.saveCurrentSessionCache(this@MainActivity, turns)
                            restoreDialogueView()
                            Toast.makeText(this@MainActivity, "Сессия от ${session.formattedDate} загружена на экран", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        } else {
                            Toast.makeText(this@MainActivity, "Ошибка загрузки сессии", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                actionsRow.addView(loadBtn)

                val copyBtn = TextView(this).apply {
                    text = "📋 Копировать"
                    textSize = 11f
                    setTextColor(0xFFE8EAED.toInt())
                    background = createRoundedDrawable(0xFF2E384D.toInt(), cornerRadiusDp = 6f)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(6), 0, 0, 0) }
                    layoutParams = params
                    isClickable = true
                    setOnClickListener {
                        val turns = DialogueHistoryManager.loadSessionTurns(session.file)
                        val textToCopy = DialogueHistoryManager.formatDialogueText(turns, showOriginalText, session.formattedDate)
                        copyToClipboard("PocketHUD Dialogue ${session.formattedDate}", textToCopy)
                        Toast.makeText(this@MainActivity, "Диалог от ${session.formattedDate} скопирован", Toast.LENGTH_SHORT).show()
                    }
                }
                actionsRow.addView(copyBtn)

                val shareBtn = TextView(this).apply {
                    text = "📤"
                    textSize = 11f
                    setTextColor(0xFF8AB4F8.toInt())
                    background = createRoundedDrawable(0xFF1F2B42.toInt(), cornerRadiusDp = 6f)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(6), 0, 0, 0) }
                    layoutParams = params
                    isClickable = true
                    setOnClickListener {
                        val turns = DialogueHistoryManager.loadSessionTurns(session.file)
                        if (turns.isNotEmpty()) {
                            shareDialogue(turns, session.formattedDate)
                        } else {
                            Toast.makeText(this@MainActivity, "Сессия пуста", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                actionsRow.addView(shareBtn)

                val deleteBtn = TextView(this).apply {
                    text = "🗑️"
                    textSize = 11f
                    setTextColor(0xFFF28B82.toInt())
                    background = createRoundedDrawable(0xFF3C2020.toInt(), cornerRadiusDp = 6f)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(6), 0, 0, 0) }
                    layoutParams = params
                    isClickable = true
                    setOnClickListener {
                        DialogueHistoryManager.deleteSession(session.file)
                        Toast.makeText(this@MainActivity, "Сессия удалена", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        showSavedDialoguesDialog() // Обновляем список
                    }
                }
                actionsRow.addView(deleteBtn)

                itemCard.addView(actionsRow)
                listContainer.addView(itemCard)
            }

            scroll.addView(listContainer)
            root.addView(scroll)
        }

        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        dialog.show()
    }

    override fun onDestroy() {
        PocketService.removeListener(serviceListener)
        PocketService.onTelemetryUpdated = null
        PocketService.onInputTextReceived = null
        PocketService.onDialogueTurnUpdated = null
        PocketService.onMuteStateChanged = null
        PocketService.onMicSourceChanged = null
        PocketService.onEngineChanged = null
        PocketService.onRestModelChanged = null
        PocketService.onTtsModeChanged = null
        super.onDestroy()
    }
}
