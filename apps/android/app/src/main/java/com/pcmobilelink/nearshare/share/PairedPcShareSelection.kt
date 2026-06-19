package com.pcmobilelink.nearshare.share

import com.pcmobilelink.nearshare.storage.PairedPcRecord

object PairedPcShareSelection {
    fun selectDirectTarget(records: List<PairedPcRecord>, requestedPcDeviceId: String?): PairedPcRecord? {
        val requested = requestedPcDeviceId?.trim().orEmpty()
        if (requested.isEmpty()) {
            return null
        }

        return records.firstOrNull { it.pcDeviceId.equals(requested, ignoreCase = true) }
    }

    fun selectInitialDropdownTarget(
        records: List<PairedPcRecord>,
        requestedPcDeviceId: String?,
        lastSelectedPcDeviceId: String?,
    ): PairedPcRecord? {
        selectDirectTarget(records, requestedPcDeviceId)?.let { return it }

        val lastSelected = lastSelectedPcDeviceId?.trim().orEmpty()
        if (lastSelected.isEmpty()) {
            return null
        }

        return records.firstOrNull { it.pcDeviceId.equals(lastSelected, ignoreCase = true) }
    }
}
