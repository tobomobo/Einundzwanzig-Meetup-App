package space.einundzwanzig.meetup.data.proofs.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityKeys
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityProofRepository
import javax.inject.Inject

/**
 * Re-verifies a humanity proof restored from a backup (Dart home screen `_loadIdentityData` →
 * `reverifyIfNeeded()`, Audit 2 finding #4).
 *
 * Dart ran the check on every home-screen load, so a proof restored during onboarding or from the
 * backup screen was re-checked in the same session. Natively this task watches
 * `humanity_needs_reverification` for the whole process lifetime in [ApplicationScope] and runs
 * [HumanityProofRepository.reverifyIfNeeded] whenever the flag becomes `true` (also at start if it
 * is already set). A failed attempt leaves the flag `true`; the flow is distinct, so it does not
 * loop and the next app start retries. [run] returns immediately so the search (minutes with dead
 * relays, spec 06 R17) never delays later startup groups. Silent: failures are only logged.
 */
internal class HumanityReverifyStartupTask @Inject constructor(
    private val humanity: HumanityProofRepository,
    private val store: KeyValueStore,
    @ApplicationScope private val scope: CoroutineScope,
    private val log: DiagnosticLog,
) : AppStartupTask {

    override val name: String = "HumanityReverify"

    override suspend fun run() {
        scope.launch { watch() }
    }

    /** Collects the flag until cancelled. */
    internal suspend fun watch() {
        store.observeBool(HumanityKeys.NEEDS_REVERIFICATION)
            .filter { it == true }
            .collectLatest { attempt() }
    }

    private suspend fun attempt() {
        try {
            humanity.reverifyIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("HumanityProof", "Reverifikation fehlgeschlagen (offline?): $e")
        }
    }
}
