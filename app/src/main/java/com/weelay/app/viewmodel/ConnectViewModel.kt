package com.weelay.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weelay.app.data.ConnectionPrefs
import com.weelay.app.relay.ConnectionState
import com.weelay.app.relay.RelayClient
import com.weelay.app.service.RelayService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectUiState(
    val host: String = "",
    val port: String = "9001",
    val password: String = "",
    val useSsl: Boolean = false,
    val passwordVisible: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val application: Application,
    private val relayClient: RelayClient,
    private val connectionPrefs: ConnectionPrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = relayClient.connectionState

    /** True if we attempted auto-connect on startup */
    private val _autoConnecting = MutableStateFlow(shouldAutoConnect())
    val autoConnecting: StateFlow<Boolean> = _autoConnecting.asStateFlow()

    init {
        if (connectionPrefs.hasSaved()) {
            _uiState.value = connectionPrefs.load()
            if (shouldAutoConnect()) connect()
        }
        viewModelScope.launch {
            relayClient.errorMessage.collect { error ->
                if (error != null) {
                    _autoConnecting.value = false
                    _uiState.update { it.copy(errorMessage = error) }
                }
            }
        }
    }

    /**
     * Connect on launch only if the last session wasn't ended on purpose. After an explicit
     * disconnect (or a rejected password) the form is shown pre-filled instead, so the user
     * decides when to go back online.
     */
    private fun shouldAutoConnect(): Boolean =
        connectionPrefs.hasSaved() && connectionPrefs.shouldAutoConnect

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

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun connect() {
        val state = _uiState.value
        if (state.host.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Host is required") }
            return
        }
        val port = state.port.toIntOrNull()
        if (port == null || port !in 1..65535) {
            _uiState.update { it.copy(errorMessage = "Invalid port number") }
            return
        }
        if (state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Password is required") }
            return
        }

        connectionPrefs.save(
            host = state.host.trim(),
            port = state.port,
            password = state.password,
            useSsl = state.useSsl
        )

        // The service owns the connection so it survives the app being backgrounded.
        RelayService.start(
            context = application,
            host = state.host.trim(),
            port = port,
            password = state.password,
            useSsl = state.useSsl
        )
    }
}
