package com.pcmobilelink.nearshare.pairing

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

object PairingPayloadCodec {
    fun encode(payload: PairingPayload): String {
        validate(payload)
        val json = JSONObject()
            .put("version", payload.version)
            .put("offerId", payload.offerId)
            .put("pcName", payload.pcName)
            .put(
                "endpoints",
                JSONArray().also { array ->
                    payload.endpoints.forEach { endpoint ->
                        array.put(JSONObject().put("host", endpoint.host).put("port", endpoint.port))
                    }
                },
            )
            .put("pairingToken", payload.pairingToken)
            .put("shortCode", payload.shortCode)
            .put("tlsCertificateSha256", payload.tlsCertificateSha256)
            .put("expiresAtUnixTimeSeconds", payload.expiresAtUnixTimeSeconds)
            .put("transport", payload.transport)
        val encodedPayload = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))
        return "nearshare://pair?payload=$encodedPayload"
    }

    fun decode(uri: String): PairingPayload {
        val parsed = runCatching { URI(uri) }
            .getOrElse { throw IllegalArgumentException("Pairing payload must be a valid URI.", it) }

        if (!parsed.scheme.equals("nearshare", ignoreCase = true) ||
            !parsed.host.equals("pair", ignoreCase = true)
        ) {
            throw IllegalArgumentException("Pairing payload must use the nearshare://pair URI format.")
        }

        val encodedPayload = queryValue(parsed.rawQuery, "payload")
            ?: throw IllegalArgumentException("Pairing payload URI is missing the payload value.")

        val json = decodeBase64Url(encodedPayload)
        val root = JSONObject(json)
        val endpointsJson = root.getJSONArray("endpoints")
        val endpoints = buildList {
            for (index in 0 until endpointsJson.length()) {
                val endpoint = endpointsJson.getJSONObject(index)
                val host = endpoint.getString("host").trim()
                val port = endpoint.getInt("port")
                require(host.isNotEmpty()) { "Pairing endpoint host cannot be empty." }
                require(port in 1..65535) { "Pairing endpoint port must be between 1 and 65535." }
                add(PairingEndpointCandidate(host = host, port = port))
            }
        }

        require(endpoints.isNotEmpty()) { "Pairing payload must include at least one endpoint." }

        return PairingPayload(
            version = root.getInt("version"),
            offerId = root.getString("offerId"),
            pcName = root.getString("pcName"),
            endpoints = endpoints,
            pairingToken = root.getString("pairingToken"),
            shortCode = root.optString("shortCode").takeIf { it.isNotBlank() },
            tlsCertificateSha256 = root.getString("tlsCertificateSha256"),
            expiresAtUnixTimeSeconds = root.getLong("expiresAtUnixTimeSeconds"),
            transport = root.optString("transport", "https"),
        ).also(::validate)
    }

    private fun validate(payload: PairingPayload) {
        require(payload.version == 1) { "Unsupported pairing protocol version: ${payload.version}." }
        require(payload.offerId.isNotBlank()) { "Pairing offer ID cannot be empty." }
        require(payload.pcName.isNotBlank()) { "PC name cannot be empty." }
        require(payload.pairingToken.isNotBlank()) { "Pairing token cannot be empty." }
        payload.shortCode?.let { code ->
            require(PairingShortCode.isValid(code)) { "Pairing short code must be 9 supported characters." }
        }
        require(payload.transport == "https") { "Unsupported pairing transport: ${payload.transport}." }
        require(payload.tlsCertificateSha256.matches(Regex("^[0-9A-Fa-f]{64}$"))) {
            "TLS certificate SHA-256 fingerprint must be 64 hexadecimal characters."
        }
    }

    private fun decodeBase64Url(value: String): String {
        val bytes = runCatching { Base64.getUrlDecoder().decode(value) }
            .getOrElse { throw IllegalArgumentException("Pairing payload value is not valid base64url.", it) }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun queryValue(rawQuery: String?, key: String): String? {
        if (rawQuery.isNullOrBlank()) {
            return null
        }

        return rawQuery
            .split('&')
            .asSequence()
            .mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size != 2) {
                    null
                } else {
                    val name = URLDecoder.decode(pieces[0], StandardCharsets.UTF_8)
                    val value = URLDecoder.decode(pieces[1], StandardCharsets.UTF_8)
                    name to value
                }
            }
            .firstOrNull { (name, _) -> name == key }
            ?.second
    }
}
