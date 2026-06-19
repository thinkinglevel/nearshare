package com.pcmobilelink.nearshare.connectivity

import java.security.SecureRandom
import java.util.Locale

object PrivateConnectionSecurityCode {
    private const val Alphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val RawLength = 9

    fun create(random: SecureRandom): String {
        return buildString(RawLength) {
            repeat(RawLength) {
                append(Alphabet[random.nextInt(Alphabet.length)])
            }
        }
    }

    fun format(rawCode: String): String {
        val normalized = normalize(rawCode)
        return if (normalized.length == RawLength) {
            "${normalized.substring(0, 3)}-${normalized.substring(3, 6)}-${normalized.substring(6, 9)}"
        } else {
            rawCode
        }
    }

    fun normalize(rawCode: String): String {
        return rawCode
            .filter { it.isLetterOrDigit() }
            .uppercase(Locale.US)
    }

    fun isValid(rawCode: String): Boolean {
        val normalized = normalize(rawCode)
        return normalized.length == RawLength && normalized.all { character -> character in Alphabet }
    }
}
