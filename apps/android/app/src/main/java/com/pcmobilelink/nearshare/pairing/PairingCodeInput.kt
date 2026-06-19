package com.pcmobilelink.nearshare.pairing

object PairingCodeInput {
    fun decode(rawCode: String): PairingPayload {
        val normalized = rawCode.trim()
        require(normalized.isNotEmpty()) { "Pairing code cannot be empty." }
        return if (normalized.startsWith("nearshare://pair", ignoreCase = true)) {
            PairingPayloadCodec.decode(normalized)
        } else {
            PairingOfferDiscoveryClient().resolve(normalized)
        }
    }
}
