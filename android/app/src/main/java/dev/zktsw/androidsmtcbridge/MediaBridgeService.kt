package dev.zktsw.androidsmtcbridge

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

class MediaBridgeService : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong(0)
    private lateinit var sessionManager: MediaSessionManager
    private var activeController: MediaController? = null
    private var lastArtKey = ""
    private var cachedArt = EncodedArt()

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        selectController(controllers.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish(forceArt = true)
        override fun onSessionDestroyed() = refreshSessions()
    }

    private val positionTicker = object : Runnable {
        override fun run() {
            if (activeController?.playbackState?.state == PlaybackState.STATE_PLAYING) publish()
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        sessionManager = getSystemService(MediaSessionManager::class.java)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        BridgeState.update { it.copy(listenerConnected = true, lastError = "") }
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionListener,
                ComponentName(this, MediaBridgeService::class.java),
                mainHandler,
            )
        }.onFailure { error ->
            BridgeState.update { it.copy(lastError = "媒体会话监听失败: ${error.message}") }
        }
        refreshSessions()
        mainHandler.removeCallbacks(positionTicker)
        mainHandler.post(positionTicker)
    }

    override fun onListenerDisconnected() {
        BridgeState.update { it.copy(listenerConnected = false) }
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        mainHandler.removeCallbacks(positionTicker)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.notification?.category == Notification.CATEGORY_TRANSPORT) refreshSessions()
    }

    override fun onDestroy() {
        if (::sessionManager.isInitialized) {
            runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionListener) }
        }
        activeController?.unregisterCallback(controllerCallback)
        mainHandler.removeCallbacks(positionTicker)
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun reloadConfig() {
        refreshSessions()
        publish(forceArt = true)
    }

    private fun refreshSessions() {
        val component = ComponentName(this, MediaBridgeService::class.java)
        runCatching { sessionManager.getActiveSessions(component) }
            .onSuccess(::selectController)
            .onFailure { BridgeState.update { state -> state.copy(lastError = "请先开启通知使用权") } }
    }

    private fun selectController(controllers: List<MediaController>) {
        val selected = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: controllers.firstOrNull()
        if (selected?.sessionToken == activeController?.sessionToken) {
            publish()
            return
        }
        activeController?.unregisterCallback(controllerCallback)
        activeController = selected
        selected?.registerCallback(controllerCallback, mainHandler)
        lastArtKey = ""
        cachedArt = EncodedArt()
        publish(forceArt = true)
    }

    @Suppress("DEPRECATION")
    private fun publish(forceArt: Boolean = false) {
        val controller = activeController
        val metadata = controller?.metadata
        val playbackState = controller?.playbackState
        val position = currentPosition(playbackState, metadata)
        val actions = playbackState?.actions ?: 0L
        val artKey = listOf(
            controller?.packageName.orEmpty(),
            metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty(),
        ).joinToString("|")
        if (forceArt || artKey != lastArtKey) {
            cachedArt = encodeArt(metadata)
            lastArtKey = artKey
        }

        val packageName = controller?.packageName.orEmpty()
        val snapshot = MediaSnapshot(
            sequence = sequence.incrementAndGet(),
            packageName = packageName,
            appName = appLabel(packageName),
            title = metadata.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0,
            positionMs = position,
            playback = when (playbackState?.state) {
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> "playing"
                PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING -> "paused"
                else -> "stopped"
            },
            canPlay = actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L,
            canPause = actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            artMime = cachedArt.mime,
            artBase64 = cachedArt.base64,
        )
        BridgeState.update { it.copy(media = snapshot) }
        TransportForegroundService.broadcast(snapshot)
    }

    private fun handleCommand(command: RemoteCommand) {
        mainHandler.post {
            val controls = activeController?.transportControls ?: return@post
            when (command.action) {
                "play" -> controls.play()
                "pause" -> controls.pause()
                "toggle" -> if (activeController?.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
                "next" -> controls.skipToNext()
                "previous" -> controls.skipToPrevious()
                "stop" -> controls.stop()
                "seek" -> command.positionMs?.coerceAtLeast(0)?.let(controls::seekTo)
            }
        }
    }

    private fun currentPosition(state: PlaybackState?, metadata: MediaMetadata?): Long {
        if (state == null) return 0
        var position = state.position.coerceAtLeast(0)
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
        return if (duration > 0) position.coerceIn(0, duration) else position
    }

    @Suppress("DEPRECATION")
    private fun encodeArt(metadata: MediaMetadata?): EncodedArt {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: loadUriBitmap(metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI))
            ?: loadUriBitmap(metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI))
            ?: return EncodedArt()
        return runCatching {
            val scaled = scaleDown(bitmap, 512)
            val output = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
            scaled.compress(format, 82, output)
            if (scaled !== bitmap) scaled.recycle()
            EncodedArt(
                mime = if (Build.VERSION.SDK_INT >= 30) "image/webp" else "image/jpeg",
                base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
            )
        }.getOrDefault(EncodedArt())
    }

    private fun loadUriBitmap(value: String?): Bitmap? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            contentResolver.openInputStream(Uri.parse(value))?.use(BitmapFactory::decodeStream)
        }.getOrNull()
    }

    private fun scaleDown(bitmap: Bitmap, max: Int): Bitmap {
        if (bitmap.width <= max && bitmap.height <= max) return bitmap
        val ratio = minOf(max.toFloat() / bitmap.width, max.toFloat() / bitmap.height)
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    }

    private fun appLabel(packageName: String): String {
        if (packageName.isBlank()) return "Android"
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun MediaMetadata?.text(primary: String, fallback: String): String =
        this?.getString(primary)?.takeIf { it.isNotBlank() }
            ?: this?.getString(fallback).orEmpty()

    private data class EncodedArt(val mime: String = "", val base64: String = "")

    companion object {
        @Volatile private var instance: MediaBridgeService? = null

        fun reload(context: Context) {
            TransportForegroundService.ensureStarted(context)
            instance?.reloadConfig() ?: requestRebind(ComponentName(context, MediaBridgeService::class.java))
        }

        fun dispatchCommand(command: RemoteCommand) {
            instance?.handleCommand(command)
        }
    }
}
