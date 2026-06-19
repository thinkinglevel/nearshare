package com.pcmobilelink.nearshare.discovery

import com.pcmobilelink.nearshare.pairing.PairingEndpointCandidate
import com.pcmobilelink.nearshare.security.PairedDeviceRequestSignature
import com.pcmobilelink.nearshare.storage.PairedPcRecord
import com.pcmobilelink.nearshare.transfer.PairedPcReachabilityResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairedPcEndpointResolverTest {
    @Test
    fun resolveUsesDiscoveredEndpointAfterSignedReachabilitySucceeds() {
        val original = pairedPcRecord(endpoints = listOf(PairingEndpointCandidate("192.168.1.50", 62975)))
        val resolver = PairedPcEndpointResolver(
            discoverCandidates = {
                listOf(
                    NearShareDiscoveryResponse(
                        pcName = "SUBHRANEEL",
                        tlsCertificateSha256 = "A".repeat(64),
                        endpoints = listOf(PairingEndpointCandidate("10.152.205.154", 49152)),
                        serverTimeUnixSeconds = 1_700_000_000L,
                    ),
                )
            },
            discoverAndroidReceiveCandidates = { emptyList() },
            checkReachability = { candidate ->
                assertEquals("10.152.205.154", candidate.endpoints.single().host)
                assertEquals(49152, candidate.endpoints.single().port)
                PairedPcReachabilityResult("reachable", original.pcDeviceId, 1_700_000_001L)
            },
        )

        val resolved = resolver.resolve(original)

        assertEquals("10.152.205.154", resolved.endpoints.single().host)
        assertEquals(49152, resolved.endpoints.single().port)
    }

    @Test
    fun resolveUsesAndroidReceiveDiscoveryEndpointAfterPrivateConnectionChangesRoute() {
        val original = pairedPcRecord(endpoints = listOf(PairingEndpointCandidate("192.168.1.50", 62975)))
        val resolver = PairedPcEndpointResolver(
            discoverCandidates = { emptyList() },
            discoverAndroidReceiveCandidates = {
                listOf(
                    NearShareDiscoveryResponse(
                        pcName = "Xiaomi M2012K11AI",
                        tlsCertificateSha256 = "A".repeat(64),
                        endpoints = listOf(PairingEndpointCandidate("192.168.49.12", 51044)),
                        serverTimeUnixSeconds = 1_700_000_000L,
                    ),
                )
            },
            checkReachability = { candidate ->
                assertEquals("192.168.49.12", candidate.endpoints.single().host)
                assertEquals(51044, candidate.endpoints.single().port)
                PairedPcReachabilityResult("reachable", original.pcDeviceId, 1_700_000_001L)
            },
        )

        val resolved = resolver.resolve(original)

        assertEquals("192.168.49.12", resolved.endpoints.single().host)
        assertEquals(51044, resolved.endpoints.single().port)
    }

    @Test
    fun resolveFallsBackToExistingEndpointWhenDiscoveryFindsNothingButReachabilityWorks() {
        val original = pairedPcRecord(endpoints = listOf(PairingEndpointCandidate("192.168.1.50", 62975)))
        val resolver = PairedPcEndpointResolver(
            discoverCandidates = { emptyList() },
            discoverAndroidReceiveCandidates = { emptyList() },
            checkReachability = { candidate ->
                assertEquals("192.168.1.50", candidate.endpoints.single().host)
                assertEquals(62975, candidate.endpoints.single().port)
                PairedPcReachabilityResult("reachable", original.pcDeviceId, 1_700_000_001L)
            },
        )

        val resolved = resolver.resolve(original)

        assertEquals(original, resolved)
    }

    @Test
    fun resolveFailsWhenDiscoveredAndExistingEndpointsAreNotReachable() {
        val original = pairedPcRecord(endpoints = listOf(PairingEndpointCandidate("192.168.1.50", 62975)))
        val resolver = PairedPcEndpointResolver(
            discoverCandidates = {
                listOf(
                    NearShareDiscoveryResponse(
                        pcName = "SUBHRANEEL",
                        tlsCertificateSha256 = "A".repeat(64),
                        endpoints = listOf(PairingEndpointCandidate("10.152.205.154", 49152)),
                        serverTimeUnixSeconds = 1_700_000_000L,
                    ),
                )
            },
            discoverAndroidReceiveCandidates = { emptyList() },
            checkReachability = { throw IllegalStateException("not reachable") },
        )

        val error = assertThrows(IllegalStateException::class.java) { resolver.resolve(original) }

        assertEquals(
            "Could not connect to SUBHRANEEL. Open NearShare on both devices, create a private connection if needed, then try again.",
            error.message,
        )
    }

    private fun pairedPcRecord(endpoints: List<PairingEndpointCandidate>): PairedPcRecord {
        return PairedPcRecord(
            pcDeviceId = "8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b",
            pcName = "SUBHRANEEL",
            endpoints = endpoints,
            tlsCertificateSha256 = "A".repeat(64),
            sharedSecret = PairedDeviceRequestSignature.encodeBase64Url(
                "shared-secret-key-32-bytes-here!!".toByteArray(Charsets.UTF_8),
            ),
            pairedAtUnixTimeSeconds = 1_700_000_000L,
        )
    }
}
