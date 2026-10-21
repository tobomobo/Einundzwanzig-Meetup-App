package space.einundzwanzig.meetup.data.meetups.portal

import kotlinx.coroutines.flow.StateFlow
import space.einundzwanzig.meetup.data.meetups.model.Meetup

/**
 * The crest registry: meetup name → portal logo (Dart `MeetupCalendarService.portalLogos`,
 * `logoFor`, `absoluteImageUrl`; spec 03 §5/§12, spec 10 §8.5).
 *
 * `:data:calendar` fills it while loading portal dates ([put] with the raw `meetup.logo`) and
 * binds a [PortalLogoLoader] so [ensureLoaded] can trigger that load. Crest composables observe
 * [logos] and recompose when logos arrive.
 */
interface PortalLogoRegistry {

    /**
     * Name → raw logo URL: the calendar entries ([put]) in insertion order (Dart `LinkedHashMap`),
     * followed by seeded entries ([seedFrom]) whose name the calendar does not have.
     */
    val logos: StateFlow<Map<String, String>>

    /** Records [logo] for [name] (non-empty logos only; an existing key keeps its position). */
    fun put(name: String, logo: String)

    /**
     * Seeds the registry from the directory's `Meetup.logoUrl` (group name and city as keys,
     * existing entries win). Display only; fills crests for meetups without upcoming dates
     * (allowed by spec 03 §5 "Native may additionally seed the registry from it"). Seeded entries
     * are kept apart: [logoFor] consults them only when no calendar entry matches, and they do
     * not count as "logos present" for [ensureLoaded].
     */
    fun seedFrom(meetups: List<Meetup>)

    /**
     * Fills the registry once if it has no calendar entries: a single shared in-flight call of the
     * bound [PortalLogoLoader] (no-op without a binding or when calendar logos are present). Unlike Flutter, a
     * load that produced nothing may be retried by a later call.
     */
    suspend fun ensureLoaded()

    /**
     * Dart `logoFor`: q = trimmed lowercase [titleOrCity]; empty → `""`. The first entry (insertion
     * order) whose lowercase name equals q, contains q or is contained in q →
     * `absoluteImageUrl(logo)`; none → `""`. The rule runs over the calendar entries first and only
     * then over the seeded ones, so results equal Flutter's whenever the calendar has a match.
     */
    fun logoFor(titleOrCity: String): String

    /** [PortalUrls.absoluteImageUrl]. */
    fun absoluteImageUrl(url: String): String = PortalUrls.absoluteImageUrl(url)
}

/** Loads portal dates and thereby fills the [PortalLogoRegistry]; bound by `:data:calendar`. */
fun interface PortalLogoLoader {
    /** Loads (e.g. `fetchPortalFirst()`); never throws. */
    suspend fun loadLogos()
}
