package fr.gunduz.awstoken.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.gunduz.awstoken.model.AwsProfile
import fr.gunduz.awstoken.model.AwsRegion

/**
 * Simple add-profile modal. Keeps all form state in `remember` — there's no
 * VM for this because the dialog is a one-shot write; on save we just call
 * `PreferenceRepository.instance.upsertProfile` from the caller. No debounced
 * flow needed (this isn't an edit form — see the skill's note on when to use
 * `combine + debounce` vs one-shot writes).
 */
@Composable
fun AddProfileDialog(
    onDismiss: () -> Unit,
    onSave: (AwsProfile) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var adfsHost by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var principalArn by remember { mutableStateOf("") }
    var roleArn by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(AwsRegion.DEFAULT) }
    var regionMenuExpanded by remember { mutableStateOf(false) }

    val canSave = name.isNotBlank() &&
        adfsHost.isNotBlank() &&
        username.isNotBlank() &&
        principalArn.isNotBlank() &&
        roleArn.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add AWS profile") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile name (aws --profile ...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = adfsHost,
                    onValueChange = { adfsHost = it },
                    label = { Text("ADFS host (e.g. sts.example.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username (DOMAIN\\user or user@domain)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = principalArn,
                    onValueChange = { principalArn = it },
                    label = { Text("Principal ARN (arn:aws:iam::…:saml-provider/…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = roleArn,
                    onValueChange = { roleArn = it },
                    label = { Text("Role ARN (arn:aws:iam::…:role/…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Default region", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(
                        onClick = { regionMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${region.code} — ${region.displayName}")
                    }
                    DropdownMenu(
                        expanded = regionMenuExpanded,
                        onDismissRequest = { regionMenuExpanded = false },
                    ) {
                        AwsRegion.entries.forEach { r ->
                            DropdownMenuItem(
                                text = { Text("${r.code} — ${r.displayName}") },
                                onClick = {
                                    region = r
                                    regionMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        AwsProfile(
                            id = generateProfileId(),
                            name = name.trim(),
                            adfsHost = adfsHost.trim(),
                            username = username.trim(),
                            principalArn = principalArn.trim(),
                            roleArn = roleArn.trim(),
                            region = region.code,
                            refreshEnabled = false,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun generateProfileId(): String = "p-" + kotlin.random.Random.nextLong().toString(36).removePrefix("-")
