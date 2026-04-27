package fr.gunduz.awstoken.auth

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import fr.gunduz.awstoken.model.AwsProfile
import fr.gunduz.awstoken.repository.PreferenceRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Minimal ADFS IdpInitiatedSignOn flow. Four steps:
 *
 *   1. GET https://{adfsHost}/adfs/ls/IdpInitiatedSignOn.aspx?loginToRp=urn:amazon:webservices
 *      The response is an HTML form with hidden inputs. We preserve cookies
 *      across this and the next step because ADFS uses MSISAuth / MSISLoopDetection.
 *   2. POST the form back to its declared `action` URL with username+password
 *      merged into the hidden-input map. Field names are auto-discovered from
 *      the form (ADFS deployments differ — some use `ctl00$...` prefixes, some
 *      don't).
 *   3. Scrape `<input name="SAMLResponse" value="...">` out of the resulting
 *      HTML. If the input is absent the response is assumed to be an MFA
 *      challenge (not yet supported — see TODO in README).
 *   4. POST to STS `AssumeRoleWithSAML` with the SAMLResponse as SAMLAssertion
 *      plus PrincipalArn and RoleArn from the profile. Parse the four-field
 *      `<Credentials>` block out of the XML with a cheap regex — adding a
 *      full XML parser for a fixed-shape 6-line response would be overkill.
 *
 * Gotchas observed across real-world ADFS deployments:
 *   - The form action is often relative; resolve against the GET URL.
 *   - Some ADFS setups return 302 → we follow redirects automatically via Ktor.
 *   - MSISLoopDetection cookie MUST round-trip or ADFS will reject with 500.
 *     `HttpCookies` with the default storage handles this transparently.
 *   - STS region doesn't matter for `AssumeRoleWithSAML` (it's a global action),
 *     but we still use a regional endpoint so any corporate proxy rules that
 *     whitelist regions keep working.
 */
class AdfsAuthenticator(
    private val httpClientFactory: () -> HttpClient = ::createAdfsHttpClient,
) {
    data class StsCredentials(
        val accessKeyId: String,
        val secretAccessKey: String,
        val sessionToken: String,
        val expirationIso: String,
    )

    /** One `(principal, role)` pair as advertised in the SAML assertion. */
    data class RoleChoice(
        val principalArn: String,
        val roleArn: String,
    ) {
        /** `arn:aws:iam::123456789012:role/MyRole` → `123456789012`. */
        val accountId: String get() = roleArn.substringAfter("::").substringBefore(":")

        /** `arn:aws:iam::123456789012:role/MyRole` → `MyRole`. */
        val roleName: String get() = roleArn.substringAfterLast("/")
    }

    /** Result of the "discover profiles" step: the roles the IdP offers for this user. */
    data class Discovery(
        val adfsHost: String,
        val username: String,
        val roles: List<RoleChoice>,
        /** Account id → human-readable alias as shown on the AWS role selector page. */
        val accountAliases: Map<String, String> = emptyMap(),
    )

    sealed class AuthError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
        class BadCredentials(msg: String) : AuthError(msg)
        class MfaRequired(msg: String) : AuthError(msg)
        class UnexpectedResponse(msg: String) : AuthError(msg)
        class Network(msg: String, cause: Throwable) : AuthError(msg, cause)
    }

    suspend fun authenticate(profile: AwsProfile, password: String): Result<StsCredentials> {
        resetDebugDumpDir()
        val client = httpClientFactory()
        return try {
            val samlResponse = fetchSamlAssertion(
                client = client,
                adfsHost = profile.adfsHost,
                username = profile.username,
                password = password,
            )
            // Pass the user's configured duration (1–8h, clamped) through to
            // STS as `DurationSeconds`. STS will reject it if the role has a
            // lower `MaxSessionDuration` set server-side — the error is
            // surfaced verbatim so the user can lower the setting and retry.
            val durationSeconds = PreferenceRepository.instance.getSessionDurationSeconds()
            val creds = assumeRoleWithSaml(
                client = client,
                region = profile.region,
                principalArn = profile.principalArn,
                roleArn = profile.roleArn,
                samlAssertion = samlResponse,
                durationSeconds = durationSeconds,
            )
            Result.success(creds)
        } catch (t: Throwable) {
            Logger.e(t, tag = "AdfsAuthenticator") { "authenticate failed for profile ${profile.name}" }
            Result.failure(mapNetworkError(t, profile.adfsHost))
        } finally {
            client.close()
        }
    }

    /**
     * First-launch flow: perform the ADFS login but instead of handing the
     * assertion to STS, base64-decode it and extract all `(PrincipalArn, RoleArn)`
     * pairs advertised under the `https://aws.amazon.com/SAML/Attributes/Role`
     * attribute. Lets the UI create one profile per advertised role without
     * making the user type every ARN by hand.
     */
    suspend fun discoverRoles(
        adfsHost: String,
        username: String,
        password: String,
        fetchAliasesFromAws: Boolean = true,
    ): Result<Discovery> {
        resetDebugDumpDir()
        val client = httpClientFactory()
        return try {
            val samlResponseB64 = fetchSamlAssertion(client, adfsHost, username, password)
            val roles = extractRolesFromSaml(samlResponseB64)
            if (roles.isEmpty()) {
                throw AuthError.UnexpectedResponse(
                    "ADFS login succeeded but the SAML assertion advertised no AWS roles",
                )
            }
            val aliases: Map<String, String> = if (fetchAliasesFromAws) {
                runCatching { fetchAwsAccountAliases(client, samlResponseB64) }
                    .onFailure { Logger.w(it, tag = "AdfsAuthenticator") { "AWS alias fetch failed" } }
                    .getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            Logger.i(tag = "AdfsAuthenticator") {
                "discovery complete: ${roles.size} roles, ${aliases.size} aliases from AWS"
            }
            Result.success(
                Discovery(
                    adfsHost = adfsHost,
                    username = username,
                    roles = roles,
                    accountAliases = aliases,
                ),
            )
        } catch (t: Throwable) {
            Logger.e(t, tag = "AdfsAuthenticator") { "discoverRoles failed for $username@$adfsHost" }
            Result.failure(mapNetworkError(t, adfsHost))
        } finally {
            client.close()
        }
    }

    /**
     * POST the freshly-minted SAMLResponse to the AWS SAML signin endpoint
     * and scrape the returned role-selector page for `Account: <alias> (<id>)`
     * tags. The endpoint accepts this POST without any AWS credentials — it's
     * the same call the browser makes to hand the SAML over to AWS — and the
     * HTML response lists every `accountId → alias` mapping AWS knows about
     * for the roles in the assertion.
     *
     * This is cheaper and more complete than calling `iam:ListAccountAliases`
     * per account: one HTTP round trip, aliases for all accounts at once, no
     * IAM permission required. Downside: tied to the HTML structure of the
     * AWS signin page, which we pin down with Ksoup selectors + a regex.
     */
    private suspend fun fetchAwsAccountAliases(
        client: HttpClient,
        samlResponseBase64: String,
    ): Map<String, String> {
        val response = client.submitForm(
            url = "https://signin.aws.amazon.com/saml",
            formParameters = Parameters.build { append("SAMLResponse", samlResponseBase64) },
            encodeInQuery = false,
        )
        val html = dumpAndReadText(response, filename = "aws-signin-saml.html")
        return parseAwsAliasPage(html)
    }

    internal fun parseAwsAliasPage(html: String): Map<String, String> {
        val doc = Ksoup.parse(html)
        val result = mutableMapOf<String, String>()
        // The page wraps each account in `<div class="saml-account">` and the
        // header element is `.saml-account-name`. The text inside looks like:
        //   "Account: my-prod-alias (123456789012)"
        // or, if no alias is registered:
        //   "Account: 123456789012"
        // We only save the first form.
        val accountPattern = Regex("""Account:\s*(.+?)\s*\((\d{12})\)""")
        doc.select(".saml-account-name").forEach { elem ->
            val match = accountPattern.find(elem.text()) ?: return@forEach
            val alias = match.groupValues[1].trim()
            val accountId = match.groupValues[2]
            if (alias.isNotBlank() && alias != accountId) {
                result[accountId] = alias
            }
        }
        return result
    }

    /**
     * Turn low-level socket exceptions into an actionable message. The common
     * case on corporate networks is DNS resolution of the ADFS host failing
     * because the JVM isn't using the same proxy/VPN path as the browser.
     */
    private fun mapNetworkError(t: Throwable, host: String): Throwable {
        if (t is AuthError) return t
        val msg = (t.message ?: "") + " " + (t.cause?.message ?: "")
        return when {
            "UnresolvedAddress" in msg || "UnknownHost" in msg || "No such host" in msg ->
                AuthError.Network(
                    "Could not resolve $host. " +
                        "If this host is only reachable through a VPN or corporate proxy, make sure the VPN is up and " +
                        "the app can see your system proxy settings (JVM flag `java.net.useSystemProxies=true` is set on startup).",
                    t,
                )
            "ConnectException" in msg || "Connection refused" in msg ->
                AuthError.Network("Connection refused by $host. Check that the ADFS endpoint is correct and reachable.", t)
            "SSLHandshake" in msg || "PKIX" in msg ->
                AuthError.Network("TLS handshake failed against $host. Corporate MITM proxy certificates must be in the system trust store.", t)
            else -> t
        }
    }

    // ---- step 1 + 2 + 3: ADFS form scrape ----

    private suspend fun fetchSamlAssertion(
        client: HttpClient,
        adfsHost: String,
        username: String,
        password: String,
    ): String {
        val initialUrl = "https://$adfsHost/adfs/ls/IdpInitiatedSignOn.aspx?loginToRp=urn:amazon:webservices"

        val initialResponse = client.get(initialUrl)
        var currentHtml = dumpAndReadText(initialResponse, filename = "adfs-hop-0.html")
        var currentUrl = initialUrl

        // Each hop is either (a) an intermediate page — most commonly the
        // ADFS Relying Party selector, where the user picks "AWS" from a
        // <select> — which we auto-fill and submit, or (b) the real login
        // page with a <input type=password>, into which we inject the
        // user's credentials.
        //
        // After each hop we check for a SAMLResponse input in the response;
        // as soon as we see one, we return its value. Bounded to 5 hops so
        // a misconfigured IdP can't loop us forever.
        var passwordInjected = false
        for (hop in 0 until 5) {
            extractSamlResponse(currentHtml)?.let { return it }

            val doc = Ksoup.parse(currentHtml)
            val form = doc.selectFirst("form#loginForm")
                ?: doc.selectFirst("form")
                ?: break

            val action = resolveUrl(currentUrl, form.attr("action").ifBlank { currentUrl })
            val fields = extractFormFields(form)
            val hasPasswordInput = form.select("input[type=password]").isNotEmpty()

            when {
                hasPasswordInput -> {
                    val userField = form.select("input[type=text], input[type=email]")
                        .firstOrNull { it.attr("name").isNotBlank() }?.attr("name") ?: "UserName"
                    val passField = form.select("input[type=password]")
                        .firstOrNull { it.attr("name").isNotBlank() }?.attr("name") ?: "Password"
                    fields[userField] = username
                    fields[passField] = password
                    passwordInjected = true
                    Logger.i(tag = "AdfsAuthenticator") {
                        "hop $hop: login form detected, injecting creds (user=$userField pass=$passField)"
                    }
                }
                else -> {
                    // Intermediate page. Patch the fields to target AWS: force
                    // any "AWS" option in a <select> and any "sign in to
                    // following sites" radio button so the server routes us to
                    // the AWS relying party instead of the default "this site".
                    patchForAws(form, fields)
                    Logger.i(tag = "AdfsAuthenticator") {
                        "hop $hop: intermediate form, auto-submitting with fields=${fields.keys}"
                    }
                }
            }

            val response = client.submitForm(
                url = action,
                formParameters = Parameters.build { fields.forEach { (k, v) -> append(k, v) } },
                encodeInQuery = false,
            )
            currentHtml = dumpAndReadText(response, filename = "adfs-hop-${hop + 1}.html")
            currentUrl = action

            if (response.status == HttpStatusCode.Unauthorized && passwordInjected) {
                throw AuthError.BadCredentials("ADFS rejected credentials (HTTP 401).")
            }

            // If we just posted credentials and got another login form back
            // (same `<input type=password>` present) ADFS almost certainly
            // rejected them — retrying will just loop. Surface the inline
            // error text (e.g. "Incorrect user ID or password.") immediately.
            if (passwordInjected) {
                val bounced = Ksoup.parse(currentHtml).selectFirst("input[type=password]") != null
                if (bounced) {
                    val hint = extractErrorHint(currentHtml) ?: "ADFS rejected the credentials."
                    throw AuthError.BadCredentials(hint)
                }
            }
        }

        extractSamlResponse(currentHtml)?.let { return it }

        if (looksLikeMfaPrompt(currentHtml)) {
            throw AuthError.MfaRequired(
                "ADFS returned an MFA challenge — not yet supported. See ${debugDumpPath("adfs-hop-1.html")}.",
            )
        }
        val hint = extractErrorHint(currentHtml)
        throw AuthError.UnexpectedResponse(
            "No SAMLResponse after ${if (passwordInjected) "password submit" else "5 form hops"}. " +
                "${hint ?: "The login flow did not reach a SAML assertion."} " +
                "HTML dumps in ${debugDumpPath("adfs-hop-0.html")}.",
        )
    }

    /**
     * Pull every value out of a form that a browser would submit if the user
     * did nothing but click "Sign in": hidden inputs, checked radios, selected
     * `<select>` options (or the first option if none is marked), prefilled
     * text inputs, and the first submit button's name/value (some ADFS
     * endpoints key off which submit button fired). Returned map is mutable
     * so callers can patch specific entries before submitting.
     */
    internal fun extractFormFields(form: com.fleeksoft.ksoup.nodes.Element): MutableMap<String, String> {
        val fields = linkedMapOf<String, String>()

        form.select("input[type=hidden]").forEach { input ->
            val name = input.attr("name")
            if (name.isNotBlank()) fields[name] = input.attr("value")
        }

        // Respect any radio already marked `checked` in the HTML; if the user
        // chose "other site" in the browser, the HTML may have that one checked.
        form.select("input[type=radio]").groupBy { it.attr("name") }.forEach { (name, radios) ->
            if (name.isBlank()) return@forEach
            val checked = radios.firstOrNull { it.hasAttr("checked") }
            if (checked != null) fields[name] = checked.attr("value")
        }

        form.select("select[name]").forEach { select ->
            val name = select.attr("name")
            val selected = select.select("option[selected]").firstOrNull()
                ?: select.select("option").firstOrNull()
            if (selected != null) {
                fields[name] = selected.attr("value").ifBlank { selected.text() }
            }
        }

        form.select("input[type=text], input[type=email]").forEach { input ->
            val name = input.attr("name")
            val value = input.attr("value")
            if (name.isNotBlank() && value.isNotBlank() && name !in fields) fields[name] = value
        }

        form.select("input[type=submit][name], button[type=submit][name]").firstOrNull()?.let { submit ->
            val name = submit.attr("name")
            if (name.isNotBlank()) fields[name] = submit.attr("value")
        }

        return fields
    }

    /**
     * Patch form fields to aim at the AWS relying party on an ADFS
     * `IdpInitiatedSignOn.aspx` selector page. Without this, submitting the
     * selector form with its default values just reloads the same page — the
     * default radio is "Sign in to this site" (ADFS itself) and the dropdown
     * isn't consulted.
     *
     * Heuristics:
     *   1. Any `<select>` whose option matches AWS by value/text wins that
     *      option (`value` containing `amazon`/`webservices`, or `text`
     *      equal to "AWS" case-insensitive).
     *   2. Any radio group containing an option whose value/id mentions
     *      "Other" or whose associated label text says "following sites"
     *      gets forced to that option.
     */
    internal fun patchForAws(
        form: com.fleeksoft.ksoup.nodes.Element,
        fields: MutableMap<String, String>,
    ) {
        form.select("select[name]").forEach { select ->
            val name = select.attr("name")
            if (name.isBlank()) return@forEach
            val awsOption = select.select("option").firstOrNull { opt ->
                val v = opt.attr("value").lowercase()
                val t = opt.text().trim().lowercase()
                "amazon" in v || "webservices" in v || t == "aws" || "aws" in t
            }
            if (awsOption != null) fields[name] = awsOption.attr("value").ifBlank { awsOption.text() }
        }

        form.select("input[type=radio]").groupBy { it.attr("name") }.forEach { (name, radios) ->
            if (name.isBlank() || radios.size < 2) return@forEach
            val other = radios.firstOrNull { radio ->
                val v = radio.attr("value").lowercase()
                val id = radio.attr("id").lowercase()
                "other" in v || "other" in id || "rp" in v || "rp" in id
            } ?: radios.last()
            fields[name] = other.attr("value")
        }
    }

    /**
     * Some ADFS deployments return an intermediate HTML page that auto-submits
     * a second form (e.g. to an MFA adapter or to AWS itself). If we see such
     * a page, POST its hidden inputs to its action and return the body.
     * Returns `null` if the current HTML does not look like such a form.
     */
    private suspend fun tryFollowIntermediateForm(
        client: HttpClient,
        html: String,
        baseUrl: String,
    ): String? {
        val doc = Ksoup.parse(html)
        val form = doc.selectFirst("form") ?: return null
        // Only follow forms whose action looks like a real URL — not if
        // it's the same origin login form echoed back on error.
        val action = form.attr("action").ifBlank { return null }
        val resolved = resolveUrl(baseUrl, action)
        val hidden = form.select("input[type=hidden]")
            .associate { it.attr("name") to it.attr("value") }
            .filterKeys { it.isNotBlank() }
        if (hidden.isEmpty()) return null
        Logger.i(tag = "AdfsAuthenticator") {
            "following intermediate form to $resolved with fields=${hidden.keys}"
        }
        val response = client.submitForm(
            url = resolved,
            formParameters = Parameters.build { hidden.forEach { (k, v) -> append(k, v) } },
            encodeInQuery = false,
        )
        return response.bodyAsText()
    }

    private fun extractErrorHint(html: String): String? {
        val doc = Ksoup.parse(html)
        val errorText = doc.select("#errorText, .error, span.error, label[for=userNameInput][class*=error]")
            .firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }
        // Prefer the inline error text — the <title> on ADFS is always "Sign In" and adds no info.
        return errorText ?: doc.title().trim().takeIf { it.isNotBlank() && !it.equals("Sign In", ignoreCase = true) }
    }

    internal data class LoginForm(
        val action: String,
        val hiddenFields: Map<String, String>,
        val usernameField: String,
        val passwordField: String,
    )

    internal fun parseLoginForm(html: String, baseUrl: String): LoginForm {
        val doc = Ksoup.parse(html)
        val form = doc.selectFirst("form#loginForm")
            ?: doc.selectFirst("form")
            ?: throw AuthError.UnexpectedResponse("ADFS login page has no <form>")

        val actionRaw = form.attr("action").ifBlank { baseUrl }
        val action = resolveUrl(baseUrl, actionRaw)

        val hidden = form.select("input[type=hidden]").associate { it.attr("name") to it.attr("value") }
            .filterKeys { it.isNotBlank() }

        val usernameField = form.select("input[type=text], input[type=email]")
            .firstOrNull { it.attr("name").isNotBlank() }?.attr("name")
            ?: "UserName"
        val passwordField = form.select("input[type=password]")
            .firstOrNull { it.attr("name").isNotBlank() }?.attr("name")
            ?: "Password"

        return LoginForm(action, hidden, usernameField, passwordField)
    }

    internal fun extractSamlResponse(html: String): String? {
        val doc = Ksoup.parse(html)
        return doc.selectFirst("input[name=SAMLResponse]")?.attr("value")?.takeIf { it.isNotBlank() }
    }

    private fun looksLikeMfaPrompt(html: String): Boolean {
        val lower = html.lowercase()
        return "duo" in lower ||
            "mfa" in lower ||
            "authentication code" in lower ||
            "verify your identity" in lower
    }

    internal fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        // Strip query/fragment from base so we don't leak them into the resolved URL.
        val baseNoQuery = base.substringBefore('?').substringBefore('#')
        val schemeEnd = baseNoQuery.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val pathStart = baseNoQuery.indexOf('/', startIndex = schemeEnd)
        val origin = if (pathStart < 0) baseNoQuery else baseNoQuery.substring(0, pathStart)
        val basePath = if (pathStart < 0) "/" else baseNoQuery.substring(pathStart)
        return if (relative.startsWith("/")) {
            origin + relative
        } else {
            val dir = basePath.substringBeforeLast('/', missingDelimiterValue = "") + "/"
            origin + dir + relative
        }
    }

    // ---- step 4: STS AssumeRoleWithSAML ----

    private suspend fun assumeRoleWithSaml(
        client: HttpClient,
        region: String,
        principalArn: String,
        roleArn: String,
        samlAssertion: String,
        durationSeconds: Int,
    ): StsCredentials {
        val endpoint = "https://sts.$region.amazonaws.com/"
        val response = client.submitForm(
            url = endpoint,
            formParameters = Parameters.build {
                append("Action", "AssumeRoleWithSAML")
                append("Version", "2011-06-15")
                append("PrincipalArn", principalArn)
                append("RoleArn", roleArn)
                append("SAMLAssertion", samlAssertion)
                append("DurationSeconds", durationSeconds.toString())
            },
            encodeInQuery = false,
        )
        val xml: String = response.body()
        if (!response.status.isSuccess()) {
            throw AuthError.UnexpectedResponse(
                "STS AssumeRoleWithSAML failed: ${response.status} / ${xml.take(400)}",
            )
        }
        return parseStsResponse(xml)
    }

    /**
     * Base64-decode the SAMLResponse, then scan every `<…AttributeValue>…</…AttributeValue>`
     * for strings of the form `arn:aws:iam::…:saml-provider/…,arn:aws:iam::…:role/…`
     * (either order — IdPs aren't consistent). Regex is fine here: we're not
     * validating SAML, just lifting a handful of ARN pairs out of a known
     * blob, and the XML parser would just mean another dependency.
     *
     * The `https://aws.amazon.com/SAML/Attributes/Role` attribute is the
     * canonical location per AWS's SAML2 federation docs. Other AttributeValues
     * in the assertion (session name, session duration) don't match the ARN
     * regex so they're filtered out naturally.
     */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun extractRolesFromSaml(samlResponseBase64: String): List<RoleChoice> {
        val decoded = runCatching { Base64.decode(samlResponseBase64).decodeToString() }
            .getOrElse { throw AuthError.UnexpectedResponse("SAMLResponse is not valid base64") }

        val valueRegex = Regex("<(?:[A-Za-z0-9]+:)?AttributeValue[^>]*>([^<]+)</(?:[A-Za-z0-9]+:)?AttributeValue>")
        return valueRegex.findAll(decoded)
            .map { it.groupValues[1].trim() }
            .filter { "arn:aws:iam::" in it && "," in it }
            .mapNotNull { raw ->
                val parts = raw.split(",").map { it.trim() }
                if (parts.size != 2) return@mapNotNull null
                val (principal, role) = when {
                    "saml-provider/" in parts[0] -> parts[0] to parts[1]
                    "saml-provider/" in parts[1] -> parts[1] to parts[0]
                    else -> return@mapNotNull null
                }
                if (":role/" !in role) return@mapNotNull null
                RoleChoice(principalArn = principal, roleArn = role)
            }
            .distinct()
            .toList()
    }

    /**
     * Read the response body as raw bytes, dump them to the debug dir along
     * with a metadata sidecar (status, final URL, content-type, length,
     * selected headers), and return the decoded text. Using `readRawBytes`
     * instead of `bodyAsText` sidesteps charset/encoding issues that can
     * silently return an empty string.
     *
     * **Sensitive values are redacted in the dumped files only.** The
     * returned `text` is the unmodified raw response so the in-memory form
     * walker can still parse the real `SAMLResponse` / hidden field values
     * and make its next request.
     */
    private suspend fun dumpAndReadText(response: HttpResponse, filename: String): String {
        val bytes = runCatching { response.readRawBytes() }.getOrElse { ByteArray(0) }
        val meta = buildString {
            append("status: ").append(response.status).append('\n')
            append("final-url: ").append(response.call.request.url).append('\n')
            append("content-type: ").append(response.headers["Content-Type"] ?: "").append('\n')
            append("content-length: ").append(bytes.size).append('\n')
            append("content-encoding: ").append(response.headers["Content-Encoding"] ?: "").append('\n')
            append("location: ").append(response.headers["Location"] ?: "").append('\n')
            append("set-cookie: ").append(redactSetCookieHeader(response.headers["Set-Cookie"] ?: "")).append('\n')
        }
        debugDump("$filename.meta.txt", meta)
        val text = runCatching { bytes.decodeToString() }.getOrDefault("")
        debugDump(filename, redactSensitive(text))
        Logger.i(tag = "AdfsAuthenticator") {
            "→ ${response.status} ${response.call.request.url} bytes=${bytes.size} ct=${response.headers["Content-Type"]}"
        }
        return text
    }

    /**
     * Replace the `value` attribute of any `<input>` whose `name` is
     * `SAMLResponse`, `SAMLRequest`, or `RelayState` with a redacted
     * placeholder. All three are effectively short-lived bearer tokens:
     *
     *   - `SAMLResponse` — the signed assertion the IdP hands to AWS. If
     *     captured inside its validity window (~5 min) an attacker can POST
     *     it to STS and obtain AWS session credentials in our place.
     *   - `SAMLRequest` — the signed authentication request going the other
     *     way. Less dangerous but still identifiable.
     *   - `RelayState` — opaque session state that may encode account or
     *     routing information the IdP doesn't want leaked to third parties.
     *
     * The redaction writes the ORIGINAL character length into the
     * replacement so a log reader can confirm "yes, a value was there"
     * without actually disclosing it.
     *
     * We also handle the two attribute orderings — `name=... value=...`
     * and `value=... name=...` — and both single- and double-quoted
     * attribute delimiters. Case-insensitive because some deployments emit
     * `Name=` / `VALUE=`.
     */
    internal fun redactSensitive(html: String): String {
        var result = html
        val sensitive = listOf("SAMLResponse", "SAMLRequest", "RelayState")
        for (name in sensitive) {
            // Case 1: name="X" ... value="..."
            val afterName = Regex(
                """(<input\b[^>]*?\bname\s*=\s*["']$name["'][^>]*?\bvalue\s*=\s*["'])([^"']*)(["'])""",
                RegexOption.IGNORE_CASE,
            )
            result = afterName.replace(result) { m ->
                "${m.groupValues[1]}***REDACTED (${m.groupValues[2].length} chars)***${m.groupValues[3]}"
            }
            // Case 2: value="..." ... name="X"
            val beforeName = Regex(
                """(<input\b[^>]*?\bvalue\s*=\s*["'])([^"']*)(["'][^>]*?\bname\s*=\s*["']$name["'])""",
                RegexOption.IGNORE_CASE,
            )
            result = beforeName.replace(result) { m ->
                "${m.groupValues[1]}***REDACTED (${m.groupValues[2].length} chars)***${m.groupValues[3]}"
            }
        }
        return result
    }

    /**
     * Replace the entire `Set-Cookie` header value with a placeholder. We
     * deliberately don't try to extract cookie names because real-world
     * headers contain commas inside `Expires=Wed, DD MMM ...` values which
     * break naive splitting — and the debug value of knowing cookie names
     * is marginal compared to the risk of leaking session IDs like
     * `MSISAuth` / `MSISLoopDetection` that an attacker could replay.
     */
    internal fun redactSetCookieHeader(raw: String): String {
        if (raw.isBlank()) return ""
        return "***REDACTED*** (${raw.length} chars)"
    }

    internal fun parseStsResponse(xml: String): StsCredentials {
        fun grab(tag: String): String = Regex("<$tag>([^<]+)</$tag>").find(xml)?.groupValues?.get(1)
            ?: throw AuthError.UnexpectedResponse("Missing <$tag> in STS response")
        return StsCredentials(
            accessKeyId = grab("AccessKeyId"),
            secretAccessKey = grab("SecretAccessKey"),
            sessionToken = grab("SessionToken"),
            expirationIso = grab("Expiration"),
        )
    }
}

/**
 * Write an HTML snapshot to a debug dir so the user can inspect what ADFS
 * returned. Desktop writes to `~/.aws-token-kmp/debug/`, android to
 * `filesDir/debug/`.
 */
internal expect fun debugDump(filename: String, content: String)

internal expect fun debugDumpPath(filename: String): String

/** Wipe every file in the debug directory. Called at the start of each auth attempt. */
internal expect fun resetDebugDumpDir()
