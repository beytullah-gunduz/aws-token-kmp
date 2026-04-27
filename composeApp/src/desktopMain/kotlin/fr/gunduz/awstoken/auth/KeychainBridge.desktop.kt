package fr.gunduz.awstoken.auth

import fr.gunduz.awstoken.desktop.auth.SecureCredentialStore
import fr.gunduz.awstoken.repository.PreferenceRepository

actual suspend fun savePersistedPasswordForCurrentIdentity(password: String): Boolean {
    val (host, user) = PreferenceRepository.instance.getLastDiscoveryIdentity()
    if (host.isBlank() || user.isBlank()) return false
    return SecureCredentialStore.store(host, user, password)
}

actual suspend fun loadPersistedPasswordForCurrentIdentity(): String? {
    val (host, user) = PreferenceRepository.instance.getLastDiscoveryIdentity()
    if (host.isBlank() || user.isBlank()) return null
    return SecureCredentialStore.retrieve(host, user)
}

actual suspend fun clearPersistedPasswordForCurrentIdentity() {
    val (host, user) = PreferenceRepository.instance.getLastDiscoveryIdentity()
    if (host.isBlank() || user.isBlank()) return
    SecureCredentialStore.delete(host, user)
}

actual fun isSecureCredentialStoreAvailable(): Boolean = SecureCredentialStore.isSupported
