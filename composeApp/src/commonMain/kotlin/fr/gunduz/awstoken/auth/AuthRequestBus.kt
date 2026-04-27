package fr.gunduz.awstoken.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One-slot bus carrying the profile id whose password dialog should be shown.
 * Read from multiple points:
 *   - the tray (AWT event thread, outside the Compose tree)
 *   - clicks on profile cards in the main window (inside the Compose tree)
 *
 * Both write here; `main.kt` observes the flow inside `application { }` and
 * drives the password dialog. Single-slot is fine: the UI is single-threaded
 * from a user perspective (they can't click two tray items at once), and
 * overwriting an outstanding request just replaces it with the newer one.
 */
object AuthRequestBus {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    fun request(profileId: String) {
        _pending.value = profileId
    }

    fun clear() {
        _pending.value = null
    }
}
