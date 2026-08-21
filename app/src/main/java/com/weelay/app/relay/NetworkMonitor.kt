package com.weelay.app.relay

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the device's default network and reports when a usable one becomes available.
 *
 * Without this the client only notices a dead link when a write fails or the activity watchdog
 * fires, and then has to ride out the exponential backoff — so a Wi-Fi to mobile handover can
 * leave the relay disconnected for the better part of a minute even though the phone had working
 * connectivity the whole time. Reacting to the system's own signal reconnects in roughly the time
 * it takes to open a socket.
 */
@Singleton
class NetworkMonitor @Inject constructor(application: Application) {

    private val connectivityManager =
        application.getSystemService(ConnectivityManager::class.java)

    /** The network we last reported as usable, so repeated callbacks don't cause reconnect storms. */
    private var lastUsableNetwork: Network? = null

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Start watching. [onNetworkAvailable] is invoked whenever the default network changes to a
     * validated one — including the first time it becomes available after a period offline.
     * Calling this while already started is a no-op.
     */
    fun start(onNetworkAvailable: () -> Unit) {
        if (callback != null) return

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Wait for VALIDATED rather than firing on onAvailable: a network that has
                // associated but not yet passed the connectivity check (captive portal, slow
                // DHCP) will just fail the connect and burn an attempt.
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!validated) return
                if (network == lastUsableNetwork) return

                lastUsableNetwork = network
                Log.i("NetworkMonitor", "usable network available: $network")
                onNetworkAvailable()
            }

            override fun onLost(network: Network) {
                if (network == lastUsableNetwork) {
                    Log.i("NetworkMonitor", "lost network: $network")
                    lastUsableNetwork = null
                }
            }
        }

        callback = cb
        try {
            // Seed with whatever is already the default network. Registering a callback replays
            // the current state immediately, and without this that replay looks like a change and
            // needlessly restarts the connection attempt that was just started.
            lastUsableNetwork = connectivityManager.activeNetwork
            connectivityManager.registerDefaultNetworkCallback(cb)
        } catch (e: Exception) {
            // registerDefaultNetworkCallback can throw if too many callbacks are registered.
            // Losing the fast path is survivable — the backoff loop still reconnects eventually.
            Log.w("NetworkMonitor", "could not register network callback", e)
            callback = null
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        lastUsableNetwork = null
        try {
            connectivityManager.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
            // Already unregistered; nothing to do.
        }
    }
}
