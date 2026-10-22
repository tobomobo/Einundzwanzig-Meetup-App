package space.einundzwanzig.meetup.data.meetups

import space.einundzwanzig.meetup.data.meetups.internal.dartLower
import space.einundzwanzig.meetup.data.meetups.model.Meetup

/**
 * Row logic of the MeetupSearchSheet (Dart `lib/widgets/meetup_search_sheet.dart`, spec 09 §2.5),
 * shared by every feature that shows the sheet. Case-insensitive comparisons use Dart
 * `toLowerCase` semantics (spec 00 item 12).
 */
object MeetupSearchRows {

    /** Meetups whose `city` or `name` contains [query] (Dart lower case); empty query → all. */
    fun filter(meetups: List<Meetup>, query: String): List<Meetup> {
        if (query.isEmpty()) return meetups
        val q = query.dartLower()
        return meetups.filter { it.city.dartLower().contains(q) || it.name.dartLower().contains(q) }
    }

    /** Number of meetups in [all] whose city equals [city] (Dart lower case, no trim). */
    fun sameCityCount(city: String, all: List<Meetup>): Int {
        val lower = city.dartLower()
        return all.count { it.city.dartLower() == lower }
    }

    /** Row title: the name when several meetups share the city and the name is set, else the city. */
    fun title(meetup: Meetup, all: List<Meetup>): String =
        if (sameCityCount(meetup.city, all) > 1 && meetup.name.isNotEmpty()) meetup.name else meetup.city

    /** Row subtitle: the name if it is set and differs from the city ([Meetup.groupName]). */
    fun subtitle(meetup: Meetup): String? = meetup.groupName
}
