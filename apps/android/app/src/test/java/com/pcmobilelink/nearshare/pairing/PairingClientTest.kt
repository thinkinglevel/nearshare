package com.pcmobilelink.nearshare.pairing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingClientTest {
    @Test
    fun pairingRequestBodyOmitsReceiveMetadataWhenReceiverIsNotRunning() {
        val body = PairingClient.pairingRequestBody(
            payload = payload(),
            deviceName = "Pixel Test",
            devicePublicKey = "test-public-key",
            receiveEndpoints = emptyList(),
            receiveTlsCertificateSha256 = null,
        )
        val json = JSONObject(body.toString(Charsets.UTF_8))

        assertEquals("offer-1", json.getString("offerId"))
        assertEquals("token-1", json.getString("pairingToken"))
        assertEquals("Pixel Test", json.getString("deviceName"))
        assertEquals("test-public-key", json.getString("devicePublicKey"))
        assertFalse(json.has("receiveEndpoints"))
        assertFalse(json.has("receiveTlsCertificateSha256"))
    }

    @Test
    fun pairingRequestBodyIncludesReceiveMetadataWhenReceiverIsRunning() {
        val body = PairingClient.pairingRequestBody(
            payload = payload(),
            deviceName = "Pixel Test",
            devicePublicKey = "test-public-key",
            receiveEndpoints = listOf(PairingEndpointCandidate("192.168.43.1", 49321)),
            receiveTlsCertificateSha256 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        )
        val json = JSONObject(body.toString(Charsets.UTF_8))

        val endpoints = json.getJSONArray("receiveEndpoints")
        assertEquals(1, endpoints.length())
        assertEquals("192.168.43.1", endpoints.getJSONObject(0).getString("host"))
        assertEquals(49321, endpoints.getJSONObject(0).getInt("port"))
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            json.getString("receiveTlsCertificateSha256"),
        )
        assertTrue(json.get("receiveEndpoints") is JSONArray)
    }

    private fun payload(): PairingPayload {
        return PairingPayload(
            version = 1,
            offerId = "offer-1",
            pcName = "NearShare Test PC",
            endpoints = listOf(PairingEndpointCandidate("127.0.0.1", 50371)),
            pairingToken = "token-1",
            tlsCertificateSha256 = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            expiresAtUnixTimeSeconds = 1_700_000_000L,
            transport = "https",
        )
    }
}
