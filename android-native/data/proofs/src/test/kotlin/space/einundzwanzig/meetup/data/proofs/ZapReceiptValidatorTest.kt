package space.einundzwanzig.meetup.data.proofs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.Bech32
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.nostr.toJsonObject
import space.einundzwanzig.meetup.data.proofs.zap.ZapReceiptValidator

/** Port of `test/zap_receipt_validator_test.dart` (Audit H3) plus spec 06 §10.6 reason/bolt11 cases. */
class ZapReceiptValidatorTest {

    private val attacker = KeyPair.generate()
    private val victim = KeyPair.generate()
    private val provider = KeyPair.generate()

    // ---- Dart cases ----

    @Test
    fun `Korrekt aufgebaute Quittung besteht die lokale Pruefung`() {
        val r = receipt(provider, victim.publicKeyHex, zapRequest(attacker, victim.publicKeyHex))
        val check = ZapReceiptValidator.checkLocal(r)
        assertTrue(check.reason, check.ok)
        assertEquals(victim.publicKeyHex, check.recipientPubkey)
        assertEquals(attacker.publicKeyHex, check.senderPubkey)
        assertEquals(provider.publicKeyHex, check.receiptPubkey)
    }

    @Test
    fun `Angriff bolt11 gehoert nicht zum Zap-Request`() {
        val r = receipt(attacker, victim.publicKeyHex, zapRequest(attacker, victim.publicKeyHex), bolt11 = bolt11WithHash("0".repeat(64)))
        val check = ZapReceiptValidator.checkLocal(r)
        assertFalse(check.ok)
        assertEquals("description_hash", check.reason)
    }

    @Test
    fun `Angriff Quittung ohne bolt11 und description`() {
        val e = EventIds.sign(attacker, 1_760_000_000L, 9735, listOf(listOf("p", victim.publicKeyHex)), "").toJsonObject()
        assertEquals("tags", ZapReceiptValidator.checkLocal(e).reason)
    }

    @Test
    fun `Angriff Zap-Request im description-Tag ist nicht signiert`() {
        val fakeReq = DartJson.decode(zapRequest(attacker, victim.publicKeyHex)) as JsonObject
        val tampered = JsonObject(fakeReq + ("sig" to JsonPrimitive("ff".repeat(64))))
        val r = receipt(attacker, victim.publicKeyHex, DartJson.encode(tampered))
        assertEquals("request_sig", ZapReceiptValidator.checkLocal(r).reason)
    }

    @Test
    fun `Angriff Empfaenger der Quittung weicht vom Zap-Request ab`() {
        val r = receipt(attacker, victim.publicKeyHex, zapRequest(attacker, attacker.publicKeyHex))
        assertEquals("recipient_mismatch", ZapReceiptValidator.checkLocal(r).reason)
    }

    @Test
    fun `Angriff Quittung mit kaputter Signatur`() {
        val r = receipt(provider, victim.publicKeyHex, zapRequest(attacker, victim.publicKeyHex))
        val broken = JsonObject(r + ("sig" to JsonPrimitive("aa".repeat(64))))
        assertEquals("receipt_sig", ZapReceiptValidator.checkLocal(broken).reason)
    }

    @Test
    fun `descriptionHashOf liest das h-Feld aus einer bolt11`() {
        val h = "ab".repeat(32)
        assertEquals(h, ZapReceiptValidator.descriptionHashOf(bolt11WithHash(h)))
        assertNull(ZapReceiptValidator.descriptionHashOf("lnbc1kaputt"))
    }

    // ---- spec 06 §10.6 ----

    @Test
    fun `descriptionHashOf accepts an uppercase invoice and rejects mixed case`() {
        val h = "cd".repeat(32)
        val invoice = bolt11WithHash(h)
        assertEquals(h, ZapReceiptValidator.descriptionHashOf(invoice.uppercase()))
        val mixed = invoice.substring(0, 10) + invoice.substring(10).uppercase()
        assertNull(ZapReceiptValidator.descriptionHashOf(mixed))
    }

    @Test
    fun `descriptionHashOf rejects a non-ln hrp`() {
        assertNull(ZapReceiptValidator.descriptionHashOf(bolt11WithHash("ab".repeat(32), hrp = "bc")))
    }

    @Test
    fun `descriptionHashOf skips an h field with another length`() {
        // h field with len 1 (one word) before the real one.
        val invoice = bolt11WithHash("ef".repeat(32), prefixFields = listOf(23, 0, 1, 7))
        assertEquals("ef".repeat(32), ZapReceiptValidator.descriptionHashOf(invoice))
    }

    @Test
    fun `descriptionHashOf rejects data shorter than timestamp plus signature`() {
        val invoice = Bech32.encode("lnbc1", IntArray(7 + 103) { 0 }, 4096)
        assertNull(ZapReceiptValidator.descriptionHashOf(invoice))
    }

    @Test
    fun `descriptionHashOf trims whitespace`() {
        val h = "01".repeat(32)
        assertEquals(h, ZapReceiptValidator.descriptionHashOf("  " + bolt11WithHash(h) + "\n"))
    }

    @Test
    fun `kind as a string fails with kind`() {
        val r = receipt(provider, victim.publicKeyHex, zapRequest(attacker, victim.publicKeyHex))
        val withStringKind = JsonObject(r + ("kind" to JsonPrimitive("9735")))
        assertEquals("kind", ZapReceiptValidator.checkLocal(withStringKind).reason)
    }

    @Test
    fun `description that is not json fails with description_json`() {
        val r = receipt(provider, victim.publicKeyHex, "not json")
        assertEquals("description_json", ZapReceiptValidator.checkLocal(r).reason)
    }

    @Test
    fun `description with kind 1 fails with request_kind`() {
        val note = DartJson.encode(EventIds.sign(attacker, 1_760_000_000L, 1, listOf(listOf("p", victim.publicKeyHex)), "").toJsonObject())
        val r = receipt(provider, victim.publicKeyHex, note)
        assertEquals("request_kind", ZapReceiptValidator.checkLocal(r).reason)
    }

    @Test
    fun `description that is a json array fails with description_json`() {
        val r = receipt(provider, victim.publicKeyHex, "[1,2]")
        assertEquals("description_json", ZapReceiptValidator.checkLocal(r).reason)
    }

    @Test
    fun `invoice without h field fails with bolt11`() {
        val noH = Bech32.encode("lnbc210n", IntArray(7 + 104) { 0 }, 4096)
        val r = receipt(provider, victim.publicKeyHex, zapRequest(attacker, victim.publicKeyHex), bolt11 = noH)
        assertEquals("bolt11", ZapReceiptValidator.checkLocal(r).reason)
    }

    @Test
    fun `first p tag wins and the sender comes from the signed request`() {
        val description = zapRequest(attacker, victim.publicKeyHex)
        val r = EventIds.sign(
            provider,
            1_760_000_000L,
            9735,
            listOf(
                listOf("p", victim.publicKeyHex),
                listOf("p", attacker.publicKeyHex),
                listOf("P", provider.publicKeyHex), // unsigned P tag is ignored
                listOf("bolt11", bolt11WithHash(space.einundzwanzig.meetup.core.crypto.Sha256.hexOfUtf8(description))),
                listOf("description", description),
            ),
            "",
        ).toJsonObject()
        val check = ZapReceiptValidator.checkLocal(r)
        assertTrue(check.reason, check.ok)
        assertEquals(victim.publicKeyHex, check.recipientPubkey)
        assertEquals(attacker.publicKeyHex, check.senderPubkey)
    }
}
