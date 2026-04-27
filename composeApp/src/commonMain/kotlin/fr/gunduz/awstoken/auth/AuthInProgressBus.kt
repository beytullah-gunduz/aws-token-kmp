package fr.gunduz.awstoken.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks which profile ids currently have an in-flight authentication.
 * Both the tray and the main window subscribe to this so they can render a
 * spinner next to refreshing profiles.
 *
 * Callers must use `begin` / `end` in a try/finally so cancellations and
 * exceptions can't leak a profile id into the set. For the common case
 * `withAuthInProgress(profileId) { ... }` wraps that pattern.
 */
object AuthInProgressBus {
    private val _activeProfileIds = MutableStateFlow<Set<String>>(emptySet())
    val activeProfileIds: StateFlow<Set<String>> = _activeProfileIds

    fun begin(profileId: String) {
        _activeProfileIds.value = _activeProfileIds.value + profileId
    }

    fun end(profileId: String) {
        _activeProfileIds.value = _activeProfileIds.value - profileId
    }
}

suspend inline fun <T> withAuthInProgress(profileId: String, block: () -> T): T {
    AuthInProgressBus.begin(profileId)
    return try {
        block()
    } finally {
        AuthInProgressBus.end(profileId)
    }
}
