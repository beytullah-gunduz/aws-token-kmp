package fr.gunduz.awstoken.aws

import fr.gunduz.awstoken.auth.AdfsAuthenticator

/**
 * Writes STS session credentials into the AWS shared credentials file under
 * the profile's name. Actual file IO is per-platform (desktop writes to
 * `~/.aws/credentials`; android writes to app-private storage because there is
 * no shared AWS credential file concept). Injected via expect/actual.
 */
expect class CredentialsFileWriter() {
    suspend fun write(
        profileName: String,
        region: String,
        credentials: AdfsAuthenticator.StsCredentials,
    )
}
