import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import fr.gunduz.awstoken.nav.AccountList
import fr.gunduz.awstoken.nav.Login
import fr.gunduz.awstoken.nav.Route
import fr.gunduz.awstoken.nav.Settings
import fr.gunduz.awstoken.ui.LoginScreen
import fr.gunduz.awstoken.ui.ProfileListScreen
import fr.gunduz.awstoken.ui.SettingsScreen

@Composable
@Preview
fun App() {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme) {
        Surface {
            // Login is the start destination on every launch. On a successful
            // discovery (fresh creds or the Keychain-backed shortcut) the
            // Login entry is popped and AccountList takes its place. Pushing
            // Login again from AccountList (the Discover icon) works the
            // same way — on success we pop back to AccountList.
            val backStack = remember { mutableStateListOf<Route>(Login) }
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryProvider = { key ->
                    when (key) {
                        Login -> NavEntry(key) {
                            LoginScreen(
                                onDiscovered = { _ ->
                                    // Remove any Login entries and ensure
                                    // AccountList is on top. Works whether
                                    // Login is the initial start destination
                                    // or was pushed on top later.
                                    backStack.removeAll { it == Login }
                                    if (backStack.lastOrNull() != AccountList) {
                                        backStack.add(AccountList)
                                    }
                                },
                            )
                        }
                        AccountList -> NavEntry(key) {
                            ProfileListScreen(
                                onOpenSettings = { backStack.add(Settings) },
                                onReDiscover = { backStack.add(Login) },
                            )
                        }
                        Settings -> NavEntry(key) {
                            SettingsScreen(onBack = { backStack.removeLastOrNull() })
                        }
                    }
                },
            )
        }
    }
}
