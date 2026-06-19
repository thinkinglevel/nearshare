package com.pcmobilelink.nearshare.pairing.qr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeBitmap {
    fun create(content: String, sizePixels: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePixels, sizePixels)
        val bitmap = Bitmap.createBitmap(sizePixels, sizePixels, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePixels) {
            for (y in 0 until sizePixels) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Black else White)
            }
        }
        return bitmap
    }

    private const val Black = 0xFF000000.toInt()
    private const val White = 0xFFFFFFFF.toInt()
}
