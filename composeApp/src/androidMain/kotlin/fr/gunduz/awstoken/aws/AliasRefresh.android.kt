package fr.gunduz.awstoken.aws

// Alias refresh not wired on Android yet (no desktop-only PasswordCache).
actual suspend fun refreshAllAccountAliases(explicitPassword: String?): AliasRefreshResult = AliasRefreshResult(
    aliasesFetched = 0,
    aliasesChanged = 0,
    skippedReason = "Not supported on Android yet",
)
