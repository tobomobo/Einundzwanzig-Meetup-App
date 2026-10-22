package space.einundzwanzig.meetup.data.badges.model

import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.badges.util.DartSort

/**
 * Identity and union rules of badges (spec 02 §8.1, spec 03 §8.2). The same rule is used by
 * the store, sync, backup, the wallet cleanup and the trust score, on every device, so the
 * union of two collections is free of double scans without deleting anything.
 */
object BadgeIdentity {

    /**
     * The event a badge belongs to: `evt:<meetupEventId>`, else `sig:<sigId>`, else
     * `nd:<lower(trim(meetupName))>|yyyy-MM-dd` from the date's own fields (UTC fields for UTC
     * dates, device-zone fields for local ones). Case mapping is Dart's ([DartString]).
     */
    fun identityKey(b: MeetupBadge): String {
        if (b.meetupEventId.isNotEmpty()) return "evt:${b.meetupEventId}"
        if (b.sigId.isNotEmpty()) return "sig:${b.sigId}"
        val f = b.date.fields()
        val day = "${f.year}-${f.monthValue.toString().padStart(2, '0')}-${f.dayOfMonth.toString().padStart(2, '0')}"
        return "nd:${DartString.lower(DartString.trim(b.meetupName))}|$day"
    }

    /** [identityKey], prefixed with `org:` for organizer markers (they never collide with participant badges). */
    fun unionKey(b: MeetupBadge): String = if (b.isOrganizer) "org:${identityKey(b)}" else identityKey(b)

    /**
     * Which of two variants of the same event stays: the one with a claim, then the one with a
     * verified presence, then the older date (instant), then the earlier claim, then the smaller
     * id (UTF-16 order); on a full tie [a] (the base).
     */
    fun preferVariant(a: MeetupBadge, b: MeetupBadge): MeetupBadge {
        val aClaim = a.claimSig.isNotEmpty()
        val bClaim = b.claimSig.isNotEmpty()
        if (aClaim != bClaim) return if (aClaim) a else b
        if (a.presenceVerified != b.presenceVerified) return if (a.presenceVerified) a else b
        val byDate = a.date.compareTo(b.date)
        if (byDate != 0) return if (byDate < 0) a else b
        if (a.claimTimestamp != b.claimTimestamp) return if (a.claimTimestamp < b.claimTimestamp) a else b
        val byId = a.id.compareTo(b.id)
        if (byId != 0) return if (byId < 0) a else b
        return a
    }

    /**
     * Unites two collections per event ([unionKey]): insertion-ordered, a replaced entry keeps
     * its position, [preferVariant] decides; returns the chosen objects themselves (no copies).
     */
    fun unionByIdentity(base: List<MeetupBadge>, extra: List<MeetupBadge>): List<MeetupBadge> {
        val byKey = LinkedHashMap<String, MeetupBadge>()
        fun add(badge: MeetupBadge) {
            val key = unionKey(badge)
            val existing = byKey[key]
            byKey[key] = if (existing == null) badge else preferVariant(existing, badge)
        }
        base.forEach(::add)
        extra.forEach(::add)
        return byKey.values.toList()
    }

    /** Same content regardless of order: the sorted lists of `jsonEncode(toJson())` are equal. */
    fun sameBadges(a: List<MeetupBadge>, b: List<MeetupBadge>): Boolean {
        if (a.size != b.size) return false
        val left = DartSort.sorted(a.map { it.toJsonString() }, naturalOrder())
        val right = DartSort.sorted(b.map { it.toJsonString() }, naturalOrder())
        return left == right
    }
}
