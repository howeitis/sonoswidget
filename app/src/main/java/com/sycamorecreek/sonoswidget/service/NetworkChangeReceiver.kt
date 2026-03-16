package com.sycamorecreek.sonoswidget.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Monitors network connectivity changes and triggers PlaybackService
 * re-discovery when the device regains Wi-Fi or internet connectivity.
 *
 * Per PRD Section 13.1:
 * - "Wi-Fi disconnected" → auto-switch back when LAN returns
 * - "No internet + no LAN" → retry on network change broadcast
 *
 * Registered by [PlaybackService] during its lifecycle.
 */
class NetworkChangeReceiver(
    private val context: Context,
    private val onNetworkAvailable: () -> Unit
) {

    companion object {
        private const val TAG = "NetworkChangeReceiver"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available — triggering re-discovery")
            onNetworkAvailable()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            val hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (hasWifi) {
                Log.d(TAG, "Wi-Fi capabilities changed — triggering re-discovery")
                onNetworkAvailable()
            }
        }
    }

    fun register() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        registered = true
        Log.d(TAG, "Network callback registered")
    }

    fun unregister() {
        if (!registered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Network callback was not registered", e)
        }
        registered = false
        Log.d(TAG, "Network callback unregistered")
    }
}
