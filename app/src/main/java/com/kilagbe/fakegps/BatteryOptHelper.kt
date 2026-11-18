package com.kilagbe.fakegps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryOptHelper {

    /** True if the OS can freely kill this app's background work (services, alarms). */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Opens the system dialog asking the user to exempt this app from battery optimization.
     *  This is the standard Android API for this (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) —
     *  it shows a native OS confirmation dialog, not something we can silently grant ourselves. */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openBatterySettingsFallback(context)
        }
    }

    /** Fallback for OEMs (MIUI etc.) where the standard dialog doesn't behave as expected —
     *  opens the app's own battery settings page instead. */
    fun openBatterySettingsFallback(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (_: Exception) { }
    }
}
