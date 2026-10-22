package space.einundzwanzig.meetup.data.meetups

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.data.meetups.internal.MeetupJson
import space.einundzwanzig.meetup.data.meetups.internal.MeetupServiceImpl
import space.einundzwanzig.meetup.data.meetups.model.Meetup

class MeetupServiceTest {

    private val server = FakeServer()
    private val logos = testLogos()
    private val service = MeetupServiceImpl(server.client, testLog(), logos)

    @After
    fun tearDown() = server.close()

    private fun parse(json: String) = MeetupJson.parse(DartJson.decode(json) as JsonObject)

    private suspend fun load(vararg entries: String): List<Meetup> {
        server.respond = { _, path -> if (path == "/api/mobile/meetups") json("[${entries.joinToString(",")}]") else status(404, "") }
        return service.fetchMeetups()
    }

    private fun m(id: Any, city: String, name: String = "", country: String = "DE", nostr: String = "") =
        """{"id":${if (id is String) "\"$id\"" else id},"city":"$city","name":"$name","country":"$country","nostr":"$nostr"}"""

    // ---- parsing (spec 09 §8.1, §10 new tests) -------------------------------------------

    @Test
    fun `firstNum takes the first numeric candidate`() {
        assertEquals(48.1, parse("""{"latitude":"48.1"}""").lat, 0.0)
        assertEquals(48.0, parse("""{"latitude":null,"lat":48}""").lat, 0.0)
        assertEquals(0.0, parse("""{"lat":"abc"}""").lat, 0.0)
        assertEquals(9.5, parse("""{"longitude":null,"lon":null,"lng":"9.5"}""").lng, 0.0)
        assertEquals(7.25, parse("""{"longitude":"x","lon":7.25}""").lng, 0.0)
    }

    @Test
    fun `image priority cover then image then logo`() {
        assertEquals("x", parse("""{"cover":"","image":"x","logo":"l"}""").coverImagePath)
        assertEquals("l", parse("""{"cover":null,"image":"","logo":"l"}""").coverImagePath)
        assertEquals("", parse("""{"cover":"","image":"","logo":""}""").coverImagePath)
        assertEquals("", parse("""{}""").coverImagePath)
    }

    @Test
    fun `portalLink explicit wins trimmed else derived from slug with encodeComponent`() {
        assertEquals("https://x/y", parse("""{"portalLink":"  https://x/y ","slug":"s"}""").portalLink)
        assertEquals(
            "https://portal.einundzwanzig.space/de/meetup/einundzwanzig-suedniedersachsen",
            parse("""{"slug":" einundzwanzig-suedniedersachsen "}""").portalLink,
        )
        assertEquals("https://portal.einundzwanzig.space/de/meetup/a%20b~*", parse("""{"slug":"a b~*"}""").portalLink)
        assertEquals("", parse("""{"slug":""}""").portalLink)
    }

    @Test
    fun `id city country fallbacks`() {
        assertEquals("107", parse("""{"id":107}""").id)
        assertEquals("Gruppe", parse("""{"name":"Gruppe"}""").id)
        assertEquals("unknown", parse("""{}""").id)
        assertEquals("Gruppe", parse("""{"name":"Gruppe"}""").city)
        assertEquals("Unbekannt", parse("""{}""").city)
        assertEquals("", parse("""{"city":"","name":"Gruppe"}""").city) // ?? is null-coalescing only
        assertEquals("DE", parse("""{}""").country)
        assertEquals("AT", parse("""{"country":"AT"}""").country)
    }

    @Test
    fun `field mapping url intro twitter nostr logo`() {
        val m = parse(
            """{"id":5,"name":"N","city":"C","url":"t.me/x","intro":"I","website":"W","twitter_username":"T",
               "nostr":"npub1x","logo":"/storage/l.png"}""",
        )
        assertEquals("t.me/x", m.telegramLink)
        assertEquals("I", m.description)
        assertEquals("W", m.website)
        assertEquals("T", m.twitterUsername)
        assertEquals("npub1x", m.nostrNpub)
        assertEquals("/storage/l.png", m.logoUrl)
        assertEquals("/storage/l.png", m.coverImagePath)
    }

    @Test
    fun `fetch dedupes exact ids only and caches`() = runTest {
        val list = load(m(5, "A"), m(5, "B"), m(6, "Würzburg"), m(7, "Würzburg"))
        assertEquals(listOf("5", "6", "7"), list.map { it.id })
        assertEquals("A", list[0].city)
        assertSame(list, service.cached)
        assertEquals(list, service.cachedFlow.value)
        assertEquals("https://portal.einundzwanzig.space/api/mobile/meetups", "https://${server.seen[0].host}${server.seen[0].path}")
    }

    @Test
    fun `directory load seeds crests for groups without upcoming events`() = runTest {
        load("""{"id":1,"city":"Wien","name":"Einundzwanzig Wien","logo":"/storage/wien.png"}""")
        assertEquals("https://portal.einundzwanzig.space/storage/wien.png", logos.logoFor("Wien"))
        assertEquals("https://portal.einundzwanzig.space/storage/wien.png", logos.logoFor("Einundzwanzig Wien"))
        logos.put("Einundzwanzig Wien", "/storage/calendar.png")
        assertEquals("https://portal.einundzwanzig.space/storage/calendar.png", logos.logoFor("Wien"))
    }

    @Test
    fun `fetch failure returns empty and keeps the cache`() = runTest {
        load(m(1, "A"))
        server.respond = { _, _ -> status(500, "x") }
        assertTrue(service.fetchMeetups().isEmpty())
        assertEquals(1, service.cached.size)
        server.respond = { _, _ -> json("{not json") }
        assertTrue(service.fetchMeetups().isEmpty())
        assertEquals(1, service.cached.size)
    }

    @Test
    fun `non-string fields are coerced instead of dropping the list`() = runTest {
        val list = load("""{"id":1,"city":"A","url":42,"nostr":null}""")
        assertEquals("42", list.single().telegramLink)
        assertEquals("", list.single().nostrNpub)
    }

    // ---- resolution (spec 09 §8.2) -------------------------------------------------------

    @Test
    fun `resolveFavorite id then legacy city`() = runTest {
        assertNull(service.resolveFavorite("107")) // empty cache
        load(m(107, "Göttingen", "Einundzwanzig Südniedersachsen"), m(1, "Würzburg", "BitcoinWalk Würzburg"), m(2, "Würzburg", "Einundzwanzig Würzburg"))
        assertEquals("107", service.resolveFavorite("107")?.id)
        assertEquals("1", service.resolveFavorite("würzburg")?.id) // ambiguous → first
        assertEquals("107", service.resolveFavorite("GÖTTINGEN")?.id)
        assertNull(service.resolveFavorite(""))
        assertNull(service.resolveFavorite("Paris"))
        assertNull(service.resolveFavorite(" Göttingen")) // no trim
    }

    @Test
    fun `labelFor and cityFor`() = runTest {
        load(m(107, "Göttingen", "Einundzwanzig Südniedersachsen"), m(1, "Würzburg", "BitcoinWalk Würzburg"), m(2, "Würzburg", "Einundzwanzig Würzburg"))
        assertEquals("BitcoinWalk Würzburg", service.labelFor("1"))
        assertEquals("Göttingen", service.labelFor("107"))
        assertEquals("Paris", service.labelFor("Paris"))
        assertEquals("Würzburg", service.cityFor("2"))
        assertEquals("Paris", service.cityFor("Paris"))
    }

    @Test
    fun `bySlug matches the city slug and optionally the country`() = runTest {
        load(m(1, "Neu-Ulm", country = "DE"), m(2, "Bad Tölz", country = "de"))
        assertEquals("1", service.bySlug("neu-ulm", "de")?.id)
        assertEquals("1", service.bySlug("neu-ulm", "")?.id)
        assertNull(service.bySlug("neu-ulm", "AT"))
        assertEquals("2", service.bySlug("bad-tölz", " DE ")?.id)
        assertNull(service.bySlug("", "DE"))
    }

    @Test
    fun `byName prefers the group name over the city`() = runTest {
        load(m(1, "Aschaffenburg", "Einundzwanzig Hessen"), m(2, "Hessen", "Einundzwanzig Aschaffenburg"))
        assertEquals("2", service.byName(" einundzwanzig aschaffenburg ")?.id)
        assertEquals("1", service.byName("Aschaffenburg")?.id)
        assertNull(service.byName(" "))
    }

    @Test
    fun `organizerMeetupFor converts hex to npub and fetches when the cache is empty`() = runTest {
        val kp = KeyPair.generate()
        server.respond = { _, _ -> json("[${m(9, "A", nostr = " ${kp.npub} ")}]") }
        assertEquals("9", service.organizerMeetupFor(kp.publicKeyHex)?.id)
        assertEquals(1, server.seen.size)
        assertEquals("9", service.organizerMeetupFor(kp.npub)?.id)
        assertEquals(1, server.seen.size) // cache used
        assertNull(service.organizerMeetupFor("zz-not-hex"))
        assertNull(service.organizerMeetupFor(""))
        assertNull(service.organizerMeetupFor(KeyPair.generate().npub))
    }

    @Test
    fun `fallback list is bundled`() {
        val list = service.fallbackMeetups
        assertEquals(listOf("m_muc", "m_hh", "m_b"), list.map { it.id })
        assertEquals(listOf("München", "Hamburg", "Berlin"), list.map { it.city })
        assertEquals("t.me/einundzwanzig_muc", list[0].telegramLink)
        assertEquals(48.1351, list[0].lat, 0.0)
        assertEquals(13.4050, list[2].lng, 0.0)
        assertTrue(list.all { it.country == "DE" && it.name.isEmpty() && it.portalLink.isEmpty() })
    }

    @Test
    fun `fallback constant equals the JSON fixture`() {
        val text = javaClass.getResourceAsStream("/space/einundzwanzig/meetup/data/meetups/fallback_meetups.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val fixture = space.einundzwanzig.meetup.core.network.AppJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Meetup.serializer()),
            text,
        )
        assertEquals(fixture, service.fallbackMeetups)
    }

    @Test
    fun `non-object entries are skipped instead of dropping the directory`() = runTest {
        val list = load("null", "\"x\"", "3", """{"id":1,"city":"A"}""")
        assertEquals(1, list.size)
        assertEquals("1", list.single().id)
        assertEquals("A", list.single().city)
        assertEquals(list, service.cached)
    }

    @Test
    fun `serialized model keeps the Dart field names`() {
        val text = space.einundzwanzig.meetup.core.network.AppJson.encodeToString(Meetup.serializer(), service.fallbackMeetups[0])
        val obj = DartJson.decode(text) as JsonObject
        assertEquals(JsonPrimitive("t.me/einundzwanzig_muc"), obj["telegramLink"])
    }
}
