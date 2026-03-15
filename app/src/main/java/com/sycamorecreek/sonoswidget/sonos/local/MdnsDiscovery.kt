package com.sycamorecreek.sonoswidget.sonos.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Discovers Sonos speakers via mDNS/Bonjour using Android's NsdManager.
 *
 * Queries for _sonos._tcp. services on the local network.
 * Returns as soon as the first speaker is successfully resolved —
 * this is optimized for the concurrent race with SSDP in LocalSonosController.
 *
 * The outer coroutine scope handles cancellation and timeout;
 * invokeOnCancellation cleans up the NsdManager listener.
 */
class MdnsDiscovery {

    companion object {
        private const val TAG = "MdnsDiscovery"
        private const val SERVICE_TYPE = "_sonos._tcp."
    }

    suspend fun discover(context: Context): List<DiscoveredSpeaker> =
        suspendCancellableCoroutine { cont ->
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val resolving = AtomicBoolean(false)
            val stopped = AtomicBoolean(false)
            var listener: NsdManager.DiscoveryListener? = null

            fun stopDiscovery() {
                if (stopped.compareAndSet(false, true)) {
                    try {
                        listener?.let { nsdManager.stopServiceDiscovery(it) }
                    } catch (e: Exception) {
                        Log.d(TAG, "stopServiceDiscovery: ${e.message}")
                    }
                }
            }

            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "mDNS discovery started for $serviceType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "mDNS found: ${serviceInfo.serviceName}")

                    // NsdManager can only resolve one service at a time on some devices.
                    // Guard with CAS to avoid concurrent resolve failures.
                    if (!resolving.compareAndSet(false, true)) return

                    val executor = Executors.newSingleThreadExecutor()
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            Log.w(TAG, "mDNS info callback registration failed: error $errorCode")
                            resolving.set(false)
                        }

                        override fun onServiceUpdated(resolved: NsdServiceInfo) {
                            val host = resolved.hostAddresses.firstOrNull()?.hostAddress
                            Log.d(
                                TAG,
                                "mDNS resolved: ${resolved.serviceName} -> $host:${resolved.port}"
                            )

                            if (host != null && !cont.isCompleted) {
                                nsdManager.unregisterServiceInfoCallback(this)
                                stopDiscovery()
                                cont.resume(
                                    listOf(
                                        DiscoveredSpeaker(
                                            ip = host,
                                            port = resolved.port,
                                            id = resolved.serviceName,
                                            displayName = resolved.serviceName
                                        )
                                    )
                                )
                            } else {
                                resolving.set(false)
                            }
                        }

                        override fun onServiceLost() {
                            Log.d(TAG, "mDNS service info lost during resolve")
                            resolving.set(false)
                        }

                        override fun onServiceInfoCallbackUnregistered() {
                            Log.d(TAG, "mDNS service info callback unregistered")
                        }
                    }

                    try {
                        nsdManager.registerServiceInfoCallback(
                            serviceInfo,
                            executor,
                            callback
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "registerServiceInfoCallback threw", e)
                        resolving.set(false)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "mDNS service lost: ${serviceInfo.serviceName}")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "mDNS discovery stopped")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "mDNS start failed: error $errorCode")
                    if (!cont.isCompleted) {
                        cont.resume(emptyList())
                    }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "mDNS stop failed: error $errorCode")
                }
            }

            cont.invokeOnCancellation {
                Log.d(TAG, "mDNS discovery cancelled")
                stopDiscovery()
            }

            try {
                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mDNS discovery", e)
                if (!cont.isCompleted) {
                    cont.resume(emptyList())
                }
            }
        }
}
