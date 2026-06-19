package com.pcmobilelink.nearshare.discovery

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NearShareDiscoveryProtocolTest {
    @Test
    fun requestJsonIncludesTargetCertificateFingerprint() {
        val json = JSONObject(NearShareDiscoveryProtocol.requestJson("A".repeat(64)))

        assertEquals("nearshare.discovery.request.v1", json.getString("type"))
        assertEquals("A".repeat(64), json.getString("tlsCertificateSha256"))
    }

    @Test
    fun parseResponseReturnsEndpointsForMatchingCertificateFingerprint() {
        val raw = """
            {
              "type": "nearshare.discovery.response.v1",
              "pcName": "SUBHRANEEL",
              "tlsCertificateSha256": "${"A".repeat(64)}",
              "endpoints": [{ "host": "10.152.205.154", "port": 49152 }],
              "serverTimeUnixSeconds": 1700000000
            }
        """.trimIndent()

        val response = NearShareDiscoveryProtocol.parseResponse(raw, expectedTlsCertificateSha256 = "A".repeat(64))

        assertNotNull(response)
        assertEquals("SUBHRANEEL", response!!.pcName)
        assertEquals("A".repeat(64), response.tlsCertificateSha256)
        assertEquals("10.152.205.154", response.endpoints.single().host)
        assertEquals(49152, response.endpoints.single().port)
        assertEquals(1_700_000_000L, response.serverTimeUnixSeconds)
    }

    @Test
    fun parseResponseRejectsDifferentCertificateFingerprint() {
        val raw = """
            {
              "type": "nearshare.discovery.response.v1",
              "pcName": "Other PC",
              "tlsCertificateSha256": "${"B".repeat(64)}",
              "endpoints": [{ "host": "192.168.1.20", "port": 50000 }],
              "serverTimeUnixSeconds": 1700000000
            }
        """.trimIndent()

        assertNull(NearShareDiscoveryProtocol.parseResponse(raw, expectedTlsCertificateSha256 = "A".repeat(64)))
    }
}
