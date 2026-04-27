package fr.gunduz.awstoken.desktop.auth

import co.touchlab.kermit.Logger
import fr.gunduz.awstoken.auth.AdfsAuthenticator
import fr.gunduz.awstoken.auth.ExpirationTracker
import fr.gunduz.awstoken.auth.withAuthInProgress
import fr.gunduz.awstoken.aws.writeCredsAndMaybeDefault
import fr.gunduz.awstoken.model.AwsProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Background re-auth loop for profiles with `refreshEnabled = true`.
 *
 * - Wakes every `TICK_SECONDS` and iterates the current profile list.
 * - For each refresh-enabled profile it looks up the last known STS
 *   expiration (set by `recordExpiration` after every successful auth in
 *   the current session). If that expiration is within `LEAD_TIME` of now,
 *   it grabs the user's password from `PasswordCache` and re-authenticates.
 * - A profile whose password isn't cached (i.e. the user hasn't manually
 *   authenticated it in this session yet) is silently skipped. Refresh is
 *   a convenience, not a prerequisite for correctness; the tray's
 *   Authenticate action is always available.
 *
 * Deliberately NOT persisting expirations: on a fresh app launch we want
 * the user to re-enter their password before anything hits the network,
 * so skipping refresh until after the first manual auth is the right UX.
 */
class RefreshScheduler(
    private val scope: CoroutineScope,
    private val profilesProvider: () -> List<AwsProfile>,
) {
    private val expirationByProfileId = ConcurrentHashMap<String, Instant>()
    private var job: Job? = null

    init {
        // Expose a process-wide handle so the "refresh aliases" button can
        // feed silently-auth'd profiles back into the scheduler's in-memory
        // expiration map. Only one scheduler instance exists per process.
        current = this
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(TICK_SECONDS * 1000L)
                refreshDueProfiles()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun recordExpiration(profileId: String, expirationIso: String) {
        runCatching { Instant.parse(expirationIso) }
            .onSuccess {
                expirationByProfileId[profileId] = it
                // Mirror into the shared cross-UI tracker so the tray + main
                // window can show a live countdown without peeking at our
                // internal map.
                ExpirationTracker.record(profileId, it.toEpochMilli())
            }
            .onFailure {
                Logger.w(it, tag = "RefreshScheduler") { "Unparseable STS expiration '$expirationIso'" }
            }
    }

    private suspend fun refreshDueProfiles() {
        val now = Instant.now()
        val dueCutoff = now.plusSeconds(LEAD_TIME_SECONDS)
        val profiles = profilesProvider().filter { it.refreshEnabled }

        for (profile in profiles) {
            val expiration = expirationByProfileId[profile.id]
            if (expiration == null || expiration.isAfter(dueCutoff)) continue

            val password = PasswordCache.get(profile.adfsHost, profile.username)
            if (password == null) {
                Logger.d(tag = "RefreshScheduler") {
                    "skip ${profile.name}: no cached password for ${profile.username}@${profile.adfsHost}"
                }
                continue
            }

            Logger.i(tag = "RefreshScheduler") {
                "refreshing ${profile.name} (expires $expiration)"
            }
            val result = withAuthInProgress(profile.id) {
                AdfsAuthenticator().authenticate(profile, password)
            }
            result.onSuccess { creds ->
                writeCredsAndMaybeDefault(profile, creds)
                recordExpiration(profile.id, creds.expirationIso)
                Logger.i(tag = "RefreshScheduler") {
                    "refreshed ${profile.name}, new expiration ${creds.expirationIso}"
                }
            }.onFailure { err ->
                Logger.w(err, tag = "RefreshScheduler") { "auto-refresh failed for ${profile.name}" }
                // Drop the recorded expiration so we don't keep retrying every
                // tick. The user will see the refresh checkbox still on and
                // can click Authenticate manually to recover.
                expirationByProfileId.remove(profile.id)
            }
        }

        // Best-effort alias fetch: if the user has any profile in an account
        // whose alias is not yet known and we've authenticated at least once
        // this session (expiration recorded), fetch it with the currently
        // valid credentials. Not implemented here to keep the loop simple —
        // alias is fetched once in the foreground auth path instead.
        Unit
    }

    companion object {
        private const val TICK_SECONDS = 60L
        private const val LEAD_TIME_SECONDS = 300L // re-auth 5 minutes before expiry

        /**
         * Process-wide pointer to the live scheduler instance. Set in `init`
         * when `main.kt` constructs the scheduler; nullable only because
         * unrelated call sites might conceivably run before construction
         * (never happens in the current wiring).
         */
        @Volatile
        var current: RefreshScheduler? = null
            private set
    }
}
