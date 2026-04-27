package fr.gunduz.awstoken.aws

import fr.gunduz.awstoken.auth.AdfsAuthenticator

/**
 * Fetch the first account alias registered in IAM for the AWS account that
 * owns the supplied STS credentials. Returns `null` on any failure (no alias,
 * credentials rejected, network error) — aliases are a cosmetic enhancement,
 * never a blocker.
 *
 * Implementation is platform-specific because SigV4 signing needs crypto
 * primitives that aren't in commonMain.
 */
expect class IamAccountAliasClient() {
    suspend fun fetchFirstAccountAlias(credentials: AdfsAuthenticator.StsCredentials): String?
}
