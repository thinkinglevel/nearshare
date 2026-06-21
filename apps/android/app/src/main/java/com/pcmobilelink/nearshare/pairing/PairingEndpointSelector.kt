package com.pcmobilelink.nearshare.pairing

object PairingEndpointSelector {
    fun pairingRequestsUrl(payload: PairingPayload): String {
        return pairingRequestsUrls(payload).first()
    }

    fun pairingRequestResultUrl(payload: PairingPayload, requestId: String): String {
        return pairingRequestResultUrls(payload, requestId).first()
    }

    fun pairingRequestsUrls(payload: PairingPayload): List<String> {
        return baseUrls(payload).map { baseUrl -> "$baseUrl/nearshare/pairing/requests" }
    }

    fun pairingRequestResultUrls(payload: PairingPayload, requestId: String): List<String> {
        require(requestId.isNotBlank()) { "Pairing request ID cannot be empty." }
        return pairingRequestsUrls(payload).map { url -> "$url/$requestId" }
    }

    private fun baseUrls(payload: PairingPayload): List<String> {
        require(payload.transport == "https") { "Unsupported pairing transport: ${payload.transport}." }
        require(payload.endpoints.isNotEmpty()) { "Pairing payload must include at least one endpoint." }
        return payload.endpoints.map { endpoint -> "https://${formatHost(endpoint.host)}:${endpoint.port}" }
    }

    private fun formatHost(host: String): String {
        val trimmed = host.trim()
        require(trimmed.isNotEmpty()) { "Pairing endpoint host cannot be empty." }

        return if (trimmed.contains(':') && !trimmed.startsWith('[')) {
            "[$trimmed]"
        } else {
            trimmed
        }
    }
}
