package com.kilagbe.fakegps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
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
    private lateinit var repo: LocationRepository

    private var container: LinearLayout? = null
    private var menuView: LinearLayout? = null
    private var menuLocList: LinearLayout? = null
    private var menuStatusText: TextView? = null
    private var bubbleCircle: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private var menuOpen = false
    private var isActive = false
    private var savedLocations: List<SavedLocation> = emptyList()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        repo = LocationRepository(applicationContext)
        addOverlay()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "ব্যাকগ্রাউন্ড সার্ভিস", NotificationManager.IMPORTANCE_MIN
            )
            manager.createNotificationChannel(channel)
        }
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("সার্ভিস চালু আছে")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun roundedBg(colorHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(colorHex))
        }
    }

    private fun ovalBg(colorHex: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(colorHex))
            setStroke(dp(2), Color.WHITE)
        }
    }

    private fun addOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        // ---- menu (hidden initially) ----
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#FFFFFF", 16)
            elevation = dp(10).toFloat()
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        val titleTv = TextView(this).apply {
            text = "সেভ করা লোকেশন"
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val statusTv = TextView(this).apply {
            text = "লোড হচ্ছে..."
            setTextColor(Color.parseColor("#0D9488"))
            textSize = 10.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        }
        menuStatusText = statusTv
        header.addView(titleTv)
        header.addView(statusTv)
        menu.addView(header)
        menu.addView(divider())

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230)
            )
        }
        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menuLocList = listContainer
        scroll.addView(listContainer)
        menu.addView(scroll)
        menu.addView(divider())

        // single full-width stop button
        val stopBtn = TextView(this).apply {
            text = "✕  বন্ধ করুন"
            setTextColor(Color.parseColor("#DC2626"))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                scope.launch { stopMock(applicationContext) }
                closeMenu()
            }
        }
        menu.addView(stopBtn)

        menuView = menu
        root.addView(menu)

        val bubbleSize = dp(56)
        val icon = ImageView(this).apply {
            val pad = bubbleSize / 4
            setPadding(pad, pad, pad, pad)
        }
        val bubble = FrameLayout(this).apply {
            background = ovalBg("#94A3B8")
            layoutParams = LinearLayout.LayoutParams(bubbleSize, bubbleSize)
            addView(icon, FrameLayout.LayoutParams(bubbleSize, bubbleSize))
        }
        bubbleCircle = bubble
        root.addView(bubble)
        container = root

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(300)
        }
        params = p

        // close menu when tapping anywhere outside the overlay (other apps, screen, etc.)
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                if (menuOpen) closeMenu()
            }
            false
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x
                    initialY = p.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    p.x = initialX + dx
                    p.y = initialY + dy
                    try { windowManager.updateViewLayout(root, p) } catch (_: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleMenu()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(root, p)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(Color.parseColor("#F1F5F4"))
    }

    private fun toggleMenu() {
        if (menuOpen) closeMenu() else openMenu()
    }

    private fun openMenu() {
        menuOpen = true
        refreshLocationRows()
        menuView?.visibility = View.VISIBLE
    }

    private fun closeMenu() {
        menuOpen = false
        menuView?.visibility = View.GONE
    }

    private fun refreshLocationRows() {
        scope.launch {
            savedLocations = repo.getSavedLocations()
            val activeState = repo.activeStateFlow.first()
            buildRows(activeState)
            updateStatusText(activeState)
        }
    }

    private fun buildRows(activeState: Triple<Boolean, Double, Double>) {
        val list = menuLocList ?: return
        list.removeAllViews()
        if (savedLocations.isEmpty()) {
            val empty = TextView(this).apply {
                text = "কোনো লোকেশন সেভ করা নাই"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11.5f
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            list.addView(empty)
            return
        }
        savedLocations.forEach { loc ->
            val isThisActive = activeState.first &&
                abs(loc.lat - activeState.second) < 0.00001 &&
                abs(loc.lng - activeState.third) < 0.00001

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                isClickable = true
                isFocusable = true
            }
            val pin = FrameLayout(this).apply {
                background = ovalBg(if (isThisActive) "#14B8A6" else "#E6FBF7")
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(10) }
            }
            val nameTv = TextView(this).apply {
                text = loc.name
                setTextColor(if (isThisActive) Color.parseColor("#0D9488") else Color.parseColor("#0F172A"))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(pin)
            row.addView(nameTv)
            if (isThisActive) {
                val check = TextView(this).apply {
                    text = "✓"
                    setTextColor(Color.parseColor("#14B8A6"))
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                row.addView(check)
            }
            row.setOnClickListener {
                startMock(applicationContext, loc.lat, loc.lng, loc.name)
                closeMenu()
            }
            list.addView(row)
        }
    }

    private fun observeState() {
        collectJob = scope.launch {
            repo.activeStateFlow.collect { state ->
                isActive = state.first
                updateBubbleAppearance()
                if (menuOpen) {
                    savedLocations = repo.getSavedLocations()
                    buildRows(state)
                    updateStatusText(state)
                }
            }
        }
    }

    private fun updateStatusText(state: Triple<Boolean, Double, Double>) {
        val activeLoc = savedLocations.firstOrNull {
            abs(it.lat - state.second) < 0.00001 && abs(it.lng - state.third) < 0.00001
        }
        menuStatusText?.text = if (state.first) {
            "সক্রিয়" + (activeLoc?.let { " — ${it.name}" } ?: "")
        } else {
            "বন্ধ আছে"
        }
        menuStatusText?.setTextColor(
            Color.parseColor(if (state.first) "#0D9488" else "#64748B")
        )
    }

    private fun updateBubbleAppearance() {
        bubbleCircle?.background = ovalBg(if (isActive) "#14B8A6" else "#94A3B8")
    }

    override fun onDestroy() {
        collectJob?.cancel()
        container?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        super.onDestroy()
    }
}
