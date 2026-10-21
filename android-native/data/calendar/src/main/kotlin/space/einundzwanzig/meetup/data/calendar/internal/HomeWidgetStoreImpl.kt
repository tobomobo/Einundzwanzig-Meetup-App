package space.einundzwanzig.meetup.data.calendar.internal

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetRenderer
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetSnapshot
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_BLOCK
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_DIFFICULTY
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_FEES
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_HASHRATE
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_MEETUP_CITY
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_MEETUP_COUNTDOWN
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_MEETUP_START
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_MOSCOW
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_NEWS_IS_NEW
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_NEWS_TITLE
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_PRICE_EUR
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_PRICE_FIAT
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_PRICE_SYMBOL
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_SUPPLY
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore.Companion.KEY_UPDATED
import space.einundzwanzig.meetup.data.calendar.widget.WidgetFormat
import space.einundzwanzig.meetup.data.calendar.widget.WidgetLaunchTargetStore
import space.einundzwanzig.meetup.data.calendar.widget.WidgetTarget
import java.time.Instant
import java.time.ZoneId
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class HomeWidgetStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPrefs: KeyValueStore,
    private val renderer: Optional<HomeWidgetRenderer>,
    private val time: TimeSource,
    private val log: DiagnosticLog,
    @IoDispatcher private val io: CoroutineDispatcher = Dispatchers.IO,
) : HomeWidgetStore {

    /** Local zone of the countdown (Dart `DateTime.now()` local); tests pin it. */
    internal var zoneProvider: () -> ZoneId = ZoneId::systemDefault

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(HomeWidgetStore.PREFS_FILE, Context.MODE_PRIVATE)
    }

    override suspend fun snapshot(): HomeWidgetSnapshot = withContext(io) {
        val p = prefs
        fun s(key: String): String? = runCatching { p.getString(key, null) }.getOrNull()
        HomeWidgetSnapshot(
            block = s(KEY_BLOCK) ?: WidgetFormat.DASH,
            price = s(KEY_PRICE_FIAT) ?: s(KEY_PRICE_EUR) ?: WidgetFormat.DASH,
            priceSymbol = s(KEY_PRICE_SYMBOL) ?: HomeWidgetSnapshot.DEFAULT_SYMBOL,
            moscow = s(KEY_MOSCOW) ?: HomeWidgetSnapshot.DEFAULT_MOSCOW,
            fees = s(KEY_FEES) ?: HomeWidgetSnapshot.DEFAULT_FEES,
            hashrate = s(KEY_HASHRATE) ?: WidgetFormat.DASH,
            supply = s(KEY_SUPPLY) ?: WidgetFormat.DASH,
            difficulty = s(KEY_DIFFICULTY) ?: WidgetFormat.DASH,
            updated = s(KEY_UPDATED) ?: "",
            meetupCity = s(KEY_MEETUP_CITY) ?: "",
            meetupCountdown = s(KEY_MEETUP_COUNTDOWN) ?: "",
            meetupStartEpochMs = runCatching { if (p.contains(KEY_MEETUP_START)) p.getLong(KEY_MEETUP_START, 0) else null }.getOrNull(),
            newsTitle = s(KEY_NEWS_TITLE) ?: HomeWidgetSnapshot.DEFAULT_NEWS_TITLE,
            newsIsNew = runCatching { p.getBoolean(KEY_NEWS_IS_NEW, false) }.getOrDefault(false),
        )
    }

    override suspend fun writeBitcoin(values: Map<String, String>) {
        write("Bitcoin") {
            for ((k, v) in values) if (k in HomeWidgetStore.BITCOIN_KEYS) putString(k, v)
        }
    }

    override suspend fun updateMeetup(city: String, start: Instant?) {
        val zone = zoneProvider()
        val countdown = start?.let {
            val today = Instant.ofEpochMilli(time.nowMillis()).atZone(zone).toLocalDate()
            WidgetFormat.germanCountdown(WidgetFormat.daysUntil(it, today, zone))
        } ?: ""
        write("Meetup") {
            putString(KEY_MEETUP_CITY, city)
            putString(KEY_MEETUP_COUNTDOWN, countdown)
            if (start != null) putLong(KEY_MEETUP_START, start.toEpochMilli()) else remove(KEY_MEETUP_START)
        }
    }

    override suspend fun publishLatestNews(id: String, title: String) {
        try {
            val lastSeen = appPrefs.getString(HomeWidgetStore.PREF_LAST_SEEN_NEWS_ID) ?: ""
            val isNew = id.isNotEmpty() && id != lastSeen
            appPrefs.setString(HomeWidgetStore.PREF_LATEST_NEWS_ID, id)
            appPrefs.setString(HomeWidgetStore.PREF_LATEST_NEWS_TITLE, title)
            write("News") {
                putString(KEY_NEWS_TITLE, title.ifEmpty { "News" })
                putBoolean(KEY_NEWS_IS_NEW, isNew)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "refreshNews fehlgeschlagen: $e")
        }
    }

    override suspend fun markNewsSeen() {
        try {
            val latest = appPrefs.getString(HomeWidgetStore.PREF_LATEST_NEWS_ID) ?: ""
            if (latest.isNotEmpty()) appPrefs.setString(HomeWidgetStore.PREF_LAST_SEEN_NEWS_ID, latest)
            write("News") { putBoolean(KEY_NEWS_IS_NEW, false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "markNewsSeen fehlgeschlagen: $e")
        }
    }

    private suspend fun write(label: String, block: SharedPreferences.Editor.() -> Unit) {
        try {
            withContext(io) { prefs.edit().apply(block).commit() }
            if (renderer.isPresent) renderer.get().requestUpdate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "$label-Widget-Update fehlgeschlagen: $e")
        }
    }

    private companion object {
        const val TAG = "WidgetService"
    }
}

@Singleton
internal class WidgetLaunchTargetStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher = Dispatchers.IO,
) : WidgetLaunchTargetStore {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(WidgetLaunchTargetStore.PREFS_FILE, Context.MODE_PRIVATE)

    private val lock = Any()

    override suspend fun consume(): WidgetTarget? = withContext(io) {
        synchronized(lock) {
            val p = prefs
            val raw = runCatching { p.getString(WidgetLaunchTargetStore.KEY_PENDING_TARGET, null) }.getOrNull()
                ?: return@synchronized null
            p.edit().remove(WidgetLaunchTargetStore.KEY_PENDING_TARGET).commit()
            WidgetTarget.fromWire(raw)
        }
    }

    override fun store(target: String): Boolean {
        if (target.isEmpty() || target == "home") return false
        return prefs.edit().putString(WidgetLaunchTargetStore.KEY_PENDING_TARGET, target).commit()
    }
}
