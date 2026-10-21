package space.einundzwanzig.meetup.data.calendar.model

import space.einundzwanzig.meetup.data.calendar.internal.dartLower
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A NIP-52 calendar event (kind 31923 time-based / 31922 date-based) with the app's badge tags
 * (Dart `NostrCalendarEvent`, spec 10 §3.2). Parse with
 * [space.einundzwanzig.meetup.data.calendar.nostr.NostrCalendarCodec.parse].
 *
 * @property pubkey author hex as received (normally lowercase).
 * @property start start instant. For all-day events: local midnight of [startDate] in the zone
 *   the event was parsed in.
 * @property startDate the `YYYY-MM-DD` date of an all-day event (`null` for kind 31923). Kept so
 *   republishing (issuers, cancel) emits the same date in any device zone.
 * @property endDate the end date of an all-day event, if any.
 * @property fromApp first `client` tag is `einundzwanzig-meetup-app` (community mode filter).
 * @property status first `status` tag (`planned`, `cancelled`, other or `""`).
 * @property badgeEnabled `["badge","true"]` (case-insensitive value).
 * @property lat latitude of the non-standard `["g", lat, lng]` tag, 0 if absent.
 * @property issuers hex keys of `["p", hex, "", "issuer"]` tags (64 chars, case as received).
 * @property formerIssuers hex → unix time until which a removed helper could issue.
 * @property createdAt `created_at` (0 if it was not an int).
 */
data class NostrCalendarEvent(
    val id: String,
    val pubkey: String,
    val dTag: String,
    val title: String,
    val description: String,
    val location: String,
    val start: Instant,
    val end: Instant?,
    val allDay: Boolean,
    val kind: Int,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val fromApp: Boolean = false,
    val status: String = "",
    val badgeEnabled: Boolean = false,
    val badgeImageUrl: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val issuers: List<String> = emptyList(),
    val formerIssuers: Map<String, Long> = emptyMap(),
    val createdAt: Long = 0,
) {
    /** `status == "cancelled"`: cancelled events are never shown. */
    val isCancelled: Boolean get() = status == STATUS_CANCELLED

    /** NIP-33 address `<kind>:<pubkey>:<d>` (badge `m` ids, RSVPs, event chat). */
    val address: String get() = "$kind:$pubkey:$dTag"

    /** `lat == 0 && lng == 0`: no map location, so no badge session is possible. */
    val hasNoLocation: Boolean get() = lat == 0.0 && lng == 0.0

    /** Local date of the start (Dart `day`); the stored date for all-day events. */
    fun day(zone: ZoneId = ZoneId.systemDefault()): LocalDate = startDate ?: start.atZone(zone).toLocalDate()

    /** Local end date (the start date when there is no end). */
    fun lastDay(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        endDate ?: end?.atZone(zone)?.toLocalDate() ?: day(zone)

    /**
     * May [pubkeyHex] issue badges for this event (spec 10 §8.8)? The creator always may, current
     * issuers may (case-insensitive). Former issuers count only with [atEpoch] (the signed issue
     * time) and only up to their removal time inclusive. UI checks pass no epoch.
     */
    fun isIssuer(pubkeyHex: String, atEpoch: Long? = null): Boolean {
        if (pubkeyHex.isEmpty()) return false
        val k = pubkeyHex.dartLower()
        if (k == pubkey.dartLower()) return true
        if (issuers.any { it.dartLower() == k }) return true
        if (atEpoch == null) return false
        return formerIssuers.any { (key, until) -> key.dartLower() == k && atEpoch <= until }
    }

    /**
     * Badge window `[local midnight of the start day, local midnight after the end day)` in
     * [zone] (spec 10 §8.8); always `false` without [badgeEnabled]. Computed on dates, so it does
     * not shift by an hour across DST like the Dart `Duration(days: 1)` arithmetic.
     */
    fun isBadgeWindowOpen(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!badgeEnabled) return false
        val from = day(zone).atStartOfDay(zone).toInstant()
        val to = lastDay(zone).plusDays(1).atStartOfDay(zone).toInstant()
        return !now.isBefore(from) && now.isBefore(to)
    }

    companion object {
        /** NIP-52 time-based calendar event. */
        const val KIND_TIME: Int = 31923

        /** NIP-52 date-based (all-day) calendar event. */
        const val KIND_DATE: Int = 31922

        /** Value of the `client` tag the app writes (community mode depends on it). */
        const val CLIENT: String = "einundzwanzig-meetup-app"

        /** `status` value of a cancelled event. */
        const val STATUS_CANCELLED: String = "cancelled"

        /** Content of the NIP-09 deletion request sent on cancel (wire value, keep exactly). */
        const val DELETION_CONTENT: String = "Termin abgesagt"
    }
}

/** NIP-52 RSVP status (kind 31925); [wire] is the tag value. */
enum class RsvpStatus(val wire: String) {
    ACCEPTED("accepted"),
    DECLINED("declined"),
    TENTATIVE("tentative"),
    ;

    companion object {
        /** The status with wire value [value] (exact, case-sensitive), or `null`. */
        fun fromWire(value: String): RsvpStatus? = entries.firstOrNull { it.wire == value }
    }
}
