package space.einundzwanzig.meetup.data.issuance

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.identity.SigningException
import space.einundzwanzig.meetup.data.issuance.internal.DefaultRollingQrService
import space.einundzwanzig.meetup.data.issuance.internal.SeedSource
import space.einundzwanzig.meetup.data.issuance.session.RollingQr
import space.einundzwanzig.meetup.data.issuance.session.RollingQrKeys
import space.einundzwanzig.meetup.data.issuance.session.SessionStartException

/** Session persistence and lifecycle (spec 02 §4.3, §8.5). */
class RollingQrServiceTest {

    private val time = FakeTime()
    private val prefs = InMemoryKeyValueStore()
    private val secure = InMemorySecureStore()
    private val signer = TestSigner(ORGANIZER, time)
    private val identity = FakeIdentity(npub = ORGANIZER.npub)
    private var seedCounter = 0
    private val seeds = SeedSource { "%064x".format(++seedCounter) }

    private fun service(blockHeights: FakeBlockHeights = FakeBlockHeights(917_000)) =
        DefaultRollingQrService(prefs, secure, signer, identity, blockHeights, time, seeds).also { it.log = quietLog() }

    @Test
    fun `create persists the exact Flutter keys and a verifiable base payload`() = runTest {
        val svc = service()
        val s = svc.getOrCreateSession("bad-neuenahr-de", "Bad Neuenahr", "DE", blockHeight = 0, lat = 50.5, lng = 7.1)
        val now = time.millis / 1000
        assertEquals(now, s.startedAt)
        assertEquals(now + 4 * 3600, s.expiresAt)
        assertEquals(917_000, s.blockHeight)
        assertEquals(ORGANIZER.npub, s.pubkey)

        val e = prefs.entries.value
        assertEquals(PrefValue.IntValue(now), e[RollingQrKeys.START])
        assertEquals(PrefValue.IntValue(now + 14_400), e[RollingQrKeys.EXPIRES])
        assertEquals(PrefValue.StringValue("bad-neuenahr-de"), e[RollingQrKeys.MEETUP_ID])
        assertEquals(PrefValue.StringValue("Bad Neuenahr"), e[RollingQrKeys.MEETUP_NAME])
        assertEquals(PrefValue.StringValue("DE"), e[RollingQrKeys.MEETUP_COUNTRY])
        assertEquals(PrefValue.IntValue(917_000), e[RollingQrKeys.BLOCK_HEIGHT])
        assertEquals(PrefValue.StringValue(ORGANIZER.npub), e[RollingQrKeys.PUBKEY])
        assertEquals(PrefValue.DoubleValue(50.5), e[RollingQrKeys.LAT])
        assertEquals(PrefValue.DoubleValue(7.1), e[RollingQrKeys.LNG])
        assertEquals("%064x".format(1), secure.read(RollingQrKeys.SECURE_SEED))
        assertFalse(RollingQrKeys.SECURE_SEED in e)

        val base = DartJson.decode(secure.read(RollingQrKeys.SECURE_BASE_PAYLOAD)!!).jsonObject
        assertEquals(listOf("v", "t", "m", "b", "x", "c", "p", "s"), base.keys.toList())
        assertEquals("bad-neuenahr-de", base.getValue("m").jsonPrimitive.content)
        assertEquals(s.expiresAt, base.getValue("x").jsonPrimitive.long)
        assertEquals(ORGANIZER.publicKeyHex, base.getValue("p").jsonPrimitive.content)
        assertTrue(BadgeSecurity.verify(base, now).isValid)
        assertEquals(s, svc.activeSession.value)
    }

    @Test
    fun `load returns the stored session and reuse keeps it`() = runTest {
        val svc = service()
        val created = svc.getOrCreateSession("wien-at", "Wien", "AT", blockHeight = 900_000)
        assertEquals(created, service().loadSession())
        time.millis += 60_000
        val again = svc.getOrCreateSession("wien-at", "Wien", "AT", blockHeight = 900_001)
        assertEquals(created, again)
        assertEquals(1, signer.calls)
        assertEquals(1, seedCounter)
    }

    @Test
    fun `another meetup replaces the single slot`() = runTest {
        val svc = service()
        svc.getOrCreateSession("wien-at", "Wien", "AT", blockHeight = 900_000)
        val other = svc.getOrCreateSession("graz-at", "Graz", "AT", blockHeight = 900_000)
        assertEquals("graz-at", svc.loadSession()!!.meetupId)
        assertEquals(other.seed, secure.read(RollingQrKeys.SECURE_SEED))
        val base = svc.getBasePayload()!!
        assertEquals("graz-at", base.getValue("m").jsonPrimitive.content)
    }

    @Test
    fun `a longer validUntil replaces the running session, a shorter one reuses it`() = runTest {
        val svc = service()
        val now = time.millis / 1000
        val first = svc.getOrCreateSession("evt:x", "Fest", "", 1, validUntilEpoch = now + 3600)
        assertEquals(now + 3600, first.expiresAt)
        assertEquals(first, svc.getOrCreateSession("evt:x", "Fest", "", 1, validUntilEpoch = now + 1800))
        assertEquals(first, svc.getOrCreateSession("evt:x", "Fest", "", 1))
        val longer = svc.getOrCreateSession("evt:x", "Fest", "", 1, validUntilEpoch = now + 36_000)
        assertNotEquals(first.seed, longer.seed)
        assertEquals(now + 36_000, longer.expiresAt)
        // Flutter bound: the signed x stays now + 4 h (replay bound); re-signed later.
        assertEquals(now + 4 * 3600, svc.getBasePayload()!!.getValue("x").jsonPrimitive.long)
    }

    @Test
    fun `event session re-signs the base payload shortly before x runs out`() = runTest {
        val svc = service()
        val start = time.millis / 1000
        val s = svc.getOrCreateSession("evt:x", "Fest", "", 1, validUntilEpoch = start + 36_000)
        assertEquals(1, signer.calls)

        // 20 min before x: nothing to do.
        time.millis = (start + 4 * 3600 - 20 * 60) * 1000
        svc.generateQrString(s)
        assertEquals(1, signer.calls)

        // 5 min before x: re-signed with x = now + 4 h, stored, and the QR carries it.
        time.millis = (start + 4 * 3600 - 5 * 60) * 1000
        val now = time.millis / 1000
        val qr = DartJson.decode(svc.generateQrString(s)).jsonObject
        assertEquals(2, signer.calls)
        assertEquals(now + 4 * 3600, qr.getValue("x").jsonPrimitive.long)
        val stored = DartJson.decode(secure.read(RollingQrKeys.SECURE_BASE_PAYLOAD)!!).jsonObject
        assertEquals(now + 4 * 3600, stored.getValue("x").jsonPrimitive.long)
        assertEquals("evt:x", stored.getValue("m").jsonPrimitive.content)
        assertTrue(BadgeSecurity.verify(stored, now).isValid)
        assertTrue(svc.validateNonce(qr).isValid)
        svc.generateQrString(s)
        assertEquals(2, signer.calls)

        // Last stretch: x is capped at the session end.
        time.millis = (start + 36_000 - 5 * 60) * 1000
        assertEquals(start + 36_000, svc.getBasePayload()!!.getValue("x").jsonPrimitive.long)
        assertEquals(3, signer.calls)
        // x == session end: never re-signed again.
        time.millis = (start + 36_000 - 60) * 1000
        svc.generateQrString(s)
        assertEquals(3, signer.calls)
    }

    @Test
    fun `a 4 h meetup session is never re-signed`() = runTest {
        val svc = service()
        val s = svc.getOrCreateSession("wien-at", "Wien", "AT", 1)
        time.millis += (4 * 3600 - 60) * 1000L
        svc.generateQrString(s)
        assertEquals(1, signer.calls)
    }

    @Test
    fun `failed re-sign keeps the old payload and backs off`() = runTest {
        val svc = service()
        val start = time.millis / 1000
        val s = svc.getOrCreateSession("evt:x", "Fest", "", 1, validUntilEpoch = start + 36_000)
        val before = secure.read(RollingQrKeys.SECURE_BASE_PAYLOAD)
        signer.failWith = SigningException("Signieren abgebrochen.")
        time.millis = (start + 4 * 3600 - 5 * 60) * 1000
        val qr = DartJson.decode(svc.generateQrString(s)).jsonObject
        assertEquals(start + 4 * 3600, qr.getValue("x").jsonPrimitive.long)
        assertEquals(before, secure.read(RollingQrKeys.SECURE_BASE_PAYLOAD))
        assertEquals(2, signer.calls)
        time.millis += 10_000
        svc.generateQrString(s)
        assertEquals(2, signer.calls)
        signer.failWith = null
        time.millis += 60_000
        svc.generateQrString(s)
        assertEquals(3, signer.calls)
        assertNotEquals(before, secure.read(RollingQrKeys.SECURE_BASE_PAYLOAD))
    }

    @Test
    fun `concurrent starts sign once and return the stored session`() = runTest {
        val svc = service(FakeBlockHeights())
        val a = async { svc.getOrCreateSession("wien-at", "Wien", "AT", 0) }
        val b = async { svc.getOrCreateSession("wien-at", "Wien", "AT", 0) }
        val sa = a.await()
        val sb = b.await()
        assertEquals(sa, sb)
        assertEquals(1, signer.calls)
        assertEquals(sa.seed, secure.read(RollingQrKeys.SECURE_SEED))
    }

    @Test
    fun `validUntil in the past falls back to four hours`() = runTest {
        val now = time.millis / 1000
        val s = service().getOrCreateSession("evt:y", "Fest", "", 1, validUntilEpoch = now - 1)
        assertEquals(now + RollingQr.SESSION_VALIDITY_HOURS * 3600, s.expiresAt)
        val s2 = service().getOrCreateSession("evt:z", "Fest", "", 1, validUntilEpoch = now)
        assertEquals(now + 14_400, s2.expiresAt)
    }

    @Test
    fun `expired session is wiped on load`() = runTest {
        val svc = service()
        svc.getOrCreateSession("wien-at", "Wien", "AT", 1)
        time.millis += (4 * 3600 + 1) * 1000L
        assertNull(svc.loadSession())
        assertNull(svc.activeSession.value)
        assertTrue(prefs.entries.value.keys.none { it.startsWith("rqr_session") })
        assertNull(secure.read(RollingQrKeys.SECURE_SEED))
        assertNull(secure.read(RollingQrKeys.SECURE_BASE_PAYLOAD))
    }

    @Test
    fun `session exactly at expiry is still valid`() = runTest {
        val svc = service()
        svc.getOrCreateSession("wien-at", "Wien", "AT", 1)
        time.millis += 4 * 3600 * 1000L
        assertNotNull(svc.loadSession())
    }

    @Test
    fun `legacy prefs seed and payload are migrated to the secure store`() = runTest {
        val now = time.millis / 1000
        prefs.edit {
            setString(RollingQrKeys.LEGACY_SEED, "ab".repeat(32))
            setString(RollingQrKeys.LEGACY_BASE_PAYLOAD, Fixtures.str(Fixtures.obj("compactBadge"), "base_payload_json"))
            setInt(RollingQrKeys.START, now)
            setInt(RollingQrKeys.EXPIRES, now + 100)
            setString(RollingQrKeys.MEETUP_ID, "bad-neuenahr-de")
        }
        val svc = service()
        val s = svc.loadSession()!!
        assertEquals("ab".repeat(32), s.seed)
        assertEquals("", s.meetupName)
        assertEquals("", s.meetupCountry)
        assertEquals(0, s.blockHeight)
        assertEquals(0.0, s.lat, 0.0)
        assertEquals("ab".repeat(32), secure.read(RollingQrKeys.SECURE_SEED))
        assertNull(prefs.getString(RollingQrKeys.LEGACY_SEED))

        val qr = svc.generateQrString(s)
        assertNull(prefs.getString(RollingQrKeys.LEGACY_BASE_PAYLOAD))
        assertTrue(qr.startsWith(Fixtures.str(Fixtures.obj("compactBadge"), "base_payload_json").dropLast(1) + ",\"n\":"))
        assertEquals(RollingQr.nonce(s.seed, RollingQr.timeStep(now)), DartJson.decode(qr).jsonObject.getValue("n").jsonPrimitive.content)
    }

    @Test
    fun `legacy seed stays in prefs when the secure write fails`() = runTest {
        prefs.setString(RollingQrKeys.LEGACY_SEED, "cd".repeat(32))
        secure.failWrite = { true }
        assertNull(service().loadSession())
        assertEquals("cd".repeat(32), prefs.getString(RollingQrKeys.LEGACY_SEED))
    }

    @Test
    fun `missing pieces mean no session`() = runTest {
        val svc = service()
        svc.getOrCreateSession("wien-at", "Wien", "AT", 1)
        prefs.remove(RollingQrKeys.MEETUP_ID)
        assertNull(svc.loadSession())
        // Not expired: nothing else is wiped (Dart only ends expired sessions).
        assertNotNull(secure.read(RollingQrKeys.SECURE_SEED))
    }

    @Test
    fun `no key throws NO_KEY and stores nothing`() = runTest {
        signer.canSign = false
        try {
            service().getOrCreateSession("wien-at", "Wien", "AT", 1)
            fail()
        } catch (e: SessionStartException) {
            assertEquals(SessionStartException.Reason.NO_KEY, e.reason)
            assertEquals("Kein Nostr-Key vorhanden. Bitte zuerst einen Key erstellen oder importieren.", e.message)
        }
        assertTrue(prefs.entries.value.isEmpty())
        assertNull(secure.read(RollingQrKeys.SECURE_SEED))
    }

    @Test
    fun `signer failure throws SIGNING_FAILED`() = runTest {
        signer.failWith = SigningException("Signieren abgebrochen.")
        try {
            service().getOrCreateSession("wien-at", "Wien", "AT", 1)
            fail()
        } catch (e: SessionStartException) {
            assertEquals(SessionStartException.Reason.SIGNING_FAILED, e.reason)
            assertTrue(e.message!!.startsWith("Badge-Signierung fehlgeschlagen: Signieren abgebrochen."))
        }
        assertNull(service().loadSession())
    }

    @Test
    fun `secure storage failure throws STORAGE_FAILED without a half session`() = runTest {
        secure.failWrite = { true }
        try {
            service().getOrCreateSession("wien-at", "Wien", "AT", 1)
            fail()
        } catch (e: SessionStartException) {
            assertEquals(SessionStartException.Reason.STORAGE_FAILED, e.reason)
        }
        assertTrue(prefs.entries.value.isEmpty())
    }

    @Test
    fun `prefs failure rolls the secure entries back`() = runTest {
        prefs.failingKeys = setOf(RollingQrKeys.START)
        try {
            service().getOrCreateSession("wien-at", "Wien", "AT", 1)
            fail()
        } catch (e: SessionStartException) {
            assertEquals(SessionStartException.Reason.STORAGE_FAILED, e.reason)
        }
        assertNull(secure.read(RollingQrKeys.SECURE_SEED))
        assertNull(secure.read(RollingQrKeys.SECURE_BASE_PAYLOAD))
    }

    @Test
    fun `block height retries once and stays 0 offline`() = runTest {
        val heights = FakeBlockHeights(0, 917_123)
        assertEquals(917_123, service(heights).getOrCreateSession("a", "A", "", 0).blockHeight)
        assertEquals(2, heights.calls)

        val offline = FakeBlockHeights()
        val s = service(offline).getOrCreateSession("b", "B", "", 0)
        assertEquals(0, s.blockHeight)
        assertEquals(2, offline.calls)
        assertEquals(0L, service().getBasePayload()!!.getValue("b").jsonPrimitive.long)

        val given = FakeBlockHeights()
        assertEquals(5, service(given).getOrCreateSession("c", "C", "", 5).blockHeight)
        assertEquals(0, given.calls)
    }

    @Test
    fun `session without npub still starts`() = runTest {
        identity.npub = null
        assertEquals("", service().getOrCreateSession("a", "A", "", 1).pubkey)
        identity.fail = IllegalStateException("unreadable")
        assertEquals("", service().getOrCreateSession("b", "B", "", 1).pubkey)
    }

    @Test
    fun `generateQrString without payload throws`() = runTest {
        val svc = service()
        val s = svc.getOrCreateSession("a", "A", "", 1)
        secure.delete(RollingQrKeys.SECURE_BASE_PAYLOAD)
        try {
            svc.generateQrString(s)
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("Kein signierter Base-Payload vorhanden. Bitte neue Session starten.", e.message)
        }
        assertNull(svc.getBasePayload())
    }

    @Test
    fun `generated qr changes with the time step and validates on the scanner`() = runTest {
        val svc = service()
        val s = svc.getOrCreateSession("bad-neuenahr-de", "Bad Neuenahr", "DE", 1, lat = 50.5453, lng = 7.1123)
        val qr1 = DartJson.decode(svc.generateQrString(s)).jsonObject
        assertEquals(listOf("v", "t", "m", "b", "x", "c", "p", "s", "n", "ts", "d", "la", "lo"), qr1.keys.toList())
        assertTrue(svc.validateNonce(qr1).isValid)
        time.millis += 10_000
        val qr2 = DartJson.decode(svc.generateQrString(s)).jsonObject
        assertNotEquals(qr1["n"], qr2["n"])
        time.millis += 10_000
        assertFalse(svc.validateNonce(qr1).isValid)
        assertTrue(svc.validateNonce(qr2).isValid)
        assertEquals(10, svc.secondsUntilNextChange())
    }

    @Test
    fun `endSession wipes everything`() = runTest {
        val svc = service()
        svc.getOrCreateSession("a", "A", "", 1)
        prefs.setString(RollingQrKeys.LEGACY_SEED, "x")
        prefs.setString(RollingQrKeys.LEGACY_BASE_PAYLOAD, "y")
        prefs.setString("other_key", "kept")
        svc.endSession()
        assertEquals(mapOf("other_key" to PrefValue.StringValue("kept")), prefs.entries.value)
        assertNull(secure.read(RollingQrKeys.SECURE_SEED))
        assertNull(secure.read(RollingQrKeys.SECURE_BASE_PAYLOAD))
        assertNull(svc.loadSession())
        assertNull(svc.activeSession.value)
    }
}
