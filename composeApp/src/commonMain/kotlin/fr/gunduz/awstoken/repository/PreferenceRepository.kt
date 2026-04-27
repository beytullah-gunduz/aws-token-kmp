package fr.gunduz.awstoken.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import fr.gunduz.awstoken.datastore.dataStorePreferencesInstance
import fr.gunduz.awstoken.model.AwsProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Hand-rolled singleton (per the project's "no DI framework" rule). All persistent
 * configuration — profiles list, default profile id — flows through here, backed
 * by DataStore preferences. Profiles are serialized as a single JSON string
 * because we have no table store; the list is small (a handful of entries).
 */
class PreferenceRepository {
    companion object {
        val instance: PreferenceRepository by lazy { PreferenceRepository() }
        const val LOG_TAG = "AwsToken.PreferenceRepository"

        const val MIN_SESSION_DURATION_SECONDS: Int = 3600 // 1 hour
        const val MAX_SESSION_DURATION_SECONDS: Int = 28_800 // 8 hours
        const val DEFAULT_SESSION_DURATION_SECONDS: Int = 3600 // 1 hour

        const val DEFAULT_WINDOW_WIDTH_DP: Float = 520f
        const val DEFAULT_WINDOW_HEIGHT_DP: Float = 780f
    }

    private val dataStore: DataStore<Preferences> by lazy { dataStorePreferencesInstance }

    private val PROFILES_JSON = stringPreferencesKey("profiles_json")
    private val DEFAULT_PROFILE_ID = stringPreferencesKey("default_profile_id")
    private val LAST_ADFS_HOST = stringPreferencesKey("last_adfs_host")
    private val LAST_USERNAME = stringPreferencesKey("last_username")
    private val ACCOUNT_ALIASES_JSON = stringPreferencesKey("account_aliases_json")
    private val DISCOVERY_SYNC_ALIASES = androidx.datastore.preferences.core.booleanPreferencesKey("discovery_sync_aliases")
    private val AUTO_AUTH_DEFAULT_ON_START = androidx.datastore.preferences.core.booleanPreferencesKey("auto_auth_default_on_start")
    private val PERSIST_PASSWORD_IN_KEYCHAIN = androidx.datastore.preferences.core.booleanPreferencesKey("persist_password_in_keychain")
    private val FILE_LOGGING_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("file_logging_enabled")
    private val SESSION_DURATION_SECONDS = androidx.datastore.preferences.core.intPreferencesKey("session_duration_seconds")
    private val WINDOW_WIDTH_DP = floatPreferencesKey("window_width_dp")
    private val WINDOW_HEIGHT_DP = floatPreferencesKey("window_height_dp")
    private val CLOSE_BUTTON_QUITS = androidx.datastore.preferences.core.booleanPreferencesKey("close_button_quits")

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    val profilesAsFlow: Flow<List<AwsProfile>> = dataStore.data
        .map { prefs -> decode(prefs[PROFILES_JSON]) }
        .distinctUntilChanged()

    suspend fun getProfiles(): List<AwsProfile> = decode(dataStore.data.first()[PROFILES_JSON])

    suspend fun upsertProfile(profile: AwsProfile) {
        mutate { current ->
            val idx = current.indexOfFirst { it.id == profile.id }
            if (idx >= 0) current.toMutableList().also { it[idx] = profile } else current + profile
        }
    }

    suspend fun removeProfile(id: String) {
        mutate { current -> current.filterNot { it.id == id } }
        if (getDefaultProfileId() == id) setDefaultProfileId(null)
    }

    suspend fun setProfileRefreshEnabled(id: String, enabled: Boolean) {
        mutate { current -> current.map { if (it.id == id) it.copy(refreshEnabled = enabled) else it } }
    }

    suspend fun setProfileRegion(id: String, regionCode: String) {
        mutate { current -> current.map { if (it.id == id) it.copy(region = regionCode) else it } }
    }

    val defaultProfileIdAsFlow: Flow<String?> = dataStore.data
        .map { prefs -> prefs[DEFAULT_PROFILE_ID] }
        .distinctUntilChanged()

    suspend fun getDefaultProfileId(): String? = dataStore.data.first()[DEFAULT_PROFILE_ID]

    suspend fun setDefaultProfileId(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(DEFAULT_PROFILE_ID) else prefs[DEFAULT_PROFILE_ID] = id
        }
    }

    /**
     * Discovery form memo: ADFS host + username are re-used across relaunches
     * so the user doesn't retype them every time. Password is deliberately
     * never persisted — it lives only in the dialog's `remember` and is
     * discarded on dismiss.
     */
    suspend fun getLastDiscoveryIdentity(): Pair<String, String> {
        val prefs = dataStore.data.first()
        return (prefs[LAST_ADFS_HOST] ?: "") to (prefs[LAST_USERNAME] ?: "")
    }

    suspend fun setLastDiscoveryIdentity(adfsHost: String, username: String) {
        dataStore.edit { prefs ->
            prefs[LAST_ADFS_HOST] = adfsHost
            prefs[LAST_USERNAME] = username
        }
    }

    /** Persist whether the discovery dialog's "sync aliases from AWS" checkbox was left on or off. */
    suspend fun getDiscoverySyncAliases(): Boolean = dataStore.data.first()[DISCOVERY_SYNC_ALIASES] ?: true

    suspend fun setDiscoverySyncAliases(value: Boolean) {
        dataStore.edit { prefs -> prefs[DISCOVERY_SYNC_ALIASES] = value }
    }

    /**
     * When set, the app opens the password dialog for the current default
     * profile at launch if its on-disk STS credentials are expired or
     * missing. Default `false` so first-launch users aren't surprised by an
     * immediate modal.
     */
    suspend fun getAutoAuthDefaultOnStart(): Boolean = dataStore.data.first()[AUTO_AUTH_DEFAULT_ON_START] ?: false

    suspend fun setAutoAuthDefaultOnStart(value: Boolean) {
        dataStore.edit { prefs -> prefs[AUTO_AUTH_DEFAULT_ON_START] = value }
    }

    /**
     * Opt-in to persisting the ADFS password in the OS keychain
     * (currently macOS only). When off (default) the password lives only in
     * the in-memory `PasswordCache` for the lifetime of the process.
     */
    suspend fun getPersistPasswordInKeychain(): Boolean = dataStore.data.first()[PERSIST_PASSWORD_IN_KEYCHAIN] ?: false

    suspend fun setPersistPasswordInKeychain(value: Boolean) {
        dataStore.edit { prefs -> prefs[PERSIST_PASSWORD_IN_KEYCHAIN] = value }
    }

    /**
     * Mirror every Kermit log line into `~/.aws-token-kmp/logs/aws-token-kmp.log`.
     * Default `false` — file logging is opt-in so a normal user isn't
     * generating disk I/O they don't know about. Log file is capped at 5 MB
     * with one generation of rotation (`.old`).
     */
    suspend fun getFileLoggingEnabled(): Boolean = dataStore.data.first()[FILE_LOGGING_ENABLED] ?: false

    val fileLoggingEnabledAsFlow: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[FILE_LOGGING_ENABLED] ?: false }
        .distinctUntilChanged()

    suspend fun setFileLoggingEnabled(value: Boolean) {
        dataStore.edit { prefs -> prefs[FILE_LOGGING_ENABLED] = value }
    }

    /**
     * Requested STS session duration in seconds. This is passed to AWS STS
     * as the `DurationSeconds` parameter of `AssumeRoleWithSAML`. Clamped to
     * the `[MIN, MAX]_SESSION_DURATION_SECONDS` range on write.
     *
     * Note: the effective maximum is whichever is smaller of this setting and
     * the role's own `MaxSessionDuration` IAM attribute. If a user picks 8h
     * but a role is capped at 1h server-side, the STS call will fail with a
     * `ValidationError` — the auth dialog surfaces that error verbatim so the
     * user can lower the setting and retry.
     */
    suspend fun getSessionDurationSeconds(): Int = (dataStore.data.first()[SESSION_DURATION_SECONDS] ?: DEFAULT_SESSION_DURATION_SECONDS)
        .coerceIn(MIN_SESSION_DURATION_SECONDS, MAX_SESSION_DURATION_SECONDS)

    suspend fun setSessionDurationSeconds(value: Int) {
        val clamped = value.coerceIn(MIN_SESSION_DURATION_SECONDS, MAX_SESSION_DURATION_SECONDS)
        dataStore.edit { prefs -> prefs[SESSION_DURATION_SECONDS] = clamped }
    }

    /**
     * Persisted window size in Dp. `null` when nothing has been saved yet
     * (first launch) — the caller falls back to
     * `DEFAULT_WINDOW_WIDTH_DP × DEFAULT_WINDOW_HEIGHT_DP`.
     */
    suspend fun getWindowSize(): Pair<Float, Float>? {
        val prefs = dataStore.data.first()
        val w = prefs[WINDOW_WIDTH_DP]
        val h = prefs[WINDOW_HEIGHT_DP]
        return if (w != null && h != null) w to h else null
    }

    suspend fun setWindowSize(widthDp: Float, heightDp: Float) {
        dataStore.edit { prefs ->
            prefs[WINDOW_WIDTH_DP] = widthDp
            prefs[WINDOW_HEIGHT_DP] = heightDp
        }
    }

    /**
     * When `true`, clicking the red close traffic light (or otherwise
     * requesting the window to close) terminates the app. When `false`
     * (default), closing hides the window to the tray — the process and
     * tray icon stay alive so the user can bring the window back with a
     * tray click.
     */
    suspend fun getCloseButtonQuits(): Boolean = dataStore.data.first()[CLOSE_BUTTON_QUITS] ?: false

    val closeButtonQuitsAsFlow: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[CLOSE_BUTTON_QUITS] ?: false }
        .distinctUntilChanged()

    suspend fun setCloseButtonQuits(value: Boolean) {
        dataStore.edit { prefs -> prefs[CLOSE_BUTTON_QUITS] = value }
    }

    /**
     * Human-readable aliases keyed by AWS account id. Stored once per
     * account (not per profile) so renaming `123456789012` to `prod-ops`
     * automatically updates every profile under that account.
     */
    val accountAliasesAsFlow: Flow<Map<String, String>> = dataStore.data
        .map { prefs -> decodeAliases(prefs[ACCOUNT_ALIASES_JSON]) }
        .distinctUntilChanged()

    suspend fun getAccountAliases(): Map<String, String> = decodeAliases(dataStore.data.first()[ACCOUNT_ALIASES_JSON])

    suspend fun setAccountAlias(accountId: String, alias: String?) {
        dataStore.edit { prefs ->
            val current = decodeAliases(prefs[ACCOUNT_ALIASES_JSON]).toMutableMap()
            if (alias.isNullOrBlank()) current.remove(accountId) else current[accountId] = alias.trim()
            prefs[ACCOUNT_ALIASES_JSON] = json.encodeToString(AliasMapSerializer, current)
        }
    }

    private fun decodeAliases(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString(AliasMapSerializer, raw) }
            .getOrDefault(emptyMap())
    }

    private val AliasMapSerializer = MapSerializer(String.serializer(), String.serializer())

    private suspend fun mutate(block: (List<AwsProfile>) -> List<AwsProfile>) {
        dataStore.edit { prefs ->
            val current = decode(prefs[PROFILES_JSON])
            val next = block(current)
            prefs[PROFILES_JSON] = json.encodeToString(ListSerializer, next)
        }
    }

    private fun decode(raw: String?): List<AwsProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer, raw) }
            .onFailure { Logger.w(tag = LOG_TAG) { "Failed to decode profiles: ${it.message}" } }
            .getOrDefault(emptyList())
    }

    private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(AwsProfile.serializer())
}
