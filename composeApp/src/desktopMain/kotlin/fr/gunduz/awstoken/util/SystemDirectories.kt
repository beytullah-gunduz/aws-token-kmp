package fr.gunduz.awstoken.util

import kotlinx.io.files.Path

object SystemDirectories {
    val applicationDirectory: Path = if (System.getenv("APPDATA") != null) {
        Path(System.getenv("APPDATA") + "/AwsTokenKmp")
    } else {
        Path(System.getProperty("user.home") + "/.aws-token-kmp")
    }
}
