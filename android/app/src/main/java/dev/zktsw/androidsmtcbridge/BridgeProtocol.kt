package dev.zktsw.androidsmtcbridge

import org.json.JSONObject

const val PROTOCOL_VERSION = 2
const val RFCOMM_SERVICE_NAME = "Android SMTC Bridge"
const val RFCOMM_SERVICE_UUID = "8e7f1a9d-2c64-4db8-9f75-6a33ce5b2170"

data class MediaSnapshot(
    val sequence: Long = 0,
    val packageName: String = "",
    val appName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val playback: String = "stopped",
    val canPlay: Boolean = false,
    val canPause: Boolean = false,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val canSeek: Boolean = false,
    val volume: Double = -1.0,
    val volumeSyncEnabled: Boolean = false,
    val artMime: String = "",
    val artBase64: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("type", "state")
        put("version", PROTOCOL_VERSION)
        put("sequence", sequence)
        put("package", packageName)
        put("app", appName)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("durationMs", durationMs)
        put("positionMs", positionMs)
        put("playback", playback)
        put("canPlay", canPlay)
        put("canPause", canPause)
        put("canNext", canNext)
        put("canPrevious", canPrevious)
        put("canSeek", canSeek)
        put("volume", volume)
        put("volumeSyncEnabled", volumeSyncEnabled)
        put("artMime", artMime)
        put("artBase64", artBase64)
    }.toString()
}

data class RemoteCommand(
    val action: String,
    val positionMs: Long? = null,
    val volume: Double? = null,
) {
    companion object {
        fun fromJson(line: String): RemoteCommand? = runCatching {
            val json = JSONObject(line)
            if (json.optString("type") != "command") return null
            val action = json.getString("action")
            val position = if (json.has("positionMs")) json.getLong("positionMs") else null
            val volume = if (json.has("volume")) json.getDouble("volume").coerceIn(0.0, 1.0) else null
            RemoteCommand(action, position, volume)
        }.getOrNull()
    }
}
