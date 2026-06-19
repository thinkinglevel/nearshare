package com.pcmobilelink.nearshare.pairing

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object PairingErrorMessage {
    private const val REACHABILITY_MESSAGE =
        "Could not reach the Windows PC. Make sure both devices are on the same Wi-Fi or hotspot, NearShare is open on Windows, and Windows Firewall allows NearShare on this network."

    fun from(exception: Throwable): String {
        val root = rootCause(exception)
        return when (root) {
            is SocketTimeoutException,
            is ConnectException,
            is NoRouteToHostException,
            is UnknownHostException -> REACHABILITY_MESSAGE
            else -> root.message?.takeIf { it.isNotBlank() } ?: "Pairing failed. Try scanning the Windows QR code again."
        }
    }

    private fun rootCause(exception: Throwable): Throwable {
        var current = exception
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }
}
