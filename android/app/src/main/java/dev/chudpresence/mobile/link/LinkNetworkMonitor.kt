package dev.chudpresence.mobile.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal class LinkNetworkMonitor(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun observe(): Flow<Network?> = callbackFlow {
        val lock = Any()
        val available = mutableSetOf<Network>()
        val candidates = linkedSetOf<Network>()
        var current: Network? = null

        // Called while holding lock. Keep the existing choice while it remains suitable.
        fun publishSelection() {
            val next = current?.takeIf { it in candidates } ?: candidates.firstOrNull()
            if (next != current) {
                current = next
                trySend(next)
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lock) {
                    available.add(network)
                    candidates.add(network) // The registered request already matches its transport.
                    publishSelection()
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                synchronized(lock) {
                    if (network !in available) return
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    ) candidates.add(network) else candidates.remove(network)
                    publishSelection()
                }
            }

            override fun onLost(network: Network) {
                synchronized(lock) {
                    available.remove(network)
                    candidates.remove(network)
                    publishSelection()
                }
            }
        }

        val request = NetworkRequest.Builder().apply {
            // Internet validation and the default trusted/restricted filters would omit
            // some local-only Wi-Fi networks. API 29 lacks clearCapabilities().
            if (Build.VERSION.SDK_INT >= 30) clearCapabilities() else {
                removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        }.build()

        trySend(null)
        connectivity.registerNetworkCallback(request, callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.buffer(Channel.CONFLATED).distinctUntilChanged()
}
