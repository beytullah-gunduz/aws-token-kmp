package fr.gunduz.awstoken.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.gunduz.awstoken.auth.AuthInProgressBus
import fr.gunduz.awstoken.auth.AuthRequestBus
import fr.gunduz.awstoken.auth.ExpirationTracker
import fr.gunduz.awstoken.auth.clearPersistedPasswordForCurrentIdentity
import fr.gunduz.awstoken.auth.isSecureCredentialStoreAvailable
import fr.gunduz.awstoken.auth.loadPersistedPasswordForCurrentIdentity
import fr.gunduz.awstoken.auth.readCachedSessionPassword
import fr.gunduz.awstoken.aws.refreshAllAccountAliases
import fr.gunduz.awstoken.console.openConsoleWithReauth
import fr.gunduz.awstoken.repository.PreferenceRepository
import fr.gunduz.awstoken.ui.chrome.TopBarDragArea
import fr.gunduz.awstoken.ui.chrome.WindowChromeLeading
import fr.gunduz.awstoken.ui.chrome.WindowChromeTrailing
import fr.gunduz.awstoken.ui.icons.AppIcons
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    onOpenSettings: () -> Unit,
    onReDiscover: () -> Unit,
) {
    val prefs = PreferenceRepository.instance
    val profiles by prefs.profilesAsFlow.collectAsState(initial = emptyList())
    val defaultId by prefs.defaultProfileIdAsFlow.collectAsState(initial = null)
    val accountAliases by prefs.accountAliasesAsFlow.collectAsState(initial = emptyMap())
    val activeAuthProfileIds by AuthInProgressBus.activeProfileIds.collectAsState()
    val expirations by ExpirationTracker.expirations.collectAsState()

    // 1-second ticker that forces the "expires in Xm Ys" labels to recompose.
    // `nowMillis` is read by each ProfileCard below so the countdown text
    // refreshes every second.
    var nowMillis by remember { mutableStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            nowMillis = kotlin.time.Clock.System.now().toEpochMilliseconds()
        }
    }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }
    var renamingAccountId by remember { mutableStateOf<String?>(null) }
    var refreshingAliases by remember { mutableStateOf(false) }
    var aliasPasswordPrompt by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                // macOS traffic lights (or a spacer on Windows/Linux) get
                // injected here via the LocalDesktopWindowChrome composition
                // local, so the window controls live INSIDE the TopAppBar
                // row — no more separate 34 dp title bar stacked above.
                navigationIcon = { WindowChromeLeading() },
                // Wrap the title in the desktop drag region so clicking +
                // dragging anywhere in the TopAppBar title area moves the
                // window. On Android this is a no-op wrapper.
                title = {
                    TopBarDragArea {
                        Text("AWS profiles")
                    }
                },
                actions = {
                    IconButton(
                        enabled = !refreshingAliases,
                        onClick = {
                            refreshingAliases = true
                            scope.launch {
                                val result = runCatching { refreshAllAccountAliases() }
                                refreshingAliases = false
                                val r = result.getOrNull()
                                if (r != null && r.needsPassword) {
                                    aliasPasswordPrompt = true
                                } else {
                                    snackbarHostState.showSnackbar(aliasResultMessage(result))
                                }
                            }
                        },
                    ) {
                        Icon(AppIcons.Refresh, contentDescription = "Refresh account aliases")
                    }
                    IconButton(onClick = onReDiscover) {
                        Icon(AppIcons.Download, contentDescription = "Discover profiles")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(AppIcons.Settings, contentDescription = "Settings")
                    }
                    // Windows/Linux: the min/max/close IconButtons hook into
                    // the same row. On macOS this is a no-op (controls are
                    // on the left via WindowChromeLeading above).
                    WindowChromeTrailing()
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(AppIcons.Add, contentDescription = "Add profile")
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).padding(16.dp)) {
            if (profiles.isEmpty()) {
                Text(
                    "No profiles yet. Click the download icon to discover them from ADFS, or + to add manually.",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                // Group by account id. Each account renders as a single
                // LazyColumn item: a compact header row followed by one
                // shared Card containing all of its role rows separated by
                // hairline dividers. Collapsing the per-role cards into one
                // surface per account cuts the visual nesting the old
                // design had and buys back a lot of vertical space.
                val groups = remember(profiles) { profiles.groupBy { it.accountId }.toSortedMap() }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    // Reserve space at the bottom equal to the FAB's height
                    // (56 dp) + its 16 dp margin + a small breathing gap, so
                    // the last profile card never slips under the FAB.
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    groups.forEach { (accountId, accountProfiles) ->
                        item(key = "group-$accountId") {
                            AccountGroup(
                                accountId = accountId,
                                alias = accountAliases[accountId],
                                profiles = accountProfiles,
                                defaultId = defaultId,
                                activeAuthProfileIds = activeAuthProfileIds,
                                expirations = expirations,
                                nowMillis = nowMillis,
                                onRename = { renamingAccountId = accountId },
                                onProfileClick = { p -> AuthRequestBus.request(p.id) },
                                onProfileDelete = { p -> scope.launch { prefs.removeProfile(p.id) } },
                                onProfileSetDefault = { p ->
                                    scope.launch {
                                        // Toggle: clicking the filled star
                                        // on the current default clears it,
                                        // clicking any empty star promotes.
                                        val next = if (p.id == defaultId) null else p.id
                                        prefs.setDefaultProfileId(next)
                                    }
                                },
                                onProfileChangeRegion = { p, r ->
                                    scope.launch { prefs.setProfileRegion(p.id, r.code) }
                                },
                                onProfileToggleRefresh = { p, enabled ->
                                    scope.launch { prefs.setProfileRefreshEnabled(p.id, enabled) }
                                },
                                onProfileOpenConsole = { p ->
                                    scope.launch {
                                        openConsoleWithReauth(p) { msg ->
                                            snackbarHostState.showSnackbar(msg)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddProfileDialog(
            onDismiss = { showAddDialog = false },
            onSave = { profile ->
                scope.launch { prefs.upsertProfile(profile) }
                showAddDialog = false
            },
        )
    }

    if (aliasPasswordPrompt) {
        AliasRefreshPasswordDialog(
            onDismiss = { aliasPasswordPrompt = false },
            onSubmit = { pwd ->
                aliasPasswordPrompt = false
                refreshingAliases = true
                scope.launch {
                    val result = runCatching { refreshAllAccountAliases(explicitPassword = pwd) }
                    refreshingAliases = false
                    snackbarHostState.showSnackbar(aliasResultMessage(result))
                }
            },
        )
    }

    renamingAccountId?.let { accountId ->
        RenameAccountDialog(
            accountId = accountId,
            currentAlias = accountAliases[accountId] ?: "",
            onDismiss = { renamingAccountId = null },
            onSave = { newAlias ->
                scope.launch { prefs.setAccountAlias(accountId, newAlias) }
                renamingAccountId = null
            },
        )
    }
}

private fun aliasResultMessage(result: Result<fr.gunduz.awstoken.aws.AliasRefreshResult>): String = result.fold(
    onSuccess = { r ->
        buildString {
            when {
                r.skippedReason != null -> append("Alias refresh skipped — ").append(r.skippedReason)
                r.aliasesFetched == 0 -> append("Alias refresh — AWS returned no aliases")
                else -> append("Alias refresh — ").append(r.aliasesFetched).append(" fetched, ").append(r.aliasesChanged).append(" updated")
            }
            if (r.defaultProfileRefreshed) append(" · default profile refreshed")
        }
    },
    onFailure = { "Alias refresh failed: ${it.message}" },
)

@Composable
private fun AliasRefreshPasswordDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(false) }
    var keychainPasswordForSavedIdentity by remember { mutableStateOf<String?>(null) }
    val keychainAvailable = remember { isSecureCredentialStoreAvailable() }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val prefs = PreferenceRepository.instance
        rememberPassword = prefs.getPersistPasswordInKeychain()
        val (host, user) = prefs.getLastDiscoveryIdentity()
        // Read directly from the Keychain (with a cache-first fast path) to
        // sidestep the PasswordCache primer timing race that made the saved
        // shortcut lose against the startup coroutine.
        keychainPasswordForSavedIdentity = if (rememberPassword && host.isNotBlank() && user.isNotBlank()) {
            readCachedSessionPassword(host, user) ?: loadPersistedPasswordForCurrentIdentity()
        } else {
            null
        }
    }
    val savedPasswordAvailable = keychainPasswordForSavedIdentity != null && rememberPassword
    val usingKeychain = savedPasswordAvailable && password.isEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ADFS password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter your ADFS password so the app can run one SAML exchange and pull every account alias at once.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (savedPasswordAvailable) {
                    Text(
                        text = if (password.isEmpty()) {
                            "A saved password is available. Click Refresh to use it, or start typing to override."
                        } else {
                            "Overriding the saved password. Keep \"Remember password\" checked to replace the saved value on success."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    placeholder = if (savedPasswordAvailable) {
                        {
                            Text(
                                "Using saved password — type to override",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (keychainAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = rememberPassword,
                            onCheckedChange = { checked ->
                                rememberPassword = checked
                                scope.launch {
                                    PreferenceRepository.instance.setPersistPasswordInKeychain(checked)
                                    if (!checked) {
                                        clearPersistedPasswordForCurrentIdentity()
                                        keychainPasswordForSavedIdentity = null
                                    }
                                }
                            },
                        )
                        Text(
                            text = "Remember password in macOS Keychain",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = usingKeychain || password.isNotEmpty(),
                onClick = {
                    scope.launch {
                        if (keychainAvailable) {
                            PreferenceRepository.instance.setPersistPasswordInKeychain(rememberPassword)
                        }
                        val effective = if (usingKeychain) {
                            keychainPasswordForSavedIdentity ?: ""
                        } else {
                            password
                        }
                        if (effective.isNotEmpty()) onSubmit(effective)
                    }
                },
            ) { Text(if (usingKeychain) "Refresh (using saved password)" else "Refresh") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AccountGroup(
    accountId: String,
    alias: String?,
    profiles: List<fr.gunduz.awstoken.model.AwsProfile>,
    defaultId: String?,
    activeAuthProfileIds: Set<String>,
    expirations: Map<String, Long>,
    nowMillis: Long,
    onRename: () -> Unit,
    onProfileClick: (fr.gunduz.awstoken.model.AwsProfile) -> Unit,
    onProfileDelete: (fr.gunduz.awstoken.model.AwsProfile) -> Unit,
    onProfileSetDefault: (fr.gunduz.awstoken.model.AwsProfile) -> Unit,
    onProfileChangeRegion: (fr.gunduz.awstoken.model.AwsProfile, fr.gunduz.awstoken.model.AwsRegion) -> Unit,
    onProfileToggleRefresh: (fr.gunduz.awstoken.model.AwsProfile, Boolean) -> Unit,
    onProfileOpenConsole: (fr.gunduz.awstoken.model.AwsProfile) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AccountHeader(
            accountId = accountId,
            alias = alias,
            isRefreshing = profiles.any { it.id in activeAuthProfileIds },
            onRename = onRename,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            // `surfaceContainerLow` is one tonal tier up from the window's
            // `surface` background, so the card edge is visible in both
            // dark and light mode without needing a border stroke.
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            profiles.forEachIndexed { idx, profile ->
                if (idx > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 15.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
                ProfileRow(
                    profile = profile,
                    isDefault = profile.id == defaultId,
                    isRefreshing = profile.id in activeAuthProfileIds,
                    expirationMillis = expirations[profile.id],
                    nowMillis = nowMillis,
                    onClick = { onProfileClick(profile) },
                    onDelete = { onProfileDelete(profile) },
                    onSetDefault = { onProfileSetDefault(profile) },
                    onChangeRegion = { r -> onProfileChangeRegion(profile, r) },
                    onToggleRefresh = { enabled -> onProfileToggleRefresh(profile, enabled) },
                    onOpenConsole = { onProfileOpenConsole(profile) },
                )
            }
        }
    }
}

@Composable
private fun AccountHeader(
    accountId: String,
    alias: String?,
    isRefreshing: Boolean,
    onRename: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 2.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
            )
        }
        Text(
            text = alias ?: "Account",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = accountId,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onRename,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                AppIcons.Edit,
                contentDescription = "Rename account",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: fr.gunduz.awstoken.model.AwsProfile,
    isDefault: Boolean,
    isRefreshing: Boolean,
    expirationMillis: Long?,
    nowMillis: Long,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onChangeRegion: (fr.gunduz.awstoken.model.AwsRegion) -> Unit,
    onToggleRefresh: (Boolean) -> Unit,
    onOpenConsole: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val elevation by animateDpAsState(
        targetValue = if (expanded) 4.dp else 0.dp,
        label = "rowElevation",
    )
    val verticalPad by animateDpAsState(
        targetValue = if (expanded) 12.dp else 0.dp,
        label = "rowVPad",
    )
    // Collapsed rows match the parent Card (`surfaceContainerLow`) so
    // they vanish into it; expanded rows animate up to `surfaceContainerHigh`
    // — two tonal tiers brighter in dark mode — so the edit panel
    // visibly stands out. Combined with the animated shadowElevation,
    // the row reads as "lifted" on both light and dark themes.
    val rowColor by animateColorAsState(
        targetValue = if (expanded) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "rowColor",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = rowColor,
        shadowElevation = elevation,
    ) {
        Column(modifier = Modifier.padding(vertical = verticalPad)) {
            // --- Compact row (existing layout + pen IconButton) -----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clickable(enabled = !isRefreshing, onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left-edge status stripe. One pixel of color carries what
                // used to be the "expired" / "refresh: off" label —
                // expired=red, <10 min=amber, healthy=green,
                // refreshing=primary, unknown=neutral.
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(statusColor(expirationMillis, nowMillis, isRefreshing)),
                )
                // Leading star toggles which profile is the default. Filled
                // when this row is the default, outlined otherwise. The
                // IconButton swallows its own click so the surrounding row's
                // clickable (which triggers the auth flow) does not also
                // fire.
                IconButton(
                    onClick = onSetDefault,
                    modifier = Modifier.padding(start = 6.dp).size(32.dp),
                ) {
                    Icon(
                        imageVector = if (isDefault) AppIcons.Star else AppIcons.StarBorder,
                        contentDescription = if (isDefault) "Default profile" else "Set as default",
                        modifier = Modifier.size(16.dp),
                        tint = if (isDefault) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = profile.roleName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (profile.refreshEnabled) {
                        Icon(
                            AppIcons.Refresh,
                            contentDescription = "Auto-refresh enabled",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Meta column: fixed width, start-aligned. Anchoring
                    // the region code at a constant x keeps every row's
                    // `eu-west-1` label in the same column regardless of
                    // the roleName's length or the countdown suffix
                    // ("2h 15m" vs "expired" vs nothing).
                    Text(
                        text = buildString {
                            append(profile.region)
                            val freshness = if (isRefreshing) "refreshing…" else formatShortFreshness(expirationMillis, nowMillis)
                            if (freshness != null) {
                                append(" · ")
                                append(freshness)
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.width(128.dp),
                    )
                }
                IconButton(
                    onClick = onOpenConsole,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        AppIcons.OpenInNew,
                        contentDescription = "Open AWS Console for this profile",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        AppIcons.Edit,
                        contentDescription = if (expanded) "Close edit" else "Edit profile",
                        modifier = Modifier.size(14.dp),
                        tint = if (expanded) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        AppIcons.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            // --- Expanded editor panel -----------------------------
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                ProfileEditorPanel(
                    profile = profile,
                    isDefault = isDefault,
                    onSetDefault = onSetDefault,
                    onChangeRegion = onChangeRegion,
                    onToggleRefresh = onToggleRefresh,
                )
            }
        }
    }
}

@Composable
private fun ProfileEditorPanel(
    profile: fr.gunduz.awstoken.model.AwsProfile,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onChangeRegion: (fr.gunduz.awstoken.model.AwsRegion) -> Unit,
    onToggleRefresh: (Boolean) -> Unit,
) {
    Column {
        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Region picker — mirrors the OutlinedButton + DropdownMenu
            // pattern from AddProfileDialog.kt so both places feel the same.
            var regionMenuExpanded by remember { mutableStateOf(false) }
            val currentRegion = remember(profile.region) {
                fr.gunduz.awstoken.model.AwsRegion.fromCode(profile.region)
            }
            Column {
                Text("Region", style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(
                        onClick = { regionMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${currentRegion.code} — ${currentRegion.displayName}")
                    }
                    DropdownMenu(
                        expanded = regionMenuExpanded,
                        onDismissRequest = { regionMenuExpanded = false },
                    ) {
                        fr.gunduz.awstoken.model.AwsRegion.entries.forEach { r ->
                            DropdownMenuItem(
                                leadingIcon = {
                                    if (r.code == profile.region) {
                                        Icon(
                                            AppIcons.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    } else {
                                        Spacer(Modifier.size(16.dp))
                                    }
                                },
                                text = { Text("${r.code} — ${r.displayName}") },
                                onClick = {
                                    regionMenuExpanded = false
                                    onChangeRegion(r)
                                },
                            )
                        }
                    }
                }
            }

            // Default-profile toggle — same onSetDefault toggle semantics
            // as the compact-row star (clicking promotes/unsets).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (isDefault) AppIcons.Star else AppIcons.StarBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isDefault) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    "Default profile",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = isDefault,
                    onCheckedChange = { onSetDefault() },
                )
            }

            // Auto-refresh toggle — bound to the profile.refreshEnabled
            // flag; the RefreshScheduler picks the updated list up on its
            // next tick, no restart needed.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    AppIcons.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Auto-refresh",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = profile.refreshEnabled,
                    onCheckedChange = onToggleRefresh,
                )
            }
        }
    }
}

@Composable
private fun statusColor(
    expirationMillis: Long?,
    nowMillis: Long,
    isRefreshing: Boolean,
): Color {
    val scheme = MaterialTheme.colorScheme
    if (isRefreshing) return scheme.primary
    if (expirationMillis == null || nowMillis == 0L) return scheme.outlineVariant
    val remaining = expirationMillis - nowMillis
    return when {
        remaining <= 0 -> scheme.error
        remaining < 10 * 60 * 1000 -> Color(0xFFF5A623)
        else -> Color(0xFF4CAF50)
    }
}

/**
 * Compact countdown for the role row's right edge: `2h 15m`, `45m`, `12s`,
 * or `expired`. Returns `null` when there is no known expiration so the
 * caller can drop the separator.
 */
private fun formatShortFreshness(expirationMillis: Long?, nowMillis: Long): String? {
    if (expirationMillis == null || nowMillis == 0L) return null
    val remaining = expirationMillis - nowMillis
    if (remaining <= 0) return "expired"
    val totalSec = remaining / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

@Composable
private fun RenameAccountDialog(
    accountId: String,
    currentAlias: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var alias by remember { mutableStateOf(currentAlias) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename account $accountId") },
        text = {
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text("Human-readable name (empty to clear)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(alias) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
