package fr.gunduz.awstoken.auth

/**
 * Remember the password the user just successfully used for the current
 * discovery identity. On desktop this writes to the in-memory `PasswordCache`
 * (so the rest of the session runs silently) AND — when the user has
 * opted into Keychain persistence — also mirrors the password into the OS
 * secure credential store so it survives across launches.
 *
 * Dialogs that collect a password (discovery, interactive auth, alias
 * refresh) all funnel through this one call, so a toggle in any of those
 * dialogs only needs to flip the underlying pref — the persistence happens
 * automatically on submit.
 */
expect suspend fun rememberPasswordForSession(password: String)

/**
 * Return the in-memory cached password for the given `(adfsHost, username)`
 * pair, or `null` if nothing is cached. Dialogs use this to decide whether
 * to show a grayed-out password field ("Using saved password") that the
 * user can submit without retyping.
 *
 * On desktop this reads `PasswordCache`, which is primed from the OS
 * Keychain at startup when the user has opted into persistence. Android
 * always returns `null` until the Keystore bridge is wired.
 */
expect suspend fun readCachedSessionPassword(adfsHost: String, username: String): String?

/**
 * Evict a known-bad cached password so a subsequent dialog submit re-prompts
 * the user for a fresh one. Called after the cached password is rejected by
 * ADFS — keeping the value around would just make every retry fail in the
 * same way.
 */
expect suspend fun dropCachedSessionPassword(adfsHost: String, username: String)
