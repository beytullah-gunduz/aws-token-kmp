package fr.gunduz.awstoken.aws

import fr.gunduz.awstoken.auth.AdfsAuthenticator
import fr.gunduz.awstoken.utils.applicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android has no shared `~/.aws/credentials` concept — we stash the freshly
 * issued STS credentials in app-private storage instead so the GUI can display
 * them / copy them out. Consumers on Android are expected to copy values into
 * other apps rather than have another tool read them off disk.
 */
actual class CredentialsFileWriter actual constructor() {
    actual suspend fun write(
        profileName: String,
        region: String,
        credentials: AdfsAuthenticator.StsCredentials,
    ) = withContext(Dispatchers.IO) {
        val dir = File(applicationContext.filesDir, "aws-credentials")
        dir.mkdirs()
        val file = File(dir, "$profileName.ini")
        file.writeText(
            buildString {
                append("[$profileName]\n")
                append("aws_access_key_id = ${credentials.accessKeyId}\n")
                append("aws_secret_access_key = ${credentials.secretAccessKey}\n")
                append("aws_session_token = ${credentials.sessionToken}\n")
                append("region = $region\n")
                append("# expires: ${credentials.expirationIso}\n")
            },
        )
    }
}
