package com.kilagbe.fakegps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

private fun Location.isMockCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock
    else @Suppress("DEPRECATION") isFromMockProvider

@SuppressLint("MissingPermission")
fun fetchBestLocation(context: Context, onResult: (Location) -> Unit) {
    val appCtx = context.applicationContext
    var delivered = false
    fun deliver(loc: Location) {
        if (delivered) return
        delivered = true
        onResult(loc)
    }

    fun legacyFetch() {
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        val nowNs = SystemClock.elapsedRealtimeNanos()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc: Location = (try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null }) ?: continue
            if (loc.isMockCompat()) continue
            if (nowNs - loc.elapsedRealtimeNanos > 60_000_000_000L) continue
            val b = best
            if (b == null || loc.accuracy < b.accuracy) best = loc
        }
        val found = best
        if (found != null) {
            deliver(found)
            return
        }

        val provider = try {
            when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> return
            }
        } catch (_: Exception) {
            return
        }

        val handler = Handler(Looper.getMainLooper())
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.isMockCompat()) return
                handler.removeCallbacksAndMessages(null)
                try { lm.removeUpdates(this) } catch (_: Exception) { }
                deliver(location)
            }
        }
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            handler.postDelayed({
                try { lm.removeUpdates(listener) } catch (_: Exception) { }
            }, 20_000L)
        } catch (_: Exception) { }
    }

    try {
        val client = LocationServices.getFusedLocationProviderClient(appCtx)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(10_000L)
            .setDurationMillis(15_000L)
            .build()
        client.getCurrentLocation(request, null)
            .addOnSuccessListener { loc ->
                if (loc != null && !loc.isMockCompat()) deliver(loc) else legacyFetch()
            }
            .addOnFailureListener { legacyFetch() }
    } catch (_: Throwable) {
        legacyFetch()
    }
}
