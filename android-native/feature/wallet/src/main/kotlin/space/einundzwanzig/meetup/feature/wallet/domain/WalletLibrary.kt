package space.einundzwanzig.meetup.feature.wallet.domain

import androidx.compose.runtime.Immutable
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.navigation.BadgeCollection
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.badges.util.DartSort
import java.util.IdentityHashMap

/**
 * One wallet group (spec 03 §8.1): a meetup, a year, or the organizer markers.
 *
 * @property badges newest first (date descending by instant).
 */
@Immutable
internal data class BadgeGroup(
    val key: String,
    val badges: List<MeetupBadge>,
) {
    val isOrganizer: Boolean get() = key == WalletLibrary.ORGANIZER_GROUP_KEY

    /** The first badge (newest first) with a cover URL, else the first badge (collection card crest). */
    val cover: MeetupBadge get() = badges.firstOrNull { it.coverUrl.isNotEmpty() } ?: badges.first()
}

/** Pure wallet rules: filter, grouping, duplicates, numbering (spec 03 §8.1, §8.2, §8.5, §8.9). */
internal object WalletLibrary {
    /** Group key of the organizer markers, always placed last. */
    const val ORGANIZER_GROUP_KEY: String = BadgeCollection.ORGANIZER_GROUP_KEY

    /** Search field visible from 6 badges on. */
    fun showSearch(count: Int): Boolean = count >= 6

    /** Compact toggle visible above 6 badges. */
    fun showCompactToggle(count: Int): Boolean = count > 6

    /** Collection-card dots are shown up to 24 badges. */
    fun showCountDots(count: Int): Boolean = count <= 24

    /** Case-insensitive substring match on `meetupName` with Dart `trim().toLowerCase()`. */
    fun filter(all: List<MeetupBadge>, query: String): List<MeetupBadge> {
        val q = DartString.lower(DartString.trim(query))
        if (q.isEmpty()) return all
        return all.filter { DartString.lower(it.meetupName).contains(q) }
    }

    /** The group key of [badge] (§8.1). */
    fun groupKey(badge: MeetupBadge, groupByMeetup: Boolean): String = when {
        badge.isOrganizer -> ORGANIZER_GROUP_KEY
        groupByMeetup -> badge.meetupName.dartTrim().ifEmpty { "?" }
        else -> badge.date.year.toString()
    }

    /** Dart `_buildGroups`: grouped, each newest first, ordered, organizer group last. */
    fun groups(filtered: List<MeetupBadge>, groupByMeetup: Boolean): List<BadgeGroup> {
        val map = LinkedHashMap<String, MutableList<MeetupBadge>>()
        for (b in filtered) map.getOrPut(groupKey(b, groupByMeetup)) { mutableListOf() }.add(b)
        val entries = map.map { (key, list) ->
            BadgeGroup(key, DartSort.sorted(list) { a, b -> b.date.compareTo(a.date) })
        }
        val ordered = if (groupByMeetup) {
            DartSort.sorted(entries) { a, b ->
                val bySize = b.badges.size.compareTo(a.badges.size)
                if (bySize != 0) bySize else a.key.compareTo(b.key)
            }
        } else {
            DartSort.sorted(entries) { a, b -> b.key.compareTo(a.key) }
        }
        val organizer = ordered.firstOrNull { it.isOrganizer } ?: return ordered
        return ordered.filter { !it.isOrganizer } + organizer
    }

    /**
     * The badges of the collection screen for [groupKey] (Dart: the group sorted ascending by
     * date, then reversed), built from the collection filtered with [query]. Empty if the group
     * no longer exists.
     */
    fun collection(all: List<MeetupBadge>, groupKey: String, query: String): List<MeetupBadge> {
        val group = groups(filter(all, query), groupByMeetup = true).firstOrNull { it.key == groupKey }
            ?: return emptyList()
        return DartSort.sorted(group.badges) { a, b -> a.date.compareTo(b.date) }.reversed()
    }

    /** Badges that the union rule drops, by object identity (§8.2). */
    fun duplicates(all: List<MeetupBadge>): List<MeetupBadge> {
        val kept = identitySet(BadgeIdentity.unionByIdentity(all, emptyList()))
        return all.filter { it !in kept }
    }

    /** [all] without the exact objects in [dups] (identity), keeping the order. */
    fun withoutDuplicates(all: List<MeetupBadge>, dups: List<MeetupBadge>): List<MeetupBadge> {
        val drop = identitySet(dups)
        return all.filter { it !in drop }
    }

    /** "Name (Nx)" rows of the cleanup dialog, in first-occurrence order. */
    fun cleanupSummary(dups: List<MeetupBadge>): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (b in dups) {
            val name = b.meetupName.dartTrim().ifEmpty { "?" }
            out[name] = (out[name] ?: 0) + 1
        }
        return out
    }

    /** Position of this exact object in the full collection (`#n` = index + 1), or -1. */
    fun globalIndex(all: List<MeetupBadge>, badge: MeetupBadge): Int = all.indexOfFirst { it === badge }

    /**
     * Resolves a [space.einundzwanzig.meetup.core.navigation.BadgeDetails] key: the badge at
     * [index] if its union key matches, else the first badge with [unionKey].
     */
    fun resolve(all: List<MeetupBadge>, unionKey: String, index: Int): MeetupBadge? {
        all.getOrNull(index)?.let { if (BadgeIdentity.unionKey(it) == unionKey) return it }
        return all.firstOrNull { BadgeIdentity.unionKey(it) == unionKey }
    }

    /** Badges with a location (`lat != 0 || lng != 0`) for the world map. */
    fun located(all: List<MeetupBadge>): List<MeetupBadge> = all.filter { it.lat != 0.0 || it.lng != 0.0 }

    /** Number of distinct meetup names ("Städte"). */
    fun cityCount(located: List<MeetupBadge>): Int = located.map { it.meetupName }.toSet().size

    /** Number of union keys of [all] that are not in [before] (merge result). */
    fun countAdded(before: Set<String>, after: List<MeetupBadge>): Int =
        after.count { BadgeIdentity.unionKey(it) !in before }

    private fun identitySet(items: List<MeetupBadge>): MutableSet<MeetupBadge> =
        java.util.Collections.newSetFromMap(IdentityHashMap<MeetupBadge, Boolean>()).apply { addAll(items) }
}
