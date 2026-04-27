package fr.gunduz.awstoken.auth

import io.ktor.client.HttpClient

/**
 * Platform-specific HTTP client for the ADFS + STS flow. Desktop uses the
 * **OkHttp** engine so that `ProxySelector.getDefault()` — populated by
 * `java.net.useSystemProxies=true` — is honoured (corporate networks that
 * require an HTTP proxy to reach internal hosts would otherwise fail with
 * `UnresolvedAddressException` because the pure-Kotlin CIO engine tries to
 * resolve the hostname directly instead of handing it to the proxy).
 *
 * Android uses OkHttp for the same reason plus because it's the blessed
 * engine for mobile.
 */
expect fun createAdfsHttpClient(): HttpClient
