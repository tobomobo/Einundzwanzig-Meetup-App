package space.einundzwanzig.meetup.data.community.portal

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.data.community.CommunityLinks
import java.time.ZoneId

class PortalFieldsTest {

    private fun obj(json: String) = DartJson.decode(json) as JsonObject

    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun pvReadsScalarsLikeDartToString() {
        val e = obj("""{"s":"x","i":42,"d":1.5,"d0":2.0,"b":true,"n":null,"m":{"a":1},"l":[1]}""")
        assertEquals("x", PortalFields.pv(e, "s"))
        assertEquals("42", PortalFields.pv(e, "i"))
        assertEquals("1.5", PortalFields.pv(e, "d"))
        assertEquals("2.0", PortalFields.pv(e, "d0"))
        assertEquals("true", PortalFields.pv(e, "b"))
        assertEquals("", PortalFields.pv(e, "n"))
        assertEquals("", PortalFields.pv(e, "m"))
        assertEquals("", PortalFields.pv(e, "l"))
        assertEquals("", PortalFields.pv(e, "missing"))
    }

    @Test
    fun pvDottedKeyPrefersFlatThenNested() {
        assertEquals("flat", PortalFields.pv(obj("""{"lecturer.name":"flat","lecturer":{"name":"nested"}}"""), "lecturer.name"))
        assertEquals("nested", PortalFields.pv(obj("""{"lecturer":{"name":"nested"}}"""), "lecturer.name"))
        assertEquals("nested", PortalFields.pv(obj("""{"lecturer.name":{"x":1},"lecturer":{"name":"nested"}}"""), "lecturer.name"))
        assertEquals("", PortalFields.pv(obj("""{"lecturer":{"name":null}}"""), "lecturer.name"))
        assertEquals("", PortalFields.pv(obj("""{"lecturer":"Anna"}"""), "lecturer.name"))
    }

    @Test
    fun pImgTakesFirstHttpValueInKeyOrder() {
        assertEquals("https://b", PortalFields.pImg(obj("""{"image":"/rel.png","logo":"https://b","avatar":"https://c"}""")))
        assertEquals("http://cover", PortalFields.pImg(obj("""{"cover":"http://cover"}""")))
        assertEquals("", PortalFields.pImg(obj("""{"image":"ftp://x"}""")))
    }

    @Test
    fun extractLinksDedupesInOrderAndStopsAtDelimiters() {
        val text = "Siehe https://a.de/x), dann (http://b.de/y] und https://a.de/x, " +
            "<https://c.de/z> https://d.de/p weiter https://e.de/q,r"
        assertEquals(
            listOf("https://a.de/x", "http://b.de/y", "https://c.de/z", "https://d.de/p", "https://e.de/q"),
            PortalFields.extractLinks(text),
        )
        assertEquals(emptyList<String>(), PortalFields.extractLinks("keine Links"))
    }

    @Test
    fun listNameAndDescriptionUseFirstNonNull() {
        assertEquals("T", PortalFields.listName(obj("""{"name":null,"title":"T"}""")))
        assertEquals("", PortalFields.listName(obj("""{}""")))
        assertEquals("7", PortalFields.listName(obj("""{"name":7}""")))
        assertEquals("bio", PortalFields.listDescription(obj("""{"description":null,"bio":"bio"}""")))
        assertEquals("intro", PortalFields.listDescription(obj("""{"intro":"intro","bio":"bio"}""")))
        assertEquals("{a: 1, b: [x, 2.5]}", PortalFields.listDescription(obj("""{"description":{"a":1,"b":["x",2.5]}}""")))
    }

    @Test
    fun intIdOnlyForJsonInts() {
        assertEquals(5L, PortalFields.intId(obj("""{"id":5}""")))
        assertNull(PortalFields.intId(obj("""{"id":"5"}""")))
        assertNull(PortalFields.intId(obj("""{"id":5.0}""")))
        assertNull(PortalFields.intId(obj("""{}""")))
    }

    @Test
    fun courseDetail() {
        val c = PortalFields.course(
            obj(
                """{"id":3,"title":"Bitcoin 101","description":"Mehr: https://x.de/a und https://x.de/a",
                "logo":"https://img","portalLink":"https://portal/c/3",
                "lecturer":{"id":9,"name":"Anna","intro":"Dozentin","avatar":"https://anna"},
                "events":"nope",
                "course_events":[{"from":"2026-11-02T18:30:00Z","venue":{"name":"Hub"},"link":"https://l"},
                                 {"start":"2026-11-03 19:05:00","location":"Wien","name":"Abend"},
                                 {"from":"bald"}, 7]}""",
            ),
            berlin,
        )
        assertEquals("Bitcoin 101", c.name)
        assertEquals("https://img", c.image)
        assertEquals("Anna", c.lecturerName)
        assertEquals("Dozentin", c.lecturerIntro)
        assertEquals("https://anna", c.lecturerImage)
        assertEquals(9L, c.lecturerId)
        assertEquals("https://portal/c/3", c.portalLink)
        assertEquals(listOf("https://x.de/a"), c.links)
        assertEquals(3, c.events.size)

        val utc = c.events[0]
        // A Z timestamp keeps UTC fields in Dart (no conversion to local time).
        assertEquals("02.11.2026 · 18:30", utc.whenLabel)
        assertEquals("Hub", utc.location)
        assertEquals("Hub", PortalFields.calendarTitle(utc))

        val local = c.events[1]
        assertEquals("03.11.2026 · 19:05", local.whenLabel)
        assertEquals("Abend", PortalFields.calendarTitle(local))

        val raw = c.events[2]
        assertNull(raw.start)
        assertEquals("bald", raw.whenLabel)
        assertEquals("Termin", PortalFields.calendarTitle(raw))
    }

    @Test
    fun lecturerDetailLinks() {
        val l = PortalFields.lecturer(
            obj(
                """{"name":"Bob","intro":"i","bio":"b","website":"bob.de","twitter":"",
                "twitter_username":"bobx","nostr":"npub1abc","portalLink":"https://portal/l/1",
                "courses":[{"id":1,"name":"K"},"x"]}""",
            ),
        )
        assertEquals("b", l.bio)
        assertEquals(1, l.courses.size)
        assertEquals(listOf("Website", "X (Twitter)", "Nostr", "Portal"), l.links.map { it.label })
        assertEquals(
            listOf("https://bob.de", "https://x.com/bobx", "https://njump.me/npub1abc", "https://portal/l/1"),
            l.links.map { it.url },
        )
        assertEquals("X (Twitter): bobx", l.links[1].text)
        assertEquals("https://nostr.example", PortalFields.lecturerLinkUrl("Nostr", "nostr.example"))
        assertEquals(emptyList<PortalLink>(), PortalFields.lecturer(obj("""{"name":"x"}""")).links)
    }

    @Test
    fun satoshiDuellLink() {
        assertEquals("https://satoshiduell.de/?npub=npub1x", CommunityLinks.satoshiDuell("npub1x"))
        assertEquals("https://satoshiduell.de/", CommunityLinks.satoshiDuell(""))
        assertEquals("https://satoshiduell.de/", CommunityLinks.satoshiDuell(null))
    }
}
