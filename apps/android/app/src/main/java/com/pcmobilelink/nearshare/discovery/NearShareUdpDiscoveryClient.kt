package com.pcmobilelink.nearshare.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

class NearShareUdpDiscoveryClient(
    private val timeoutMillis: Int = 1_500,
    private val discoveryPort: Int = NearShareDiscoveryProtocol.DISCOVERY_PORT,
) {
    fun discover(tlsCertificateSha256: String): List<NearShareDiscoveryResponse> {
        val requestBytes = NearShareDiscoveryProtocol
            .requestJson(tlsCertificateSha256)
            .toByteArray(Charsets.UTF_8)
        val responses = linkedMapOf<String, NearShareDiscoveryResponse>()

        DatagramSocket().use { socket ->
            socket.broadcast = true
            UdpBroadcastAddresses.resolve().forEach { address ->
                runCatching {
                    socket.send(DatagramPacket(requestBytes, requestBytes.size, address, discoveryPort))
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    break
                }

                socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val buffer = ByteArray(8 * 1024)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }

                val raw = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val response = NearShareDiscoveryProtocol.parseResponse(raw, tlsCertificateSha256) ?: continue
                val key = response.endpoints.joinToString(";") { endpoint -> "${endpoint.host}:${endpoint.port}" }
                responses[key] = response
            }
        }

        return responses.values.toList()
    }
}
