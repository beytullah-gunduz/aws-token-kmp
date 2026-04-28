package fr.gunduz.awstoken.desktop.tray

import co.touchlab.kermit.Logger
import fr.gunduz.awstoken.model.AwsProfile
import fr.gunduz.awstoken.model.AwsRegion
import java.awt.BasicStroke
import java.awt.CheckboxMenuItem
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * AWT-based system tray. We deliberately do NOT use Compose Desktop's
 * `androidx.compose.ui.window.Tray` DSL because its `menu { Item / Separator }`
 * API exposes neither `CheckboxMenuItem` (needed for the per-profile refresh
 * toggle) nor nested `Menu` (needed for the "hover opens region submenu"
 * behaviour). AWT's `PopupMenu` gives us both natively, and every desktop
 * platform we target already has a working Swing/AWT event loop.
 *
 * The tray is rebuilt in place whenever the profiles list or default-id changes
 * — `updateProfiles` replaces the whole popup menu. This is cheap (a dozen
 * menu items) and avoids stale closure captures.
 */
class AwsTokenTray(
    private val onShowWindow: () -> Unit,
    private val onQuit: () -> Unit,
    private val onToggleRefresh: (profileId: String, enabled: Boolean) -> Unit,
    private val onSelectRegion: (profileId: String, region: AwsRegion) -> Unit,
    private val onSetDefault: (profileId: String) -> Unit,
    private val onAuthenticate: (profileId: String) -> Unit,
    private val onOpenConsole: (profileId: String) -> Unit,
) {
    private var trayIcon: TrayIcon? = null
    private var iconSize: Dimension = Dimension(22, 22)
    private var staticIcon: BufferedImage? = null
    private var spinnerTimer: javax.swing.Timer? = null
    private var spinnerAngle = 0

    fun install() {
        if (!SystemTray.isSupported()) {
            Logger.w { "SystemTray not supported on this platform — tray disabled" }
            return
        }
        val tray = SystemTray.getSystemTray()
        iconSize = tray.trayIconSize.let {
            Dimension(it.width.coerceAtLeast(16), it.height.coerceAtLeast(16))
        }
        val image = renderIcon(iconSize.width, iconSize.height).also { staticIcon = it }
        val icon = TrayIcon(image, "AWS Token").apply {
            isImageAutoSize = true
            addActionListener { onShowWindow() }
        }
        tray.add(icon)
        trayIcon = icon
        // Process-wide handle so callbacks defined where the tray is being
        // constructed (in `main.kt`) can route notifications back here
        // without needing a self-reference. Same pattern as
        // `RefreshScheduler.current`.
        current = this
    }

    private fun renderIcon(w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(255, 153, 0) // AWS orange
        g.fillRoundRect(0, 0, w, h, w / 4, h / 4)
        g.color = Color.WHITE
        g.stroke = BasicStroke(1.5f)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, (h * 0.55).toInt())
        val label = "AWS"
        val fm = g.fontMetrics
        val tx = (w - fm.stringWidth(label)) / 2
        val ty = (h - fm.height) / 2 + fm.ascent
        g.drawString(label, tx, ty)
        g.dispose()
        return img
    }

    fun remove() {
        stopSpinner()
        trayIcon?.let { SystemTray.getSystemTray().remove(it) }
        trayIcon = null
        if (current === this) current = null
    }

    /**
     * Show an OS-native notification anchored to the tray icon. Used by
     * tray-initiated actions (e.g. "Open in AWS Console") to surface
     * failures without requiring the user to be in the main window — the
     * notification routes through Notification Center on macOS, balloons
     * on Windows, libnotify on Linux. No-op if `install()` hasn't run yet
     * or the platform doesn't support a tray.
     */
    fun notify(title: String, message: String, isError: Boolean = false) {
        val type = if (isError) TrayIcon.MessageType.WARNING else TrayIcon.MessageType.INFO
        runCatching { trayIcon?.displayMessage(title, message, type) }
            .onFailure { Logger.w(it, tag = "AwsTokenTray") { "tray notify failed: $title — $message" } }
    }

    fun updateProfiles(
        profiles: List<AwsProfile>,
        defaultProfileId: String?,
        accountAliases: Map<String, String>,
        activeAuthProfileIds: Set<String> = emptySet(),
        expirations: Map<String, Long> = emptyMap(),
    ) {
        val icon = trayIcon ?: return
        icon.popupMenu = buildMenu(profiles, defaultProfileId, accountAliases, activeAuthProfileIds)
        val activeCount = profiles.count { it.refreshEnabled }
        val refreshing = activeAuthProfileIds.size

        // Tooltip: active / refreshing summary + soonest expiration of any
        // profile that currently has fresh creds on disk. macOS AWT caps
        // tooltips at ~64 chars so we keep it terse.
        val soonestEta = computeSoonestEta(profiles, expirations)
        icon.toolTip = buildString {
            append("AWS Token — ")
            append(if (activeCount == 0) "no active" else "$activeCount active")
            if (refreshing > 0) append(" · $refreshing refreshing")
            if (soonestEta != null) append(" · next expiry ").append(formatRemaining(soonestEta))
        }

        // Drive the animated spinner off the active set. Any non-empty set
        // spins; the moment it empties we snap back to the static icon.
        if (refreshing > 0) startSpinner() else stopSpinner()
    }

    /**
     * Start a 10 Hz Swing timer that repaints the tray icon as a rotating
     * arc. Runs on the EDT (Swing `Timer`'s ActionListener fires on the
     * event thread), same place AWT itself updates the tray image. Cheap —
     * one `BufferedImage` allocation per frame at a 22×22 resolution.
     */
    private fun startSpinner() {
        if (spinnerTimer?.isRunning == true) return
        val icon = trayIcon ?: return
        spinnerTimer = javax.swing.Timer(100) {
            spinnerAngle = (spinnerAngle + 30) % 360
            icon.image = renderSpinnerIcon(iconSize.width, iconSize.height, spinnerAngle)
        }.apply {
            isCoalesce = true
            start()
        }
    }

    private fun stopSpinner() {
        spinnerTimer?.stop()
        spinnerTimer = null
        val icon = trayIcon ?: return
        staticIcon?.let { icon.image = it }
    }

    private fun computeSoonestEta(profiles: List<AwsProfile>, expirations: Map<String, Long>): Long? {
        if (profiles.isEmpty() || expirations.isEmpty()) return null
        val now = System.currentTimeMillis()
        val remaining = profiles.mapNotNull { expirations[it.id] }
            .map { it - now }
            .filter { it > 0 }
        return remaining.minOrNull()
    }

    private fun formatRemaining(millis: Long): String {
        if (millis <= 0) return "expired"
        val totalSec = millis / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return when {
            hours > 0 -> "${hours}h${minutes}m"
            minutes > 0 -> "${minutes}m${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun renderSpinnerIcon(w: Int, h: Int, rotationDegrees: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Dimmed base: AWS orange rounded square with reduced alpha so the
        // spinner reads as "same app, just busy".
        g.color = Color(255, 153, 0, 110)
        g.fillRoundRect(0, 0, w, h, w / 4, h / 4)

        // Track: full-circle faint ring.
        val inset = (w * 0.15).toInt().coerceAtLeast(1)
        val size = w - 2 * inset - 1
        g.color = Color(255, 255, 255, 80)
        g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.drawOval(inset, inset, size, size)

        // Active arc: 270° wedge that rotates so the eye reads motion.
        g.color = Color.WHITE
        g.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        // drawArc's 0° is 3 o'clock, positive angles sweep counter-clockwise.
        g.drawArc(inset, inset, size, size, 90 - rotationDegrees, -270)

        g.dispose()
        return img
    }

    private fun buildMenu(
        profiles: List<AwsProfile>,
        defaultProfileId: String?,
        accountAliases: Map<String, String>,
        activeAuthProfileIds: Set<String>,
    ): PopupMenu {
        val popup = PopupMenu()

        popup.add(MenuItem("Show window").apply { addActionListener { onShowWindow() } })
        popup.addSeparator()

        if (profiles.isEmpty()) {
            popup.add(MenuItem("(no profiles — open window to add)").apply { isEnabled = false })
        } else {
            // Group profiles by AWS account id. The account row is rendered as
            // a disabled MenuItem (AWT's equivalent of a gray section header —
            // no hover, no click). Each profile under it is a Menu (submenu)
            // whose label is prefixed with a leading space to visually indent
            // it below the header. AWT menus don't support real indentation,
            // so the prefix is the cheapest visual hierarchy we get.
            profiles
                .groupBy { it.accountId }
                .toSortedMap()
                .forEach { (accountId, accountProfiles) ->
                    val alias = accountAliases[accountId]
                    val accountRefreshing = accountProfiles.any { it.id in activeAuthProfileIds }
                    val headerLabel = buildString {
                        if (accountRefreshing) append("⟳ ")
                        append(if (alias != null) "$alias  ($accountId)" else "Account $accountId")
                    }
                    popup.add(MenuItem(headerLabel).apply { isEnabled = false })
                    accountProfiles.forEach { profile ->
                        popup.add(
                            buildProfileMenu(
                                profile = profile,
                                isDefault = profile.id == defaultProfileId,
                                isRefreshing = profile.id in activeAuthProfileIds,
                            ),
                        )
                    }
                    popup.addSeparator()
                }
        }

        popup.add(MenuItem("Quit").apply { addActionListener { onQuit() } })
        return popup
    }

    private fun buildProfileMenu(profile: AwsProfile, isDefault: Boolean, isRefreshing: Boolean): Menu {
        val label = buildString {
            append("   ") // indent under the account header
            if (isRefreshing) append("⟳ ")
            if (isDefault) append("★ ")
            append(profile.roleName)
            if (profile.refreshEnabled) append(" ✓")
        }
        val profileMenu = Menu(label)

        // Checkbox: enable refresh + authenticate. Toggling it fires the
        // refresh enable/disable callback; if being turned ON we also trigger
        // an immediate authenticate so the user gets fresh creds right away.
        val refreshCheckbox = CheckboxMenuItem("Refresh enabled", profile.refreshEnabled)
        refreshCheckbox.addItemListener { event ->
            val nowOn = event.stateChange == java.awt.event.ItemEvent.SELECTED
            onToggleRefresh(profile.id, nowOn)
            if (nowOn) onAuthenticate(profile.id)
        }
        profileMenu.add(refreshCheckbox)

        profileMenu.add(
            MenuItem("Authenticate now").apply {
                addActionListener { onAuthenticate(profile.id) }
            },
        )

        profileMenu.add(
            MenuItem("Open in AWS Console").apply {
                addActionListener { onOpenConsole(profile.id) }
            },
        )

        profileMenu.addSeparator()

        // Region submenu — hover-opened because AWT Menu is always
        // hover-expandable when nested inside a PopupMenu.
        val regionMenu = Menu("Region: ${profile.region}")
        AwsRegion.entries.forEach { region ->
            val marker = if (region.code == profile.region) "● " else "   "
            val item = MenuItem("$marker${region.code} — ${region.displayName}")
            item.addActionListener { onSelectRegion(profile.id, region) }
            regionMenu.add(item)
        }
        profileMenu.add(regionMenu)

        profileMenu.addSeparator()
        val setDefault = MenuItem(if (isDefault) "Default profile" else "Set as default profile")
        setDefault.isEnabled = !isDefault
        setDefault.addActionListener { onSetDefault(profile.id) }
        profileMenu.add(setDefault)

        return profileMenu
    }

    companion object {
        /**
         * Process-wide handle to the live tray instance. Set in `install`
         * and cleared in `remove`. Lets `main.kt`-side callbacks route OS
         * notifications back through the tray (e.g. when a tray menu action
         * fails) without needing a self-reference at construction time.
         * Single tray per process so the singleton is sufficient.
         */
        @Volatile
        var current: AwsTokenTray? = null
            private set
    }
}
