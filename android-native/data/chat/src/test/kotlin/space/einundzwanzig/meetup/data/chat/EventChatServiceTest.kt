package space.einundzwanzig.meetup.data.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.data.chat.internal.DefaultChatReadMarkStore
import space.einundzwanzig.meetup.data.chat.internal.DefaultEventChatService
import space.einundzwanzig.meetup.data.identity.SigningException
import java.time.Instant

/** [DefaultEventChatService] against plain scripted relays (spec 11 §6.5, §8.8). */
@OptIn(ExperimentalCoroutinesApi::class)
class EventChatServiceTest {

    private val author = KeyPair.generate()
    private val other = KeyPair.generate()
    private val address = "31923:${author.publicKeyHex}:abc"
    private val r1 = ScriptedRelay("wss://r1.test", requireAuth = false)
    private val r2 = ScriptedRelay("wss://r2.test", requireAuth = false)
    private val dead = "wss://dead.test"
    private val signer = FakeSigner()
    private val prefs = InMemoryKeyValueStore()

    private fun TestScope.service(connector: FakeRelayConnector = connectorFor(r1, r2)) = DefaultEventChatService(
        relayClient = RelayClient(connector, StandardTestDispatcher(testScheduler)),
        signing = signer.service,
        targets = FakeTargets(listOf(r1.url, dead, r2.url)),
        readMarks = DefaultChatReadMarkStore(prefs),
        time = fixedTime,
    )

    private fun comment(key: KeyPair, content: String, createdAt: Long, addr: String = address) =
        relayEvent(key, 1111, EventChatService.commentTags(addr, author.publicKeyHex), content, createdAt)

    private fun ScriptedRelay.answer(vararg events: JsonObject) {
        onReq = { sub, _, s ->
            events.forEach { s.push(listOf("EVENT", sub, it)) }
            s.push(listOf("EOSE", sub))
        }
    }

    @Test
    fun `send publishes the NIP-22 comment to every read target, one OK suffices`() = runTest {
        r1.onEvent = { ev, s -> s.push(listOf("OK", ev.id(), false, "blocked")) }
        assertNull(service().send(address, author.publicKeyHex, "  Bin dabei!  "))
        val ev = r2.published.single()
        assertEquals(1111L, ev["kind"]!!.jsonPrimitive.long)
        assertEquals("Bin dabei!", ev["content"]!!.jsonPrimitive.content)
        assertEquals(
            """[["A","$address"],["K","31923"],["P","${author.publicKeyHex}"],["a","$address"],["k","31923"],["p","${author.publicKeyHex}"]]""",
            DartJson.encode(ev["tags"]!!),
        )
        assertEquals(ev, r1.published.single())

        r2.onEvent = { ev2, s -> s.push(listOf("OK", ev2.id(), false, "blocked")) }
        assertEquals("Kein Relay hat den Beitrag angenommen", service().send(address, "", "x"))
        assertNull(service().send(address, "", " "))
        signer.failSigning = SigningException("abgelehnt")
        assertEquals("abgelehnt", service().send(address, "", "x"))
    }

    @Test
    fun `load merges all relays by id, oldest first`() = runTest {
        val a = comment(other, "erster", NOW_SECONDS - 300)
        val b = comment(author, "zweiter", NOW_SECONDS - 200)
        val c = comment(other, "dritter", NOW_SECONDS - 100)
        r1.answer(c, a)
        r2.answer(a, b, comment(other, "anderer Termin", NOW_SECONDS - 50, addr = "31923:x:y"))
        val msgs = service().loadMessages(address)
        assertEquals(listOf("erster", "zweiter", "dritter"), msgs.map { it.content })
        assertEquals("""["REQ","q",{"kinds":[1111],"#A":["$address"],"limit":200}]""", r1.reqs.single())
    }

    @Test
    fun `unread counts de-duplicate across relays and use the evt read mark`() = runTest {
        val mark = NOW_SECONDS - 1000
        prefs.setStringList(ChatReadMarkStore.KEY, listOf("evt|$address|$mark", "$address|1"))
        val other2 = "31922:${author.publicKeyHex}:xyz"
        val fresh = comment(other, "neu", mark + 10)
        r1.answer(fresh, comment(other, "alt", mark), comment(signer.keyPair!!, "eigener", mark + 20))
        r2.answer(fresh, comment(other, "zweiter Termin", NOW_SECONDS - 3 * 86_400, addr = other2))
        val counts = service().unreadCounts(listOf(address, other2))
        assertEquals(mapOf(address to 1, other2 to 1), counts)
        // since = min(mark, now - 14 days)
        assertEquals(
            """["REQ","q",{"kinds":[1111],"#A":["$address","$other2"],"since":${NOW_SECONDS - 14 * 86_400},"limit":300}]""",
            r1.reqs.single(),
        )
    }

    @Test
    fun `live thread uses the first reachable relay`() = runTest {
        r1.unreachable = java.io.IOException("Connection refused")
        r2.onReq = { sub, _, s ->
            s.push(listOf("EOSE", sub))
            s.push(listOf("EVENT", sub, comment(other, "live", NOW_SECONDS - 1)))
        }
        val connector = connectorFor(r1, r2)
        val received = mutableListOf<ChatMessage>()
        val job = launch { service(connector).subscribe(address, Instant.ofEpochSecond(NOW_SECONDS - 10)).collect { received += it } }
        runCurrent()
        assertEquals(listOf("live"), received.map { it.content })
        assertEquals(listOf(r1.url, dead, r2.url), connector.connects)
        assertEquals("""["REQ","evtchat",{"kinds":[1111],"#A":["$address"],"since":${NOW_SECONDS - 10}}]""", r2.reqs.single())
        job.cancel()
        runCurrent()
        assertTrue(r2.sockets.single().isClosed)

        r2.unreachable = java.io.IOException("Connection refused")
        assertEquals(emptyList<ChatMessage>(), service().subscribe(address).toList())
    }

    @Test
    fun `an IOException of the collector is not swallowed by the live thread`() = runTest {
        r1.onReq = { sub, _, s ->
            s.push(listOf("EOSE", sub))
            s.push(listOf("EVENT", sub, comment(other, "live", NOW_SECONDS - 1)))
        }
        val own = java.io.IOException("collector")
        val thrown = runCatching { service().subscribe(address).collect { throw own } }.exceptionOrNull()
        assertEquals(own.toString(), thrown.toString())
    }
}
