package fr.gunduz.awstoken.console

import fr.gunduz.awstoken.model.AwsProfile

/**
 * Stub: the Android target doesn't manage `~/.aws/credentials` and has no
 * default-browser federation flow wired up. Always returns Failed so the UI
 * can surface a snackbar without crashing or special-casing the platform.
 */
actual suspend fun openAwsConsoleForProfile(profile: AwsProfile): OpenConsoleResult = OpenConsoleResult.Failed("Console sign-in is desktop-only")
