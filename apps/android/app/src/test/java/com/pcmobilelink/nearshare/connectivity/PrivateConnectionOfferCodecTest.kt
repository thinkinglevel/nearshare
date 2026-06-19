package com.pcmobilelink.nearshare.connectivity

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateConnectionOfferCodecTest {
    @Test
    fun decode_withEncodedOffer_returnsOfferFields() {
        val offer = privateConnectionOffer()
        val encoded = PrivateConnectionOfferCodec.encode(offer)

        val decoded = PrivateConnectionOfferCodec.decode(
            encoded,
            currentUnixTimeSeconds = 1_893_456_100,
        )

        assertEquals(1, decoded.version)
        assertEquals("NearShare-Test", decoded.connectionName)
        assertEquals("nearshare123", decoded.password)
        assertEquals("K7MQ9T2P4", decoded.code)
        assertEquals(1_893_456_000L, decoded.createdAtUnixTimeSeconds)
        assertEquals(1_893_456_600L, decoded.expiresAtUnixTimeSeconds)
    }

    @Test
    fun decode_withExpiredOffer_rejectsOffer() {
        val encoded = PrivateConnectionOfferCodec.encode(privateConnectionOffer())

        assertThrows(IllegalArgumentException::class.java) {
            PrivateConnectionOfferCodec.decode(
                encoded,
                currentUnixTimeSeconds = 1_893_456_601,
            )
        }
    }

    @Test
    fun encodeWifiQrPayload_withPassword_usesStandardWifiQrFormatAndEscapesFields() {
        val offer = privateConnectionOffer(
            connectionName = "Near;Share: \"Phone\"",
            password = "pass\\word;123",
        )

        val payload = PrivateConnectionOfferCodec.encodeWifiQrPayload(offer)

        assertEquals("WIFI:T:WPA;S:Near\\;Share\\: \\\"Phone\\\";P:pass\\\\word\\;123;;", payload)
    }

    @Test
    fun encodeWifiQrPayload_withoutPassword_usesNoPasswordFormat() {
        val offer = privateConnectionOffer(password = "")

        val payload = PrivateConnectionOfferCodec.encodeWifiQrPayload(offer)

        assertEquals("WIFI:T:nopass;S:NearShare-Test;;", payload)
    }

    @Test
    fun securityCode_create_returnsNineAllowedCharacters() {
        val code = PrivateConnectionSecurityCode.create(SecureRandom(byteArrayOf(1, 2, 3, 4)))

        assertEquals(9, code.length)
        assertTrue(code.all { it in "23456789ABCDEFGHJKMNPQRSTUVWXYZ" })
    }

    @Test
    fun securityCode_format_groupsNineCharactersForManualEntry() {
        assertEquals("K7M-Q9T-2P4", PrivateConnectionSecurityCode.format("k7mq9t2p4"))
    }

    @Test
    fun securityCode_isValid_rejectsAmbiguousCharacters() {
        assertTrue(!PrivateConnectionSecurityCode.isValid("O0I1LZ9XQ"))
    }

    private fun privateConnectionOffer(
        connectionName: String = "NearShare-Test",
        password: String = "nearshare123",
    ): PrivateConnectionOffer {
        return PrivateConnectionOffer(
            connectionName = connectionName,
            password = password,
            code = "K7MQ9T2P4",
            createdAtUnixTimeSeconds = 1_893_456_000,
            expiresAtUnixTimeSeconds = 1_893_456_600,
        )
    }
}
