package com.kilagbe.fakegps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "fake_gps_channel"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Fake GPS Status", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "লোকেশন সক্রিয় থাকা অবস্থায় কুইক-সুইচ নোটিফিকেশন"
                lightColor = Color.parseColor("#0D9488")
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun build(
        context: Context,
        active: Boolean,
        activeName: String,
        saved: List<SavedLocation> = emptyList()
    ): android.app.Notification {
        ensureChannel(context)

        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Fake GPS")
            .setContentText(if (active) "সক্রিয় — $activeName" else "বন্ধ আছে")
            .setContentIntent(openAppPending)
            .setOngoing(active)
            .setOnlyAlertOnce(true)
            .setColor(Color.parseColor("#0D9488"))
            .setPriority(NotificationCompat.PRIORITY_LOW)

        saved.take(2).forEachIndexed { index, loc ->
            val switchIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = MockLocationService.ACTION_SWITCH
                putExtra(MockLocationService.EXTRA_LAT, loc.lat)
                putExtra(MockLocationService.EXTRA_LNG, loc.lng)
                putExtra(MockLocationService.EXTRA_NAME, loc.name)
            }
            val switchPending = PendingIntent.getBroadcast(
                context, 100 + index, switchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, loc.name, switchPending)
        }

        val toggleIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = if (active) MockLocationService.ACTION_STOP else MockLocationService.ACTION_START
        }
        val togglePending = PendingIntent.getBroadcast(
            context, 999, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(0, if (active) "বন্ধ করুন" else "চালু করুন", togglePending)

        return builder.build()
    }
}
