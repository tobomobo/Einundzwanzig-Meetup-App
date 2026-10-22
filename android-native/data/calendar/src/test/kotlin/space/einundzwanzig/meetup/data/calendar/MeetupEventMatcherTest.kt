package space.einundzwanzig.meetup.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.calendar.match.MeetupEventMatcher
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import java.time.LocalDate
import java.time.ZoneId

/** Port of `test/meetup_event_matcher_test.dart` (all cases, same fixtures). */
class MeetupEventMatcherTest {

    private fun event(
        id: String = "",
        link: String = "",
        title: String = "Einundzwanzig Südniedersachsen",
        location: String = "Dinx Diner",
    ) = MeetupDate(
        title = title,
        location = location,
        description = "",
        start = LocalDate.of(2026, 10, 9).atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant(),
        url = "https://example.com/event",
        meetupId = id,
        meetupPortalLink = link,
    )

    private val south = group("107", "Einundzwanzig Südniedersachsen", "Göttingen", "einundzwanzig-suedniedersachsen")
    private val saxony = group("32", "Einundzwanzig Sachsen", "Sachsen", "einundzwanzig-sachsen")
    private val groups = listOf(saxony, south)

    @Test
    fun `issue 53 - portal link identifies meetup despite different venue or city`() {
        val e = event(link = south.portalLink)
        assertEquals("107", MeetupEventMatcher.resolve(e, groups)?.id)
        assertFalse(MeetupEventMatcher.matchesCity(e, "Sachsen"))
    }

    @Test
    fun `portal identity tolerates locale, query and trailing slash`() {
        val link = south.portalLink.replaceFirst("/de/", "/en/") + "/?source=calendar"
        assertEquals("107", MeetupEventMatcher.resolve(event(link = link), groups)?.id)
    }

    @Test
    fun `explicit different or unknown ID cannot fall back to name or link`() {
        assertEquals("32", MeetupEventMatcher.resolve(event(id = "32", link = south.portalLink), groups)?.id)
        assertNull(MeetupEventMatcher.resolve(event(id = "unknown", link = south.portalLink), groups))
    }

    @Test
    fun `unknown or foreign portal link cannot fall back to title`() {
        assertNull(MeetupEventMatcher.resolve(event(link = "${south.portalLink}-unknown"), groups))
        assertNull(
            MeetupEventMatcher.resolve(
                event(link = "https://example.com/de/meetup/einundzwanzig-suedniedersachsen"),
                groups,
            ),
        )
    }

    @Test
    fun `legacy exact group name works and Sachsen is not a substring match`() {
        assertEquals("107", MeetupEventMatcher.resolve(event(), groups)?.id)
        assertFalse(MeetupEventMatcher.matchesCity(event(), "Sachsen"))
        assertTrue(MeetupEventMatcher.matchesCity(event(title = "Treffen in Sachsen"), "Sachsen"))
        assertFalse(MeetupEventMatcher.matchesCity(event(title = "Frankfurter Straße"), "Frankfurt"))
    }

    @Test
    fun `same-city meetups require an unambiguous identity`() {
        val a = group("1", "BitcoinWalk Würzburg", "Würzburg", "walk-wuerzburg")
        val b = group("2", "Würzburg Meetup", "Würzburg", "wuerzburg-meetup")
        assertEquals("2", MeetupEventMatcher.resolve(event(link = b.portalLink), listOf(a, b))?.id)
        assertNull(MeetupEventMatcher.resolve(event(title = "Treffen Würzburg"), listOf(a, b)))
    }

    @Test
    fun `legacy city matches Unicode boundaries and not description`() {
        val munich = group("3", "Bitcoin München", "München", "muenchen")
        assertEquals("3", MeetupEventMatcher.resolve(event(title = "Treffen in München"), listOf(munich))?.id)
        assertFalse(MeetupEventMatcher.matchesCity(event(title = "ÜSachsen"), "Sachsen"))
    }

    @Test
    fun `portalIdentity edge cases`() {
        assertEquals("x", MeetupEventMatcher.portalIdentity(" HTTPS://Portal.Einundzwanzig.Space/de/meetup/x "))
        assertEquals("", MeetupEventMatcher.portalIdentity("ftp://portal.einundzwanzig.space/de/meetup/x"))
        assertEquals("", MeetupEventMatcher.portalIdentity("portal.einundzwanzig.space/de/meetup/x"))
        assertEquals("", MeetupEventMatcher.portalIdentity("https://portal.einundzwanzig.space/de/meetup"))
        assertEquals("münchen", MeetupEventMatcher.portalIdentity("https://portal.einundzwanzig.space/de/meetup/m%C3%BCnchen#top"))
        assertEquals("x", MeetupEventMatcher.portalIdentity("https://user@portal.einundzwanzig.space:443/meetup/x"))
        // FIRST "meetup" segment must be second to last.
        assertEquals("", MeetupEventMatcher.portalIdentity("https://portal.einundzwanzig.space/meetup/meetup/x"))
    }

    @Test
    fun `generic and short city terms never match`() {
        assertFalse(MeetupEventMatcher.matchesCity(event(title = "Bitcoin Meetup"), "Bitcoin"))
        assertFalse(MeetupEventMatcher.matchesCity(event(title = "Ab Treffen"), "Ab"))
        assertFalse(MeetupEventMatcher.matchesCity(event(title = "Treffen in Sachsen", id = "1"), "Sachsen"))
    }
}
