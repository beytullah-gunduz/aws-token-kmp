package fr.gunduz.awstoken.auth

// No session cache or keychain on Android today — stub out.
actual suspend fun rememberPasswordForSession(password: String) = Unit

actual suspend fun readCachedSessionPassword(adfsHost: String, username: String): String? = null

actual suspend fun dropCachedSessionPassword(adfsHost: String, username: String) = Unit
