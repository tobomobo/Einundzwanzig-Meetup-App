package space.einundzwanzig.meetup.data.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnectException
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.data.chat.internal.DefaultChatReadMarkStore
import space.einundzwanzig.meetup.data.chat.internal.DefaultChatService
import space.einundzwanzig.meetup.data.chat.internal.hasUsableNetwork
import space.einundzwanzig.meetup.data.identity.SigningException
import space.einundzwanzig.meetup.data.identity.SigningMode
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** [DefaultChatService] against a scripted NIP-42 group relay (spec 11 §6.4, §8.8). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatServiceTest {

    private val relayKey = KeyPair.generate()
    private val other = KeyPair.generate()
    private val relay = ScriptedRelay(GROUP_RELAY)
    private val signer = FakeSigner()
    private val me get() = signer.keyPair!!.publicKeyHex
    private val prefs = InMemoryKeyValueStore()
    private var online = true

    private fun TestScope.service(connector: FakeRelayConnector = connectorFor(relay)) = DefaultChatService(
        relayClient = RelayClient(connector, StandardTestDispatcher(testScheduler)),
        signing = signer.service,
        readMarks = DefaultChatReadMarkStore(prefs),
        time = fixedTime,
        networkStatus = { online },
    )

    private fun room(h: String, createdAt: Long, vararg extra: List<String>) =
        relayEvent(relayKey, 39000, listOf(listOf("d", h), listOf("t", "meetup")) + extra.toList(), createdAt = createdAt)

    private fun answer(vararg events: JsonObject) {
        relay.onReq = { sub, _, s ->
            events.forEach { s.push(listOf("EVENT", sub, it)) }
            s.push(listOf("EOSE", sub))
        }
    }

    @Test
    fun `room list authenticates first, keeps the newest per room and is cached`() = runTest {
        answer(
            room("r1", NOW_SECONDS - 100, listOf("name", "Alt")),
            room("r1", NOW_SECONDS - 50, listOf("name", "Neu"), listOf("meetup_slug", "einundzwanzig-saarbruecken")),
            room("r2", NOW_SECONDS - 10, listOf("name", "Zwei")),
            // Future timestamps beyond 600 s cannot win.
            room("r2", NOW_SECONDS + 3600, listOf("name", "Zukunft")),
        )
        val connector = connectorFor(relay)
        val svc = service(connector)
        val rooms = svc.loadMeetupRooms()
        assertEquals(listOf("r1" to "Neu", "r2" to "Zwei"), rooms.map { it.h to it.name })
        assertEquals("""["REQ","q",{"kinds":[39000],"#t":["meetup"],"limit":500}]""", relay.reqs.single())
        val auth = relay.auths.single()
        assertEquals(22242L, auth["kind"]!!.jsonPrimitive.long)
        assertEquals("""[["relay","wss://group.einundzwanzig.space"],["challenge","challenge-1"]]""", DartJson.encode(auth["tags"]!!))
        assertEquals("", auth["content"]!!.jsonPrimitive.content)
        assertTrue(relay.sockets.single().isClosed)

        // Cached: no second connection; force bypasses the cache.
        assertEquals(rooms, svc.loadMeetupRooms())
        assertEquals(1, connector.connects.size)
        svc.loadMeetupRooms(force = true)
        assertEquals(2, connector.connects.size)

        assertEquals("r1", svc.findRoomForCity("Saarbrücken")!!.h)
        assertEquals("r1", svc.findMeetupRoom("EINUNDZWANZIG-Saarbruecken")!!.h)
        assertNull(svc.findRoomForCity("Berlin"))
        assertEquals(2, connector.connects.size)
    }

    @Test
    fun `empty room list is not cached`() = runTest {
        val connector = connectorFor(relay)
        val svc = service(connector)
        assertEquals(emptyList<ChatRoom>(), svc.loadMeetupRooms())
        assertEquals(emptyList<ChatRoom>(), svc.loadMeetupRooms())
        assertEquals(2, connector.connects.size)
    }

    @Test
    fun `no answer throws ChatRelayUnavailable, stale cache is used`() = runTest {
        answer(room("r1", NOW_SECONDS - 10))
        val svc = service()
        svc.loadMeetupRooms()
        relay.unreachable = IOException("Connection refused")
        assertEquals("r1", svc.loadMeetupRooms(force = true).single().h)
        // Nothing cached for this id: the exact lookup has no answer.
        val e = runCatching { svc.findRoomForMeetupId("42") }.exceptionOrNull()
        assertTrue(e is ChatRelayUnavailable)
        assertFalse((e as ChatRelayUnavailable).offline)
        // The reason travels as the cause (diagnostics); the user-visible text is unchanged.
        assertTrue(e.cause is RelayConnectException)
        assertEquals("Connection refused", e.cause!!.cause!!.message)
        assertEquals("Gruppen-Relay ohne Auskunft", e.toString())
    }

    @Test
    fun `ChatRelayUnavailable carries the AUTH, CLOSED and offline reasons`() = runTest {
        relay.unreachable = UnknownHostException("Unable to resolve host")
        val offline = runCatching { service().findRoomForMeetupId("42") }.exceptionOrNull() as ChatRelayUnavailable
        assertTrue(offline.offline)
        assertTrue(offline.cause!!.cause is UnknownHostException)
        assertEquals("Keine Internetverbindung", offline.toString())

        relay.unreachable = null
        relay.onReq = { sub, _, s -> s.push(listOf("CLOSED", sub, "restricted: nope")) }
        val closed = runCatching { service().loadMeetupRooms() }.exceptionOrNull() as ChatRelayUnavailable
        assertEquals("CLOSED: restricted: nope", closed.cause!!.message)

        relay.acceptAuth = false
        val rejected = runCatching { service().findRoomForMeetupId("42") }.exceptionOrNull()
        assertTrue(rejected is ChatRelayUnavailable)
        assertEquals("Gruppen-Relay ohne Auskunft", rejected.toString())
    }

    @Test
    fun `room by portal id caches found rooms only and serialises lookups of one id`() = runTest {
        val connector = connectorFor(relay)
        val svc = service(connector)
        // No room: not cached, the next lookup asks again.
        assertNull(svc.findRoomForMeetupId("42"))
        answer(room("r1", NOW_SECONDS - 10, listOf("i", "meetup:42")))
        // Concurrent lookups of the same id: one connection, the waiter takes the cached room.
        val found = List(3) { async { svc.findRoomForMeetupId("42")?.h } }.awaitAll()
        assertEquals(listOf("r1", "r1", "r1"), found)
        assertEquals(2, connector.connects.size)
        // Cached: no further connection (no further Amber prompt) for the next dashboard refresh.
        assertEquals("r1", svc.findRoomForMeetupId("42")!!.h)
        assertEquals(2, connector.connects.size)
        // Another id is its own lookup; a failure is not cached.
        relay.unreachable = IOException("Connection refused")
        assertTrue(runCatching { svc.findRoomForMeetupId("43") }.exceptionOrNull() is ChatRelayUnavailable)
        relay.unreachable = null
        assertEquals("r1", svc.findRoomForMeetupId("42")!!.h)
        assertEquals(3, connector.connects.size)
        // An explicit reload drops the per-id answers.
        svc.loadMeetupRooms(force = true)
        assertEquals("r1", svc.findRoomForMeetupId("42")!!.h)
        assertEquals(5, connector.connects.size)
    }

    @Test
    fun `send failing after a successful AUTH reports the send error, not an AUTH failure`() = runTest {
        // The relay accepts AUTH and drops the connection right after the OK: the EVENT cannot go out.
        relay.afterAuth = { s -> s.finish() }
        val svc = service()
        assertEquals("Zeitüberschreitung", svc.send("r1", "hallo"))
        relay.afterAuth = { s -> s.finish(IOException("Software caused connection abort")) }
        assertEquals("Software caused connection abort", svc.join("r1"))
        assertEquals(2, relay.auths.size)
        assertTrue(relay.published.isEmpty())
    }

    @Test
    fun `offline is detected from the connect failure`() = runTest {
        relay.unreachable = UnknownHostException("Unable to resolve host \"group.einundzwanzig.space\": No address associated with hostname")
        val svc = service()
        try {
            svc.findRoomForCity("Saarbrücken")
            fail("expected ChatRelayUnavailable")
        } catch (e: ChatRelayUnavailable) {
            assertTrue(e.offline)
        }
        // Lists degrade to empty / false / 0.
        assertEquals(emptyList<ChatMessage>(), svc.loadMessages("r1"))
        assertFalse(svc.isMember("r1"))
        assertEquals(mapOf("r1" to 0), svc.unreadCounts(listOf("r1")))
        assertEquals("Anmeldung am Relay fehlgeschlagen", svc.send("r1", "hallo"))
    }

    @Test
    fun `connect failure without any usable network counts as offline`() = runTest {
        // E.g. a connect timeout right after airplane mode: the exception alone says "relay down".
        relay.unreachable = IOException("timeout")
        val svc = service()
        online = true
        assertFalse((runCatching { svc.findRoomForMeetupId("42") }.exceptionOrNull() as ChatRelayUnavailable).offline)
        online = false
        assertTrue((runCatching { svc.findRoomForMeetupId("42") }.exceptionOrNull() as ChatRelayUnavailable).offline)
    }

    @Test
    fun `connected but unvalidated network - relay outage is not offline`() = runTest {
        // Internet works but Google's check is blocked (VPN, private DNS, corporate Wi-Fi): an
        // active network with INTERNET and without VALIDATED is online, so a refused or timed-out
        // connect stays chatRelayUnavailable (Dart dacccf4).
        assertTrue(hasUsableNetwork(hasActiveNetwork = true, hasInternet = true))
        assertFalse(hasUsableNetwork(hasActiveNetwork = true, hasInternet = false))
        assertFalse(hasUsableNetwork(hasActiveNetwork = false, hasInternet = false))
        online = hasUsableNetwork(hasActiveNetwork = true, hasInternet = true)
        val svc = service()
        for (cause in listOf(IOException("Connection refused"), SocketTimeoutException("connect timed out"))) {
            relay.unreachable = cause
            val e = runCatching { svc.loadMeetupRooms(force = true) }.exceptionOrNull()
            assertFalse((e as ChatRelayUnavailable).offline)
        }
    }

    @Test
    fun `concurrent room lookups share one authenticated fetch`() = runTest {
        answer(room("r1", NOW_SECONDS - 10, listOf("meetup_slug", "einundzwanzig-saarbruecken")))
        val connector = connectorFor(relay)
        val svc = service(connector)
        val found = listOf(
            async { svc.findRoomForCity("Saarbrücken")?.h },
            async { svc.findMeetupRoom("einundzwanzig-saarbruecken")?.h },
            async { svc.loadMeetupRooms().single().h },
        ).awaitAll()
        assertEquals(listOf("r1", "r1", "r1"), found)
        assertEquals(1, connector.connects.size)
        assertEquals(1, relay.auths.size)

        // A shared failure is not retried by the waiters either.
        relay.unreachable = IOException("Connection refused")
        val fresh = service(connector)
        val errors = List(3) { async { runCatching { fresh.loadMeetupRooms() }.exceptionOrNull() } }.awaitAll()
        assertTrue(errors.all { it is ChatRelayUnavailable })
        assertEquals(2, connector.connects.size)
    }

    @Test
    fun `force issued during an in-flight fetch performs its own REQ`() = runTest {
        // The first REQ is answered only on demand; later ones at once.
        val pending = mutableListOf<Pair<String, FakeRelaySocket>>()
        var holdFirst = true
        relay.onReq = { sub, _, s ->
            if (holdFirst) {
                holdFirst = false
                pending += sub to s
            } else {
                s.push(listOf("EVENT", sub, room("r2", NOW_SECONDS - 5)))
                s.push(listOf("EOSE", sub))
            }
        }
        val connector = connectorFor(relay)
        val svc = service(connector)
        val first = async { svc.loadMeetupRooms(force = true).map { it.h } }
        runCurrent()
        assertEquals(1, relay.reqs.size)
        // Asked while the first fetch is in flight: must not get that older answer.
        val reload = async { svc.loadMeetupRooms(force = true).map { it.h } }
        // A normal lookup queued behind it shares the next finished fetch (the reload's).
        val lookup = async { svc.loadMeetupRooms().map { it.h } }
        runCurrent()
        val (sub, socket) = pending.single()
        socket.push(listOf("EVENT", sub, room("r1", NOW_SECONDS - 10)))
        socket.push(listOf("EOSE", sub))
        assertEquals(listOf("r1"), first.await())
        assertEquals(listOf("r2"), reload.await())
        assertEquals(listOf("r2"), lookup.await())
        assertEquals(2, relay.reqs.size)
        assertEquals(2, connector.connects.size)
    }

    @Test
    fun `room by portal id - exact filter, newest wins, empty answer is null`() = runTest {
        val svc = service()
        assertNull(svc.findRoomForMeetupId("42"))
        assertEquals("""["REQ","q",{"kinds":[39000],"#i":["meetup:42"],"limit":5}]""", relay.reqs.single())
        answer(
            room("old", NOW_SECONDS - 100, listOf("i", "meetup:42")),
            room("new", NOW_SECONDS - 100, listOf("i", "meetup:42")),
            room("older", NOW_SECONDS - 200, listOf("i", "meetup:42")),
        )
        assertEquals("new", svc.findRoomForMeetupId("42")!!.h)
        assertNull(svc.findRoomForMeetupId(""))
    }

    @Test
    fun `events not matching the filter or with bad signatures are dropped`() = runTest {
        val good = room("r1", NOW_SECONDS - 10, listOf("i", "meetup:42"))
        val wrongBinding = room("r2", NOW_SECONDS - 5, listOf("i", "meetup:43"))
        val forged = JsonObject(room("r3", NOW_SECONDS - 1, listOf("i", "meetup:42")) + ("content" to JsonPrimitive("x")))
        answer(good, wrongBinding, forged)
        assertEquals("r1", service().findRoomForMeetupId("42")!!.h)
    }

    @Test
    fun `rejected AUTH means no answer`() = runTest {
        relay.acceptAuth = false
        val svc = service()
        assertTrue(runCatching { svc.findRoomForMeetupId("42") }.exceptionOrNull() is ChatRelayUnavailable)
        assertTrue(relay.reqs.isEmpty())
        assertEquals("Anmeldung am Relay fehlgeschlagen", svc.join("r1"))
    }

    @Test
    fun `CLOSED auth-required is retried exactly once`() = runTest {
        var calls = 0
        relay.onReq = { sub, _, s ->
            calls++
            if (calls == 1) s.push(listOf("CLOSED", sub, "auth-required: too early")) else s.push(listOf("EOSE", sub))
        }
        val connector = connectorFor(relay)
        assertNull(service(connector).findRoomForMeetupId("42"))
        assertEquals(2, connector.connects.size)

        relay.onReq = { sub, _, s -> s.push(listOf("CLOSED", sub, "auth-required: never")) }
        val connector2 = connectorFor(relay)
        assertTrue(runCatching { service(connector2).findRoomForMeetupId("42") }.exceptionOrNull() is ChatRelayUnavailable)
        assertEquals(2, connector2.connects.size)

        relay.onReq = { sub, _, s -> s.push(listOf("CLOSED", sub, "restricted")) }
        val connector3 = connectorFor(relay)
        assertTrue(runCatching { service(connector3).findRoomForMeetupId("42") }.exceptionOrNull() is ChatRelayUnavailable)
        assertEquals(1, connector3.connects.size)
    }

    @Test
    fun `timeout without EOSE and events is no answer, partial events count`() = runTest {
        relay.onReq = { _, _, _ -> }
        val svc = service()
        assertTrue(runCatching { svc.findRoomForMeetupId("42") }.exceptionOrNull() is ChatRelayUnavailable)
        relay.onReq = { sub, _, s -> s.push(listOf("EVENT", sub, room("r1", NOW_SECONDS - 1, listOf("i", "meetup:42")))) }
        assertEquals("r1", svc.findRoomForMeetupId("42")!!.h)
    }

    @Test
    fun `unread counts per room (spec 10_4 vector)`() = runTest {
        val mark = 1_792_000_000L
        prefs.setStringList(ChatReadMarkStore.KEY, listOf("h1|$mark"))
        fun msg(key: KeyPair, ts: Long, vararg hs: String) =
            relayEvent(key, 9, hs.map { listOf("h", it) }, "x", createdAt = ts)
        answer(
            msg(other, mark + 1, "h1"), // counts
            msg(other, mark, "h1"), // not newer than the mark
            msg(signer.keyPair!!, mark + 500, "h1"), // own
            msg(other, mark + 2, "h2", "h1"), // first h is not requested → skipped
            msg(other, NOW_SECONDS - 3600, "h3"), // never opened: 7-day window
            msg(other, NOW_SECONDS - 8 * 86_400, "h3"), // older than the window
        )
        val counts = service().unreadCounts(listOf("h1", "h3"))
        assertEquals(mapOf("h1" to 1, "h3" to 1), counts)
        assertEquals("""["REQ","q",{"kinds":[9],"#h":["h1","h3"],"since":${NOW_SECONDS - 7 * 86_400},"limit":500}]""", relay.reqs.single())
        assertEquals(emptyMap<String, Int>(), service().unreadCounts(emptyList()))
    }

    @Test
    fun `membership follows the newest member list`() = runTest {
        val svc = service()
        answer(
            relayEvent(relayKey, 39002, listOf(listOf("d", "r1"), listOf("p", me)), createdAt = NOW_SECONDS - 100),
            relayEvent(relayKey, 39002, listOf(listOf("d", "r1"), listOf("p", other.publicKeyHex)), createdAt = NOW_SECONDS - 10),
        )
        assertFalse(svc.isMember("r1"))
        assertEquals("""["REQ","q",{"kinds":[39002],"#d":["r1"],"limit":5}]""", relay.reqs.last())
        answer(relayEvent(relayKey, 39002, listOf(listOf("d", "r1"), listOf("p", other.publicKeyHex), listOf("p", me))))
        assertTrue(svc.isMember("r1"))

        val anonymous = FakeSigner(keyPair = null)
        val connector = connectorFor(relay)
        val svc2 = DefaultChatService(RelayClient(connector, StandardTestDispatcher(testScheduler)), anonymous.service, DefaultChatReadMarkStore(prefs), fixedTime) { true }
        assertFalse(svc2.isMember("r1"))
        assertTrue(connector.connects.isEmpty())
    }

    @Test
    fun `messages are loaded oldest first`() = runTest {
        answer(
            relayEvent(other, 9, listOf(listOf("h", "r1")), "zwei", createdAt = NOW_SECONDS - 10),
            relayEvent(other, 9, listOf(listOf("h", "r1")), "eins", createdAt = NOW_SECONDS - 20),
        )
        val msgs = service().loadMessages("r1")
        assertEquals(listOf("eins", "zwei"), msgs.map { it.content })
        assertEquals("""["REQ","q",{"kinds":[9],"#h":["r1"],"limit":100}]""", relay.reqs.single())
    }

    @Test
    fun `an event sent twice in one answer is loaded once`() = runTest {
        val twice = relayEvent(other, 9, listOf(listOf("h", "r1")), "eins", createdAt = NOW_SECONDS - 20)
        answer(twice, twice)
        assertEquals(listOf("eins"), service().loadMessages("r1").map { it.content })
    }

    @Test
    fun `send, join and leave publish the exact events and map the outcome`() = runTest {
        val svc = service()
        assertNull(svc.send("r1", "  Servus  "))
        val sent = relay.published.single()
        assertEquals(9L, sent["kind"]!!.jsonPrimitive.long)
        assertEquals("""[["h","r1"]]""", DartJson.encode(sent["tags"]!!))
        assertEquals("Servus", sent["content"]!!.jsonPrimitive.content)

        assertNull(svc.join("r1"))
        assertEquals(9021L, relay.published.last()["kind"]!!.jsonPrimitive.long)
        assertEquals("", relay.published.last()["content"]!!.jsonPrimitive.content)
        assertEquals("""[["h","r1"]]""", DartJson.encode(relay.published.last()["tags"]!!))
        assertNull(svc.leave("r1"))
        assertEquals(9022L, relay.published.last()["kind"]!!.jsonPrimitive.long)

        relay.onEvent = { ev, s -> s.push(listOf("OK", ev.id(), false, "blocked: kein Vereinsmitglied")) }
        assertEquals("blocked: kein Vereinsmitglied", svc.join("r1"))
        relay.onEvent = { ev, s -> s.push(listOf("OK", ev.id(), false)) }
        assertEquals("", svc.send("r1", "x"))
        relay.onEvent = { _, _ -> }
        assertEquals("Zeitüberschreitung", svc.send("r1", "x"))

        val before = relay.published.size
        assertNull(svc.send("r1", "   "))
        assertEquals(before, relay.published.size)

        signer.failSigning = SigningException("Signatur abgelehnt")
        assertEquals("Signatur abgelehnt", svc.send("r1", "x"))
    }

    @Test
    fun `live subscription sends the live REQ, emits verified matching messages and closes on cancel`() = runTest {
        relay.onReq = { sub, _, s ->
            s.push(listOf("EOSE", sub))
            s.push(listOf("EVENT", sub, relayEvent(other, 9, listOf(listOf("h", "r1")), "neu", createdAt = NOW_SECONDS - 1)))
            s.push(listOf("EVENT", sub, relayEvent(other, 9, listOf(listOf("h", "r2")), "fremd", createdAt = NOW_SECONDS - 1)))
            s.push(listOf("EVENT", "other-sub", relayEvent(other, 9, listOf(listOf("h", "r1")), "anderes Abo", createdAt = NOW_SECONDS - 1)))
        }
        val received = mutableListOf<ChatMessage>()
        val job = launch { service().subscribe("r1", Instant.ofEpochSecond(NOW_SECONDS - 5, 999_000_000)).collect { received += it } }
        runCurrent()
        assertEquals(listOf("neu"), received.map { it.content })
        assertEquals("""["REQ","live",{"kinds":[9],"#h":["r1"],"since":${NOW_SECONDS - 5}}]""", relay.reqs.single())
        val socket = relay.sockets.single()
        assertFalse(socket.isClosed)
        // A renewal challenge on the open live socket is answered there.
        socket.push(listOf("AUTH", "renew"))
        runCurrent()
        assertEquals(2, relay.auths.size)
        job.cancel()
        runCurrent()
        assertTrue(socket.isClosed)

        relay.unreachable = IOException("Connection refused")
        assertEquals(emptyList<ChatMessage>(), service().subscribe("r1").toList())
    }

    @Test
    fun `an IOException of the collector is not swallowed by the live subscription`() = runTest {
        relay.onReq = { sub, _, s ->
            s.push(listOf("EOSE", sub))
            s.push(listOf("EVENT", sub, relayEvent(other, 9, listOf(listOf("h", "r1")), "neu", createdAt = NOW_SECONDS - 1)))
        }
        val own = IOException("collector")
        val thrown = runCatching { service().subscribe("r1").collect { throw own } }.exceptionOrNull()
        assertEquals(own.toString(), thrown.toString())
        assertTrue(relay.sockets.single().isClosed)
    }

    @Test
    fun `AUTH deadline depends on the signer`() = runTest {
        assertEquals(8.seconds, DefaultChatService.authTimeout(FakeSigner(mode = SigningMode.LOCAL).service))
        assertEquals(80.seconds, DefaultChatService.authTimeout(FakeSigner(mode = SigningMode.NIP46).service))
        assertEquals(25.seconds, DefaultChatService.authTimeout(FakeSigner(mode = SigningMode.AMBER).service))
        assertEquals(25.seconds, DefaultChatService.authTimeout(FakeSigner(mode = SigningMode.NIP07).service))
    }

    @Test
    fun `relay without AUTH challenge fails at the local-key deadline`() = runTest {
        // No grace period: without AUTH the request is never sent and the query fails after 8 s.
        relay.requireAuth = false
        val svc = service()
        assertTrue(runCatching { svc.findRoomForMeetupId("42") }.exceptionOrNull() is ChatRelayUnavailable)
        assertTrue(relay.reqs.isEmpty())
        assertEquals(8_000L, testScheduler.currentTime)
    }

}
