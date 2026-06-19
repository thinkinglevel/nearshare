package com.pcmobilelink.nearshare.pairing

object PairingEndpointSelector {
    fun pairingRequestsUrl(payload: PairingPayload): String {
        return "${primaryBaseUrl(payload)}/nearshare/pairing/requests"
    }

    fun pairingRequestResultUrl(payload: PairingPayload, requestId: String): String {
        require(requestId.isNotBlank()) { "Pairing request ID cannot be empty." }
        return "${pairingRequestsUrl(payload)}/$requestId"
    }

    private fun primaryBaseUrl(payload: PairingPayload): String {
        require(payload.transport == "https") { "Unsupported pairing transport: ${payload.transport}." }
        val endpoint = payload.endpoints.firstOrNull()
            ?: throw IllegalArgumentException("Pairing payload must include at least one endpoint.")
        return "https://${formatHost(endpoint.host)}:${endpoint.port}"
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
