package space.einundzwanzig.meetup.data.proofs

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartUri
import space.einundzwanzig.meetup.data.proofs.internal.DefaultSatoshiDuellClient
import space.einundzwanzig.meetup.data.proofs.satoshiduell.DuellStatus
import space.einundzwanzig.meetup.data.proofs.satoshiduell.SatoshiDuellClient
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Spec 06 §10.6 SatoshiDuell vectors (MockWebServer). */
class SatoshiDuellClientTest {

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var respond: (RecordedRequest) -> MockResponse = { route(it) }
    private val time = FakeTime()
    private val identity = FakeIdentity()
    private val npub get() = identity.keyPair!!.npub

    private val mine = """[
        {"status":"open","creator":"alice","creator_score":null},
        {"status":"active","creator":"bob","challenger":"alice","challenger_score":null},
        {"status":"active","creator":"alice","creator_score":5},
        {"status":"open","creator":"bob","target_player":"alice"},
        {"status":"finished","creator":"alice","creator_score":null},
        "junk"
    ]"""

    private val lobby = """[
        {"creator":"alice"},
        {"creator":"bob","target_player":null},
        {"creator":"carl","target_player":""},
        {"creator":"dan","target_player":"alice"},
        {"creator":"eve","target_player":"bob"}
    ]"""

    private fun ok(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

    private fun route(r: RecordedRequest): MockResponse = when {
        r.target.startsWith("/rest/v1/profiles") -> ok("""[{"username":"Alice"}]""")
        r.target.contains("or=(") -> ok(mine)
        r.target.contains("status=eq.open") -> ok(lobby)
        else -> MockResponse.Builder().code(404).build()
    }

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

    private fun client() = DefaultSatoshiDuellClient(
        OkHttpClient(),
        identity,
        time,
        log,
        testDispatcher,
        server.url("/rest/v1").toString().removeSuffix("/"),
    )

    @Test
    fun `categorises my games and the lobby with exact requests`() = runBlocking<Unit> {
        val c = client()
        val status = c.fetchStatus()
        assertEquals(DuellStatus(myTurn = 2, waiting = 2, lobby = 3), status)
        assertEquals(5, status.actionCount)
        assertEquals(
            listOf(
                "/rest/v1/profiles?npub=eq.$npub&select=username&limit=1",
                "/rest/v1/duels?select=status,creator,challenger,target_player,creator_score,challenger_score" +
                    "&or=(creator.eq.%22alice%22,challenger.eq.%22alice%22,target_player.eq.%22alice%22)" +
                    "&order=created_at.desc&limit=50",
                "/rest/v1/duels?select=creator,target_player&status=eq.open&limit=100",
            ),
            requests.map { it.target },
        )
        for (r in requests) {
            assertEquals(SatoshiDuellClient.PUBLISHABLE_KEY, r.headers["apikey"])
            assertEquals("Bearer ${SatoshiDuellClient.PUBLISHABLE_KEY}", r.headers["Authorization"])
            assertEquals("application/json", r.headers["Accept"])
        }
        // Cached for 2 minutes.
        assertEquals(status, c.fetchStatus())
        assertEquals(3, requests.size)
        time.millis += 121_000
        c.fetchStatus()
        assertEquals(6, requests.size)
        // forceRefresh bypasses the cache.
        c.openDuelCount(forceRefresh = true)
        assertEquals(9, requests.size)
    }

    @Test
    fun `profiles 500 gives EMPTY and is not cached`() = runBlocking<Unit> {
        respond = { MockResponse.Builder().code(500).build() }
        val c = client()
        assertEquals(DuellStatus.EMPTY, c.fetchStatus())
        assertEquals(DuellStatus.EMPTY, c.fetchStatus())
        assertEquals(2, requests.size)
    }

    @Test
    fun `no account or no npub gives EMPTY`() = runBlocking<Unit> {
        respond = { ok("[]") }
        assertEquals(DuellStatus.EMPTY, client().fetchStatus())
        respond = { ok("""[{"username":""}]""") }
        assertEquals(DuellStatus.EMPTY, client().fetchStatus())
        val before = requests.size
        val noKey = DefaultSatoshiDuellClient(OkHttpClient(), FakeIdentity(keyPair = null), time, log, testDispatcher, server.url("/rest/v1").toString())
        assertEquals(DuellStatus.EMPTY, noKey.fetchStatus())
        assertEquals(before, requests.size)
    }

    @Test
    fun `failure after a successful run returns the cached value`() = runBlocking<Unit> {
        val c = client()
        val first = c.fetchStatus()
        respond = { r -> if (r.target.contains("or=(")) ok("not json") else route(r) }
        assertEquals(first, c.fetchStatus(forceRefresh = true))
    }

    @Test
    fun `non-200 sub-requests count zero`() = runBlocking<Unit> {
        respond = { r -> if (r.target.startsWith("/rest/v1/profiles")) route(r) else MockResponse.Builder().code(401).build() }
        assertEquals(DuellStatus(0, 0, 0), client().fetchStatus())
    }

    @Test
    fun `query component encoder matches Dart`() {
        assertEquals("%22a+b~%2A%27%22", DartUri.encodeQueryComponent("\"a b~*'\""))
    }

    @Test
    fun `web app url`() {
        assertEquals("https://satoshiduell.de/?npub=npub1abc", client().webAppUrl("npub1abc"))
        assertEquals("https://satoshiduell.de/", client().webAppUrl(null))
    }
}
