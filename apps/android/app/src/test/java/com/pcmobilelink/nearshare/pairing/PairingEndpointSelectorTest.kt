package com.pcmobilelink.nearshare.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingEndpointSelectorTest {
    @Test
    fun pairingRequestsUrl_usesFirstHttpsEndpoint() {
        val payload = payloadWithEndpoints(
            listOf(
                PairingEndpointCandidate(host = "192.168.1.50", port = 54321),
                PairingEndpointCandidate(host = "10.0.0.10", port = 12345),
            )
        )

        val url = PairingEndpointSelector.pairingRequestsUrl(payload)

        assertEquals("https://192.168.1.50:54321/nearshare/pairing/requests", url)
    }

    @Test
    fun pairingRequestResultUrl_includesRequestId() {
        val payload = payloadWithEndpoints(
            listOf(PairingEndpointCandidate(host = "192.168.1.50", port = 54321))
        )

        val url = PairingEndpointSelector.pairingRequestResultUrl(
            payload,
            requestId = "22222222-3333-4444-5555-666666666666",
        )

        assertEquals(
            "https://192.168.1.50:54321/nearshare/pairing/requests/22222222-3333-4444-5555-666666666666",
            url,
        )
    }

    @Test
    fun pairingRequestsUrl_withNoEndpoints_rejectsPayload() {
        val payload = payloadWithEndpoints(emptyList())

        assertThrows(IllegalArgumentException::class.java) {
            PairingEndpointSelector.pairingRequestsUrl(payload)
        }
    }

    private fun payloadWithEndpoints(endpoints: List<PairingEndpointCandidate>): PairingPayload {
        return PairingPayload(
            version = 1,
            offerId = "11111111-2222-3333-4444-555555555555",
            pcName = "NearShare Test PC",
            endpoints = endpoints,
            pairingToken = "abc123",
            tlsCertificateSha256 = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
            expiresAtUnixTimeSeconds = 1893456000L,
            transport = "https",
        )
    }
}
