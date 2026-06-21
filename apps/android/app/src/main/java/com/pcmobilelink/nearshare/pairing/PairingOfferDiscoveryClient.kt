package com.pcmobilelink.nearshare.pairing

import com.pcmobilelink.nearshare.discovery.NearShareDiscoveryProtocol
import com.pcmobilelink.nearshare.discovery.UdpBroadcastAddresses
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import org.json.JSONObject

class PairingOfferDiscoveryClient(
    private val timeoutMillis: Int = 15_000,
) {
    fun resolve(shortCode: String): PairingPayload {
        val normalized = PairingShortCode.normalize(shortCode)
        require(PairingShortCode.isValid(normalized)) {
            "Enter the 9-character pairing code shown on the other device."
        }

        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = BroadcastIntervalMillis

            val request = JSONObject()
                .put("type", AndroidPairingOfferDiscoveryResponder.REQUEST_TYPE)
                .put("shortCode", normalized)
                .toString()
                .toByteArray(Charsets.UTF_8)

            val buffer = ByteArray(MAX_DATAGRAM_BYTES)
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                broadcastRequest(socket, request)
                while (true) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        parseResponse(
                            responseBytes = packet.data.copyOf(packet.length),
                            expectedShortCode = normalized,
                            responderHost = packet.address.hostAddress,
                        )?.let { return it }
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
            }

            throw IllegalStateException("Could not find that pairing code. Join the same Wi-Fi, or create and join a private connection first.")
        }
    }

    private fun broadcastRequest(socket: DatagramSocket, request: ByteArray) {
        UdpBroadcastAddresses.resolve().forEach { address ->
            runCatching {
                socket.send(
                    DatagramPacket(
                        request,
                        request.size,
                        address,
                        NearShareDiscoveryProtocol.DISCOVERY_PORT,
                    ),
                )
            }
        }
    }

    private fun parseResponse(
        responseBytes: ByteArray,
        expectedShortCode: String,
        responderHost: String?,
    ): PairingPayload? {
        return runCatching {
            val json = JSONObject(responseBytes.toString(Charsets.UTF_8))
            if (json.optString("type") != AndroidPairingOfferDiscoveryResponder.RESPONSE_TYPE) {
                return null
            }
            if (PairingShortCode.normalize(json.optString("shortCode")) != expectedShortCode) {
                return null
            }

            val pairingUri = json.optString("pairingUri")
            if (pairingUri.isBlank()) {
                return null
            }

            val payload = PairingPayloadCodec.decode(pairingUri)
            if (PairingShortCode.normalize(payload.shortCode.orEmpty()) != expectedShortCode) {
                return null
            }
            payload.preferResponderEndpoint(responderHost)
        }.getOrNull()
    }

    private fun PairingPayload.preferResponderEndpoint(responderHost: String?): PairingPayload {
        val primaryEndpoint = endpoints.firstOrNull()
        if (primaryEndpoint == null || responderHost.isNullOrBlank()) {
            return this
        }

        val responderEndpoint = PairingEndpointCandidate(
            host = responderHost,
            port = primaryEndpoint.port,
        )
        val preferredEndpoints = buildList {
            add(responderEndpoint)
            endpoints.forEach { endpoint ->
                if (!endpoint.host.equals(responderEndpoint.host, ignoreCase = true) ||
                    endpoint.port != responderEndpoint.port
                ) {
                    add(endpoint)
                }
            }
        }
        return copy(endpoints = preferredEndpoints)
    }

    private companion object {
        private const val BroadcastIntervalMillis = 750
        private const val MAX_DATAGRAM_BYTES = 8192
    }
}
