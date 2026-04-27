package fr.gunduz.awstoken.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds back every piece of automatic startup work until the user has
 * explicitly completed the initial Discovery dialog at least once in this
 * session. The gate is used by `main.kt` to suspend:
 *
 *   - the OS Keychain → `PasswordCache` primer,
 *   - the auto-auth-at-start default profile refresh,
 *   - the `RefreshScheduler` background tick loop, and
 *   - the `ExpirationTracker` seeding from `~/.aws/credentials`.
 *
 * Nothing touches the network or the shared credentials file until the user
 * has either submitted a successful discovery (which flips `discoveryComplete`
 * to `true`) or cancelled the dialog (which is a no-op — cancelling stays
 * locked, the user can still drive the app manually via the tray).
 *
 * State is process-local: every launch starts with the gate closed.
 */
object StartupGate {
    private val _discoveryComplete = MutableStateFlow(false)
    val discoveryComplete: StateFlow<Boolean> = _discoveryComplete

    fun markDiscoveryComplete() {
        _discoveryComplete.value = true
    }
}
