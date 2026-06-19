package com.pcmobilelink.nearshare.receiver

import android.content.Context
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

class AndroidReceiveCertificateStore(private val context: Context) {
    fun loadOrCreate(): AndroidReceiveCertificate {
        ensureBouncyCastleProvider()
        val certificateFile = File(context.filesDir, CERTIFICATE_FILE_NAME)
        val keyStore = KeyStore.getInstance("PKCS12")
        if (certificateFile.exists()) {
            certificateFile.inputStream().use { input -> keyStore.load(input, PASSWORD) }
        } else {
            keyStore.load(null, PASSWORD)
            val keyPair = generateKeyPair()
            val certificate = createSelfSignedCertificate(keyPair)
            keyStore.setKeyEntry(ALIAS, keyPair.private, PASSWORD, arrayOf(certificate))
            certificateFile.outputStream().use { output -> keyStore.store(output, PASSWORD) }
        }

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, PASSWORD)
        val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
        return AndroidReceiveCertificate(
            keyManagers = keyManagerFactory.keyManagers,
            tlsCertificateSha256 = sha256Fingerprint(certificate),
        )
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        return generator.generateKeyPair()
    }

    private fun createSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - FIVE_MINUTES_MILLIS)
        val notAfter = Date(now + CERTIFICATE_VALIDITY_MILLIS)
        val subject = X500Name("CN=NearShare Android Receive")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(160, SecureRandom()).abs(),
            notBefore,
            notAfter,
            subject,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        builder.addExtension(
            Extension.extendedKeyUsage,
            true,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        certificate.verify(keyPair.public)
        return certificate
    }

    private fun sha256Fingerprint(certificate: X509Certificate): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return hash.joinToString(separator = "") { byte -> "%02X".format(byte) }
    }

    private fun ensureBouncyCastleProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private companion object {
        private const val CERTIFICATE_FILE_NAME = "nearshare-receive-server.p12"
        private const val ALIAS = "nearshare-receive"
        private val PASSWORD = "nearshare-local-receiver".toCharArray()
        private const val FIVE_MINUTES_MILLIS = 5L * 60L * 1000L
        private const val CERTIFICATE_VALIDITY_MILLIS = 3650L * 24L * 60L * 60L * 1000L
    }
}

data class AndroidReceiveCertificate(
    val keyManagers: Array<KeyManager>,
    val tlsCertificateSha256: String,
)
