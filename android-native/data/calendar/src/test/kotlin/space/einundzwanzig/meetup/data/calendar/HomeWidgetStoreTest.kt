package space.einundzwanzig.meetup.data.calendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.data.calendar.internal.HomeWidgetStoreImpl
import space.einundzwanzig.meetup.data.calendar.internal.WidgetLaunchTargetStoreImpl
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetRenderer
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetSnapshot
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.data.calendar.widget.WidgetLaunchTargetStore
import space.einundzwanzig.meetup.data.calendar.widget.WidgetTarget
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional

/** `HomeWidgetPreferences` continuity (fixture `home_widget_prefs`, spec 14 §3) and the router target. */
@RunWith(AndroidJUnit4::class)
class HomeWidgetStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appPrefs = InMemoryKeyValueStore()
    private var renders = 0
    private val time = FakeTime(Instant.parse("2026-03-05T10:00:00Z").toEpochMilli())

    private fun store(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) = HomeWidgetStoreImpl(
        context,
        appPrefs,
        Optional.of(HomeWidgetRenderer { renders++ }),
        time,
        testLog(),
        UnconfinedTestDispatcher(scheduler),
    ).also { it.zoneProvider = { ZoneId.of("Europe/Berlin") } }

    @Test
    fun `flutter-written values render like the fixture`() = runTest {
        context.getSharedPreferences(HomeWidgetStore.PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putString("block", "921.345").putString("priceFiat", "95.000").putString("priceSymbol", "€")
            .putString("priceEur", "95.000").putString("moscow", "10:52").putString("fees", "2·3·5")
            .putString("hashrate", "812 EH/s").putString("supply", "19.937.512").putString("difficulty", "+1.4% / 1033")
            .putString("updated", "09:41").putString("meetupCity", "Berlin").putString("meetupCountdown", "in 3 Tagen")
            .putString("newsTitle", "Neuer Artikel").putBoolean("newsIsNew", true).commit()
        val s = store(testScheduler).snapshot()
        assertEquals("921.345", s.block)
        assertEquals("95.000 €", s.priceText)
        assertEquals("10:52", s.moscow)
        assertEquals("2·3·5", s.fees)
        assertEquals("812 EH/s", s.hashrate)
        assertEquals("19.937.512", s.supply)
        assertEquals("+1.4% / 1033", s.difficulty)
        assertEquals("Stand 09:41", s.updatedText)
        assertEquals("Berlin", s.meetupCity)
        assertEquals("in 3 Tagen", s.meetupCountdown)
        assertEquals("Neuer Artikel", s.newsTitle)
        assertTrue(s.newsIsNew)
        assertNull(s.meetupStartEpochMs)
    }

    @Test
    fun `empty store renders the defaults`() = runTest {
        val s = store(testScheduler).snapshot()
        assertEquals(HomeWidgetSnapshot(), s)
        assertEquals("––", s.block)
        assertEquals("–– €", s.priceText)
        assertEquals("––:––", s.moscow)
        assertEquals("–·–·–", s.fees)
        assertEquals("", s.updatedText)
        assertFalse(s.hasMeetup)
        assertEquals("Aktuelle Artikel", s.newsTitle)
        assertFalse(s.newsIsNew)
    }

    @Test
    fun `price falls back to the legacy EUR key`() = runTest {
        context.getSharedPreferences(HomeWidgetStore.PREFS_FILE, Context.MODE_PRIVATE).edit().putString("priceEur", "90.000").commit()
        assertEquals("90.000 €", store(testScheduler).snapshot().priceText)
    }

    @Test
    fun `meetup, bitcoin and news writers`() = runTest {
        val store = store(testScheduler)
        val start = Instant.parse("2026-03-10T18:00:00Z")
        store.updateMeetup("Berlin", start)
        var s = store.snapshot()
        assertEquals("in 5 Tagen", s.meetupCountdown)
        assertEquals(start.toEpochMilli(), s.meetupStartEpochMs)
        assertEquals(4L, s.meetupDaysFrom(LocalDate.of(2026, 3, 6), ZoneId.of("UTC")))
        store.updateMeetup("", null)
        s = store.snapshot()
        assertEquals("", s.meetupCity)
        assertEquals("", s.meetupCountdown)
        assertNull(s.meetupStartEpochMs)

        store.writeBitcoin(mapOf("block" to "1.000", "unknown" to "x"))
        assertEquals("1.000", store.snapshot().block)
        assertFalse(context.getSharedPreferences(HomeWidgetStore.PREFS_FILE, Context.MODE_PRIVATE).contains("unknown"))

        store.publishLatestNews("n1", "Titel")
        assertTrue(store.snapshot().newsIsNew)
        assertEquals("n1", appPrefs.getString(HomeWidgetStore.PREF_LATEST_NEWS_ID))
        store.markNewsSeen()
        assertFalse(store.snapshot().newsIsNew)
        assertEquals("n1", appPrefs.getString(HomeWidgetStore.PREF_LAST_SEEN_NEWS_ID))
        store.publishLatestNews("n1", "")
        s = store.snapshot()
        assertFalse(s.newsIsNew)
        assertEquals("News", s.newsTitle)
        assertEquals(6, renders)
    }

    @Test
    fun `launch target is consumed once and home is never stored`() = runTest {
        val targets = WidgetLaunchTargetStoreImpl(context, UnconfinedTestDispatcher(testScheduler))
        assertFalse(targets.store("home"))
        assertFalse(targets.store(""))
        assertNull(targets.consume())
        assertTrue(targets.store("meetup"))
        assertEquals(WidgetTarget.MEETUP, targets.consume())
        assertNull(targets.consume())
        context.getSharedPreferences(WidgetLaunchTargetStore.PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putString(WidgetLaunchTargetStore.KEY_PENDING_TARGET, "evil").commit()
        assertNull(targets.consume())
        assertFalse(context.getSharedPreferences(WidgetLaunchTargetStore.PREFS_FILE, Context.MODE_PRIVATE).contains("pending_target"))
    }
}
