package space.einundzwanzig.meetup.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Widget routing of spec 10 §2.10 / spec 12 §2.5.10: `homewidget://<target>` (or extra
 * `open=<target>`), targets news / bitcoin / meetup; `home` and `refresh` do not navigate.
 */
class DeepLinksTest {

    @Test
    fun `news area opens news and marks it read`() {
        assertEquals(News(markAsRead = true), DeepLinks.parse("homewidget://news"))
    }

    @Test
    fun `bitcoin area opens the dashboard`() {
        assertSame(BitcoinDashboard, DeepLinks.parse("homewidget://bitcoin"))
    }

    @Test
    fun `meetup area opens the meetup calendar for the next favorite`() {
        assertEquals(MeetupCalendar(preselectNextFavorite = true), DeepLinks.parse("homewidget://meetup"))
    }

    @Test
    fun `logo and refresh do not navigate`() {
        assertNull(DeepLinks.parse("homewidget://home"))
        assertNull(DeepLinks.parse("homewidget://refresh"))
    }

    @Test
    fun `unknown targets do not navigate`() {
        assertNull(DeepLinks.parse("homewidget://settings"))
        assertNull(DeepLinks.parse("homewidget://"))
    }

    @Test
    fun `target match is case-sensitive like the Dart switch`() {
        assertNull(DeepLinks.parse("homewidget://NEWS"))
        assertNull(DeepLinks.parse("homewidget://Bitcoin"))
    }

    @Test
    fun `scheme match is case-insensitive`() {
        assertSame(BitcoinDashboard, DeepLinks.parse("HomeWidget://bitcoin"))
    }

    @Test
    fun `path query and fragment are ignored`() {
        assertSame(BitcoinDashboard, DeepLinks.parse("homewidget://bitcoin/"))
        assertEquals(News(markAsRead = true), DeepLinks.parse("homewidget://news?from=widget"))
        assertEquals(MeetupCalendar(preselectNextFavorite = true), DeepLinks.parse("homewidget://meetup#x"))
        assertSame(BitcoinDashboard, DeepLinks.parse("  homewidget://bitcoin  "))
    }

    @Test
    fun `other schemes and malformed input do not navigate`() {
        assertNull(DeepLinks.parse("https://news"))
        assertNull(DeepLinks.parse("nostr:npub1xyz"))
        assertNull(DeepLinks.parse("homewidget:news"))
        assertNull(DeepLinks.parse("homewidget:/news"))
        assertNull(DeepLinks.parse("news"))
        assertNull(DeepLinks.parse(""))
        assertNull(DeepLinks.parse("://news"))
        assertNull(DeepLinks.parse(":::"))
    }

    @Test
    fun `host is extracted like Uri getHost`() {
        assertEquals("news", DeepLinks.widgetTargetOf("homewidget://news"))
        assertEquals("news", DeepLinks.widgetTargetOf("homewidget://user@news:80/path"))
        assertNull(DeepLinks.widgetTargetOf("homewidget:///path"))
        assertNull(DeepLinks.widgetTargetOf("homewidget://"))
    }

    @Test
    fun `stored pending targets and open extras map like the URI host`() {
        assertEquals(News(markAsRead = true), DeepLinks.forWidgetTarget("news"))
        assertSame(BitcoinDashboard, DeepLinks.forWidgetTarget("bitcoin"))
        assertEquals(MeetupCalendar(preselectNextFavorite = true), DeepLinks.forWidgetTarget("meetup"))
        assertNull(DeepLinks.forWidgetTarget("home"))
        assertNull(DeepLinks.forWidgetTarget("refresh"))
        assertNull(DeepLinks.forWidgetTarget(""))
        assertNull(DeepLinks.forWidgetTarget(null))
    }

    @Test
    fun `widget intents are built like MeetupWidgetProvider`() {
        assertEquals("homewidget://news", DeepLinks.widgetUri(DeepLinks.TARGET_NEWS))
        assertEquals("homewidget://refresh", DeepLinks.widgetUri(DeepLinks.TARGET_REFRESH))
        assertEquals("space.einundzwanzig.meetup.WIDGET_BITCOIN", DeepLinks.widgetAction(DeepLinks.TARGET_BITCOIN))
        assertEquals("space.einundzwanzig.meetup.WIDGET_HOME", DeepLinks.widgetAction(DeepLinks.TARGET_HOME))
        assertEquals("open", DeepLinks.EXTRA_OPEN)
        assertThrows(IllegalArgumentException::class.java) { DeepLinks.widgetUri("a/b") }
        assertThrows(IllegalArgumentException::class.java) { DeepLinks.widgetUri("") }
    }

    @Test
    fun `built URIs parse back to the same destination`() {
        for (target in listOf(DeepLinks.TARGET_NEWS, DeepLinks.TARGET_BITCOIN, DeepLinks.TARGET_MEETUP)) {
            assertEquals(DeepLinks.forWidgetTarget(target), DeepLinks.parse(DeepLinks.widgetUri(target)))
        }
    }
}
