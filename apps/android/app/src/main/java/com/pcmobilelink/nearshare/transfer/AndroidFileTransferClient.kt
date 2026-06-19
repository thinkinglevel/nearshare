package com.pcmobilelink.nearshare.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.pcmobilelink.nearshare.security.PairedDeviceRequestSignature
import com.pcmobilelink.nearshare.security.PinnedCertificateTls
import com.pcmobilelink.nearshare.storage.PairedPcRecord
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

class AndroidFileTransferClient(
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 30_000,
) {
    fun prepareSharedFiles(context: Context, fileUris: List<Uri>): PreparedTransferBatch {
        require(fileUris.isNotEmpty()) { "At least one file is required." }
        val batchId = UUID.randomUUID().toString()
        val preparedFiles = mutableListOf<PreparedTransferFile>()
        try {
            fileUris.forEach { uri -> preparedFiles += prepareSharedFile(context, uri, batchId) }
            return PreparedTransferBatch(preparedFiles.toList())
        } catch (exception: Exception) {
            preparedFiles.forEach { it.tempFile.delete() }
            throw exception
        }
    }

    fun sendSharedFile(
        context: Context,
        fileUri: Uri,
        record: PairedPcRecord,
        onProgress: (FileTransferProgress) -> Unit = {},
    ): FileTransferResult {
        return sendSharedFiles(context, listOf(fileUri), record, onProgress).single()
    }

    fun sendSharedFiles(
        context: Context,
        fileUris: List<Uri>,
        record: PairedPcRecord,
        onProgress: (FileTransferProgress) -> Unit = {},
    ): List<FileTransferResult> {
        val batch = prepareSharedFiles(context, fileUris)
        return try {
            sendPreparedFiles(batch, record, FileTransferControl(), onProgress)
        } finally {
            batch.close()
        }
    }

    fun sendPreparedFiles(
        batch: PreparedTransferBatch,
        record: PairedPcRecord,
        control: FileTransferControl = FileTransferControl(),
        onProgress: (FileTransferProgress) -> Unit = {},
    ): List<FileTransferResult> {
        require(batch.files.isNotEmpty()) { "At least one file is required." }
        val results = mutableListOf<FileTransferResult>()
        var completedBatchBytes = 0L
        batch.files.forEachIndexed { index, preparedFile ->
            val result = uploadPreparedFile(
                record = record,
                preparedFile = preparedFile,
                fileIndex = index + 1,
                totalFiles = batch.files.size,
                completedBatchBytes = completedBatchBytes,
                batchTotalBytes = batch.totalBytes,
                control = control,
                onProgress = onProgress,
            )
            results += result
            completedBatchBytes += preparedFile.sizeBytes
        }
        return results
    }

    fun uploadBytes(
        record: PairedPcRecord,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
    ): FileTransferResult {
        require(fileName.isNotBlank()) { "File name cannot be empty." }
        val tempFile = File.createTempFile("nearshare-test-upload-", ".bin")
        tempFile.writeBytes(fileBytes)
        val batchId = UUID.randomUUID().toString()
        val preparedFile = PreparedTransferFile(
            batchId = batchId,
            tempFile = tempFile,
            displayName = fileName,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            sizeBytes = fileBytes.size.toLong(),
            sha256Base64Url = PairedDeviceRequestSignature.createBodyHash(fileBytes),
            clientSessionId = UUID.randomUUID().toString(),
        )
        val batch = PreparedTransferBatch(listOf(preparedFile))
        return try {
            sendPreparedFiles(batch, record).single()
        } finally {
            batch.close()
        }
    }

    private fun prepareSharedFile(context: Context, fileUri: Uri, batchId: String): PreparedTransferFile {
        val fileName = displayName(context, fileUri)
        val mimeType = context.contentResolver.getType(fileUri) ?: "application/octet-stream"
        val tempFile = File.createTempFile("nearshare-upload-", ".tmp", context.cacheDir)
        val digest = MessageDigest.getInstance("SHA-256")
        var sizeBytes = 0L
        try {
            context.contentResolver.openInputStream(fileUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) {
                            break
                        }
                        ensureCacheSpaceForWrite(tempFile, read.toLong())
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        sizeBytes += read
                    }
                }
            } ?: throw IllegalArgumentException("Could not open shared file.")
        } catch (exception: Exception) {
            tempFile.delete()
            throw exception
        }

        return PreparedTransferFile(
            batchId = batchId,
            tempFile = tempFile,
            displayName = fileName,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            sizeBytes = sizeBytes,
            sha256Base64Url = PairedDeviceRequestSignature.encodeBase64Url(digest.digest()),
            clientSessionId = UUID.randomUUID().toString(),
        )
    }

    private fun uploadPreparedFile(
        record: PairedPcRecord,
        preparedFile: PreparedTransferFile,
        fileIndex: Int,
        totalFiles: Int,
        completedBatchBytes: Long,
        batchTotalBytes: Long,
        control: FileTransferControl,
        onProgress: (FileTransferProgress) -> Unit,
    ): FileTransferResult {
        control.throwIfCancellationRequested()
        val session = createOrResumeSession(record, preparedFile, fileIndex, totalFiles, control)
        var offsetBytes = session.offsetBytes
        onProgress(
            FileTransferProgress(
                fileIndex = fileIndex,
                totalFiles = totalFiles,
                fileName = preparedFile.displayName,
                sentBytes = offsetBytes,
                totalBytes = preparedFile.sizeBytes,
                batchSentBytes = completedBatchBytes + offsetBytes,
                batchTotalBytes = batchTotalBytes,
            ),
        )

        try {
            FileInputStream(preparedFile.tempFile).use { input ->
                skipFully(input, offsetBytes)
                val chunkSize = session.chunkSizeBytes.coerceAtLeast(1)
                val buffer = ByteArray(chunkSize)
                while (offsetBytes < preparedFile.sizeBytes) {
                    control.throwIfCancellationRequested()
                    val remaining = preparedFile.sizeBytes - offsetBytes
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read == -1) {
                        throw IllegalStateException("Unexpected end of prepared file.")
                    }
                    val chunk = buffer.copyOf(read)
                    val chunkResponse = uploadChunk(
                        record = record,
                        sessionId = session.sessionId,
                        offsetBytes = offsetBytes,
                        chunk = chunk,
                        control = control,
                    )
                    offsetBytes = chunkResponse.offsetBytes
                    onProgress(
                        FileTransferProgress(
                            fileIndex = fileIndex,
                            totalFiles = totalFiles,
                            fileName = preparedFile.displayName,
                            sentBytes = offsetBytes,
                            totalBytes = preparedFile.sizeBytes,
                            batchSentBytes = completedBatchBytes + offsetBytes,
                            batchTotalBytes = batchTotalBytes,
                        ),
                    )
                    if (chunkResponse.status == "completed") {
                        return FileTransferResult(
                            status = chunkResponse.status,
                            pcDeviceId = record.pcDeviceId,
                            originalFileName = preparedFile.displayName,
                            savedFileName = chunkResponse.savedFileName ?: preparedFile.displayName,
                            sizeBytes = chunkResponse.fileSizeBytes,
                            sha256 = chunkResponse.sha256 ?: preparedFile.sha256Base64Url,
                        )
                    }
                }
            }
        } catch (exception: Exception) {
            if (control.isCancellationRequested) {
                runCatching { cancelTransferSession(record, session.sessionId) }
                throw CancellationException("Transfer cancelled.")
            }
            throw exception
        }

        throw IllegalStateException("Transfer session ended without completion.")
    }

    private fun createOrResumeSession(
        record: PairedPcRecord,
        preparedFile: PreparedTransferFile,
        fileIndex: Int,
        totalFiles: Int,
        control: FileTransferControl,
    ): TransferSessionStatus {
        val urlText = transferSessionUrl(record)
        val url = URL(urlText)
        val pathAndQuery = url.path + (url.query?.let { "?$it" } ?: "")
        val body = transferSessionRequestBody(preparedFile, fileIndex, totalFiles)
        val headers = signedRequestHeaders(
            pcDeviceId = record.pcDeviceId,
            sharedSecret = record.sharedSecret,
            method = "POST",
            pathAndQuery = pathAndQuery,
            timestampUnixTimeSeconds = System.currentTimeMillis() / 1_000L,
            nonce = PairedDeviceRequestSignature.createNonce(),
            body = body,
        )
        val connection = openPinnedConnection(url, record.tlsCertificateSha256)
        control.attach(connection)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(body) }
            val json = JSONObject(readResponseOrThrow(connection, expectedCode = HttpsURLConnection.HTTP_OK))
            return TransferSessionStatus(
                status = json.getString("status"),
                sessionId = json.getString("sessionId"),
                offsetBytes = json.getLong("offsetBytes"),
                chunkSizeBytes = json.getInt("chunkSizeBytes"),
                fileSizeBytes = json.getLong("fileSizeBytes"),
                originalFileName = json.getString("originalFileName"),
            )
        } finally {
            control.detach(connection)
        }
    }

    private fun uploadChunk(
        record: PairedPcRecord,
        sessionId: String,
        offsetBytes: Long,
        chunk: ByteArray,
        control: FileTransferControl,
    ): TransferChunkStatus {
        val urlText = transferChunkUrl(record, sessionId)
        val url = URL(urlText)
        val pathAndQuery = url.path + (url.query?.let { "?$it" } ?: "")
        val headers = signedChunkHeaders(
            pcDeviceId = record.pcDeviceId,
            sharedSecret = record.sharedSecret,
            chunkOffsetBytes = offsetBytes,
            chunkSizeBytes = chunk.size.toLong(),
            method = "PUT",
            pathAndQuery = pathAndQuery,
            timestampUnixTimeSeconds = System.currentTimeMillis() / 1_000L,
            nonce = PairedDeviceRequestSignature.createNonce(),
            body = chunk,
        )
        val connection = openPinnedConnection(url, record.tlsCertificateSha256)
        control.attach(connection)
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(chunk.size)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(chunk) }
            val json = JSONObject(readResponseOrThrow(connection, expectedCode = HttpsURLConnection.HTTP_OK))
            return TransferChunkStatus(
                status = json.getString("status"),
                sessionId = json.getString("sessionId"),
                offsetBytes = json.getLong("offsetBytes"),
                fileSizeBytes = json.getLong("fileSizeBytes"),
                savedFileName = json.optString("savedFileName").takeIf { it.isNotBlank() },
                sha256 = json.optString("sha256").takeIf { it.isNotBlank() },
            )
        } finally {
            control.detach(connection)
        }
    }

    fun cancelTransferSession(record: PairedPcRecord, sessionId: String) {
        val urlText = transferSessionStatusUrl(record, sessionId)
        val url = URL(urlText)
        val pathAndQuery = url.path + (url.query?.let { "?$it" } ?: "")
        val headers = signedRequestHeaders(
            pcDeviceId = record.pcDeviceId,
            sharedSecret = record.sharedSecret,
            method = "DELETE",
            pathAndQuery = pathAndQuery,
            timestampUnixTimeSeconds = System.currentTimeMillis() / 1_000L,
            nonce = PairedDeviceRequestSignature.createNonce(),
            body = ByteArray(0),
        )
        val connection = openPinnedConnection(url, record.tlsCertificateSha256)
        connection.requestMethod = "DELETE"
        connection.setRequestProperty("Accept", "application/json")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        readResponseOrThrow(connection, expectedCode = HttpsURLConnection.HTTP_OK)
    }

    private fun skipFully(input: InputStream, offsetBytes: Long) {
        var remaining = offsetBytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped <= 0L) {
                if (input.read() == -1) {
                    throw IllegalStateException("Could not resume because the local temp file is shorter than the remote offset.")
                }
                remaining--
            } else {
                remaining -= skipped
            }
        }
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

    private fun displayName(context: Context, fileUri: Uri): String {
        context.contentResolver.query(fileUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0 && cursor.moveToFirst()) {
                val value = cursor.getString(nameColumn)
                if (!value.isNullOrBlank()) {
                    return value
                }
            }
        }

        return fileUri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "shared-file"
    }

    private fun ensureCacheSpaceForWrite(tempFile: File, incomingBytes: Long) {
        val cacheFolder = tempFile.parentFile ?: return
        val safetyMarginBytes = 5L * 1024L * 1024L
        if (cacheFolder.usableSpace < incomingBytes + safetyMarginBytes) {
            throw IOException("Not enough free space to prepare this transfer. Free storage on this phone and try again.")
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
            throw IllegalStateException("File transfer failed with HTTP $statusCode: $body")
        }

        return body
    }

    private fun readFully(stream: InputStream): String {
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 1024 * 128

        fun uploadUrl(record: PairedPcRecord): String {
            val endpoint = record.endpoints.firstOrNull()
                ?: throw IllegalArgumentException("Paired PC must include at least one endpoint.")
            return "https://${formatHost(endpoint.host)}:${endpoint.port}/nearshare/paired-devices/${record.pcDeviceId}/transfers/files"
        }

        fun transferSessionUrl(record: PairedPcRecord): String {
            val endpoint = record.endpoints.firstOrNull()
                ?: throw IllegalArgumentException("Paired PC must include at least one endpoint.")
            return "https://${formatHost(endpoint.host)}:${endpoint.port}/nearshare/paired-devices/${record.pcDeviceId}/transfer-sessions"
        }

        fun transferSessionStatusUrl(record: PairedPcRecord, sessionId: String): String {
            val endpoint = record.endpoints.firstOrNull()
                ?: throw IllegalArgumentException("Paired PC must include at least one endpoint.")
            return "https://${formatHost(endpoint.host)}:${endpoint.port}/nearshare/paired-devices/${record.pcDeviceId}/transfer-sessions/$sessionId"
        }

        fun transferChunkUrl(record: PairedPcRecord, sessionId: String): String {
            return "${transferSessionStatusUrl(record, sessionId)}/chunks"
        }

        fun transferSessionRequestBody(
            preparedFile: PreparedTransferFile,
            fileIndex: Int,
            totalFiles: Int,
        ): ByteArray {
            require(fileIndex >= 1) { "File index must be at least 1." }
            require(totalFiles >= 1) { "Total files must be at least 1." }
            require(fileIndex <= totalFiles) { "File index cannot be greater than total files." }
            return JSONObject()
                .put("clientSessionId", preparedFile.clientSessionId)
                .put("fileName", preparedFile.displayName)
                .put("fileSizeBytes", preparedFile.sizeBytes)
                .put("sha256", preparedFile.sha256Base64Url)
                .put("contentType", preparedFile.mimeType)
                .put("fileIndex", fileIndex)
                .put("totalFiles", totalFiles)
                .toString()
                .toByteArray(Charsets.UTF_8)
        }

        fun signedUploadHeaders(
            pcDeviceId: String,
            sharedSecret: String,
            fileName: String,
            fileSizeBytes: Long,
            method: String,
            pathAndQuery: String,
            timestampUnixTimeSeconds: Long,
            nonce: String,
            body: ByteArray,
        ): Map<String, String> {
            return signedUploadHeadersForBodyHash(
                pcDeviceId = pcDeviceId,
                sharedSecret = sharedSecret,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                method = method,
                pathAndQuery = pathAndQuery,
                timestampUnixTimeSeconds = timestampUnixTimeSeconds,
                nonce = nonce,
                bodySha256Base64Url = PairedDeviceRequestSignature.createBodyHash(body),
            )
        }

        fun signedUploadHeadersForBodyHash(
            pcDeviceId: String,
            sharedSecret: String,
            fileName: String,
            fileSizeBytes: Long,
            method: String,
            pathAndQuery: String,
            timestampUnixTimeSeconds: Long,
            nonce: String,
            bodySha256Base64Url: String,
        ): Map<String, String> {
            require(fileName.isNotBlank()) { "File name cannot be empty." }
            require(fileSizeBytes >= 0) { "File size cannot be negative." }
            return signedRequestHeadersForBodyHash(
                pcDeviceId = pcDeviceId,
                sharedSecret = sharedSecret,
                method = method,
                pathAndQuery = pathAndQuery,
                timestampUnixTimeSeconds = timestampUnixTimeSeconds,
                nonce = nonce,
                bodySha256Base64Url = bodySha256Base64Url,
            ) + mapOf(
                "X-NearShare-File-Name" to fileName,
                "X-NearShare-File-Size" to fileSizeBytes.toString(),
            )
        }

        fun signedChunkHeaders(
            pcDeviceId: String,
            sharedSecret: String,
            chunkOffsetBytes: Long,
            chunkSizeBytes: Long,
            method: String,
            pathAndQuery: String,
            timestampUnixTimeSeconds: Long,
            nonce: String,
            body: ByteArray,
        ): Map<String, String> {
            require(chunkOffsetBytes >= 0) { "Chunk offset cannot be negative." }
            require(chunkSizeBytes >= 0) { "Chunk size cannot be negative." }
            return signedRequestHeaders(
                pcDeviceId = pcDeviceId,
                sharedSecret = sharedSecret,
                method = method,
                pathAndQuery = pathAndQuery,
                timestampUnixTimeSeconds = timestampUnixTimeSeconds,
                nonce = nonce,
                body = body,
            ) + mapOf(
                "X-NearShare-Chunk-Offset" to chunkOffsetBytes.toString(),
                "X-NearShare-Chunk-Size" to chunkSizeBytes.toString(),
            )
        }

        fun signedRequestHeaders(
            pcDeviceId: String,
            sharedSecret: String,
            method: String,
            pathAndQuery: String,
            timestampUnixTimeSeconds: Long,
            nonce: String,
            body: ByteArray,
        ): Map<String, String> {
            return signedRequestHeadersForBodyHash(
                pcDeviceId = pcDeviceId,
                sharedSecret = sharedSecret,
                method = method,
                pathAndQuery = pathAndQuery,
                timestampUnixTimeSeconds = timestampUnixTimeSeconds,
                nonce = nonce,
                bodySha256Base64Url = PairedDeviceRequestSignature.createBodyHash(body),
            )
        }

        fun signedRequestHeadersForBodyHash(
            pcDeviceId: String,
            sharedSecret: String,
            method: String,
            pathAndQuery: String,
            timestampUnixTimeSeconds: Long,
            nonce: String,
            bodySha256Base64Url: String,
        ): Map<String, String> {
            val signature = PairedDeviceRequestSignature.signBodyHash(
                sharedSecret = sharedSecret,
                method = method,
                pathAndQuery = pathAndQuery,
                timestampUnixTimeSeconds = timestampUnixTimeSeconds,
                nonce = nonce,
                bodySha256Base64Url = bodySha256Base64Url,
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

class FileTransferControl {
    @Volatile
    var isCancellationRequested: Boolean = false
        private set

    @Volatile
    private var activeConnection: HttpsURLConnection? = null

    fun cancel() {
        isCancellationRequested = true
        activeConnection?.disconnect()
    }

    internal fun throwIfCancellationRequested() {
        if (isCancellationRequested) {
            throw CancellationException("Transfer cancelled.")
        }
    }

    internal fun attach(connection: HttpsURLConnection) {
        activeConnection = connection
    }

    internal fun detach(connection: HttpsURLConnection) {
        if (activeConnection === connection) {
            activeConnection = null
        }
    }
}

class PreparedTransferBatch(
    val files: List<PreparedTransferFile>,
) : AutoCloseable {
    val batchId: String = files.firstOrNull()?.batchId.orEmpty()
    val totalBytes: Long = files.sumOf { it.sizeBytes }

    fun toActiveManifests(pcDeviceId: String): List<ActiveTransferManifest> {
        return files.map { file ->
            ActiveTransferManifest(
                batchId = file.batchId,
                pcDeviceId = pcDeviceId,
                clientSessionId = file.clientSessionId,
                displayName = file.displayName,
                mimeType = file.mimeType,
                cacheFilePath = file.tempFile.absolutePath,
                sizeBytes = file.sizeBytes,
                sha256Base64Url = file.sha256Base64Url,
                status = ActiveTransferStatus.Active,
            )
        }
    }

    override fun close() {
        files.forEach { it.tempFile.delete() }
    }

    companion object {
        fun fromActiveManifests(manifests: List<ActiveTransferManifest>): PreparedTransferBatch {
            require(manifests.isNotEmpty()) { "At least one active transfer manifest is required." }
            return PreparedTransferBatch(
                manifests.map { manifest ->
                    val cacheFile = File(manifest.cacheFilePath)
                    if (!cacheFile.exists()) {
                        throw IllegalStateException("Cached transfer file is missing: ${manifest.displayName}")
                    }
                    if (cacheFile.length() != manifest.sizeBytes) {
                        throw IllegalStateException("Cached transfer file size changed: ${manifest.displayName}")
                    }
                    PreparedTransferFile(
                        batchId = manifest.batchId,
                        tempFile = cacheFile,
                        displayName = manifest.displayName,
                        mimeType = manifest.mimeType,
                        sizeBytes = manifest.sizeBytes,
                        sha256Base64Url = manifest.sha256Base64Url,
                        clientSessionId = manifest.clientSessionId,
                    )
                },
            )
        }
    }
}

data class PreparedTransferFile(
    val batchId: String,
    val tempFile: File,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256Base64Url: String,
    val clientSessionId: String,
)

data class TransferSessionStatus(
    val status: String,
    val sessionId: String,
    val offsetBytes: Long,
    val chunkSizeBytes: Int,
    val fileSizeBytes: Long,
    val originalFileName: String,
)

data class TransferChunkStatus(
    val status: String,
    val sessionId: String,
    val offsetBytes: Long,
    val fileSizeBytes: Long,
    val savedFileName: String?,
    val sha256: String?,
)

data class FileTransferProgress(
    val fileIndex: Int,
    val totalFiles: Int,
    val fileName: String,
    val sentBytes: Long,
    val totalBytes: Long,
    val batchSentBytes: Long = sentBytes,
    val batchTotalBytes: Long = totalBytes,
) {
    val currentFilePercent: Int = percent(sentBytes, totalBytes)
    val batchPercent: Int = percent(batchSentBytes, batchTotalBytes)

    private companion object {
        fun percent(value: Long, total: Long): Int {
            if (total <= 0L) {
                return 100
            }
            return ((value * 100L) / total).coerceIn(0L, 100L).toInt()
        }
    }
}

data class FileTransferResult(
    val status: String,
    val pcDeviceId: String,
    val originalFileName: String,
    val savedFileName: String,
    val sizeBytes: Long,
    val sha256: String,
)
