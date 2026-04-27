package fr.gunduz.awstoken.auth

// Android Keystore integration is a TODO — for now the bridge is a no-op.
actual suspend fun savePersistedPasswordForCurrentIdentity(password: String): Boolean = false

actual suspend fun loadPersistedPasswordForCurrentIdentity(): String? = null

actual suspend fun clearPersistedPasswordForCurrentIdentity() = Unit

actual fun isSecureCredentialStoreAvailable(): Boolean = false
