package fr.gunduz.awstoken.aws

/**
 * Re-run account-alias discovery against every account that doesn't yet
 * have a saved alias, using whatever STS session credentials the app has
 * already stashed to `~/.aws/credentials`. Returns a summary for the UI to
 * display: `attempted` is the number of accounts we tried, `succeeded` is
 * the number that actually gained an alias. Skipped accounts (no valid
 * cached credentials) don't count against `attempted`.
 *
 * Platform-specific because the credential-file location and reader are
 * desktop-only today.
 */
expect suspend fun refreshAllAccountAliases(explicitPassword: String? = null): AliasRefreshResult

data class AliasRefreshResult(
    /** How many `(accountId, alias)` pairs AWS returned. */
    val aliasesFetched: Int,
    /** How many of those differed from the value we already had (i.e. were written back). */
    val aliasesChanged: Int,
    /** Non-null when the refresh was skipped entirely — surfaces a user-facing reason. */
    val skippedReason: String?,
    /**
     * Set when the skip reason is specifically "no cached password in this
     * session" — the UI reacts by prompting the user for their password and
     * retrying with `explicitPassword`.
     */
    val needsPassword: Boolean = false,
    /**
     * True when the alias refresh also re-authenticated the currently-default
     * profile (reusing the same session password) and wrote fresh STS
     * credentials to `~/.aws/credentials` / `[default]`.
     */
    val defaultProfileRefreshed: Boolean = false,
)
