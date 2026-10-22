package com.aashish.myrings.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aashish.myrings.MainActivity
import com.aashish.myrings.data.Analysis
import com.aashish.myrings.data.Drift

/**
 * Local "heads-up" notifications when the body has moved away from the person's own normal
 * (see [Drift]). Computed on the phone after a sync; nothing leaves the device. One notification per episode: it is sent
 * when a drift first appears and not again until the metric has returned to normal.
 */
object HealthNotifier {
    private const val CHANNEL = "health_headsup"
    private const val PREFS = "headsup"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, true)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply() }

    /** What to do for one metric given whether an episode was already announced. Pure, so it is unit-tested. */
    data class Decision(val notify: Boolean, val nowActive: Boolean)

    fun decide(wasActive: Boolean, notePresent: Boolean, canPost: Boolean): Decision = when {
        !notePresent -> Decision(notify = false, nowActive = false)      // back to normal: the next drift is a new episode
        wasActive -> Decision(notify = false, nowActive = true)          // already announced
        !canPost -> Decision(notify = false, nowActive = false)          // can't show it yet: try again after permission is granted
        else -> Decision(notify = true, nowActive = true)
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Health heads-ups", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A calm note when resting heart rate or skin temperature drifts from your normal for several days"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun canPost(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun post(ctx: Context, id: Int, title: String, text: String) {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) } // permission can be revoked at any time
    }

    /** Called after a sync with the ring's analysis. One notification per episode of each kind of finding. */
    fun check(ctx: Context, serial: String, ringName: String, analysis: Analysis, nowSec: Long = System.currentTimeMillis() / 1000) {
        if (!enabled(ctx)) return
        val p = prefs(ctx)
        val found = Drift.findings(analysis, nowSec).associateBy { it.kind }
        for (kind in Drift.Kind.entries) {
            val key = "active_${serial}_${kind.key}"
            val finding = found[kind]
            val d = decide(p.getBoolean(key, false), finding != null, canPost(ctx))
            if (d.notify && finding != null) post(ctx, (serial + kind.key).hashCode(), "$ringName: ${kind.title}", finding.text)
            if (d.nowActive != p.getBoolean(key, false)) p.edit().putBoolean(key, d.nowActive).apply()
        }
    }

    const val LOW_BATTERY_PCT = 20   // ours, not Oura's: the ring ran flat at 7% on 2026-10-21 and stopped logging mid-sleep
    const val RECOVERED_PCT = 35     // back above this, the next low battery is a new episode

    /** Low-battery decision. [prevPct] lets us stay quiet while the ring is charging (the number is rising). Pure, so it is unit-tested. */
    fun decideLowBattery(wasActive: Boolean, pct: Int, prevPct: Int?, canPost: Boolean): Decision = when {
        pct >= RECOVERED_PCT -> Decision(notify = false, nowActive = false)
        pct > LOW_BATTERY_PCT -> Decision(notify = false, nowActive = wasActive)
        wasActive -> Decision(notify = false, nowActive = true)
        prevPct != null && pct > prevPct -> Decision(notify = false, nowActive = false) // charging
        !canPost -> Decision(notify = false, nowActive = false)
        else -> Decision(notify = true, nowActive = true)
    }

    /** Called with each new battery reading. One "charge it" note per low-battery episode. */
    fun checkBattery(ctx: Context, serial: String, ringName: String, pct: Int, prevPct: Int?) {
        if (!enabled(ctx)) return
        val p = prefs(ctx)
        val key = "active_${serial}_battery"
        val was = p.getBoolean(key, false)
        val d = decideLowBattery(was, pct, prevPct, canPost(ctx))
        if (d.notify) post(ctx, (serial + "battery").hashCode(), "$ringName is at $pct%", "Charge it before bed. A ring that runs flat stops recording your sleep.")
        if (d.nowActive != was) p.edit().putBoolean(key, d.nowActive).apply()
    }

    /** Developer-mode check that notifications reach the phone. Returns false if they are blocked. */
    fun sendTest(ctx: Context): Boolean {
        if (!canPost(ctx)) return false
        post(ctx, 4242, "Test heads-up", "Your heart rate has stayed around 82 bpm for 35 min while you've been still. (This is only a test.)")
        return true
    }
}
