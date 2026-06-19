package com.pcmobilelink.nearshare.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedDeviceRequestSignatureTest {
    @Test
    fun createSignatureInputIncludesMethodPathTimestampNonceAndBodyHash() {
        val input = PairedDeviceRequestSignature.createSignatureInput(
            method = "get",
            pathAndQuery = "/nearshare/paired-devices/device-1/reachability?check=1",
            timestampUnixTimeSeconds = 1_700_000_000L,
            nonce = "nonce-1",
            body = "hello".toByteArray(Charsets.UTF_8),
        )

        assertEquals(
            "GET\n/nearshare/paired-devices/device-1/reachability?check=1\n1700000000\nnonce-1\nLPJNul-wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ",
            input,
        )
    }

    @Test
    fun signUsesBase64UrlDecodedSharedSecretAsHmacKey() {
        val sharedSecret = PairedDeviceRequestSignature.encodeBase64Url(
            "shared-secret-key-32-bytes-here!!".toByteArray(Charsets.UTF_8),
        )

        val signature = PairedDeviceRequestSignature.sign(
            sharedSecret = sharedSecret,
            method = "GET",
            pathAndQuery = "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/reachability",
            timestampUnixTimeSeconds = 1_700_000_000L,
            nonce = "nonce-1",
            body = ByteArray(0),
        )

        assertEquals("CyiCbWJOc0bM2CgCztT5xT0RNvpAqWbMC2IAQfGzZx4", signature)
    }

    @Test
    fun verifyReturnsTrueOnlyForMatchingSignatureWithinClockSkew() {
        val sharedSecret = PairedDeviceRequestSignature.encodeBase64Url(
            "shared-secret-key-32-bytes-here!!".toByteArray(Charsets.UTF_8),
        )
        val signature = PairedDeviceRequestSignature.sign(
            sharedSecret = sharedSecret,
            method = "GET",
            pathAndQuery = "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/reachability",
            timestampUnixTimeSeconds = 1_700_000_000L,
            nonce = "nonce-1",
            body = ByteArray(0),
        )

        assertTrue(
            PairedDeviceRequestSignature.verify(
                sharedSecret = sharedSecret,
                method = "GET",
                pathAndQuery = "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/reachability",
                timestampUnixTimeSeconds = 1_700_000_000L,
                nonce = "nonce-1",
                body = ByteArray(0),
                signature = signature,
                nowUnixTimeSeconds = 1_700_000_120L,
                allowedClockSkewSeconds = 300L,
            ),
        )

        assertFalse(
            PairedDeviceRequestSignature.verify(
                sharedSecret = sharedSecret,
                method = "GET",
                pathAndQuery = "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/reachability",
                timestampUnixTimeSeconds = 1_700_000_000L,
                nonce = "nonce-1",
                body = ByteArray(0),
                signature = "bad-signature",
                nowUnixTimeSeconds = 1_700_000_120L,
                allowedClockSkewSeconds = 300L,
            ),
        )

        assertFalse(
            PairedDeviceRequestSignature.verify(
                sharedSecret = sharedSecret,
                method = "GET",
                pathAndQuery = "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/reachability",
                timestampUnixTimeSeconds = 1_700_000_000L,
                nonce = "nonce-1",
                body = ByteArray(0),
                signature = signature,
                nowUnixTimeSeconds = 1_700_001_000L,
                allowedClockSkewSeconds = 300L,
            ),
        )
    }

    @Test
    fun signBodyHashMatchesSigningTheOriginalBody() {
        val sharedSecret = PairedDeviceRequestSignature.encodeBase64Url(
            "shared-secret-key-32-bytes-here!!".toByteArray(Charsets.UTF_8),
        )
        val body = "hello from android".toByteArray(Charsets.UTF_8)
        val bodyHash = PairedDeviceRequestSignature.createBodyHash(body)

        val fromBody = PairedDeviceRequestSignature.sign(
            sharedSecret = sharedSecret,
            method = "POST",
            pathAndQuery = "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfers/files",
            timestampUnixTimeSeconds = 1_700_000_000L,
            nonce = "upload-nonce-1",
            body = body,
        )
        val fromBodyHash = PairedDeviceRequestSignature.signBodyHash(
            sharedSecret = sharedSecret,
            method = "POST",
            pathAndQuery = "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfers/files",
            timestampUnixTimeSeconds = 1_700_000_000L,
            nonce = "upload-nonce-1",
            bodySha256Base64Url = bodyHash,
        )

        assertEquals("XQnr5TdqYiCssDf5TLUWmk1BFZ4vh47UjxSMe2NYtcs", bodyHash)
        assertEquals(fromBody, fromBodyHash)
        assertEquals("FZs6Pi29GHau1RQ2MVNnN2hgQMZfD146DM2HBxoVlBQ", fromBodyHash)
    }
}
