package fr.gunduz.awstoken.model

import kotlinx.serialization.Serializable

/**
 * A user-configured AWS profile backed by ADFS. Secrets (password) are **not**
 * stored here — the auth flow prompts for them in-memory. The persisted shape is
 * everything needed to kick off an `AssumeRoleWithSAML` against a specific IdP
 * host + role combo, plus the region the resulting credentials default to.
 */
@Serializable
data class AwsProfile(
    val id: String,
    val name: String,
    val adfsHost: String,
    val username: String,
    val principalArn: String,
    val roleArn: String,
    val region: String = AwsRegion.DEFAULT.code,
    val refreshEnabled: Boolean = false,
) {
    /** `arn:aws:iam::123456789012:role/MyRole` → `123456789012`. */
    val accountId: String get() = roleArn.substringAfter("::").substringBefore(":")

    /** `arn:aws:iam::123456789012:role/MyRole` → `MyRole`. */
    val roleName: String get() = roleArn.substringAfterLast("/")
}
