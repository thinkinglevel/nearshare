package com.pcmobilelink.nearshare.pairing

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingPayloadCodecTest {
    @Test
    fun decode_withValidNearShareUri_returnsPayloadFields() {
        val uri = nearShareUri(
            """
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
        )

        val payload = PairingPayloadCodec.decode(uri)

        assertEquals(1, payload.version)
        assertEquals("11111111-2222-3333-4444-555555555555", payload.offerId)
        assertEquals("NearShare Test PC", payload.pcName)
        assertEquals("abc123", payload.pairingToken)
        assertEquals("0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF", payload.tlsCertificateSha256)
        assertEquals(1893456000L, payload.expiresAtUnixTimeSeconds)
        assertEquals("https", payload.transport)
        assertEquals(1, payload.endpoints.size)
        assertEquals("192.168.1.50", payload.endpoints[0].host)
        assertEquals(54321, payload.endpoints[0].port)
    }

    @Test
    fun decode_withWrongScheme_rejectsUri() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayloadCodec.decode("https://example.test/pair?payload=abc")
        }
    }

    @Test
    fun decode_withMissingPayload_rejectsUri() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayloadCodec.decode("nearshare://pair")
        }
    }

    @Test
    fun decode_withEndpointPortOutsideTcpRange_rejectsPayload() {
        val uri = nearShareUri(
            """
            {
              "version": 1,
              "offerId": "11111111-2222-3333-4444-555555555555",
              "pcName": "NearShare Test PC",
              "endpoints": [
                { "host": "192.168.1.50", "port": 70000 }
              ],
              "pairingToken": "abc123",
              "tlsCertificateSha256": "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
              "expiresAtUnixTimeSeconds": 1893456000,
              "transport": "https"
            }
            """.trimIndent()
        )

        assertThrows(IllegalArgumentException::class.java) {
            PairingPayloadCodec.decode(uri)
        }
    }

    private fun nearShareUri(json: String): String {
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        return "nearshare://pair?payload=$encoded"
    }
}
