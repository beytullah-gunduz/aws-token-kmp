package fr.gunduz.awstoken.desktop.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory password cache for the background refresh loop. Password is
 * held ONLY for the lifetime of the process — on quit it's dropped, and
 * the user must re-authenticate on next launch. No disk persistence, no
 * keychain integration (yet — see README TODO).
 *
 * Keyed by `(adfsHost, username)` because a single credential set may back
 * multiple profiles (different roles / regions in the same AD account), so
 * authenticating one profile unlocks refresh for all of them.
 */
object PasswordCache {
    private data class Key(val adfsHost: String, val username: String)

    private val cache = ConcurrentHashMap<Key, String>()

    fun put(adfsHost: String, username: String, password: String) {
        cache[Key(adfsHost, username)] = password
    }

    fun get(adfsHost: String, username: String): String? = cache[Key(adfsHost, username)]

    /**
     * Remove the cached password for a specific identity. Called after an
     * auth attempt that used the cached value is rejected by the server —
     * holding on to a known-bad password would just make every subsequent
     * silent-auth attempt fail in exactly the same way.
     */
    fun remove(adfsHost: String, username: String) {
        cache.remove(Key(adfsHost, username))
    }

    fun clear() = cache.clear()
}
