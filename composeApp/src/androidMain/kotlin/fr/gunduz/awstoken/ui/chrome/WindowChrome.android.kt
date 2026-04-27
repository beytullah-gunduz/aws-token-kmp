package fr.gunduz.awstoken.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Android has a system status bar; no custom window chrome needed.
@Composable
actual fun WindowChromeLeading(modifier: Modifier) = Unit

@Composable
actual fun WindowChromeTrailing(modifier: Modifier) = Unit

@Composable
actual fun TopBarDragArea(content: @Composable () -> Unit) {
    content()
}
