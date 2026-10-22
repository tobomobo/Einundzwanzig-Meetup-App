package space.einundzwanzig.meetup.data.socialgraph

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.MeetupKey
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkBuildEvent
import space.einundzwanzig.meetup.data.socialgraph.internal.CoAttendanceFetcher
import space.einundzwanzig.meetup.data.socialgraph.internal.DefaultCoAttendanceRepository
import space.einundzwanzig.meetup.data.socialgraph.internal.GraphCache
import java.util.concurrent.atomic.AtomicInteger

/**
 * Repository flows: presence check vectors on the staged crawl, network build with cache
 * (stale, incremental, owner binding), job coalescing, publish status and the organizer marker.
 */
class CoAttendanceRepositoryTest {

    private val me = npubOf(1)
    private val anna = npubOf(2)
    private val ben = npubOf(3)
    private val carla = npubOf(4)
    private val dora = npubOf(5)
    private val emil = npubOf(6)
    private val fred = npubOf(7)
    private val gus = npubOf(8)

    private val vectorRecords = listOf(
        rec(me, "a-2026-01-01@S1"), rec(me, "b-2026-02-01@S1"),
        rec(anna, "a-2026-01-01@S1"), rec(anna, "b-2026-02-01"), rec(anna, "c-2026-03-01@S2"),
        rec(ben, "a-2026-01-01@S2"), rec(ben, "d-2026-04-01@S3"),
        rec(carla, "c-2026-03-01@S2"),
        rec(dora, "c-2026-03-01"), rec(dora, "e-2026-05-01@S4"),
        rec(emil, "e-2026-05-01@S4"),
        rec(fred, "e-2026-05-01@S4"), rec(fred, "f-2026-06-01@S5"),
        rec(gus, "f-2026-06-01@S5"),
        // Ignored: degenerate legacy id.
        rec(gus, "test-2026-07-24"),
    )

    private val clock = FakeClock()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Env(
        val repo: DefaultCoAttendanceRepository,
        val store: InMemoryKeyValueStore,
        val badges: BadgeRepository,
        val signer: FakeSigner,
    )

    private fun env(
        fetcher: CoAttendanceFetcher = FakeCoAttRelays(vectorRecords),
        connector: RelayConnector = FakeRelayConnector.unreachable(),
        signer: FakeSigner = FakeSigner(clock = clock),
        store: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        blockHeight: Int = 900_000,
        scope: CoroutineScope = appScope,
        wrapBadges: (BadgeRepository) -> BadgeRepository = { it },
    ): Env {
        val badges = wrapBadges(BadgeRepository.create(store, InMemorySecureStore(), clock, Dispatchers.Default))
        val repo = DefaultCoAttendanceRepository(
            store = store,
            relayClient = RelayClient(connector),
            relayConfig = RelayConfig(store, connector),
            signer = signer,
            badges = badges,
            fetchBlockHeight = { blockHeight },
            timeSource = clock,
            scope = scope,
            log = RecordingLog(),
            fetcher = fetcher,
        )
        return Env(repo, store, badges, signer)
    }

    // ------------------------------------------------------------------ verifyPerson

    @Test
    fun `verifyPerson vectors`() = runTest {
        val repo = env().repo
        val toEmil = repo.verifyPerson(me, emil)
        assertEquals(3, toEmil.degree)
        assertEquals(listOf(me, anna, dora, emil), toEmil.path)

        val toGus = repo.verifyPerson(me, gus)
        assertEquals(4, toGus.degree)
        assertEquals(listOf(me, anna, dora, fred, gus), toGus.path)
        assertEquals(1, toGus.targetTotalMeetups)

        val toBen = repo.verifyPerson(me, ben)
        assertEquals(-1, toBen.degree)
        assertTrue(toBen.targetInNetwork)
        assertFalse(toBen.found)

        val stranger = repo.verifyPerson(me, npubOf(77))
        assertFalse(stranger.targetInNetwork)
        assertEquals(-1, stranger.degree)

        val self = repo.verifyPerson(me, me)
        assertEquals(0, self.degree)
        assertTrue(self.targetInNetwork)
    }

    @Test
    fun `verifyPerson does not touch the cache`() = runTest {
        val e = env()
        e.repo.verifyPerson(me, emil)
        assertNull(e.store.getString(GraphCache.KEY))
    }

    // ------------------------------------------------------------------ build

    @Test
    fun `buildMyNetwork - stages, done, cache written, cachedNetwork owner-bound`() = runTest {
        val e = env()
        val events = e.repo.buildMyNetwork(me).toList()
        val stages = events.filterIsInstance<NetworkBuildEvent.Stage>()
        assertEquals(listOf(1, 2, 3), stages.map { it.depth })
        val done = events.last() as NetworkBuildEvent.Done
        val net = done.network
        assertEquals(listOf(anna), net.byDegree[1]!!.map { it.npub })
        assertEquals(listOf(carla, dora), net.byDegree[2]!!.map { it.npub })
        assertEquals(setOf(emil, fred), net.byDegree[3]!!.map { it.npub }.toSet())
        assertEquals(clock.nowMillis(), net.updatedAt!!.toEpochMilli())
        assertFalse(net.stale)
        // Stage 1 of a full build without cache shows degree 1 only.
        assertEquals(listOf(anna), stages.first().network.byDegree[1]!!.map { it.npub })

        val cache = GraphCache.read(e.store, RecordingLog())!!
        assertEquals(me, cache.owner)
        assertEquals(clock.nowMillis(), cache.fullSyncAt)
        assertEquals(clock.nowMillis(), cache.syncAt)
        assertTrue(cache.knownAuthors.contains(me))

        val cached = e.repo.cachedNetwork(me)!!
        assertEquals(net.byDegree.mapValues { l -> l.value.map { it.npub } }, cached.byDegree.mapValues { l -> l.value.map { it.npub } })
        assertNull(e.repo.cachedNetwork(anna))
    }

    @Test
    fun `no relay answer with a cache - cached network marked stale, cache not rewritten`() = runTest {
        val relays = FakeCoAttRelays(vectorRecords)
        val e = env(fetcher = relays)
        e.repo.awaitMyNetwork(me)
        val before = e.store.getString(GraphCache.KEY)

        relays.down += RelayConfig.DEFAULT_RELAYS
        clock.advance(60_000)
        val stale = e.repo.awaitMyNetwork(me, forceFull = true)
        assertTrue(stale.stale)
        assertEquals(5, stale.totalReach)
        assertEquals(before, e.store.getString(GraphCache.KEY))
    }

    @Test
    fun `no relay answer and no cache - empty stale network`() = runTest {
        val relays = FakeCoAttRelays(vectorRecords).apply { down += RelayConfig.DEFAULT_RELAYS }
        val e = env(fetcher = relays)
        val net = e.repo.awaitMyNetwork(me)
        assertTrue(net.stale)
        assertTrue(net.isEmpty)
        assertEquals(listOf(1, 2, 3), net.byDegree.keys.toList())
        assertNull(e.store.getString(GraphCache.KEY))
    }

    @Test
    fun `incremental build asks known authors with since and keeps fullSyncAt`() = runTest {
        val relays = FakeCoAttRelays(vectorRecords)
        val e = env(fetcher = relays)
        e.repo.awaitMyNetwork(me)
        val firstSync = clock.nowMillis()
        relays.calls.clear()
        clock.advance(3_600_000)

        val net = e.repo.awaitMyNetwork(me)
        assertEquals(5, net.totalReach)
        val since = firstSync / 1000 - 3600
        assertTrue(relays.calls.any { it.endsWith("since=$since") })
        val cache = GraphCache.read(e.store, RecordingLog())!!
        assertEquals(firstSync, cache.fullSyncAt)
        assertEquals(clock.nowMillis(), cache.syncAt)

        // After 24 h the next build is full again (no since).
        relays.calls.clear()
        clock.advance(24L * 3_600_000)
        e.repo.awaitMyNetwork(me)
        assertTrue(relays.calls.all { it.endsWith("since=null") })
        assertEquals(clock.nowMillis(), GraphCache.read(e.store, RecordingLog())!!.fullSyncAt)
    }

    @Test
    fun `a disappeared attendance stays when not every relay answered completely`() = runTest {
        val first = FakeCoAttRelays(vectorRecords)
        val store = InMemoryKeyValueStore()
        env(fetcher = first, store = store).repo.awaitMyNetwork(me)

        // Anna's records vanish on the answering relay; the other relays do not answer.
        val second = FakeCoAttRelays(vectorRecords.filter { it.npub != anna }).apply {
            down += RelayConfig.DEFAULT_RELAYS.drop(1)
        }
        val net = env(fetcher = second, store = store).repo.awaitMyNetwork(me, forceFull = true)
        assertEquals(listOf(anna), net.byDegree[1]!!.map { it.npub })

        // All relays complete: now Anna is gone.
        val third = FakeCoAttRelays(vectorRecords.filter { it.npub != anna })
        val gone = env(fetcher = third, store = store).repo.awaitMyNetwork(me, forceFull = true)
        assertTrue(gone.contactsByNpub[anna] == null)
    }

    @Test
    fun `job coalescing - a second caller joins the running build`() = runTest {
        // Reference: the number of relay calls of one build.
        val reference = FakeCoAttRelays(vectorRecords)
        env(fetcher = reference).repo.awaitMyNetwork(me)
        val oneBuild = reference.calls.size

        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val inner = FakeCoAttRelays(vectorRecords)
        val gated = CoAttendanceFetcher { relay, keys, authors, since ->
            calls.incrementAndGet()
            gate.await()
            inner.fetch(relay, keys, authors, since)
        }
        val e = env(fetcher = gated)
        val stagesSecond = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { e.repo.awaitMyNetwork(me) }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            e.repo.awaitMyNetwork(me, forceFull = true) { _, d -> stagesSecond += d }
        }
        while (calls.get() == 0) yield()
        gate.complete(Unit)
        val a: MyNetwork = first.await()
        val b: MyNetwork = second.await()
        assertTrue(a === b)
        assertEquals(listOf(1, 2, 3), stagesSecond.toList())
        assertEquals(oneBuild, inner.calls.size)

        // Finished: the next call starts a new build.
        e.repo.awaitMyNetwork(me)
        assertTrue(inner.calls.size > oneBuild)
    }

    @Test
    fun `a cancelled build fails its collectors instead of cancelling them silently`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val fetcher = CoAttendanceFetcher { _, _, _, _ ->
            started.complete(Unit)
            never.await()
            null
        }
        val e = env(fetcher = fetcher, scope = scope)
        val flowResult = async { runCatching { e.repo.buildMyNetwork(me).toList() } }
        val awaitResult = async { runCatching { e.repo.awaitMyNetwork(me) } }
        started.await()
        scope.cancel()
        assertTrue(flowResult.await().exceptionOrNull() is IllegalStateException)
        assertTrue(awaitResult.await().exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `a cancelled collector detaches while the shared build keeps running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val inner = FakeCoAttRelays(vectorRecords)
        val gated = CoAttendanceFetcher { relay, keys, authors, since ->
            started.complete(Unit)
            gate.await()
            inner.fetch(relay, keys, authors, since)
        }
        val e = env(fetcher = gated)
        val stages = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val collector = launch { e.repo.buildMyNetwork(me).collect { if (it is NetworkBuildEvent.Stage) stages += it.depth } }
        val survivor = async { e.repo.awaitMyNetwork(me) }
        started.await()
        collector.cancelAndJoin()
        gate.complete(Unit)
        assertEquals(5, survivor.await().totalReach)
        assertTrue(stages.isEmpty())
    }

    // ------------------------------------------------------------------ crawl caps / cache merge

    @Test
    fun `stage cap - only the first 60 meetups in insertion order are queried`() = runTest {
        val keys = (0..60).map { "ort%02d-2026-01-01@S1".format(it) }
        val inner = FakeCoAttRelays(keys.map { rec(me, it) })
        val keySets = java.util.concurrent.CopyOnWriteArrayList<Set<String>>()
        val fetcher = CoAttendanceFetcher { relay, k, authors, since ->
            if (k != null) keySets += k
            inner.fetch(relay, k, authors, since)
        }
        env(fetcher = fetcher).repo.awaitMyNetwork(me)
        assertTrue(keySets.isNotEmpty())
        // One batch of 60 (KEY_BATCH) per relay, keys in insertion order.
        for (set in keySets) assertEquals(keys.take(60), set.toList())
        assertFalse(keySets.any { keys[60] in it })
    }

    @Test
    fun `stage cap - only the first 300 new people are fetched as authors`() = runTest {
        val key = "gross-2026-01-01@S1"
        val people = (0 until 301).map { Nip19.encodeNpub(String.format("%064x", 1000 + it)) }
        val inner = FakeCoAttRelays(listOf(rec(me, key)) + people.map { rec(it, key) })
        val authorBatches = java.util.concurrent.CopyOnWriteArrayList<Pair<String, List<String>>>()
        val fetcher = CoAttendanceFetcher { relay, k, authors, since ->
            if (authors != null) authorBatches += relay to authors
            inner.fetch(relay, k, authors, since)
        }
        val net = env(fetcher = fetcher).repo.awaitMyNetwork(me)
        val relay0 = RelayConfig.DEFAULT_RELAYS[0]
        val fetched = authorBatches.filter { it.first == relay0 }.flatMap { it.second }
        val myHex = Nip19.decodeNpub(me)
        val expected = listOf(myHex) + people.take(300).map { Nip19.decodeNpub(it) }
        assertEquals(expected.toSet(), fetched.toSet())
        assertEquals(expected.size, fetched.size)
        assertFalse(Nip19.decodeNpub(people[300]) in fetched)
        // The 301st is still in the graph from the key answer, so it stays visible on degree 1.
        assertEquals(301, net.degree1Count)
    }

    @Test
    fun `full build with cache - stage 1 is fresh degree 1 plus cached deeper degrees`() = runTest {
        val store = InMemoryKeyValueStore()
        env(store = store).repo.awaitMyNetwork(me)
        val firstSync = clock.nowMillis()
        clock.advance(60_000)

        // Carla (cached degree 2) now attended my meetup: fresh degree 1.
        val changed = vectorRecords + rec(carla, "a-2026-01-01@S1")
        val events = env(fetcher = FakeCoAttRelays(changed), store = store).repo
            .buildMyNetwork(me, forceFull = true).toList()
        val stage1 = events.filterIsInstance<NetworkBuildEvent.Stage>().first { it.depth == 1 }.network
        assertEquals(setOf(anna, carla), stage1.byDegree[1]!!.map { it.npub }.toSet())
        // Cached degree 2 minus Carla, already placed on degree 1.
        assertEquals(listOf(dora), stage1.byDegree[2]!!.map { it.npub })
        assertEquals(setOf(emil, fred), stage1.byDegree[3]!!.map { it.npub }.toSet())
        assertEquals(firstSync, stage1.updatedAt!!.toEpochMilli())

        val done = (events.last() as NetworkBuildEvent.Done).network
        assertEquals(clock.nowMillis(), done.updatedAt!!.toEpochMilli())
        assertEquals(setOf(anna, carla), done.byDegree[1]!!.map { it.npub }.toSet())
    }

    @Test
    fun `key batch answered incompletely keeps exact cached key hits`() = runTest {
        val store = InMemoryKeyValueStore()
        env(store = store).repo.awaitMyNetwork(me)
        clock.advance(60_000)

        val full = FakeCoAttRelays(vectorRecords)
        val noAnna = FakeCoAttRelays(vectorRecords.filter { it.npub != anna })
        val relays = RelayConfig.DEFAULT_RELAYS
        fun fetcher(onlyFirstAnswersKeys: Boolean) = CoAttendanceFetcher { relay, keys, authors, since ->
            when {
                keys == null -> full.fetch(relay, keys, authors, since)
                onlyFirstAnswersKeys && relay != relays[0] -> null
                else -> noAnna.fetch(relay, keys, authors, since)
            }
        }
        // Authors complete everywhere; the key batch is complete on one relay only and lacks Anna.
        val kept = env(fetcher = fetcher(onlyFirstAnswersKeys = true), store = store).repo
            .awaitMyNetwork(me, forceFull = true)
        assertEquals(listOf(anna), kept.byDegree[1]!!.map { it.npub })
        assertTrue("a-2026-01-01@S1" in kept.meetupsOf[anna]!!)

        // Every relay answers the key batch completely: Anna is gone.
        val gone = env(fetcher = fetcher(onlyFirstAnswersKeys = false), store = store).repo
            .awaitMyNetwork(me, forceFull = true)
        assertNull(gone.contactsByNpub[anna])
    }

    // ------------------------------------------------------------------ publish

    private fun signedBadge(sigId: String = "sig-1", eventId: String = "aschaffenburg-2026-06-03") = MeetupBadge(
        id = "b-$sigId",
        meetupName = "Aschaffenburg",
        date = DartDateTime.fromMillisecondsSinceEpoch(1_780_000_000_123),
        iconPath = "",
        signerNpub = "npub1lf0rga7j66uj6enae2mxezamz5nsz3vechhvmh25tcarn4u8qf5q534jzc",
        meetupEventId = eventId,
        sig = "ab".repeat(64),
        sigId = sigId,
        adminPubkey = "cd".repeat(32),
        sigVersion = 2,
    )

    /** Relay 0 and 1 accept, relay 2 rejects, relay 3 is unreachable. */
    private fun publishRelays(): ScriptedRelays = ScriptedRelays().apply {
        val r = RelayConfig.DEFAULT_RELAYS
        for (i in 0..1) onEvent[r[i]] = { ev, s -> s.push(listOf("OK", ev["id"]!!.jsonPrimitive.content, true, "")) }
        onEvent[r[2]] = { ev, s -> s.push(listOf("OK", ev["id"]!!.jsonPrimitive.content, false, "blocked: rate")) }
    }

    @Test
    fun `publishAttendance - counts matching OK true, exact event, status saved`() = runTest {
        val relays = publishRelays()
        val e = env(connector = relays.connector)
        val n = e.repo.publishAttendance(signedBadge())
        assertEquals(2, n)
        assertEquals(mapOf("sig-1" to 2L), e.repo.publishStatus())
        assertEquals("""{"sig-1":2}""", e.store.getString("coatt_publish_status"))

        val ev = e.signer.signed.single()
        assertEquals(30079, ev.kind)
        val key = "aschaffenburg-2026-06-03@u8qf5q534jzc"
        assertEquals(listOf(listOf("d", key), listOf("e_ref", "sig-1"), listOf("client", "einundzwanzig-meetup-app")), ev.tags)
        assertEquals("""{"event":"$key","meetup":"Aschaffenburg","t":1780000000}""", ev.content)
        val sent = relays.frames.first { it.second.startsWith("[\"EVENT\"") }.second
        assertEquals(ev.id, ((DartJson.decode(sent) as JsonArray)[1] as JsonObject)["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `publishAttendance - unqualified badge returns 0 without status`() = runTest {
        val e = env()
        assertEquals(0, e.repo.publishAttendance(signedBadge().copyWithSig("")))
        assertEquals(0, e.repo.publishAttendance(signedBadge(eventId = "test-2026-07-24")))
        assertNull(e.store.getString("coatt_publish_status"))
        assertTrue(e.signer.signed.isEmpty())
    }

    @Test
    fun `publishAttendance - throwing signer records status 0, retry fixes it`() = runTest {
        val store = InMemoryKeyValueStore()
        val failing = env(signer = FakeSigner(clock = clock, fail = true), store = store)
        assertEquals(0, failing.repo.publishAttendance(signedBadge()))
        assertEquals(mapOf("sig-1" to 0L), failing.repo.publishStatus())

        val other = signedBadge(sigId = "sig-2")
        val all = listOf(signedBadge(), other)
        assertEquals(listOf("sig-1"), failing.repo.failedBadges(all).map { it.sigId })

        val working = env(connector = publishRelays().connector, store = store)
        assertEquals(1, working.repo.retryFailed(all))
        assertEquals(mapOf("sig-1" to 2L), working.repo.publishStatus())
        assertTrue(working.repo.failedBadges(all).isEmpty())
    }

    @Test
    fun `publishStatus - non-int values read as 0, garbage as empty`() = runTest {
        val e = env()
        e.store.setString("coatt_publish_status", """{"a":3,"b":"x","c":1.0}""")
        assertEquals(mapOf("a" to 3L, "b" to 0L, "c" to 0L), e.repo.publishStatus())
        e.store.setString("coatt_publish_status", "[1]")
        assertEquals(emptyMap<String, Long>(), e.repo.publishStatus())
    }

    @Test
    fun `recordOrganizerAttendance - marker badge stored once, organizer event published`() = runTest {
        val relays = publishRelays()
        val e = env(connector = relays.connector, blockHeight = 912_345)
        val date = DartDateTime.fromMillisecondsSinceEpoch(1_780_000_000_000)
        val badge = e.repo.recordOrganizerAttendance("Bad Neuenahr", date)
        assertNotNull(badge)
        val eventId = MeetupKey.eventId(MeetupKey.slug("Bad Neuenahr"), date)
        assertEquals("org-$eventId", badge!!.id)
        assertTrue(badge.isOrganizer)
        assertEquals("organizer", badge.delivery)
        assertEquals(912_345L, badge.blockHeight)
        assertFalse(badge.isNostrSigned)

        val stored = e.badges.loadBadges()
        assertEquals(listOf("org-$eventId"), stored.map { it.id })

        val ev = e.signer.signed.single()
        val key = "$eventId@${e.signer.keyPair.npub.takeLast(12)}"
        assertEquals(listOf(listOf("d", key), listOf("role", "organizer"), listOf("client", "einundzwanzig-meetup-app")), ev.tags)
        assertEquals("""{"event":"$key","meetup":"Bad Neuenahr","t":1780000000,"role":"organizer"}""", ev.content)
        assertNull(e.store.getString("coatt_publish_status"))

        // Second call: no duplicate marker, published again.
        e.repo.recordOrganizerAttendance("Bad Neuenahr", date, blockHeight = 5)
        assertEquals(1, e.badges.loadBadges().size)
        assertEquals(2, e.signer.signed.size)

        val evt = e.repo.recordOrganizerAttendance("Blocktrainer Event", date, blockHeight = 7, isEvent = true)!!
        assertTrue(evt.isEvent)
        assertEquals("ev|${MeetupKey.eventId("blocktrainer-event", date)}", e.signer.signed.last().tags[0][1])
    }

    /** A repository whose wallet writes fail; [keepAsideOk] decides whether keep-aside works. */
    private class FailingBadges(real: BadgeRepository, private val keepAsideOk: Boolean) : BadgeRepository by real {
        val keptAside = mutableListOf<MeetupBadge>()
        override suspend fun update(transform: (current: List<MeetupBadge>) -> List<MeetupBadge>?): Boolean = false
        override suspend fun keepAside(badge: MeetupBadge): Boolean {
            keptAside += badge
            return keepAsideOk
        }
    }

    @Test
    fun `recordOrganizerAttendance - failed save falls back to keepAside and still publishes`() = runTest {
        val relays = publishRelays()
        var failing: FailingBadges? = null
        val e = env(connector = relays.connector, wrapBadges = { FailingBadges(it, keepAsideOk = true).also { f -> failing = f } })
        val date = DartDateTime.fromMillisecondsSinceEpoch(1_780_000_000_000)
        val badge = e.repo.recordOrganizerAttendance("Bad Neuenahr", date, blockHeight = 5)
        assertNotNull(badge)
        val eventId = MeetupKey.eventId(MeetupKey.slug("Bad Neuenahr"), date)
        assertEquals(listOf("org-$eventId"), failing!!.keptAside.map { it.id })
        assertEquals(1, e.signer.signed.size)
    }

    @Test
    fun `recordOrganizerAttendance - badge stored nowhere returns null and publishes nothing`() = runTest {
        val relays = publishRelays()
        var failing: FailingBadges? = null
        val e = env(connector = relays.connector, wrapBadges = { FailingBadges(it, keepAsideOk = false).also { f -> failing = f } })
        val date = DartDateTime.fromMillisecondsSinceEpoch(1_780_000_000_000)
        assertNull(e.repo.recordOrganizerAttendance("Bad Neuenahr", date, blockHeight = 5))
        assertEquals(1, failing!!.keptAside.size)
        assertTrue(e.signer.signed.isEmpty())
    }

    private fun MeetupBadge.copyWithSig(sig: String) = MeetupBadge(
        id = id, meetupName = meetupName, date = date, iconPath = iconPath, signerNpub = signerNpub,
        meetupEventId = meetupEventId, sig = sig, sigId = sigId, adminPubkey = adminPubkey, sigVersion = sigVersion,
    )
}
