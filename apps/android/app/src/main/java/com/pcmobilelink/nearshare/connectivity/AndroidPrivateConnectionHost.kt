package com.pcmobilelink.nearshare.connectivity

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.SecureRandom

class AndroidPrivateConnectionHost(
    context: Context,
    private val stopped: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val random = SecureRandom()
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    val isActive: Boolean
        get() = reservation != null

    fun start(callback: (Result<PrivateConnectionOffer>) -> Unit) {
        if (reservation != null) {
            callback(Result.failure(IllegalStateException("Private connection is already active.")))
            return
        }

        val createdAt = System.currentTimeMillis() / 1000
        runCatching {
            wifiManager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(activeReservation: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = activeReservation
                        val details = hotspotDetails(activeReservation)
                        if (details.connectionName.isBlank()) {
                            activeReservation.close()
                            reservation = null
                            callback(Result.failure(IllegalStateException("Android did not return private connection details.")))
                            return
                        }

                        callback(
                            Result.success(
                                PrivateConnectionOffer(
                                    connectionName = details.connectionName,
                                    password = details.password,
                                    code = createCode(),
                                    createdAtUnixTimeSeconds = createdAt,
                                    expiresAtUnixTimeSeconds = createdAt + OfferLifetimeSeconds,
                                ),
                            ),
                        )
                    }

                    override fun onStopped() {
                        reservation = null
                        stopped()
                    }

                    override fun onFailed(reason: Int) {
                        reservation = null
                        callback(Result.failure(IllegalStateException(failureMessage(reason))))
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }.onFailure { exception ->
            reservation = null
            callback(Result.failure(exception))
        }
    }

    fun stop() {
        reservation?.close()
        reservation = null
    }

    private fun hotspotDetails(activeReservation: WifiManager.LocalOnlyHotspotReservation): HotspotDetails {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fromSoftApConfiguration(activeReservation.softApConfiguration)
        } else {
            @Suppress("DEPRECATION")
            fromWifiConfiguration(activeReservation.wifiConfiguration)
        }
    }

    private fun fromSoftApConfiguration(configuration: SoftApConfiguration): HotspotDetails {
        return HotspotDetails(
            connectionName = configuration.ssid.orEmpty(),
            password = configuration.passphrase.orEmpty(),
        )
    }

    private fun fromWifiConfiguration(configuration: WifiConfiguration?): HotspotDetails {
        return HotspotDetails(
            connectionName = configuration?.SSID?.trim('"').orEmpty(),
            password = configuration?.preSharedKey?.trim('"').orEmpty(),
        )
    }

    private fun createCode(): String {
        return PrivateConnectionSecurityCode.create(random)
    }

    private fun failureMessage(reason: Int): String {
        return when (reason) {
            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "Android could not find a Wi-Fi channel for the private connection."
            WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "Android could not create the private connection."
            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Turn off the current hotspot or Wi-Fi sharing mode, then try again."
            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "This device or mobile plan does not allow app-created private connections."
            else -> "Android could not create the private connection. Reason: $reason"
        }
    }

    private data class HotspotDetails(
        val connectionName: String,
        val password: String,
    )

    private companion object {
        private const val OfferLifetimeSeconds = 10 * 60L
    }
}
