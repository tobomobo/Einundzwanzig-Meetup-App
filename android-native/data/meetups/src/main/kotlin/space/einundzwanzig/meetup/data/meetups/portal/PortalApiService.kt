package space.einundzwanzig.meetup.data.meetups.portal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * REST client of the Einundzwanzig portal (Dart `PortalApiService`, spec 09 §5, §6, §8.9, §8.11).
 *
 * Authentication: a Sanctum bearer token obtained with [loginWithNostr] (stateless kind-22242
 * challenge signed by the active signer — local key, Amber or NIP-46 — spec 00 item 16, not
 * NIP-98). The token lives in the Keystore-backed `SecureStore` under `portal_api_token`, bound to
 * the identity in `portal_api_token_npub`. It is never logged or backed up.
 *
 * Read calls never throw; failures give `null`/`[]` as documented. Write calls return a
 * [PortalResult]; a 401 deletes the token.
 */
interface PortalApiService {

    // ---- token ---------------------------------------------------------------------------

    /** The stored token, or `null` (absent OR unreadable; an unreadable token is logged). */
    suspend fun getToken(): String?

    /** `getToken() != null`. */
    suspend fun hasToken(): Boolean

    /**
     * Stores [token] and binds it to the mode-aware `SigningService.npub()` (if non-empty), both in
     * one secure-store transaction. Returns `false` (and logs) if the secure store rejected the
     * write and nothing was stored (Dart threw); show an error then, never "saved".
     */
    suspend fun storeToken(token: String): Boolean

    /**
     * `storeToken(token.trim())` — personal token created on the portal website. `false` if the
     * token could not be stored.
     */
    suspend fun setManualToken(token: String): Boolean

    /** Deletes token and binding; `false` (and a log line) if the secure store failed. */
    suspend fun deleteToken(): Boolean

    /**
     * Stateless Nostr login: random 32-byte hex `k1`, kind 22242 with exactly `[["challenge", k1]]`
     * and empty content signed via `SigningService`, then
     * `POST /api/mobile/token {k1, event, device_name}`. 200/201 with a non-empty `token` → stored,
     * `PortalResult(ok, data = body.user)`. The event is never published to relays.
     */
    suspend fun loginWithNostr(deviceName: String = PortalUrls.DEVICE_NAME): PortalResult

    /**
     * Revokes the token server-side (best effort, errors ignored) and always deletes it locally.
     * Runs non-cancellable: cancelling the caller does not skip the local delete.
     */
    suspend fun logout()

    /**
     * Does the stored token belong to the active identity? `false` = proven foreign or dead
     * (callers then delete it); `true` when in doubt. Decision table: spec 09 §8.11. If
     * `SigningService.npub()` throws (signer unreachable) the answer is `true` (Dart propagated the
     * exception and the token was kept); `null`/empty npub stays `false`.
     */
    suspend fun tokenMatchesCurrentKey(): Boolean

    /** `GET /api/user` with the token; 401 deletes the token. `null` on any failure. */
    suspend fun getProfile(): JsonObject?

    // ---- generic reads -------------------------------------------------------------------

    /** `GET /api<path>` (`Accept` + optional bearer). `null` on non-200 or errors. */
    suspend fun rawGet(path: String): JsonElement?

    /**
     * Like [rawGet] but with the status: 200 → `(200, json)`, other → `(code, null)`, no response
     * → `(0, null)`. Distinguishes an expired token (401/403) from being offline.
     */
    suspend fun rawGetStatus(path: String): StatusBody

    // ---- organizer -----------------------------------------------------------------------

    /** `GET /api/my-meetups`; any failure → `[]` (no token deletion). */
    suspend fun getMyMeetups(): List<PortalMeetup>

    /** `GET /api/my-meetup-events`; any failure → `[]`. */
    suspend fun getMyMeetupEvents(): List<PortalMeetupEvent>

    /**
     * The result of the last [getMyMeetupEvents] call of this process (in memory, `[]` before the
     * first call). The portal event editor resolves the edited date from it without a second
     * request (ROUTES.md, `PortalEventEditor`).
     */
    val lastMyMeetupEvents: List<PortalMeetupEvent> get() = emptyList()

    /**
     * `POST /api/meetup-events`. [start]/[recurrenceEndDate] in RFC 3339 UTC
     * ([PortalTime.toPortalIso]). Empty location/description/link and every `null` are omitted
     * (the portal rejects explicit `null` recurrence values). Recurrence type + end date → series.
     */
    suspend fun createMeetupEvent(
        meetupId: Int,
        start: String,
        location: String? = null,
        description: String? = null,
        link: String? = null,
        recurrenceType: String? = null,
        recurrenceDayOfWeek: String? = null,
        recurrenceDayPosition: String? = null,
        recurrenceInterval: Int? = null,
        recurrenceEndDate: String? = null,
    ): PortalResult

    /** `PATCH /api/meetup-events/{eventId}`; only non-null keys are sent. */
    suspend fun updateMeetupEvent(
        eventId: Int,
        meetupId: Int? = null,
        start: String? = null,
        location: String? = null,
        description: String? = null,
        link: String? = null,
        recurrenceType: String? = null,
        recurrenceDayOfWeek: String? = null,
        recurrenceDayPosition: String? = null,
        recurrenceInterval: Int? = null,
        recurrenceEndDate: String? = null,
    ): PortalResult

    /** `GET /api/meetup/{id}/leaders` (`data` list or bare list of objects); `[]` on failure. */
    suspend fun getMeetupLeaders(meetupId: Int): List<JsonObject>

    /** `POST /api/meetup/{id}/leaders {"npub": …}`. */
    suspend fun addMeetupLeader(meetupId: Int, npub: String): PortalResult

    /** `DELETE /api/meetup/{id}/leaders/{userId}`. */
    suspend fun removeMeetupLeader(meetupId: Int, userId: Int): PortalResult

    // ---- public events + RSVP ------------------------------------------------------------

    /**
     * Upcoming public meetup events: `GET /api/meetup-events/{date}` for [today], the 1st of next
     * month and the 1st of the month after (sequential), merged by `id` (a later date overwrites
     * the value but keeps the first position). Raw maps; times are UTC without zone.
     */
    suspend fun getAllMeetupEvents(today: LocalDate): List<JsonObject>

    /**
     * [getAllMeetupEvents] for today's local date (device zone) taken from the injected
     * `TimeSource` (Dart `DateTime.now()`), never the system clock directly.
     */
    suspend fun getAllMeetupEvents(): List<JsonObject>

    /** `GET /api/meetup-events/{id}/rsvp` → `body.data` if it is an object, else the body. */
    suspend fun getRsvp(eventId: Int): JsonObject?

    /** [getRsvp] with a 1 h cache in `rsvp_cache_<id>`/`rsvp_cache_ts_<id>`; [forceRefresh] bypasses it. */
    suspend fun getRsvpCached(eventId: Int, forceRefresh: Boolean = false): JsonObject?

    /** Removes the RSVP cache entries of [eventId]. */
    suspend fun clearRsvpCache(eventId: Int)

    /** `POST /api/meetup-events/{id}/rsvp {"status": attending|maybe|none}`. */
    suspend fun rsvp(eventId: Int, status: String = RSVP_ATTENDING): PortalResult

    /** Tolerant attendee lookup over several endpoints (spec 09 §8.9); `[]` if the portal has no names. */
    suspend fun getRsvpAttendees(eventId: Int): List<Attendee>

    /** `status` attending/maybe, or `going`/`is_going`/`rsvped` == true. */
    fun isGoing(r: JsonObject?): Boolean

    // ---- courses / lecturers -------------------------------------------------------------

    /** `GET /api/courses?withDetails=1` (bare list or `data`/`items`); `[]` on failure. */
    suspend fun getCourses(): List<JsonObject>

    /** `GET /api/courses/{id}` → `data ?? body`. */
    suspend fun getCourse(id: Int): JsonObject?

    /** `GET /api/lecturers?withDetails=1`; `[]` on failure. */
    suspend fun getLecturers(): List<JsonObject>

    /** `GET /api/lecturers/{id}` → `data ?? body`. */
    suspend fun getLecturer(id: Int): JsonObject?

    companion object {
        /** Secure-store key of the bearer token. */
        const val TOKEN_KEY = "portal_api_token"

        /** Secure-store key of the npub the token is bound to. */
        const val TOKEN_NPUB_KEY = "portal_api_token_npub"

        /** Prefs key prefix of the cached RSVP JSON (`rsvp_cache_<eventId>`). */
        const val RSVP_CACHE_PREFIX = "rsvp_cache_"

        /** Prefs key prefix of the RSVP cache time in epoch ms (`rsvp_cache_ts_<eventId>`). */
        const val RSVP_CACHE_TS_PREFIX = "rsvp_cache_ts_"

        /** RSVP cache lifetime, ms. */
        const val RSVP_CACHE_TTL_MS = 60L * 60 * 1000

        /** Nostr kind of the portal login event. */
        const val LOGIN_EVENT_KIND = 22242

        const val RSVP_ATTENDING = "attending"
        const val RSVP_MAYBE = "maybe"
        const val RSVP_NONE = "none"

        /**
         * Leader npub input check of the leader dialog: `startsWith("npub1") && length >= 60`.
         * Features should additionally check `Nip19.isValidNpub`.
         */
        fun looksLikeNpub(input: String): Boolean = input.startsWith("npub1") && input.length >= 60
    }
}
