package fr.gunduz.awstoken.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Navigation 3 back-stack keys. Sealed so the entry-provider `when` is
 * exhaustive, which saves us a fragile `else -> error("unknown")` branch
 * when new screens are added. All three are object singletons — the app
 * has no per-route state (no profile IDs or query params carried across
 * screens), so there's nothing to put in a data class.
 *
 * `@Serializable` lets Nav3's SavedState machinery persist the back stack
 * across Activity recreation on Android. On desktop/iOS the annotation is
 * harmless.
 */
sealed interface Route : NavKey

@Serializable
data object Login : Route

@Serializable
data object AccountList : Route

@Serializable
data object Settings : Route
