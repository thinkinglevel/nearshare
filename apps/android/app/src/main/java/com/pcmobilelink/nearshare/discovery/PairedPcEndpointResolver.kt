package com.pcmobilelink.nearshare.discovery

import com.pcmobilelink.nearshare.storage.PairedPcRecord
import com.pcmobilelink.nearshare.transfer.PairedPcReachabilityClient
import com.pcmobilelink.nearshare.transfer.PairedPcReachabilityResult
import com.pcmobilelink.nearshare.receiver.AndroidReceiveEndpointDiscoveryClient

class PairedPcEndpointResolver(
    private val discoverCandidates: (PairedPcRecord) -> List<NearShareDiscoveryResponse> = { record ->
        NearShareUdpDiscoveryClient().discover(record.tlsCertificateSha256)
    },
    private val discoverAndroidReceiveCandidates: (PairedPcRecord) -> List<NearShareDiscoveryResponse> = { record ->
        AndroidReceiveEndpointDiscoveryClient().discover(record)
    },
    private val checkReachability: (PairedPcRecord) -> PairedPcReachabilityResult = { record ->
        PairedPcReachabilityClient().checkReachability(record)
    },
    private val diagnostics: (String) -> Unit = {},
) {
    fun resolve(record: PairedPcRecord): PairedPcRecord {
        diagnostics("Resolve endpoint started device=${record.pcName} stored=${record.endpoints.joinToString { "${it.host}:${it.port}" }}")
        val windowsResponses = discoverCandidates(record)
        diagnostics("Windows discovery candidates=${windowsResponses.sumOf { it.endpoints.size }}")
        val androidResponses = discoverAndroidReceiveCandidates(record)
        diagnostics("Android receive discovery candidates=${androidResponses.sumOf { it.endpoints.size }}")
        val discoveredCandidates = (windowsResponses + androidResponses)
            .filter { response -> response.tlsCertificateSha256.equals(record.tlsCertificateSha256, ignoreCase = true) }
            .flatMap { response ->
                response.endpoints.map { endpoint ->
                    record.copy(endpoints = listOf(endpoint))
                }
            }

        for (candidate in discoveredCandidates) {
            diagnostics("Checking discovered endpoint ${candidate.endpoints.singleOrNull()?.let { "${it.host}:${it.port}" } ?: "none"}")
            if (isReachable(candidate, expectedPcDeviceId = record.pcDeviceId)) {
                diagnostics("Resolved discovered endpoint ${candidate.endpoints.singleOrNull()?.let { "${it.host}:${it.port}" } ?: "none"}")
                return candidate
            }
        }

        diagnostics("Checking stored endpoint")
        if (isReachable(record, expectedPcDeviceId = record.pcDeviceId)) {
            diagnostics("Resolved stored endpoint")
            return record
        }

        diagnostics("Resolve endpoint failed device=${record.pcName}")
        throw IllegalStateException(
            "Could not connect to ${record.pcName}. Open NearShare on both devices, create a private connection if needed, then try again.",
        )
    }

    private fun isReachable(record: PairedPcRecord, expectedPcDeviceId: String): Boolean {
        return runCatching {
            val result = checkReachability(record)
            result.status == "reachable" && result.pcDeviceId == expectedPcDeviceId
        }.getOrDefault(false)
    }
}
