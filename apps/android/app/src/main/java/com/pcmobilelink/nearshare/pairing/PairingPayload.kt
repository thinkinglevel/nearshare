package com.pcmobilelink.nearshare.pairing

data class PairingPayload(
    val version: Int,
    val offerId: String,
    val pcName: String,
    val endpoints: List<PairingEndpointCandidate>,
    val pairingToken: String,
    val shortCode: String? = null,
    val tlsCertificateSha256: String,
    val expiresAtUnixTimeSeconds: Long,
    val transport: String,
)
