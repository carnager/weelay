package com.weelay.app.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

class PinningTrustManagerTest {

    // Self-signed test certificate; fingerprint from `openssl x509 -noout -fingerprint -sha256`.
    private val pem = """
        -----BEGIN CERTIFICATE-----
        MIIBgjCCASmgAwIBAgIUB5JiyYi3GkBqKeMAG1OmckAFgJAwCgYIKoZIzj0EAwIw
        FjEUMBIGA1UEAwwLd2VlbGF5LXRlc3QwIBcNMjYxMDE4MTMwNjQ1WhgPMjEyNjA5
        MjQxMzA2NDVaMBYxFDASBgNVBAMMC3dlZWxheS10ZXN0MFkwEwYHKoZIzj0CAQYI
        KoZIzj0DAQcDQgAEwTPVIyrKb5a/lBe27vWIf7JJTZvqY3SysPbCVR67Tp1D1lLj
        2XyGOhwyK/F+5Tz0y7MVdYJThVGs35RAFTHYE6NTMFEwHQYDVR0OBBYEFH/289d5
        cZoU/Yytf4qmrfl7QTeEMB8GA1UdIwQYMBaAFH/289d5cZoU/Yytf4qmrfl7QTeE
        MA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDRwAwRAIgJL2Ly86f/unQogqy
        KTuKRo3tOPXoVPFxzF4m+cKrbVoCICE6fkarOtbO77BO5FyLlcRUUnQt5cNrt8ia
        07HLxYiI
        -----END CERTIFICATE-----
    """.trimIndent()
    private val fingerprint =
        "63:C0:CA:1F:57:F8:DA:FD:73:56:24:37:22:40:D2:8D:C9:75:AF:F8:07:5E:3E:BA:33:90:57:22:05:ED:DD:04"

    private val cert: X509Certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(pem.byteInputStream()) as X509Certificate

    /** Stands in for the system trust store: accepts or refuses everything. */
    private class FakeSystem(private val trusts: Boolean) : X509ExtendedTrustManager() {
        private fun check() { if (!trusts) throw CertificateException("untrusted") }
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String, s: Socket?) = check()
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String, e: SSLEngine?) = check()
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = check()
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String, s: Socket?) = Unit
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String, e: SSLEngine?) = Unit
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun fingerprintMatchesOpenssl() {
        assertEquals(fingerprint, PinningTrustManager.fingerprint(cert))
    }

    @Test
    fun systemTrustedCertificatePassesWithoutAsking() {
        var rejected: X509Certificate? = null
        PinningTrustManager(FakeSystem(trusts = true), null) { rejected = it }
            .checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA", null as Socket?)
        assertNull(rejected)
    }

    @Test
    fun pinnedCertificatePassesWhenSystemRefuses() {
        var rejected: X509Certificate? = null
        PinningTrustManager(FakeSystem(trusts = false), fingerprint.lowercase()) { rejected = it }
            .checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA", null as Socket?)
        assertNull(rejected)
    }

    @Test
    fun unpinnedOrMismatchedCertificateIsReportedAndRefused() {
        for (pin in listOf(null, "00:11:22")) {
            var rejected: X509Certificate? = null
            try {
                PinningTrustManager(FakeSystem(trusts = false), pin) { rejected = it }
                    .checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA", null as Socket?)
                fail("expected the certificate to be refused (pin=$pin)")
            } catch (_: CertificateException) {
            }
            assertSame(cert, rejected)
        }
    }
}
