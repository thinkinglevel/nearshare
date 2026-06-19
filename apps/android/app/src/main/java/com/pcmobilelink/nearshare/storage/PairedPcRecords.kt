package com.pcmobilelink.nearshare.storage

object PairedPcRecords {
    fun addOrUpdate(
        existingRecords: List<PairedPcRecord>,
        record: PairedPcRecord,
    ): List<PairedPcRecord> {
        return deduplicate(existingRecords.filterNot { existing -> existing.matchesSamePc(record) } + record)
    }

    fun deduplicate(existingRecords: List<PairedPcRecord>): List<PairedPcRecord> {
        return existingRecords.fold(emptyList()) { records, record ->
            records.filterNot { existing -> existing.matchesSamePc(record) } + record
        }
    }

    fun removeByDeviceId(
        existingRecords: List<PairedPcRecord>,
        pcDeviceId: String,
    ): List<PairedPcRecord> {
        return existingRecords.filterNot { existing -> existing.pcDeviceId == pcDeviceId }
    }

    fun containsDeviceId(existingRecords: List<PairedPcRecord>, pcDeviceId: String): Boolean {
        return existingRecords.any { existing -> existing.pcDeviceId == pcDeviceId }
    }

    private fun PairedPcRecord.matchesSamePc(other: PairedPcRecord): Boolean {
        return pcDeviceId == other.pcDeviceId ||
            tlsCertificateSha256.equals(other.tlsCertificateSha256, ignoreCase = true)
    }
}
