package com.kilagbe.fakegps

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class FakeGpsWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_SWITCH = "com.kilagbe.fakegps.widget.SWITCH"
        const val ACTION_WIDGET_STOP = "com.kilagbe.fakegps.widget.STOP"

        fun updateWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, FakeGpsWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                val provider = FakeGpsWidgetProvider()
                for (id in ids) {
                    provider.updateOne(context, manager, id)
                }
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateOne(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_SWITCH -> {
                val lat = intent.getDoubleExtra(MockLocationService.EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(MockLocationService.EXTRA_LNG, 0.0)
                val name = intent.getStringExtra(MockLocationService.EXTRA_NAME) ?: "কাস্টম"
                startMock(context, lat, lng, name)
                updateWidgets(context)
            }
            ACTION_WIDGET_STOP -> {
                stopMock(context)
                updateWidgets(context)
            }
        }
    }

    private fun updateOne(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val repo = LocationRepository(context)
        val saved = runBlocking { repo.getSavedLocations() }
        val (active, activeLat, activeLng) = runBlocking { repo.activeStateFlow.first() }

        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        views.removeAllViews(R.id.widget_locations_container)

        for (loc in saved) {
            val itemView = RemoteViews(context.packageName, R.layout.widget_location_item)
            val isActive = active && kotlin.math.abs(loc.lat - activeLat) < 0.00001 &&
                kotlin.math.abs(loc.lng - activeLng) < 0.00001

            itemView.setTextViewText(R.id.location_item_button, "📍 " + loc.name)
            itemView.setInt(
                R.id.location_item_button,
                "setBackgroundResource",
                if (isActive) R.drawable.widget_item_bg_active else R.drawable.widget_item_bg
            )
            itemView.setTextColor(
                R.id.location_item_button,
                if (isActive) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt()
            )

            val switchIntent = Intent(context, FakeGpsWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_SWITCH
                putExtra(MockLocationService.EXTRA_LAT, loc.lat)
                putExtra(MockLocationService.EXTRA_LNG, loc.lng)
                putExtra(MockLocationService.EXTRA_NAME, loc.name)
            }
            val switchPending = PendingIntent.getBroadcast(
                context, loc.name.hashCode(), switchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            itemView.setOnClickPendingIntent(R.id.location_item_button, switchPending)

            views.addView(R.id.widget_locations_container, itemView)
        }

        val stopIntent = Intent(context, FakeGpsWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_STOP
        }
        val stopPending = PendingIntent.getBroadcast(
            context, 9999, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_stop_button, stopPending)

        manager.updateAppWidget(widgetId, views)
    }
}
