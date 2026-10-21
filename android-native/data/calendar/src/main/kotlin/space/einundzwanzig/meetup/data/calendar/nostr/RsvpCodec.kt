package space.einundzwanzig.meetup.data.calendar.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.data.calendar.internal.dartLongValue
import space.einundzwanzig.meetup.data.calendar.internal.dartLower
import space.einundzwanzig.meetup.data.calendar.internal.dartToString
import space.einundzwanzig.meetup.data.calendar.model.RsvpStatus

/** NIP-52 RSVP (kind 31925) wire format of `lib/services/event_rsvp_service.dart` (spec 10 §6.7). */
object RsvpCodec {

    /** NIP-52 RSVP kind. */
    const val KIND_RSVP: Int = 31925

    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val EDGE_DASHES = Regex("^-+|-+$")

    /**
     * Deterministic d-tag of the RSVP to [eventAddress]: lowercase, every run of non-`[a-z0-9]`
     * characters → `-`, leading/trailing dashes removed, prefixed `rsvp-`. A second answer
     * therefore replaces the first.
     */
    fun dTagFor(eventAddress: String): String {
        val clean = eventAddress.dartLower().replace(NON_ALNUM, "-").replace(EDGE_DASHES, "")
        return "rsvp-$clean"
    }

    /** `[["a", address], ["d", dTag], ["status", status], ["p", author]?]` (no `p` for an empty author). */
    fun tags(eventAddress: String, eventAuthorPubkey: String, status: RsvpStatus): List<List<String>> = buildList {
        add(listOf("a", eventAddress))
        add(listOf("d", dTagFor(eventAddress)))
        add(listOf("status", status.wire))
        if (eventAuthorPubkey.isNotEmpty()) add(listOf("p", eventAuthorPubkey))
    }

    /** One parsed own RSVP. */
    data class Entry(val address: String, val status: RsvpStatus, val createdAt: Long)

    /**
     * Dart `loadMine` per event: `created_at` must be an int; the LAST `a` and the LAST `status`
     * tag (each with ≥ 2 elements) win; empty address or unknown status → `null`.
     */
    fun parse(event: JsonObject): Entry? {
        val ts = event["created_at"].dartLongValue() ?: return null
        val tags = event["tags"] as? JsonArray ?: return null
        var address = ""
        var status = ""
        for (t in tags) {
            val arr = t as? JsonArray ?: continue
            if (arr.size < 2) continue
            val name = arr[0].dartToString()
            if (name == "a") address = arr[1].dartToString()
            if (name == "status") status = arr[1].dartToString()
        }
        if (address.isEmpty()) return null
        val parsed = RsvpStatus.fromWire(status) ?: return null
        return Entry(address, parsed, ts)
    }

    /** Newest entry per address (strictly greater `created_at` replaces; first seen wins ties). */
    fun newestPerAddress(entries: Iterable<Entry>): Map<String, RsvpStatus> {
        val newest = LinkedHashMap<String, Entry>()
        for (e in entries) {
            val known = newest[e.address]
            if (known == null || e.createdAt > known.createdAt) newest[e.address] = e
        }
        return newest.mapValues { it.value.status }
    }
}
