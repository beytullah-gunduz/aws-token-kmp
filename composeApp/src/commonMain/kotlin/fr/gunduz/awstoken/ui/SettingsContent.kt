package fr.gunduz.awstoken.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.gunduz.awstoken.auth.clearPersistedPasswordForCurrentIdentity
import fr.gunduz.awstoken.auth.isSecureCredentialStoreAvailable
import fr.gunduz.awstoken.repository.PreferenceRepository
import kotlinx.coroutines.launch

@Composable
fun SettingsContent(modifier: Modifier = Modifier) {
    var adfsHost by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var profileCount by remember { mutableStateOf(0) }
    var aliasCount by remember { mutableStateOf(0) }
    var autoAuthDefaultOnStart by remember { mutableStateOf(false) }
    var persistPasswordInKeychain by remember { mutableStateOf(false) }
    var fileLoggingEnabled by remember { mutableStateOf(false) }
    var closeButtonQuits by remember { mutableStateOf(false) }
    var sessionDurationHours by remember {
        mutableStateOf(PreferenceRepository.DEFAULT_SESSION_DURATION_SECONDS / 3600f)
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val prefs = PreferenceRepository.instance
        val (host, user) = prefs.getLastDiscoveryIdentity()
        adfsHost = host
        username = user
        profileCount = prefs.getProfiles().size
        aliasCount = prefs.getAccountAliases().size
        autoAuthDefaultOnStart = prefs.getAutoAuthDefaultOnStart()
        persistPasswordInKeychain = prefs.getPersistPasswordInKeychain()
        fileLoggingEnabled = prefs.getFileLoggingEnabled()
        sessionDurationHours = prefs.getSessionDurationSeconds() / 3600f
        closeButtonQuits = prefs.getCloseButtonQuits()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // ── Account ─────────────────────────────────────────────
        SettingsSection("Account") {
            LabelledRow("ADFS host", adfsHost.ifBlank { "(not set)" })
            LabelledRow("Username", username.ifBlank { "(not set)" })
            LabelledRow("Profiles", profileCount.toString())
            LabelledRow("Account aliases", aliasCount.toString())
            Text(
                "Run a fresh discovery to change host or username.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Authentication ──────────────────────────────────────
        SettingsSection("Authentication") {
            ToggleRow(
                label = "Auto-authenticate at launch",
                description = "Opens the password dialog for the default profile on start " +
                    "if its credentials are expired or missing.",
                checked = autoAuthDefaultOnStart,
                onCheckedChange = { value ->
                    autoAuthDefaultOnStart = value
                    scope.launch {
                        PreferenceRepository.instance.setAutoAuthDefaultOnStart(value)
                    }
                },
            )

            SessionDurationRow(
                valueHours = sessionDurationHours,
                onValueChange = { sessionDurationHours = it },
                onValueCommit = { hours ->
                    val whole = hours.toInt().coerceIn(1, 8)
                    sessionDurationHours = whole.toFloat()
                    scope.launch {
                        PreferenceRepository.instance.setSessionDurationSeconds(whole * 3600)
                    }
                },
            )

            if (isSecureCredentialStoreAvailable()) {
                ToggleRow(
                    label = "Remember password",
                    description = "Stores your ADFS password in the macOS Keychain.",
                    checked = persistPasswordInKeychain,
                    onCheckedChange = { value ->
                        persistPasswordInKeychain = value
                        scope.launch {
                            PreferenceRepository.instance.setPersistPasswordInKeychain(value)
                            if (!value) {
                                clearPersistedPasswordForCurrentIdentity()
                            }
                        }
                    },
                )
            }
        }

        // ── Application ─────────────────────────────────────────
        SettingsSection("Application") {
            ToggleRow(
                label = "Close button quits app",
                description = "When off, the close button hides to the tray. " +
                    "When on, it terminates the app entirely.",
                checked = closeButtonQuits,
                onCheckedChange = { value ->
                    closeButtonQuits = value
                    scope.launch {
                        PreferenceRepository.instance.setCloseButtonQuits(value)
                    }
                },
            )

            ToggleRow(
                label = "Write logs to file",
                description = "Mirrors logs to ~/.aws-token-kmp/logs/ (5 MB cap, 1 rotation).",
                checked = fileLoggingEnabled,
                onCheckedChange = { value ->
                    fileLoggingEnabled = value
                    scope.launch {
                        PreferenceRepository.instance.setFileLoggingEnabled(value)
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun LabelledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionDurationRow(
    valueHours: Float,
    onValueChange: (Float) -> Unit,
    onValueCommit: (Float) -> Unit,
) {
    val whole = valueHours.toInt().coerceIn(1, 8)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Session duration", style = MaterialTheme.typography.bodyMedium)
            Text("${whole}h", style = MaterialTheme.typography.titleSmall)
        }
        Slider(
            value = valueHours,
            onValueChange = onValueChange,
            onValueChangeFinished = { onValueCommit(valueHours) },
            valueRange = 1f..8f,
            steps = 6,
        )
        Text(
            "Passed to AWS STS as DurationSeconds. Effective max is the smaller of " +
                "this and the role's MaxSessionDuration.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
