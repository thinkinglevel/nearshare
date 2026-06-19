package com.pcmobilelink.nearshare.transfer

import com.pcmobilelink.nearshare.pairing.PairingEndpointCandidate
import com.pcmobilelink.nearshare.security.PairedDeviceRequestSignature
import com.pcmobilelink.nearshare.storage.PairedPcRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.json.JSONObject
import org.junit.Test

class AndroidFileTransferClientTest {
    @Test
    fun uploadUrlUsesFirstPairedPcEndpointAndDeviceId() {
        val record = pairedPcRecord()

        val url = AndroidFileTransferClient.uploadUrl(record)

        assertEquals(
            "https://192.168.1.50:50371/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfers/files",
            url,
        )
    }

    @Test
    fun signedUploadHeadersIncludeAuthAndFileMetadata() {
        val sharedSecret = PairedDeviceRequestSignature.encodeBase64Url(
            "shared-secret-key-32-bytes-here!!".toByteArray(Charsets.UTF_8),
        )
        val fileBytes = "hello from android".toByteArray(Charsets.UTF_8)

        val headers = AndroidFileTransferClient.signedUploadHeaders(
            pcDeviceId = "8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b",
            sharedSecret = sharedSecret,
            fileName = "hello.txt",
            fileSizeBytes = fileBytes.size.toLong(),
            method = "POST",
            pathAndQuery = "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfers/files",
            timestampUnixTimeSeconds = 1_700_000_000L,
            nonce = "upload-nonce-1",
            body = fileBytes,
        )

        assertEquals("8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b", headers["X-NearShare-Device-Id"])
        assertEquals("1700000000", headers["X-NearShare-Timestamp"])
        assertEquals("upload-nonce-1", headers["X-NearShare-Nonce"])
        assertEquals("hello.txt", headers["X-NearShare-File-Name"])
        assertEquals(fileBytes.size.toString(), headers["X-NearShare-File-Size"])
        assertEquals("FZs6Pi29GHau1RQ2MVNnN2hgQMZfD146DM2HBxoVlBQ", headers["X-NearShare-Signature"])
    }

    @Test
    fun signedUploadHeadersForBodyHashIncludeAuthAndFileMetadata() {
        val sharedSecret = PairedDeviceRequestSignature.encodeBase64Url(
            "shared-secret-key-32-bytes-here!!".toByteArray(Charsets.UTF_8),
        )

        val headers = AndroidFileTransferClient.signedUploadHeadersForBodyHash(
            pcDeviceId = "8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b",
            sharedSecret = sharedSecret,
            fileName = "hello.txt",
            fileSizeBytes = "hello from android".toByteArray(Charsets.UTF_8).size.toLong(),
            method = "POST",
            pathAndQuery = "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfers/files",
            timestampUnixTimeSeconds = 1_700_000_000L,
            nonce = "upload-nonce-1",
            bodySha256Base64Url = "XQnr5TdqYiCssDf5TLUWmk1BFZ4vh47UjxSMe2NYtcs",
        )

        assertEquals("8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b", headers["X-NearShare-Device-Id"])
        assertEquals("1700000000", headers["X-NearShare-Timestamp"])
        assertEquals("upload-nonce-1", headers["X-NearShare-Nonce"])
        assertEquals("hello.txt", headers["X-NearShare-File-Name"])
        assertEquals("18", headers["X-NearShare-File-Size"])
        assertEquals("FZs6Pi29GHau1RQ2MVNnN2hgQMZfD146DM2HBxoVlBQ", headers["X-NearShare-Signature"])
    }

    @Test
    fun transferSessionUrlUsesFirstPairedPcEndpointAndDeviceId() {
        val record = pairedPcRecord()

        val url = AndroidFileTransferClient.transferSessionUrl(record)

        assertEquals(
            "https://192.168.1.50:50371/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfer-sessions",
            url,
        )
    }

    @Test
    fun transferChunkUrlUsesSessionId() {
        val record = pairedPcRecord()

        val url = AndroidFileTransferClient.transferChunkUrl(record, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertEquals(
            "https://192.168.1.50:50371/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfer-sessions/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/chunks",
            url,
        )
    }

    @Test
    fun signedChunkHeadersIncludeAuthAndChunkMetadata() {
        val sharedSecret = PairedDeviceRequestSignature.encodeBase64Url(
            "shared-secret-key-32-bytes-here!!".toByteArray(Charsets.UTF_8),
        )
        val chunk = "hello ".toByteArray(Charsets.UTF_8)

        val headers = AndroidFileTransferClient.signedChunkHeaders(
            pcDeviceId = "8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b",
            sharedSecret = sharedSecret,
            chunkOffsetBytes = 0,
            chunkSizeBytes = chunk.size.toLong(),
            method = "PUT",
            pathAndQuery = "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfer-sessions/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/chunks",
            timestampUnixTimeSeconds = 1_700_000_000L,
            nonce = "chunk-nonce-1",
            body = chunk,
        )

        assertEquals("0", headers["X-NearShare-Chunk-Offset"])
        assertEquals(chunk.size.toString(), headers["X-NearShare-Chunk-Size"])
        assertEquals("8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b", headers["X-NearShare-Device-Id"])
        assertEquals("1700000000", headers["X-NearShare-Timestamp"])
        assertEquals("chunk-nonce-1", headers["X-NearShare-Nonce"])
    }

    @Test
    fun fileTransferProgressCalculatesCurrentAndBatchPercent() {
        val progress = FileTransferProgress(
            fileIndex = 2,
            totalFiles = 3,
            fileName = "video.mp4",
            sentBytes = 50,
            totalBytes = 200,
            batchSentBytes = 250,
            batchTotalBytes = 1_000,
        )

        assertEquals(25, progress.currentFilePercent)
        assertEquals(25, progress.batchPercent)
    }

    @Test
    fun transferSessionRequestBodyIncludesBatchFilePosition() {
        val tempFile = kotlin.io.path.createTempFile(prefix = "nearshare-session-body-", suffix = ".bin").toFile()
        try {
            val body = AndroidFileTransferClient.transferSessionRequestBody(
                PreparedTransferFile(
                    batchId = "batch-1",
                    tempFile = tempFile,
                    displayName = "photo.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 123,
                    sha256Base64Url = "abc123",
                    clientSessionId = "client-session-1",
                ),
                fileIndex = 3,
                totalFiles = 14,
            )
            val json = JSONObject(body.toString(Charsets.UTF_8))

            assertEquals("client-session-1", json.getString("clientSessionId"))
            assertEquals("photo.jpg", json.getString("fileName"))
            assertEquals(3, json.getInt("fileIndex"))
            assertEquals(14, json.getInt("totalFiles"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun preparedBatchCreatesActiveTransferManifestsForTargetPc() {
        val tempFile = kotlin.io.path.createTempFile(prefix = "nearshare-prepared-", suffix = ".bin").toFile()
        try {
            val batch = PreparedTransferBatch(
                listOf(
                    PreparedTransferFile(
                        batchId = "batch-1",
                        tempFile = tempFile,
                        displayName = "photo.jpg",
                        mimeType = "image/jpeg",
                        sizeBytes = 123,
                        sha256Base64Url = "abc123",
                        clientSessionId = "client-session-1",
                    ),
                ),
            )

            val manifests = batch.toActiveManifests("pc-1")

            assertEquals(
                listOf(
                    ActiveTransferManifest(
                        batchId = "batch-1",
                        pcDeviceId = "pc-1",
                        clientSessionId = "client-session-1",
                        displayName = "photo.jpg",
                        mimeType = "image/jpeg",
                        cacheFilePath = tempFile.absolutePath,
                        sizeBytes = 123,
                        sha256Base64Url = "abc123",
                        status = ActiveTransferStatus.Active,
                    ),
                ),
                manifests,
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun preparedBatchRestoresActiveManifestsForForegroundServiceRetry() {
        val tempFile = kotlin.io.path.createTempFile(prefix = "nearshare-restored-", suffix = ".bin").toFile()
        tempFile.writeText("cached bytes")
        try {
            val manifests = listOf(
                ActiveTransferManifest(
                    batchId = "batch-1",
                    pcDeviceId = "pc-1",
                    clientSessionId = "client-session-1",
                    displayName = "photo.jpg",
                    mimeType = "image/jpeg",
                    cacheFilePath = tempFile.absolutePath,
                    sizeBytes = tempFile.length(),
                    sha256Base64Url = "abc123",
                    status = ActiveTransferStatus.Active,
                ),
            )

            val restored = PreparedTransferBatch.fromActiveManifests(manifests)

            assertEquals("batch-1", restored.batchId)
            assertEquals(1, restored.files.size)
            assertEquals(tempFile.absolutePath, restored.files.single().tempFile.absolutePath)
            assertEquals("client-session-1", restored.files.single().clientSessionId)
            assertEquals("photo.jpg", restored.files.single().displayName)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun preparedBatchRestoreFailsWhenCachedFileIsMissing() {
        val manifests = listOf(
            ActiveTransferManifest(
                batchId = "batch-1",
                pcDeviceId = "pc-1",
                clientSessionId = "client-session-1",
                displayName = "photo.jpg",
                mimeType = "image/jpeg",
                cacheFilePath = "Z:/missing/nearshare/photo.jpg",
                sizeBytes = 123,
                sha256Base64Url = "abc123",
                status = ActiveTransferStatus.Active,
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            PreparedTransferBatch.fromActiveManifests(manifests)
        }
    }

    private fun pairedPcRecord(): PairedPcRecord {
        return PairedPcRecord(
            pcDeviceId = "8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b",
            pcName = "NearShare Test PC",
            endpoints = listOf(PairingEndpointCandidate("192.168.1.50", 50371)),
            tlsCertificateSha256 = "A".repeat(64),
            sharedSecret = PairedDeviceRequestSignature.encodeBase64Url(
                "shared-secret-key-32-bytes-here!!".toByteArray(Charsets.UTF_8),
            ),
            pairedAtUnixTimeSeconds = 1_700_000_000L,
        )
    }
}
