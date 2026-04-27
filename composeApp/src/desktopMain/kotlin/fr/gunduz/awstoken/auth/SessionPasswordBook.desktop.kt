package fr.gunduz.awstoken.auth

import co.touchlab.kermit.Logger
import fr.gunduz.awstoken.desktop.auth.PasswordCache
import fr.gunduz.awstoken.desktop.auth.SecureCredentialStore
import fr.gunduz.awstoken.repository.PreferenceRepository

actual suspend fun rememberPasswordForSession(password: String) {
    val prefs = PreferenceRepository.instance
    val (host, user) = prefs.getLastDiscoveryIdentity()
    if (host.isBlank() || user.isBlank()) {
        Logger.w(tag = TAG) { "rememberPasswordForSession skipped: no saved discovery identity" }
        return
    }

    PasswordCache.put(host, user, password)
    Logger.i(tag = TAG) { "password cached in memory for $user@$host" }

    val persist = prefs.getPersistPasswordInKeychain()
    Logger.i(tag = TAG) { "persist-in-keychain pref = $persist, keychain supported = ${SecureCredentialStore.isSupported}" }
    if (persist) {
        val ok = SecureCredentialStore.store(host, user, password)
        Logger.i(tag = TAG) { "keychain store returned $ok for $user@$host" }
    }
}

actual suspend fun readCachedSessionPassword(adfsHost: String, username: String): String? {
    val hit = PasswordCache.get(adfsHost, username)
    Logger.i(tag = TAG) {
        "readCachedSessionPassword(host='$adfsHost', user='$username') -> ${if (hit == null) "<null>" else "<${hit.length} chars>"}"
    }
    return hit
}

actual suspend fun dropCachedSessionPassword(adfsHost: String, username: String) {
    PasswordCache.remove(adfsHost, username)
    Logger.i(tag = TAG) { "dropped session password for $username@$adfsHost" }
    // Also delete the persisted keychain entry. Rationale: this function is
    // called only when a cache-backed auth has just been rejected by the
    // server, which means the value that came from (or matches) the keychain
    // is broken. Leaving the keychain entry in place would cause every
    // subsequent launch to silently retry the same dead credential.
    if (SecureCredentialStore.isSupported) {
        val deleted = SecureCredentialStore.delete(adfsHost, username)
        Logger.i(tag = TAG) { "keychain delete for $username@$adfsHost returned $deleted" }
    }
}

private const val TAG = "SessionPasswordBook"
