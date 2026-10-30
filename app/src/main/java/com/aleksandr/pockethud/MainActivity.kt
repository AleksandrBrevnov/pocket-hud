package com.aleksandr.pockethud

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var toggleBtn: Button
    private lateinit var statusText: TextView

    private val prefsName = "pocket_hud_prefs"
    private val keyApiKey = "api_key"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createLayout())

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        apiKeyInput.setText(prefs.getString(keyApiKey, ""))

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
                val intent = Intent(this, PocketService::class.java).apply {
                    putExtra(PocketService.EXTRA_API_KEY, key)
                }
                ContextCompat.startForegroundService(this, intent)
                PocketService.isServiceActive = true
                updateUi()
                Toast.makeText(this, "Суфлёр запущен! Можно блокировать экран", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun updateUi() {
        if (PocketService.isServiceActive) {
            toggleBtn.text = "ОСТАНОВИТЬ"
            toggleBtn.setBackgroundColor(0xFFFF3344.toInt())
            statusText.text = "🟢 Суфлёр активен в фоне.\nЭкран можно выключить и положить телефон в карман."
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

    private fun createLayout(): android.view.View {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
            setBackgroundColor(0xFF121212.toInt())
        }

        val title = TextView(this).apply {
            text = "PocketHUD — Карманный суфлёр"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        layout.addView(title)

        val desc = TextView(this).apply {
            text = "Работает в фоне с выключенным экраном через Gemini Live API WebSocket (задержка ~350 мс)."
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 16, 0, 32)
        }
        layout.addView(desc)

        apiKeyInput = EditText(this).apply {
            hint = "Google AI Studio API Key (AIzaSy...)"
            setHintTextColor(0xFF666666.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF222222.toInt())
            setPadding(32, 24, 32, 24)
        }
        layout.addView(apiKeyInput)

        toggleBtn = Button(this).apply {
            text = "ЗАПУСТИТЬ В ФОНЕ"
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 32, 0, 32)
        }
        val btnParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 48, 0, 32) }
        layout.addView(toggleBtn, btnParams)

        statusText = TextView(this).apply {
            text = "⚪ Суфлёр остановлен."
            textSize = 15f
            setTextColor(0xFFCCCCCC.toInt())
            setLineSpacing(8f, 1f)
        }
        layout.addView(statusText)

        return layout
    }
}

