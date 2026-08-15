package dev.zktsw.androidsmtcbridge

import android.content.Context
import java.security.SecureRandom

data class BridgeConfig(
    val wifiEnabled: Boolean,
    val bluetoothEnabled: Boolean,
    val port: Int,
    val pin: String,
)

object BridgePreferences {
    private const val FILE = "bridge_preferences"
    private const val WIFI = "wifi_enabled"
    private const val BLUETOOTH = "bluetooth_enabled"
    private const val PORT = "port"
    private const val PIN = "pin"

    fun load(context: Context): BridgeConfig {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        var pin = prefs.getString(PIN, null)
        if (pin == null) {
            pin = (100000 + SecureRandom().nextInt(900000)).toString()
            prefs.edit().putString(PIN, pin).apply()
        }
        return BridgeConfig(
            wifiEnabled = prefs.getBoolean(WIFI, true),
            bluetoothEnabled = prefs.getBoolean(BLUETOOTH, false),
            port = prefs.getInt(PORT, 45831).coerceIn(1024, 65535),
            pin = pin,
        )
    }

    fun save(context: Context, config: BridgeConfig) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(WIFI, config.wifiEnabled)
            .putBoolean(BLUETOOTH, config.bluetoothEnabled)
            .putInt(PORT, config.port.coerceIn(1024, 65535))
            .putString(PIN, config.pin.filter(Char::isDigit).take(6).padStart(6, '0'))
            .apply()
    }
}
