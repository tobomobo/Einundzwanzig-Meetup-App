package space.einundzwanzig.meetup.data.calendar.widget

import java.time.Instant

/**
 * The home-screen widget state in the Flutter-era `SharedPreferences("HomeWidgetPreferences")`
 * file with the exact `home_widget` keys (spec 14 §3.1.3/§3.2), so placed widgets keep their
 * values across the upgrade. After every write the optional [HomeWidgetRenderer] is asked to
 * re-render (the app binds it to Glance `updateAll`).
 *
 * Writers: the bitcoin domain (via an app-side `BitcoinWidgetSink` that calls [writeBitcoin]),
 * Home favorites ([updateMeetup], usually through `MyEventsService.loadFavoriteCards`), News
 * ([publishLatestNews] on Home start / refresh, [markNewsSeen] when News opens).
 */
interface HomeWidgetStore {

    /** Current values with the provider's defaults. */
    suspend fun snapshot(): HomeWidgetSnapshot

    /**
     * Writes the bitcoin strings (`block`, `priceFiat`, `priceSymbol`, `priceEur`, `moscow`,
     * `fees`, `hashrate`, `supply`, `difficulty`, `updated` — e.g. `BitcoinWidgetValues.toMap()`
     * of `:data:mempool`). Unknown keys are ignored.
     */
    suspend fun writeBitcoin(values: Map<String, String>)

    /**
     * Next meetup: `meetupCity` = [city] (empty → "Kein Home-Meetup"), `meetupStartEpochMs` =
     * [start] (removed when `null`), legacy `meetupCountdown` = German countdown relative to
     * today (`""` without date).
     */
    suspend fun updateMeetup(city: String, start: Instant?)

    /**
     * Dart `refreshNews` after the fetch: `isNew = id.isNotEmpty && id != widget_last_seen_news_id`;
     * stores `widget_latest_news_id`/`_title` (app prefs) and widget `newsTitle` (title or `News`)
     * and `newsIsNew`.
     */
    suspend fun publishLatestNews(id: String, title: String)

    /** Dart `markNewsSeen`: last seen = latest id (if any), `newsIsNew = false`. */
    suspend fun markNewsSeen()

    companion object {
        /** SharedPreferences file of `home_widget` 0.6.0 (no `flutter.` prefix). */
        const val PREFS_FILE: String = "HomeWidgetPreferences"

        const val KEY_BLOCK: String = "block"
        const val KEY_PRICE_FIAT: String = "priceFiat"
        const val KEY_PRICE_SYMBOL: String = "priceSymbol"
        const val KEY_PRICE_EUR: String = "priceEur"
        const val KEY_MOSCOW: String = "moscow"
        const val KEY_FEES: String = "fees"
        const val KEY_HASHRATE: String = "hashrate"
        const val KEY_SUPPLY: String = "supply"
        const val KEY_DIFFICULTY: String = "difficulty"
        const val KEY_UPDATED: String = "updated"
        const val KEY_MEETUP_CITY: String = "meetupCity"
        const val KEY_MEETUP_COUNTDOWN: String = "meetupCountdown"

        /** Native addition (spec 14 §3.2): start of the next meetup, epoch ms (Long). */
        const val KEY_MEETUP_START: String = "meetupStartEpochMs"
        const val KEY_NEWS_TITLE: String = "newsTitle"
        const val KEY_NEWS_IS_NEW: String = "newsIsNew"

        /** The string keys [writeBitcoin] accepts. */
        val BITCOIN_KEYS: Set<String> = setOf(
            KEY_BLOCK, KEY_PRICE_FIAT, KEY_PRICE_SYMBOL, KEY_PRICE_EUR, KEY_MOSCOW, KEY_FEES,
            KEY_HASHRATE, KEY_SUPPLY, KEY_DIFFICULTY, KEY_UPDATED,
        )

        /** App prefs (shared `KeyValueStore`) of the news "NEU" logic. */
        const val PREF_LAST_SEEN_NEWS_ID: String = "widget_last_seen_news_id"
        const val PREF_LATEST_NEWS_ID: String = "widget_latest_news_id"
        const val PREF_LATEST_NEWS_TITLE: String = "widget_latest_news_title"
    }
}

/**
 * Re-renders all placed widgets (the app binds it, e.g. `MeetupWidget().updateAll(context)`).
 * Optional: without a binding writes are only stored.
 */
fun interface HomeWidgetRenderer {
    /** Requests a re-render; must not throw. */
    suspend fun requestUpdate()
}

/**
 * The tap target the widget router left for the app (`e21_widget` / `pending_target`, spec 14
 * §3.1.5): MIUI replays stale launch intents, so the target is never read from the intent.
 */
interface WidgetLaunchTargetStore {

    /** Reads AND removes the pending target (commit). Only `bitcoin`, `meetup`, `news` are returned. */
    fun consume(): WidgetTarget?

    /**
     * Router side: stores [target] with `commit()` unless it is empty or `home`.
     * @return `true` if something was stored.
     */
    fun store(target: String): Boolean

    companion object {
        /** SharedPreferences file of the router (native, not Flutter). */
        const val PREFS_FILE: String = "e21_widget"

        /** Key of the pending target. */
        const val KEY_PENDING_TARGET: String = "pending_target"
    }
}

/** Closed set of widget tap targets the app routes (spec 10 §9 item 10). */
enum class WidgetTarget(val wire: String) {
    BITCOIN("bitcoin"),
    MEETUP("meetup"),
    NEWS("news"),
    ;

    companion object {
        /** The target for [wire], `null` for `home`, empty or unknown values. */
        fun fromWire(wire: String?): WidgetTarget? = entries.firstOrNull { it.wire == wire }
    }
}
