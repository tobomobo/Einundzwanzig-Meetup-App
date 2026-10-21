package space.einundzwanzig.meetup.data.calendar.meetups

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.data.calendar.internal.dartLower
import space.einundzwanzig.meetup.data.calendar.match.MeetupEventMatcher
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalUrls

/**
 * Result of [MeetupDatesLogic.toggleFavorite]: the new favorite list, the new home meetup
 * (`favs.first` or `""`) and whether the meetup was added (`true`) or removed (`false`).
 * The caller persists [favorites] / [homeMeetupId] to the user profile and shows the snackbar
 * (`calFavAdded` / `calFavRemoved` with `MeetupService.labelFor(favKey)`).
 */
data class FavoriteToggle(
    val favorites: List<String>,
    val homeMeetupId: String,
    val added: Boolean,
)

/**
 * Pure business rules of the meetup dates list (`lib/screens/calendar_screen.dart`, spec 10
 * §2.2): search filter, crest resolution, favorite star and the RSVP prefetch schedule. No I/O;
 * the ViewModel of `:feature:calendar` feeds it the loaded dates and the meetup directory.
 */
object MeetupDatesLogic {

    /** `_loadRsvpStatuses`: size of the first, immediately loaded (visible) chunk. */
    const val RSVP_FIRST_CHUNK: Int = 12

    /** `_loadRsvpStatuses`: size of every following background wave. */
    const val RSVP_WAVE_SIZE: Int = 20

    /** `_matchMeetup`: cities shorter than this never match. */
    const val MIN_CREST_CITY_LENGTH: Int = 3

    /**
     * Dart `_filterEvents`. `q = query.toLowerCase()`. While [initialMeetupId] is set and `q`
     * equals `(initialSearch ?? '').toLowerCase()` (the user did not edit the prefilled search),
     * only dates that [MeetupEventMatcher.resolve] to exactly that meetup are kept. Otherwise a
     * date is kept if its lowercase title or location contains `q` (empty `q` keeps all).
     * Note: the query is **not** trimmed (Dart parity).
     */
    fun filter(
        dates: List<MeetupDate>,
        query: String,
        initialSearch: String?,
        initialMeetupId: String?,
        meetups: List<Meetup>,
    ): List<MeetupDate> {
        val q = query.dartLower()
        if (initialMeetupId != null && q == (initialSearch ?: "").dartLower()) {
            return dates.filter { MeetupEventMatcher.resolve(it, meetups)?.id == initialMeetupId }
        }
        return dates.filter { it.title.dartLower().contains(q) || it.location.dartLower().contains(q) }
    }

    /**
     * Dart `_matchMeetup`: among [meetups] whose `city.trim().toLowerCase()` has at least 3
     * characters and occurs in `"$title $location".toLowerCase()`, the one with the LONGEST city
     * (first wins on equal length), else `null`. Callers may cache per `"$title|$location"`.
     */
    fun crestMeetup(date: MeetupDate, meetups: List<Meetup>): Meetup? {
        val hay = "${date.title} ${date.location}".dartLower()
        var best: Meetup? = null
        for (m in meetups) {
            val city = m.city.dartTrim().dartLower()
            if (city.length < MIN_CREST_CITY_LENGTH) continue
            if (hay.contains(city) && (best == null || city.length > best.city.dartTrim().length)) best = m
        }
        return best
    }

    /**
     * Crest URL of a date (Dart `_crest`): the portal logo of the date ([MeetupDate.logo]) if
     * non-empty, else [crestMeetup]'s `logoUrl`, else its `coverImagePath`; `""` = show the
     * fallback icon. Unlike Flutter (which passed relative portal paths to `Image.network`, so
     * they failed to the fallback), the result is made absolute with
     * [PortalUrls.absoluteImageUrl]; absolute URLs are returned unchanged.
     */
    fun crestUrl(date: MeetupDate, meetups: List<Meetup>): String {
        if (date.logo.isNotEmpty()) return PortalUrls.absoluteImageUrl(date.logo)
        val m = crestMeetup(date, meetups) ?: return ""
        return PortalUrls.absoluteImageUrl(m.logoUrl.ifEmpty { m.coverImagePath })
    }

    /**
     * The favorite star of a date whose resolved meetup ID is [favKey]: filled if any stored
     * favorite resolves (`MeetupService.resolveFavorite`) to [favKey].
     */
    fun isFavorite(favorites: Collection<String>, favKey: String, resolveFavorite: (String) -> Meetup?): Boolean =
        favorites.any { resolveFavorite(it)?.id == favKey }

    /**
     * Dart `_toggleFav(favKey)` without the persistence:
     * `sameMeetup(key) = key == favKey || resolveFavorite(key)?.id == favKey`; adds [favKey] if
     * no stored key is the same meetup, else removes every same-meetup key (legacy city names
     * included). `homeMeetupId = favs.first` or `""`.
     */
    fun toggleFavorite(favorites: List<String>, favKey: String, resolveFavorite: (String) -> Meetup?): FavoriteToggle {
        fun sameMeetup(key: String): Boolean = key == favKey || resolveFavorite(key)?.id == favKey
        val favs = favorites.toMutableList()
        val added = favs.none(::sameMeetup)
        if (added) favs.add(favKey) else favs.removeAll(::sameMeetup)
        return FavoriteToggle(favs.toList(), favs.firstOrNull() ?: "", added)
    }

    /**
     * Dart `_loadRsvpStatuses` schedule: the first 12 portal event IDs (list order) as one chunk
     * loaded in parallel, then the rest in waves of 20. Waves run one after another; the IDs
     * inside a wave are loaded in parallel (`getRsvpCached(id)`). Empty input → no waves.
     */
    fun rsvpPrefetchWaves(ids: List<Int>): List<List<Int>> {
        if (ids.isEmpty()) return emptyList()
        return listOf(ids.take(RSVP_FIRST_CHUNK)) + ids.drop(RSVP_FIRST_CHUNK).chunked(RSVP_WAVE_SIZE)
    }
}
