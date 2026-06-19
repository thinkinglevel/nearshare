package com.pcmobilelink.nearshare.pairing.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeTextDecoderTest {
    @Test
    fun decodeLuminance_withQrCode_returnsEncodedText() {
        val expected = "nearshare://pair?payload=test"
        val luminance = qrCodeLuminance(expected, size = 96)

        val decoded = QrCodeTextDecoder.decodeLuminance(
            luminance = luminance,
            width = 96,
            height = 96,
        )

        assertEquals(expected, decoded)
    }

    @Test
    fun decodeLuminance_withBlankFrame_returnsNull() {
        val decoded = QrCodeTextDecoder.decodeLuminance(
            luminance = ByteArray(64 * 64) { 0xFF.toByte() },
            width = 64,
            height = 64,
        )

        assertNull(decoded)
    }

    private fun qrCodeLuminance(text: String, size: Int): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        return ByteArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (matrix[x, y]) 0x00.toByte() else 0xFF.toByte()
        }
    }
}
