package com.slinggus.bridgedroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class BridgeService : Service() {

    private val binder = LocalBinder()
    lateinit var bridgeManager: BridgeManager
    var isBridgeRunning: Boolean = false
        private set

    companion object {
        const val CHANNEL_ID = "bridge_service"
        const val NOTIF_ID = 1001
        const val ACTION_START_FOREGROUND = "com.slinggus.bridgedroid.START_FG"
        const val ACTION_STOP_FOREGROUND = "com.slinggus.bridgedroid.STOP_FG"
    }

    inner class LocalBinder : Binder() {
        fun getService(): BridgeService = this@BridgeService
    }

    override fun onCreate() {
        super.onCreate()
        bridgeManager = BridgeManager(this) { log ->
            val intent = Intent("com.netbridge.LOG").putExtra("msg", log)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> promoteToForeground()
            ACTION_STOP_FOREGROUND -> demoteAndStop()
        }
        return START_NOT_STICKY
    }

    fun promoteToForeground(detail: String = "Bridging Network") {
        isBridgeRunning = true
        val notification = createNotification(detail)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    fun demoteAndStop() {
        isBridgeRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotification(detail: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Network Bridge Active",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Network Bridge Active")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}