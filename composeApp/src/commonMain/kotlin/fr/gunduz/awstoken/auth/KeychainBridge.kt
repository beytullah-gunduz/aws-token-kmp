package fr.gunduz.awstoken.auth

/**
 * Cross-platform façade for the OS keychain / secure credential store.
 * Common-main UI code (the settings dialog) calls these; desktopMain wires
 * them to `SecureCredentialStore`, androidMain stubs them out.
 */
expect suspend fun savePersistedPasswordForCurrentIdentity(password: String): Boolean

expect suspend fun loadPersistedPasswordForCurrentIdentity(): String?

expect suspend fun clearPersistedPasswordForCurrentIdentity()

expect fun isSecureCredentialStoreAvailable(): Boolean
