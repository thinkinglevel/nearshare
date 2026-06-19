package com.pcmobilelink.nearshare.share

import com.pcmobilelink.nearshare.pairing.PairingEndpointCandidate
import com.pcmobilelink.nearshare.storage.PairedPcRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairedPcShareSelectionTest {
    @Test
    fun selectDirectTargetReturnsMatchingPcIgnoringCase() {
        val record = pairedPc("ABC-123")

        assertEquals(record, PairedPcShareSelection.selectDirectTarget(listOf(record), "abc-123"))
    }

    @Test
    fun selectDirectTargetReturnsNullForMissingTarget() {
        assertNull(PairedPcShareSelection.selectDirectTarget(listOf(pairedPc("pc-1")), "pc-2"))
    }

    @Test
    fun selectDirectTargetReturnsNullWhenNoTargetRequested() {
        assertNull(PairedPcShareSelection.selectDirectTarget(listOf(pairedPc("pc-1")), null))
    }

    @Test
    fun selectInitialDropdownTargetPrefersDirectTargetOverLastSelection() {
        val firstRecord = pairedPc("pc-1")
        val secondRecord = pairedPc("pc-2")

        assertEquals(
            secondRecord,
            PairedPcShareSelection.selectInitialDropdownTarget(
                records = listOf(firstRecord, secondRecord),
                requestedPcDeviceId = "pc-2",
                lastSelectedPcDeviceId = "pc-1",
            ),
        )
    }

    @Test
    fun selectInitialDropdownTargetUsesLastSelectionWhenStillPaired() {
        val firstRecord = pairedPc("pc-1")
        val secondRecord = pairedPc("pc-2")

        assertEquals(
            secondRecord,
            PairedPcShareSelection.selectInitialDropdownTarget(
                records = listOf(firstRecord, secondRecord),
                requestedPcDeviceId = null,
                lastSelectedPcDeviceId = "PC-2",
            ),
        )
    }

    @Test
    fun selectInitialDropdownTargetFallsBackToSelectDeviceWhenLastSelectionWasDeleted() {
        assertNull(
            PairedPcShareSelection.selectInitialDropdownTarget(
                records = listOf(pairedPc("pc-1"), pairedPc("pc-2")),
                requestedPcDeviceId = null,
                lastSelectedPcDeviceId = "deleted-pc",
            ),
        )
    }

    @Test
    fun selectInitialDropdownTargetFallsBackToSelectDeviceWhenNothingWasPreviouslySelected() {
        assertNull(
            PairedPcShareSelection.selectInitialDropdownTarget(
                records = listOf(pairedPc("pc-1")),
                requestedPcDeviceId = null,
                lastSelectedPcDeviceId = null,
            ),
        )
    }

    private fun pairedPc(id: String): PairedPcRecord {
        return PairedPcRecord(
            pcDeviceId = id,
            pcName = "Test PC",
            endpoints = listOf(PairingEndpointCandidate("192.168.1.50", 50371)),
            tlsCertificateSha256 = "A".repeat(64),
            sharedSecret = "secret",
            pairedAtUnixTimeSeconds = 1_700_000_000L,
        )
    }
}
