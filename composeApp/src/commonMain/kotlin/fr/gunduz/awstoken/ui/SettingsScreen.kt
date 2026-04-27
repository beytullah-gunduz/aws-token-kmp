package fr.gunduz.awstoken.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import fr.gunduz.awstoken.ui.icons.AppIcons

/**
 * Full-screen settings route. Back arrow in the TopAppBar pops the Nav3
 * back stack via [onBack]. On desktop, the TopAppBar still carries the
 * traffic-light / window-control chrome so the undecorated window stays
 * movable + closable from this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { WindowChromeLeading() },
                title = {
                    TopBarDragArea {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    AppIcons.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                            Text("Settings")
                        }
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
                .padding(16.dp),
        ) {
            SettingsContent()
        }
    }
}
