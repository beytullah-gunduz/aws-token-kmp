package fr.gunduz.awstoken.console

import fr.gunduz.awstoken.model.AwsProfile

/**
 * Outcome of `openAwsConsoleForProfile` — granular enough that the UI can
 * decide whether to silently re-authenticate (`MissingCreds`, `ExpiredCreds`)
 * or surface a hard error to the user (`Failed`).
 */
sealed class OpenConsoleResult {
    /** Browser was launched with a valid sign-in URL. */
    object Success : OpenConsoleResult()

    /** No on-disk credentials for this profile yet — caller should trigger auth. */
    object MissingCreds : OpenConsoleResult()

    /** On-disk credentials are past their expiration — caller should re-auth. */
    object ExpiredCreds : OpenConsoleResult()

    /** Federation rejected the credentials, browser launch failed, etc. */
    data class Failed(val message: String) : OpenConsoleResult()
}

/**
 * Open the AWS Management Console in the default browser, signed in as the
 * role configured by [profile]. Reads the temporary STS credentials from
 * `~/.aws/credentials` (the same file the AWS CLI / SDKs use), exchanges
 * them for a single-use SigninToken via the AWS federation endpoint, and
 * hands the resulting URL to the OS.
 *
 * Desktop-only feature for now; the Android `actual` returns
 * `Failed("Console sign-in is desktop-only")` so callers in commonMain can
 * still target it without `expect`/`actual` UI gymnastics.
 */
expect suspend fun openAwsConsoleForProfile(profile: AwsProfile): OpenConsoleResult
