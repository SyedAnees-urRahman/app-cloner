package com.cloner.multiinstance.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cloner.multiinstance.R

/**
 * Keep-alive foreground service, runs in the :clone process.
 *
 * Infinix XOS ships an aggressive task killer ("Freezer") that terminates
 * backgrounded app processes — including our :clone process, which would
 * kill every running cloned app. A foreground service raises the process
 * to foreground priority so XOS leaves it alone.
 *
 * Uses the specialUse foreground-service type (Android 14+), declared in
 * the manifest with the required PROPERTY_SPECIAL_USE_FGS_SUBTYPE.
 */
class CloneKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Clone engine",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("App Cloner Studio")
            .setContentText("Cloned apps are running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        // If XOS kills us anyway, have the system restart the service
        // (which restarts the :clone process).
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "clone_keep_alive"
        private const val NOTIFICATION_ID = 1001
    }
}
