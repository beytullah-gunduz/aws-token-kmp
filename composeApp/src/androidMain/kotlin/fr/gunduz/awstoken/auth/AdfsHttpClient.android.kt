package fr.gunduz.awstoken.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.cookies.HttpCookies

actual fun createAdfsHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpCookies)
    install(HttpRedirect) {
        checkHttpMethod = false
        allowHttpsDowngrade = false
    }
    followRedirects = true
}
