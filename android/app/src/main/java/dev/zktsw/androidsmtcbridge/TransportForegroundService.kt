package dev.zktsw.androidsmtcbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class TransportForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Media Bridge 正在运行")
                .setContentText("等待 Windows 通过 Wi-Fi 或蓝牙连接")
                .setOngoing(true)
                .setSilent(true)
                .build(),
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:transport")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:wifi")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TransportRuntime.reload(applicationContext)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "媒体桥接连接",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "media_bridge_transport"
        private const val NOTIFICATION_ID = 7419
        private const val ACTION_RELOAD = "dev.zktsw.androidsmtcbridge.RELOAD_TRANSPORT"
        @Volatile private var instance: TransportForegroundService? = null

        fun ensureStarted(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TransportForegroundService::class.java).setAction(ACTION_RELOAD),
            )
        }

        fun broadcast(snapshot: MediaSnapshot) {
            TransportRuntime.broadcast(snapshot)
        }
    }
}

/**
 * Owns transport sockets for the lifetime of the app process rather than the
 * lifetime of one Service instance. Android/OEM service recreation must not
 * close an authenticated Windows connection.
 */
private object TransportRuntime {
    @Volatile private var hub: TransportHub? = null

    fun reload(context: Context) {
        val current = hub ?: synchronized(this) {
            hub ?: TransportHub(
                context.applicationContext,
                MediaBridgeService::dispatchCommand,
            ).also { hub = it }
        }
        current.start(BridgePreferences.load(context))
        current.broadcast(BridgeState.state.value.media)
    }

    fun broadcast(snapshot: MediaSnapshot) {
        hub?.broadcast(snapshot)
    }
}
