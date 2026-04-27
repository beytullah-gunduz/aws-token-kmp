# AWS Token KMP

A desktop tray app that turns a single ADFS login into fresh AWS credentials
for every role you can assume. One password, all your accounts, always up to
date.

Built with Kotlin Multiplatform and Compose Multiplatform. Runs on macOS,
Windows, and Linux with a shared codebase (Android build included).

## Features

- **One-click discovery** --- sign in to ADFS once and the app creates a
  profile for every `(principalArn, roleArn)` pair in the SAML assertion,
  auto-named `<accountId>-<roleName>`.
- **STS credentials written to `~/.aws/credentials`** --- every profile
  gets its own INI section, and the default profile mirrors whichever role
  you star, so plain `aws` commands just work.
- **Background refresh** --- profiles with auto-refresh enabled are
  silently re-authenticated before they expire (60 s polling, 5 min
  look-ahead). No surprise expired-session errors.
- **Live countdown** --- each profile shows how long its credentials are
  still valid, updated every second.
- **macOS Keychain** --- optionally store your ADFS password so you don't
  retype it after every restart. Unchecking "Remember" deletes the entry
  immediately.
- **System tray** --- the app lives in the menu bar. Authenticate, flip
  regions, toggle auto-refresh, or set the default --- all from the tray
  menu. The tray icon animates while an auth is in flight.
- **Hide to tray on close** --- closing the window hides it (Dock icon
  disappears too). Click the tray to bring it back. A setting switches
  the close button to a real quit if you prefer.
- **Account aliases** --- the app scrapes the AWS SAML sign-in page for
  human-readable account aliases and shows them alongside account IDs.
- **Configurable session duration** --- STS `DurationSeconds` from 1 to 8
  hours via a slider in settings.
- **Corporate proxy & split-horizon DNS** --- respects macOS system proxy
  settings and falls back through `dscacheutil` / `host` / `dig` when the
  JVM resolver can't see VPN-pushed DNS entries.
- **File logging** --- opt-in log mirroring to
  `~/.aws-token-kmp/logs/aws-token-kmp.log` (5 MB cap, one rotation).
- **ADFS debug dumps** --- every authentication hop writes sanitised HTML
  to `~/.aws-token-kmp/debug/` for troubleshooting federated login
  issues. Sensitive fields (SAML assertions, cookies) are redacted.

## Installation

### macOS (pre-built DMG)

Download `AwsTokenKmp-1.0.0.dmg` from the
[Releases](../../releases) page, open it, and drag the app to
`/Applications`.

> The app is unsigned. On first launch macOS will block it ---
> right-click the app, choose **Open**, then click **Open** again to
> bypass Gatekeeper.

### Build from source

Requires JDK 21+.

```bash
# Desktop (macOS / Windows / Linux)
./gradlew :composeApp:run                    # development run
./gradlew :composeApp:packageReleaseDmg      # release .dmg (macOS)

# Android
./gradlew :androidApp:assembleDebug

# Formatting
./gradlew spotlessCheck                      # verify
./gradlew spotlessApply                      # auto-fix
```

The release DMG lands in
`composeApp/build/compose/binaries/main-release/dmg/`.

## Quick start

1. Launch the app. The **Sign in** screen appears on every start.
2. Enter your ADFS host (e.g. `sts.example.com`), username, and
   password. If a saved password exists in the Keychain, the field shows
   dots --- just click **Connect**.
3. The app discovers all roles and creates one profile per role.
4. You land on the **profile list**. Click any profile to authenticate it
   --- credentials are written to `~/.aws/credentials` immediately.
5. Star a profile to make it the default (`[default]` section in the
   credentials file).
6. Enable auto-refresh on profiles you use frequently so they stay fresh
   in the background.

## Platforms

| Platform | UI | Credentials |
|---|---|---|
| macOS / Windows / Linux | Tray + undecorated window | `~/.aws/credentials` |
| Android | Compose activity | App-private storage |

The desktop build is the primary target. Android shares the same
`commonMain` codebase but has stubs for Keychain, tray, and background
refresh (contributions welcome).

## Tech stack

- **Kotlin** 2.3 / **Compose Multiplatform** 1.11
- **Navigation 3** (`org.jetbrains.androidx.navigation3:navigation3-ui`)
- **Ktor 3** + OkHttp engine (ADFS form walker, STS calls, alias scraping)
- **Ksoup** (HTML parsing for ADFS multi-hop forms)
- **DataStore** (preferences persistence)
- **JNA** (macOS Dock icon visibility, native window drag)
- **Kermit** (logging)
- **Spotless + ktlint** (code formatting)

## License

Apache-2.0
