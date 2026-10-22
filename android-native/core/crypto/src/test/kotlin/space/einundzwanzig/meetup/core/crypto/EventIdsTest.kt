package space.einundzwanzig.meetup.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event id serialization/hashing and `Event.isValid()` parity. Includes the crypto part of
 * test/relay_event_signature_test.dart and the event vectors of spec fixtures 02, 04 and 07.
 */
class EventIdsTest {
    private val zeroAux = ByteArray(32)

    @Test
    fun escapingVectorFromSpec07() {
        val v = Fixtures.nostrInfra.obj("event_id_escaping")
        val serialized = EventIds.serialize(v.str("pubkey"), v.long("created_at"), v.int("kind"), v.tags("tags"), v.str("content"))
        assertEquals(v.str("expected_serialization_utf8"), serialized)
        assertEquals(
            v.str("expected_id"),
            EventIds.compute(v.str("pubkey"), v.long("created_at"), v.int("kind"), v.tags("tags"), v.str("content")),
        )
        assertEquals("7152cbaa303fca438bcb1f1490f20614a7989caa620127eec874c909a9f7a275", v.str("expected_id"))
    }

    @Test
    fun officialSignedEventsAreValid() {
        for (e in Fixtures.nostrInfra.arr("valid_signed_events")) {
            assertEquals(e.str("id"), EventIds.compute(e.str("pubkey"), e.long("created_at"), e.int("kind"), e.tags("tags"), e.str("content")))
            assertTrue(
                EventIds.isValid(e.str("id"), e.str("pubkey"), e.long("created_at"), e.int("kind"), e.tags("tags"), e.str("content"), e.str("sig")),
            )
        }
    }

    @Test
    fun compactBadgeAndClaimVectorsFromSpec02() {
        val keys = Fixtures.badges.obj("keys")
        for ((name, signer) in listOf("compactBadge" to "organizer", "claim" to "attendee")) {
            val v = Fixtures.badges.obj(name)
            val kp = KeyPair.fromPrivateKeyHex(keys.obj(signer).str("priv"))
            assertEquals(keys.obj(signer).str("pub"), kp.publicKeyHex)
            assertEquals(keys.obj(signer).str("npub"), kp.npub)
            val serialized = EventIds.serialize(kp.publicKeyHex, v.long("created_at"), v.int("kind"), v.tags("tags"), v.str("content"))
            assertEquals(v.str("serialized_for_id"), serialized)
            // Dart vectors were signed with aux_rand = 32 zero bytes → byte-identical signature.
            val signed = EventIds.sign(kp, v.long("created_at"), v.int("kind"), v.tags("tags"), v.str("content"), zeroAux)
            assertEquals(v.str("id"), signed.id)
            assertEquals(v.str("sig"), signed.sig)
            assertTrue(signed.isValid())
        }
        // tampered block height in the compact badge content → invalid
        val badge = Fixtures.badges.obj("compactBadge")
        val tamperedContent = badge.str("content").replace("917000", "917001")
        assertFalse(
            EventIds.isValid(badge.str("id"), keys.obj("organizer").str("pub"), badge.long("created_at"), badge.int("kind"), badge.tags("tags"), tamperedContent, badge.str("sig")),
        )
    }

    @Test
    fun reputationQrV3VectorsFromSpec02And04() {
        val a = Fixtures.badges.obj("reputationQrV3")
        val attendee = KeyPair.fromPrivateKeyHex(Fixtures.badges.obj("keys").obj("attendee").str("priv"))
        val signedA = EventIds.sign(attendee, a.long("created_at"), a.int("kind"), a.tags("tags"), a.str("json"), zeroAux)
        assertEquals(a.str("id"), signedA.id)
        assertEquals(a.str("sig"), signedA.sig)
        // QR string = "21v3:" + base64(json) + "." + sig + "." + id + "." + created_at + "." + pubkey
        assertEquals(
            a.str("qr_string"),
            "21v3:" + DartBase64.encode(DartUtf8.encode(a.str("json"))) + "." + signedA.sig + "." + signedA.id + "." +
                signedA.createdAt + "." + signedA.pubkey,
        )

        val b = Fixtures.trust.obj("reputationQrV3")
        val organizer = KeyPair.fromPrivateKeyHex(Fixtures.trust.obj("keys").str("privkey_hex"))
        val json = b.str("payload_json_exact")
        assertEquals(b.str("base64_standard_padded"), DartBase64.encode(DartUtf8.encode(json)))
        assertEquals(b.str("id_serialization"), EventIds.serialize(organizer.publicKeyHex, b.long("created_at"), b.int("kind"), emptyList(), json))
        val signedB = EventIds.sign(organizer, b.long("created_at"), b.int("kind"), emptyList(), json, zeroAux)
        assertEquals(b.str("event_id"), signedB.id)
        assertEquals(b.str("sig_aux0"), signedB.sig)
    }

    @Test
    fun reputationEvent30078FromSpec04IsValid() {
        val e = Fixtures.trust.obj("reputationEvent30078").obj("event")
        assertTrue(
            EventIds.isValid(e.str("id"), e.str("pubkey"), e.long("created_at"), e.int("kind"), e.tags("tags"), e.str("content"), e.str("sig")),
        )
        val signed = SignedNostrEvent(e.str("id"), e.str("pubkey"), e.long("created_at"), e.int("kind"), e.tags("tags"), e.str("content"), e.str("sig"))
        assertEquals(
            Fixtures.trust.obj("reputationEvent30078").str("publish_frame"),
            signed.toEventMessage(),
        )
    }

    @Test
    fun dartGeneratedSignaturesWithVariousAux() {
        // bip340.sign(priv, id, aux) from package:bip340 0.2.0 (the app's signer).
        val kp = KeyPair.fromPrivateKeyHex("67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa")
        assertEquals("7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e", kp.publicKeyHex)
        val tags = listOf(listOf("e", "x"), listOf("p", "y", ""))
        val content = "hallo 🧡 \"x\"\n"
        val id = EventIds.compute(kp.publicKeyHex, 1_700_000_000, 1, tags, content)
        assertEquals("eb72a771c1e24c2acfe073bdf940e08e7598521b62e01934ede1d8dc9ba4d9d1", id)
        val expected = mapOf(
            "00".repeat(32) to "c8e200cc8876e9e31e72b8fe1b882a1419e90caf8b1a6cb880c7c27dec9f3ca0188b88fc4c9444c1576a15c8589eb8fa7aace0c662802d2f8ba1dbdddd238dfa",
            "ff".repeat(32) to "9ffcbc2b0d7323e918990a886126ae45e4b542b32be49457f7743a796b4d0b2575df5bf1007f783413ace6f318944048e6691b3749d00845238f78ff163c52b5",
            "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20" to "005443ce0e5372b7994e6248eaec508188ff1af7d87d17478c7eb9069fd460c1f7602fc298191eee773960f07ae1bfd97a059b1096429f2d875e661ab0d563eb",
        )
        for ((aux, sig) in expected) {
            assertEquals(sig, Schnorr.signHex(id, kp.privateKeyHex, aux.hexToBytes()))
        }
        // bip340.verify accepts uppercase signature hex.
        assertTrue(Schnorr.verifyHex(kp.publicKeyHex, id, expected.values.first().uppercase()))
    }

    @Test
    fun isValidRulesOfNostr150() {
        val kp = KeyPair.fromPrivateKeyHex("0000000000000000000000000000000000000000000000000000000000000001")
        val ok = EventIds.sign(kp, 1_700_000_000, 1, emptyList(), "x")
        assertTrue(ok.isValid())

        // created_at must have exactly 10 digits (9 and 11 digits are invalid even when signed).
        for (createdAt in listOf(999_999_999L, 10_000_000_000L)) {
            val e = EventIds.sign(kp, createdAt, 1, emptyList(), "x")
            assertFalse("created_at=$createdAt", e.isValid())
        }
        // Dart checks the string length only, so a negative 9-digit value passes.
        assertTrue(EventIds.sign(kp, -123_456_789L, 1, emptyList(), "x").isValid())

        // uppercase id is rejected (string equality with the lowercase recomputation)
        assertFalse(EventIds.isValid(ok.id.uppercase(), ok.pubkey, ok.createdAt, ok.kind, ok.tags, ok.content, ok.sig))
        // uppercase pubkey is lower-cased before hashing → valid
        assertTrue(EventIds.isValid(ok.id, ok.pubkey.uppercase(), ok.createdAt, ok.kind, ok.tags, ok.content, ok.sig))
        // uppercase signature hex is fine for bip340
        assertTrue(EventIds.isValid(ok.id, ok.pubkey, ok.createdAt, ok.kind, ok.tags, ok.content, ok.sig.uppercase()))
    }

    // ---- crypto part of test/relay_event_signature_test.dart ----

    private val kc = KeyPair.generate()
    private val now = 1_760_000_000L

    private fun signedCalendarEvent(): SignedNostrEvent = EventIds.sign(
        kc,
        now,
        31923,
        listOf(listOf("d", "test-meetup"), listOf("title", "Echtes Meetup"), listOf("start", "${now + 86400}")),
        "",
    )

    private fun SignedNostrEvent.valid() = EventIds.isValid(id, pubkey, createdAt, kind, tags, content, sig)

    @Test
    fun relayEventSignatureChecks() {
        assertTrue(signedCalendarEvent().valid())
        assertFalse("unsigned", signedCalendarEvent().copy(sig = "").valid())
        val tampered = signedCalendarEvent().let { it.copy(tags = it.tags.toMutableList().also { t -> t[1] = listOf("title", "Gefälschtes Meetup") }) }
        assertFalse("tampered title", tampered.valid())
        assertFalse("foreign pubkey", signedCalendarEvent().copy(pubkey = KeyPair.generate().publicKeyHex).valid())
        assertFalse("forged id", signedCalendarEvent().copy(id = "ab".repeat(32)).valid())
        assertFalse("non-hex sig", signedCalendarEvent().copy(sig = "zz".repeat(64)).valid())
        assertFalse("non-hex pubkey", signedCalendarEvent().copy(pubkey = "xyz").valid())
        assertFalse("empty everything", EventIds.isValid("", "", 0, 0, emptyList(), "", ""))
    }

    @Test
    fun toJsonUsesDartKeyOrder() {
        val e = SignedNostrEvent("i", "p", 1_700_000_000, 1, listOf(listOf("t", "x")), "c/\"", "s")
        assertEquals(
            "{\"id\":\"i\",\"pubkey\":\"p\",\"created_at\":1700000000,\"kind\":1,\"tags\":[[\"t\",\"x\"]],\"content\":\"c/\\\"\",\"sig\":\"s\"}",
            e.toJson(),
        )
        assertEquals("[\"EVENT\"," + e.toJson() + "]", e.toEventMessage())
    }
}
