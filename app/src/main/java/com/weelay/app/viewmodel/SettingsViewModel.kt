package com.weelay.app.viewmodel

import androidx.lifecycle.ViewModel
import com.weelay.app.data.ConnectionPrefs
import com.weelay.app.relay.RelayClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SettingsUiState(
    val host: String = "",
    val port: String = "9001",
    val password: String = "",
    val useSsl: Boolean = false,
    val hideSpecialBuffers: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val connectionPrefs: ConnectionPrefs,
    private val relayClient: RelayClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        val saved = connectionPrefs.load()
        _uiState.value = SettingsUiState(
            host = saved.host,
            port = saved.port,
            password = saved.password,
            useSsl = saved.useSsl,
            hideSpecialBuffers = connectionPrefs.hideSpecialBuffers
        )
    }

    fun onHostChange(host: String) {
        _uiState.update { it.copy(host = host) }
    }

    fun onPortChange(port: String) {
        val filtered = port.filter { c -> c.isDigit() }
        val portNum = filtered.toIntOrNull()
        _uiState.update {
            it.copy(
                port = filtered,
                useSsl = if (portNum == 443) true else it.useSsl
            )
        }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password) }
    }

    fun onSslChange(useSsl: Boolean) {
        _uiState.update { it.copy(useSsl = useSsl) }
    }

    fun onHideSpecialBuffersChange(hide: Boolean) {
        _uiState.update { it.copy(hideSpecialBuffers = hide) }
        connectionPrefs.hideSpecialBuffers = hide
        // Force immediate refresh of buffer list
        relayClient.requestBuffers()
    }

    fun saveAndReconnect() {
        val state = _uiState.value
        connectionPrefs.save(
            host = state.host.trim(),
            port = state.port,
            password = state.password,
            useSsl = state.useSsl
        )
        val port = state.port.toIntOrNull() ?: return
        relayClient.disconnect()
        relayClient.connect(
            host = state.host.trim(),
            port = port,
            password = state.password,
            useSsl = state.useSsl
        )
    }
}
