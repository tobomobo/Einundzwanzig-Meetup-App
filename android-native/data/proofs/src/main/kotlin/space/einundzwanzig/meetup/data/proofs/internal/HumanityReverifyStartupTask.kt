package space.einundzwanzig.meetup.data.proofs.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityKeys
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityProofRepository
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Re-verifies a humanity proof restored from a backup (Dart home screen `_loadIdentityData` →
 * `reverifyIfNeeded()`, Audit 2 finding #4).
 *
 * Dart ran the check on every home-screen load, so a proof restored during onboarding or from the
 * backup screen was re-checked in the same session, and a failed check (offline, signer not ready,
 * every relay down) was retried on the next home load. Natively this task watches
 * `humanity_needs_reverification` for the whole process lifetime in [ApplicationScope] and runs
 * [HumanityProofRepository.reverifyIfNeeded] whenever the flag becomes `true` (also at start if it
 * is already set). While the flag stays set after a failed attempt it retries when a signer
 * becomes ready ([ProofsIdentity.signerReady]) and after [retryDelays] (1, 5, 15 min); after the
 * last delay only a ready signer, a new flag value or the next app start trigger another attempt.
 * Clearing the flag (identity switch, spec 06 §14 R20, or the attempt's own successful write)
 * cancels the attempt and its retries. [run] returns immediately so the search (minutes with
 * dead relays, spec 06 R17) never delays later startup groups. Silent: failures are only logged.
 */
internal class HumanityReverifyStartupTask @Inject constructor(
    private val humanity: HumanityProofRepository,
    private val store: KeyValueStore,
    private val identity: ProofsIdentity,
    @ApplicationScope private val scope: CoroutineScope,
    private val log: DiagnosticLog,
) : AppStartupTask {

    override val name: String = "HumanityReverify"

    /** Waits between failed attempts while the flag stays set (tests shorten them). */
    internal var retryDelays: List<Duration> = RETRY_DELAYS

    override suspend fun run() {
        scope.launch { watch() }
    }

    /** Collects the flag until cancelled. */
    internal suspend fun watch() {
        // Unfiltered on purpose: when the flag goes away (identity switch, or a check that found
        // the proof) collectLatest cancels an attempt that is still searching or waiting to retry.
        store.observeBool(HumanityKeys.NEEDS_REVERIFICATION)
            .collectLatest { pending -> if (pending == true) attemptUntilDone() }
    }

    private suspend fun attemptUntilDone() = coroutineScope {
        // Subscribed for the whole loop, so a signer that becomes ready during an attempt still
        // triggers the next one.
        val signerReady = Channel<Unit>(Channel.CONFLATED)
        val listener = launch { identity.signerReady.collect { signerReady.trySend(Unit) } }
        try {
            var failures = 0
            while (!attempt() && store.getBool(HumanityKeys.NEEDS_REVERIFICATION) == true) {
                val wait = retryDelays.getOrNull(failures++)
                if (wait != null) withTimeoutOrNull(wait) { signerReady.receive() } else signerReady.receive()
            }
        } finally {
            listener.cancel()
        }
    }

    /** @return `true` once re-verified. */
    private suspend fun attempt(): Boolean = try {
        humanity.reverifyIfNeeded()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.debug("HumanityProof", "Reverifikation fehlgeschlagen (offline?): $e")
        false
    }

    companion object {
        /** Retry delays after the 1st, 2nd and 3rd failed attempt. */
        val RETRY_DELAYS: List<Duration> = listOf(1.minutes, 5.minutes, 15.minutes)
    }
}
