package fr.gunduz.awstoken.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import fr.gunduz.awstoken.auth.AdfsAuthenticator
import fr.gunduz.awstoken.auth.StartupGate
import fr.gunduz.awstoken.auth.clearPersistedPasswordForCurrentIdentity
import fr.gunduz.awstoken.auth.dropCachedSessionPassword
import fr.gunduz.awstoken.auth.isSecureCredentialStoreAvailable
import fr.gunduz.awstoken.auth.loadPersistedPasswordForCurrentIdentity
import fr.gunduz.awstoken.auth.readCachedSessionPassword
import fr.gunduz.awstoken.auth.rememberPasswordForSession
import fr.gunduz.awstoken.model.AwsProfile
import fr.gunduz.awstoken.model.AwsRegion
import fr.gunduz.awstoken.repository.PreferenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ADFS discovery form. Hosted by `LoginScreen` at startup and re-used when
 * the user re-runs discovery from the profile list. Stateless wrt the
 * surrounding chrome — the caller decides whether to wrap it in a Scaffold,
 * an AlertDialog, a Card, etc.
 *
 * Generated profile names follow `{accountId}-{roleName}`. Collisions with
 * pre-existing profiles are resolved with a numeric suffix so re-running
 * discovery never clobbers existing entries.
 *
 * @param onDiscovered fired once the SAML exchange succeeds and profiles
 *   have been persisted. The `createdCount` is the number of *new* profile
 *   rows added (re-discovery returning already-known roles yields 0).
 * @param onCancel optional "cancel" action. When null, the cancel button
 *   is hidden — the startup Login screen has nothing to cancel back to.
 */
@Composable
fun DiscoveryContent(
    onDiscovered: (createdCount: Int) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var adfsHost by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fetchAliasesFromAws by remember { mutableStateOf(true) }
    var rememberPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Password pulled from Keychain for the *saved* discovery identity.
    // Read directly from the OS keychain in the LaunchedEffect below so we
    // sidestep the PasswordCache primer timing race.
    var keychainPasswordForSavedIdentity by remember { mutableStateOf<String?>(null) }
    // Snapshot of the saved `(host, user)` at open. Used so the grayed-field
    // shortcut only applies while the user is looking at the same identity.
    var savedHostAtLoad by remember { mutableStateOf("") }
    var savedUserAtLoad by remember { mutableStateOf("") }
    // Tracks the password field's focus state so the "saved password" dot
    // preview can be replaced with a real empty editor once the user
    // clicks the field — the user can then type their override without
    // having to manually delete the placeholder dots first.
    var passwordFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keychainAvailable = remember { isSecureCredentialStoreAvailable() }

    LaunchedEffect(Unit) {
        val prefs = PreferenceRepository.instance
        val (savedHost, savedUser) = prefs.getLastDiscoveryIdentity()
        savedHostAtLoad = savedHost
        savedUserAtLoad = savedUser
        if (adfsHost.isEmpty()) adfsHost = savedHost
        if (username.isEmpty()) username = savedUser
        fetchAliasesFromAws = prefs.getDiscoverySyncAliases()
        rememberPassword = prefs.getPersistPasswordInKeychain()
        keychainPasswordForSavedIdentity = if (rememberPassword && savedHost.isNotBlank() && savedUser.isNotBlank()) {
            readCachedSessionPassword(savedHost, savedUser)
                ?: loadPersistedPasswordForCurrentIdentity()
        } else {
            null
        }
        Logger.i(tag = "DiscoveryContent") {
            "loaded: host='$adfsHost' user='$username' remember=$rememberPassword " +
                "keychainPasswordForSavedIdentity=${if (keychainPasswordForSavedIdentity == null) "<null>" else "<present>"}"
        }
    }

    val savedPasswordAvailable = savedHostAtLoad.isNotBlank() &&
        savedUserAtLoad.isNotBlank() &&
        adfsHost.trim() == savedHostAtLoad &&
        username.trim() == savedUserAtLoad &&
        keychainPasswordForSavedIdentity != null &&
        rememberPassword
    val usingKeychain = savedPasswordAvailable && password.isEmpty()

    val canSubmit = adfsHost.isNotBlank() &&
        username.isNotBlank() &&
        (usingKeychain || password.isNotEmpty()) &&
        !busy

    // Outer wrapper: hosts the in-flight progress bar pinned to the top of
    // the form so it stays visible regardless of scroll position. The
    // existing scrollable Column lives inside, untouched.
    Column(modifier = Modifier.fillMaxWidth()) {
        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Sign in once against ADFS and a profile is created for every role your account can assume.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = adfsHost,
                onValueChange = { adfsHost = it },
                label = { Text("ADFS host (e.g. sts.example.com)") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username (DOMAIN\\user or user@domain)") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            // When a saved password is available and the user hasn't focused the
            // field yet, render 8 literal "•" characters directly as the field's
            // text so the password box is visibly non-empty at rest — no click
            // required. We drop `PasswordVisualTransformation` in that state so
            // the dots show as-is (applying the transformation on top of dot
            // characters would render them again identically but makes the
            // intent murky). The moment the field gets focus, `passwordFocused`
            // flips and we show the real (empty) editor with password masking
            // re-enabled, ready for the user to type an override.
            val showSavedDotsPreview = savedPasswordAvailable && password.isEmpty() && !passwordFocused
            OutlinedTextField(
                value = if (showSavedDotsPreview) "••••••••" else password,
                onValueChange = { password = it },
                label = { Text("Password") },
                // Once the user clicks the field, the dot preview disappears
                // and Material3's normal placeholder kicks in (focused+empty),
                // spelling out why the field is empty and how to bypass the
                // saved value.
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
                enabled = !busy,
                visualTransformation = if (showSavedDotsPreview) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { passwordFocused = it.isFocused },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = fetchAliasesFromAws,
                    onCheckedChange = { checked ->
                        fetchAliasesFromAws = checked
                        scope.launch {
                            PreferenceRepository.instance.setDiscoverySyncAliases(checked)
                        }
                    },
                    enabled = !busy,
                )
                Text(
                    text = "Also sync account aliases from AWS",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            if (keychainAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
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
                        enabled = !busy,
                    )
                    Text(
                        text = "Remember password in macOS Keychain",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (errorMessage != null) {
                Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onCancel != null) {
                    TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
                }
                Button(
                    enabled = canSubmit,
                    onClick = {
                        busy = true
                        errorMessage = null
                        scope.launch {
                            // Persist host/username + the checkbox states up-front
                            // so a failed discovery still leaves them saved and
                            // we're not racing an in-flight checkbox scope.launch.
                            PreferenceRepository.instance.setLastDiscoveryIdentity(
                                adfsHost = adfsHost.trim(),
                                username = username.trim(),
                            )
                            PreferenceRepository.instance.setDiscoverySyncAliases(fetchAliasesFromAws)
                            if (keychainAvailable) {
                                PreferenceRepository.instance.setPersistPasswordInKeychain(rememberPassword)
                            }
                            val effectivePassword = if (usingKeychain) {
                                keychainPasswordForSavedIdentity
                            } else {
                                password
                            }
                            if (effectivePassword.isNullOrEmpty()) {
                                busy = false
                                errorMessage = "No password available — please enter one."
                                keychainPasswordForSavedIdentity = null
                                return@launch
                            }
                            val usedCachedPassword = usingKeychain
                            val result = AdfsAuthenticator().discoverRoles(
                                adfsHost = adfsHost.trim(),
                                username = username.trim(),
                                password = effectivePassword,
                                fetchAliasesFromAws = fetchAliasesFromAws,
                            )
                            result.onSuccess { discovery ->
                                if (!usedCachedPassword) {
                                    rememberPasswordForSession(effectivePassword)
                                }
                                val created = withContext(Dispatchers.IO) {
                                    createProfilesFromDiscovery(discovery)
                                }
                                StartupGate.markDiscoveryComplete()
                                // Deliberately *not* resetting `busy` here.
                                // `onDiscovered` swaps Login out of the
                                // NavDisplay backStack, so the form is about
                                // to unmount; flipping busy=false first would
                                // recompose this composable in its enabled
                                // state for one frame, which the NavDisplay
                                // cross-fade then makes visible (inputs
                                // un-gray, progress bar disappears) right
                                // before the screen swap. Keeping busy=true
                                // until unmount lets the form fade out still
                                // showing its in-flight state.
                                onDiscovered(created)
                            }.onFailure { err ->
                                busy = false
                                errorMessage = err.message ?: "Discovery failed"
                                if (usedCachedPassword) {
                                    dropCachedSessionPassword(adfsHost.trim(), username.trim())
                                    keychainPasswordForSavedIdentity = null
                                    errorMessage = "Saved password rejected — please enter it again."
                                }
                            }
                        }
                    },
                ) { Text("Connect") }
            }
        }
    }
}

private suspend fun createProfilesFromDiscovery(discovery: AdfsAuthenticator.Discovery): Int {
    val prefs = PreferenceRepository.instance

    discovery.accountAliases.forEach { (accountId, alias) ->
        prefs.setAccountAlias(accountId, alias)
    }

    val existing = prefs.getProfiles()
    val existingNames = existing.map { it.name }.toMutableSet()
    var created = 0
    discovery.roles.forEach { role ->
        val baseName = "${role.accountId}-${role.roleName}"
        val uniqueName = uniqueName(baseName, existingNames)
        existingNames += uniqueName
        val alreadyHave = existing.any {
            it.principalArn == role.principalArn && it.roleArn == role.roleArn && it.adfsHost == discovery.adfsHost
        }
        if (alreadyHave) return@forEach

        prefs.upsertProfile(
            AwsProfile(
                id = "p-" + kotlin.random.Random.nextLong().toString(36).removePrefix("-"),
                name = uniqueName,
                adfsHost = discovery.adfsHost,
                username = discovery.username,
                principalArn = role.principalArn,
                roleArn = role.roleArn,
                region = AwsRegion.DEFAULT.code,
                refreshEnabled = false,
            ),
        )
        created++
    }
    return created
}

private fun uniqueName(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var i = 2
    while ("$base-$i" in taken) i++
    return "$base-$i"
}
