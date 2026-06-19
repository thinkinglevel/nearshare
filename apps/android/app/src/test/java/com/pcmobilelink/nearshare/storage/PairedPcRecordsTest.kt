package com.pcmobilelink.nearshare.storage

import com.pcmobilelink.nearshare.pairing.PairingEndpointCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedPcRecordsTest {
    @Test
    fun addOrUpdateWithSamePcDeviceIdReplacesExistingRecord() {
        val records = PairedPcRecords.addOrUpdate(
            existingRecords = listOf(record(pcDeviceId = "pc-a", pcName = "Old PC", fingerprint = FINGERPRINT_A)),
            record = record(pcDeviceId = "pc-a", pcName = "New PC", fingerprint = FINGERPRINT_B),
        )

        assertEquals(1, records.size)
        assertEquals("New PC", records.single().pcName)
        assertEquals(FINGERPRINT_B, records.single().tlsCertificateSha256)
    }

    @Test
    fun addOrUpdateWithSameTlsFingerprintReplacesExistingRecord() {
        val records = PairedPcRecords.addOrUpdate(
            existingRecords = listOf(record(pcDeviceId = "old-generated-id", pcName = "NearShare", fingerprint = FINGERPRINT_A)),
            record = record(pcDeviceId = "new-generated-id", pcName = "NearShare", fingerprint = FINGERPRINT_A.lowercase()),
        )

        assertEquals(1, records.size)
        assertEquals("new-generated-id", records.single().pcDeviceId)
        assertEquals(FINGERPRINT_A.lowercase(), records.single().tlsCertificateSha256)
    }

    @Test
    fun deduplicateKeepsLatestRecordForSameTlsFingerprint() {
        val records = PairedPcRecords.deduplicate(
            listOf(
                record(pcDeviceId = "old-generated-id", pcName = "NearShare", fingerprint = FINGERPRINT_A),
                record(pcDeviceId = "new-generated-id", pcName = "NearShare", fingerprint = FINGERPRINT_A.lowercase()),
            ),
        )

        assertEquals(1, records.size)
        assertEquals("new-generated-id", records.single().pcDeviceId)
    }

    @Test
    fun removeByDeviceIdRemovesOnlyMatchingRecord() {
        val records = PairedPcRecords.removeByDeviceId(
            existingRecords = listOf(
                record(pcDeviceId = "pc-a", pcName = "PC A", fingerprint = FINGERPRINT_A),
                record(pcDeviceId = "pc-b", pcName = "PC B", fingerprint = FINGERPRINT_B),
            ),
            pcDeviceId = "pc-a",
        )

        assertEquals(1, records.size)
        assertEquals("pc-b", records.single().pcDeviceId)
    }

    @Test
    fun containsDeviceIdReturnsTrueOnlyForExistingRecord() {
        val records = listOf(record(pcDeviceId = "pc-a", pcName = "PC A", fingerprint = FINGERPRINT_A))

        assertTrue(PairedPcRecords.containsDeviceId(records, "pc-a"))
        assertFalse(PairedPcRecords.containsDeviceId(records, "pc-b"))
    }

    private fun record(pcDeviceId: String, pcName: String, fingerprint: String): PairedPcRecord {
        return PairedPcRecord(
            pcDeviceId = pcDeviceId,
            pcName = pcName,
            endpoints = listOf(PairingEndpointCandidate(host = "192.168.1.10", port = 50371)),
            tlsCertificateSha256 = fingerprint,
            sharedSecret = "secret-$pcDeviceId",
            pairedAtUnixTimeSeconds = 1_781_000_000,
        )
    }

    private companion object {
        private const val FINGERPRINT_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        private const val FINGERPRINT_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
    }
}
