package com.pcmobilelink.nearshare.transfer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTransferManifestStoreTest {
    @Test
    fun saveBatchThenLoadAllReturnsActiveManifest() {
        val tempDir = kotlin.io.path.createTempDirectory(prefix = "nearshare-active-transfer-test-").toFile()
        try {
            val store = ActiveTransferManifestStore(File(tempDir, "active-transfers.json"))
            val manifest = manifest(batchId = "batch-1", pcDeviceId = "pc-1")

            store.saveBatch(listOf(manifest))

            assertEquals(listOf(manifest), store.loadAll())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun saveBatchReplacesExistingBatchOnly() {
        val tempDir = kotlin.io.path.createTempDirectory(prefix = "nearshare-active-transfer-test-").toFile()
        try {
            val store = ActiveTransferManifestStore(File(tempDir, "active-transfers.json"))
            store.saveBatch(listOf(manifest(batchId = "batch-1", pcDeviceId = "old-pc")))
            store.saveBatch(listOf(manifest(batchId = "batch-2", pcDeviceId = "pc-2")))
            store.saveBatch(listOf(manifest(batchId = "batch-1", pcDeviceId = "new-pc")))

            val manifests = store.loadAll()
            assertEquals(2, manifests.size)
            assertTrue(manifests.any { it.batchId == "batch-1" && it.pcDeviceId == "new-pc" })
            assertTrue(manifests.any { it.batchId == "batch-2" && it.pcDeviceId == "pc-2" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun deleteBatchRemovesOnlyThatBatch() {
        val tempDir = kotlin.io.path.createTempDirectory(prefix = "nearshare-active-transfer-test-").toFile()
        try {
            val store = ActiveTransferManifestStore(File(tempDir, "active-transfers.json"))
            store.saveBatch(
                listOf(
                    manifest(batchId = "batch-1", pcDeviceId = "pc-1"),
                    manifest(batchId = "batch-2", pcDeviceId = "pc-2"),
                ),
            )

            store.deleteBatch("batch-1")

            assertEquals(listOf(manifest(batchId = "batch-2", pcDeviceId = "pc-2")), store.loadAll())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun manifest(batchId: String, pcDeviceId: String): ActiveTransferManifest {
        return ActiveTransferManifest(
            batchId = batchId,
            pcDeviceId = pcDeviceId,
            clientSessionId = "client-session-$batchId",
            displayName = "photo.jpg",
            mimeType = "image/jpeg",
            cacheFilePath = "/tmp/photo.jpg",
            sizeBytes = 123,
            sha256Base64Url = "abc123",
            status = ActiveTransferStatus.Active,
        )
    }
}
