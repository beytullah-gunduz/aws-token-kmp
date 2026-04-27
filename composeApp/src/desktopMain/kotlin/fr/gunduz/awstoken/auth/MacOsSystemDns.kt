package fr.gunduz.awstoken.auth

import co.touchlab.kermit.Logger
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Custom OkHttp DNS resolver for macOS. Tries the JVM's built-in resolver
 * first, then a chain of macOS command-line tools that each use a different
 * path into the system resolver:
 *
 *   1. `/usr/bin/dscacheutil` — legacy DirectoryService path. Fast when it
 *      works, but on modern macOS (Ventura+) often returns no results for
 *      VPN-pushed DNS because mDNSResponder doesn't backfeed DirectoryService.
 *   2. `/usr/bin/host` — uses libresolv directly. This is what `ping`, Safari,
 *      and basically every native tool end up calling. Sees VPN DNS servers
 *      installed via Network Extensions.
 *   3. `/usr/bin/dig` — last resort if `host` isn't present (rare on macOS).
 *
 * Whichever one first returns at least one address wins. Raw tool output is
 * logged at DEBUG so issues are easy to diagnose — if all three return
 * nothing, the VPN itself isn't advertising the host and the app can't fix
 * that for you.
 */
internal class MacOsSystemDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val jvmResult = runCatching { Dns.SYSTEM.lookup(hostname) }
        jvmResult.onSuccess { return it }

        for (strategy in strategies) {
            val result = runCatching { strategy.resolve(hostname) }
            result.onSuccess { addresses ->
                if (addresses.isNotEmpty()) {
                    Logger.d(tag = TAG) {
                        "${strategy.name} resolved $hostname → ${addresses.map { it.hostAddress }}"
                    }
                    return addresses
                } else {
                    Logger.d(tag = TAG) { "${strategy.name} returned no addresses for $hostname" }
                }
            }.onFailure { t ->
                Logger.d(t, tag = TAG) { "${strategy.name} failed for $hostname" }
            }
        }

        Logger.w(tag = TAG) {
            "All macOS DNS fallbacks failed for $hostname. " +
                "Check VPN is up and `host $hostname` works in Terminal."
        }
        throw jvmResult.exceptionOrNull() ?: UnknownHostException(hostname)
    }

    private interface Strategy {
        val name: String
        fun resolve(hostname: String): List<InetAddress>
    }

    private val strategies: List<Strategy> = listOf(Dscacheutil, HostTool, DigTool)

    companion object {
        private const val TAG = "MacOsSystemDns"
        val IS_MAC: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

        private fun runCmd(vararg cmd: String): String {
            val proc = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                throw UnknownHostException("${cmd.first()} timed out")
            }
            return proc.inputStream.bufferedReader().readText()
        }

        private fun String.toInetAddressOrNull(): InetAddress? = runCatching { InetAddress.getByName(this) }.getOrNull()
    }

    // --- dscacheutil ---

    private object Dscacheutil : Strategy {
        override val name = "dscacheutil"
        override fun resolve(hostname: String): List<InetAddress> {
            val output = runCmd("/usr/bin/dscacheutil", "-q", "host", "-a", "name", hostname)
            Logger.d(tag = TAG) { "dscacheutil raw output for $hostname:\n$output" }
            return output.lineSequence()
                .mapNotNull {
                    when {
                        it.startsWith("ip_address:") -> it.substringAfter(":").trim()
                        it.startsWith("ipv6_address:") -> it.substringAfter(":").trim()
                        else -> null
                    }
                }
                .filter { it.isNotEmpty() }
                .mapNotNull { it.toInetAddressOrNull() }
                .toList()
        }
    }

    // --- host (BIND tool shipped with macOS) ---

    private object HostTool : Strategy {
        override val name = "host"
        override fun resolve(hostname: String): List<InetAddress> {
            val output = runCmd("/usr/bin/host", hostname)
            Logger.d(tag = TAG) { "host raw output for $hostname:\n$output" }
            // Lines look like:
            //   adfs.ad.example.com has address 10.0.0.42
            //   adfs.ad.example.com has IPv6 address fd00::1
            val ipv4 = Regex("has address ([0-9.]+)")
            val ipv6 = Regex("has IPv6 address ([0-9a-fA-F:]+)")
            return (ipv4.findAll(output) + ipv6.findAll(output))
                .mapNotNull { it.groupValues[1].toInetAddressOrNull() }
                .toList()
        }
    }

    // --- dig fallback ---

    private object DigTool : Strategy {
        override val name = "dig"
        override fun resolve(hostname: String): List<InetAddress> {
            val output = runCmd("/usr/bin/dig", "+short", hostname, "A", hostname, "AAAA")
            Logger.d(tag = TAG) { "dig raw output for $hostname:\n$output" }
            return output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains(' ') }
                .mapNotNull { it.toInetAddressOrNull() }
                .toList()
        }
    }
}
