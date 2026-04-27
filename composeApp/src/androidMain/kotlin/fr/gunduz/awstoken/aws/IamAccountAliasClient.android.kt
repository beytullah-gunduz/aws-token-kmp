package fr.gunduz.awstoken.aws

import fr.gunduz.awstoken.auth.AdfsAuthenticator

/**
 * Android stub — alias fetching isn't wired on mobile yet. The desktop
 * implementation uses JVM `javax.crypto` + `java.security` directly; on
 * Android we'd reuse the exact same code (both are JVM) but keeping the
 * platform-specific actual here makes it easy to add once the Android UI
 * actually ships auth.
 */
actual class IamAccountAliasClient actual constructor() {
    actual suspend fun fetchFirstAccountAlias(credentials: AdfsAuthenticator.StsCredentials): String? = null
}
