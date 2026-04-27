package fr.gunduz.awstoken.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.gunduz.awstoken.ui.chrome.TopBarDragArea
import fr.gunduz.awstoken.ui.chrome.WindowChromeLeading
import fr.gunduz.awstoken.ui.chrome.WindowChromeTrailing

/**
 * Full-screen discovery flow. Shown as the Nav3 start destination on every
 * app launch and pushed again from the profile list when the user wants to
 * re-discover against a different ADFS instance.
 *
 * Success semantics are delegated: the caller decides whether to replace
 * the Login entry with AccountList (initial launch) or just pop it back
 * (re-discovery from an existing AccountList).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onDiscovered: (createdCount: Int) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { WindowChromeLeading() },
                title = {
                    TopBarDragArea {
                        Text("Sign in")
                    }
                },
                actions = { WindowChromeTrailing() },
            )
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .padding(top = 8.dp),
            ) {
                DiscoveryContent(onDiscovered = onDiscovered)
            }
        }
    }
}
