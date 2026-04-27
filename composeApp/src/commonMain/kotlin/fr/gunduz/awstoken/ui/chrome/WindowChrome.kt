package fr.gunduz.awstoken.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Leading-edge window chrome content: on macOS this is the three-dot traffic
 * lights, on Windows / Linux it's nothing (the window controls live in
 * `WindowChromeTrailing` instead). On Android it's a no-op.
 */
@Composable
expect fun WindowChromeLeading(modifier: Modifier = Modifier)

/**
 * Trailing-edge window chrome content: the minimize / maximize / close
 * IconButtons on Windows / Linux; nothing on macOS (already handled by the
 * leading traffic lights). No-op on Android.
 */
@Composable
expect fun WindowChromeTrailing(modifier: Modifier = Modifier)

/**
 * Wrap a chunk of toolbar content in a draggable region. On desktop this
 * uses Compose Desktop's `WindowDraggableArea` so the user can move the
 * undecorated window by clicking+dragging the title area. Fullscreen is
 * disabled app-wide so no double-click-to-maximize handler is wired up.
 * On Android it just invokes `content()` directly.
 */
@Composable
expect fun TopBarDragArea(content: @Composable () -> Unit)
