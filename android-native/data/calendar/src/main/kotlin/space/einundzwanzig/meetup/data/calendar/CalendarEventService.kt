package space.einundzwanzig.meetup.data.calendar

import space.einundzwanzig.meetup.data.calendar.model.CalendarEventDraft
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent

/**
 * NIP-52 calendar events on Nostr (Dart `CalendarEventService`, spec 10 §6.1–§6.6). Reads verify
 * every event (id + BIP-340) and match it against the sent filter; writes are signed through the
 * identity domain's `SigningService` (local key, Amber or NIP-46). Never throws for relay or
 * signing problems (only cancellation).
 */
interface CalendarEventService {

    /**
     * Calendar events (kinds 31923/31922, `limit` per relay).
     *
     * Community mode (default): only [CalendarRelays.COMMUNITY] and only events with the app's
     * `client` tag. Worldwide: all active relays, all events. Per `pubkey:d:kind` the version with
     * the newest `created_at` wins (cancelled versions included), then cancelled events are
     * dropped; sorted by start.
     */
    suspend fun fetchEvents(limit: Int = DEFAULT_LIMIT, worldwide: Boolean = false): List<NostrCalendarEvent>

    /**
     * One event by its address `<kind>:<pubkey>:<d>` from `{community} ∪ active relays` in
     * parallel: newest `created_at` (≤ now + 600 s) of an exact author/d/kind match wins across
     * all relays (ties: later relay). `null` if the address is malformed, nothing was found or
     * the newest version is cancelled. Used by My events and event-badge verification.
     */
    suspend fun fetchByAddress(address: String): NostrCalendarEvent?

    /**
     * Signs and publishes a calendar event built from [draft] with the exact Dart tag layout
     * (§6.3). [existingDTag] replaces an existing event (same d-tag); otherwise a fresh random
     * 32-hex d-tag is used.
     *
     * @return the number of relays (`active ∪ community`, sequential) the event was sent to;
     *   0 on any failure (signing, no relay reachable).
     */
    suspend fun publishEvent(draft: CalendarEventDraft, existingDTag: String? = null): Int

    /**
     * Creator only (`SigningService.pubkeyHex() == event.pubkey`, exact): republishes [event]
     * with [newIssuersHex] (lowercased, 64 chars, creator removed, deduplicated in order).
     * Dropped issuers become `former_issuer` with the current unix time; re-added ones lose that
     * mark. `true` if at least one relay took it.
     */
    suspend fun updateIssuers(event: NostrCalendarEvent, newIssuersHex: List<String>): Boolean

    /**
     * Creator only: replaces [event] by a `status=cancelled` version and sends a NIP-09 deletion
     * request (kind 5, `e`/`a`/`k`, content `Termin abgesagt`, best effort). `true` if at least
     * one relay took the replacement.
     */
    suspend fun cancelEvent(event: NostrCalendarEvent): Boolean

    companion object {
        /** Default REQ `limit`. */
        const val DEFAULT_LIMIT: Int = 200
    }
}
