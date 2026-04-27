package fr.gunduz.awstoken.aws

import co.touchlab.kermit.Logger
import fr.gunduz.awstoken.auth.AdfsAuthenticator
import fr.gunduz.awstoken.model.AwsProfile
import fr.gunduz.awstoken.repository.PreferenceRepository

/**
 * Write STS credentials into `~/.aws/credentials` under the profile's own
 * name AND, if the profile is currently marked as the default, also update
 * the `[default]` section. The AWS CLI / SDKs resolve `aws --profile <name>`
 * against the named section and `aws` (no profile) against `[default]`, so
 * keeping the two in sync is what "default profile" actually means to the
 * rest of the toolchain.
 */
internal suspend fun writeCredsAndMaybeDefault(
    profile: AwsProfile,
    creds: AdfsAuthenticator.StsCredentials,
) {
    val writer = CredentialsFileWriter()
    writer.write(profile.name, profile.region, creds)

    val defaultId = PreferenceRepository.instance.getDefaultProfileId()
    if (defaultId == profile.id) {
        writer.write(profileName = "default", region = profile.region, credentials = creds)
        Logger.i(tag = "CredentialsWriteHelper") {
            "mirrored ${profile.name} to [default] (default profile)"
        }
    }
}

/**
 * Called when the user flips which profile is default in the tray. If the
 * new-default profile already has valid on-disk credentials, copy them into
 * the `[default]` section so they take effect without a fresh auth. If the
 * creds are missing or expired, the next authentication (automatic or
 * interactive) will mirror them — this helper just covers the common case.
 */
internal suspend fun mirrorDefaultFromDisk(profile: AwsProfile): Boolean {
    val existing = CredentialsFileReader().read(profile.name) ?: return false
    if (!existing.isNotExpired()) return false
    CredentialsFileWriter().write(profileName = "default", region = profile.region, credentials = existing)
    Logger.i(tag = "CredentialsWriteHelper") {
        "mirrored on-disk creds of ${profile.name} to [default] after default-profile change"
    }
    return true
}
