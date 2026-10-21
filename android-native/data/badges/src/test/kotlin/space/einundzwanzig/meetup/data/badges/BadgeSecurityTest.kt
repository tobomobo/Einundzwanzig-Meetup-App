package space.einundzwanzig.meetup.data.badges

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.badges.security.ExpiryInfo
import space.einundzwanzig.meetup.data.identity.SigningException
import java.time.ZoneOffset

/** Port of `test/badge_security_test.dart`, `security_followup_test.dart` (H1) and the spec 02 vectors. */
class BadgeSecurityTest {

    private val now = 1_760_000_000L
    private val testKey = KeyPair.generate()
    private val testPubkeyHex = testKey.publicKeyHex

    private fun createValidCompactPayload(
        meetupId: String = "aschaffenburg-de",
        blockHeight: Long = 875000,
        expiresAt: Long? = null,
        key: KeyPair = testKey,
        createdAt: Long = now,
    ): JsonObject {
        val expiry = expiresAt ?: (now + 6 * 3600)
        val content = jsonObjectOf("v" to 2, "t" to "B", "m" to meetupId, "b" to blockHeight, "x" to expiry)
        val event = EventIds.sign(key, createdAt, 21000, listOf(listOf("t", "badge"), listOf("m", meetupId)), BadgeSecurity.canonicalJsonEncode(content))
        return JsonObject(content + mapOf("c" to JsonPrimitive(event.createdAt), "p" to JsonPrimitive(event.pubkey), "s" to JsonPrimitive(event.sig)))
    }

    private fun JsonObject.with(key: String, value: Any?): JsonObject =
        JsonObject(LinkedHashMap<String, JsonElement>(this).also { it[key] = jsonObjectOf("x" to value)["x"]!! })

    private fun JsonObject.without(key: String): JsonObject = JsonObject(filterKeys { it != key })

    // ---- isValidPubkeyHex / isValidSignatureHex

    @Test fun `pubkey valid 64 hex`() = assertTrue(BadgeSecurity.isValidPubkeyHex(testPubkeyHex))

    @Test fun `pubkey too short`() = assertFalse(BadgeSecurity.isValidPubkeyHex("abcdef1234"))

    @Test fun `pubkey too long`() = assertFalse(BadgeSecurity.isValidPubkeyHex("a".repeat(65)))

    @Test
    fun `pubkey non hex`() {
        assertFalse(BadgeSecurity.isValidPubkeyHex("g".repeat(64)))
        assertFalse(BadgeSecurity.isValidPubkeyHex("x" + "a".repeat(63)))
    }

    @Test fun `pubkey empty`() = assertFalse(BadgeSecurity.isValidPubkeyHex(""))

    @Test
    fun `pubkey upper case accepted`() {
        assertTrue(BadgeSecurity.isValidPubkeyHex("A".repeat(64)))
        assertTrue(BadgeSecurity.isValidPubkeyHex("aAbBcCdDeEfF".repeat(5) + "aAbB"))
    }

    @Test fun `signature valid 128`() = assertTrue(BadgeSecurity.isValidSignatureHex("a".repeat(128)))

    @Test fun `signature too short`() = assertFalse(BadgeSecurity.isValidSignatureHex("a".repeat(127)))

    @Test fun `signature too long`() = assertFalse(BadgeSecurity.isValidSignatureHex("a".repeat(129)))

    @Test fun `signature non hex`() = assertFalse(BadgeSecurity.isValidSignatureHex("z".repeat(128)))

    // ---- canonicalJsonEncode

    @Test
    fun `canonical sorts keys`() {
        assertEquals("""{"a":2,"b":4,"m":3,"z":1}""", BadgeSecurity.canonicalJsonEncode(linkedMapOf("z" to 1, "a" to 2, "m" to 3, "b" to 4)))
    }

    @Test
    fun `canonical independent of insertion order`() {
        val a = BadgeSecurity.canonicalJsonEncode(linkedMapOf("x" to 1, "a" to 2, "c" to 3))
        val b = BadgeSecurity.canonicalJsonEncode(linkedMapOf("c" to 3, "x" to 1, "a" to 2))
        val c = BadgeSecurity.canonicalJsonEncode(linkedMapOf("a" to 2, "x" to 1, "c" to 3))
        assertEquals(a, b)
        assertEquals(b, c)
    }

    @Test fun `canonical empty map`() = assertEquals("{}", BadgeSecurity.canonicalJsonEncode(emptyMap()))

    @Test
    fun `canonical nested values`() {
        assertEquals(
            """{"a":{"nested":true},"b":[1,2,3]}""",
            BadgeSecurity.canonicalJsonEncode(linkedMapOf("b" to listOf(1, 2, 3), "a" to linkedMapOf("nested" to true))),
        )
    }

    @Test
    fun `fixture canonicalJson`() {
        for (v in Fixtures["canonicalJson"].jsonArray) {
            val o = v.jsonObject
            assertEquals(o["expected"]!!.jsonPrimitive.content, BadgeSecurity.canonicalJsonEncode(o["input_insertion_order"]!!.jsonObject))
        }
    }

    // ---- Legacy v1

    @Test fun `signLegacy empty`() = assertEquals("", BadgeSecurity.signLegacy("meetup-123", "2025-01-01", 875000))

    @Test
    fun `verifyLegacy false`() {
        assertFalse(BadgeSecurity.verifyLegacy(jsonObjectOf("meetup_id" to "test", "timestamp" to "2025-01-01", "hash" to "fakehash")))
    }

    @Test
    fun `verify rejects v1 with upgrade hint`() {
        val result = BadgeSecurity.verify(jsonObjectOf("v" to 1, "meetup_id" to "test", "timestamp" to "2025-01-01", "hash" to "anything"), now)
        assertFalse(result.isValid)
        assertEquals(1, result.version)
        assertTrue(result.message.contains("v1"))
        assertTrue(result.message.contains("nicht mehr akzeptiert"))
    }

    @Test
    fun `verify rejects v0`() {
        assertFalse(BadgeSecurity.verify(jsonObjectOf("meetup_id" to "test", "timestamp" to "2025-01-01"), now).isValid)
    }

    // ---- verifyCompact

    @Test
    fun `accepts valid compact badge`() {
        val result = BadgeSecurity.verifyCompact(createValidCompactPayload(), now)
        assertTrue(result.isValid)
        assertEquals(2, result.version)
        assertEquals(testPubkeyHex, result.adminPubkey)
        assertTrue(result.adminNpub.startsWith("npub1"))
        assertTrue(result.message.contains("gültig"))
    }

    @Test
    fun `rejects tampered meetup id`() {
        val result = BadgeSecurity.verifyCompact(createValidCompactPayload().with("m", "manipuliert-de"), now)
        assertFalse(result.isValid)
        assertEquals("Verifikations-Fehler: Invalid event", result.message)
        assertEquals(0, result.version)
    }

    @Test
    fun `rejects tampered block height`() {
        assertFalse(BadgeSecurity.verifyCompact(createValidCompactPayload().with("b", 999999), now).isValid)
    }

    @Test
    fun `rejects other pubkey`() {
        assertFalse(BadgeSecurity.verifyCompact(createValidCompactPayload().with("p", KeyPair.generate().publicKeyHex), now).isValid)
    }

    @Test
    fun `rejects tampered signature`() {
        val payload = createValidCompactPayload()
        val sig = payload["s"]!!.jsonPrimitive.content
        val flipped = (if (sig[0] == 'a') 'b' else 'a') + sig.substring(1)
        assertFalse(BadgeSecurity.verifyCompact(payload.with("s", flipped), now).isValid)
    }

    @Test
    fun `rejects expired badge`() {
        val result = BadgeSecurity.verifyCompact(createValidCompactPayload(expiresAt = now - 3600), now)
        assertFalse(result.isValid)
        assertTrue(result.message.contains("abgelaufen"))
        assertEquals("Tag abgelaufen! Abgelaufen vor 1h 0min. Bitte Organisator um neuen Tag.", result.message)
        // checkExpiry = false (promotion verifiers): only the signature counts.
        assertTrue(BadgeSecurity.verifyCompact(createValidCompactPayload(expiresAt = now - 3600), now, checkExpiry = false).isValid)
    }

    @Test
    fun `rejects missing signature`() {
        val result = BadgeSecurity.verifyCompact(
            jsonObjectOf("v" to 2, "t" to "B", "m" to "test-de", "b" to 875000, "c" to 1700000000, "p" to testPubkeyHex), now,
        )
        assertFalse(result.isValid)
        assertTrue(result.message.contains("Fehlende"))
    }

    @Test
    fun `rejects bad pubkey format`() {
        val result = BadgeSecurity.verifyCompact(
            jsonObjectOf("v" to 2, "t" to "B", "m" to "test-de", "b" to 875000, "c" to 1700000000, "p" to "zu_kurz", "s" to "a".repeat(128)), now,
        )
        assertFalse(result.isValid)
        assertTrue(result.message.contains("Pubkey-Format"))
    }

    @Test
    fun `rejects bad signature format`() {
        val result = BadgeSecurity.verifyCompact(
            jsonObjectOf("v" to 2, "t" to "B", "m" to "test-de", "b" to 875000, "c" to 1700000000, "p" to testPubkeyHex, "s" to "kurz"), now,
        )
        assertFalse(result.isValid)
        assertTrue(result.message.contains("Signatur-Format"))
    }

    @Test
    fun `cross signing with another key is rejected`() {
        val other = KeyPair.generate()
        val content = jsonObjectOf("v" to 2, "t" to "B", "m" to "test-de", "b" to 875000, "x" to 9999999999L)
        val event = EventIds.sign(other, now, 21000, listOf(listOf("t", "badge"), listOf("m", "test-de")), BadgeSecurity.canonicalJsonEncode(content))
        val payload = JsonObject(content + mapOf("c" to JsonPrimitive(event.createdAt), "p" to JsonPrimitive(testPubkeyHex), "s" to JsonPrimitive(event.sig)))
        assertFalse(BadgeSecurity.verifyCompact(payload, now).isValid)
    }

    @Test
    fun `unsigned add-ons do not enter the hash`() {
        val payload = createValidCompactPayload().with("n", "abcd").with("ts", 176000000).with("d", "rolling_qr").with("la", 50.5).with("lo", 7.1)
        assertTrue(BadgeSecurity.verifyCompact(payload, now).isValid)
    }

    @Test
    fun `double created_at is invalid`() {
        val payload = createValidCompactPayload()
        val c = payload["c"]!!.jsonPrimitive.content.toLong()
        val result = BadgeSecurity.verifyCompact(payload.with("c", c.toDouble()), now)
        assertFalse(result.isValid)
        assertEquals(0, result.version)
        assertTrue(result.message.startsWith("Verifikations-Fehler"))
    }

    @Test
    fun `double expiry is invalid`() {
        assertFalse(BadgeSecurity.verifyCompact(createValidCompactPayload().with("x", (now + 100).toDouble()), now).isValid)
    }

    @Test
    fun `upper case p is invalid like Flutter`() {
        val payload = createValidCompactPayload()
        val upper = payload.with("p", payload["p"]!!.jsonPrimitive.content.uppercase())
        assertFalse(BadgeSecurity.verifyCompact(upper, now).isValid)
    }

    @Test
    fun `created_at with 9 or 11 digits is invalid`() {
        assertFalse(BadgeSecurity.verifyCompact(createValidCompactPayload(createdAt = 999_999_999), now).isValid)
        assertFalse(BadgeSecurity.verifyCompact(createValidCompactPayload(createdAt = 10_000_000_000), now).isValid)
    }

    @Test
    fun `fixture compactBadge`() {
        val o = Fixtures["compactBadge"].jsonObject
        val base = DartJson.decode(o["base_payload_json"]!!.jsonPrimitive.content).jsonObject
        val content = o["content"]!!.jsonPrimitive.content
        val pub = base["p"]!!.jsonPrimitive.content
        assertEquals(o["serialized_for_id"]!!.jsonPrimitive.content, EventIds.serialize(pub, 1760000000, 21000, listOf(listOf("t", "badge"), listOf("m", "bad-neuenahr-de")), content))
        assertEquals(o["id"]!!.jsonPrimitive.content, EventIds.compute(pub, 1760000000, 21000, listOf(listOf("t", "badge"), listOf("m", "bad-neuenahr-de")), content))
        val result = BadgeSecurity.verifyCompact(base, 1760000001)
        assertEquals(o["valid"]!!.jsonPrimitive.boolean, result.isValid)
        assertEquals(o["tampered_b_valid"]!!.jsonPrimitive.boolean, BadgeSecurity.verifyCompact(base.with("b", 917001), 1760000001).isValid)
        assertEquals(o["base_payload_json"]!!.jsonPrimitive.content, DartJson.encode(base))
    }

    @Test
    fun `signCompact produces the base payload in Dart order`() = runTest {
        val signer = TestSigner(KeyPair.fromPrivateKeyHex("0".repeat(63) + "1"), FakeTime(now * 1000))
        val payload = BadgeSecurity.signCompact(signer, "bad-neuenahr-de", 917000, nowSeconds = now)
        assertEquals(listOf("v", "t", "m", "b", "x", "c", "p", "s"), payload.keys.toList())
        assertEquals(JsonPrimitive(now + 4 * 3600), payload["x"])
        assertTrue(BadgeSecurity.verify(payload, now).isValid)
        signer.canSign = false
        assertThrows(SigningException::class.java) {
            kotlinx.coroutines.runBlocking { BadgeSecurity.signCompact(signer, "x", 1, now) }
        }
    }

    // ---- verify routing

    @Test
    fun `verify routes compact`() {
        val result = BadgeSecurity.verify(createValidCompactPayload(), now)
        assertTrue(result.isValid)
        assertEquals(2, result.version)
    }

    @Test
    fun `verify rejects v1 legacy`() {
        val result = BadgeSecurity.verify(jsonObjectOf("v" to 1, "meetup_id" to "test", "timestamp" to "2025-01-01", "hash" to "x"), now)
        assertFalse(result.isValid)
        assertEquals(1, result.version)
    }

    @Test
    fun `verify v2 legacy without created_at fails`() {
        val result = BadgeSecurity.verify(
            jsonObjectOf("v" to 2, "meetup_id" to "test", "admin_pubkey" to testPubkeyHex, "sig" to "a".repeat(128), "sig_id" to "b".repeat(64)), now,
        )
        assertFalse(result.isValid)
        assertEquals("Signatur ungültig!", result.message)
    }

    @Test
    fun `verifyNostr accepts a correctly signed legacy full badge`() {
        val tagData = jsonObjectOf(
            "v" to 2, "type" to "BADGE", "meetup_id" to "wien", "meetup_name" to "Wien", "meetup_country" to "AT",
            "meetup_date" to "2025-03-15", "timestamp" to "2025-03-15", "block_height" to 875000, "admin_npub" to "npub1x",
        )
        val ev = EventIds.sign(
            testKey, now, 21000,
            listOf(listOf("t", "badge"), listOf("meetup", "wien"), listOf("block", "875000")),
            BadgeSecurity.canonicalJsonEncode(tagData),
        )
        val full = JsonObject(
            tagData + mapOf(
                "sig" to JsonPrimitive(ev.sig), "sig_id" to JsonPrimitive(ev.id),
                "admin_pubkey" to JsonPrimitive(ev.pubkey), "created_at" to JsonPrimitive(ev.createdAt),
                "_verified_by" to JsonPrimitive("x"),
            ),
        )
        assertTrue(BadgeSecurity.verifyNostr(full))
        val result = BadgeSecurity.verify(full, now)
        assertTrue(result.isValid)
        assertEquals("Schnorr-Signatur gültig (Legacy-Format)", result.message)
        assertFalse(BadgeSecurity.verifyNostr(full.with("block_height", 1)))
        assertFalse(BadgeSecurity.verifyNostr(full.with("created_at", now.toDouble())))
    }

    // ---- normalize

    @Test
    fun `normalize converts compact format`() {
        val full = BadgeSecurity.normalize(
            jsonObjectOf("v" to 2, "t" to "B", "m" to "aschaffenburg-de", "b" to 875000, "c" to 1700000000, "x" to 1700021600, "p" to testPubkeyHex, "s" to "a".repeat(128)),
        )
        assertEquals(JsonPrimitive(2), full["v"])
        assertEquals(JsonPrimitive("BADGE"), full["type"])
        assertEquals(JsonPrimitive("aschaffenburg"), full["meetup_id"])
        assertEquals(JsonPrimitive("DE"), full["meetup_country"])
        assertEquals(JsonPrimitive(875000), full["block_height"])
        assertEquals(JsonPrimitive(testPubkeyHex), full["admin_pubkey"])
        assertEquals(JsonPrimitive("a".repeat(128)), full["sig"])
        assertEquals(JsonPrimitive(1700021600), full["expires_at"])
    }

    @Test
    fun `normalize timestamp from c is local ISO`() {
        val full = BadgeSecurity.normalize(jsonObjectOf("v" to 2, "t" to "B", "m" to "x", "b" to 0, "c" to 1700000000), ZoneOffset.UTC)
        assertEquals(JsonPrimitive("2023-11-14T22:13:20.000"), full["timestamp"])
        assertEquals(full["timestamp"], full["meetup_date"])
    }

    @Test
    fun `normalize name from id`() {
        val full = BadgeSecurity.normalize(jsonObjectOf("v" to 2, "t" to "B", "m" to "münchen-de", "b" to 0))
        assertEquals(JsonPrimitive("München"), full["meetup_name"])
        assertEquals(JsonPrimitive("DE"), full["meetup_country"])
    }

    @Test
    fun `normalize returns already normalized map`() {
        val full = jsonObjectOf("meetup_id" to "test", "v" to 2)
        assertSame(full, BadgeSecurity.normalize(full))
    }

    @Test
    fun `normalize delivery qr`() {
        assertEquals(JsonPrimitive("rolling_qr"), BadgeSecurity.normalize(jsonObjectOf("v" to 2, "t" to "B", "m" to "test", "b" to 0, "d" to "qr"))["delivery"])
    }

    @Test
    fun `normalize admin npub`() {
        val full = BadgeSecurity.normalize(jsonObjectOf("v" to 2, "t" to "B", "m" to "test-de", "b" to 0, "p" to testPubkeyHex))
        assertTrue(full["admin_npub"]!!.jsonPrimitive.content.startsWith("npub1"))
    }

    @Test
    fun `fixture normalize`() {
        for (v in Fixtures["normalize"].jsonArray) {
            val o = v.jsonObject
            assertEquals(DartJson.encode(o["expected"]!!), DartJson.encode(BadgeSecurity.normalize(o["input"]!!.jsonObject, ZoneOffset.UTC)))
        }
    }

    @Test
    fun `fixture eventMeetupId and address`() {
        val o = Fixtures["eventMeetupId"].jsonObject
        val m = BadgeSecurity.eventMeetupId(o["eventAddress"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content)
        assertEquals(o["expected"]!!.jsonPrimitive.content, m)
        val normalized = BadgeSecurity.normalize(jsonObjectOf("v" to 2, "t" to "B", "m" to m, "b" to 0))
        assertEquals(o["eventAddress"]!!.jsonPrimitive.content, BadgeSecurity.eventAddressOf(normalized))
        assertNull(BadgeSecurity.eventAddressOf(BadgeSecurity.normalize(jsonObjectOf("m" to "wien-at"))))
        assertEquals("evt|31923:a:b", BadgeSecurity.eventBadgeKey("31923:a:b"))
    }

    // ---- expiry

    @Test fun `isExpired future`() = assertFalse(BadgeSecurity.isExpired(jsonObjectOf("x" to now + 3600), now))

    @Test fun `isExpired past`() = assertTrue(BadgeSecurity.isExpired(jsonObjectOf("x" to now - 3600), now))

    @Test
    fun `isExpired without expiry`() {
        assertFalse(BadgeSecurity.isExpired(JsonObject(emptyMap()), now))
        assertFalse(BadgeSecurity.isExpired(jsonObjectOf("x" to 0), now))
    }

    @Test fun `isExpired expires_at`() = assertTrue(BadgeSecurity.isExpired(jsonObjectOf("expires_at" to now - 1), now))

    @Test
    fun `expiryInfo texts`() {
        assertEquals("Kein Ablauf", BadgeSecurity.expiryInfo(JsonObject(emptyMap()), now).toGermanString())
        assertTrue(BadgeSecurity.expiryInfo(jsonObjectOf("x" to now - 7200), now).toGermanString().contains("Abgelaufen"))
        assertTrue(BadgeSecurity.expiryInfo(jsonObjectOf("x" to now + 7200), now).toGermanString().contains("Gültig"))
        assertEquals("Abgelaufen vor 1h 3min", ExpiryInfo.Expired(3780).toGermanString())
        assertEquals("Abgelaufen vor 0min", ExpiryInfo.Expired(0).toGermanString())
        assertEquals("Gültig noch 12min", ExpiryInfo.Valid(12 * 60 + 59).toGermanString())
        assertEquals(ExpiryInfo.Expired(0), BadgeSecurity.expiryInfo(jsonObjectOf("x" to now), now))
    }

    // ---- QR

    @Test
    fun `verifyQRLegacy always rejects`() {
        assertFalse(BadgeSecurity.verifyQrLegacy("""{"test": true}""", "fake_signature").isValid)
    }

    @Test
    fun `fixture reputationQrV3`() {
        val o = Fixtures["reputationQrV3"].jsonObject
        val parts = BadgeSecurity.parseQrV3(o["qr_string"]!!.jsonPrimitive.content)!!
        assertEquals(o["json"]!!.jsonPrimitive.content, parts.json)
        assertEquals(o["sig"]!!.jsonPrimitive.content, parts.signature)
        assertEquals(o["id"]!!.jsonPrimitive.content, parts.eventId)
        assertEquals(1760000000L, parts.createdAt)
        val result = BadgeSecurity.verifyQrV3(parts)
        assertEquals(o["valid"]!!.jsonPrimitive.boolean, result.isValid)
        assertEquals(3, result.version)
        assertEquals("Schnorr-Signatur verifiziert ✓", result.message)
        assertEquals(Fixtures["keys"].jsonObject["attendee"]!!.jsonObject["npub"]!!.jsonPrimitive.content, result.signerNpub)

        val tampered = BadgeSecurity.verifyQrV3(parts.copy(json = parts.json.replace("STARTER", "LEGEND")))
        assertFalse(tampered.isValid)
        assertEquals("Fehler: Invalid event", tampered.message)
        assertNull(BadgeSecurity.parseQrV3("21:abc.def"))
        assertNull(BadgeSecurity.parseQrV3("21v3:abc.def"))
        assertEquals("Fehlende Signatur-Daten", BadgeSecurity.verifyQrV3("{}", "", "x", 1, "y").message)
    }

    @Test
    fun `parseQrV3 reads created_at like Dart int tryParse, including hex`() {
        val o = Fixtures["reputationQrV3"].jsonObject
        val code = o["qr_string"]!!.jsonPrimitive.content
        val fields = code.substring(BadgeSecurity.QR_V3_PREFIX.length).split('.').toMutableList()
        fields[3] = "0x68E77800" // 1760000000 in hex
        val hexCode = BadgeSecurity.QR_V3_PREFIX + fields.joinToString(".")
        val parts = BadgeSecurity.parseQrV3(hexCode)!!
        assertEquals(1760000000L, parts.createdAt)
        assertEquals(o["valid"]!!.jsonPrimitive.boolean, BadgeSecurity.verifyQrV3(parts).isValid)
    }

    @Test
    fun `dartIntTryParse vectors`() {
        val cases = mapOf(
            "1760000000" to 1760000000L,
            " 42 " to 42L,
            "+7" to 7L,
            "-7" to -7L,
            "0x68E77800" to 1760000000L,
            "0X68e77800" to 1760000000L,
            "-0x10" to -16L,
            "+0x10" to 16L,
            "0x0000000000000000001" to 1L,
            "0xFFFFFFFFFFFFFFFF" to -1L,
            "0x8000000000000000" to Long.MIN_VALUE,
            "9223372036854775807" to Long.MAX_VALUE,
            "-9223372036854775808" to Long.MIN_VALUE,
            "007" to 7L,
            "0" to 0L,
        )
        for ((input, expected) in cases) assertEquals(input, expected, BadgeSecurity.dartIntTryParse(input))
        for (bad in listOf("", " ", "+", "-", "0x", "0xG", "0x10000000000000000", "9223372036854775808", "1.0", "++1", "+-1", "1_000", "\u0661", "x10", "0b1")) {
            assertNull(bad, BadgeSecurity.dartIntTryParse(bad))
        }
    }

    @Test
    fun `signQrV3 signs kind 21001 with empty tags`() = runTest {
        val signer = TestSigner(time = FakeTime(now * 1000))
        val r = BadgeSecurity.signQrV3(signer, """{"v":6}""")
        assertTrue(r.isNostr)
        assertTrue(BadgeSecurity.verifyQrV3("""{"v":6}""", r.signature, r.eventId, r.createdAt, r.pubkeyHex).isValid)
        signer.canSign = false
        assertEquals(space.einundzwanzig.meetup.data.badges.security.QrSignResult("", "", 0, "", false), BadgeSecurity.signQrV3(signer, "{}"))
    }

    // ---- security_followup_test (H1)

    @Test
    fun `unsigned la lo are no measured reference`() {
        val coords = BadgeSecurity.signedCoordinates(jsonObjectOf("v" to 2, "t" to "B", "m" to "aschaffenburg-de", "la" to 48.1, "lo" to 10.2))
        assertEquals(0.0 to 0.0, coords)
        assertFalse(BadgeSecurity.COMPACT_SIGNED_KEYS.contains("la"))
    }
}
