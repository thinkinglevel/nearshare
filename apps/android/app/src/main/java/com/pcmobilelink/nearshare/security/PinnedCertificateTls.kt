package com.pcmobilelink.nearshare.security

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class PinnedCertificateTls(expectedSha256Fingerprint: String) {
    private val expectedFingerprint = CertificateFingerprint.normalize(expectedSha256Fingerprint)
    private val trustManager = FingerprintTrustManager(expectedFingerprint)

    val sslSocketFactory: SSLSocketFactory
    val hostnameVerifier: HostnameVerifier = HostnameVerifier { _, session ->
        runCatching {
            val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
            certificate != null && CertificateFingerprint.matches(
                actual = CertificateFingerprint.sha256Hex(certificate),
                expected = expectedFingerprint,
            )
        }.getOrDefault(false)
    }

    init {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), null)
        sslSocketFactory = context.socketFactory
    }

    private class FingerprintTrustManager(
        private val expectedFingerprint: String,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are not supported.")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull()
                ?: throw CertificateException("Server did not provide a certificate.")
            val actualFingerprint = CertificateFingerprint.sha256Hex(certificate)
            if (!CertificateFingerprint.matches(actualFingerprint, expectedFingerprint)) {
                throw CertificateException("Server certificate fingerprint does not match the pairing payload.")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
