package com.aleksandr.pockethud

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var toggleBtn: Button
    private lateinit var statusText: TextView
    private lateinit var subtitlesText: TextView
    private lateinit var scrollView: ScrollView

    private val prefsName = "pocket_hud_prefs"
    private val keyApiKey = "api_key"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createLayout())

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        apiKeyInput.setText(prefs.getString(keyApiKey, ""))

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
                }
                ContextCompat.startForegroundService(this, intent)
                PocketService.isServiceActive = true
                updateUi()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupServiceCallbacks()
        updateUi()
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

        PocketService.onTextReceived = { text ->
            runOnUiThread {
                if (subtitlesText.text.startsWith("Ожидание") || subtitlesText.text.startsWith("Здесь")) {
                    subtitlesText.text = text
                } else {
                    subtitlesText.append(" " + text)
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
            setPadding(40, 48, 40, 40)
            setBackgroundColor(0xFF0A0A0A.toInt())
        }

        val title = TextView(this).apply {
            text = "PocketHUD — Ушной суфлёр"
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
        ).apply { setMargins(0, 24, 0, 16) }
        layout.addView(apiKeyInput, apiParams)

        toggleBtn = Button(this).apply {
            text = "ЗАПУСТИТЬ В ФОНЕ"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF00AA55.toInt())
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 28, 0, 28)
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 8, 0, 16) }
        layout.addView(toggleBtn, btnParams)

        statusText = TextView(this).apply {
            text = "⚪ Суфлёр остановлен."
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
        }
        layout.addView(statusText)

        val subTitle = TextView(this).apply {
            text = "Живые субтитры (HUD):"
            textSize = 13f
            setTextColor(0xFF777777.toInt())
            setPadding(0, 24, 0, 8)
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
            setTextColor(0xFF00FF99.toInt()) // Высококонтрастный зелёный на чёрном OLED
            setTypeface(null, Typeface.BOLD)
            setLineSpacing(12f, 1.15f)
        }
        scrollView.addView(subtitlesText)
        layout.addView(scrollView)

        return layout
    }
}
