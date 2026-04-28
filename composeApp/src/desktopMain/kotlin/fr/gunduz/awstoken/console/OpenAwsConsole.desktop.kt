package fr.gunduz.awstoken.console

import co.touchlab.kermit.Logger
import fr.gunduz.awstoken.aws.CredentialsFileReader
import fr.gunduz.awstoken.aws.isNotExpired
import fr.gunduz.awstoken.desktop.console.AwsConsoleSignIn
import fr.gunduz.awstoken.desktop.console.BrowserOpener
import fr.gunduz.awstoken.model.AwsProfile

actual suspend fun openAwsConsoleForProfile(profile: AwsProfile): OpenConsoleResult {
    val creds =
        CredentialsFileReader().read(profile.name)
            ?: return OpenConsoleResult.MissingCreds
    if (!creds.isNotExpired()) {
        return OpenConsoleResult.ExpiredCreds
    }
    val urlResult = AwsConsoleSignIn().buildConsoleSignInUrl(profile, creds)
    return urlResult.fold(
        onSuccess = { url ->
            if (BrowserOpener.open(url)) {
                OpenConsoleResult.Success
            } else {
                OpenConsoleResult.Failed("Failed to launch the default browser")
            }
        },
        onFailure = { err ->
            Logger.w(err, tag = "OpenAwsConsole") { "federation failed for ${profile.name}" }
            OpenConsoleResult.Failed(err.message ?: "Federation request failed")
        },
    )
}
