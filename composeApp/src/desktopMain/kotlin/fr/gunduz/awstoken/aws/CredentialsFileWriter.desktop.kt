package fr.gunduz.awstoken.aws

import fr.gunduz.awstoken.auth.AdfsAuthenticator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes/updates an entry in `~/.aws/credentials`. Very small ini-style writer —
 * deliberately not pulling a full ini library in. Only the named profile section
 * is rewritten; other profiles in the file are preserved verbatim.
 */
actual class CredentialsFileWriter actual constructor() {
    actual suspend fun write(
        profileName: String,
        region: String,
        credentials: AdfsAuthenticator.StsCredentials,
    ) = withContext(Dispatchers.IO) {
        val file = File(System.getProperty("user.home"), ".aws/credentials")
        file.parentFile?.mkdirs()

        val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
        val header = "[$profileName]"
        val headerIdx = lines.indexOfFirst { it.trim() == header }

        val newBlock = listOf(
            header,
            "aws_access_key_id = ${credentials.accessKeyId}",
            "aws_secret_access_key = ${credentials.secretAccessKey}",
            "aws_session_token = ${credentials.sessionToken}",
            "region = $region",
            "# expires: ${credentials.expirationIso}",
        )

        if (headerIdx < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += newBlock
        } else {
            val end = (headerIdx + 1 until lines.size).firstOrNull {
                lines[it].trim().startsWith("[") && lines[it].trim().endsWith("]")
            } ?: lines.size
            val before = lines.subList(0, headerIdx)
            val after = lines.subList(end, lines.size)
            val merged = before + newBlock + after
            lines.clear()
            lines += merged
        }

        file.writeText(lines.joinToString("\n") + "\n")
    }
}
