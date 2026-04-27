package fr.gunduz.awstoken.aws

import fr.gunduz.awstoken.auth.AdfsAuthenticator
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal AWS SigV4 signer for `Action=...`-style Query APIs that are
 * POSTed as `application/x-www-form-urlencoded`. Deliberately not a generic
 * signer — we only need it to hit global-service endpoints (IAM, STS) with
 * STS session credentials, so the scope is narrow and the code stays short
 * enough to audit by eye.
 *
 * Not depending on aws-sdk-kotlin keeps the binary size and build time
 * down; pulling in the full SDK just to fetch an account alias would be
 * wildly disproportionate.
 */
internal object SigV4Signer {
    private val DATE_HEADER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd")

    data class SignedRequest(
        val method: String,
        val url: String,
        val body: String,
        val headers: Map<String, String>,
    )

    fun signFormPost(
        host: String,
        path: String,
        region: String,
        service: String,
        body: String,
        credentials: AdfsAuthenticator.StsCredentials,
        now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),
    ): SignedRequest {
        val amzDate = now.format(DATE_HEADER)
        val dateStamp = now.format(DATE_STAMP)
        val contentType = "application/x-www-form-urlencoded; charset=utf-8"

        // Canonical request
        val canonicalHeaders = linkedMapOf(
            "content-type" to contentType,
            "host" to host,
            "x-amz-date" to amzDate,
            "x-amz-security-token" to credentials.sessionToken,
        )
        val signedHeaders = canonicalHeaders.keys.joinToString(";")
        val canonicalHeadersBlock = canonicalHeaders
            .entries.joinToString("") { "${it.key}:${it.value}\n" }
        val payloadHash = hex(sha256(body.toByteArray(Charsets.UTF_8)))
        val canonicalRequest = listOf(
            "POST",
            path,
            "", // canonical query string (form body carries params)
            canonicalHeadersBlock,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")

        // String to sign
        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            credentialScope,
            hex(sha256(canonicalRequest.toByteArray(Charsets.UTF_8))),
        ).joinToString("\n")

        // Signing key
        val kSecret = ("AWS4" + credentials.secretAccessKey).toByteArray(Charsets.UTF_8)
        val kDate = hmac(kSecret, dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        val kSigning = hmac(kService, "aws4_request")
        val signature = hex(hmac(kSigning, stringToSign))

        val authorization = "AWS4-HMAC-SHA256 " +
            "Credential=${credentials.accessKeyId}/$credentialScope, " +
            "SignedHeaders=$signedHeaders, " +
            "Signature=$signature"

        return SignedRequest(
            method = "POST",
            url = "https://$host$path",
            body = body,
            headers = mapOf(
                "Content-Type" to contentType,
                "X-Amz-Date" to amzDate,
                "X-Amz-Security-Token" to credentials.sessionToken,
                "Authorization" to authorization,
            ),
        )
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
