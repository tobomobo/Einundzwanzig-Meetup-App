package space.einundzwanzig.meetup.data.issuance

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.crypto.HmacSha256
import space.einundzwanzig.meetup.core.crypto.toHex
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.issuance.session.MeetupSession
import space.einundzwanzig.meetup.data.issuance.session.NonceStatus
import space.einundzwanzig.meetup.data.issuance.session.RollingQr
import space.einundzwanzig.meetup.data.issuance.session.SessionRemaining

/** Spec 02 §7.5 / §8.5 and fixture `rollingQr`. */
class RollingQrTest {

    private val fixture = Fixtures.obj("rollingQr")
    private val seed = Fixtures.str(fixture, "seed_hex_string")
    private val step = fixture.getValue("time_step").toString().toLong()

    private fun session(lat: Double = 0.0, lng: Double = 0.0) = MeetupSession(
        seed = seed,
        startedAt = 1_760_000_000,
        expiresAt = 1_760_014_400,
        meetupId = "bad-neuenahr-de",
        meetupName = "Bad Neuenahr",
        meetupCountry = "DE",
        blockHeight = 917_000,
        pubkey = "",
        lat = lat,
        lng = lng,
    )

    @Test
    fun `hmac uses the hex string as UTF-8 key and the decimal step as message`() {
        val mac = HmacSha256.mac(DartUtf8.encode(seed), DartUtf8.encode(Fixtures.str(fixture, "hmac_message"))).toHex()
        assertEquals(Fixtures.str(fixture, "hmac_sha256_hex"), mac)
        assertEquals(Fixtures.str(fixture, "nonce"), RollingQr.nonce(seed, step))
    }

    @Test
    fun `time step is unix seconds div 10`() {
        assertEquals(step, RollingQr.timeStep(step * 10))
        assertEquals(step, RollingQr.timeStep(step * 10 + 9))
        assertEquals(step + 1, RollingQr.timeStep(step * 10 + 10))
    }

    @Test
    fun `qr string equals the fixture byte for byte`() {
        val base = Fixtures.str(Fixtures.obj("compactBadge"), "base_payload_json")
        for (offset in 0L..9L) {
            val qr = RollingQr.buildQrString(base, session(lat = 50.5453, lng = 7.1123), step * 10 + offset)
            assertEquals(Fixtures.str(fixture, "qr_string"), qr)
        }
    }

    @Test
    fun `no coordinates without session location`() {
        val base = Fixtures.str(Fixtures.obj("compactBadge"), "base_payload_json")
        val qr = RollingQr.buildQrString(base, session(), step * 10)
        assertEquals(
            base.dropLast(1) + ",\"n\":\"${Fixtures.str(fixture, "nonce")}\",\"ts\":$step,\"d\":\"rolling_qr\"}",
            qr,
        )
        // Only one coordinate set still appends both (Dart: lat != 0 || lng != 0).
        val one = DartJson.decode(RollingQr.buildQrString(base, session(lat = 0.0, lng = 7.5), step * 10)).jsonObject
        assertEquals("0.0", one.getValue("la").toString())
        assertEquals("7.5", one.getValue("lo").toString())
    }

    @Test
    fun `scanned qr verifies after stripping the rolling fields`() {
        val qr = DartJson.decode(Fixtures.str(fixture, "qr_string")).jsonObject
        val stripped = JsonObject(LinkedHashMap(qr).apply { listOf("n", "ts", "d").forEach { remove(it) } })
        // la/lo stay (Dart keeps them) and are not part of the whitelisted content.
        val result = BadgeSecurity.verify(stripped, nowSeconds = 1_760_000_005)
        assertTrue(result.message, result.isValid)
        assertEquals(2, result.version)
        // With n/ts still inside, the signature still verifies (whitelist), but freshness is separate.
        assertTrue(BadgeSecurity.verify(qr, nowSeconds = 1_760_000_005).isValid)
    }

    @Test
    fun `validateNonce boundaries`() {
        val now = step * 10 + 3
        fun check(ts: Long) = RollingQr.validateNonce(jsonObjectOf("n" to "abc", "ts" to ts), now)

        check(step).let {
            assertTrue(it.isValid); assertEquals(NonceStatus.FRESH, it.status); assertEquals(0, it.ageSeconds)
            assertEquals("Frisch ✓", it.message)
        }
        check(step - 1).let {
            assertTrue(it.isValid); assertEquals(10, it.ageSeconds); assertEquals("Gültig (10s)", it.message)
        }
        check(step + 1).let { assertTrue(it.isValid); assertEquals(NonceStatus.WITHIN_TOLERANCE, it.status) }
        check(step - 2).let {
            assertFalse(it.isValid); assertEquals(NonceStatus.EXPIRED, it.status); assertEquals(20, it.ageSeconds)
            assertEquals("QR-Code abgelaufen (20s alt). Bitte direkt am Bildschirm scannen.", it.message)
        }
        check(step + 2).let { assertFalse(it.isValid) }
        check(step - 360).let { assertFalse(it.isValid); assertEquals(3600, it.ageSeconds) }
    }

    @Test
    fun `validateNonce without nonce or step is a static payload`() {
        val now = step * 10
        listOf(
            jsonObjectOf(),
            jsonObjectOf("n" to "abc"),
            jsonObjectOf("ts" to step),
            jsonObjectOf("n" to null, "ts" to step),
        ).forEach {
            val v = RollingQr.validateNonce(it, now)
            assertTrue(v.isValid)
            assertEquals(NonceStatus.NOT_ROLLING, v.status)
            assertEquals("Kein Rolling-QR (NFC/Static)", v.message)
        }
    }

    @Test
    fun `validateNonce reads the long aliases`() {
        val now = step * 10
        assertFalse(RollingQr.validateNonce(jsonObjectOf("qr_nonce" to "x", "qr_time_step" to step - 5), now).isValid)
        assertTrue(RollingQr.validateNonce(jsonObjectOf("qr_nonce" to "x", "qr_time_step" to step), now).isValid)
        // n wins over qr_nonce, ts over qr_time_step.
        assertFalse(RollingQr.validateNonce(jsonObjectOf("n" to "x", "ts" to step - 5, "qr_time_step" to step), now).isValid)
        assertTrue(RollingQr.hasNonce(jsonObjectOf("qr_nonce" to "x")))
        assertFalse(RollingQr.hasNonce(jsonObjectOf("ts" to 1)))
    }

    @Test
    fun `validateNonce rejects wrongly typed fields like the Dart casts`() {
        val now = step * 10
        val doubleTs = DartJson.decode("{\"n\":\"x\",\"ts\":${step}.0}").jsonObject
        assertEquals(NonceStatus.MALFORMED, RollingQr.validateNonce(doubleTs, now).status)
        assertFalse(RollingQr.validateNonce(doubleTs, now).isValid)
        val intNonce = jsonObjectOf("n" to 5, "ts" to step)
        assertEquals(NonceStatus.MALFORMED, RollingQr.validateNonce(intNonce, now).status)
        val stringTs = JsonObject(mapOf("n" to JsonPrimitive("x"), "ts" to JsonPrimitive(step.toString())))
        assertEquals(NonceStatus.MALFORMED, RollingQr.validateNonce(stringTs, now).status)
    }

    @Test
    fun `countdown helpers`() {
        assertEquals(10, RollingQr.secondsUntilNextChange(1_760_000_000))
        assertEquals(1, RollingQr.secondsUntilNextChange(1_760_000_009))
        assertEquals(0.0, RollingQr.currentProgress(1_760_000_000), 0.0)
        assertEquals(0.7, RollingQr.currentProgress(1_760_000_007), 1e-12)
    }

    @Test
    fun `session remaining time strings`() {
        val s = session()
        assertEquals("4h 0min", s.remaining(1_760_000_000).toGermanString())
        assertEquals("1h 3min", s.remaining(1_760_014_400 - 3780).toGermanString())
        assertEquals("59min", s.remaining(1_760_014_400 - 3599).toGermanString())
        assertEquals("0min", s.remaining(1_760_014_399).toGermanString())
        assertEquals(SessionRemaining.Expired, s.remaining(1_760_014_400))
        assertEquals("Abgelaufen", s.remaining(1_760_020_000).toGermanString())
        assertFalse(s.isExpired(1_760_014_400))
        assertTrue(s.isExpired(1_760_014_401))
        assertEquals(0, s.remainingSeconds(1_760_020_000))
        assertEquals(100, s.elapsedSeconds(1_760_000_100))
        assertFalse(s.toString().contains(seed))
        assertFalse(s.isEventSession)
        assertTrue(s.copy(meetupId = "evt:31923:ab:d:x").isEventSession)
    }
}
