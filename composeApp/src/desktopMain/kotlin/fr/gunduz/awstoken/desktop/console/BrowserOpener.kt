package fr.gunduz.awstoken.desktop.console

import co.touchlab.kermit.Logger
import java.awt.Desktop
import java.net.URI

/**
 * Hand a URL to the OS default browser. Tries `java.awt.Desktop.browse()`
 * (the JVM's portable API) first; falls back to platform-specific shell
 * commands when Desktop is unavailable — happens on headless Linux JVMs and
 * inside CI containers where AWT isn't fully wired up.
 *
 * Both code paths fail soft and return `false` — the caller is expected to
 * surface a user-visible error (Snackbar) on failure rather than crashing.
 */
internal object BrowserOpener {
    fun open(url: String): Boolean {
        val uri =
            runCatching { URI(url) }
                .getOrElse {
                    Logger.w(it, tag = "BrowserOpener") { "invalid URL '$url'" }
                    return false
                }

        // Path 1: portable AWT API. Works on every platform when a desktop
        // session is attached and the JVM was started with AWT support.
        if (runCatching { Desktop.isDesktopSupported() }.getOrDefault(false)) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                runCatching { desktop.browse(uri) }
                    .onSuccess { return true }
                    .onFailure {
                        Logger.w(it, tag = "BrowserOpener") {
                            "Desktop.browse failed, falling back to platform shell"
                        }
                    }
            }
        }

        // Path 2: platform-specific shell. Last-resort path that works on
        // headless Linux + CI runners and on JVMs built without AWT.
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val cmd =
            when {
                osName.contains("mac") || osName.contains("darwin") -> arrayOf("open", url)
                osName.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
                else -> arrayOf("xdg-open", url) // Linux + everything else
            }
        return runCatching {
            ProcessBuilder(*cmd).start()
            true
        }.getOrElse {
            Logger.w(it, tag = "BrowserOpener") {
                "shell-open fallback failed: ${cmd.joinToString(" ")}"
            }
            false
        }
    }
}
