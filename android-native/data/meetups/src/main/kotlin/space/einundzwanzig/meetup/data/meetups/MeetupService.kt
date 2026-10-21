package space.einundzwanzig.meetup.data.meetups

import kotlinx.coroutines.flow.StateFlow
import space.einundzwanzig.meetup.data.meetups.model.Meetup

/**
 * The portal meetup directory (Dart `MeetupService`, spec 09 §8.1–§8.3).
 *
 * Favorites (`favorite_meetups` / `home_meetup`, owned by `UserProfile`) store portal IDs; values
 * written by old app versions may be city names. Every reader resolves them via
 * [resolveFavorite] (ID first, then case-insensitive city) — never compare `city == homeMeetupId`.
 *
 * The resolver functions read the in-memory cache only (filled by the last successful
 * [fetchMeetups]); they return `null`/fallbacks while it is empty.
 */
interface MeetupService {

    /** The last successfully fetched, ID-deduplicated list (possibly empty). Dart `cached`. */
    val cached: List<Meetup>

    /** [cached] as a flow for UIs that want to recompose when the directory arrives. */
    val cachedFlow: StateFlow<List<Meetup>>

    /**
     * The bundled offline list (München, Hamburg, Berlin; Dart `allMeetups`/`fallbackMeetups`).
     * Callers use it themselves when [fetchMeetups] returns an empty list.
     */
    val fallbackMeetups: List<Meetup>

    /**
     * `GET /api/mobile/meetups` (15 s). Never throws: non-200, malformed JSON or network errors
     * give `[]` and leave [cached] untouched. On success the list is deduplicated by exact ID
     * (first wins; two groups in one city are both kept) and becomes [cached].
     */
    suspend fun fetchMeetups(): List<Meetup>

    /**
     * Resolves a stored favorite: the meetup with `id == stored`, else the first meetup whose city
     * equals [stored] case-insensitively (legacy city-name favorites, spec 00 item 6). `null` if
     * [stored] or the cache is empty or nothing matches.
     */
    fun resolveFavorite(stored: String): Meetup?

    /**
     * Display label of a stored favorite: the group name if several cached meetups share its city
     * (and the name is non-empty), else the city; [stored] itself if unresolvable.
     */
    fun labelFor(stored: String): String

    /** City of a stored favorite, or [stored] itself if unresolvable. */
    fun cityFor(stored: String): String

    /**
     * Meetup for a signed badge identity (slug + country), **display only**. The slug rule is
     * `MeetupKey.slug(city)` = trim → lowercase → spaces to `-`. An empty [country] ignores the
     * country; otherwise it must match case-insensitively.
     */
    fun bySlug(slug: String, country: String): Meetup?

    /** Meetup whose group name (preferred) or city equals [name] (trimmed, case-insensitive). */
    fun byName(name: String): Meetup?

    /**
     * The meetup whose portal `nostr` field (trimmed, exact) equals the npub of [pubkeyHexOrNpub]
     * (hex is converted to npub; invalid hex → `null`). Fetches the directory when the cache is
     * empty.
     */
    suspend fun organizerMeetupFor(pubkeyHexOrNpub: String): Meetup?

    /**
     * Maps stored favorites (portal IDs or legacy city names) onto the IDs of [meetups]
     * (spec 09 §8.4). See [FavoriteMigration.migrate]; the result is meant to be persisted only
     * when the user saves.
     */
    fun migrateFavorites(stored: List<String>, meetups: List<Meetup> = cached): FavoriteMigration.Result =
        FavoriteMigration.migrate(stored, meetups)
}
