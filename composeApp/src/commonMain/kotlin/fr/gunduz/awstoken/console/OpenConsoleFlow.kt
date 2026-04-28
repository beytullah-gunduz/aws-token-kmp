package fr.gunduz.awstoken.console

import fr.gunduz.awstoken.auth.AuthRequestBus
import fr.gunduz.awstoken.model.AwsProfile
import kotlinx.coroutines.flow.first

/**
 * Drives the "Open AWS Console" action with a re-authentication fallback.
 *
 * - Tries [openAwsConsoleForProfile] first against whatever's on disk.
 * - On `MissingCreds` / `ExpiredCreds`, fires `AuthRequestBus.request` and
 *   waits for the existing silent-auth path (or password dialog, depending on
 *   what main.kt elects to do) to complete, then retries once.
 * - Anything else (network failure, federation rejection, browser launch
 *   failure) is reported through [onError] — the caller plugs in a
 *   `SnackbarHostState.showSnackbar` for the in-window button or a
 *   `TrayIcon.displayMessage` for the tray submenu, so the same flow drives
 *   both entry points without dragging UI types into commonMain.
 *
 * This function intentionally never throws — every failure mode is funneled
 * through [onError] so callers can `appScope.launch { … }` it without
 * try/catch boilerplate.
 */
internal suspend fun openConsoleWithReauth(
    profile: AwsProfile,
    onError: suspend (String) -> Unit,
) {
    when (val first = openAwsConsoleForProfile(profile)) {
        OpenConsoleResult.Success -> return
        is OpenConsoleResult.Failed -> {
            onError("Couldn't open AWS Console: ${first.message}")
            return
        }
        OpenConsoleResult.MissingCreds, OpenConsoleResult.ExpiredCreds -> {
            // Need fresh creds. Trigger the existing silent-auth flow (uses
            // the cached password if available, falls back to the dialog if
            // not). Then wait for `pending` to leave our profile id —
            // main.kt clears it on success, on failure, and on dialog
            // cancel, so any of those exit conditions unblocks us correctly.
            AuthRequestBus.request(profile.id)
            AuthRequestBus.pending.first { it != profile.id }

            when (val retry = openAwsConsoleForProfile(profile)) {
                OpenConsoleResult.Success -> Unit
                is OpenConsoleResult.Failed ->
                    onError("Couldn't open AWS Console: ${retry.message}")
                else ->
                    onError("Authentication didn't complete — try again.")
            }
        }
    }
}
