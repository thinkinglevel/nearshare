package com.pcmobilelink.nearshare.pairing

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingCodeInputTest {
    @Test
    fun decode_withScannedQrText_trimsAndUsesPairingPayloadCodec() {
        val scannedText = "  \n${nearShareUri()}\n  "

        val payload = PairingCodeInput.decode(scannedText)

        assertEquals("11111111-2222-3333-4444-555555555555", payload.offerId)
        assertEquals("NearShare Test PC", payload.pcName)
        assertEquals("192.168.1.50", payload.endpoints.single().host)
        assertEquals(54321, payload.endpoints.single().port)
    }

    @Test
    fun decode_withBlankScannedText_rejectsInput() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingCodeInput.decode("  \n\t  ")
        }
    }

    private fun nearShareUri(): String {
        val json = """
            {
              "version": 1,
              "offerId": "11111111-2222-3333-4444-555555555555",
              "pcName": "NearShare Test PC",
              "endpoints": [
                { "host": "192.168.1.50", "port": 54321 }
              ],
              "pairingToken": "abc123",
              "tlsCertificateSha256": "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
              "expiresAtUnixTimeSeconds": 1893456000,
              "transport": "https"
            }
        """.trimIndent()
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        return "nearshare://pair?payload=$encoded"
    }
}
