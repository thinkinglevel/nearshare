package com.pcmobilelink.nearshare.receiver

import android.util.Log
import com.pcmobilelink.nearshare.security.PairedDeviceRequestSignature
import com.pcmobilelink.nearshare.storage.PairedPcRecord
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

class AndroidReceiveSessionManager(
    private val pairedPcLookup: (String) -> PairedPcRecord?,
    private val storage: AndroidReceiveStorage,
    private val tempDirectory: File,
    private val sessionIdFactory: () -> UUID = { UUID.randomUUID() },
    private val nowUnixTimeSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val chunkSizeBytes: Long = DEFAULT_CHUNK_SIZE_BYTES,
    private val progressChanged: ((ReceiveTransferProgress) -> Unit)? = null,
) {
    private val sessionsById = ConcurrentHashMap<UUID, AndroidReceiveSession>()
    private val sessionsByClientSessionId = ConcurrentHashMap<String, UUID>()

    fun handle(request: ReceiveHttpRequest): ReceiveHttpResponse {
        return try {
            logInfo("Android receive HTTP request method=${request.method} path=${request.pathAndQuery} bodyBytes=${request.body.size}")
            when {
                request.method.equals("GET", ignoreCase = true) && request.pathAndQuery.endsWith("/reachability") -> {
                    reachability(request)
                }

                request.method.equals("POST", ignoreCase = true) && request.pathAndQuery.endsWith("/transfer-sessions") -> {
                    createTransferSession(request)
                }

                request.method.equals("PUT", ignoreCase = true) && request.pathAndQuery.endsWith("/chunks") -> {
                    uploadTransferChunk(request)
                }

                else -> jsonResponse(404, "not_found", "NearShare receive endpoint not found.")
            }
        } catch (exception: IllegalArgumentException) {
            logWarning("Android receive request rejected as bad request path=${request.pathAndQuery}: ${exception.message}", exception)
            jsonResponse(400, "bad_request", exception.message ?: "Invalid NearShare request.")
        } catch (exception: SecurityException) {
            logWarning("Android receive request was not authenticated path=${request.pathAndQuery}: ${exception.message}", exception)
            jsonResponse(401, "unauthorized", exception.message ?: "NearShare request was not authenticated.")
        } catch (exception: NoSuchElementException) {
            logWarning("Android receive request target was not found path=${request.pathAndQuery}: ${exception.message}", exception)
            jsonResponse(404, "not_found", exception.message ?: "NearShare transfer session was not found.")
        } catch (exception: Exception) {
            logError("Android receive request failed path=${request.pathAndQuery}", exception)
            jsonResponse(500, "failed", exception.message ?: "NearShare receive failed.")
        }
    }

    private fun reachability(request: ReceiveHttpRequest): ReceiveHttpResponse {
        val pcDeviceId = pcDeviceIdFromReachabilityPath(request.pathAndQuery)
        authenticate(request, pcDeviceId)
        logInfo("Android receive reachability verified for pcDeviceId=$pcDeviceId")
        return ReceiveHttpResponse(
            statusCode = 200,
            bodyText = JSONObject()
                .put("status", "reachable")
                .put("deviceId", pcDeviceId)
                .put("serverTimeUnixSeconds", nowUnixTimeSeconds())
                .toString(),
        )
    }

    private fun createTransferSession(request: ReceiveHttpRequest): ReceiveHttpResponse {
        val pcDeviceId = pcDeviceIdFromTransferSessionsPath(request.pathAndQuery)
        val pairedPc = authenticate(request, pcDeviceId)
        val json = JSONObject(request.body.toString(Charsets.UTF_8))
        val clientSessionId = json.getString("clientSessionId").trim()
        require(clientSessionId.isNotEmpty()) { "Client session ID cannot be empty." }
        val originalFileName = json.getString("fileName")
        val safeFileName = safeDisplayFileName(originalFileName)
        val fileSizeBytes = json.getLong("fileSizeBytes")
        require(fileSizeBytes >= 0L) { "File size cannot be negative." }
        val expectedSha256 = json.getString("sha256").trim()
        require(expectedSha256.isNotEmpty()) { "File hash cannot be empty." }
        val contentType = json.optString("contentType", "application/octet-stream").ifBlank { "application/octet-stream" }
        val fileIndex = json.optInt("fileIndex", 1)
        val totalFiles = json.optInt("totalFiles", 1)
        require(fileIndex >= 1) { "File index must be at least 1." }
        require(totalFiles >= 1) { "Total files must be at least 1." }
        require(fileIndex <= totalFiles) { "File index cannot be greater than total files." }
        logInfo("Creating Android receive session pcDeviceId=$pcDeviceId file=$fileIndex/$totalFiles fileName=$safeFileName sizeBytes=$fileSizeBytes")

        val existingSessionId = sessionsByClientSessionId[clientSessionId]
        if (existingSessionId != null) {
            val existingSession = sessionsById[existingSessionId]
                ?: throw NoSuchElementException("Existing transfer session was not found.")
            logInfo("Returning existing Android receive session sessionId=$existingSessionId offsetBytes=${existingSession.offsetBytes()}")
            return sessionStatusResponse(existingSession, "ready")
        }

        tempDirectory.mkdirs()
        val sessionId = sessionIdFactory()
        val tempFile = File(tempDirectory, "$sessionId.part")
        val session = AndroidReceiveSession(
            sessionId = sessionId,
            clientSessionId = clientSessionId,
            pcDeviceId = pairedPc.pcDeviceId,
            pcName = pairedPc.pcName,
            originalFileName = safeFileName,
            fileSizeBytes = fileSizeBytes,
            expectedSha256 = expectedSha256,
            contentType = contentType,
            fileIndex = fileIndex,
            totalFiles = totalFiles,
            tempFile = tempFile,
        )
        sessionsById[sessionId] = session
        sessionsByClientSessionId[clientSessionId] = sessionId
        logInfo("Android receive session created sessionId=$sessionId pcDeviceId=$pcDeviceId fileName=$safeFileName")
        return sessionStatusResponse(session, "ready")
    }

    private fun uploadTransferChunk(request: ReceiveHttpRequest): ReceiveHttpResponse {
        val route = parseChunkPath(request.pathAndQuery)
        authenticate(request, route.pcDeviceId)
        val session = sessionsById[route.sessionId]
            ?: throw NoSuchElementException("Transfer session was not found.")
        require(session.pcDeviceId.equals(route.pcDeviceId, ignoreCase = true)) { "Transfer session does not belong to this paired PC." }

        val offsetBytes = request.requiredHeader("X-NearShare-Chunk-Offset").toLongOrNull()
            ?: throw IllegalArgumentException("Chunk offset header is invalid.")
        val chunkSizeHeader = request.requiredHeader("X-NearShare-Chunk-Size").toLongOrNull()
            ?: throw IllegalArgumentException("Chunk size header is invalid.")
        require(offsetBytes >= 0L) { "Chunk offset cannot be negative." }
        require(chunkSizeHeader == request.body.size.toLong()) { "Chunk size header does not match body size." }
        require(offsetBytes == session.offsetBytes()) { "Chunk offset does not match the current transfer offset." }
        require(offsetBytes + request.body.size <= session.fileSizeBytes) { "Chunk exceeds expected file size." }
        logInfo("Writing Android receive chunk sessionId=${session.sessionId} offsetBytes=$offsetBytes chunkBytes=${request.body.size} fileSizeBytes=${session.fileSizeBytes}")

        session.tempFile.parentFile?.mkdirs()
        RandomAccessFile(session.tempFile, "rw").use { file ->
            file.seek(offsetBytes)
            file.write(request.body)
        }

        val newOffset = session.offsetBytes()
        if (newOffset < session.fileSizeBytes) {
            publishProgress(session, newOffset, ReceiveTransferStatus.InProgress)
            logInfo("Android receive chunk accepted sessionId=${session.sessionId} newOffset=$newOffset status=in_progress")
            return chunkResponse(session, status = "in_progress", savedFileName = null, sha256 = null)
        }

        val actualSha256 = sha256Base64Url(session.tempFile)
        require(actualSha256 == session.expectedSha256) { "Completed file hash did not match the sender metadata." }
        val savedFile = storage.saveCompletedFile(
            originalFileName = session.originalFileName,
            contentType = session.contentType,
            tempFile = session.tempFile,
        )
        val completedOffsetBytes = session.offsetBytes()
        publishProgress(session, completedOffsetBytes, ReceiveTransferStatus.Completed)
        session.tempFile.delete()
        sessionsById.remove(session.sessionId)
        sessionsByClientSessionId.remove(session.clientSessionId)
        logInfo("Android receive file completed sessionId=${session.sessionId} savedFileName=${savedFile.displayName} sizeBytes=$completedOffsetBytes")
        return chunkResponse(
            session,
            status = "completed",
            savedFileName = savedFile.displayName,
            sha256 = actualSha256,
            offsetBytes = completedOffsetBytes,
        )
    }

    private fun authenticate(request: ReceiveHttpRequest, pcDeviceId: String): PairedPcRecord {
        val headerDeviceId = request.requiredHeader("X-NearShare-Device-Id")
        require(headerDeviceId.equals(pcDeviceId, ignoreCase = true)) { "Device ID header does not match request path." }
        val pairedPc = pairedPcLookup(pcDeviceId)
            ?: throw NoSuchElementException("Paired PC was not found.")
        val timestamp = request.requiredHeader("X-NearShare-Timestamp").toLongOrNull()
            ?: throw IllegalArgumentException("Timestamp header is invalid.")
        val nonce = request.requiredHeader("X-NearShare-Nonce")
        val signature = request.requiredHeader("X-NearShare-Signature")
        val verified = PairedDeviceRequestSignature.verify(
            sharedSecret = pairedPc.sharedSecret,
            method = request.method,
            pathAndQuery = request.pathAndQuery,
            timestampUnixTimeSeconds = timestamp,
            nonce = nonce,
            body = request.body,
            signature = signature,
            nowUnixTimeSeconds = nowUnixTimeSeconds(),
            allowedClockSkewSeconds = 300L,
        )
        if (!verified) {
            throw SecurityException("NearShare request signature was invalid or expired.")
        }
        logInfo("Authenticated Android receive request pcDeviceId=$pcDeviceId method=${request.method} path=${request.pathAndQuery}")
        return pairedPc
    }

    private fun sessionStatusResponse(session: AndroidReceiveSession, status: String): ReceiveHttpResponse {
        return ReceiveHttpResponse(
            statusCode = 200,
            bodyText = JSONObject()
                .put("status", status)
                .put("sessionId", session.sessionId.toString())
                .put("offsetBytes", session.offsetBytes())
                .put("chunkSizeBytes", chunkSizeBytes)
                .put("fileSizeBytes", session.fileSizeBytes)
                .put("originalFileName", session.originalFileName)
                .toString(),
        )
    }

    private fun chunkResponse(
        session: AndroidReceiveSession,
        status: String,
        savedFileName: String?,
        sha256: String?,
        offsetBytes: Long = session.offsetBytes(),
    ): ReceiveHttpResponse {
        val json = JSONObject()
            .put("status", status)
            .put("sessionId", session.sessionId.toString())
            .put("offsetBytes", offsetBytes)
            .put("fileSizeBytes", session.fileSizeBytes)
        if (!savedFileName.isNullOrBlank()) {
            json.put("savedFileName", savedFileName)
        }
        if (!sha256.isNullOrBlank()) {
            json.put("sha256", sha256)
        }
        return ReceiveHttpResponse(statusCode = 200, bodyText = json.toString())
    }

    private fun pcDeviceIdFromReachabilityPath(pathAndQuery: String): String {
        val path = pathAndQuery.substringBefore('?')
        val prefix = "/nearshare/paired-devices/"
        val suffix = "/reachability"
        require(path.startsWith(prefix) && path.endsWith(suffix)) { "Reachability path is invalid." }
        return path.removePrefix(prefix).removeSuffix(suffix).trim('/').also {
            require(it.isNotBlank()) { "Paired PC ID is missing." }
        }
    }

    private fun pcDeviceIdFromTransferSessionsPath(pathAndQuery: String): String {
        val path = pathAndQuery.substringBefore('?')
        val prefix = "/nearshare/paired-devices/"
        val suffix = "/transfer-sessions"
        require(path.startsWith(prefix) && path.endsWith(suffix)) { "Transfer session path is invalid." }
        return path.removePrefix(prefix).removeSuffix(suffix).trim('/').also {
            require(it.isNotBlank()) { "Paired PC ID is missing." }
        }
    }

    private fun parseChunkPath(pathAndQuery: String): ChunkRoute {
        val path = pathAndQuery.substringBefore('?')
        val regex = Regex("^/nearshare/paired-devices/([^/]+)/transfer-sessions/([^/]+)/chunks$")
        val match = regex.matchEntire(path) ?: throw IllegalArgumentException("Chunk upload path is invalid.")
        val pcDeviceId = match.groupValues[1]
        val sessionId = UUID.fromString(match.groupValues[2])
        return ChunkRoute(pcDeviceId, sessionId)
    }

    private fun safeDisplayFileName(fileName: String): String {
        val leaf = fileName.replace('\\', '/').substringAfterLast('/').trim()
        val cleaned = leaf
            .replace(Regex("[\\u0000-\\u001F<>:\"/\\\\|?*]"), "_")
            .trim(' ', '.')
        return cleaned.ifBlank { "received-file" }
    }

    private fun sha256Base64Url(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return PairedDeviceRequestSignature.encodeBase64Url(digest.digest())
    }

    private fun publishProgress(session: AndroidReceiveSession, receivedBytes: Long, status: ReceiveTransferStatus) {
        val perFileBatchTotalBytes = session.fileSizeBytes.coerceAtLeast(1L) * session.totalFiles.coerceAtLeast(1)
        val perFileBatchReceivedBytes = (session.fileIndex - 1).coerceAtLeast(0) * session.fileSizeBytes.coerceAtLeast(1L) + receivedBytes
        progressChanged?.invoke(
            ReceiveTransferProgress(
                pcDeviceId = session.pcDeviceId,
                pcName = session.pcName,
                fileName = session.originalFileName,
                fileIndex = session.fileIndex,
                totalFiles = session.totalFiles,
                receivedBytes = receivedBytes,
                totalBytes = session.fileSizeBytes,
                batchReceivedBytes = perFileBatchReceivedBytes,
                batchTotalBytes = perFileBatchTotalBytes,
                status = status,
            ),
        )
    }

    private fun jsonResponse(statusCode: Int, status: String, message: String): ReceiveHttpResponse {
        return ReceiveHttpResponse(
            statusCode = statusCode,
            bodyText = JSONObject()
                .put("status", status)
                .put("message", message)
                .toString(),
        )
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logWarning(message: String, exception: Throwable) {
        runCatching { Log.w(TAG, message, exception) }
    }

    private fun logError(message: String, exception: Throwable) {
        runCatching { Log.e(TAG, message, exception) }
    }

    private data class ChunkRoute(val pcDeviceId: String, val sessionId: UUID)

    private companion object {
        private const val DEFAULT_CHUNK_SIZE_BYTES = 1024L * 1024L
        private const val TAG = "NearShare"
    }
}

data class ReceiveHttpRequest(
    val method: String,
    val pathAndQuery: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    private val caseInsensitiveHeaders: Map<String, String> = headers.mapKeys { it.key.lowercase(Locale.US) }

    fun requiredHeader(name: String): String {
        return caseInsensitiveHeaders[name.lowercase(Locale.US)]
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing NearShare header: $name")
    }
}

data class ReceiveHttpResponse(
    val statusCode: Int,
    val bodyText: String,
    val contentType: String = "application/json; charset=utf-8",
)

interface AndroidReceiveStorage {
    fun saveCompletedFile(
        originalFileName: String,
        contentType: String,
        tempFile: File,
    ): AndroidSavedFile
}

data class AndroidSavedFile(
    val displayName: String,
    val sizeBytes: Long,
    val uri: String? = null,
)

private data class AndroidReceiveSession(
    val sessionId: UUID,
    val clientSessionId: String,
    val pcDeviceId: String,
    val pcName: String,
    val originalFileName: String,
    val fileSizeBytes: Long,
    val expectedSha256: String,
    val contentType: String,
    val fileIndex: Int,
    val totalFiles: Int,
    val tempFile: File,
) {
    fun offsetBytes(): Long = if (tempFile.exists()) tempFile.length() else 0L
}
