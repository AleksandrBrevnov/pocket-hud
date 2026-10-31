package com.aleksandr.pockethud

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var toggleBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var statusText: TextView
    private lateinit var telemetryText: TextView
    private lateinit var shareLogBtn: Button
    private lateinit var copySummaryBtn: Button
    private lateinit var subtitlesText: TextView
    private lateinit var scrollView: ScrollView

    private val prefsName = "pocket_hud_prefs"
    private val keyApiKey = "api_key"
    private val keyAudioMuted = "audio_muted"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createLayout())

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        apiKeyInput.setText(prefs.getString(keyApiKey, ""))

        val isMuted = prefs.getBoolean(keyAudioMuted, false)
        PocketService.isAudioMuted = isMuted
        updateMuteButtonUi()

        setupServiceCallbacks()
        updateUi()

        toggleBtn.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isEmpty()) {
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
                statusText.text = "🔵 Запуск сервиса..."
                subtitlesText.text = "Ожидание итальянской речи..."
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
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("PocketHUD Diagnostics", summary)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Сводка скопирована в буфер обмена! Отправьте её ассистенту.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        setupServiceCallbacks()
        updateUi()
        updateMuteButtonUi()
    }

    private fun updateMuteButtonUi() {
        if (PocketService.isAudioMuted) {
            muteBtn.text = "🔇 ЗВУК: ВЫКЛ (Только субтитры)"
            muteBtn.setBackgroundColor(0xFFE65100.toInt())
        } else {
            muteBtn.text = "🔊 ЗВУК: ВКЛ (Голос в наушник)"
            muteBtn.setBackgroundColor(0xFF1976D2.toInt())
        }
    }

    private fun shareLogFile() {
        val logFile = PocketService.getLogFile(this)
        
        // Если файла нет или он пуст, записываем текущую сводку диагностики
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
                clipData = android.content.ClipData.newRawUri("PocketHUD Session Log", uri)
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
                statusText.text = status
                if (status.contains("🟢") || status.contains("🔵")) {
                    toggleBtn.text = "ОСТАНОВИТЬ"
                    toggleBtn.setBackgroundColor(0xFFFF3344.toInt())
                } else if (status.contains("🔴") || status.contains("⚪")) {
                    toggleBtn.text = "ЗАПУСТИТЬ В ФОНЕ"
                    toggleBtn.setBackgroundColor(0xFF00AA55.toInt())
                }
            }
        }

        PocketService.onTelemetryUpdated = { micAmp, chunks, responses ->
            runOnUiThread {
                telemetryText.text = "🎙️ Мик: $micAmp% | Чанков: $chunks | Ответов: $responses"
            }
        }

        PocketService.onTextReceived = { text ->
            runOnUiThread {
                if (subtitlesText.text.startsWith("Ожидание") || subtitlesText.text.startsWith("Здесь")) {
                    subtitlesText.text = text
                } else {
                    if (text.startsWith(" ") || subtitlesText.text.endsWith(" ")) {
                        subtitlesText.append(text)
                    } else {
                        subtitlesText.append(" $text")
                    }
                }
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun updateUi() {
        if (PocketService.isServiceActive) {
            toggleBtn.text = "ОСТАНОВИТЬ"
            toggleBtn.setBackgroundColor(0xFFFF3344.toInt())
        } else {
            toggleBtn.text = "ЗАПУСТИТЬ В ФОНЕ"
            toggleBtn.setBackgroundColor(0xFF00AA55.toInt())
            statusText.text = "⚪ Суфлёр остановлен."
            telemetryText.text = "🎙️ Мик: 0% | Чанков: 0 | Ответов: 0"
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
                Toast.makeText(this, "Разрешения предоставлены! Нажмите 'ЗАПУСТИТЬ В ФОНЕ'", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Требуется разрешение на запись звука для суфлёра", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createLayout(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 36)
            setBackgroundColor(0xFF0A0A0A.toInt())
        }

        val title = TextView(this).apply {
            text = "PocketHUD — Ушной суфлёр v1.0.13"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
        }
        layout.addView(title)

        apiKeyInput = EditText(this).apply {
            hint = "Google AI Studio API Key"
            setHintTextColor(0xFF666666.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(28, 20, 28, 20)
            textSize = 14f
        }
        val apiParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 20, 0, 14) }
        layout.addView(apiKeyInput, apiParams)

        // Кнопка Старт/Стоп
        toggleBtn = Button(this).apply {
            text = "ЗАПУСТИТЬ В ФОНЕ"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF00AA55.toInt())
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 26, 0, 26)
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 4, 0, 10) }
        layout.addView(toggleBtn, btnParams)

        // Кнопка Mute звука
        muteBtn = Button(this).apply {
            text = "🔊 ЗВУК: ВКЛ (Голос в наушник)"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1976D2.toInt())
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 22, 0, 22)
        }
        val muteParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) }
        layout.addView(muteBtn, muteParams)

        statusText = TextView(this).apply {
            text = "⚪ Суфлёр остановлен."
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
        }
        layout.addView(statusText)

        telemetryText = TextView(this).apply {
            text = "🎙️ Мик: 0% | Чанков: 0 | Ответов: 0"
            textSize = 13f
            setTextColor(0xFF00E5FF.toInt())
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 10, 0, 8)
        }
        layout.addView(telemetryText)

        // Горизонтальный контейнер кнопок экспорта логов и сводки
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        copySummaryBtn = Button(this).apply {
            text = "📋 СКОПИРОВАТЬ СВОДКУ"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF333333.toInt())
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(8, 16, 8, 16)
        }
        val copySummaryParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply { setMargins(0, 0, 8, 0) }
        actionsRow.addView(copySummaryBtn, copySummaryParams)

        shareLogBtn = Button(this).apply {
            text = "📤 ПОДЕЛИТЬСЯ ЛОГОМ (ФАЙЛ)"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2E7D32.toInt())
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(8, 16, 8, 16)
        }
        val shareLogParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply { setMargins(8, 0, 0, 0) }
        actionsRow.addView(shareLogBtn, shareLogParams)

        val actionsParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 4, 0, 12) }
        layout.addView(actionsRow, actionsParams)

        val subTitle = TextView(this).apply {
            text = "Живые субтитры (HUD & Xiaomi Watch 2 Wear OS 5):"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 8, 0, 8)
        }
        layout.addView(subTitle)

        scrollView = ScrollView(this).apply {
            val sParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutParams = sParams
            setBackgroundColor(0xFF000000.toInt())
            setPadding(24, 24, 24, 24)
        }

        subtitlesText = TextView(this).apply {
            text = "Здесь в реальном времени будет появляться русский текст перевода..."
            textSize = 18f
            setTextColor(0xFF00FF99.toInt())
            setTypeface(null, Typeface.BOLD)
            setLineSpacing(12f, 1.15f)
        }
        scrollView.addView(subtitlesText)
        layout.addView(scrollView)

        return layout
    }
}
