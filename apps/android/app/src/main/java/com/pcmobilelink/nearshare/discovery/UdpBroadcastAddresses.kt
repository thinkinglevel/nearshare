package com.pcmobilelink.nearshare.discovery

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

internal object UdpBroadcastAddresses {
    fun resolve(): List<InetAddress> {
        val addresses = linkedSetOf<InetAddress>()
        addresses += InetAddress.getByName("255.255.255.255")

        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { networkInterface ->
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    return@forEach
                }

                networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast is Inet4Address) {
                        addresses += broadcast
                    }
                }
            }
        }

        return addresses.toList()
    }
}
