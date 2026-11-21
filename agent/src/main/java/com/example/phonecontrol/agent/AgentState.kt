package com.example.phonecontrol.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class MdnsState {
    IDLE,
    ADVERTISING,
    FAILED
}

data class AgentUiState(
    val isPreparing: Boolean = false,
    val isSharing: Boolean = false,
    val isServerRunning: Boolean = false,
    val connections: Int = 0,
    val localIp: String = "--",
    val port: Int = 8765,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val captureWidth: Int = 0,
    val captureHeight: Int = 0,
    val accessibilityConnected: Boolean = false,
    val mdnsState: MdnsState = MdnsState.IDLE,
    val error: String? = null
)

/**
 * Process-wide state shared by MainActivity, AgentService, the WebSocket server
 * and the accessibility service. It deliberately contains no Context.
 */
object AgentState {
    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    fun update(transform: (AgentUiState) -> AgentUiState) {
        _state.update(transform)
    }

    fun setAccessibilityConnected(connected: Boolean) {
        update { it.copy(accessibilityConnected = connected) }
    }

    fun clearError() {
        update { it.copy(error = null) }
    }

    fun stopped(error: String? = null) {
        update {
            it.copy(
                isPreparing = false,
                isSharing = false,
                isServerRunning = false,
                connections = 0,
                mdnsState = MdnsState.IDLE,
                error = error
            )
        }
    }
}
