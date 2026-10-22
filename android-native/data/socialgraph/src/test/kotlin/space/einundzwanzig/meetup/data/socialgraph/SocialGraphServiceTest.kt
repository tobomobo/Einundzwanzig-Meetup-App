package space.einundzwanzig.meetup.data.socialgraph

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.socialgraph.internal.DefaultSocialGraphService
import space.einundzwanzig.meetup.data.socialgraph.social.SocialAnalysis
import space.einundzwanzig.meetup.data.socialgraph.social.SocialGraphService

/** Spec 05 §8.1 on fake relays: newest kind 3 across relays, cache, analyze, stats. */
class SocialGraphServiceTest {

    private val clock = FakeClock()
    private val relays = RelayConfig.DEFAULT_RELAYS
    private val me = KeyPair.generate()

    /** Per relay: the kind-3 events it stores. Relays without an entry are unreachable. */
    private val stored = HashMap<String, MutableList<NostrEvent>>()
    private val scripted = ScriptedRelays()

    private fun relayHas(i: Int, vararg events: NostrEvent) {
        val url = relays[i]
        stored.getOrPut(url) { mutableListOf() }.addAll(events)
        scripted.onReq[url] = { sub, filter, socket ->
            val authors = filter["authors"]!!.jsonArray.map { it.jsonPrimitive.content }
            for (ev in stored[url].orEmpty()) if (ev.pubkey in authors) socket.pushEvent(sub, ev)
            socket.push(listOf("EOSE", sub))
        }
    }

    private fun kind3(author: KeyPair, at: Long, vararg follows: String): NostrEvent =
        EventIds.sign(author, at, 3, follows.map { listOf("p", it) }, "")

    private fun service(
        store: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        npub: String? = me.npub,
        admins: List<AdminEntry>? = null,
    ) = DefaultSocialGraphService(
        store = store,
        relayClient = RelayClient(scripted.connector),
        relayConfig = RelayConfig(store, scripted.connector),
        ownNpub = { npub },
        organizers = { admins },
        timeSource = clock,
        log = RecordingLog(),
    )

    private val now get() = clock.nowSeconds()

    @Test
    fun `newest contact list across relays wins, even when empty`() = runTest {
        val target = KeyPair.generate()
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        relayHas(0, kind3(target, now - 300, a, b))
        relayHas(1, kind3(target, now - 100))
        relayHas(2, kind3(target, now - 200, b))
        assertEquals(emptySet<String>(), service().fetchContactList(target.publicKeyHex))
        // One REQ per reachable relay, sequentially, with the Dart filter.
        val reqs = scripted.frames.filter { it.second.startsWith("[\"REQ\"") }
        assertEquals(relays.take(3), reqs.map { it.first })
        val filter = (DartJson.decode(reqs[0].second).jsonArray)
        assertTrue(filter[1].jsonPrimitive.content.matches(Regex("contacts-[0-9a-f]{16}")))
        assertEquals("""{"kinds":[3],"authors":["${target.publicKeyHex}"],"limit":1}""", DartJson.encode(filter[2]))
    }

    @Test
    fun `future events are ignored and the first relay wins ties`() = runTest {
        val target = KeyPair.generate()
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val c = "c".repeat(64)
        relayHas(0, kind3(target, now - 50, a))
        relayHas(1, kind3(target, now - 50, b), kind3(target, now + 3600, c))
        assertEquals(setOf(a), service().fetchContactList(target.publicKeyHex))
    }

    @Test
    fun `follows - only p tags with 64 characters, case kept`() = runTest {
        val target = KeyPair.generate()
        val upper = "A".repeat(64)
        val ev = EventIds.sign(
            target, now, 3,
            listOf(listOf("p", upper), listOf("p", "b".repeat(63)), listOf("e", "c".repeat(64)), listOf("p")),
            "",
        )
        relayHas(0, ev)
        assertEquals(setOf(upper), service().fetchContactList(target.publicKeyHex))
    }

    @Test
    fun `getMyFollows - cached for 2 h, empty results cached, no npub not cached`() = runTest {
        val f = "f".repeat(64)
        relayHas(0, kind3(me, now - 10, f))
        val store = InMemoryKeyValueStore()
        val svc = service(store)
        assertEquals(setOf(f), svc.getMyFollows())
        assertEquals("""["$f"]""", store.getString(SocialGraphService.KEY_MY_FOLLOWS))
        assertEquals(clock.nowMillis(), store.getInt(SocialGraphService.KEY_CACHE_TS))
        val reqs = scripted.frames.size

        clock.advance(SocialGraphService.CACHE_DURATION_MS)
        assertEquals(setOf(f), svc.getMyFollows())
        assertEquals(reqs, scripted.frames.size)

        clock.advance(1)
        stored.getValue(relays[0]).add(kind3(me, clock.nowSeconds(), "e".repeat(64)))
        assertEquals(setOf("e".repeat(64)), svc.getMyFollows())
        assertEquals(setOf("e".repeat(64)), svc.getMyFollows(forceRefresh = false))

        stored.getValue(relays[0]).add(kind3(me, clock.nowSeconds() + 1))
        assertEquals(emptySet<String>(), svc.getMyFollows(forceRefresh = true))
        assertEquals("[]", store.getString(SocialGraphService.KEY_MY_FOLLOWS))

        val noKey = InMemoryKeyValueStore()
        assertEquals(emptySet<String>(), service(noKey, npub = null).getMyFollows())
        assertEquals(emptySet<String>(), service(noKey, npub = "nsec1xyz").getMyFollows())
        assertEquals(null, noKey.getString(SocialGraphService.KEY_MY_FOLLOWS))
    }

    @Test
    fun `corrupt follow cache is refetched`() = runTest {
        val f = "f".repeat(64)
        relayHas(0, kind3(me, now - 10, f))
        val store = InMemoryKeyValueStore()
        store.setInt(SocialGraphService.KEY_CACHE_TS, clock.nowMillis())
        store.setString(SocialGraphService.KEY_MY_FOLLOWS, """["x", 1]""")
        assertEquals(setOf(f), service(store).getMyFollows())
    }

    @Test
    fun `analyze - mutual, common contacts, organizer followers, hops`() = runTest {
        val target = KeyPair.generate()
        val common = "c".repeat(64)
        val org1 = KeyPair.generate()
        val org2 = KeyPair.generate()
        relayHas(
            0,
            kind3(me, now - 10, target.publicKeyHex, common),
            kind3(target, now - 10, me.publicKeyHex, common, "d".repeat(64)),
            kind3(org1, now - 10, target.publicKeyHex),
            kind3(org2, now - 10, "e".repeat(64)),
        )
        val admins = listOf(AdminEntry(org1.npub, "M"), AdminEntry(org2.npub, "M"), AdminEntry("npub1kaputt", "M"))
        val res = service(admins = admins).analyze(target.publicKeyHex)
        assertEquals(
            SocialAnalysis(target.publicKeyHex, 2, 3, iFollow = true, followsMe = true, isMutual = true, commonContactCount = 1, orgFollowerCount = 1, hops = 1),
            res,
        )
        assertEquals(1.0 + 1.0 / 6.0 + 0.5, res.socialScore, 1e-12)

        assertEquals(SocialAnalysis.empty(), service().analyze(me.publicKeyHex))
        assertEquals(SocialAnalysis.empty(), service(npub = null).analyze(target.publicKeyHex))
    }

    @Test
    fun `analyze - hops 2 via common contacts, -1 without`() = runTest {
        val target = KeyPair.generate()
        val stranger = KeyPair.generate()
        val common = "c".repeat(64)
        relayHas(0, kind3(me, now - 10, common), kind3(target, now - 10, common), kind3(stranger, now - 10, "9".repeat(64)))
        val svc = service()
        assertEquals(2, svc.analyze(target.publicKeyHex).hops)
        val none = svc.analyze(stranger.publicKeyHex)
        assertEquals(-1, none.hops)
        assertEquals("Keine Verbindung", none.connectionLabel)
    }

    @Test
    fun `getMyStats - sample of follows, estimate and organizer count`() = runTest {
        val friends = List(4) { KeyPair.generate() }
        val org = KeyPair.generate()
        relayHas(
            0,
            kind3(me, now - 10, *friends.map { it.publicKeyHex }.toTypedArray()),
            kind3(friends[0], now - 10, me.publicKeyHex),
            kind3(friends[1], now - 10, me.publicKeyHex),
            kind3(org, now - 10, me.publicKeyHex),
        )
        val stats = service(admins = listOf(AdminEntry(org.npub, "M"))).getMyStats()
        assertEquals(4, stats.followCount)
        assertEquals(2, stats.estimatedMutualCount)
        assertEquals(1, stats.orgFollowerCount)
        assertEquals(0, service(npub = null).getMyStats().followCount)
    }
}
