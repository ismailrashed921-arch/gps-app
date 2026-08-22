package com.kilagbe.fakegps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingBubbleService : Service() {

    companion object {
        private const val NOTIF_ID = 2002
        private const val CHANNEL_ID = "floating_bubble_channel"
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var collectJob: Job? = null
    private lateinit var windowManager: WindowManager
    private var bubbleView: FrameLayout? = null
    private var isActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "ফ্লোটিং বাটন", NotificationManager.IMPORTANCE_MIN
            )
            manager.createNotificationChannel(channel)
        }
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("ফ্লোটিং বাটন চালু আছে")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun addBubble() {
        val size = (48 * resources.displayMetrics.density).toInt()
        val iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_mylocation)
            setColorFilter(Color.WHITE)
            val pad = size / 4
            setPadding(pad, pad, pad, pad)
        }
        val bubble = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@FloatingBubbleService, R.drawable.bubble_bg_off)
            addView(iconView, FrameLayout.LayoutParams(size, size))
        }
        bubbleView = bubble

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            size, size,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try { windowManager.updateViewLayout(bubble, params) } catch (_: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleMock()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(bubble, params)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun toggleMock() {
        scope.launch {
            val repo = LocationRepository(applicationContext)
            val state = repo.activeStateFlow.first()
            if (state.first) {
                stopMock(applicationContext)
            } else {
                val prefs = applicationContext.dataStore.data.first()
                val name = prefs[PrefKeys.ACTIVE_NAME]?.takeIf { it.isNotBlank() } ?: "কাস্টম"
                startMock(applicationContext, state.second, state.third, name)
            }
        }
    }

    private fun observeState() {
        collectJob = scope.launch {
            LocationRepository(applicationContext).activeStateFlow.collect { state ->
                isActive = state.first
                updateBubbleAppearance()
            }
        }
    }

    private fun updateBubbleAppearance() {
        bubbleView?.background = ContextCompat.getDrawable(
            this, if (isActive) R.drawable.bubble_bg_on else R.drawable.bubble_bg_off
        )
    }

    override fun onDestroy() {
        collectJob?.cancel()
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        super.onDestroy()
    }
}
