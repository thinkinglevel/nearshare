package com.pcmobilelink.nearshare.transfer

import com.pcmobilelink.nearshare.security.PairedDeviceRequestSignature
import com.pcmobilelink.nearshare.security.PinnedCertificateTls
import com.pcmobilelink.nearshare.storage.PairedPcRecord
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

class PairedPcReachabilityClient(
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 10_000,
) {
    fun checkReachability(record: PairedPcRecord): PairedPcReachabilityResult {
        val urlText = reachabilityUrl(record)
        val url = URL(urlText)
        val connection = openPinnedConnection(url, record.tlsCertificateSha256)
        val timestamp = System.currentTimeMillis() / 1_000L
        val nonce = PairedDeviceRequestSignature.createNonce()
        val pathAndQuery = url.path + (url.query?.let { "?$it" } ?: "")
        val headers = signedHeaders(
            pcDeviceId = record.pcDeviceId,
            sharedSecret = record.sharedSecret,
            method = "GET",
            pathAndQuery = pathAndQuery,
            timestampUnixTimeSeconds = timestamp,
            nonce = nonce,
        )

        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

        val responseBody = readResponseOrThrow(connection, expectedCode = HttpsURLConnection.HTTP_OK)
        val json = JSONObject(responseBody)
        return PairedPcReachabilityResult(
            status = json.getString("status"),
            pcDeviceId = json.getString("deviceId"),
            serverTimeUnixSeconds = json.getLong("serverTimeUnixSeconds"),
        )
    }

    private fun openPinnedConnection(url: URL, expectedFingerprint: String): HttpsURLConnection {
        val pinnedTls = PinnedCertificateTls(expectedFingerprint)
        return (url.openConnection() as HttpsURLConnection).apply {
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
            throw IllegalStateException("Reachability check failed with HTTP $statusCode: $body")
        }

        return body
    }

    private fun readFully(stream: InputStream): String {
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }

    companion object {
        fun reachabilityUrl(record: PairedPcRecord): String {
            val endpoint = record.endpoints.firstOrNull()
                ?: throw IllegalArgumentException("Paired PC must include at least one endpoint.")
            return "https://${formatHost(endpoint.host)}:${endpoint.port}/nearshare/paired-devices/${record.pcDeviceId}/reachability"
        }

        fun signedHeaders(
            pcDeviceId: String,
            sharedSecret: String,
            method: String,
            pathAndQuery: String,
            timestampUnixTimeSeconds: Long,
            nonce: String,
        ): Map<String, String> {
            val signature = PairedDeviceRequestSignature.sign(
                sharedSecret = sharedSecret,
                method = method,
                pathAndQuery = pathAndQuery,
                timestampUnixTimeSeconds = timestampUnixTimeSeconds,
                nonce = nonce,
                body = ByteArray(0),
            )
            return mapOf(
                "X-NearShare-Device-Id" to pcDeviceId,
                "X-NearShare-Timestamp" to timestampUnixTimeSeconds.toString(),
                "X-NearShare-Nonce" to nonce,
                "X-NearShare-Signature" to signature,
            )
        }

        private fun formatHost(host: String): String {
            val trimmed = host.trim()
            require(trimmed.isNotEmpty()) { "Paired PC endpoint host cannot be empty." }
            return if (trimmed.contains(':') && !trimmed.startsWith('[')) {
                "[$trimmed]"
            } else {
                trimmed
            }
        }
    }
}

data class PairedPcReachabilityResult(
    val status: String,
    val pcDeviceId: String,
    val serverTimeUnixSeconds: Long,
)
