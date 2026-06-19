package com.pcmobilelink.nearshare.pairing

import java.security.SecureRandom

object PairingShortCode {
    private const val CODE_LENGTH = 9
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(CODE_LENGTH)
        random.nextBytes(bytes)
        return buildString(CODE_LENGTH) {
            bytes.forEach { byte ->
                val index = (byte.toInt() and 0xFF) % ALPHABET.length
                append(ALPHABET[index])
            }
        }
    }

    fun normalize(value: String): String {
        return value.filter { it.isLetterOrDigit() }.uppercase()
    }

    fun isValid(value: String): Boolean {
        val normalized = normalize(value)
        return normalized.length == CODE_LENGTH && normalized.all { character -> ALPHABET.contains(character) }
    }

    fun format(value: String): String {
        val normalized = normalize(value)
        return if (normalized.length == CODE_LENGTH) {
            "${normalized.substring(0, 3)}-${normalized.substring(3, 6)}-${normalized.substring(6, 9)}"
        } else {
            value
        }
    }
}
