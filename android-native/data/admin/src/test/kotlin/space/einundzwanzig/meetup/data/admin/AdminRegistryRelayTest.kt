package space.einundzwanzig.meetup.data.admin

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.admin.model.AdminCheckResult
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistry
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistryException
import space.einundzwanzig.meetup.data.admin.registry.AdminRelayFetch
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real relay path of the registry (`_fetchFromSingleRelay` on [RelayClient] + fake sockets,
 * virtual time): EOSE → `{}` (next relay), timeout → collected or `null`, disconnect → `null`,
 * connect failure → next relay; and the OK handling of `createAndPublishAdminListEvent` (Audit
 * M8). Decides `unavailable` vs `not_found` for the scanner (spec 05 §9 item 10).
 *
 * The super admin's private key is not available, so lists are signed by a known admin of the
 * sunset phase (authors = cached admins); the bootstrap cases only need empty answers.
 */
class AdminRegistryRelayTest {

    private val clock = FakeClock()
    private val known = KeyPair.generate()
    private val target = KeyPair.generate()

    private fun TestScope.registryWith(
        relays: ScriptedRelays,
        store: InMemoryKeyValueStore,
        signer: FakeAdminSigner = FakeAdminSigner(clock = clock),
    ): AdminRegistry = registry(
        store = store,
        fetch = null,
        clock = clock,
        signer = signer,
        connector = relays.connector,
        relayDispatcher = StandardTestDispatcher(testScheduler),
    )

    /** Sunset phase whose only cached admin is [known] (the queried author). */
    private fun sunsetStore(): InMemoryKeyValueStore = prefs(
        AdminConstants.KEY_SUNSET_RESET_DONE to true,
        AdminConstants.KEY_SUNSET_FLAG to true,
        AdminConstants.KEY_UNIQUE_AUTHORS to AdminConstants.SUNSET_THRESHOLD,
        AdminConstants.KEY_CACHE to AdminEntry.listToJson(listOf(AdminEntry(known.npub, "Known", "K", 1))),
        AdminConstants.KEY_CACHE_TIMESTAMP to clock.nowMillis(),
    )

    /** Kind-30078 list of [known] that names [target]. */
    private fun knownList(): NostrEvent = EventIds.sign(
        known,
        clock.nowSeconds(),
        AdminConstants.ADMIN_LIST_KIND,
        listOf(listOf("d", AdminConstants.ADMIN_LIST_D_TAG)),
        DartJson.encode(linkedMapOf("admins" to listOf(AdminEntry(target.npub, "Zielmeetup", "Ziel", 5).toJsonMap()))),
    )

    @Test
    fun `EOSE-leeres Relay, danach Relay mit signierter Liste - nostr_relay`() = runTest {
        val relays = ScriptedRelays()
        relays.onReq[relay(0)] = { sub, s -> s.push(listOf("EOSE", sub)) }
        relays.onReq[relay(1)] = { sub, s ->
            s.pushEvent(sub, knownList())
            s.push(listOf("EOSE", sub))
        }
        val result = registryWith(relays, sunsetStore()).checkAdmin(target.npub)

        assertEquals(AdminCheckResult(true, "Zielmeetup", "Ziel", AdminCheckResult.SOURCE_NOSTR_RELAY), result)
        assertEquals(listOf(relay(0), relay(1)), relays.connector.connects)
    }

    @Test
    fun `alle Relays EOSE-leer - unavailable, nicht not_found`() = runTest {
        val relays = ScriptedRelays()
        AdminConstants.RELAYS.forEach { url -> relays.onReq[url] = { sub, s -> s.push(listOf("EOSE", sub)) } }

        val result = registryWith(relays, prefs()).checkAdmin(target.npub)

        assertEquals(AdminCheckResult.SOURCE_UNAVAILABLE, result.source)
        assertEquals(AdminConstants.RELAYS, relays.connector.connects)
    }

    @Test
    fun `Timeout ohne Events, Verbindungsfehler und Abbruch - jeweils naechstes Relay`() = runTest {
        val relays = ScriptedRelays()
        relays.onReq[relay(0)] = { _, _ -> } // silent → 8 s timeout, nothing collected → null
        // relay(1) unscripted → connect failure
        relays.onReq[relay(2)] = { _, s -> s.finish() } // disconnect → null
        relays.onReq[relay(3)] = { sub, s ->
            s.pushEvent(sub, knownList())
            s.push(listOf("EOSE", sub))
        }
        val result = registryWith(relays, sunsetStore()).checkAdmin(target.npub)

        assertEquals(AdminCheckResult.SOURCE_NOSTR_RELAY, result.source)
        assertEquals(AdminConstants.RELAYS, relays.connector.connects)
    }

    @Test
    fun `Timeout nach einem Event - die gesammelte Liste zaehlt`() = runTest {
        val relays = ScriptedRelays()
        relays.onReq[relay(0)] = { sub, s -> s.pushEvent(sub, knownList()) } // then silence
        relays.onReq[relay(1)] = { sub, s -> s.push(listOf("EOSE", sub)) }

        val result = registryWith(relays, sunsetStore()).checkAdmin(target.npub)

        assertEquals(AdminCheckResult.SOURCE_NOSTR_RELAY, result.source)
        assertEquals(listOf(relay(0)), relays.connector.connects)
    }

    @Test
    fun `createAndPublishAdminListEvent - OK=false ueberall wirft`() = runTest {
        val relays = ScriptedRelays()
        AdminConstants.RELAYS.forEach { url ->
            relays.onEvent[url] = { ev, s -> s.push(listOf("OK", ev["id"]!!.jsonPrimitive.content, false, "blocked")) }
        }
        val registry = registryWith(relays, prefs())
        try {
            registry.createAndPublishAdminListEvent()
            fail("expected AdminRegistryException")
        } catch (e: AdminRegistryException) {
            val lines = e.message!!.lines()
            assertEquals("Event konnte an keinen Relay gesendet werden.", lines[0])
            assertEquals(AdminConstants.RELAYS.map { "$it: Keine Bestätigung erhalten" }, lines.drop(1))
        }
    }

    @Test
    fun `createAndPublishAdminListEvent - erstes OK beliebiger id zaehlt, 5 s Inaktivitaet nicht`() = runTest {
        val relays = ScriptedRelays()
        relays.onEvent[relay(0)] = { _, _ -> } // no OK → 5 s inactivity timeout
        relays.onEvent[relay(1)] = { _, s -> s.push(listOf("OK", "f".repeat(64), true, "")) } // foreign id still counts
        relays.onEvent[relay(2)] = { ev, s -> s.push(listOf("OK", ev["id"]!!.jsonPrimitive.content, true, "")) }
        // relay(3) unreachable
        val signer = FakeAdminSigner(clock = clock)
        val json = registryWith(relays, prefs(), signer).createAndPublishAdminListEvent()

        assertTrue(json, json.startsWith("{\"sent_to\": 2, \"event\": "))
        assertEquals("{\"sent_to\": 2, \"event\": ${signer.signed.single().toJson()}}", json)
        // Sequential, in relay order.
        assertEquals(AdminConstants.RELAYS, relays.connector.connects)
    }

    /** advanceUntilIdle ignores backgroundScope work; advancing virtual time runs it. */
    private fun TestScope.settleBackground() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    @Test
    fun `Hintergrund-Refresh ist single-flight - viele Cache-Treffer, ein Crawl`() = runTest {
        val calls = AtomicInteger()
        val fetch = AdminRelayFetch { _, _ ->
            calls.incrementAndGet()
            delay(1_000)
            null
        }
        val store = prefs(
            AdminConstants.KEY_CACHE to AdminEntry.listToJson(listOf(AdminEntry(target.npub, "M", "T", 1))),
            AdminConstants.KEY_CACHE_TIMESTAMP to clock.nowMillis() - 2 * 3_600_000L,
        )
        val registry = registry(store, fetch, clock)

        repeat(5) { assertEquals(AdminCheckResult.SOURCE_LOCAL_CACHE, registry.checkAdmin(target.npub).source) }
        settleBackground()
        // One refresh asks every relay once (all answer null); five refreshes would be 20 calls.
        assertEquals(AdminConstants.RELAYS.size, calls.get())

        // Once finished, the next stale hit starts a new one.
        registry.checkAdmin(target.npub)
        settleBackground()
        assertEquals(2 * AdminConstants.RELAYS.size, calls.get())
    }

    // ---- Admin-list wire format (kind 30078) ----

    private val fixedClock = FakeClock(1_772_906_400_000L) // 2026-03-07T18:00:00Z

    @Test
    fun `createAdminListEvent - Tags, Inhalt in Flutter-Reihenfolge, lokales updated_at ohne Z`() = runTest {
        val vouched = freshNpub()
        val store = prefs(
            AdminConstants.KEY_MY_VOUCHES to AdminEntry.listToJson(listOf(AdminEntry(vouched, "Wien", "V", 1_700_000_000))),
        )
        val signer = FakeAdminSigner(clock = fixedClock)
        val registry = registry(store, null, fixedClock, signer, zone = ZoneId.of("Europe/Berlin"))

        val json = registry.createAdminListEvent()
        val event = signer.signed.single()
        assertEquals(event.toJson(), json)
        assertEquals(AdminConstants.ADMIN_LIST_KIND, event.kind)
        assertEquals(listOf(listOf("d", "einundzwanzig-admins")), event.tags)
        assertEquals(
            "{\"admins\":[{\"npub\":\"$vouched\",\"meetup\":\"Wien\",\"name\":\"V\",\"added_at\":1700000000}]," +
                "\"updated_at\":\"2026-03-07T19:00:00.000\"}",
            event.content,
        )
        val decoded = DartJson.decode(json) as JsonObject
        assertEquals("[[\"d\",\"einundzwanzig-admins\"]]", DartJson.encode(decoded["tags"]!!))
    }

    // ---- Legacy vouch API ----

    @Test
    fun `addVouch - Fehler in Dart-Reihenfolge, removeVouch`() = runTest {
        val me = KeyPair.generate()
        val broken = "npub1kaputt"
        val store = prefs(AdminConstants.KEY_MY_VOUCHES to AdminEntry.listToJson(listOf(AdminEntry(broken, "M", "B", 1))))
        val registry = registry(store, null, fixedClock, FakeAdminSigner(me, fixedClock))

        suspend fun expect(message: String, block: suspend () -> Unit) {
            try {
                block()
                fail("expected AdminRegistryException")
            } catch (e: AdminRegistryException) {
                assertEquals(message, e.message)
            }
        }
        // Duplicate is checked before validity (the stored entry is not a valid npub).
        expect("Du bürgst bereits für diesen npub.") { registry.addVouch(AdminEntry(broken, "M", "B", 0)) }
        expect("Ungültiger npub.") { registry.addVouch(AdminEntry("npub1ungueltig", "M", "X", 0)) }
        expect("Du kannst nicht für dich selbst bürgen.") { registry.addVouch(AdminEntry(me.npub, "M", "Me", 0)) }

        val friend = freshNpub()
        registry.addVouch(AdminEntry(friend, "Wien", "F", 5))
        // added_at is always "now" (Dart), not the passed value.
        assertEquals(listOf(AdminEntry(broken, "M", "B", 1), AdminEntry(friend, "Wien", "F", fixedClock.nowSeconds())), registry.getMyVouches())
        expect("Du bürgst bereits für diesen npub.") { registry.addVouch(AdminEntry(friend, "Wien", "F", 0)) }

        registry.removeVouch(broken)
        assertEquals(listOf(friend), registry.getMyVouches().map { it.npub })
    }

    private fun ownList(me: KeyPair, vararg entries: AdminEntry): NostrEvent = EventIds.sign(
        me,
        clock.nowSeconds(),
        AdminConstants.ADMIN_LIST_KIND,
        listOf(listOf("d", AdminConstants.ADMIN_LIST_D_TAG)),
        DartJson.encode(linkedMapOf("admins" to entries.map { it.toJsonMap() })),
    )

    @Test
    fun `recoverMyVouchesFromRelays - eigenes Event, leere Antwort 0 ohne Ueberschreiben, kein Relay -1`() = runTest {
        val me = KeyPair.generate()
        val signer = FakeAdminSigner(me, clock)
        val store = prefs()
        val recovered = AdminEntry(freshNpub(), "Wien", "R", 7)

        val withEvent = ScriptedRelays()
        withEvent.onReq[relay(0)] = { sub, s -> s.push(listOf("EOSE", sub)) } // empty answer → 0
        val emptyRegistry = registryWith(withEvent, store, signer)
        val local = listOf(AdminEntry(freshNpub(), "Graz", "L", 3))
        store.setString(AdminConstants.KEY_MY_VOUCHES, AdminEntry.listToJson(local))
        assertEquals(0, emptyRegistry.recoverMyVouchesFromRelays())
        assertEquals(local, emptyRegistry.getMyVouches())

        val full = ScriptedRelays()
        // relay(0) unreachable → next relay
        full.onReq[relay(1)] = { sub, s ->
            s.pushEvent(sub, ownList(me, recovered))
            s.push(listOf("EOSE", sub))
        }
        val fullRegistry = registryWith(full, store, signer)
        assertEquals(1, fullRegistry.recoverMyVouchesFromRelays())
        assertEquals(listOf(recovered), fullRegistry.getMyVouches())
        assertEquals(listOf(relay(0), relay(1)), full.connector.connects)

        val none = ScriptedRelays() // every relay unreachable
        val noneRegistry = registryWith(none, store, signer)
        assertEquals(-1, noneRegistry.recoverMyVouchesFromRelays())
        assertEquals(listOf(recovered), noneRegistry.getMyVouches())
        assertEquals(AdminConstants.RELAYS, none.connector.connects)

        // Without a key: -1.
        assertEquals(-1, registryWith(full, store, FakeAdminSigner(null, clock)).recoverMyVouchesFromRelays())
    }

    @Test
    fun `revokeAllVouches - leert die Liste und publiziert eine leere Admin-Liste`() = runTest {
        val relays = ScriptedRelays()
        relays.onEvent[relay(0)] = { ev, s -> s.push(listOf("OK", ev["id"]!!.jsonPrimitive.content, true, "")) }
        val store = prefs(AdminConstants.KEY_MY_VOUCHES to AdminEntry.listToJson(listOf(AdminEntry(freshNpub(), "M", "V", 1))))
        val signer = FakeAdminSigner(clock = clock)
        val registry = registryWith(relays, store, signer)

        val json = registry.revokeAllVouches()

        assertEquals(emptyList<AdminEntry>(), registry.getMyVouches())
        val event = signer.signed.single()
        assertEquals("{\"sent_to\": 1, \"event\": ${event.toJson()}}", json)
        assertTrue(event.content, event.content.startsWith("{\"admins\":[],\"updated_at\":\""))

        val keyless = registryWith(relays, prefs(), FakeAdminSigner(null, clock))
        try {
            keyless.revokeAllVouches()
            fail("expected AdminRegistryException")
        } catch (e: AdminRegistryException) {
            assertEquals("Kein Nostr-Key vorhanden.", e.message)
        }
    }
}
