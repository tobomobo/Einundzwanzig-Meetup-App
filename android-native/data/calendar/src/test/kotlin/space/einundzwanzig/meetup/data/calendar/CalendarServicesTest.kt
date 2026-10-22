package space.einundzwanzig.meetup.data.calendar

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.data.calendar.internal.CalendarEventServiceImpl
import space.einundzwanzig.meetup.data.calendar.internal.EventRsvpServiceImpl
import space.einundzwanzig.meetup.data.calendar.model.CalendarEventDraft
import space.einundzwanzig.meetup.data.calendar.model.RsvpStatus
import space.einundzwanzig.meetup.data.calendar.nostr.NostrCalendarCodec
import java.time.Instant
import java.time.ZoneOffset

/** Relay/signing behaviour of `CalendarEventService` and `EventRsvpService` (spec 10 §6, §10). */
class CalendarServicesTest {

    private val net = FakeNetwork()
    private val signer = FakeSigner()
    private val time = FakeTime()
    private val me get() = signer.keyPair!!.publicKeyHex

    private fun TestScope.calendar() = CalendarEventServiceImpl(
        RelayClient(net.connector, UnconfinedTestDispatcher(testScheduler)),
        net.relayConfig,
        signer.service,
        time,
        testLog(),
    )

    private fun TestScope.rsvp() = EventRsvpServiceImpl(
        RelayClient(net.connector, UnconfinedTestDispatcher(testScheduler)),
        net.relayConfig,
        signer.service,
        time,
        testLog(),
    )

    private fun JsonObject.tags(): List<List<String>> = this["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }

    private fun calTags(d: String, title: String, vararg extra: List<String>) =
        listOf(listOf("d", d), listOf("title", title), listOf("start", "1800100000"), listOf("client", "einundzwanzig-meetup-app")) + extra

    @Test
    fun `publish signs the exact tags and sends to active relays plus community`() = runTest {
        val draft = CalendarEventDraft(
            title = "BTC Prag", description = "  Beschreibung  ", location = " Prag ",
            start = Instant.parse("2026-06-19T08:00:00Z").atZone(ZoneOffset.UTC),
        )
        val n = calendar().publishEvent(draft, existingDTag = "D")
        assertEquals(4, n)
        assertEquals(4, net.published.map { it.first }.distinct().size)
        assertTrue(net.published.any { it.first == CalendarRelays.COMMUNITY })
        val ev = net.published.first().second
        assertEquals(31923, ev["kind"]!!.jsonPrimitive.content.toInt())
        assertEquals("Beschreibung", ev["content"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(listOf("d", "D"), listOf("title", "BTC Prag"), listOf("start", "1781856000"), listOf("location", "Prag"), listOf("client", "einundzwanzig-meetup-app")),
            ev.tags(),
        )
        signer.failSigning = IllegalStateException("kein Signer")
        assertEquals(0, calendar().publishEvent(draft))
    }

    @Test
    fun `updateIssuers moves removed helpers to former issuers`() = runTest {
        val a = "a".repeat(64)
        val c = "c".repeat(64)
        val d = "d".repeat(64)
        val raw = signedRaw(signer.keyPair!!, 31923, calTags("D", "T", listOf("badge", "true"), listOf("p", a, "", "issuer"), listOf("p", c, "", "issuer")))
        val event = NostrCalendarCodec.parse(raw)!!
        time.now = 1_800_000_123_000L
        assertTrue(calendar().updateIssuers(event, listOf(c, d.uppercase(), me)))
        val first = NostrCalendarCodec.parse(net.published.last().second)!!
        assertEquals(listOf(c, d), first.issuers)
        assertEquals(mapOf(a to 1_800_000_123L), first.formerIssuers)
        assertEquals("D", first.dTag)

        time.now = 1_800_000_456_000L
        assertTrue(calendar().updateIssuers(first, listOf(a)))
        val second = NostrCalendarCodec.parse(net.published.last().second)!!
        assertEquals(listOf(a), second.issuers)
        assertEquals(mapOf(c to 1_800_000_456L, d to 1_800_000_456L), second.formerIssuers)
    }

    @Test
    fun `only the creator may edit or cancel`() = runTest {
        val other = KeyPair.generate()
        val event = NostrCalendarCodec.parse(signedRaw(other, 31923, calTags("D", "T")))!!
        assertFalse(calendar().updateIssuers(event, emptyList()))
        assertFalse(calendar().cancelEvent(event))
        assertTrue(signer.signed.isEmpty())
    }

    @Test
    fun `cancel publishes the replacement and a deletion request`() = runTest {
        val event = NostrCalendarCodec.parse(signedRaw(signer.keyPair!!, 31923, calTags("D", "T", listOf("location", "L")), content = " desc "))!!
        assertTrue(calendar().cancelEvent(event))
        val kinds = signer.signed.map { it.kind }
        assertEquals(listOf(31923, 5), kinds)
        assertEquals(NostrCalendarCodec.cancelTags(event), signer.signed[0].tags)
        assertEquals(" desc ", signer.signed[0].content)
        assertEquals(listOf(listOf("e", event.id), listOf("a", event.address), listOf("k", "31923")), signer.signed[1].tags)
        assertEquals("Termin abgesagt", signer.signed[1].content)
        net.unreachable = net.relayConfig.getActiveRelays().toSet() + CalendarRelays.COMMUNITY
        assertFalse(calendar().cancelEvent(event))
    }

    @Test
    fun `fetchByAddress - newest created_at wins across relays, cancelled hides, future ignored`() = runTest {
        val kp = KeyPair.generate()
        val address = "31923:${kp.publicKeyHex}:D"
        val old = signedRaw(kp, 31923, calTags("D", "Alt"), createdAt = 1_700_000_000L)
        val newer = signedRaw(kp, 31923, calTags("D", "Neu"), createdAt = 1_700_000_100L)
        val future = signedRaw(kp, 31923, calTags("D", "Zukunft"), createdAt = 1_800_010_000L)
        val otherD = signedRaw(kp, 31923, calTags("X", "Anders"), createdAt = 1_799_000_000L)
        net.events["wss://relay.damus.io"] = listOf(newer, future, otherD)
        net.events[CalendarRelays.COMMUNITY] = listOf(old)
        val found = calendar().fetchByAddress(address)
        assertEquals("Neu", found?.title)
        val req = net.requests.first().second
        assertEquals("""{"kinds":[31923],"authors":["${kp.publicKeyHex}"],"#d":["D"],"limit":1}""", req[2].toString())
        assertTrue(req[1].jsonPrimitive.content.matches(Regex("cal1-[0-9a-f]{12}")))

        val cancelled = signedRaw(kp, 31923, calTags("D", "Neu", listOf("status", "cancelled")), createdAt = 1_700_000_200L)
        net.events["wss://nos.lol"] = listOf(cancelled)
        assertNull(calendar().fetchByAddress(address))
        assertNull(calendar().fetchByAddress("31923:short:D"))
    }

    @Test
    fun `fetchEvents community mode reads the community relay and app events only`() = runTest {
        val kp = KeyPair.generate()
        val app = signedRaw(kp, 31923, calTags("A", "App"), createdAt = 1_700_000_000L)
        val foreign = signedRaw(kp, 31923, listOf(listOf("d", "F"), listOf("title", "Fremd"), listOf("start", "1800000000")))
        val oldActive = signedRaw(kp, 31923, calTags("C", "Abgesagt"), createdAt = 1_700_000_000L)
        val newCancelled = signedRaw(kp, 31923, calTags("C", "Abgesagt", listOf("status", "cancelled")), createdAt = 1_700_000_500L)
        val early = signedRaw(kp, 31922, listOf(listOf("d", "E"), listOf("title", "Früh"), listOf("start", "2020-01-01"), listOf("client", "einundzwanzig-meetup-app")))
        net.events[CalendarRelays.COMMUNITY] = listOf(app, foreign, newCancelled, oldActive, early)
        net.events["wss://relay.damus.io"] = listOf(foreign)
        val community = calendar().fetchEvents()
        assertEquals(listOf("Früh", "App"), community.map { it.title })
        assertEquals(listOf(CalendarRelays.COMMUNITY), net.requests.map { it.first })
        val req = net.requests.first().second
        assertEquals("""{"kinds":[31923,31922],"limit":200}""", req[2].toString())

        net.requests.clear()
        val world = calendar().fetchEvents(worldwide = true)
        assertEquals(setOf("Früh", "App", "Fremd"), world.map { it.title }.toSet())
        assertEquals(4, net.requests.size)
    }

    @Test
    fun `RSVP setStatus succeeds on any OK true and reports failures`() = runTest {
        val address = "31923:${"ab".repeat(32)}:D"
        assertNull(rsvp().setStatus(address, "ab".repeat(32), RsvpStatus.ACCEPTED))
        val ev = net.published.first().second
        assertEquals(31925, ev["kind"]!!.jsonPrimitive.content.toInt())
        assertEquals("", ev["content"]!!.jsonPrimitive.content)
        assertEquals(rsvpTags(address), ev.tags())
        // Every read target got it (4 fixed + no extra active relays).
        assertEquals(CalendarRelays.RSVP_BASE.toSet(), net.published.map { it.first }.toSet())

        net.accept = { false }
        assertEquals(RsvpError.NotAccepted, rsvp().setStatus(address, "", RsvpStatus.DECLINED))
        net.accept = { it == "wss://nos.lol" }
        assertNull(rsvp().setStatus(address, "", RsvpStatus.DECLINED))
        signer.failSigning = IllegalStateException("abgebrochen")
        val err = rsvp().setStatus(address, "", RsvpStatus.DECLINED)
        assertTrue(err is RsvpError.SigningFailed && err.message.contains("abgebrochen"))
    }

    private fun rsvpTags(address: String) = listOf(
        listOf("a", address),
        listOf("d", "rsvp-31923-${"ab".repeat(32)}-d"),
        listOf("status", "accepted"),
        listOf("p", "ab".repeat(32)),
    )

    @Test
    fun `RSVP loadMine - newest per address, future and foreign events ignored`() = runTest {
        val kp = signer.keyPair!!
        fun r(addr: String, status: String, at: Long) =
            signedRaw(kp, 31925, listOf(listOf("a", addr), listOf("d", "x"), listOf("status", status)), createdAt = at)
        net.events[CalendarRelays.COMMUNITY] = listOf(r("A", "accepted", 1_700_000_000L), r("B", "declined", 1_700_000_000L))
        net.events["wss://nos.lol"] = listOf(
            r("A", "declined", 1_700_000_100L),
            r("B", "accepted", 1_800_100_000L), // > now + 600 s
            signedRaw(KeyPair.generate(), 31925, listOf(listOf("a", "C"), listOf("status", "accepted"))),
        )
        val mine = rsvp().loadMine()
        assertEquals(mapOf("A" to RsvpStatus.DECLINED, "B" to RsvpStatus.DECLINED), mine)
        val req = net.requests.first().second
        assertEquals("rsvp", req[1].jsonPrimitive.content)
        assertEquals("""{"kinds":[31925],"authors":["$me"],"limit":300}""", req[2].toString())

        val before = net.requests.size
        signer.keyPair = null
        assertTrue(rsvp().loadMine().isEmpty())
        assertEquals(before, net.requests.size)
    }
}
