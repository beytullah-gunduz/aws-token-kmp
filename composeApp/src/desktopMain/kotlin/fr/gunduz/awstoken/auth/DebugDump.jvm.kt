package fr.gunduz.awstoken.auth

import fr.gunduz.awstoken.util.SystemDirectories
import java.io.File

private val debugDir: File by lazy {
    File(SystemDirectories.applicationDirectory.toString(), "debug").apply { mkdirs() }
}

internal actual fun debugDump(filename: String, content: String) {
    runCatching { File(debugDir, filename).writeText(content) }
}

internal actual fun debugDumpPath(filename: String): String = File(debugDir, filename).absolutePath

internal actual fun resetDebugDumpDir() {
    runCatching {
        debugDir.listFiles()?.forEach { it.delete() }
    }
}
