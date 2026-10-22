package space.einundzwanzig.meetup.feature.admin

import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.meetups.model.Meetup

/** One row of the portal meetup picker (spec 05 §2.2.2 "Portal picker list details"). */
data class PickerMeetup(
    val meetup: Meetup,
    val isMine: Boolean,
) {
    /** Subtitle: the group name if non-empty and different from the city (case-insensitive). */
    val subtitle: String?
        get() = meetup.name.takeIf { it.isNotEmpty() && lower(it) != lower(meetup.city) }

    /** `place` (green) unless both coordinates are exactly 0 (`location_off`). */
    val hasCoordinates: Boolean get() = !(meetup.lat == 0.0 && meetup.lng == 0.0)
}

/** Pure ordering and filtering of `_pickAnyPortalMeetup`. */
object PortalMeetupPicker {

    /** `isMine(m) = mine ∋ m.name.trim().lower || mine ∋ m.city.trim().lower`. */
    fun isMine(meetup: Meetup, mine: Set<String>): Boolean =
        lower(DartString.trim(meetup.name)) in mine || lower(DartString.trim(meetup.city)) in mine

    /** Own meetups first, each part sorted by `city.lowercase()` (stable). */
    fun order(all: List<Meetup>, mine: Set<String>): List<PickerMeetup> {
        val (own, others) = all.partition { isMine(it, mine) }
        val byCity = compareBy<Meetup> { lower(it.city) }
        return own.sortedWith(byCity).map { PickerMeetup(it, true) } +
            others.sortedWith(byCity).map { PickerMeetup(it, false) }
    }

    /** `q = search.trim().lower`; empty → all, else city or name contains `q`. */
    fun filter(rows: List<PickerMeetup>, search: String): List<PickerMeetup> {
        val q = lower(DartString.trim(search))
        if (q.isEmpty()) return rows
        return rows.filter { lower(it.meetup.city).contains(q) || lower(it.meetup.name).contains(q) }
    }
}

private fun lower(s: String): String = DartString.lower(s)
