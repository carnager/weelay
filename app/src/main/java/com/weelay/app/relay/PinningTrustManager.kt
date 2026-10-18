package com.weelay.app.relay

import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Trusts what Android trusts, plus one certificate the user has explicitly accepted.
 *
 * The system check always runs first, hostname verification included, so a properly issued
 * certificate behaves exactly as before. Only when that fails is the server's leaf certificate
 * compared against [pinnedSha256] — an exact match on the whole certificate, which is why a
 * pinned self-signed certificate is accepted even if its name doesn't match the host. Anything
 * else is reported through [onRejected] (so the UI can offer to trust it) and then rejected.
 */
class PinningTrustManager(
    private val system: X509ExtendedTrustManager,
    private val pinnedSha256: String?,
    private val onRejected: (X509Certificate) -> Unit
) : X509ExtendedTrustManager() {

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        verify(chain) { system.checkServerTrusted(chain, authType, socket) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        verify(chain) { system.checkServerTrusted(chain, authType, engine) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        verify(chain) { system.checkServerTrusted(chain, authType) }

    private inline fun verify(chain: Array<X509Certificate>, systemCheck: () -> Unit) {
        try {
            systemCheck()
        } catch (e: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw e
            if (pinnedSha256 != null && fingerprint(leaf).equals(pinnedSha256, ignoreCase = true)) return
            onRejected(leaf)
            throw e
        }
    }

    // A client never checks client certificates; delegate for completeness.
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        system.checkClientTrusted(chain, authType, socket)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        system.checkClientTrusted(chain, authType, engine)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    companion object {
        /** SHA-256 of the DER-encoded certificate as colon-separated hex, the form `openssl` prints. */
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }

        /** The platform's default trust manager, i.e. the system CA store. */
        fun systemTrustManager(): X509ExtendedTrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
        }
    }
}
