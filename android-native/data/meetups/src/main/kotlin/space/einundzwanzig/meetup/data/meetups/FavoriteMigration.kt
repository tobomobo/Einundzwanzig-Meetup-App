package space.einundzwanzig.meetup.data.meetups

import space.einundzwanzig.meetup.data.meetups.internal.dartLower
import space.einundzwanzig.meetup.data.meetups.model.Meetup

/**
 * Silent migration of legacy city-name favorites to portal IDs, run when a favorites picker opens
 * (Dart `MeetupSelectionScreen` / `MeetupSearchSheet`, spec 09 §8.4, spec 00 item 6).
 */
object FavoriteMigration {

    /**
     * Outcome of [migrate].
     *
     * @property ids the migrated favorites in their original order, without duplicates.
     * @property ambiguous legacy city names that match several meetups; they were dropped and the
     *   user has to pick again (the full-screen picker logs a warning, the sheet stays silent).
     * @property dropped values that match nothing and were dropped.
     */
    data class Result(
        val ids: List<String>,
        val ambiguous: List<String> = emptyList(),
        val dropped: List<String> = emptyList(),
    )

    /**
     * For every stored value in order: kept if a meetup has that ID; otherwise the meetups whose
     * city equals it case-insensitively (no trim): exactly one → its ID; several → dropped as
     * ambiguous; none → dropped. Duplicate IDs collapse (ordered set).
     *
     * Example: `["107", "Würzburg" (2 meetups), "Göttingen" (only meetup 107)]` → `["107"]`.
     *
     * Note: with an empty [meetups] list every value is dropped; callers must not persist the
     * result while the directory failed to load (spec 09 §14 item 11).
     */
    fun migrate(stored: List<String>, meetups: List<Meetup>): Result {
        val migrated = LinkedHashSet<String>()
        val ambiguous = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        for (value in stored) {
            if (meetups.any { it.id == value }) {
                migrated += value
                continue
            }
            val lower = value.dartLower()
            val sameCity = meetups.filter { it.city.dartLower() == lower }
            when {
                sameCity.size == 1 -> migrated += sameCity[0].id
                sameCity.size > 1 -> ambiguous += value
                else -> dropped += value
            }
        }
        return Result(migrated.toList(), ambiguous, dropped)
    }
}
