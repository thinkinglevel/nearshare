package com.pcmobilelink.nearshare.discovery

import com.pcmobilelink.nearshare.pairing.PairingEndpointCandidate
import org.json.JSONArray
import org.json.JSONObject

data class NearShareDiscoveryResponse(
    val pcName: String,
    val tlsCertificateSha256: String,
    val endpoints: List<PairingEndpointCandidate>,
    val serverTimeUnixSeconds: Long,
)

object NearShareDiscoveryProtocol {
    const val DISCOVERY_PORT = 53318
    private const val REQUEST_TYPE = "nearshare.discovery.request.v1"
    private const val RESPONSE_TYPE = "nearshare.discovery.response.v1"

    fun requestJson(tlsCertificateSha256: String): String {
        require(tlsCertificateSha256.isNotBlank()) { "TLS certificate fingerprint cannot be empty." }
        return JSONObject()
            .put("type", REQUEST_TYPE)
            .put("tlsCertificateSha256", tlsCertificateSha256)
            .toString()
    }

    fun parseResponse(rawJson: String, expectedTlsCertificateSha256: String): NearShareDiscoveryResponse? {
        if (expectedTlsCertificateSha256.isBlank()) {
            return null
        }

        return runCatching {
            val json = JSONObject(rawJson)
            if (json.optString("type") != RESPONSE_TYPE) {
                return null
            }

            val fingerprint = json.optString("tlsCertificateSha256")
            if (!fingerprint.equals(expectedTlsCertificateSha256, ignoreCase = true)) {
                return null
            }

            val endpointsJson = json.optJSONArray("endpoints") ?: return null
            val endpoints = endpointsJson.toEndpoints()
            if (endpoints.isEmpty()) {
                return null
            }

            NearShareDiscoveryResponse(
                pcName = json.optString("pcName").ifBlank { "Windows PC" },
                tlsCertificateSha256 = fingerprint,
                endpoints = endpoints,
                serverTimeUnixSeconds = json.optLong("serverTimeUnixSeconds"),
            )
        }.getOrNull()
    }

    private fun JSONArray.toEndpoints(): List<PairingEndpointCandidate> {
        return buildList {
            for (index in 0 until length()) {
                val endpoint = optJSONObject(index) ?: continue
                val host = endpoint.optString("host")
                val port = endpoint.optInt("port", -1)
                if (host.isNotBlank() && port in 1..65535) {
                    add(PairingEndpointCandidate(host, port))
                }
            }
        }
    }
}
