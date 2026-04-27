package fr.gunduz.awstoken.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.cookies.HttpCookies

actual fun createAdfsHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpCookies)

    // Ktor's `followRedirects = true` on HttpClient only applies to GET/HEAD
    // via the default HttpRedirect plugin. ADFS answers the credential POST
    // with a 302 to the same URL (classic Post/Redirect/Get), so we must
    // explicitly allow the plugin to follow non-safe-method redirects or the
    // flow stalls at hop 1 with a zero-byte body.
    install(HttpRedirect) {
        checkHttpMethod = false
        allowHttpsDowngrade = false
    }
    followRedirects = true
    engine {
        config {
            // OkHttp picks up ProxySelector.getDefault() automatically; we
            // only need `java.net.useSystemProxies=true` set before the JVM
            // initializes ProxySelector (done in main.kt). Per-request
            // timeouts are deliberately generous — corporate IdPs behind a
            // VPN + proxy can be slow on the first round trip.
            connectTimeout(java.time.Duration.ofSeconds(30))
            readTimeout(java.time.Duration.ofSeconds(60))
            writeTimeout(java.time.Duration.ofSeconds(30))

            // On macOS, install a DNS fallback that shells out to
            // `dscacheutil` when the JVM resolver can't see a hostname.
            // This recovers the case where VPN-pushed DNS (via Network
            // Extensions) is visible to the system resolver but not to
            // `InetAddress.getByName` — the exact failure mode for
            // corporate ADFS hosts that only exist in split-horizon DNS.
            if (MacOsSystemDns.IS_MAC) {
                dns(MacOsSystemDns())
            }
        }
    }
}
