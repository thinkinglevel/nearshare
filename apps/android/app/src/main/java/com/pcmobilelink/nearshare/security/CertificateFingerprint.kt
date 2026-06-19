package com.pcmobilelink.nearshare.security

import java.security.MessageDigest
import java.security.cert.X509Certificate

object CertificateFingerprint {
    private val sha256HexPattern = Regex("^[0-9A-F]{64}$")

    fun normalize(value: String): String {
        val normalized = value
            .replace(":", "")
            .replace(" ", "")
            .trim()
            .uppercase()

        require(sha256HexPattern.matches(normalized)) {
            "SHA-256 certificate fingerprint must be 64 hexadecimal characters."
        }

        return normalized
    }

    fun matches(actual: String, expected: String): Boolean {
        return runCatching { normalize(actual) == normalize(expected) }.getOrDefault(false)
    }

    fun sha256Hex(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(separator = "") { byte -> "%02X".format(byte) }
    }
}
