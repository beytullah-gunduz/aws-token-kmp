package fr.gunduz.awstoken.aws

import co.touchlab.kermit.Logger
import fr.gunduz.awstoken.auth.AdfsAuthenticator
import fr.gunduz.awstoken.auth.rememberPasswordForSession
import fr.gunduz.awstoken.auth.withAuthInProgress
import fr.gunduz.awstoken.desktop.auth.PasswordCache
import fr.gunduz.awstoken.desktop.auth.RefreshScheduler
import fr.gunduz.awstoken.repository.PreferenceRepository

/**
 * Refresh aliases by reusing the ADFS→AWS SAML round trip.
 *
 *   1. Take the `(adfsHost, username)` last used for discovery and look up
 *      the cached password for that pair.
 *   2. Run a fresh `AdfsAuthenticator.discoverRoles(...)` with
 *      `fetchAliasesFromAws = true`. The `Discovery` it returns carries
 *      a `Map<accountId, alias>` that AWS itself produced on the role
 *      selector page — this covers every account the user can assume in
 *      one HTTP round trip, no IAM permission required, no per-account
 *      authentication.
 *   3. Diff against the aliases we already have and persist anything that
 *      differs.
 *
 * If there's no cached password (i.e. the user hasn't authenticated any
 * profile in this session) the refresh is skipped with a user-facing
 * reason the UI can display — the previous per-account silent-auth path is
 * gone because it required individual ADFS logins per account, which is
 * strictly more work than the SAML-selector trick.
 */
actual suspend fun refreshAllAccountAliases(explicitPassword: String?): AliasRefreshResult {
    val prefs = PreferenceRepository.instance
    val (adfsHost, username) = prefs.getLastDiscoveryIdentity()
    if (adfsHost.isBlank() || username.isBlank()) {
        return AliasRefreshResult(0, 0, "Run a discovery first — no saved ADFS host / username.")
    }

    // Caller-provided password takes priority and is cached immediately so
    // the background refresh loop and subsequent alias refreshes can reuse
    // it for the rest of the session. `rememberPasswordForSession` also
    // mirrors into the OS keychain if the user enabled persistence.
    if (explicitPassword != null) {
        rememberPasswordForSession(explicitPassword)
    }

    val password = PasswordCache.get(adfsHost, username)
    if (password == null) {
        return AliasRefreshResult(
            aliasesFetched = 0,
            aliasesChanged = 0,
            skippedReason = "Enter your ADFS password to refresh aliases.",
            needsPassword = true,
        )
    }

    Logger.i(tag = "AliasRefresh") { "refreshing aliases via SAML round-trip for $username@$adfsHost" }
    val discovery = AdfsAuthenticator()
        .discoverRoles(
            adfsHost = adfsHost,
            username = username,
            password = password,
            fetchAliasesFromAws = true,
        )
        .getOrElse { err ->
            Logger.w(err, tag = "AliasRefresh") { "discoverRoles failed during alias refresh" }
            return AliasRefreshResult(0, 0, "ADFS login failed: ${err.message}")
        }

    val existing = prefs.getAccountAliases()
    var changed = 0
    discovery.accountAliases.forEach { (accountId, alias) ->
        if (existing[accountId] != alias) {
            prefs.setAccountAlias(accountId, alias)
            changed++
        }
    }
    Logger.i(tag = "AliasRefresh") {
        "alias refresh done: ${discovery.accountAliases.size} fetched, $changed changed"
    }

    // Piggyback on the same session password: refresh the default profile's
    // STS credentials so `aws` (no `--profile`) keeps working after the
    // alias sync. The background RefreshScheduler would eventually do this
    // 5 minutes before expiry, but the user just entered their password and
    // the UI feels much snappier if `aws s3 ls` works immediately.
    val defaultRefreshed = refreshDefaultProfile(prefs, password)

    return AliasRefreshResult(
        aliasesFetched = discovery.accountAliases.size,
        aliasesChanged = changed,
        skippedReason = null,
        defaultProfileRefreshed = defaultRefreshed,
    )
}

private suspend fun refreshDefaultProfile(
    prefs: PreferenceRepository,
    password: String,
): Boolean {
    val defaultId = prefs.getDefaultProfileId() ?: return false
    val defaultProfile = prefs.getProfiles().firstOrNull { it.id == defaultId } ?: return false
    Logger.i(tag = "AliasRefresh") { "also refreshing default profile ${defaultProfile.name}" }
    val result = withAuthInProgress(defaultProfile.id) {
        AdfsAuthenticator().authenticate(defaultProfile, password)
    }
    return result.fold(
        onSuccess = { creds ->
            writeCredsAndMaybeDefault(defaultProfile, creds)
            RefreshScheduler.current?.recordExpiration(defaultProfile.id, creds.expirationIso)
            true
        },
        onFailure = { err ->
            Logger.w(err, tag = "AliasRefresh") { "default profile refresh failed: ${err.message}" }
            false
        },
    )
}
