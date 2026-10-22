package space.einundzwanzig.meetup.data.proofs

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.toJsonObject
import space.einundzwanzig.meetup.data.proofs.internal.DefaultNip05Service
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05DomainType
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Result
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Service
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Spec 06 §10.6 NIP-05 vectors (classification, score, HTTP, kind-0 lookup). */
class Nip05ServiceTest {

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var respond: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(404).build() }
    private val hex = "ab".repeat(32)
    private val time = FakeTime(1_760_000_000_000L)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return respond(request)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun service(relays: FakeRelays = FakeRelays()) =
        DefaultNip05Service(clientFor(server), RelayClient(relays.connector), time, log, testDispatcher)

    @Test
    fun `domain classification`() {
        assertEquals(Nip05DomainType.COMMUNITY, Nip05Service.classify("einundzwanzig.space"))
        assertEquals(Nip05DomainType.COMMUNITY, Nip05Service.classify("meetup.einundzwanzig.space"))
        assertEquals(Nip05DomainType.CUSTOM, Nip05Service.classify("xeinundzwanzig.space"))
        assertEquals(Nip05DomainType.COMMUNITY, Nip05Service.classify("nostr.com"))
        assertEquals(Nip05DomainType.PUBLIC_PROVIDER, Nip05Service.classify("iris.to"))
        assertEquals(Nip05DomainType.PUBLIC_PROVIDER, Nip05Service.classify("sub.damus.io"))
        assertEquals(Nip05DomainType.CUSTOM, Nip05Service.classify("example.org"))
    }

    @Test
    fun `score and labels`() {
        assertEquals(0.0, Nip05Service.score(Nip05Result(false, "a@b", "b", Nip05DomainType.COMMUNITY)), 0.0)
        assertEquals(1.0, Nip05Service.score(Nip05Result(true, "a@b", "b", Nip05DomainType.COMMUNITY)), 0.0)
        assertEquals(0.7, Nip05Service.score(Nip05Result(true, "a@b", "b", Nip05DomainType.CUSTOM)), 0.0)
        assertEquals(0.3, Nip05Service.score(Nip05Result(true, "a@b", "b", Nip05DomainType.PUBLIC_PROVIDER)), 0.0)
        assertEquals(0.2, Nip05Service.score(Nip05Result(true, "a@b")), 0.0)
        assertEquals("Community-verifiziert", Nip05Result(true, "a@b", "b", Nip05DomainType.COMMUNITY).domainLabel)
        assertEquals("", Nip05Result(true, "a@b").domainLabel)
    }

    @Test
    fun `valid lookup lowercases name and domain`() = runBlocking<Unit> {
        respond = { MockResponse.Builder().code(200).body("""{"names":{"bob":"$hex"}}""").build() }
        val r = service().verify("Bob@Domain.Example", hex)
        assertTrue(r.valid)
        assertEquals("Bob@Domain.Example", r.nip05)
        assertEquals("domain.example", r.domain)
        assertEquals(Nip05DomainType.CUSTOM, r.domainType)
        assertEquals("/.well-known/nostr.json?name=bob", requests.single().target)
        assertEquals("domain.example", requests.single().headers["X-Original-Host"])
    }

    @Test
    fun `non-200 is invalid with domain`() = runBlocking<Unit> {
        respond = { MockResponse.Builder().code(404).body("""{"names":{"bob":"$hex"}}""").build() }
        val r = service().verify("bob@example.org", hex)
        assertFalse(r.valid)
        assertEquals("example.org", r.domain)
        assertEquals(Nip05DomainType.UNKNOWN, r.domainType)
    }

    @Test
    fun `body over 64 KiB is invalid, exactly 64 KiB is read`() = runBlocking<Unit> {
        val base = """{"names":{"bob":"$hex"},"pad":""}"""
        fun body(len: Int) = base.substring(0, base.length - 2) + "x".repeat(len - base.length) + "\"}"
        respond = { MockResponse.Builder().code(200).body(body(65_537)).build() }
        assertFalse(service().verify("bob@example.org", hex).valid)
        respond = { MockResponse.Builder().code(200).body(body(65_536)).build() }
        assertTrue(service().verify("bob@example.org", hex).valid)
    }

    @Test
    fun `pubkey mismatch by case is invalid`() = runBlocking<Unit> {
        respond = { MockResponse.Builder().code(200).body("""{"names":{"bob":"${hex.uppercase()}"}}""").build() }
        val r = service().verify("bob@iris.to", hex)
        assertFalse(r.valid)
        assertEquals(Nip05DomainType.PUBLIC_PROVIDER, r.domainType)
    }

    @Test
    fun `names missing is invalid with domain`() = runBlocking<Unit> {
        respond = { MockResponse.Builder().code(200).body("""{"relays":{}}""").build() }
        val r = service().verify("bob@example.org", hex)
        assertFalse(r.valid)
        assertEquals("example.org", r.domain)
        assertEquals(Nip05DomainType.UNKNOWN, r.domainType)
    }

    @Test
    fun `latin1 content type is still decoded as UTF-8`() = runBlocking<Unit> {
        respond = {
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json; charset=iso-8859-1")
                .body(Buffer().writeUtf8("""{"names":{"jörg":"$hex"}}"""))
                .build()
        }
        assertTrue(service().verify("jörg@example.org", hex).valid)
    }

    @Test
    fun `malformed identifiers are invalid without a request`() = runBlocking<Unit> {
        val s = service()
        assertEquals(Nip05Result(false, ""), s.verify("", hex))
        assertEquals(Nip05Result(false, "example.org"), s.verify("example.org", hex))
        assertEquals(Nip05Result(false, "a@b@c"), s.verify("a@b@c", hex))
        assertTrue(requests.isEmpty())
    }

    // ---- fetchNip05FromProfile ----

    private val alice = KeyPair.generate()

    private fun profile(at: Long, content: String) =
        EventIds.sign(alice, at, 0, emptyList(), content).toJsonObject()

    @Test
    fun `newest profile wins across the first two relays only`() = runBlocking<Unit> {
        val relays = FakeRelays()
        relays.add("wss://a", profile(1_750_000_000, """{"nip05":"old@a.example"}"""))
        relays.add("wss://b", profile(1_750_000_100, """{"nip05":"new@b.example"}"""))
        relays.add("wss://c", profile(1_750_000_200, """{"nip05":"third@c.example"}"""))
        val nip05 = service(relays).fetchNip05FromProfile(alice.publicKeyHex, listOf("wss://a", "wss://b", "wss://c"))
        assertEquals("new@b.example", nip05)
        assertEquals(listOf("wss://a", "wss://b"), relays.connector.connects.toList())
        val req = relays.reqs.first().second
        assertTrue(req[1].toString().startsWith("\"nip05-"))
    }

    @Test
    fun `a newer profile without nip05 erases an older one`() = runBlocking<Unit> {
        val relays = FakeRelays()
        relays.add("wss://a", profile(1_750_000_000, """{"nip05":"old@a.example"}"""))
        relays.add("wss://b", profile(1_750_000_100, """{"name":"x"}"""))
        assertNull(service(relays).fetchNip05FromProfile(alice.publicKeyHex, listOf("wss://a", "wss://b")))
    }

    @Test
    fun `future timestamps and non-string nip05 are handled`() = runBlocking<Unit> {
        val relays = FakeRelays()
        relays.add("wss://a", profile(1_750_000_000, """{"nip05":"ok@a.example"}"""))
        relays.add("wss://b", profile(time.millis / 1000 + 601, """{"nip05":"future@b.example"}"""))
        assertEquals("ok@a.example", service(relays).fetchNip05FromProfile(alice.publicKeyHex, listOf("wss://a", "wss://b")))

        val relays2 = FakeRelays()
        relays2.add("wss://a", profile(1_750_000_000, """{"nip05":42}"""))
        assertNull(service(relays2).fetchNip05FromProfile(alice.publicKeyHex, listOf("wss://a")))
    }

    @Test
    fun `equal timestamps across relays keep the first`() = runBlocking<Unit> {
        val relays = FakeRelays()
        relays.add("wss://a", profile(1_750_000_000, """{"nip05":"first@a.example"}"""))
        relays.add("wss://b", profile(1_750_000_000, """{"nip05":"second@b.example"}"""))
        assertEquals("first@a.example", service(relays).fetchNip05FromProfile(alice.publicKeyHex, listOf("wss://a", "wss://b")))
    }
}
