package com.rm.interchange

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rm.interchange.platform.Network

/** Keeps the app running while a game is open to players, with Wi-Fi and the CPU held awake. */
class HostingService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            Handler(Looper.getMainLooper()).post { Network.stopHosting?.invoke() }
            stopSelf()
            return START_NOT_STICKY
        }
        val channel = NotificationChannel(CHANNEL, getString(R.string.hosting_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val name = intent?.getStringExtra(NAME) ?: ""
        val players = intent?.getIntExtra(PLAYERS, 0) ?: 0
        ServiceCompat.startForeground(
            this, ID, notification(this, name, players), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        running = true
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "interchange:hosting").apply { setReferenceCounted(false); acquire() }
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "interchange:hosting").apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        wake = null
        wifi = null
    }

    companion object {
        private const val CHANNEL = "hosting"
        private const val ID = 1
        private const val STOP = "com.rm.interchange.STOP_HOSTING"
        private const val NAME = "name"
        private const val PLAYERS = "players"

        /** True from the service going to the foreground until it's destroyed. */
        @Volatile var running = false
            private set

        /** Starts the service, or once it's running updates its notification, since starting it again from the background isn't allowed. */
        fun start(context: Context, name: String, players: Int) {
            if (running) {
                context.getSystemService(NotificationManager::class.java).notify(ID, notification(context, name, players))
                return
            }
            // Refused from the background on Android 12 and up; hosting carries on while the app stays open.
            runCatching {
                context.startForegroundService(
                    Intent(context, HostingService::class.java).putExtra(NAME, name).putExtra(PLAYERS, players),
                )
            }
        }

        private fun notification(context: Context, name: String, players: Int): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getService(
                context, 1, Intent(context, HostingService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_hosting)
                .setContentTitle(context.getString(R.string.hosting_title, name))
                .setContentText(context.resources.getQuantityString(R.plurals.hosting_players, players, players))
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.hosting_stop), stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        fun stop(context: Context) {
            running = false
            context.stopService(Intent(context, HostingService::class.java))
        }
    }
}
