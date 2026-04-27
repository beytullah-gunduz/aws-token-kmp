package fr.gunduz.awstoken.aws

import fr.gunduz.awstoken.auth.AdfsAuthenticator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Read back an `~/.aws/credentials` entry that `CredentialsFileWriter` put
 * there, including the non-standard `# expires:` comment so callers can
 * decide whether the cached STS session is still usable. No ini parser
 * dependency — the writer's format is fixed and trivial to line-scan.
 */
internal class CredentialsFileReader {
    suspend fun read(profileName: String): AdfsAuthenticator.StsCredentials? = withContext(Dispatchers.IO) {
        val file = File(System.getProperty("user.home"), ".aws/credentials")
        if (!file.exists()) return@withContext null

        val lines = file.readLines()
        val header = "[$profileName]"
        val headerIdx = lines.indexOfFirst { it.trim() == header }
        if (headerIdx < 0) return@withContext null

        val endIdx = (headerIdx + 1 until lines.size).firstOrNull {
            val t = lines[it].trim()
            t.startsWith("[") && t.endsWith("]")
        } ?: lines.size

        var accessKeyId: String? = null
        var secretAccessKey: String? = null
        var sessionToken: String? = null
        var expirationIso: String? = null

        for (i in (headerIdx + 1) until endIdx) {
            val line = lines[i].trim()
            when {
                line.startsWith("aws_access_key_id") -> accessKeyId = line.substringAfter("=").trim()
                line.startsWith("aws_secret_access_key") -> secretAccessKey = line.substringAfter("=").trim()
                line.startsWith("aws_session_token") -> sessionToken = line.substringAfter("=").trim()
                line.startsWith("# expires:") -> expirationIso = line.substringAfter("# expires:").trim()
            }
        }

        if (accessKeyId == null || secretAccessKey == null || sessionToken == null || expirationIso == null) {
            return@withContext null
        }
        AdfsAuthenticator.StsCredentials(accessKeyId, secretAccessKey, sessionToken, expirationIso)
    }
}

internal fun AdfsAuthenticator.StsCredentials.isNotExpired(now: Instant = Instant.now()): Boolean = runCatching { Instant.parse(expirationIso).isAfter(now) }.getOrDefault(false)
