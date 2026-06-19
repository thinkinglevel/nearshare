package com.pcmobilelink.nearshare.receiver

import com.pcmobilelink.nearshare.pairing.PairingEndpointCandidate
import com.pcmobilelink.nearshare.security.PairedDeviceRequestSignature
import com.pcmobilelink.nearshare.storage.PairedPcRecord
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidReceiveSessionManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun reachabilityRequiresSignedPairedPcRequest() {
        val manager = manager(RecordingReceiveStorage())
        val path = "/nearshare/paired-devices/$PC_DEVICE_ID/reachability"

        val response = manager.handle(
            ReceiveHttpRequest("GET", path, signedHeaders("GET", path, ByteArray(0)), ByteArray(0)),
        )

        assertEquals(200, response.statusCode)
        val json = JSONObject(response.bodyText)
        assertEquals("reachable", json.getString("status"))
        assertEquals(PC_DEVICE_ID, json.getString("deviceId"))
    }

    @Test
    fun createSessionRejectsBadSignatureAndCreatesNoTempFile() {
        val storage = RecordingReceiveStorage()
        val manager = manager(storage)
        val path = "/nearshare/paired-devices/$PC_DEVICE_ID/transfer-sessions"
        val body = createSessionBody("hello.txt", "hello".toByteArray())
        val headers = signedHeaders("POST", path, body).toMutableMap()
        headers["X-NearShare-Signature"] = "bad-signature"

        val response = manager.handle(
            ReceiveHttpRequest("POST", path, headers, body),
        )

        assertEquals(401, response.statusCode)
        assertEquals(0, storage.savedFiles.size)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun createSessionThenSignedChunkSavesCompletedFile() {
        val storage = RecordingReceiveStorage()
        val manager = manager(storage)
        val fileBytes = "hello from pc".toByteArray()
        val createPath = "/nearshare/paired-devices/$PC_DEVICE_ID/transfer-sessions"
        val createBody = createSessionBody("hello.txt", fileBytes)

        val createResponse = manager.handle(
            ReceiveHttpRequest("POST", createPath, signedHeaders("POST", createPath, createBody), createBody),
        )

        assertEquals(200, createResponse.statusCode)
        val sessionId = JSONObject(createResponse.bodyText).getString("sessionId")
        assertEquals(SESSION_ID.toString(), sessionId)

        val chunkPath = "/nearshare/paired-devices/$PC_DEVICE_ID/transfer-sessions/$sessionId/chunks"
        val chunkHeaders = signedHeaders("PUT", chunkPath, fileBytes).toMutableMap().apply {
            put("X-NearShare-Chunk-Offset", "0")
            put("X-NearShare-Chunk-Size", fileBytes.size.toString())
        }

        val chunkResponse = manager.handle(
            ReceiveHttpRequest("PUT", chunkPath, chunkHeaders, fileBytes),
        )

        assertEquals(200, chunkResponse.statusCode)
        val json = JSONObject(chunkResponse.bodyText)
        assertEquals("completed", json.getString("status"))
        assertEquals(fileBytes.size.toLong(), json.getLong("offsetBytes"))
        assertEquals("hello.txt", json.getString("savedFileName"))
        assertEquals("hello from pc", storage.savedFiles.single().bytes.toString(Charsets.UTF_8))
    }

    private fun manager(storage: RecordingReceiveStorage): AndroidReceiveSessionManager {
        return AndroidReceiveSessionManager(
            pairedPcLookup = { id -> if (id == PC_DEVICE_ID) pairedPcRecord() else null },
            storage = storage,
            tempDirectory = temporaryFolder.root,
            sessionIdFactory = { SESSION_ID },
            nowUnixTimeSeconds = { 1_700_000_120L },
        )
    }

    private fun createSessionBody(fileName: String, fileBytes: ByteArray): ByteArray {
        return JSONObject()
            .put("clientSessionId", "client-session-1")
            .put("fileName", fileName)
            .put("fileSizeBytes", fileBytes.size)
            .put("sha256", PairedDeviceRequestSignature.createBodyHash(fileBytes))
            .put("contentType", "text/plain")
            .put("fileIndex", 1)
            .put("totalFiles", 1)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun signedHeaders(method: String, path: String, body: ByteArray): Map<String, String> {
        return mapOf(
            "X-NearShare-Device-Id" to PC_DEVICE_ID,
            "X-NearShare-Timestamp" to "1700000000",
            "X-NearShare-Nonce" to "nonce-1",
            "X-NearShare-Signature" to PairedDeviceRequestSignature.sign(
                sharedSecret = SHARED_SECRET,
                method = method,
                pathAndQuery = path,
                timestampUnixTimeSeconds = 1_700_000_000L,
                nonce = "nonce-1",
                body = body,
            ),
        )
    }

    private fun pairedPcRecord(): PairedPcRecord {
        return PairedPcRecord(
            pcDeviceId = PC_DEVICE_ID,
            pcName = "Desktop",
            endpoints = listOf(PairingEndpointCandidate("127.0.0.1", 49152)),
            tlsCertificateSha256 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            sharedSecret = SHARED_SECRET,
            pairedAtUnixTimeSeconds = 1_700_000_000L,
        )
    }

    private class RecordingReceiveStorage : AndroidReceiveStorage {
        val savedFiles = mutableListOf<Saved>()

        override fun saveCompletedFile(
            originalFileName: String,
            contentType: String,
            tempFile: File,
        ): AndroidSavedFile {
            val saved = Saved(originalFileName, tempFile.readBytes())
            savedFiles += saved
            return AndroidSavedFile(displayName = originalFileName, sizeBytes = saved.bytes.size.toLong())
        }
    }

    private data class Saved(val name: String, val bytes: ByteArray)

    private companion object {
        private const val PC_DEVICE_ID = "8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b"
        private val SESSION_ID: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        private val SHARED_SECRET = PairedDeviceRequestSignature.encodeBase64Url(
            "shared-secret-key-32-bytes-here!!".toByteArray(Charsets.UTF_8),
        )
    }
}
