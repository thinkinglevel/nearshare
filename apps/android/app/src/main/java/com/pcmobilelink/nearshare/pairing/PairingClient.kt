package com.pcmobilelink.nearshare.pairing

import com.pcmobilelink.nearshare.security.PinnedCertificateTls
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

class PairingClient(
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 10_000,
) {
    fun submitPairingRequest(
        payload: PairingPayload,
        deviceName: String,
        devicePublicKey: String,
        receiveEndpoints: List<PairingEndpointCandidate> = emptyList(),
        receiveTlsCertificateSha256: String? = null,
    ): PairingRequestReceipt {
        require(deviceName.isNotBlank()) { "Device name cannot be empty." }
        require(devicePublicKey.isNotBlank()) { "Device public key cannot be empty." }

        val body = pairingRequestBody(
            payload = payload,
            deviceName = deviceName,
            devicePublicKey = devicePublicKey,
            receiveEndpoints = receiveEndpoints,
            receiveTlsCertificateSha256 = receiveTlsCertificateSha256,
        )

        var lastEndpointException: IOException? = null
        PairingEndpointSelector.pairingRequestsUrls(payload).forEach { url ->
            try {
                val connection = openPinnedConnection(
                    url = url,
                    expectedFingerprint = payload.tlsCertificateSha256,
                )

                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.outputStream.use { stream ->
                    stream.write(body)
                }

                val responseBody = readResponseOrThrow(connection, expectedCode = HttpsURLConnection.HTTP_ACCEPTED)
                val json = JSONObject(responseBody)
                return PairingRequestReceipt(
                    requestId = json.getString("requestId"),
                    status = json.getString("status"),
                    message = json.optString("message"),
                )
            } catch (exception: IOException) {
                lastEndpointException = exception
            }
        }

        throw IllegalStateException("Could not reach any pairing endpoint from the pairing code.", lastEndpointException)
    }

    fun getPairingResult(payload: PairingPayload, requestId: String): PairingRequestResult {
        var lastEndpointException: IOException? = null
        PairingEndpointSelector.pairingRequestResultUrls(payload, requestId).forEach { url ->
            try {
                val connection = openPinnedConnection(
                    url = url,
                    expectedFingerprint = payload.tlsCertificateSha256,
                )
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")

                val responseBody = readResponseOrThrow(connection, expectedCode = HttpsURLConnection.HTTP_OK)
                val json = JSONObject(responseBody)
                return PairingRequestResult(
                    requestId = json.getString("requestId"),
                    status = json.getString("status"),
                    deviceId = json.optNullableString("deviceId"),
                    deviceName = json.optNullableString("deviceName"),
                    sharedSecret = json.optNullableString("sharedSecret"),
                    message = json.optNullableString("message"),
                )
            } catch (exception: IOException) {
                lastEndpointException = exception
            }
        }

        throw IllegalStateException("Could not reach any pairing endpoint while waiting for approval.", lastEndpointException)
    }

    private fun openPinnedConnection(url: String, expectedFingerprint: String): HttpsURLConnection {
        val pinnedTls = PinnedCertificateTls(expectedFingerprint)
        return (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            sslSocketFactory = pinnedTls.sslSocketFactory
            hostnameVerifier = pinnedTls.hostnameVerifier
        }
    }

    private fun readResponseOrThrow(connection: HttpsURLConnection, expectedCode: Int): String {
        val statusCode = connection.responseCode
        val body = if (statusCode == expectedCode) {
            readFully(connection.inputStream)
        } else {
            readFully(connection.errorStream ?: connection.inputStream)
        }

        if (statusCode != expectedCode) {
            throw IllegalStateException("Pairing request failed with HTTP $statusCode: $body")
        }

        return body
    }

    private fun readFully(stream: InputStream): String {
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (has(name) && !isNull(name)) optString(name) else null
    }

    companion object {
        fun pairingRequestBody(
            payload: PairingPayload,
            deviceName: String,
            devicePublicKey: String,
            receiveEndpoints: List<PairingEndpointCandidate> = emptyList(),
            receiveTlsCertificateSha256: String? = null,
        ): ByteArray {
            require(deviceName.isNotBlank()) { "Device name cannot be empty." }
            require(devicePublicKey.isNotBlank()) { "Device public key cannot be empty." }

            val json = JSONObject()
                .put("offerId", payload.offerId)
                .put("pairingToken", payload.pairingToken)
                .put("deviceName", deviceName)
                .put("devicePublicKey", devicePublicKey)

            if (receiveEndpoints.isNotEmpty()) {
                json.put(
                    "receiveEndpoints",
                    org.json.JSONArray().also { array ->
                        receiveEndpoints.forEach { endpoint ->
                            array.put(JSONObject().put("host", endpoint.host).put("port", endpoint.port))
                        }
                    },
                )
            }

            if (!receiveTlsCertificateSha256.isNullOrBlank()) {
                json.put("receiveTlsCertificateSha256", receiveTlsCertificateSha256)
            }

            return json.toString().toByteArray(Charsets.UTF_8)
        }
    }
}
