package com.pcmobilelink.nearshare.connectivity

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject

object PrivateConnectionOfferCodec {
    private const val Prefix = "nearshare://private-connection?payload="

    fun encode(offer: PrivateConnectionOffer): String {
        val root = JSONObject()
            .put("version", offer.version)
            .put("connectionName", offer.connectionName)
            .put("password", offer.password)
            .put("code", offer.code)
            .put("createdAtUnixTimeSeconds", offer.createdAtUnixTimeSeconds)
            .put("expiresAtUnixTimeSeconds", offer.expiresAtUnixTimeSeconds)
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(root.toString().toByteArray(StandardCharsets.UTF_8))
        return Prefix + encoded
    }

    fun canDecode(rawOffer: String): Boolean {
        return rawOffer.trim().startsWith(Prefix, ignoreCase = true)
    }

    fun decode(
        rawOffer: String,
        currentUnixTimeSeconds: Long = System.currentTimeMillis() / 1000,
    ): PrivateConnectionOffer {
        val normalizedOffer = rawOffer.trim()
        require(canDecode(normalizedOffer)) { "This is not a NearShare private connection code." }

        val encodedPayload = normalizedOffer.substring(Prefix.length)
        require(encodedPayload.isNotBlank()) { "Private connection code is missing details." }

        val payloadJson = String(
            Base64.getUrlDecoder().decode(encodedPayload),
            StandardCharsets.UTF_8,
        )
        val root = JSONObject(payloadJson)
        val version = root.optInt("version", -1)
        require(version == 1) { "Private connection code version is not supported." }

        val connectionName = root.optString("connectionName").trim()
        require(connectionName.isNotBlank()) { "Private connection name is missing." }

        val securityCode = PrivateConnectionSecurityCode.normalize(root.optString("code"))
        require(PrivateConnectionSecurityCode.isValid(securityCode)) { "Private connection security code is invalid." }

        val createdAtUnixTimeSeconds = root.optLong("createdAtUnixTimeSeconds", 0)
        val expiresAtUnixTimeSeconds = root.optLong("expiresAtUnixTimeSeconds", 0)
        require(createdAtUnixTimeSeconds > 0 && expiresAtUnixTimeSeconds > createdAtUnixTimeSeconds) {
            "Private connection expiry is invalid."
        }
        require(expiresAtUnixTimeSeconds >= currentUnixTimeSeconds) {
            "Private connection code has expired."
        }

        return PrivateConnectionOffer(
            version = version,
            connectionName = connectionName,
            password = root.optString("password"),
            code = securityCode,
            createdAtUnixTimeSeconds = createdAtUnixTimeSeconds,
            expiresAtUnixTimeSeconds = expiresAtUnixTimeSeconds,
        )
    }

    fun encodeWifiQrPayload(offer: PrivateConnectionOffer): String {
        val connectionName = escapeWifiQrField(offer.connectionName)
        if (offer.password.isBlank()) {
            return "WIFI:T:nopass;S:$connectionName;;"
        }

        val password = escapeWifiQrField(offer.password)
        return "WIFI:T:WPA;S:$connectionName;P:$password;;"
    }

    private fun escapeWifiQrField(value: String): String {
        return buildString(value.length) {
            for (character in value) {
                if (character == '\\' || character == ';' || character == ',' || character == ':' || character == '"') {
                    append('\\')
                }
                append(character)
            }
        }
    }
}
