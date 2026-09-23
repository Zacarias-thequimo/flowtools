package com.flowtools.session

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ConnectionManager: holds session state, survives short reconnects,
 * never blocks UI. Local-first; relay is explicit opt-in.
 */
class ConnectionManager(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow(ConnectionState.Unpaired)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _pcName = MutableStateFlow("PC de trabalho")
    val pcName: StateFlow<String> = _pcName.asStateFlow()

    private var granted: Set<Capability> = emptySet()
    private var token: String? = null
    private var reconnectJob: Job? = null

    fun setPaired(pcName: String, granted: Set<Capability>, token: String) {
        _pcName.value = pcName
        this.granted = granted
        this.token = token
        _state.value = ConnectionState.Connected
    }

    fun connecting() { _state.value = ConnectionState.Pairing }

    fun onDisconnected() {
        if (_state.value == ConnectionState.Connected) {
            _state.value = ConnectionState.Reconnecting
            scheduleReconnect()
        }
    }

    fun onOffline() {
        reconnectJob?.cancel()
        _state.value = ConnectionState.Offline
    }

    fun requirePermission() { _state.value = ConnectionState.PermissionRequired }

    /** Server confirmed the session (WS State event). Keeps granted set. */
    fun markConnected() {
        reconnectJob?.cancel()
        _state.value = ConnectionState.Connected
    }

    fun revoke() {
        reconnectJob?.cancel()
        granted = emptySet()
        token = null
        _state.value = ConnectionState.Unpaired
    }

    fun canUse(tool: String): Boolean {
        if (_state.value != ConnectionState.Connected) return false
        return missingCapabilities(granted, requiredForTool(tool)).isEmpty()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            // MVP: mark offline if no token refresh; real impl re-opens WS.
            if (_state.value == ConnectionState.Reconnecting) {
                _state.value = ConnectionState.Offline
            }
        }
    }
}
