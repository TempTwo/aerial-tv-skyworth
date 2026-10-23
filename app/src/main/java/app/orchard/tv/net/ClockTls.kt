package app.orchard.tv.net

import android.content.Context
import android.util.Log
import app.orchard.tv.R
import app.orchard.tv.time.TimeSync
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private const val TAG = "AerialTls"

/**
 * HTTPS that checks certificates against the synced clock, and trusts Let's Encrypt's root.
 *
 * Two things trip this panel's HTTPS up. Its clock can boot years behind, so a certificate issued
 * this year looks "not yet valid". And Android 5 predates ISRG Root X1, which Let's Encrypt
 * chains to. The chain is still verified — signatures, host name, and a root we trust — only the
 * dates come from the clock's network measurement instead of the RTC.
 */
object ClockTls {
    private val extraRoots = mutableListOf<X509Certificate>()

    /** Loads the bundled root, once, from the process start. */
    fun init(context: Context) {
        if (extraRoots.isNotEmpty()) return
        runCatching {
            context.resources.openRawResource(R.raw.isrg_root_x1).use { input ->
                extraRoots += CertificateFactory.getInstance("X.509")
                    .generateCertificate(input) as X509Certificate
            }
        }.onFailure { Log.w(TAG, "could not read the bundled root: ${it.message}") }
    }

    val socketFactory: SSLSocketFactory by lazy {
        val base = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: error("no system trust manager")
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(ClockTrust(base)), SecureRandom())
        }.socketFactory
    }

    internal fun roots(): List<X509Certificate> {
        val out = mutableListOf<X509Certificate>()
        runCatching {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val aliases = store.aliases()
            while (aliases.hasMoreElements()) {
                (store.getCertificate(aliases.nextElement()) as? X509Certificate)?.let(out::add)
            }
        }
        out += extraRoots
        return out
    }
}

private class ClockTrust(private val delegate: X509TrustManager) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            delegate.checkServerTrusted(chain, authType)
            return
        } catch (e: CertificateException) {
            Log.i(TAG, "rejected at the device clock (${e.message}); checking at the synced one")
        }
        val at = Date(TimeSync.nowMs())
        chain.forEach { it.checkValidity(at) }
        for (i in 0 until chain.lastIndex) chain[i].verify(chain[i + 1].publicKey)
        val last = chain.last()
        val trusted = ClockTls.roots().any {
            it.subjectX500Principal == last.issuerX500Principal &&
                runCatching { last.verify(it.publicKey) }.isSuccess
        }
        if (!trusted) throw CertificateException("certificate chain does not end at a trusted root")
    }
}
