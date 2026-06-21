package com.pcmobilelink.nearshare.pairing

import android.content.Context
import com.pcmobilelink.nearshare.diagnostics.NearShareDiagnostics
import com.pcmobilelink.nearshare.receiver.AndroidReceiveCertificateStore
import com.pcmobilelink.nearshare.receiver.AndroidReceiveEndpointMetadata
import com.pcmobilelink.nearshare.receiver.AndroidReceiveHttpServer
import com.pcmobilelink.nearshare.receiver.AndroidReceiveSessionManager
import com.pcmobilelink.nearshare.receiver.AndroidReceiveStorageResolver
import com.pcmobilelink.nearshare.receiver.ReceiveHttpRequest
import com.pcmobilelink.nearshare.receiver.ReceiveHttpResponse
import com.pcmobilelink.nearshare.receiver.ReceiveTransferProgress
import com.pcmobilelink.nearshare.security.PairedDeviceRequestSignature
import com.pcmobilelink.nearshare.storage.PairedPcRecord
import com.pcmobilelink.nearshare.storage.PairedPcStore
import java.io.Closeable
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class AndroidLocalPairingServer private constructor(
    private val pairedPcStore: PairedPcStore,
    private val deviceName: String,
    private val devicePublicKey: String,
    private val offerId: String,
    private val pairingToken: String,
    private val shortCode: String,
    private val expiresAtUnixTimeSeconds: Long,
    private val sessionManager: AndroidReceiveSessionManager,
    private val onPendingRequestChanged: () -> Unit,
    private val server: AndroidReceiveHttpServer,
    private val diagnostics: (String) -> Unit,
) : Closeable {
    private val pendingRequestsLock = Any()
    private val pendingRequests = mutableListOf<PendingRequest>()

    lateinit var endpoint: AndroidReceiveEndpointMetadata
        private set

    lateinit var offer: PairingPayload
        private set

    private var discoveryResponder: AndroidPairingOfferDiscoveryResponder? = null

    val encodedOffer: String
        get() = PairingPayloadCodec.encode(offer)

    fun start(): AndroidLocalPairingServer {
        endpoint = server.start()
        offer = PairingPayload(
            version = 1,
            offerId = offerId,
            pcName = deviceName,
            endpoints = listOf(PairingEndpointCandidate(endpoint.host, endpoint.port)),
            pairingToken = pairingToken,
            shortCode = shortCode,
            tlsCertificateSha256 = endpoint.tlsCertificateSha256,
            expiresAtUnixTimeSeconds = expiresAtUnixTimeSeconds,
            transport = "https",
        )
        discoveryResponder = AndroidPairingOfferDiscoveryResponder(
            shortCode = shortCode,
            deviceName = deviceName,
            pairingUri = encodedOffer,
            diagnostics = diagnostics,
        ).also { responder -> responder.start() }
        return this
    }

    fun currentPendingRequest(): LocalPairingPendingRequest? {
        synchronized(pendingRequestsLock) {
            return pendingRequests.firstOrNull { request -> request.status == STATUS_PENDING }
                ?.toPublicRequest()
        }
    }

    fun approve(requestId: String): PairedPcRecord {
        val record = synchronized(pendingRequestsLock) {
            val request = pendingRequests.firstOrNull { candidate -> candidate.requestId == requestId }
                ?: throw IllegalArgumentException("Pairing request was not found.")
            if (request.status == STATUS_REJECTED) {
                throw IllegalStateException("Rejected pairing requests cannot be approved.")
            }
            request.approvedRecord ?: createPairedRecord(request).also { approved ->
                pairedPcStore.addOrUpdate(approved)
                request.status = STATUS_APPROVED
                request.approvedRecord = approved
            }
        }
        onPendingRequestChanged()
        return record
    }

    fun reject(requestId: String): Boolean {
        val rejected = synchronized(pendingRequestsLock) {
            val request = pendingRequests.firstOrNull { candidate -> candidate.requestId == requestId }
                ?: return@synchronized false
            if (request.status == STATUS_APPROVED) {
                return@synchronized false
            }
            request.status = STATUS_REJECTED
            true
        }
        if (rejected) {
            onPendingRequestChanged()
        }
        return rejected
    }

    private fun handle(request: ReceiveHttpRequest): ReceiveHttpResponse {
        return when {
            request.method.equals("POST", ignoreCase = true) &&
                request.pathAndQuery.substringBefore('?') == PAIRING_REQUESTS_PATH -> handlePairingRequest(request)
            request.method.equals("GET", ignoreCase = true) &&
                request.pathAndQuery.substringBefore('?').startsWith(PAIRING_REQUESTS_PATH_WITH_SLASH) -> {
                handlePairingResult(request)
            }
            else -> sessionManager.handle(request)
        }
    }

    private fun handlePairingRequest(request: ReceiveHttpRequest): ReceiveHttpResponse {
        return try {
            if (nowUnixTimeSeconds() > expiresAtUnixTimeSeconds) {
                return jsonResponse(400, "expired", "This pairing code expired. Show a new code and try again.")
            }
            val json = JSONObject(request.body.toString(Charsets.UTF_8))
            require(json.getString("offerId") == offerId) { "Pairing offer ID does not match this device." }
            if (json.getString("pairingToken") != pairingToken) {
                throw SecurityException("Pairing token does not match this device.")
            }

            val requesterPublicKey = json.getString("devicePublicKey").trim()
            require(requesterPublicKey.isNotEmpty()) { "Device public key cannot be empty." }
            require(!requesterPublicKey.equals(devicePublicKey, ignoreCase = true)) {
                "This is this device's own pairing code."
            }

            val pendingRequest = PendingRequest(
                requestId = UUID.randomUUID().toString(),
                deviceName = json.getString("deviceName").trim().ifBlank { "Nearby device" },
                devicePublicKey = requesterPublicKey,
                receiveEndpoints = json.optJSONArray("receiveEndpoints").toEndpointCandidates(),
                receiveTlsCertificateSha256 = json.optString("receiveTlsCertificateSha256").trim(),
            )

            synchronized(pendingRequestsLock) {
                pendingRequests.removeAll { existing ->
                    existing.devicePublicKey == pendingRequest.devicePublicKey ||
                        (!pendingRequest.receiveTlsCertificateSha256.isNullOrBlank() &&
                            existing.receiveTlsCertificateSha256.equals(pendingRequest.receiveTlsCertificateSha256, ignoreCase = true))
                }
                pendingRequests.add(pendingRequest)
            }
            onPendingRequestChanged()

            jsonResponse(
                statusCode = 202,
                status = STATUS_PENDING,
                message = "Confirm the pairing request on this device.",
                extra = JSONObject().put("requestId", pendingRequest.requestId),
            )
        } catch (exception: SecurityException) {
            jsonResponse(401, "unauthorized", exception.message ?: "Pairing request was not authenticated.")
        } catch (exception: IllegalArgumentException) {
            jsonResponse(400, "bad_request", exception.message ?: "Pairing request was invalid.")
        } catch (exception: Exception) {
            jsonResponse(500, "failed", exception.message ?: "Pairing request failed.")
        }
    }

    private fun handlePairingResult(request: ReceiveHttpRequest): ReceiveHttpResponse {
        val requestId = request.pathAndQuery.substringBefore('?').removePrefix(PAIRING_REQUESTS_PATH_WITH_SLASH)
        val pendingRequest = synchronized(pendingRequestsLock) {
            pendingRequests.firstOrNull { candidate -> candidate.requestId == requestId }
        } ?: return jsonResponse(404, "not_found", "Pairing request was not found.")

        val body = JSONObject()
            .put("requestId", pendingRequest.requestId)
            .put("status", pendingRequest.status)
            .put(
                "message",
                when (pendingRequest.status) {
                    STATUS_APPROVED -> "Pairing was approved."
                    STATUS_REJECTED -> "Pairing was rejected."
                    else -> "Waiting for confirmation on this device."
                },
            )

        pendingRequest.approvedRecord?.let { record ->
            body
                .put("deviceId", record.pcDeviceId)
                .put("deviceName", deviceName)
                .put("sharedSecret", record.sharedSecret)
        }

        return ReceiveHttpResponse(statusCode = 200, bodyText = body.toString())
    }

    private fun createPairedRecord(request: PendingRequest): PairedPcRecord {
        return PairedPcRecord(
            pcDeviceId = UUID.randomUUID().toString(),
            pcName = request.deviceName,
            endpoints = request.receiveEndpoints,
            tlsCertificateSha256 = request.receiveTlsCertificateSha256.orEmpty(),
            sharedSecret = PairedDeviceRequestSignature.encodeBase64Url(SecureRandomHolder.nextBytes(32)),
            pairedAtUnixTimeSeconds = nowUnixTimeSeconds(),
        )
    }

    override fun close() {
        discoveryResponder?.close()
        discoveryResponder = null
        server.close()
    }

    private fun JSONArray?.toEndpointCandidates(): List<PairingEndpointCandidate> {
        if (this == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until length()) {
                val endpoint = getJSONObject(index)
                val host = endpoint.getString("host").trim()
                val port = endpoint.getInt("port")
                require(host.isNotEmpty()) { "Receive endpoint host cannot be empty." }
                require(port in 1..65535) { "Receive endpoint port must be between 1 and 65535." }
                add(PairingEndpointCandidate(host, port))
            }
        }
    }

    private fun jsonResponse(
        statusCode: Int,
        status: String,
        message: String,
        extra: JSONObject = JSONObject(),
    ): ReceiveHttpResponse {
        val body = JSONObject()
            .put("status", status)
            .put("message", message)
        extra.keys().forEach { key -> body.put(key, extra.get(key)) }
        return ReceiveHttpResponse(statusCode = statusCode, bodyText = body.toString())
    }

    private fun PendingRequest.toPublicRequest(): LocalPairingPendingRequest {
        return LocalPairingPendingRequest(
            requestId = requestId,
            deviceName = deviceName,
            devicePublicKey = devicePublicKey,
        )
    }

    private data class PendingRequest(
        val requestId: String,
        val deviceName: String,
        val devicePublicKey: String,
        val receiveEndpoints: List<PairingEndpointCandidate>,
        val receiveTlsCertificateSha256: String?,
        var status: String = STATUS_PENDING,
        var approvedRecord: PairedPcRecord? = null,
    )

    companion object {
        private const val PAIRING_REQUESTS_PATH = "/nearshare/pairing/requests"
        private const val PAIRING_REQUESTS_PATH_WITH_SLASH = "$PAIRING_REQUESTS_PATH/"
        private const val STATUS_PENDING = "pending_confirmation"
        private const val STATUS_APPROVED = "approved"
        private const val STATUS_REJECTED = "rejected"

        fun start(
            context: Context,
            deviceName: String,
            devicePublicKey: String,
            lifetimeSeconds: Long,
            progressChanged: (ReceiveTransferProgress) -> Unit,
            onPendingRequestChanged: () -> Unit,
        ): AndroidLocalPairingServer {
            val appContext = context.applicationContext
            val pairedPcStore = PairedPcStore(appContext)
            lateinit var localServer: AndroidLocalPairingServer
            val certificate = AndroidReceiveCertificateStore(appContext).loadOrCreate()
            val sessionManager = AndroidReceiveSessionManager(
                pairedPcLookup = { pcDeviceId ->
                    pairedPcStore.loadAll().firstOrNull { record ->
                        record.pcDeviceId.equals(pcDeviceId, ignoreCase = true)
                    }
                },
                storage = AndroidReceiveStorageResolver(appContext),
                tempDirectory = File(appContext.cacheDir, "local-pairing-receive-sessions"),
                progressChanged = progressChanged,
            )
            val expiresAt = nowUnixTimeSeconds() + lifetimeSeconds
            val offerId = UUID.randomUUID().toString()
            val pairingToken = PairedDeviceRequestSignature.createNonce(32)
            val shortCode = PairingShortCode.generate()
            val server = AndroidReceiveHttpServer(
                certificate = certificate,
                requestHandler = { request -> localServer.handle(request) },
                diagnostics = { message -> NearShareDiagnostics.info(appContext, message) },
            )
            localServer = AndroidLocalPairingServer(
                pairedPcStore = pairedPcStore,
                deviceName = deviceName.ifBlank { "Android device" },
                devicePublicKey = devicePublicKey,
                offerId = offerId,
                pairingToken = pairingToken,
                shortCode = shortCode,
                expiresAtUnixTimeSeconds = expiresAt,
                sessionManager = sessionManager,
                onPendingRequestChanged = onPendingRequestChanged,
                server = server,
                diagnostics = { message -> NearShareDiagnostics.info(appContext, message) },
            )
            return localServer.start()
        }

        private fun nowUnixTimeSeconds(): Long = System.currentTimeMillis() / 1000L
    }
}

data class LocalPairingPendingRequest(
    val requestId: String,
    val deviceName: String,
    val devicePublicKey: String,
)

private object SecureRandomHolder {
    private val random = java.security.SecureRandom()

    fun nextBytes(byteCount: Int): ByteArray {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return bytes
    }
}
