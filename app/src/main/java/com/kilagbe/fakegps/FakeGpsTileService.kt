package com.kilagbe.fakegps

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class FakeGpsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            showDialog(buildDialog())
        } else {
            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivityAndCollapse(intent)
        }
    }

    private fun buildDialog(): Dialog {
        val ctx = this
        val repo = LocationRepository(applicationContext)
        val saved = runBlocking { repo.getSavedLocations() }
        val (active, activeLat, activeLng) = runBlocking { repo.activeStateFlow.first() }

        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
            }
        }

        val title = TextView(ctx).apply {
            text = "লোকেশন বেছে নিন"
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(4), dp(14), dp(10))
        }
        root.addView(title)

        val scroll = ScrollView(ctx)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        if (saved.isEmpty()) {
            val empty = TextView(ctx).apply {
                text = "কোনো লোকেশন সেভ করা নেই"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 13f
                setPadding(dp(14), dp(10), dp(14), dp(14))
            }
            list.addView(empty)
        } else {
            for (loc in saved) {
                val isActive = active &&
                    kotlin.math.abs(loc.lat - activeLat) < 0.00001 &&
                    kotlin.math.abs(loc.lng - activeLng) < 0.00001

                val row = TextView(ctx).apply {
                    text = "📍  ${loc.name}" + if (isActive) "  ✓" else ""
                    setTextColor(if (isActive) Color.parseColor("#0D9488") else Color.parseColor("#0F172A"))
                    textSize = 13f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        startMock(ctx, loc.lat, loc.lng, loc.name)
                        dialog.dismiss()
                        refreshTile()
                    }
                }
                list.addView(row)
            }
        }
        scroll.addView(list)
        root.addView(scroll)

        val stopRow = TextView(ctx).apply {
            text = "⏻  বন্ধ করুন"
            setTextColor(Color.parseColor("#DC2626"))
            textSize = 13f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                stopMock(ctx)
                dialog.dismiss()
                refreshTile()
            }
        }
        root.addView(stopRow)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
    }

    private fun refreshTile() {
        val repo = LocationRepository(applicationContext)
        val (active, _, _) = runBlocking { repo.activeStateFlow.first() }
        qsTile?.let { t ->
            t.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            t.label = "Fake GPS"
            t.updateTile()
        }
    }
}
