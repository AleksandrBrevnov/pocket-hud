package com.aleksandr.pockethud

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Floating Quick Overlay Service (interfaces.md §2.3)
 *
 * Владеет: Отображением компактной плавающей кнопки/виджета поверх всех окон (TYPE_APPLICATION_OVERLAY).
 * Функциональность:
 * - Запуск/остановка распознавания и записи в 1 тап.
 * - Плавный drag-and-drop виджета по экрану с сохранением координат.
 * - Индикация текущего статуса (слушает/переводит/пауза) и превью последней реплики перевода.
 * - Быстрый возврат в полноэкранное приложение PocketHUD.
 */
class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private lateinit var params: WindowManager.LayoutParams

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var previewText: TextView
    private lateinit var toggleMicBtn: Button
    private lateinit var openAppBtn: Button
    private lateinit var closeOverlayBtn: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchStartTime = 0L

    companion object {
        private const val TAG = "FloatingWidgetService"

        const val ACTION_SHOW = "com.aleksandr.pockethud.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.aleksandr.pockethud.action.HIDE_OVERLAY"

        @Volatile
        var isOverlayActive = false
            private set

        private var currentInstance: FloatingWidgetService? = null

        /**
         * Публичный интерфейс вызова по interfaces.md §2.3:
         * fun showOverlay(context: Context)
         * fun hideOverlay()
         * fun updateState(isRecording: Boolean, lastTranslationPreview: String)
         */
        fun showOverlay(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Отсутствует разрешение SYSTEM_ALERT_WINDOW (canDrawOverlays == false)")
                return
            }
            val intent = Intent(context, FloatingWidgetService::class.java).apply {
                action = ACTION_SHOW
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка запуска FloatingWidgetService: ${e.message}")
            }
        }

        fun hideOverlay(context: Context? = null) {
            val inst = currentInstance
            if (inst != null) {
                inst.stopSelf()
            } else if (context != null) {
                try {
                    val intent = Intent(context, FloatingWidgetService::class.java).apply {
                        action = ACTION_HIDE
                    }
                    context.stopService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка остановки FloatingWidgetService: ${e.message}")
                }
            }
        }

        fun updateState(isRecording: Boolean, lastTranslationPreview: String) {
            currentInstance?.updateStateInternal(isRecording, lastTranslationPreview)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                if (!isOverlayActive && ::overlayView.isInitialized) {
                    try {
                        windowManager.addView(overlayView, params)
                        isOverlayActive = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка вызова WindowManager.addView в onStartCommand: ${e.message}")
                        stopSelf()
                    }
                }
            }
            ACTION_HIDE -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        isOverlayActive = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        initOverlayView()
        setupListeners()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

    private fun createRoundedBg(bgColor: Int, strokeColor: Int = 0, strokeDp: Int = 0, radiusDp: Float = 16f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(bgColor)
            if (strokeDp > 0 && strokeColor != 0) {
                setStroke(dp(strokeDp), strokeColor)
            }
        }
    }

    private fun initOverlayView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            dp(260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(140)
        }

        // Корневой плавающий контейнер
        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedBg(0xF0131722.toInt(), 0xFF2D3748.toInt(), 1, 16f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dpF(10f)
        }

        // 1. Верхняя строка: Индикатор статуса + Заголовок + Кнопка закрытия
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(6)) }
            layoutParams = lp
        }

        statusDot = View(this).apply {
            background = createRoundedBg(0xFF10B981.toInt(), radiusDp = 8f)
            val lp = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                setMargins(0, 0, dp(6), 0)
            }
            layoutParams = lp
        }
        headerRow.addView(statusDot)

        statusText = TextView(this).apply {
            text = if (PocketService.isServiceActive) "🟢 Слушаю..." else "⚪ PocketHUD"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        headerRow.addView(statusText)

        closeOverlayBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            isClickable = true
            setOnClickListener {
                stopSelf()
            }
        }
        headerRow.addView(closeOverlayBtn)
        overlayView.addView(headerRow)

        // 2. Превью перевода (последняя реплика / перевод речи)
        previewText = TextView(this).apply {
            text = "Ожидание итальянской речи..."
            textSize = 12.5f
            setTextColor(0xFFE2E8F0.toInt())
            maxLines = 3
            background = createRoundedBg(0xFF1E293B.toInt(), radiusDp = 8f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
            layoutParams = lp
        }
        overlayView.addView(previewText)

        // 3. Нижняя панель действий (1 тап: Старт/Стоп + Вход в приложение)
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        toggleMicBtn = Button(this).apply {
            text = if (PocketService.isServiceActive) "⏹️ СТОП" else "🎙️ СТАРТ"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            background = createRoundedBg(
                if (PocketService.isServiceActive) 0xFFDC2626.toInt() else 0xFF10B981.toInt(),
                radiusDp = 8f
            )
            val lp = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                setMargins(0, 0, dp(6), 0)
            }
            layoutParams = lp
            setOnClickListener {
                toggleServiceState()
            }
        }
        actionsRow.addView(toggleMicBtn)

        openAppBtn = Button(this).apply {
            text = "📱 HUD"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            background = createRoundedBg(0xFF2563EB.toInt(), radiusDp = 8f)
            val lp = LinearLayout.LayoutParams(dp(70), dp(34))
            layoutParams = lp
            setOnClickListener {
                val intent = Intent(this@FloatingWidgetService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            }
        }
        actionsRow.addView(openAppBtn)
        overlayView.addView(actionsRow)

        // 4. Drag-and-drop перетаскивание виджета
        setupDragAndDrop()

        // Добавление во WindowManager
        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка вызова WindowManager.addView: ${e.message}")
            isOverlayActive = false
            currentInstance = null
            stopSelf()
            return
        }
    }

    private fun setupDragAndDrop() {
        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(overlayView, params)
                    } catch (e: Exception) {
                        Log.w(TAG, "Ошибка updateViewLayout: ${e.message}")
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    val duration = System.currentTimeMillis() - touchStartTime
                    if (dx < dp(8) && dy < dp(8) && duration < 300) {
                        // Клик по свободной области виджета переключает старт/стоп
                        toggleServiceState()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleServiceState() {
        if (PocketService.isServiceActive) {
            val intent = Intent(this, PocketService::class.java)
            stopService(intent)
            PocketService.isServiceActive = false
            updateUiState(false, "⏹️ Суфлёр остановлен")
            Toast.makeText(this, "PocketHUD: Суфлёр остановлен", Toast.LENGTH_SHORT).show()
        } else {
            val prefs = getSharedPreferences("pocket_hud_prefs", Context.MODE_PRIVATE)
            val key = prefs.getString("api_key", "") ?: ""
            if (key.isBlank()) {
                Toast.makeText(this, "Введите API ключ в приложении PocketHUD", Toast.LENGTH_LONG).show()
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
                return
            }

            val intent = Intent(this, PocketService::class.java).apply {
                putExtra(PocketService.EXTRA_API_KEY, key)
                putExtra(PocketService.EXTRA_IS_MUTED, PocketService.isAudioMuted)
                putExtra(PocketService.EXTRA_MIC_SOURCE, PocketService.selectedMicSource.name)
                putExtra(PocketService.EXTRA_TRANSLATION_ENGINE, PocketService.selectedEngine.name)
                putExtra(PocketService.EXTRA_REST_MODEL, PocketService.selectedRestModel)
                putExtra(PocketService.EXTRA_TTS_MODE, PocketService.selectedTtsMode.name)
            }
            try {
                ContextCompat.startForegroundService(this, intent)
                PocketService.isServiceActive = true
                updateUiState(true, "🟢 Слушаю...")
                Toast.makeText(this, "PocketHUD: Запущен перевод в 1 тап", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось запустить Foreground Service (Android 14 FGS): ${e.message}")
                try {
                    val actIntent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(actIntent)
                    Toast.makeText(this, "Откройте PocketHUD для запуска (Android 14)", Toast.LENGTH_SHORT).show()
                } catch (actEx: Exception) {
                    Log.e(TAG, "Не удалось открыть MainActivity: ${actEx.message}")
                }
            }
        }
    }

    private val serviceListener = object : PocketService.PocketServiceListener {
        override fun onStatusChanged(status: String) {
            mainHandler.post {
                val isActive = PocketService.isServiceActive
                updateUiState(isActive, status)
            }
        }

        override fun onTextReceived(text: String) {
            mainHandler.post {
                previewText.text = text
                previewText.setTextColor(0xFF81C995.toInt())
            }
        }
    }

    private fun setupListeners() {
        // Подключаемся к реестру слушателей PocketService без взаимного затирания
        PocketService.addListener(serviceListener)

        val oldTurnCb = PocketService.onDialogueTurnUpdated
        PocketService.onDialogueTurnUpdated = { idx, turn, isNew ->
            oldTurnCb?.invoke(idx, turn, isNew)
            mainHandler.post {
                val preview = when {
                    turn.russianText.isNotBlank() -> turn.russianText
                    turn.italianText.isNotBlank() -> "🇮🇹 ${turn.italianText}"
                    else -> "⏳ Обработка речи..."
                }
                previewText.text = preview
                if (turn.russianText.isNotBlank()) {
                    previewText.setTextColor(0xFF81C995.toInt())
                } else {
                    previewText.setTextColor(0xFF93C5FD.toInt())
                }
            }
        }
    }

    fun updateStateInternal(isRecording: Boolean, lastTranslationPreview: String) {
        mainHandler.post {
            updateUiState(isRecording, if (isRecording) "🟢 Слушаю..." else "⚪ Пауза")
            if (lastTranslationPreview.isNotBlank()) {
                previewText.text = lastTranslationPreview
            }
        }
    }

    private fun updateUiState(isActive: Boolean, status: String) {
        statusText.text = status
        if (isActive) {
            statusDot.background = createRoundedBg(0xFF10B981.toInt(), radiusDp = 8f)
            toggleMicBtn.text = "⏹️ СТОП"
            toggleMicBtn.background = createRoundedBg(0xFFDC2626.toInt(), radiusDp = 8f)
        } else {
            statusDot.background = createRoundedBg(0xFF64748B.toInt(), radiusDp = 8f)
            toggleMicBtn.text = "🎙️ СТАРТ"
            toggleMicBtn.background = createRoundedBg(0xFF10B981.toInt(), radiusDp = 8f)
        }
    }

    override fun onDestroy() {
        PocketService.removeListener(serviceListener)
        isOverlayActive = false
        currentInstance = null
        if (::overlayView.isInitialized) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка удаления overlayView: ${e.message}")
            }
        }
        super.onDestroy()
    }
}
