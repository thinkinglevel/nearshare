package com.pcmobilelink.nearshare.receiver

import com.pcmobilelink.nearshare.discovery.NearShareDiscoveryProtocol
import com.pcmobilelink.nearshare.discovery.NearShareDiscoveryResponse
import com.pcmobilelink.nearshare.discovery.UdpBroadcastAddresses
import com.pcmobilelink.nearshare.pairing.PairingEndpointCandidate
import com.pcmobilelink.nearshare.storage.PairedPcRecord
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import org.json.JSONObject

class AndroidReceiveEndpointDiscoveryClient(
    private val timeoutMillis: Int = 1_500,
    private val discoveryPort: Int = NearShareDiscoveryProtocol.DISCOVERY_PORT,
    private val diagnostics: (String) -> Unit = {},
) {
    fun discover(record: PairedPcRecord): List<NearShareDiscoveryResponse> {
        val requestBytes = JSONObject()
            .put("type", AndroidReceiveDiscoveryResponder.REQUEST_TYPE)
            .put("pairedDeviceId", record.pcDeviceId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val responses = linkedMapOf<String, NearShareDiscoveryResponse>()

        DatagramSocket().use { socket ->
            socket.broadcast = true
            val broadcastAddresses = UdpBroadcastAddresses.resolve()
            diagnostics("Android receive discovery sending broadcasts=${broadcastAddresses.size} pairedDeviceId=${record.pcDeviceId.take(8)}")
            broadcastAddresses.forEach { address ->
                runCatching {
                    socket.send(DatagramPacket(requestBytes, requestBytes.size, address, discoveryPort))
                    diagnostics("Android receive discovery sent to ${address.hostAddress}:$discoveryPort")
                }.onFailure { exception ->
                    diagnostics("Android receive discovery send failed to ${address.hostAddress}: ${exception.message ?: "unknown"}")
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    break
                }

                socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val buffer = ByteArray(MAX_DATAGRAM_BYTES)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }

                val response = parseResponse(
                    rawJson = String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
                    record = record,
                ) ?: continue
                diagnostics("Android receive discovery accepted ${response.endpoints.joinToString { "${it.host}:${it.port}" }}")
                val key = response.endpoints.joinToString(";") { endpoint -> "${endpoint.host}:${endpoint.port}" }
                responses[key] = response
            }
        }

        diagnostics("Android receive discovery completed candidates=${responses.size}")
        return responses.values.toList()
    }

    private fun parseResponse(rawJson: String, record: PairedPcRecord): NearShareDiscoveryResponse? {
        return runCatching {
            val json = JSONObject(rawJson)
            if (json.optString("type") != AndroidReceiveDiscoveryResponder.RESPONSE_TYPE) {
                return null
            }
            if (!json.optString("pairedDeviceId").equals(record.pcDeviceId, ignoreCase = true)) {
                return null
            }

            val fingerprint = json.optString("receiveTlsCertificateSha256")
            if (!fingerprint.equals(record.tlsCertificateSha256, ignoreCase = true)) {
                return null
            }

            val endpointJson = json.optJSONObject("endpoint") ?: return null
            val host = endpointJson.optString("host")
            val port = endpointJson.optInt("port", -1)
            if (host.isBlank() || port !in 1..65535) {
                return null
            }

            NearShareDiscoveryResponse(
                pcName = json.optString("deviceName").ifBlank { record.pcName },
                tlsCertificateSha256 = fingerprint,
                endpoints = listOf(PairingEndpointCandidate(host, port)),
                serverTimeUnixSeconds = json.optLong("serverTimeUnixSeconds"),
            )
        }.getOrNull()
    }

    private companion object {
        private const val MAX_DATAGRAM_BYTES = 8192
    }
}
