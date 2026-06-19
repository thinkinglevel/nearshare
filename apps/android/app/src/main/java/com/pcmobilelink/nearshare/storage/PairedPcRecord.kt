package com.pcmobilelink.nearshare.storage

import com.pcmobilelink.nearshare.pairing.PairingEndpointCandidate

data class PairedPcRecord(
    val pcDeviceId: String,
    val pcName: String,
    val endpoints: List<PairingEndpointCandidate>,
    val tlsCertificateSha256: String,
    val sharedSecret: String,
    val pairedAtUnixTimeSeconds: Long,
)
