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
    }
}
