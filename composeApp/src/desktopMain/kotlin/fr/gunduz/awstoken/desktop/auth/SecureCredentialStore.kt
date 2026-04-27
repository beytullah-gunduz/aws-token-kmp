package fr.gunduz.awstoken.desktop.auth

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper over the OS keyring / keychain. macOS is the only platform
 * wired today; everything else returns `null` / no-ops so the rest of the
 * code can call these methods unconditionally.
 *
 * macOS: shells out to `/usr/bin/security`, the built-in Keychain CLI that
 * every macOS install ships. No new dependencies, no JNI. The entry is
 * registered under a single service name so the user sees one row in
 * Keychain Access ("aws-token-kmp"), keyed per `(adfsHost, username)`.
 *
 * TODO (see README):
 *   - Windows: wrap `wincred.h`/CredRead+CredWrite via a tiny JNA call, or
 *     ship a native helper. `cmdkey` stores but can't print passwords back.
 *   - Linux: libsecret / Secret Service via D-Bus.
 */
object SecureCredentialStore {
    private const val SERVICE = "aws-token-kmp"

    val isSupported: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    suspend fun store(adfsHost: String, username: String, password: String): Boolean {
        if (!isSupported) {
            Logger.w(tag = TAG) { "store() called on non-macOS — no-op" }
            return false
        }
        val account = accountKey(adfsHost, username)
        Logger.i(tag = TAG) { "store: running `security add-generic-password -U -s $SERVICE -a $account -w <redacted>`" }
        return withContext(Dispatchers.IO) {
            val proc = ProcessBuilder(
                "/usr/bin/security",
                "add-generic-password",
                "-U", // update if it already exists
                "-s", SERVICE,
                "-a", account,
                "-w", password,
            )
                .redirectErrorStream(true)
                .start()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                Logger.w(tag = TAG) { "keychain store timed out" }
                return@withContext false
            }
            val exit = proc.exitValue()
            val output = proc.inputStream.bufferedReader().readText().trim()
            if (exit != 0) {
                Logger.w(tag = TAG) { "keychain store failed (exit $exit): $output" }
            } else {
                Logger.i(tag = TAG) { "keychain store OK for $account ${if (output.isNotBlank()) "(output: $output)" else ""}" }
            }
            exit == 0
        }
    }

    suspend fun retrieve(adfsHost: String, username: String): String? {
        if (!isSupported) {
            Logger.i(tag = TAG) { "retrieve() on non-macOS — returning null" }
            return null
        }
        val account = accountKey(adfsHost, username)
        Logger.i(tag = TAG) { "retrieve: running `security find-generic-password -s $SERVICE -a $account -w`" }
        return withContext(Dispatchers.IO) {
            val proc = ProcessBuilder(
                "/usr/bin/security",
                "find-generic-password",
                "-s",
                SERVICE,
                "-a",
                account,
                "-w",
            )
                .redirectErrorStream(false)
                .start()
            val stdout = proc.inputStream.bufferedReader().readText().trim()
            val stderr = proc.errorStream.bufferedReader().readText().trim()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                Logger.w(tag = TAG) { "keychain retrieve timed out" }
                return@withContext null
            }
            val exit = proc.exitValue()
            Logger.i(tag = TAG) {
                "retrieve exit=$exit stdout.len=${stdout.length} stderr=${stderr.ifBlank { "<empty>" }}"
            }
            when (exit) {
                0 -> stdout.ifBlank { null }
                44 -> null // not found
                else -> {
                    Logger.w(tag = TAG) { "keychain retrieve failed (exit $exit): $stderr" }
                    null
                }
            }
        }
    }

    suspend fun delete(adfsHost: String, username: String): Boolean {
        if (!isSupported) return false
        val account = accountKey(adfsHost, username)
        return withContext(Dispatchers.IO) {
            val proc = ProcessBuilder(
                "/usr/bin/security",
                "delete-generic-password",
                "-s",
                SERVICE,
                "-a",
                account,
            )
                .redirectErrorStream(true)
                .start()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return@withContext false
            }
            proc.exitValue() == 0
        }
    }

    private fun accountKey(adfsHost: String, username: String) = "$username@$adfsHost"

    private const val TAG = "SecureCredentialStore"
}
