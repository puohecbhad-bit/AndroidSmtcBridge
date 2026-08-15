package dev.zktsw.androidsmtcbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BridgeUiState(
    val listenerConnected: Boolean = false,
    val wifiRunning: Boolean = false,
    val bluetoothRunning: Boolean = false,
    val connectedClients: Int = 0,
    val wifiAddresses: List<String> = emptyList(),
    val lastError: String = "",
    val media: MediaSnapshot = MediaSnapshot(),
)

object BridgeState {
    private val mutable = MutableStateFlow(BridgeUiState())
    val state = mutable.asStateFlow()

    fun update(block: (BridgeUiState) -> BridgeUiState) {
        mutable.value = block(mutable.value)
    }
}
