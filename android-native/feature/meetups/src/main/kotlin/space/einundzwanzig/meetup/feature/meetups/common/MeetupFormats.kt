package space.einundzwanzig.meetup.feature.meetups.common

import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.ui.format.Formats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure text rules of the meetup screens (spec 09 §2). */
internal object MeetupFormats {

    private fun Int.pad2(): String = toString().padStart(2, '0')

    /** `HH:mm`. */
    fun hhmm(t: ZonedDateTime): String = "${t.hour.pad2()}:${t.minute.pad2()}"

    /** `dd.MM.yyyy`. */
    fun ddMMyyyy(d: LocalDate): String = Formats.datePadded(d)

    /** `dd.MM.` (range chip, `nbDateFromTo`). */
    fun ddMM(d: LocalDate): String = "${d.dayOfMonth.pad2()}.${d.monthValue.pad2()}."

    /**
     * Organizer date (spec 09 §2.1 `fmtStart`): `dd.MM.yyyy  HH:mm` (two spaces) of
     * [local], or `null` when the raw value was unparsable (the row then shows the raw string).
     */
    fun portalStart(local: ZonedDateTime?): String? = local?.let { Formats.dateTimePadded(it.toLocalDateTime()) }

    /**
     * Meetup details date chip (spec 09 §2.3): `"<weekday>, dd.MM. HH:mm"`; [weekday] is the
     * short weekday label (`Mo`…`So` in German, from native string resources).
     */
    fun detailsChip(t: ZonedDateTime, weekday: String): String =
        "$weekday, ${t.dayOfMonth.pad2()}.${t.monthValue.pad2()}. ${hhmm(t)}"

    /** Meetup details sheet (spec 09 §2.3): `dd.MM.yyyy, HH:mm` (the "Uhr" suffix comes from resources). */
    fun detailsSheetDate(t: ZonedDateTime): String = "${ddMMyyyy(t.toLocalDate())}, ${hhmm(t)}"

    /** Kind of the nearby card date (spec 09 §2.2 `formatEventDate`). */
    sealed interface NearbyDate {
        val time: String

        data class Today(override val time: String) : NearbyDate
        data class Tomorrow(override val time: String) : NearbyDate

        /** `EEE, dd.MM.yyyy · HH:mm` in the app locale (spec 09 §14 item 26). */
        data class Other(override val time: String, val text: String) : NearbyDate
    }

    fun nearbyDate(start: Instant, now: Instant, zone: ZoneId, locale: Locale): NearbyDate {
        val t = start.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val time = hhmm(t)
        return when (t.toLocalDate()) {
            today -> NearbyDate.Today(time)
            today.plusDays(1) -> NearbyDate.Tomorrow(time)
            else -> NearbyDate.Other(time, DateTimeFormatter.ofPattern("EEE, dd.MM.yyyy · HH:mm", locale).format(t))
        }
    }

    /** `nbKmAway` argument: `toStringAsFixed(distance < 10 ? 1 : 0)` (spec 09 §8.13). */
    fun km(distanceKm: Double): String = distanceKm.toStringAsFixed(if (distanceKm < 10) 1 else 0)

    /** STANDORT card: `"${lat.toStringAsFixed(4)}, ${lng.toStringAsFixed(4)}"`. */
    fun coordinates4(lat: Double, lng: Double): String = "${lat.toStringAsFixed(4)}, ${lng.toStringAsFixed(4)}"

    /** "Route" link (spec 09 §2.3) with Dart `double.toString()` formatting. */
    fun googleMapsRoute(lat: Double, lng: Double): String =
        "https://www.google.com/maps/search/?api=1&query=${lat.toDartString()},${lng.toDartString()}"

    /** Twitter / X link (spec 09 §2.3). */
    fun twitterUrl(username: String): String = "https://twitter.com/" + username.replace("@", "")

    /**
     * Community link: a scheme-less `t.me/…` value gets `https://` (spec 09 §14 item 16); the
     * safe launcher still enforces http(s).
     */
    fun communityUrl(link: String): String {
        val v = link.trim()
        return if (v.startsWith("t.me/")) "https://$v" else v
    }
}
