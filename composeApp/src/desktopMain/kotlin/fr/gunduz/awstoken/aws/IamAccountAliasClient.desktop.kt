package fr.gunduz.awstoken.aws

import co.touchlab.kermit.Logger
import fr.gunduz.awstoken.auth.AdfsAuthenticator
import fr.gunduz.awstoken.auth.createAdfsHttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * IAM is a global service: the endpoint is `iam.amazonaws.com` but SigV4
 * still requires `us-east-1` as the region in the credential scope. The
 * `ListAccountAliases` action returns at most one alias per account (AWS
 * permits only one), so we just grab the first `<AccountAlias>` element
 * from the XML with a regex rather than spin up a parser.
 */
actual class IamAccountAliasClient actual constructor() {
    actual suspend fun fetchFirstAccountAlias(credentials: AdfsAuthenticator.StsCredentials): String? {
        val body = "Action=ListAccountAliases&Version=2010-05-08"
        val signed = SigV4Signer.signFormPost(
            host = "iam.amazonaws.com",
            path = "/",
            region = "us-east-1",
            service = "iam",
            body = body,
            credentials = credentials,
        )

        val client = createAdfsHttpClient()
        return try {
            val response = client.post(signed.url) {
                contentType(ContentType.Application.FormUrlEncoded)
                signed.headers.forEach { (k, v) -> headers.append(k, v) }
                setBody(signed.body)
            }
            if (!response.status.isSuccess()) {
                Logger.w(tag = "IamAccountAliasClient") {
                    "ListAccountAliases failed ${response.status}: ${response.bodyAsText().take(300)}"
                }
                return null
            }
            val xml = response.bodyAsText()
            Regex("<AccountAlias>([^<]+)</AccountAlias>").find(xml)?.groupValues?.get(1)
        } catch (t: Throwable) {
            Logger.w(t, tag = "IamAccountAliasClient") { "ListAccountAliases threw" }
            null
        } finally {
            client.close()
        }
    }
}
