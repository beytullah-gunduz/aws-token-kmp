import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import co.touchlab.kermit.Logger
import fr.gunduz.awstoken.auth.AdfsAuthenticator
import fr.gunduz.awstoken.auth.AuthInProgressBus
import fr.gunduz.awstoken.auth.AuthRequestBus
import fr.gunduz.awstoken.auth.ExpirationTracker
import fr.gunduz.awstoken.auth.StartupGate
import fr.gunduz.awstoken.auth.loadPersistedPasswordForCurrentIdentity
import fr.gunduz.awstoken.auth.rememberPasswordForSession
import fr.gunduz.awstoken.auth.withAuthInProgress
import fr.gunduz.awstoken.aws.CredentialsFileReader
import fr.gunduz.awstoken.aws.IamAccountAliasClient
import fr.gunduz.awstoken.aws.mirrorDefaultFromDisk
import fr.gunduz.awstoken.aws.writeCredsAndMaybeDefault
import fr.gunduz.awstoken.desktop.auth.PasswordCache
import fr.gunduz.awstoken.desktop.auth.RefreshScheduler
import fr.gunduz.awstoken.desktop.chrome.DockIconVisibility
import fr.gunduz.awstoken.desktop.logging.LoggingSetup
import fr.gunduz.awstoken.desktop.tray.AwsTokenTray
import fr.gunduz.awstoken.desktop.ui.PasswordDialog
import fr.gunduz.awstoken.model.AwsProfile
import fr.gunduz.awstoken.repository.PreferenceRepository
import fr.gunduz.awstoken.ui.chrome.LocalDesktopWindowChrome
import fr.gunduz.awstoken.ui.chrome.rememberDesktopWindowChrome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

/**
 * Consolidates everything that has to happen when an interactive auth
 * succeeds: write `~/.aws/credentials`, cache the password so the refresh
 * loop can re-auth later, record the expiration for the scheduler, and —
 * if the account's alias isn't already known — kick off a best-effort
 * `iam:ListAccountAliases` with the freshly minted STS credentials.
 */
private suspend fun onAuthSuccess(
    profile: AwsProfile,
    password: String,
    creds: AdfsAuthenticator.StsCredentials,
    scheduler: RefreshScheduler,
    currentAliases: Map<String, String>,
) {
    writeCredsAndMaybeDefault(profile, creds)
    Logger.i { "Wrote STS credentials for ${profile.name} (expires ${creds.expirationIso})" }

    // Cache in memory + mirror to keychain if the pref is on. One call funnels
    // all password-persistence concerns through `SessionPasswordBook` so every
    // dialog path lands in the same place.
    rememberPasswordForSession(password)
    scheduler.recordExpiration(profile.id, creds.expirationIso)

    if (profile.accountId !in currentAliases) {
        appScope.launch {
            runCatching { IamAccountAliasClient().fetchFirstAccountAlias(creds) }
                .onSuccess { alias ->
                    if (alias != null) {
                        PreferenceRepository.instance.setAccountAlias(profile.accountId, alias)
                        Logger.i { "Fetched alias '$alias' for account ${profile.accountId}" }
                    }
                }
                .onFailure { Logger.w(it) { "Alias fetch failed for ${profile.accountId}" } }
        }
    }

    // If the user authenticated a non-default profile, reuse their still-in-hand
    // password to also refresh the default profile's STS credentials. Keeps
    // `aws` (no `--profile`) in sync with whatever the user considers their
    // current default, without forcing a second interactive prompt.
    val defaultId = PreferenceRepository.instance.getDefaultProfileId()
    if (defaultId != null && defaultId != profile.id) {
        val defaultProfile = PreferenceRepository.instance.getProfiles().firstOrNull { it.id == defaultId }
        if (defaultProfile != null) {
            appScope.launch {
                Logger.i { "Also refreshing default profile ${defaultProfile.name}" }
                val defResult = withAuthInProgress(defaultProfile.id) {
                    AdfsAuthenticator().authenticate(defaultProfile, password)
                }
                defResult.onSuccess { defCreds ->
                    writeCredsAndMaybeDefault(defaultProfile, defCreds)
                    scheduler.recordExpiration(defaultProfile.id, defCreds.expirationIso)
                }.onFailure {
                    Logger.w(it) { "Default profile refresh failed" }
                }
            }
        }
    }
}

/**
 * Must run before ANY network call so the JVM's `ProxySelector.getDefault()`
 * is populated from the OS system-proxy settings. Without this, corporate
 * networks whose ADFS host is only reachable through an HTTP proxy fail with
 * `UnresolvedAddressException` — the browser picks up the proxy via macOS
 * Network preferences but the JVM refuses to consult them unless we opt in.
 */
private fun configureSystemNetworking() {
    System.setProperty("java.net.useSystemProxies", "true")
}

fun main() {
    configureSystemNetworking()
    // Install unconditionally — the writer checks LoggingSetup.enabled on
    // every log call and no-ops when the setting is off. The initial value
    // is read from prefs on first composition via a LaunchedEffect below.
    val logFile = LoggingSetup.installFileLogger()
    Logger.i { "file logger installed at ${logFile.absolutePath} (gated by settings toggle)" }
    application { awsTokenApp() }
}

@OptIn(FlowPreview::class)
@androidx.compose.runtime.Composable
private fun androidx.compose.ui.window.ApplicationScope.awsTokenApp() {
    // Narrower + taller default — the main content is a vertical list of
    // profiles grouped by account, so horizontal space is wasted and
    // vertical space is at a premium. 520 × 780 feels like a "panel" the
    // user docks to one side of the screen, similar to a Spotify / VSCode
    // side panel. The persisted size (see the LaunchedEffects below) takes
    // over as soon as DataStore replies.
    val windowState = rememberWindowState(
        size = DpSize(
            PreferenceRepository.DEFAULT_WINDOW_WIDTH_DP.dp,
            PreferenceRepository.DEFAULT_WINDOW_HEIGHT_DP.dp,
        ),
        position = WindowPosition(Alignment.Center),
    )

    // Restore the persisted window size on first composition. A brief flash
    // at the default size is acceptable — DataStore is fast enough that the
    // eye rarely catches it.
    LaunchedEffect(Unit) {
        val saved = PreferenceRepository.instance.getWindowSize()
        if (saved != null) {
            windowState.size = DpSize(saved.first.dp, saved.second.dp)
            Logger.i { "restored window size: ${saved.first} × ${saved.second}" }
        }
    }

    // Save any size change 500 ms after the user stops resizing. `drop(1)`
    // skips the initial emission from `snapshotFlow` (the default or
    // just-restored size) so we don't rewrite the pref with the same value
    // right after loading it.
    LaunchedEffect(Unit) {
        snapshotFlow { windowState.size }
            .drop(1)
            .debounce(500)
            .collect { size ->
                PreferenceRepository.instance.setWindowSize(
                    widthDp = size.width.value,
                    heightDp = size.height.value,
                )
                Logger.i { "persisted window size: ${size.width.value} × ${size.height.value}" }
            }
    }

    // Fullscreen is disabled: if something external (macOS keyboard shortcut,
    // accessibility tool, etc.) flips the window placement away from
    // `Floating`, snap it back immediately. The traffic lights no longer
    // offer a maximize action and the drag area no longer handles
    // double-click, so nothing inside the app can trigger this either.
    LaunchedEffect(Unit) {
        snapshotFlow { windowState.placement }.collect { placement ->
            if (placement != WindowPlacement.Floating) {
                windowState.placement = WindowPlacement.Floating
            }
        }
    }

    val prefs = PreferenceRepository.instance
    val profiles by prefs.profilesAsFlow.collectAsState(initial = emptyList())
    val defaultId by prefs.defaultProfileIdAsFlow.collectAsState(initial = null)
    val accountAliases by prefs.accountAliasesAsFlow.collectAsState(initial = emptyMap())
    val activeAuthProfileIds by AuthInProgressBus.activeProfileIds.collectAsState()
    val expirations by ExpirationTracker.expirations.collectAsState()
    val closeButtonQuits by prefs.closeButtonQuitsAsFlow.collectAsState(initial = false)

    // Hide-to-tray state: when the user closes the window with the default
    // setting, the window becomes invisible but the process + tray stay
    // alive. Clicking the tray (or "Show window" from its menu) flips this
    // back to true. The Window composable is not decomposed when hidden, so
    // size/position/scroll state are preserved across hide/show cycles.
    var windowVisible by remember { mutableStateOf(true) }
    val showWindow: () -> Unit = remember {
        {
            // Flip the Dock icon back BEFORE re-showing the window so macOS
            // routes focus correctly when the Window becomes visible.
            DockIconVisibility.setHidden(false)
            windowVisible = true
            windowState.isMinimized = false
        }
    }
    val requestClose: () -> Unit = remember(closeButtonQuits) {
        {
            if (closeButtonQuits) {
                exitApplication()
            } else {
                Logger.i { "close request → hiding window to tray" }
                windowVisible = false
                // Hide from Dock too — the tray icon is still present, so
                // the app becomes a pure menu-bar-only utility until the
                // user brings the window back.
                DockIconVisibility.setHidden(true)
            }
        }
    }

    // Password-dialog state lives up here (above every LaunchedEffect) so the
    // startup auto-auth block below can set `dialogKeychainPrimed = true`
    // before publishing to AuthRequestBus — the pendingId LaunchedEffect needs
    // to see the primed flag when it reacts to the new pending profile.
    val pendingId by AuthRequestBus.pending.collectAsState()
    var authenticating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showPasswordDialogFor by remember { mutableStateOf<String?>(null) }
    // Driven from outside the silent-auth flow (by auto-auth-at-start) when we
    // want the dialog to open with the field grayed out and a "Use saved
    // password" button. Flipped back to false as soon as a cache-backed auth
    // actually fails so the next render shows an enabled field.
    var dialogKeychainPrimed by remember { mutableStateOf(false) }

    // Background auto-refresh: the scheduler re-reads the profiles list on
    // every tick via `profilesProvider`, so adding/removing profiles at
    // runtime doesn't need extra plumbing.
    val refreshScheduler = remember {
        RefreshScheduler(
            scope = appScope,
            profilesProvider = { profiles },
        )
    }
    // Scheduler gets instantiated up front but is NOT started until the
    // Discovery dialog has been completed at least once — per the user's
    // explicit request "don't do anything until we've connected successfully
    // through the initial discover popup". `stop()` stays in the onDispose
    // so quitting while the gate is still closed is still clean.
    DisposableEffect(Unit) {
        onDispose { refreshScheduler.stop() }
    }
    LaunchedEffect(Unit) {
        StartupGate.discoveryComplete.first { it }
        Logger.i { "startup gate opened — starting RefreshScheduler" }
        refreshScheduler.start()
    }

    // Seed the expiration tracker at startup from whatever is already in
    // `~/.aws/credentials`. This gives the UI a countdown right away, even
    // for profiles that haven't been re-authenticated in this session yet.
    // Re-runs whenever the profile list changes (discovery, add, delete).
    // Gated on the startup gate so nothing reads the credentials file until
    // the user has confirmed discovery.
    LaunchedEffect(profiles) {
        StartupGate.discoveryComplete.first { it }
        val reader = CredentialsFileReader()
        profiles.forEach { profile ->
            val creds = reader.read(profile.name) ?: return@forEach
            runCatching { java.time.Instant.parse(creds.expirationIso) }
                .onSuccess { ExpirationTracker.record(profile.id, it.toEpochMilli()) }
        }
    }

    // Drive the file-logger's enabled flag off the persisted pref. Flipping
    // the setting dialog toggle updates `LoggingSetup.enabled` instantly, so
    // the next log line either starts or stops being mirrored to disk.
    val fileLoggingEnabled by prefs.fileLoggingEnabledAsFlow.collectAsState(initial = false)
    LaunchedEffect(fileLoggingEnabled) {
        LoggingSetup.enabled.set(fileLoggingEnabled)
        Logger.i { "file logging ${if (fileLoggingEnabled) "enabled" else "disabled"}" }
    }

    // Single startup sequence: prime the in-memory PasswordCache from the OS
    // keychain first, THEN eagerly re-auth the default + every auto-refresh
    // profile with whatever's now cached. Gated on
    // `StartupGate.discoveryComplete` so NOTHING automatic runs — not the
    // keychain primer, not the startup refresh — until the user has
    // explicitly completed the initial Discovery dialog.
    LaunchedEffect(Unit) {
        StartupGate.discoveryComplete.first { it }
        Logger.i { "startup gate opened — running primer + auto-auth-at-start" }
        // --- PART 1: keychain → PasswordCache primer ---
        val persist = prefs.getPersistPasswordInKeychain()
        Logger.i { "startup keychain load: pref=$persist" }
        if (persist) {
            val (host, user) = prefs.getLastDiscoveryIdentity()
            Logger.i { "startup keychain load: identity host='$host' user='$user'" }
            if (host.isNotBlank() && user.isNotBlank()) {
                val stored = loadPersistedPasswordForCurrentIdentity()
                Logger.i {
                    "startup keychain load: retrieved password = " +
                        if (stored == null) "<null>" else "<${stored.length} chars>"
                }
                if (stored != null) {
                    PasswordCache.put(host, user, stored)
                    Logger.i { "primed password cache from keychain for $user@$host" }
                }
            }
        }

        // --- PART 2: startup refresh ---
        // Runs *after* the primer so the cache lookups below see the
        // freshly-primed password, if any. Eagerly re-auth the default
        // profile + every auto-refresh profile so the user starts with
        // maximum-lifetime STS credentials, regardless of whether the
        // on-disk creds are still technically valid.
        val autoAuth = prefs.getAutoAuthDefaultOnStart()
        if (!autoAuth) return@LaunchedEffect
        val defaultId = prefs.getDefaultProfileId() ?: return@LaunchedEffect
        val allProfiles = prefs.getProfiles()
        val defaultProfile = allProfiles.firstOrNull { it.id == defaultId } ?: return@LaunchedEffect

        // Seed the scheduler with the default profile's on-disk expiration
        // so that even if the silent re-auth below fails, the background
        // refresh loop still knows when to retry.
        CredentialsFileReader().read(defaultProfile.name)?.let { onDisk ->
            refreshScheduler.recordExpiration(defaultProfile.id, onDisk.expirationIso)
        }

        // Background pass for non-default auto-refresh profiles. Each uses
        // its own (host, user) cache entry; a profile whose password isn't
        // cached is silently skipped (same policy as RefreshScheduler).
        // Kept separate from the AuthRequestBus path below so a rejected
        // cached password here never opens a dialog — only the default
        // profile's failure escalates to the UI.
        appScope.launch {
            val toRefresh = allProfiles.filter { it.refreshEnabled && it.id != defaultId }
            for (profile in toRefresh) {
                val pwd = PasswordCache.get(profile.adfsHost, profile.username)
                if (pwd == null) {
                    Logger.d { "startup refresh skip ${profile.name}: no cached password" }
                    continue
                }
                Logger.i { "startup refresh: ${profile.name}" }
                val res = withAuthInProgress(profile.id) {
                    AdfsAuthenticator().authenticate(profile, pwd)
                }
                res.onSuccess { creds ->
                    writeCredsAndMaybeDefault(profile, creds)
                    refreshScheduler.recordExpiration(profile.id, creds.expirationIso)
                    Logger.i { "startup refresh ok: ${profile.name} (expires ${creds.expirationIso})" }
                }.onFailure { err ->
                    Logger.w(err) { "startup refresh failed: ${profile.name}" }
                }
            }
        }

        Logger.i { "auto-auth: requesting auth for ${defaultProfile.name}" }
        AuthRequestBus.request(defaultProfile.id)
    }

    val tray = remember {
        AwsTokenTray(
            onShowWindow = showWindow,
            onQuit = ::exitApplication,
            onToggleRefresh = { id, enabled ->
                appScope.launch { prefs.setProfileRefreshEnabled(id, enabled) }
            },
            onSelectRegion = { id, region ->
                appScope.launch { prefs.setProfileRegion(id, region.code) }
            },
            onSetDefault = { id ->
                appScope.launch {
                    prefs.setDefaultProfileId(id)
                    // Mirror the new default's on-disk creds into [default]
                    // right away if they're still valid — avoids making the
                    // user re-authenticate just to flip which role the
                    // plain `aws` command targets.
                    profiles.firstOrNull { it.id == id }?.let { target ->
                        runCatching { mirrorDefaultFromDisk(target) }
                    }
                }
            },
            onAuthenticate = { id -> AuthRequestBus.request(id) },
        )
    }

    DisposableEffect(Unit) {
        tray.install()
        onDispose { tray.remove() }
    }

    // Re-render the tray whenever any of its inputs change. The expirations
    // map changes on re-auth (and once per minute via a ticker we start
    // below) so the tooltip's soonest-expiry line stays fresh.
    LaunchedEffect(profiles, defaultId, accountAliases, activeAuthProfileIds, expirations) {
        tray.updateProfiles(profiles, defaultId, accountAliases, activeAuthProfileIds, expirations)
    }

    // 60 s ticker that just bumps a dummy state so any derived computations
    // (tray tooltip ETA, remaining-time labels) recompose. Cheaper than
    // rebuilding the whole tray every second; a ~60 s granularity on the
    // tray label is plenty for a 1-hour STS session.
    var trayTick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            trayTick = System.currentTimeMillis()
        }
    }
    LaunchedEffect(trayTick) {
        if (trayTick > 0L) {
            tray.updateProfiles(profiles, defaultId, accountAliases, activeAuthProfileIds, expirations)
        }
    }

    val pendingProfile = remember(pendingId, profiles) { profiles.firstOrNull { it.id == pendingId } }

    LaunchedEffect(pendingId) {
        errorMessage = null
        showPasswordDialogFor = null
        val target = pendingProfile ?: run {
            dialogKeychainPrimed = false
            return@LaunchedEffect
        }

        // All triggers (auto-auth-at-start, profile click, tray) arrive
        // here: try silent auth with whatever's cached. Only fall back to
        // the dialog if the cache is empty or the cached password is
        // rejected — auto-auth must not interrupt the user with a popup
        // when a working password is already on hand.
        val cached = PasswordCache.get(target.adfsHost, target.username)
        if (cached == null) {
            Logger.i { "no cached password for ${target.username}@${target.adfsHost}, prompting via dialog" }
            showPasswordDialogFor = target.id
            return@LaunchedEffect
        }

        Logger.i { "silently authenticating ${target.name} with cached password" }
        authenticating = true
        val result = withAuthInProgress(target.id) {
            AdfsAuthenticator().authenticate(target, cached)
        }
        authenticating = false
        result.onSuccess { creds ->
            onAuthSuccess(
                profile = target,
                password = cached,
                creds = creds,
                scheduler = refreshScheduler,
                currentAliases = accountAliases,
            )
            AuthRequestBus.clear()
        }.onFailure { err ->
            Logger.w(err) { "silent auth failed for ${target.name}, falling back to password dialog" }
            // Cached password is known-bad — drop it so the next attempt
            // isn't going to hit the exact same rejection.
            PasswordCache.remove(target.adfsHost, target.username)
            errorMessage = err.message ?: "Saved password rejected — please enter it again."
            showPasswordDialogFor = target.id
            dialogKeychainPrimed = false
        }
    }

    if (pendingProfile != null && showPasswordDialogFor == pendingProfile.id) {
        PasswordDialog(
            profile = pendingProfile,
            authenticating = authenticating,
            errorMessage = errorMessage,
            keychainPrimed = dialogKeychainPrimed,
            onCancel = {
                if (!authenticating) {
                    AuthRequestBus.clear()
                    errorMessage = null
                    dialogKeychainPrimed = false
                }
            },
            onSubmit = { typedPassword ->
                // `typedPassword == null` is the dialog's signal that the
                // grayed-out field was submitted → use the PasswordCache
                // entry (which was primed from the Keychain at startup).
                val usingCache = typedPassword == null
                val effective = typedPassword
                    ?: PasswordCache.get(pendingProfile.adfsHost, pendingProfile.username)
                if (effective == null) {
                    errorMessage = "No password available — please enter one."
                    dialogKeychainPrimed = false
                    return@PasswordDialog
                }
                authenticating = true
                errorMessage = null
                appScope.launch {
                    val result = withAuthInProgress(pendingProfile.id) {
                        AdfsAuthenticator().authenticate(pendingProfile, effective)
                    }
                    result.onSuccess { creds ->
                        onAuthSuccess(
                            profile = pendingProfile,
                            password = effective,
                            creds = creds,
                            scheduler = refreshScheduler,
                            currentAliases = accountAliases,
                        )
                        authenticating = false
                        dialogKeychainPrimed = false
                        AuthRequestBus.clear()
                    }.onFailure { err ->
                        Logger.w(err) { "Auth failed for ${pendingProfile.name} (usedCache=$usingCache)" }
                        authenticating = false
                        errorMessage = err.message ?: "Authentication failed"
                        if (usingCache) {
                            // Saved password rejected — purge it so the next
                            // submit path doesn't silently reuse the same
                            // broken value, and flip the prop so the dialog
                            // re-renders with an enabled field.
                            PasswordCache.remove(pendingProfile.adfsHost, pendingProfile.username)
                            dialogKeychainPrimed = false
                            errorMessage = "Saved password rejected — please enter it again."
                        }
                    }
                }
            },
        )
    }

    val windowTitle = "AWS Token"

    Window(
        onCloseRequest = requestClose,
        state = windowState,
        title = windowTitle,
        undecorated = true,
        visible = windowVisible,
    ) {
        // Build the per-window chrome inside the Window's FrameWindowScope
        // so the `dragArea` lambda closes over the scope. The chrome is then
        // published via CompositionLocal so ProfileListScreen can inject
        // traffic lights (macOS) / window control buttons (Windows/Linux)
        // directly into its TopAppBar — no more stacked title bar row.
        // The red close traffic light / window-close button goes through
        // `requestClose`, which respects the "close button quits" setting.
        val desktopChrome = rememberDesktopWindowChrome(
            windowState = windowState,
            onClose = requestClose,
        )
        CompositionLocalProvider(LocalDesktopWindowChrome provides desktopChrome) {
            App()
        }
    }
}
