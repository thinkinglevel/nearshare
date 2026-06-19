package com.pcmobilelink.nearshare.transfer

import com.pcmobilelink.nearshare.pairing.PairingEndpointCandidate
import com.pcmobilelink.nearshare.security.PairedDeviceRequestSignature
import com.pcmobilelink.nearshare.storage.PairedPcRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class PairedPcReachabilityClientTest {
    @Test
    fun reachabilityUrlUsesFirstPairedPcEndpointAndDeviceId() {
        val record = pairedPcRecord()

        val url = PairedPcReachabilityClient.reachabilityUrl(record)

        assertEquals(
            "https://192.168.1.50:50371/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/reachability",
            url,
        )
    }

    @Test
    fun signedHeadersIncludeDeviceTimestampNonceAndSignature() {
        val sharedSecret = PairedDeviceRequestSignature.encodeBase64Url(
            "shared-secret-key-32-bytes-here!!".toByteArray(Charsets.UTF_8),
        )

        val headers = PairedPcReachabilityClient.signedHeaders(
            pcDeviceId = "8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b",
            sharedSecret = sharedSecret,
            method = "GET",
            pathAndQuery = "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/reachability",
            timestampUnixTimeSeconds = 1_700_000_000L,
            nonce = "nonce-1",
        )

        assertEquals("8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b", headers["X-NearShare-Device-Id"])
        assertEquals("1700000000", headers["X-NearShare-Timestamp"])
        assertEquals("nonce-1", headers["X-NearShare-Nonce"])
        assertEquals("CyiCbWJOc0bM2CgCztT5xT0RNvpAqWbMC2IAQfGzZx4", headers["X-NearShare-Signature"])
    }

    private fun pairedPcRecord(): PairedPcRecord {
        return PairedPcRecord(
            pcDeviceId = "8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b",
            pcName = "NearShare Test PC",
            endpoints = listOf(PairingEndpointCandidate("192.168.1.50", 50371)),
            tlsCertificateSha256 = "A".repeat(64),
            sharedSecret = PairedDeviceRequestSignature.encodeBase64Url(
                "shared-secret-key-32-bytes-here!!".toByteArray(Charsets.UTF_8),
            ),
            pairedAtUnixTimeSeconds = 1_700_000_000L,
        )
    }
}
