package dev.zktsw.androidsmtcbridge

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.AudioManager
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
import android.provider.Settings
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MediaBridgeService : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong(0)
    private lateinit var sessionManager: MediaSessionManager
    private lateinit var audioManager: AudioManager
    private var activeController: MediaController? = null
    private var lastArtKey = ""
    private var loadingArtKey = ""
    private var cachedArt = EncodedArt()
    private var notificationArtPackage = ""
    private var notificationArtBitmap: Bitmap? = null
    private val artExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "media-bridge-artwork").apply { isDaemon = true }
    }
    private var lastPublishedVolume = -1

    private val volumeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current != lastPublishedVolume) publish()
        }
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        selectController(controllers.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
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
        audioManager = getSystemService(AudioManager::class.java)
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        BridgeState.update { it.copy(listenerConnected = true, lastError = "") }
        activeNotifications
            ?.filter { it.notification.category == Notification.CATEGORY_TRANSPORT }
            ?.maxByOrNull { it.postTime }
            ?.let(::captureNotificationArt)
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
        if (sbn?.notification?.category == Notification.CATEGORY_TRANSPORT) {
            captureNotificationArt(sbn)
            refreshSessions()
        }
    }

    private fun captureNotificationArt(sbn: StatusBarNotification) {
        notificationArtPackage = sbn.packageName
        notificationArtBitmap = sbn.notification.getLargeIcon()
            ?.loadDrawable(this)
            ?.let(::drawableToBitmap)
    }

    override fun onDestroy() {
        if (::sessionManager.isInitialized) {
            runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionListener) }
        }
        activeController?.unregisterCallback(controllerCallback)
        mainHandler.removeCallbacks(positionTicker)
        contentResolver.unregisterContentObserver(volumeObserver)
        artExecutor.shutdownNow()
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
        val packageName = controller?.packageName.orEmpty()
        val position = currentPosition(playbackState, metadata)
        val actions = playbackState?.actions ?: 0L
        val config = BridgePreferences.load(this)
        val volumeLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        lastPublishedVolume = volumeLevel
        val artKey = listOf(
            packageName,
            artworkTrackIdentity(metadata),
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty(),
            metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty(),
        ).joinToString("|")
        if ((forceArt || artKey != lastArtKey) && loadingArtKey != artKey) {
            lastArtKey = artKey
            loadingArtKey = artKey
            val fallbackArt = notificationArtBitmap.takeIf { notificationArtPackage == packageName }
            artExecutor.execute {
                val encoded = encodeArt(metadata, fallbackArt)
                mainHandler.post {
                    if (lastArtKey == artKey) {
                        cachedArt = encoded
                        loadingArtKey = ""
                        publish()
                    }
                }
            }
        }

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
            volume = if (config.volumeSyncEnabled) volumeLevel.toDouble() / volumeMax else -1.0,
            volumeSyncEnabled = config.volumeSyncEnabled,
            artMime = cachedArt.mime,
            artBase64 = cachedArt.base64,
        )
        BridgeState.update { it.copy(media = snapshot) }
        TransportForegroundService.broadcast(snapshot)
    }

    private fun artworkTrackIdentity(metadata: MediaMetadata?): String {
        val title = metadata.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).trim()
        val artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).trim()
        val embeddedTrack = TRACK_AND_ARTIST.find(artist)
        return if (embeddedTrack != null) {
            val (trackName, trackArtist) = embeddedTrack.destructured
            "${trackName.trim()}\u001f${trackArtist.trim()}"
        } else {
            "$title\u001f$artist"
        }
    }

    private fun handleCommand(command: RemoteCommand) {
        mainHandler.post {
            val controls = activeController?.transportControls
            when (command.action) {
                "play" -> controls?.play()
                "pause" -> controls?.pause()
                "toggle" -> if (activeController?.playbackState?.state == PlaybackState.STATE_PLAYING) controls?.pause() else controls?.play()
                "next" -> controls?.skipToNext()
                "previous" -> controls?.skipToPrevious()
                "stop" -> controls?.stop()
                "seek" -> command.positionMs?.coerceAtLeast(0)?.let { controls?.seekTo(it) }
                "volume" -> if (BridgePreferences.load(this).volumeSyncEnabled) {
                    command.volume?.let { scalar ->
                        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            (scalar * maximum).toInt().coerceIn(0, maximum),
                            0,
                        )
                        publish()
                    }
                }
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
    private fun encodeArt(metadata: MediaMetadata?, fallback: Bitmap?): EncodedArt {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: loadUriBitmap(metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI))
            ?: loadUriBitmap(metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI))
            ?: loadUriBitmap(metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))
            ?: fallback
            ?: return EncodedArt()
        return runCatching {
            val scaled = scaleDown(bitmap, 512)
            val output = ByteArrayOutputStream()
            // JPEG is consistently decoded by Windows SMTC. WebP thumbnails
            // are accepted by some Windows builds but silently ignored by others.
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)
            if (scaled !== bitmap) scaled.recycle()
            EncodedArt(
                mime = "image/jpeg",
                base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
            )
        }.getOrDefault(EncodedArt())
    }

    private fun loadUriBitmap(value: String?): Bitmap? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val uri = Uri.parse(value)
            if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
                val connection = URL(value).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = true
                try {
                    connection.inputStream.use(BitmapFactory::decodeStream)
                } finally {
                    connection.disconnect()
                }
            } else {
                contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            }
        }.getOrNull()
    }

    private fun scaleDown(bitmap: Bitmap, max: Int): Bitmap {
        if (bitmap.width <= max && bitmap.height <= max) return bitmap
        val ratio = minOf(max.toFloat() / bitmap.width, max.toFloat() / bitmap.height)
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val width = drawable.intrinsicWidth.coerceAtLeast(1).coerceAtMost(1024)
        val height = drawable.intrinsicHeight.coerceAtLeast(1).coerceAtMost(1024)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
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
        private val TRACK_AND_ARTIST = Regex("^(.+?)\\s+[\\-–—]\\s+(.+)$")
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
