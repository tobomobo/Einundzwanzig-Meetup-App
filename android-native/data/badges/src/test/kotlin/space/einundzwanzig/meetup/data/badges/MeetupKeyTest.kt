package space.einundzwanzig.meetup.data.badges

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.badges.security.MeetupKey
import space.einundzwanzig.meetup.data.badges.text.DartString

/** Port of `test/meetup_key_test.dart` plus `fixtures.meetupKey`. */
class MeetupKeyTest {

    private val parseCases = linkedMapOf(
        "bad-neuenahr" to ("bad-neuenahr" to ""),
        "frankfurt-am-main" to ("frankfurt-am-main" to ""),
        "rothenburg-ob-der-tauber" to ("rothenburg-ob-der-tauber" to ""),
        "köln-deutz" to ("köln-deutz" to ""),
        "neu-ulm" to ("neu-ulm" to ""),
        "aachen-de" to ("aachen" to "DE"),
        "bad-neuenahr-de" to ("bad-neuenahr" to "DE"),
        "koblenz-de" to ("koblenz" to "DE"),
        "wien-at" to ("wien" to "AT"),
        "zürich-ch" to ("zürich" to "CH"),
        "aschaffenburg" to ("aschaffenburg" to ""),
        "frankfurt-am-main-de" to ("frankfurt-am-main" to "DE"),
    )

    @Test
    fun `parse splits only real country codes`() {
        for ((input, expected) in parseCases) {
            val p = MeetupKey.parse(input)
            assertEquals(input, expected.first, p.slug)
            assertEquals(input, expected.second, p.country)
        }
    }

    @Test
    fun `bad-neuenahr is not split into bad + NEUENAHR`() {
        val p = MeetupKey.parse("bad-neuenahr")
        assertNotEquals("bad", p.slug)
        assertNotEquals("NEUENAHR", p.country)
    }

    @Test
    fun `frankfurt-am-main is not split into frankfurt-am + MAIN`() {
        val p = MeetupKey.parse("frankfurt-am-main")
        assertNotEquals("frankfurt-am", p.slug)
        assertNotEquals("MAIN", p.country)
    }

    @Test
    fun `edge cases`() {
        assertEquals("", MeetupKey.parse("").slug)
        assertEquals("-de", MeetupKey.parse("-de").slug)
        assertEquals("aachen-", MeetupKey.parse("aachen-").slug)
    }

    @Test
    fun `compose appends valid country lower case`() {
        assertEquals("bad-neuenahr-de", MeetupKey.compose("bad-neuenahr", "DE"))
        assertEquals("wien-at", MeetupKey.compose("wien", "at"))
    }

    @Test
    fun `compose without or with invalid country keeps slug`() {
        assertEquals("bad-neuenahr", MeetupKey.compose("bad-neuenahr", ""))
        assertEquals("bad-neuenahr", MeetupKey.compose("bad-neuenahr", "Deutschland"))
    }

    private val cities = listOf("Bad Neuenahr", "Frankfurt am Main", "Rothenburg ob der Tauber", "Neu-Ulm", "Köln-Deutz", "Aschaffenburg")

    @Test
    fun `compose and parse are reversible`() {
        for (city in cities) {
            val slug = MeetupKey.slug(city)
            val p = MeetupKey.parse(MeetupKey.compose(slug, "DE"))
            assertEquals(city, slug, p.slug)
            assertEquals(city, "DE", p.country)
        }
    }

    @Test
    fun `slug equals the old organizer formula`() {
        for (name in listOf("Aschaffenburg", "Bad Neuenahr", "Neu-Ulm", "München")) {
            assertEquals(DartString.lower(name).replace(' ', '-'), MeetupKey.slug(name))
        }
    }

    private val date = DartDateTime.local(2026, 10, 5)

    private fun organizerKey(city: String) = MeetupKey.eventId(MeetupKey.slug(city), date)

    private fun participantKey(m: String): String {
        val normalized = BadgeSecurity.normalize(jsonObjectOf("v" to 2, "t" to "B", "m" to m, "b" to 0))
        return MeetupKey.eventId(normalized["meetup_slug"]!!.jsonPrimitive.content, date)
    }

    @Test
    fun `organizer key equals participant key`() {
        for (city in cities + "Bad Neuenahr-Ahrweiler") {
            assertEquals("$city (new organizer)", organizerKey(city), participantKey(MeetupKey.compose(MeetupKey.slug(city), "DE")))
            assertEquals("$city (old organizer)", organizerKey(city), participantKey(MeetupKey.slug(city)))
        }
    }

    @Test
    fun `old participant app with new badge derives the same slug`() {
        val m = MeetupKey.compose(MeetupKey.slug("Bad Neuenahr"), "DE")
        val parts = m.split('-')
        val oldCity = parts.subList(0, parts.size - 1).joinToString("-")
        val oldName = DartString.upper(oldCity.substring(0, 1)) + oldCity.substring(1)
        assertEquals("bad-neuenahr", DartString.lower(oldName).replace(' ', '-'))
    }

    @Test
    fun `normalize bad-neuenahr-de`() {
        val n = BadgeSecurity.normalize(jsonObjectOf("v" to 2, "t" to "B", "m" to "bad-neuenahr-de", "b" to 0))
        assertEquals(JsonPrimitive("bad-neuenahr"), n["meetup_slug"])
        assertEquals(JsonPrimitive("bad-neuenahr"), n["meetup_id"])
        assertEquals(JsonPrimitive("DE"), n["meetup_country"])
        assertEquals(JsonPrimitive("Bad Neuenahr"), n["meetup_name"])
    }

    @Test
    fun `normalize bad-neuenahr without country stays whole`() {
        val n = BadgeSecurity.normalize(jsonObjectOf("v" to 2, "t" to "B", "m" to "bad-neuenahr", "b" to 0))
        assertEquals(JsonPrimitive("bad-neuenahr"), n["meetup_slug"])
        assertEquals(JsonPrimitive(""), n["meetup_country"])
    }

    @Test
    fun `event badges are not affected`() {
        val n = BadgeSecurity.normalize(jsonObjectOf("v" to 2, "t" to "B", "m" to "evt:31923:${"a".repeat(64)}:zitadelle:zitadelle-2026", "b" to 0))
        assertEquals(JsonPrimitive(true), n["is_event"])
        assertFalse(n.containsKey("meetup_slug"))
    }

    @Test
    fun `iso set has 249 codes, UK is not one`() {
        assertEquals(249, MeetupKey.isoCountryCount)
        assertTrue(MeetupKey.isIsoCountry("gb"))
        assertFalse(MeetupKey.isIsoCountry("UK"))
        assertFalse(MeetupKey.isIsoCountry("DEU"))
    }

    @Test
    fun `fixture meetupKey`() {
        val o = Fixtures["meetupKey"].jsonObject
        fun s(e: kotlinx.serialization.json.JsonElement, k: String) = e.jsonObject[k]!!.jsonPrimitive.content
        for (c in o["parse"]!!.jsonArray) {
            val p = MeetupKey.parse(s(c, "input"))
            assertEquals(s(c, "input"), s(c, "slug"), p.slug)
            assertEquals(s(c, "input"), s(c, "country"), p.country)
        }
        for (c in o["compose"]!!.jsonArray) assertEquals(s(c, "expected"), MeetupKey.compose(s(c, "slug"), s(c, "country")))
        for (c in o["slug"]!!.jsonArray) assertEquals(s(c, "expected"), MeetupKey.slug(s(c, "input")))
        for (c in o["displayFromSlug"]!!.jsonArray) assertEquals(s(c, "expected"), MeetupKey.displayFromSlug(s(c, "input")))
        val e = o["eventId"]!!
        assertEquals(s(e, "expected"), MeetupKey.eventId(s(e, "slug"), DartDateTime.parse(s(e, "date_local"))))
        assertEquals(14, o["parse"]!!.jsonArray.size)
    }
}
