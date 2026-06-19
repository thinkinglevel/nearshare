package com.pcmobilelink.nearshare.pairing

data class PairingRequestReceipt(
    val requestId: String,
    val status: String,
    val message: String,
)

data class PairingRequestResult(
    val requestId: String,
    val status: String,
    val deviceId: String?,
    val deviceName: String?,
    val sharedSecret: String?,
    val message: String?,
)
