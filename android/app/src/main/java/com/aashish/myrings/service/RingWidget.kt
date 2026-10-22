package com.aashish.myrings.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.aashish.myrings.MainActivity
import com.aashish.myrings.R
import com.aashish.myrings.data.Analysis
import kotlin.math.roundToInt

/**
 * Home-screen widget: readiness, sleep and ring battery at a glance. The app writes the latest numbers into a small
 * SharedPreferences snapshot after each sync or battery read, and the widget only draws from it, so it never touches
 * Bluetooth or recomputes anything. With several rings it shows the one that was updated last.
 */
class RingWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, render(ctx))
    }

    companion object {
        private const val PREFS = "ring_widget"
        const val LOW_PCT = 20
        const val OK_PCT = 40

        /** "just now", "12 min ago", "3 h ago", "2 d ago". Pure, so it is unit-tested. */
        fun ago(seconds: Long): String = when {
            seconds < 90 -> "just now"
            seconds < 3600 -> "${(seconds / 60.0).roundToInt()} min ago"
            seconds < 86_400 -> "${(seconds / 3600.0).roundToInt()} h ago"
            else -> "${(seconds / 86_400.0).roundToInt()} d ago"
        }

        /** Battery colour, as in the app: red when low, amber when getting there, green otherwise. */
        fun batteryColor(pct: Int): Int = when {
            pct < LOW_PCT -> 0xFFFF6B6B.toInt()
            pct < OK_PCT -> 0xFFF5B642.toInt()
            else -> 0xFF4ADE9C.toInt()
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Remember the newest night's scores for [serial] after a sync, then redraw. */
        fun saveNight(ctx: Context, serial: String, ringName: String, analysis: Analysis) {
            val n = analysis.nights.lastOrNull()
            prefs(ctx).edit()
                .putString("serial", serial).putString("name", ringName)
                .putInt("ready", n?.readiness?.value ?: -1)
                .putInt("sleep", n?.sleepScore?.value ?: -1)
                .putFloat("sleepH", ((n?.totalSleepMin ?: 0.0) / 60.0).toFloat())
                .putLong("synced", System.currentTimeMillis() / 1000)
                .apply()
            refresh(ctx)
        }

        /** Remember the latest battery reading for [serial], then redraw. */
        fun saveBattery(ctx: Context, serial: String, ringName: String, pct: Int) {
            val p = prefs(ctx)
            val e = p.edit()
            if (p.getString("serial", null) != serial) e.putString("serial", serial).putString("name", ringName).putInt("ready", -1).putInt("sleep", -1)
            e.putInt("batt", pct).putLong("battTs", System.currentTimeMillis() / 1000).apply()
            refresh(ctx)
        }

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RingWidget::class.java))
            if (ids.isEmpty()) return
            val views = render(ctx)
            for (id in ids) mgr.updateAppWidget(id, views)
        }

        private fun render(ctx: Context): RemoteViews {
            val p = prefs(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.ring_widget)
            val ready = p.getInt("ready", -1)
            val sleep = p.getInt("sleep", -1)
            val batt = p.getInt("batt", -1)
            val now = System.currentTimeMillis() / 1000
            val synced = p.getLong("synced", 0)
            v.setTextViewText(R.id.w_name, p.getString("name", null) ?: "My Rings")
            v.setTextViewText(R.id.w_when, if (synced == 0L) "Open the app to sync" else "Synced ${ago(now - synced)}")
            v.setTextViewText(R.id.w_ready, if (ready >= 0) ready.toString() else "—")
            v.setTextViewText(R.id.w_sleep, if (sleep >= 0) sleep.toString() else "—")
            val h = p.getFloat("sleepH", 0f)
            v.setTextViewText(R.id.w_sleep_sub, if (sleep >= 0 && h > 0f) "Sleep · %.1f h".format(h) else "Sleep")
            v.setTextViewText(R.id.w_batt, if (batt >= 0) "$batt%" else "—")
            if (batt >= 0) v.setTextColor(R.id.w_batt, batteryColor(batt))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }
    }
}
