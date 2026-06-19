package com.pcmobilelink.nearshare.pairing

import com.pcmobilelink.nearshare.discovery.NearShareDiscoveryProtocol
import com.pcmobilelink.nearshare.discovery.UdpBroadcastAddresses
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import org.json.JSONObject

class PairingOfferDiscoveryClient(
    private val timeoutMillis: Int = 4_000,
) {
    fun resolve(shortCode: String): PairingPayload {
        val normalized = PairingShortCode.normalize(shortCode)
        require(PairingShortCode.isValid(normalized)) {
            "Enter the 9-character pairing code shown on the other device."
        }

        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMillis

            val request = JSONObject()
                .put("type", AndroidPairingOfferDiscoveryResponder.REQUEST_TYPE)
                .put("shortCode", normalized)
                .toString()
                .toByteArray(Charsets.UTF_8)

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

            val buffer = ByteArray(MAX_DATAGRAM_BYTES)
            try {
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    parseResponse(packet.data.copyOf(packet.length), normalized)?.let { return it }
                }
            } catch (_: SocketTimeoutException) {
                throw IllegalStateException("Could not find that pairing code. Join the same Wi-Fi, or create and join a private connection first.")
            }
        }
    }

    private fun parseResponse(responseBytes: ByteArray, expectedShortCode: String): PairingPayload? {
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
            payload
        }.getOrNull()
    }

    private companion object {
        private const val MAX_DATAGRAM_BYTES = 8192
    }
}
