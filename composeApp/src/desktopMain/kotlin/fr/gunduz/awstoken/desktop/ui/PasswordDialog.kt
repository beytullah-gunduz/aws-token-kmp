package fr.gunduz.awstoken.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import fr.gunduz.awstoken.auth.clearPersistedPasswordForCurrentIdentity
import fr.gunduz.awstoken.auth.isSecureCredentialStoreAvailable
import fr.gunduz.awstoken.model.AwsProfile
import fr.gunduz.awstoken.repository.PreferenceRepository
import kotlinx.coroutines.launch

/**
 * Modal password prompt. Lives in its own OS-level window (`DialogWindow`)
 * because when the user clicks "Authenticate" from the tray the main window
 * is often minimized or hidden — an in-composition `AlertDialog` inside that
 * window would be invisible.
 *
 * Three interaction modes, all driven by `keychainPrimed` (caller-controlled,
 * true when `PasswordCache` currently holds a password for this identity AND
 * the user opted into Keychain persistence):
 *
 *  1. **`keychainPrimed = true` + `rememberPassword` checkbox on + nothing
 *     typed yet** — the field is grayed out, filled with a placeholder, and a
 *     one-line explainer says "Using the password saved in your Keychain".
 *     Clicking Authenticate passes `null` to `onSubmit`, signalling the
 *     caller to use `PasswordCache`.
 *  2. **User unchecks Remember** — field enables, placeholder clears, user
 *     types a fresh password. Submit passes the typed value.
 *  3. **User starts typing with Remember still checked** — same as (2);
 *     typing anything releases the keychain path. Submit passes the typed
 *     value, and because `rememberPassword` is still true the caller will
 *     overwrite the Keychain entry with the new value on success.
 *
 * When auth fails with the keychain password, the caller flips
 * `keychainPrimed` back to false (and clears `PasswordCache`) so the
 * dialog — still open — re-renders with an enabled field and an error
 * message, prompting the user to type a new password.
 */
@Composable
fun PasswordDialog(
    profile: AwsProfile,
    authenticating: Boolean,
    errorMessage: String?,
    keychainPrimed: Boolean,
    onSubmit: (password: String?) -> Unit,
    onCancel: () -> Unit,
) {
    val dialogState = rememberDialogState(size = DpSize(460.dp, 360.dp))
    var typedPassword by remember { mutableStateOf("") }
    // Initialize to `keychainPrimed` so the very first composition shows the
    // grayed field without a flicker — we know Keychain persistence must be
    // on for keychainPrimed to be true, so "Remember" starts checked here.
    // The LaunchedEffect below confirms against the persisted pref.
    var rememberPassword by remember { mutableStateOf(keychainPrimed) }
    val keychainAvailable = remember { isSecureCredentialStoreAvailable() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        rememberPassword = PreferenceRepository.instance.getPersistPasswordInKeychain() || keychainPrimed
    }

    // Is a saved password offered for this profile? True only while the user
    // hasn't overridden it by typing. Note that the field is ALWAYS editable;
    // `usingKeychain` simply controls the placeholder / button label and
    // tells the submit path whether to pass `null` (reuse cache) or the
    // typed value. Typing even one character flips it off via
    // `typedPassword.isEmpty()` — no explicit mode switch required.
    val savedPasswordAvailable = keychainPrimed && rememberPassword
    val usingKeychain = savedPasswordAvailable && typedPassword.isEmpty()
    val canSubmit = (usingKeychain || typedPassword.isNotEmpty()) && !authenticating

    DialogWindow(
        onCloseRequest = onCancel,
        state = dialogState,
        title = "Authenticate ${profile.name}",
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Enter password for ${profile.username} @ ${profile.adfsHost}",
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (savedPasswordAvailable) {
                    Text(
                        text = if (typedPassword.isEmpty()) {
                            "A saved password is available. Click Authenticate to use it, or start typing to override."
                        } else {
                            "Overriding the saved password. Keep \"Remember password\" checked to replace the saved value on success."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = typedPassword,
                    onValueChange = { typedPassword = it },
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
                    enabled = !authenticating,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (canSubmit) {
                                submit(
                                    scope = scope,
                                    keychainAvailable = keychainAvailable,
                                    rememberPassword = rememberPassword,
                                    usingKeychain = usingKeychain,
                                    typedPassword = typedPassword,
                                    onSubmit = onSubmit,
                                )
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (keychainAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = rememberPassword,
                            enabled = !authenticating,
                            onCheckedChange = { checked ->
                                rememberPassword = checked
                                scope.launch {
                                    PreferenceRepository.instance.setPersistPasswordInKeychain(checked)
                                    if (!checked) {
                                        clearPersistedPasswordForCurrentIdentity()
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

                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (authenticating) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    TextButton(onClick = onCancel, enabled = !authenticating) { Text("Cancel") }
                    Button(
                        onClick = {
                            submit(
                                scope = scope,
                                keychainAvailable = keychainAvailable,
                                rememberPassword = rememberPassword,
                                usingKeychain = usingKeychain,
                                typedPassword = typedPassword,
                                onSubmit = onSubmit,
                            )
                        },
                        enabled = canSubmit,
                    ) { Text(if (usingKeychain) "Use saved password" else "Authenticate") }
                }
            }
        }
    }
}

/**
 * Centralised submit helper: commits the Remember-password pref inside the
 * dialog's own coroutine (so `onAuthSuccess` reads the final value with no
 * race against a separate `onCheckedChange` launch), then hands off to the
 * external callback with either the typed password or `null` meaning
 * "use the cached password".
 */
private fun submit(
    scope: kotlinx.coroutines.CoroutineScope,
    keychainAvailable: Boolean,
    rememberPassword: Boolean,
    usingKeychain: Boolean,
    typedPassword: String,
    onSubmit: (password: String?) -> Unit,
) {
    scope.launch {
        if (keychainAvailable) {
            PreferenceRepository.instance.setPersistPasswordInKeychain(rememberPassword)
        }
        onSubmit(if (usingKeychain) null else typedPassword)
    }
}
