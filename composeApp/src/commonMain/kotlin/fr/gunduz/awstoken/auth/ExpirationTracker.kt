package fr.gunduz.awstoken.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Session-wide ledger of STS credential expiration times, keyed by profile
 * id. Populated from two sources:
 *
 *   1. On app launch, `main.kt` reads the `# expires:` comment out of every
 *      profile's section in `~/.aws/credentials` and feeds the result here so
 *      the UI can show a countdown from the moment the window opens.
 *   2. `RefreshScheduler.recordExpiration` — and by extension every
 *      successful foreground/background auth — mirrors the fresh expiration
 *      here after each re-auth.
 *
 * Both the main window and the tray observe `expirations` so they can render
 * "expires in Xm Ys" / adjust the tray tooltip. Epoch milliseconds instead of
 * `kotlinx.datetime.Instant` to keep allocations down on the per-second
 * recomposition loop; callers convert at the display site.
 */
object ExpirationTracker {
    private val _expirations = MutableStateFlow<Map<String, Long>>(emptyMap())
    val expirations: StateFlow<Map<String, Long>> = _expirations

    fun record(profileId: String, epochMillis: Long) {
        _expirations.value = _expirations.value + (profileId to epochMillis)
    }

    fun clear(profileId: String) {
        _expirations.value = _expirations.value - profileId
    }
}
