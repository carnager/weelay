package com.weelay.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import com.weelay.app.data.ConnectionPrefs
import com.weelay.app.relay.ConnectionState
import com.weelay.app.data.MessageLayout
import com.weelay.app.ui.theme.AppStyle
import com.weelay.app.ui.theme.Palette
import com.weelay.app.relay.RelayClient
import com.weelay.app.service.RelayService
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
    val hideSpecialBuffers: Boolean = false,
    val highlightWords: String = "",
    val linkPreviews: Boolean = true,
    val style: AppStyle = AppStyle.RELAY,
    val channelLayout: MessageLayout = MessageLayout.LINES,
    val privateLayout: MessageLayout = MessageLayout.BUBBLES
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val application: Application,
    private val connectionPrefs: ConnectionPrefs,
    private val relayClient: RelayClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = relayClient.connectionState

    /** The installed version, for the About row. */
    val appVersion: String = try {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    init {
        val saved = connectionPrefs.load()
        _uiState.value = SettingsUiState(
            host = saved.host,
            port = saved.port,
            password = saved.password,
            useSsl = saved.useSsl,
            hideSpecialBuffers = connectionPrefs.hideSpecialBuffers,
            highlightWords = connectionPrefs.highlightWords,
            linkPreviews = connectionPrefs.linkPreviews,
            style = connectionPrefs.style,
            channelLayout = connectionPrefs.channelLayout,
            privateLayout = connectionPrefs.privateLayout
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

    fun onHighlightWordsChange(words: String) {
        _uiState.update { it.copy(highlightWords = words) }
        // Persisted immediately; ChatViewModel rebuilds its matchers on the next buffer load.
        connectionPrefs.highlightWords = words
    }

    fun onStyleChange(style: AppStyle) {
        _uiState.update { it.copy(style = style) }
        connectionPrefs.style = style
        // Recolours every screen immediately; see Palette.
        Palette.style = style
    }

    fun onChannelLayoutChange(layout: MessageLayout) {
        _uiState.update { it.copy(channelLayout = layout) }
        connectionPrefs.channelLayout = layout
    }

    fun onPrivateLayoutChange(layout: MessageLayout) {
        _uiState.update { it.copy(privateLayout = layout) }
        connectionPrefs.privateLayout = layout
    }

    fun onLinkPreviewsChange(enabled: Boolean) {
        _uiState.update { it.copy(linkPreviews = enabled) }
        connectionPrefs.linkPreviews = enabled
    }

    /**
     * Drop the relay connection and stop the foreground service.
     *
     * Both steps happen here synchronously, not only in the service: the connect screen shown next
     * reads auto-connect and the connection state straight away, and seeing either still set
     * before the service had processed the stop would send it right back online.
     */
    fun disconnect() {
        connectionPrefs.shouldAutoConnect = false
        relayClient.disconnect()
        RelayService.stop(application)
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
        // No explicit disconnect first: that would push the client to DISCONNECTED and race the
        // service's own shutdown against the reconnect below. RelayClient.connect() already tears
        // down any existing socket.
        RelayService.start(
            context = application,
            host = state.host.trim(),
            port = port,
            password = state.password,
            useSsl = state.useSsl
        )
    }
}
