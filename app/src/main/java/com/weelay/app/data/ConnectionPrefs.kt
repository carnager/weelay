package com.weelay.app.data

import android.app.Application
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.weelay.app.viewmodel.ConnectUiState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionPrefs @Inject constructor(application: Application) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs = EncryptedSharedPreferences.create(
        "weelay_connection_prefs",
        masterKeyAlias,
        application,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(host: String, port: String, password: String, useSsl: Boolean) {
        // A trusted certificate belongs to one server; pointing the app elsewhere drops it.
        val serverChanged = prefs.getString(KEY_HOST, "") != host || prefs.getString(KEY_PORT, "") != port
        if (serverChanged) prefs.edit().remove(KEY_PINNED_CERT).apply()
        prefs.edit()
            .putString(KEY_HOST, host)
            .putString(KEY_PORT, port)
            .putString(KEY_PASSWORD, password)
            .putBoolean(KEY_USE_SSL, useSsl)
            .apply()
    }

    fun load(): ConnectUiState {
        return ConnectUiState(
            host = prefs.getString(KEY_HOST, "") ?: "",
            port = prefs.getString(KEY_PORT, "9001") ?: "9001",
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            useSsl = prefs.getBoolean(KEY_USE_SSL, false)
        )
    }

    fun hasSaved(): Boolean = prefs.contains(KEY_HOST) &&
            (prefs.getString(KEY_HOST, "") ?: "").isNotBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * True while the user wants a live relay connection. Set when connecting and cleared on an
     * explicit disconnect, so a service restarted by the system (START_STICKY, with a null
     * intent) can tell whether to re-establish the connection or just stand down.
     */
    var shouldAutoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONNECT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()
        }

    /**
     * Extra words that count as a mention, comma-separated, mirroring `weechat.look.highlight`.
     *
     * Kept here rather than read from the relay because the `relay api` exposes no options
     * endpoint and its buffer objects carry no highlight_words field — there is no way to ask
     * WeeChat what they are.
     */
    var highlightWords: String
        get() = prefs.getString(KEY_HIGHLIGHT_WORDS, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_HIGHLIGHT_WORDS, value).apply()
        }

    /**
     * Fetch OpenGraph previews for links in chat. Fetching a link reveals the device's IP address
     * to whoever controls it, and anyone in a channel can post one, so this can be switched off.
     */
    var linkPreviews: Boolean
        get() = prefs.getBoolean(KEY_LINK_PREVIEWS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LINK_PREVIEWS, value).apply()
        }

    /** SHA-256 fingerprint of a certificate the user accepted for the saved server, if any. */
    var pinnedCertSha256: String?
        get() = prefs.getString(KEY_PINNED_CERT, null)
        set(value) {
            prefs.edit().putString(KEY_PINNED_CERT, value).apply()
        }

    var hideSpecialBuffers: Boolean
        get() = prefs.getBoolean(KEY_HIDE_SPECIAL, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HIDE_SPECIAL, value).apply()
        }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_PASSWORD = "password"
        private const val KEY_USE_SSL = "use_ssl"
        private const val KEY_HIDE_SPECIAL = "hide_special_buffers"
        private const val KEY_AUTO_CONNECT = "should_auto_connect"
        private const val KEY_HIGHLIGHT_WORDS = "highlight_words"
        private const val KEY_LINK_PREVIEWS = "link_previews"
        private const val KEY_PINNED_CERT = "pinned_cert_sha256"
    }
}
