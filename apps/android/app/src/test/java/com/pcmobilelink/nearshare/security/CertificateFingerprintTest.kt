package com.pcmobilelink.nearshare.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateFingerprintTest {
    @Test
    fun normalize_acceptsLowercaseAndColonSeparatedSha256() {
        val normalized = CertificateFingerprint.normalize(
            "01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef"
        )

        assertEquals(
            "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
            normalized,
        )
    }

    @Test
    fun normalize_rejectsNonSha256Length() {
        assertThrows(IllegalArgumentException::class.java) {
            CertificateFingerprint.normalize("012345")
        }
    }

    @Test
    fun matches_comparesNormalizedValues() {
        assertTrue(
            CertificateFingerprint.matches(
                actual = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                expected = "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF",
            )
        )
    }

    @Test
    fun matches_returnsFalseForDifferentValues() {
        assertFalse(
            CertificateFingerprint.matches(
                actual = "1123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
                expected = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
            )
        )
    }
}
