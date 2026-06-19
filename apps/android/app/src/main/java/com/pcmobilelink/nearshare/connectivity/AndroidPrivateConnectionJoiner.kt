package com.pcmobilelink.nearshare.connectivity

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build

class AndroidPrivateConnectionJoiner(context: Context) {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val isConnected: Boolean
        get() = networkCallback != null

    @SuppressLint("MissingPermission")
    fun connect(
        offer: PrivateConnectionOffer,
        callback: (Result<PrivateConnectionJoinResult>) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            callback(Result.failure(IllegalStateException("Android 10 or newer is required to join a private connection inside NearShare.")))
            return
        }

        disconnect()

        runCatching {
            val wifiSpecifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid(offer.connectionName)
            if (offer.password.isNotBlank()) {
                wifiSpecifierBuilder.setWpa2Passphrase(offer.password)
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(wifiSpecifierBuilder.build())
                .build()

            var completed = false
            val currentCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connectivityManager.bindProcessToNetwork(network)
                    if (!completed) {
                        completed = true
                        callback(
                            Result.success(
                                PrivateConnectionJoinResult(
                                    connectionName = offer.connectionName,
                                    code = offer.code,
                                ),
                            ),
                        )
                    }
                }

                override fun onUnavailable() {
                    if (!completed) {
                        completed = true
                        clearIfCurrent(this)
                        callback(Result.failure(IllegalStateException("Could not join this private connection.")))
                    }
                }

                override fun onLost(network: Network) {
                    clearIfCurrent(this)
                }
            }

            networkCallback = currentCallback
            connectivityManager.requestNetwork(request, currentCallback, RequestTimeoutMilliseconds)
        }.onFailure { exception ->
            disconnect()
            callback(Result.failure(exception))
        }
    }

    fun disconnect() {
        networkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        connectivityManager.bindProcessToNetwork(null)
    }

    private fun clearIfCurrent(callback: ConnectivityManager.NetworkCallback) {
        if (networkCallback === callback) {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            networkCallback = null
            connectivityManager.bindProcessToNetwork(null)
        }
    }

    private companion object {
        private const val RequestTimeoutMilliseconds = 30_000
    }
}

data class PrivateConnectionJoinResult(
    val connectionName: String,
    val code: String,
)
