package com.pcmobilelink.nearshare.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object PairedDeviceRequestSignature {
    fun createSignatureInput(
        method: String,
        pathAndQuery: String,
        timestampUnixTimeSeconds: Long,
        nonce: String,
        body: ByteArray,
    ): String {
        require(method.isNotBlank()) { "HTTP method cannot be empty." }
        require(pathAndQuery.isNotBlank()) { "Path cannot be empty." }
        require(nonce.isNotBlank()) { "Nonce cannot be empty." }

        val bodyHash = createBodyHash(body)
        return createSignatureInputFromBodyHash(
            method = method,
            pathAndQuery = pathAndQuery,
            timestampUnixTimeSeconds = timestampUnixTimeSeconds,
            nonce = nonce,
            bodySha256Base64Url = bodyHash,
        )
    }

    fun sign(
        sharedSecret: String,
        method: String,
        pathAndQuery: String,
        timestampUnixTimeSeconds: Long,
        nonce: String,
        body: ByteArray,
    ): String {
        val bodyHash = createBodyHash(body)
        return signBodyHash(
            sharedSecret = sharedSecret,
            method = method,
            pathAndQuery = pathAndQuery,
            timestampUnixTimeSeconds = timestampUnixTimeSeconds,
            nonce = nonce,
            bodySha256Base64Url = bodyHash,
        )
    }

    fun createBodyHash(body: ByteArray): String {
        return encodeBase64Url(MessageDigest.getInstance("SHA-256").digest(body))
    }

    fun createSignatureInputFromBodyHash(
        method: String,
        pathAndQuery: String,
        timestampUnixTimeSeconds: Long,
        nonce: String,
        bodySha256Base64Url: String,
    ): String {
        require(method.isNotBlank()) { "HTTP method cannot be empty." }
        require(pathAndQuery.isNotBlank()) { "Path cannot be empty." }
        require(nonce.isNotBlank()) { "Nonce cannot be empty." }
        require(bodySha256Base64Url.isNotBlank()) { "Body hash cannot be empty." }
        return listOf(
            method.uppercase(),
            pathAndQuery,
            timestampUnixTimeSeconds.toString(),
            nonce,
            bodySha256Base64Url,
        ).joinToString(separator = "\n")
    }

    fun signBodyHash(
        sharedSecret: String,
        method: String,
        pathAndQuery: String,
        timestampUnixTimeSeconds: Long,
        nonce: String,
        bodySha256Base64Url: String,
    ): String {
        val key = decodeBase64Url(sharedSecret)
        val input = createSignatureInputFromBodyHash(
            method = method,
            pathAndQuery = pathAndQuery,
            timestampUnixTimeSeconds = timestampUnixTimeSeconds,
            nonce = nonce,
            bodySha256Base64Url = bodySha256Base64Url,
        )
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return encodeBase64Url(mac.doFinal(input.toByteArray(Charsets.UTF_8)))
    }

    fun verify(
        sharedSecret: String,
        method: String,
        pathAndQuery: String,
        timestampUnixTimeSeconds: Long,
        nonce: String,
        body: ByteArray,
        signature: String,
        nowUnixTimeSeconds: Long,
        allowedClockSkewSeconds: Long,
    ): Boolean {
        if (signature.isBlank() || allowedClockSkewSeconds < 0) {
            return false
        }
        if (abs(nowUnixTimeSeconds - timestampUnixTimeSeconds) > allowedClockSkewSeconds) {
            return false
        }

        return runCatching {
            val expected = sign(
                sharedSecret = sharedSecret,
                method = method,
                pathAndQuery = pathAndQuery,
                timestampUnixTimeSeconds = timestampUnixTimeSeconds,
                nonce = nonce,
                body = body,
            )
            MessageDigest.isEqual(
                expected.toByteArray(Charsets.US_ASCII),
                signature.toByteArray(Charsets.US_ASCII),
            )
        }.getOrDefault(false)
    }

    fun createNonce(byteCount: Int = 16): String {
        require(byteCount > 0) { "Nonce byte count must be positive." }
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return encodeBase64Url(bytes)
    }

    fun encodeBase64Url(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun decodeBase64Url(value: String): ByteArray {
        require(value.isNotBlank()) { "Base64url value cannot be empty." }
        return Base64.getUrlDecoder().decode(value)
    }
}
