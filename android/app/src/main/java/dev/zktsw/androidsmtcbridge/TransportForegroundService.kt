package dev.zktsw.androidsmtcbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class TransportForegroundService : Service() {
    private lateinit var hub: TransportHub

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
        hub = TransportHub(applicationContext, MediaBridgeService::dispatchCommand)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::hub.isInitialized) reloadTransport()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::hub.isInitialized) hub.close()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun reloadTransport() {
        hub.start(BridgePreferences.load(this))
        hub.broadcast(BridgeState.state.value.media)
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
            instance?.hub?.broadcast(snapshot)
        }
    }
}
