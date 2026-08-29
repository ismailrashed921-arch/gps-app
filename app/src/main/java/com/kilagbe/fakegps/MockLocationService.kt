package com.kilagbe.fakegps

import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

        private val PROVIDERS: List<String>
            get() {
                val list = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    list.add(LocationManager.FUSED_PROVIDER)
                } else {
                    list.add("fused")
                }
                return list
            }
    }

    private var job: Job? = null
    private var cycleJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var currentLat = 23.8103
    private var currentLng = 90.4125
    private var currentName: String = "কাস্টম"
    private var jitterEnabled = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var cyclingActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_SWITCH -> {
                cyclingActive = false
                cycleJob?.cancel()
                currentLat = intent.getDoubleExtra(EXTRA_LAT, currentLat)
                currentLng = intent.getDoubleExtra(EXTRA_LNG, currentLng)
                currentName = intent.getStringExtra(EXTRA_NAME) ?: currentName
                startForeground(NOTIF_ID, NotificationHelper.build(this, true, currentName))
                acquireWakeLock()
                startFeeding()
                val lat = currentLat; val lng = currentLng; val name = currentName
                scope.launch {
                    LocationRepository(applicationContext).setActive(true, lat, lng, name)
                }
            }
            ACTION_START_CYCLE -> {
                val minutes = intent.getIntExtra(EXTRA_INTERVAL_MINUTES, 10)
                startForeground(NOTIF_ID, NotificationHelper.build(this, true, currentName))
                acquireWakeLock()
                startCycling(minutes)
            }
            ACTION_STOP -> {
                cyclingActive = false
                cycleJob?.cancel()
                stopFeeding()
                releaseWakeLock()
                val lat = currentLat; val lng = currentLng; val name = currentName
                scope.launch {
                    LocationRepository(applicationContext).apply {
                        setActive(false, lat, lng, name)
                        setAutoCycle(false, 10)
                    }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startCycling(minutes: Int) {
        cyclingActive = true
        val repo = LocationRepository(applicationContext)

        cycleJob?.cancel()
        cycleJob = scope.launch {
            repo.setAutoCycle(true, minutes)
            var index = 0
            while (cyclingActive) {
                // Uses the user's selected Start→End location order if set,
                // otherwise falls back to all saved locations.
                val cycleList = repo.getCycleLocations()
                if (cycleList.isEmpty()) {
                    delay(5000)
                    continue
                }
                val loc = cycleList[index % cycleList.size]
                currentLat = loc.lat
                currentLng = loc.lng
                currentName = loc.name
                startFeeding()
                repo.setActive(true, currentLat, currentLng, currentName)
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

    private fun startFeeding() {
        job?.cancel()
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager

        for (provider in PROVIDERS) {
            try {
                lm.addTestProvider(
                    provider,
                    false, false, false, false,
                    true, true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
            } catch (_: Exception) { }
            try {
                lm.setTestProviderEnabled(provider, true)
            } catch (_: Exception) { }
        }

        job = scope.launch {
            var tick = 0
            while (true) {
                pushLocation(lm)
                tick++
                if (tick % 20 == 0) {
                    NotificationManagerCompat.from(this@MockLocationService)
                        .notify(NOTIF_ID, NotificationHelper.build(this@MockLocationService, true, currentName))
                }
                delay(PUSH_INTERVAL_MS)
            }
        }
    }

    private fun pushLocation(lm: LocationManager) {
        val lat = if (jitterEnabled) currentLat + Random.nextDouble(-0.00005, 0.00005) else currentLat
        val lng = if (jitterEnabled) currentLng + Random.nextDouble(-0.00005, 0.00005) else currentLng

        for (provider in PROVIDERS) {
            val location = Location(provider).apply {
                latitude = lat
                longitude = lng
                altitude = 0.0
                accuracy = 1f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearingAccuracyDegrees = 0.1f
                    verticalAccuracyMeters = 0.1f
                    speedAccuracyMetersPerSecond = 0.01f
                }
            }
            try {
                lm.setTestProviderLocation(provider, location)
            } catch (_: Exception) { }
        }
    }

    private fun stopFeeding() {
        job?.cancel()
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        for (provider in PROVIDERS) {
            try {
                lm.removeTestProvider(provider)
            } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        cyclingActive = false
        cycleJob?.cancel()
        stopFeeding()
        releaseWakeLock()
        super.onDestroy()
    }
}
