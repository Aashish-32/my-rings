package com.aashish.myrings.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aashish.myrings.MainActivity
import kotlinx.coroutines.*

/** Keeps the battery fresh and syncs history in the background while rings are nearby. */
class RingService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ring sync", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("My Rings")
            .setContentText("Keeping your ring's battery and data up to date")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        if (job?.isActive != true) {
            val mgr = RingManager.get(this)
            job = mgr.scope.launch {
                while (isActive) {
                    runCatching { mgr.backgroundPass() }
                    delay(INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ring_sync"
        private const val NOTIF_ID = 1
        // How often the loop wakes to check what is due. Connections themselves are rate-limited in RingManager.
        const val INTERVAL_MS = 5 * 60_000L

        fun start(ctx: Context) {
            androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, RingService::class.java))
        }
    }
}
