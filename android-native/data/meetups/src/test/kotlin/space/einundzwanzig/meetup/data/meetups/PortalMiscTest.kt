package space.einundzwanzig.meetup.data.meetups

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.meetups.internal.GeocodingServiceImpl
import space.einundzwanzig.meetup.data.meetups.internal.PortalLogoRegistryImpl
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoLoader
import space.einundzwanzig.meetup.data.meetups.portal.PortalTime
import space.einundzwanzig.meetup.data.meetups.portal.PortalUrls
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Optional
import javax.inject.Provider

class PortalMiscTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    // ---- PortalTime ----------------------------------------------------------------------

    @Test
    fun `portalStartUtc reads zone-less values as UTC`() {
        assertEquals(LocalDateTime.of(2026, 7, 21, 18, 0), PortalTime.portalStartUtc("2026-07-21 16:00", berlin)!!.toLocalDateTime())
        assertEquals(LocalDateTime.of(2026, 7, 21, 18, 0), PortalTime.portalStartUtc("2026-07-21T16:00:00Z", berlin)!!.toLocalDateTime())
        assertEquals(LocalDateTime.of(2026, 1, 21, 17, 0, 5), PortalTime.portalStartUtc(" 2026-01-21 16:00:05.999 ", berlin)!!.toLocalDateTime())
        assertEquals(LocalDateTime.of(2026, 7, 21, 18, 0), PortalTime.portalStartUtc("2026-07-21T18:00:00+02:00", berlin)!!.toLocalDateTime())
        assertNull(PortalTime.portalStartUtc("", berlin))
        assertNull(PortalTime.portalStartUtc("gestern", berlin))
    }

    @Test
    fun `editor round trip keeps the time`() {
        val prefill: ZonedDateTime = PortalTime.portalStartUtc("2026-07-21 16:00:00", berlin)!!
        assertEquals("2026-07-21T16:00:00.000Z", PortalTime.toPortalIso(prefill))
        assertEquals("2026-07-21T17:00:00.000Z", PortalTime.toPortalIso(LocalDateTime.of(2026, 7, 21, 19, 0).atZone(berlin)))
    }

    // ---- URLs / logos --------------------------------------------------------------------

    @Test
    fun `absoluteImageUrl`() {
        assertEquals("https://portal.einundzwanzig.space/storage/x.png", PortalUrls.absoluteImageUrl("/storage/x.png"))
        assertEquals("https://portal.einundzwanzig.space/x.png", PortalUrls.absoluteImageUrl(" x.png "))
        assertEquals("https://a", PortalUrls.absoluteImageUrl("https://a"))
        assertEquals("http://a", PortalUrls.absoluteImageUrl("http://a"))
        assertEquals("", PortalUrls.absoluteImageUrl(""))
    }

    @Test
    fun `logoFor exact contains contained-in and first insertion wins`() {
        val logos = linkedMapOf(
            "Einundzwanzig Würzburg" to "/w.png",
            "Würzburg Walk" to "https://x/walk.png",
            "Berlin" to "b.png",
        )
        assertEquals("https://portal.einundzwanzig.space/w.png", PortalLogoRegistryImpl.logoFor(logos, "würzburg"))
        assertEquals("https://x/walk.png", PortalLogoRegistryImpl.logoFor(logos, " WÜRZBURG WALK "))
        assertEquals("https://portal.einundzwanzig.space/b.png", PortalLogoRegistryImpl.logoFor(logos, "Einundzwanzig Berlin Mitte"))
        assertEquals("", PortalLogoRegistryImpl.logoFor(logos, "Hamburg"))
        assertEquals("", PortalLogoRegistryImpl.logoFor(logos, "  "))
    }

    @Test
    fun `registry put keeps position, seeding never overrides, single shared load`() = runTest {
        var loads = 0
        lateinit var registry: PortalLogoRegistryImpl
        val loader = PortalLogoLoader {
            loads++
            yield()
            registry.put("A", "a.png")
        }
        registry = PortalLogoRegistryImpl(Optional.of(Provider { loader }), testLog())
        val a = async { registry.ensureLoaded() }
        val b = async { registry.ensureLoaded() }
        a.await()
        b.await()
        assertEquals(1, loads)
        registry.ensureLoaded()
        assertEquals(1, loads)

        registry.put("B", "b.png")
        registry.put("A", "a2.png")
        registry.put("C", "")
        assertEquals(listOf("A" to "a2.png", "B" to "b.png"), registry.logos.value.toList())
        registry.seedFrom(
            listOf(
                Meetup(id = "1", name = "B", city = "Köln", country = "DE", telegramLink = "", lat = 0.0, lng = 0.0, logoUrl = "k.png"),
            ),
        )
        assertEquals(listOf("A" to "a2.png", "B" to "b.png", "Köln" to "k.png"), registry.logos.value.toList())
    }

    @Test
    fun `seeded short city names do not beat calendar entries`() = runTest {
        var loads = 0
        val registry = PortalLogoRegistryImpl(Optional.of(Provider { PortalLogoLoader { loads++ } }), testLog())
        registry.seedFrom(
            listOf(
                Meetup(id = "1", name = "", city = "Ulm", country = "DE", telegramLink = "", lat = 0.0, lng = 0.0, logoUrl = "ulm.png"),
                Meetup(id = "2", name = "", city = "Bern", country = "CH", telegramLink = "", lat = 0.0, lng = 0.0, logoUrl = "bern.png"),
            ),
        )
        // Seeding does not count as loaded: the calendar load still runs.
        registry.ensureLoaded()
        assertEquals(1, loads)
        registry.put("Einundzwanzig Neu-Ulm", "neu-ulm.png")
        registry.put("Bernau", "bernau.png")
        assertEquals(PortalUrls.absoluteImageUrl("neu-ulm.png"), registry.logoFor("Einundzwanzig Neu-Ulm"))
        assertEquals(PortalUrls.absoluteImageUrl("bernau.png"), registry.logoFor("Bernau"))
        // A containing calendar entry still wins (Dart rule): "einundzwanzig neu-ulm" contains "ulm".
        assertEquals(PortalUrls.absoluteImageUrl("neu-ulm.png"), registry.logoFor("Ulm"))
        // No calendar match → seeded entry.
        assertEquals(PortalUrls.absoluteImageUrl("bern.png"), registry.logoFor("Einundzwanzig Bern"))
        assertEquals(
            listOf("Einundzwanzig Neu-Ulm", "Bernau", "Ulm", "Bern"),
            registry.logos.value.keys.toList(),
        )
    }

    @Test
    fun `registry without loader is a no-op`() = runTest {
        val registry = PortalLogoRegistryImpl(Optional.empty(), testLog())
        registry.ensureLoaded()
        assertTrue(registry.logos.value.isEmpty())
    }

    // ---- Nominatim -----------------------------------------------------------------------

    @Test
    fun `nominatim parsing`() {
        val places = GeocodingServiceImpl.parse(
            """[{"display_name":"Hamburg, Deutschland","name":"","lat":"53.55","lon":"9.99"},
               {"display_name":"Null Island","name":"Null","lat":"0","lon":"0"},
               {"display_name":"X","name":"Bonn","lat":"50.7","lon":"7.1"}]""",
        )
        assertEquals(2, places.size)
        assertEquals("Hamburg", places[0].shortName)
        assertEquals("Hamburg, Deutschland", places[0].displayName)
        assertEquals(53.55, places[0].lat, 0.0)
        assertEquals("Bonn", places[1].shortName)
    }

    @Test
    fun `nominatim request shape and short query`() = runTest {
        FakeServer().use { server ->
            server.respond = { _, _ -> json("""[{"display_name":"Hamburg, DE","lat":"53.5","lon":"10"}]""") }
            val geo = GeocodingServiceImpl(server.client, FakeTime(), testLog())
            assertTrue(geo.search(" H ").isEmpty())
            assertTrue(server.seen.isEmpty())
            val r = geo.search(" Bad Tölz ")
            assertEquals("Hamburg", r.single().shortName)
            val s = server.seen.single()
            assertEquals("nominatim.openstreetmap.org", s.host)
            assertEquals("/search", s.path)
            assertEquals("q=Bad+T%C3%B6lz&format=jsonv2&limit=5&addressdetails=1&accept-language=de%2Cen", s.query)
            assertEquals("EinundzwanzigMeetupApp/1.0 (nostr meetup app)", s.header("User-Agent"))
            server.respond = { _, _ -> status(429, "") }
            assertTrue(geo.search("Hamburg").isEmpty())
        }
    }
}
