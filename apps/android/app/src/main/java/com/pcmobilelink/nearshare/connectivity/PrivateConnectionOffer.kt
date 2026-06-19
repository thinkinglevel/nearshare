package com.pcmobilelink.nearshare.connectivity

data class PrivateConnectionOffer(
    val version: Int = 1,
    val connectionName: String,
    val password: String,
    val code: String,
    val createdAtUnixTimeSeconds: Long,
    val expiresAtUnixTimeSeconds: Long,
)
