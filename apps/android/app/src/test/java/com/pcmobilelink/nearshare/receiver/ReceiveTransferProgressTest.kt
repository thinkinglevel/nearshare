package com.pcmobilelink.nearshare.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiveTransferProgressTest {
    @Test
    fun progressCalculatesFileAndBatchPercent() {
        val progress = ReceiveTransferProgress(
            pcDeviceId = "pc-1",
            pcName = "Desktop",
            fileName = "photo.jpg",
            fileIndex = 2,
            totalFiles = 4,
            receivedBytes = 50,
            totalBytes = 200,
            batchReceivedBytes = 250,
            batchTotalBytes = 1000,
            status = ReceiveTransferStatus.InProgress,
        )

        assertEquals(25, progress.currentFilePercent)
        assertEquals(25, progress.batchPercent)
    }
}
