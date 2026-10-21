package space.einundzwanzig.meetup.data.admin

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.data.admin.ClaimFixtures.claimEvent
import space.einundzwanzig.meetup.data.admin.ClaimFixtures.nowSeconds
import space.einundzwanzig.meetup.data.admin.internal.DefaultPromotionClaimService
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.admin.model.VerifiedClaim
import space.einundzwanzig.meetup.data.admin.registry.AdminRelayFetch
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import java.time.ZoneOffset

/**
 * The relay paths of `PromotionClaimService` on [RelayClient] + fake sockets (virtual time):
 * claim fetch (partial results discarded on timeout / transport error, kept on a clean close,
 * first non-empty relay wins), the storage rules of `syncOrganicAdmins` and the sequential
 * fire-and-linger publish of `publishAdminClaim`.
 */
class PromotionClaimRelayTest {

    private val clock = FakeClock()
    private val admin = KeyPair.generate()
    private val claimer1 = KeyPair.generate()
    private val claimer2 = KeyPair.generate()
    private val own = KeyPair.generate()
    private val noRelay = AdminRelayFetch { _, _ -> null }

    /** [admin] is a known (registry) admin. */
    private fun store(vararg extra: Pair<String, Any>): InMemoryKeyValueStore = prefs(
        AdminConstants.KEY_CACHE to AdminEntry.listToJson(listOf(AdminEntry(admin.npub, "Aschaffenburg", "Admin", 1))),
        AdminConstants.KEY_CACHE_TIMESTAMP to clock.nowMillis(),
        *extra,
    )

    private fun TestScope.service(relays: ScriptedRelays, store: InMemoryKeyValueStore): DefaultPromotionClaimService {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DefaultPromotionClaimService(
            store = store,
            relayClient = RelayClient(relays.connector, dispatcher),
            signer = FakeAdminSigner(own, clock),
            registry = registry(store, noRelay, clock),
            timeSource = clock,
            zone = { ZoneOffset.UTC },
            log = AppLogger,
            cpuDispatcher = dispatcher,
        )
    }

    @Test
    fun `Timeout nach einem Claim verwirft ihn, naechstes Relay liefert`() = runTest {
        val relays = ScriptedRelays()
        relays.onReq[relay(0)] = { sub, s -> s.pushEvent(sub, claimEvent(admin, claimer1)) } // then silence
        relays.onReq[relay(1)] = { sub, s ->
            s.pushEvent(sub, claimEvent(admin, claimer2))
            s.push(listOf("EOSE", sub))
        }
        val store = store()
        val result = service(relays, store).syncOrganicAdmins()

        assertEquals(listOf(claimer2.publicKeyHex), result.map { it.claimerPubkey })
        assertEquals(listOf(relay(0), relay(1)), relays.connector.connects)
        assertEquals(result, service(relays, store).getOrganicAdmins())
    }

    @Test
    fun `Transportfehler nach einem Claim verwirft ihn ebenfalls`() = runTest {
        val relays = ScriptedRelays()
        relays.onReq[relay(0)] = { sub, s ->
            s.pushEvent(sub, claimEvent(admin, claimer1))
            s.finish(java.io.IOException("reset"))
        }
        relays.onReq[relay(1)] = { sub, s ->
            s.pushEvent(sub, claimEvent(admin, claimer2))
            s.push(listOf("EOSE", sub))
        }
        val result = service(relays, store()).syncOrganicAdmins()
        assertEquals(listOf(claimer2.publicKeyHex), result.map { it.claimerPubkey })
    }

    @Test
    fun `sauberes Schliessen behaelt die Teilergebnisse`() = runTest {
        val relays = ScriptedRelays()
        relays.onReq[relay(0)] = { sub, s ->
            s.pushEvent(sub, claimEvent(admin, claimer1))
            s.finish()
        }
        relays.onReq[relay(1)] = { sub, s -> s.push(listOf("EOSE", sub)) }

        val result = service(relays, store()).syncOrganicAdmins()

        assertEquals(listOf(claimer1.publicKeyHex), result.map { it.claimerPubkey })
        assertEquals(listOf(relay(0)), relays.connector.connects)
    }

    @Test
    fun `leerer Abruf ueberschreibt organic_admin_claims nicht`() = runTest {
        val existing = VerifiedClaim("ab".repeat(32), "Wien", 3, 1, 1_700_000_000)
        val stored = space.einundzwanzig.meetup.core.common.json.DartJson.encode(listOf(existing.toJsonMap()))
        val relays = ScriptedRelays()
        AdminConstants.RELAYS.forEach { url -> relays.onReq[url] = { sub, s -> s.push(listOf("EOSE", sub)) } }
        val store = store(AdminConstants.KEY_ORGANIC_CLAIMS to stored)

        assertEquals(emptyList<VerifiedClaim>(), service(relays, store).syncOrganicAdmins())
        assertEquals(stored, store.getString(AdminConstants.KEY_ORGANIC_CLAIMS))
        assertEquals(AdminConstants.RELAYS, relays.connector.connects)
    }

    @Test
    fun `pro Claimer gewinnt der neueste claimedAt, Reihenfolge des ersten Auftretens`() = runTest {
        val now = nowSeconds()
        val relays = ScriptedRelays()
        relays.onReq[relay(0)] = { sub, s ->
            s.pushEvent(sub, claimEvent(admin, claimer1, createdAt = now - 100))
            s.pushEvent(sub, claimEvent(admin, claimer2, createdAt = now - 50))
            s.pushEvent(sub, claimEvent(admin, claimer1, createdAt = now - 10))
            s.pushEvent(sub, claimEvent(admin, claimer1, createdAt = now - 200))
            s.push(listOf("EOSE", sub))
        }
        val store = store()
        val service = service(relays, store)

        assertEquals(4, service.syncOrganicAdmins().size)
        val saved = service.getOrganicAdmins()
        assertEquals(listOf(claimer1.publicKeyHex, claimer2.publicKeyHex), saved.map { it.claimerPubkey })
        assertEquals(listOf(now - 10, now - 50), saved.map { it.claimedAt })
    }

    private fun boundBadge(n: Int) = MeetupBadge(
        id = "b$n",
        meetupName = "Aschaffenburg",
        date = DartDateTime.utc(2026, 3, n, 18),
        iconPath = "",
        blockHeight = 875_000L + n,
        sig = "a$n",
        sigId = "b$n",
        adminPubkey = admin.publicKeyHex,
        sigVersion = 2,
        sigContent = "{}",
        claimSig = "c$n",
        claimEventId = "d$n",
        claimPubkey = own.publicKeyHex,
        claimTimestamp = 1_700_000_000L + n,
    )

    @Test
    fun `publishAdminClaim - sequentiell, zaehlt erreichbare Relays`() = runTest {
        val relays = ScriptedRelays()
        relays.onEvent[relay(0)] = { _, _ -> }
        relays.onEvent[relay(2)] = { _, _ -> }
        // relay(1), relay(3) unreachable

        assertTrue(service(relays, store()).publishAdminClaim(listOf(boundBadge(1), boundBadge(2), boundBadge(3)), "Aschaffenburg"))
        assertEquals(AdminConstants.RELAYS, relays.connector.connects)
        assertEquals(listOf(relay(0) to "EVENT", relay(2) to "EVENT"), relays.frames.toList())
    }

    @Test
    fun `publishAdminClaim - kein Relay erreichbar oder zu wenige Badges - false`() = runTest {
        val badges = listOf(boundBadge(1), boundBadge(2), boundBadge(3))
        assertFalse(service(ScriptedRelays(), store()).publishAdminClaim(badges, "Aschaffenburg"))

        val relays = ScriptedRelays()
        AdminConstants.RELAYS.forEach { url -> relays.onEvent[url] = { _, _ -> } }
        assertFalse(service(relays, store()).publishAdminClaim(badges.take(2), "Aschaffenburg"))
        assertEquals(emptyList<String>(), relays.connector.connects)
    }
}
