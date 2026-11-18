package com.kilagbe.fakegps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = LocationRepository(appContext)

                if (repo.bubbleEnabledFlow.first() && Settings.canDrawOverlays(appContext)) {
                    try {
                        ContextCompat.startForegroundService(
                            appContext, Intent(appContext, FloatingBubbleService::class.java)
                        )
                    } catch (_: Exception) { }
                }

                val (wasActive, lat, lng) = repo.activeStateFlow.first()
                if (!wasActive) return@launch
                val name = repo.activeNameFlow.first().ifBlank { "কাস্টম" }

                if (!repo.autoStartFlow.first()) {
                    repo.setActive(false, lat, lng, name)
                    return@launch
                }

                val (cycleOn, minutes) = repo.autoCycleFlow.first()
                try {
                    val serviceIntent = Intent(appContext, MockLocationService::class.java)
                    if (cycleOn) {
                        serviceIntent.action = MockLocationService.ACTION_START_CYCLE
                        serviceIntent.putExtra(MockLocationService.EXTRA_INTERVAL_MINUTES, minutes)
                    } else {
                        serviceIntent.action = MockLocationService.ACTION_START
                        serviceIntent.putExtra(MockLocationService.EXTRA_LAT, lat)
                        serviceIntent.putExtra(MockLocationService.EXTRA_LNG, lng)
                        serviceIntent.putExtra(MockLocationService.EXTRA_NAME, name)
                    }
                    ContextCompat.startForegroundService(appContext, serviceIntent)
                } catch (_: Exception) {
                    repo.setActive(false, lat, lng, name)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
