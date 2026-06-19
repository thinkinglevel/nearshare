package com.pcmobilelink.nearshare.receiver

data class ReceiveTransferProgress(
    val pcDeviceId: String,
    val pcName: String,
    val fileName: String,
    val fileIndex: Int,
    val totalFiles: Int,
    val receivedBytes: Long,
    val totalBytes: Long,
    val batchReceivedBytes: Long = receivedBytes,
    val batchTotalBytes: Long = totalBytes,
    val status: ReceiveTransferStatus,
) {
    val currentFilePercent: Int = percent(receivedBytes, totalBytes)
    val batchPercent: Int = percent(batchReceivedBytes, batchTotalBytes)

    private companion object {
        fun percent(value: Long, total: Long): Int {
            if (total <= 0L) {
                return 100
            }
            return ((value * 100L) / total).coerceIn(0L, 100L).toInt()
        }
    }
}

enum class ReceiveTransferStatus {
    Ready,
    InProgress,
    Completed,
    Failed,
    Stopped,
}
