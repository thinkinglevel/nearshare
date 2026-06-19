package com.pcmobilelink.nearshare.pairing.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class QrImageAnalyzer(
    private val onQrCodeFound: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            val luminance = copyYPlane(image)
            val decodedText = QrCodeTextDecoder.decodeLuminance(
                luminance = luminance,
                width = image.width,
                height = image.height,
            )

            if (!decodedText.isNullOrBlank()) {
                onQrCodeFound(decodedText)
            }
        } catch (_: Exception) {
            // Keep scanning. Bad frames and non-QR frames are expected camera input.
        } finally {
            image.close()
        }
    }

    private fun copyYPlane(image: ImageProxy): ByteArray {
        val yPlane = image.planes.first()
        val buffer = yPlane.buffer
        val width = image.width
        val height = image.height
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val output = ByteArray(width * height)

        var outputIndex = 0
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (column in 0 until width) {
                output[outputIndex++] = buffer.get(rowStart + column * pixelStride)
            }
        }

        return output
    }
}
