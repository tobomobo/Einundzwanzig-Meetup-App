package space.einundzwanzig.meetup.data.calendar.widget

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Everything the home-screen widget shows (spec 14 §3.1.3, spec 10 §2.10), read from
 * `HomeWidgetPreferences` with the Flutter provider's defaults. Labels ("BITCOIN", "PREIS", …,
 * and the empty-meetup texts) are localized by the widget UI.
 *
 * @property price `priceFiat`, else legacy `priceEur`, else `––`.
 * @property updated `HH:mm` of the last bitcoin write or `""` (rendered as `"Stand $updated"`).
 * @property meetupCity `""` → the UI shows "Kein Home-Meetup" / "In der App wählen".
 * @property meetupCountdown legacy German countdown string written by the app.
 * @property meetupStartEpochMs start of the next meetup (native key), for a render-time countdown.
 * @property newsTitle latest article title, default `Aktuelle Artikel`.
 */
data class HomeWidgetSnapshot(
    val block: String = WidgetFormat.DASH,
    val price: String = WidgetFormat.DASH,
    val priceSymbol: String = DEFAULT_SYMBOL,
    val moscow: String = DEFAULT_MOSCOW,
    val fees: String = DEFAULT_FEES,
    val hashrate: String = WidgetFormat.DASH,
    val supply: String = WidgetFormat.DASH,
    val difficulty: String = WidgetFormat.DASH,
    val updated: String = "",
    val meetupCity: String = "",
    val meetupCountdown: String = "",
    val meetupStartEpochMs: Long? = null,
    val newsTitle: String = DEFAULT_NEWS_TITLE,
    val newsIsNew: Boolean = false,
) {
    /** `"$price $priceSymbol"`. */
    val priceText: String get() = "$price $priceSymbol"

    /** `"Stand $updated"` or `""` (German default; localize the prefix in the UI). */
    val updatedText: String get() = if (updated.isEmpty()) "" else "Stand $updated"

    /** No next meetup configured. */
    val hasMeetup: Boolean get() = meetupCity.isNotEmpty()

    /**
     * Calendar days from [today] to the stored meetup start (`<= 0` today, `1` tomorrow), or
     * `null` when only the legacy [meetupCountdown] string is available (or no date).
     */
    fun meetupDaysFrom(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long? =
        meetupStartEpochMs?.let { ChronoUnit.DAYS.between(today, Instant.ofEpochMilli(it).atZone(zone).toLocalDate()) }

    companion object {
        /** Defaults of `MeetupWidgetProvider`. */
        const val DEFAULT_SYMBOL: String = "€"
        const val DEFAULT_MOSCOW: String = "––:––"
        const val DEFAULT_FEES: String = "–·–·–"
        const val DEFAULT_NEWS_TITLE: String = "Aktuelle Artikel"
    }
}
