package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoLoader
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoRegistry
import space.einundzwanzig.meetup.data.meetups.portal.PortalUrls
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/** Process-wide crest registry with a single shared in-flight load. */
@Singleton
internal class PortalLogoRegistryImpl @Inject constructor(
    private val loader: Optional<PortalLogoLoader>,
    private val log: DiagnosticLog,
) : PortalLogoRegistry {

    private val lock = Any()
    private var inFlight: CompletableDeferred<Unit>? = null

    /** Entries from the calendar ([put]); the Dart `portalLogos` map. Guarded by [lock]. */
    private var calendar: Map<String, String> = emptyMap()

    /** Native-only entries from [seedFrom]; consulted only after [calendar]. Guarded by [lock]. */
    private var seeded: Map<String, String> = emptyMap()

    private val state = MutableStateFlow<Map<String, String>>(emptyMap())

    /** [calendar] entries first, then seeded entries whose key the calendar does not have. */
    override val logos: StateFlow<Map<String, String>> = state.asStateFlow()

    override fun put(name: String, logo: String) {
        if (logo.isEmpty()) return
        synchronized(lock) {
            // Dart `portalLogos[name] = logo`: overwrites the value, keeps the key's position.
            calendar = LinkedHashMap(calendar).apply { put(name, logo) }
            publish()
        }
    }

    override fun seedFrom(meetups: List<Meetup>) {
        synchronized(lock) {
            val next = LinkedHashMap(seeded)
            for (m in meetups) {
                if (m.logoUrl.isEmpty()) continue
                for (key in listOf(m.name, m.city)) if (key.isNotEmpty() && key !in next) next[key] = m.logoUrl
            }
            seeded = next
            publish()
        }
    }

    private fun publish() {
        val merged = LinkedHashMap(calendar)
        for ((k, v) in seeded) if (k !in merged) merged[k] = v
        state.value = merged
    }

    override suspend fun ensureLoaded() {
        // Seeded entries do not count: the calendar load still runs (Dart has no seeding).
        if (synchronized(lock) { calendar.isNotEmpty() }) return
        val l = loader.orElse(null) ?: return
        var owner = false
        val deferred = synchronized(lock) {
            inFlight ?: CompletableDeferred<Unit>().also {
                inFlight = it
                owner = true
            }
        }
        if (!owner) return deferred.await()
        try {
            runSuspendCatching { l.loadLogos() }.onFailure { log.warn(TAG, "Wappen nicht ladbar: $it") }
        } finally {
            synchronized(lock) { inFlight = null }
            deferred.complete(Unit)
        }
    }

    override fun logoFor(titleOrCity: String): String {
        val (cal, seed) = synchronized(lock) { calendar to seeded }
        // Dart rule over the calendar entries first; the seeded names (often short city names such
        // as "Ulm") would otherwise win for a containing query like "Einundzwanzig Neu-Ulm".
        return logoFor(cal, titleOrCity).ifEmpty { logoFor(seed, titleOrCity) }
    }

    companion object {
        private const val TAG = "Portal"

        /** Pure Dart `logoFor` over [logos]. */
        internal fun logoFor(logos: Map<String, String>, titleOrCity: String): String {
            val q = titleOrCity.dartTrim().dartLower()
            if (q.isEmpty()) return ""
            for ((name, logo) in logos) {
                val n = name.dartLower()
                if (n == q || n.contains(q) || q.contains(n)) return PortalUrls.absoluteImageUrl(logo)
            }
            return ""
        }
    }
}
