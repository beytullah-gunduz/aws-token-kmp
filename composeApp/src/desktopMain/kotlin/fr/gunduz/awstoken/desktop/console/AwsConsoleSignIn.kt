package fr.gunduz.awstoken.desktop.console

import co.touchlab.kermit.Logger
import fr.gunduz.awstoken.auth.AdfsAuthenticator
import fr.gunduz.awstoken.auth.createAdfsHttpClient
import fr.gunduz.awstoken.model.AwsProfile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Builds an AWS Management Console sign-in URL using the public federation
 * endpoint (`https://signin.aws.amazon.com/federation`):
 *
 *   1. Wraps `(accessKey, secretKey, sessionToken)` from a successful STS
 *      `AssumeRoleWithSAML` into a JSON `Session` payload.
 *   2. GETs `?Action=getSigninToken&Session=<encoded JSON>` to exchange those
 *      credentials for a single-use `SigninToken` (AWS-issued, ~15 min valid).
 *   3. Returns the final `?Action=login` URL with that token + a region-aware
 *      console destination. The caller opens it in the user's default browser
 *      and AWS drops the user straight into the chosen role + region.
 *
 * **Requires temporary credentials.** The federation endpoint refuses static
 * IAM-user keys; only STS-vended `(access, secret, session)` triplets are
 * accepted. Our app never produces static keys, so this is always satisfied.
 *
 * Doc: https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_providers_enable-console-custom-url.html
 */
internal class AwsConsoleSignIn(
    private val httpClientFactory: () -> HttpClient = ::createAdfsHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Serializable
    private data class SessionPayload(
        val sessionId: String,
        val sessionKey: String,
        val sessionToken: String,
    )

    @Serializable
    private data class SigninTokenResponse(
        @SerialName("SigninToken") val signinToken: String,
    )

    /**
     * @param profile drives the destination region (`<region>.console.aws.amazon.com`).
     * @param creds the STS triplet, must include a non-empty session token.
     * @param issuer shown in CloudTrail audit events for the resulting console
     *   session — keeps it traceable to this app.
     * @return a URL ready to hand to the OS browser opener, or a failure if
     *   the federation call rejected the credentials / network failed.
     */
    suspend fun buildConsoleSignInUrl(
        profile: AwsProfile,
        creds: AdfsAuthenticator.StsCredentials,
        issuer: String = "AwsTokenKmp",
    ): Result<String> = runCatching {
        require(creds.sessionToken.isNotBlank()) {
            "Console federation requires temporary credentials with a session token"
        }

        val client = httpClientFactory()
        try {
            val sessionJson = json.encodeToString(
                SessionPayload(
                    sessionId = creds.accessKeyId,
                    sessionKey = creds.secretAccessKey,
                    sessionToken = creds.sessionToken,
                ),
            )

            // SessionDuration must not exceed the credentials' actual remaining
            // lifetime (federation max is 43200 = 12 h, but our STS sessions are
            // typically ≤ 1 h). Floor at 15 min so very-fresh-but-just-rotated
            // creds still produce a usable session.
            val remainingSeconds =
                (Instant.parse(creds.expirationIso).epochSecond - Instant.now().epochSecond)
                    .coerceIn(900L, 43200L)

            val getTokenUrl =
                "https://signin.aws.amazon.com/federation" +
                    "?Action=getSigninToken" +
                    "&SessionDuration=$remainingSeconds" +
                    "&Session=" + URLEncoder.encode(sessionJson, StandardCharsets.UTF_8)

            Logger.i(tag = "AwsConsoleSignIn") {
                "fetching SigninToken for ${profile.name} (sessionDuration=${remainingSeconds}s)"
            }
            val tokenResponseBody = client.get(getTokenUrl).bodyAsText()
            val signinToken = json.decodeFromString<SigninTokenResponse>(tokenResponseBody).signinToken

            // Region-specific console URL: drops the user in the right region
            // dropdown rather than the us-east-1 default.
            val destination = "https://${profile.region}.console.aws.amazon.com/"

            "https://signin.aws.amazon.com/federation" +
                "?Action=login" +
                "&Issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8) +
                "&Destination=" + URLEncoder.encode(destination, StandardCharsets.UTF_8) +
                "&SigninToken=" + URLEncoder.encode(signinToken, StandardCharsets.UTF_8)
        } finally {
            client.close()
        }
    }
}
