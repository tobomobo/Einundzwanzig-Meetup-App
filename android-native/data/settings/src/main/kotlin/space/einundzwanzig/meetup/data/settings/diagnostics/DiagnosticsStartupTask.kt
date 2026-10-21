package space.einundzwanzig.meetup.data.settings.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.log.DiagnosticsService
import space.einundzwanzig.meetup.core.common.log.DiagnosticsSources
import space.einundzwanzig.meetup.core.common.log.InventoryCounts
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import java.util.Optional
import javax.inject.Inject

/**
 * Supplies the `Bestand: N Badge(s) · M Favorit(en)` line of the start-up fingerprint. Counting
 * badges needs the encrypted badge store, so the module that owns it (or `:app`) binds this:
 *
 * ```
 * @Binds abstract fun inventory(impl: MyInventorySource): DiagnosticsInventorySource
 * ```
 *
 * Without a binding the line is skipped.
 */
fun interface DiagnosticsInventorySource {
    /** Badge count (Dart `MeetupBadge.loadBadges().length`) and favourite count (`favoriteMeetupIds.length`). */
    suspend fun counts(): InventoryCounts
}

/**
 * Writes the environment fingerprint once per process start (Dart `DiagnosticsService.logEnvironment`,
 * called unawaited from `main()`; spec 12 §8.13). The lines and wording come from `:core:common`'s
 * [DiagnosticsService]; this task supplies the parts owned by other domains.
 *
 * The task runs alone in the earliest startup group and starts `logEnvironment` undispatched, so
 * the `APP-START` header is written before [run] returns and therefore before any other startup
 * task logs; the remaining probes (location, inventory) continue in the application scope without
 * holding up later groups (Dart: unawaited from `main()`).
 *
 * The app must not call `logEnvironment` itself as well, or the block appears twice. The signing
 * mode is read from the raw `signing_mode` key, so `:app` must run the startup tasks only after
 * the Flutter migration has completed (as `AppStartupTask` requires).
 */
internal class DiagnosticsStartupTask @Inject constructor(
    private val diagnostics: DiagnosticsService,
    private val store: KeyValueStore,
    private val inventory: Optional<DiagnosticsInventorySource>,
    @param:ApplicationScope private val scope: CoroutineScope,
) : AppStartupTask {

    override val name: String = "DiagnosticsFingerprint"

    // Alone in the first group: the APP-START block must open this start's log.
    override val order: Int = ORDER

    override suspend fun run() {
        val source: DiagnosticsInventorySource? = inventory.orElse(null)
        val counts: (suspend () -> InventoryCounts)? = source?.let { s -> suspend { s.counts() } }
        val sources = DiagnosticsSources(
            signingMode = { signingModeName(store.getString(SIGNING_MODE_KEY)) },
            inventory = counts,
        )
        // UNDISPATCHED: the synchronous section header is written before run() returns.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { diagnostics.logEnvironment(sources) }
    }

    internal companion object {
        /** Startup group of this task; lower than every other task so it runs first and alone. */
        const val ORDER = -100

        /** Flutter key of `SigningService` (owned by `:data:identity`, read-only here). */
        const val SIGNING_MODE_KEY = "signing_mode"

        /**
         * Dart `SigningService.getMode().name` on Android: `amber`, `nip07`, `nip46`, and `local`
         * for absent or unknown values (Amber is supported on Android, so it is not downgraded).
         */
        fun signingModeName(stored: String?): String = when (stored) {
            "amber", "nip07", "nip46" -> stored
            else -> "local"
        }
    }
}
