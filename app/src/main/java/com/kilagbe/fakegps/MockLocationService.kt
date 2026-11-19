package com.kilagbe.fakegps

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "com.kilagbe.fakegps.action.START"
        const val ACTION_SWITCH = "com.kilagbe.fakegps.action.SWITCH"
        const val ACTION_START_CYCLE = "com.kilagbe.fakegps.action.START_CYCLE"
        const val ACTION_STOP = "com.kilagbe.fakegps.action.STOP"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_INTERVAL_MINUTES = "extra_interval_minutes"
        const val NOTIF_ID = 1001

        private const val PUSH_INTERVAL_MS = 250L
        private const val MOCK_ACCURACY_M = 3f
        private const val WARM_UP_MS = 15_000L

        private const val MSG_MOCK_OFF =
            "Mock Location চালু নেই — Developer Options → Select mock location app থেকে Fake GPS বেছে নিন"
        private const val MSG_FG_FAIL =
            "ব্যাকগ্রাউন্ডে সার্ভিস চালু করা যায়নি — অ্যাপ খুলে আবার চালু করুন"

        private val PROVIDERS: List<String>
            get() = buildList {
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                add(LocationManager.PASSIVE_PROVIDER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(LocationManager.FUSED_PROVIDER)
                }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var repo: LocationRepository

    private var feedJob: Job? = null
    private var cycleJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var fusedClient: FusedLocationProviderClient? = null

    @Volatile private var fusedMockOn = false
    @Volatile private var cyclingActive = false
    @Volatile private var jitterEnabled = false
    @Volatile private var savedCache: List<SavedLocation> = emptyList()
    @Volatile private var currentLat = 23.8103
    @Volatile private var currentLng = 90.4125
    @Volatile private var currentName: String = "কাস্টম"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = LocationRepository(applicationContext)
        fusedClient = try {
            LocationServices.getFusedLocationProviderClient(this)
        } catch (_: Throwable) {
            null
        }
        scope.launch { repo.jitterFlow.collect { jitterEnabled = it } }
        scope.launch {
            repo.savedLocationsFlow.collect {
                savedCache = it
                if (feedJob?.isActive == true) refreshNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            restoreAfterKill()
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START, ACTION_SWITCH -> {
                cyclingActive = false
                cycleJob?.cancel()
                beginMock(
                    intent.getDoubleExtra(EXTRA_LAT, currentLat),
                    intent.getDoubleExtra(EXTRA_LNG, currentLng),
                    intent.getStringExtra(EXTRA_NAME) ?: currentName,
                    disableCycle = true
                )
            }
            ACTION_START_CYCLE -> {
                val minutes = intent.getIntExtra(EXTRA_INTERVAL_MINUTES, 10).coerceAtLeast(1)
                if (!enterForeground()) {
                    failAndStop(MSG_FG_FAIL)
                    return START_NOT_STICKY
                }
                acquireWakeLock()
                startCycling(minutes)
            }
            ACTION_STOP -> stopEverything()
            else -> {
                enterForeground(active = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun beginMock(lat: Double, lng: Double, name: String, disableCycle: Boolean) {
        currentLat = lat
        currentLng = lng
        currentName = name
        if (!enterForeground()) {
            failAndStop(MSG_FG_FAIL)
            return
        }
        acquireWakeLock()
        if (!startFeeding()) {
            failAndStop(MSG_MOCK_OFF)
            return
        }
        ioScope.launch {
            if (disableCycle) repo.setAutoCycleEnabled(false)
            repo.setActive(true, lat, lng, name)
        }
    }

    private fun stopEverything() {
        cyclingActive = false
        cycleJob?.cancel()
        enterForeground(active = false)
        stopFeeding()
        warmUpRealGps()
        releaseWakeLock()
        val lat = currentLat
        val lng = currentLng
        val name = currentName
        ioScope.launch {
            repo.setActive(false, lat, lng, name)
            repo.setAutoCycleEnabled(false)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failAndStop(message: String) {
        mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
        cyclingActive = false
        cycleJob?.cancel()
        stopFeeding()
        releaseWakeLock()
        val lat = currentLat
        val lng = currentLng
        val name = currentName
        ioScope.launch {
            repo.setActive(false, lat, lng, name)
            repo.setAutoCycleEnabled(false)
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    private fun restoreAfterKill() {
        ioScope.launch {
            val (active, lat, lng) = repo.activeStateFlow.first()
            val name = repo.activeNameFlow.first()
            val (cycleOn, minutes) = repo.autoCycleFlow.first()
            mainHandler.post {
                when {
                    !active -> stopSelf()
                    cycleOn -> {
                        if (enterForeground()) {
                            acquireWakeLock()
                            startCycling(minutes)
                        } else {
                            failAndStop(MSG_FG_FAIL)
                        }
                    }
                    else -> beginMock(lat, lng, name.ifBlank { currentName }, disableCycle = false)
                }
            }
        }
    }

    private fun enterForeground(active: Boolean = true): Boolean {
        return try {
            startForeground(NOTIF_ID, NotificationHelper.build(this, active, currentName, savedCache))
            true
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshNotification() {
        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIF_ID, NotificationHelper.build(this, true, currentName, savedCache))
        } catch (_: Exception) { }
    }

    private fun startCycling(minutes: Int) {
        cyclingActive = true
        cycleJob?.cancel()
        cycleJob = scope.launch {
            repo.setAutoCycle(true, minutes)
            var index = 0
            while (cyclingActive) {
                val cycleList = repo.getCycleLocations()
                if (cycleList.isEmpty()) {
                    delay(5000)
                    continue
                }
                val loc = cycleList[index % cycleList.size]
                currentLat = loc.lat
                currentLng = loc.lng
                currentName = loc.name
                if (!startFeeding()) {
                    mainHandler.post { failAndStop(MSG_MOCK_OFF) }
                    return@launch
                }
                repo.setActive(true, currentLat, currentLng, currentName)
                refreshNotification()
                index++
                delay(minutes * 60_000L)
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "FakeGPS:MockLocationWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startFeeding(): Boolean {
        feedJob?.cancel()
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager

        var denied = false
        for (provider in PROVIDERS) {
            try {
                lm.addTestProvider(
                    provider,
                    false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
            } catch (_: SecurityException) {
                denied = true
            } catch (_: Exception) {
            }
            try {
                lm.setTestProviderEnabled(provider, true)
            } catch (_: SecurityException) {
                denied = true
            } catch (_: Exception) { }
        }
        if (denied) return false

        enableFusedMock()

        feedJob = scope.launch {
            while (true) {
                pushLocation(lm)
                delay(PUSH_INTERVAL_MS)
            }
        }
        return true
    }

    private fun enableFusedMock() {
        val client = fusedClient ?: return
        try {
            client.setMockMode(true)
                .addOnSuccessListener { fusedMockOn = true }
                .addOnFailureListener { fusedMockOn = false }
        } catch (_: Throwable) {
            fusedMockOn = false
        }
    }

    private fun disableFusedMock() {
        fusedMockOn = false
        try { fusedClient?.setMockMode(false) } catch (_: Throwable) { }
    }

    private fun buildLocation(provider: String, lat: Double, lng: Double, timeMs: Long, elapsedNs: Long): Location {
        return Location(provider).apply {
            latitude = lat
            longitude = lng
            altitude = 0.0
            accuracy = MOCK_ACCURACY_M
            speed = 0f
            bearing = 0f
            time = timeMs
            elapsedRealtimeNanos = elapsedNs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 3f
                speedAccuracyMetersPerSecond = 0.5f
                bearingAccuracyDegrees = 30f
            }
        }
    }

    private fun pushLocation(lm: LocationManager) {
        val jitter = jitterEnabled
        val lat = if (jitter) currentLat + Random.nextDouble(-0.00005, 0.00005) else currentLat
        val lng = if (jitter) currentLng + Random.nextDouble(-0.00005, 0.00005) else currentLng
        val timeMs = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos()

        for (provider in PROVIDERS) {
            try {
                lm.setTestProviderLocation(provider, buildLocation(provider, lat, lng, timeMs, elapsedNs))
            } catch (_: Exception) { }
        }
        if (fusedMockOn) {
            try {
                fusedClient?.setMockLocation(buildLocation("fused", lat, lng, timeMs, elapsedNs))
            } catch (_: Throwable) { }
        }
    }

    private fun stopFeeding() {
        feedJob?.cancel()
        feedJob = null
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        for (provider in PROVIDERS) {
            try { lm.removeTestProvider(provider) } catch (_: Exception) { }
        }
        disableFusedMock()
    }

    @SuppressLint("MissingPermission")
    private fun warmUpRealGps() {
        val lm = applicationContext.getSystemService(LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { }
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            mainHandler.postDelayed({
                try { lm.removeUpdates(listener) } catch (_: Exception) { }
            }, WARM_UP_MS)
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        cyclingActive = false
        cycleJob?.cancel()
        stopFeeding()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}
