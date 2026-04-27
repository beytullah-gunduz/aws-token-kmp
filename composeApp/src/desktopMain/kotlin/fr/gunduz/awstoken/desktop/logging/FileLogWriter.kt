package fr.gunduz.awstoken.desktop.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import fr.gunduz.awstoken.util.SystemDirectories
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kermit `LogWriter` that mirrors every log line into a file under
 * `~/.aws-token-kmp/logs/aws-token-kmp.log`. Opt-in via settings — writer is
 * installed unconditionally so the toggle can flip at runtime without having
 * to tear down and rebuild Kermit's writer list (which has no public remove
 * API), but each write checks `enabled.get()` and no-ops when off.
 *
 * Size cap: `MAX_BYTES`. Before each write, if the current file has exceeded
 * the cap we rename it to `.old` (overwriting any previous `.old`) and open a
 * fresh file. Single lock serialises writes + rotation across any thread that
 * logs.
 */
class FileLogWriter(
    private val file: File,
    internal val enabled: AtomicBoolean,
) : LogWriter() {
    private val formatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())
    private val lock = Any()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        if (!enabled.get()) return
        val ts = formatter.format(Instant.now())
        val throwableStr = throwable?.stackTraceToString() ?: ""
        val line = buildString {
            append(ts).append(' ')
            append(severity.name.padEnd(5)).append(' ')
            append('[').append(tag).append("] ")
            append(message)
            if (throwableStr.isNotBlank()) {
                append('\n').append(throwableStr)
            }
            append('\n')
        }
        synchronized(lock) {
            rotateIfTooLarge()
            file.appendText(line)
        }
    }

    private fun rotateIfTooLarge() {
        if (!file.exists() || file.length() < MAX_BYTES) return
        val old = File(file.parentFile, file.name + ".old")
        if (old.exists()) old.delete()
        file.renameTo(old)
        file.createNewFile()
    }

    companion object {
        const val MAX_BYTES: Long = 5L * 1024 * 1024
    }
}

object LoggingSetup {
    private val logsDir: File by lazy {
        File(SystemDirectories.applicationDirectory.toString(), "logs").apply { mkdirs() }
    }

    /** AtomicBoolean shared with the installed writer — set from main.kt. */
    val enabled: AtomicBoolean = AtomicBoolean(false)

    /**
     * Install the file logger unconditionally (so the `enabled` flag can be
     * toggled at runtime) and rotate any stale file from the previous run.
     * Returns the target file path for display.
     */
    fun installFileLogger(): File {
        val current = File(logsDir, "aws-token-kmp.log")
        if (current.exists() && current.length() > 0) {
            val old = File(logsDir, "aws-token-kmp.log.old")
            if (old.exists()) old.delete()
            current.renameTo(old)
        }
        current.createNewFile()
        co.touchlab.kermit.Logger.addLogWriter(FileLogWriter(current, enabled))
        return current
    }
}
