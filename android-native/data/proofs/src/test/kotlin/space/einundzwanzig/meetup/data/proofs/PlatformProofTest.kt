package space.einundzwanzig.meetup.data.proofs

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.data.proofs.internal.DefaultPlatformProofRepository
import space.einundzwanzig.meetup.data.proofs.platform.ParsedVerifyString
import space.einundzwanzig.meetup.data.proofs.platform.PlatformCatalog
import space.einundzwanzig.meetup.data.proofs.platform.PlatformIcon
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofBackup
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofVerification
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateError
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateResult
import space.einundzwanzig.meetup.data.proofs.platform.ReputationFacts
import space.einundzwanzig.meetup.data.proofs.platform.VerifyLevel
import space.einundzwanzig.meetup.data.proofs.platform.VerifyMessage
import space.einundzwanzig.meetup.data.proofs.platform.VerifyStringCodec

/** Spec 06 §10.4 (backup union) and §10.6 (verify string, custom id, storage, verification). */
class PlatformProofTest {

    private val s = "a".repeat(128)

    // ---- verify string ----

    @Test
    fun `parse vectors`() {
        assertEquals(ParsedVerifyString("npub1x", "telegram", "alice", s), VerifyStringCodec.parse("21rep::npub1x::telegram::alice::sig=$s"))
        assertEquals(ParsedVerifyString("npub1x", "t", "u", s), VerifyStringCodec.parse("  21rep::npub1x::t::u::sig=$s  "))
        assertEquals(ParsedVerifyString("npub1x", "t", "u", s), VerifyStringCodec.parse("21rep::npub1x::t::u::sig=$s::extra"))
        assertNull(VerifyStringCodec.parse("21rep::npub1x::t::u"))
        assertNull(VerifyStringCodec.parse("21rep::nsec1x::t::u::sig=$s"))
        assertNull(VerifyStringCodec.parse("21rep::npub1x::t::u::$s"))
        assertNull(VerifyStringCodec.parse("21rep::npub1x::t::u::sig=${"a".repeat(127)}"))
        assertNull(VerifyStringCodec.parse("21rep::npub1x::t::a::b::sig=$s"))
        assertNull(VerifyStringCodec.parse("npub1abc"))
    }

    @Test
    fun `format check is not cryptographic`() {
        val npub = KeyPair.generate().npub
        assertTrue(VerifyStringCodec.isWellFormed(ParsedVerifyString(npub, "t", "u", "f".repeat(128))))
        assertTrue(VerifyStringCodec.isWellFormed(ParsedVerifyString(npub, "t", "u", "-" + "f".repeat(127))))
        assertFalse(VerifyStringCodec.isWellFormed(ParsedVerifyString(npub, "t", "u", "g".repeat(128))))
        assertFalse(VerifyStringCodec.isWellFormed(ParsedVerifyString("npub1x", "t", "u", s)))
        // Dart BigInt.parse(radix: 16) accepts surrounding \s (ECMAScript set), not U+0085 or 0x.
        assertTrue(VerifyStringCodec.isWellFormed(ParsedVerifyString(npub, "t", "u", " " + "f".repeat(127))))
        assertTrue(VerifyStringCodec.isWellFormed(ParsedVerifyString(npub, "t", "u", "\u00A0+" + "f".repeat(125) + "\t")))
        assertTrue(VerifyStringCodec.isWellFormed(ParsedVerifyString(npub, "t", "u", "\uFEFF" + "f".repeat(127))))
        assertFalse(VerifyStringCodec.isWellFormed(ParsedVerifyString(npub, "t", "u", "\u0085" + "f".repeat(127))))
        assertFalse(VerifyStringCodec.isWellFormed(ParsedVerifyString(npub, "t", "u", "0x" + "f".repeat(126))))
        assertFalse(VerifyStringCodec.isWellFormed(ParsedVerifyString(npub, "t", "u", "f".repeat(63) + " " + "f".repeat(64))))
        assertFalse(VerifyStringCodec.isWellFormed(ParsedVerifyString(npub, "t", "u", " ".repeat(128))))
        val padded = VerifyStringCodec.parse("21rep::$npub::t::u::sig= ${"f".repeat(127)}")!!
        assertTrue(VerifyStringCodec.isWellFormed(padded))
        assertEquals("21rep::$npub::telegram::bob::sig=$s", VerifyStringCodec.build(npub, "telegram", "bob", s))
    }

    @Test
    fun `custom platform ids`() {
        assertEquals("my_market", PlatformCatalog.customPlatformId("My Market"))
        assertEquals("bisq__p2p", PlatformCatalog.customPlatformId("Bisq  P2P"))
        assertEquals("ärger", PlatformCatalog.customPlatformId("ÄRGER"))
    }

    @Test
    fun `catalogue order and fallbacks`() {
        assertEquals(listOf("satoshikleinanzeigen", "telegram", "robosats", "nostr", "other"), PlatformCatalog.platforms.map { it.id })
        assertEquals("my_market", PlatformCatalog.displayName("my_market"))
        assertEquals(PlatformIcon.LANGUAGE, PlatformCatalog.iconFor("my_market"))
        assertEquals(PlatformIcon.SEND, PlatformCatalog.iconFor("telegram"))
        assertEquals(PlatformIcon.LINK, PlatformIcon.of("whatever"))
    }

    // ---- verification seam ----

    private data class Rep(val bound: Int, val total: Int, val proofs: Map<String, JsonElement> = emptyMap())

    private suspend fun verify(input: String, rep: Rep?, expected: String? = null) =
        PlatformProofVerification.verify(input, expected, { rep }) { ReputationFacts(it.bound, it.total, it.proofs) }

    @Test
    fun `verification decision table`() = runTest {
        val npub = KeyPair.generate().npub
        val str = "21rep::$npub::telegram::Alice::sig=${"b".repeat(128)}"
        val proof = mapOf<String, JsonElement>("telegram" to jsonObjectOf("username" to "Alice"))

        verify(str, Rep(3, 3, proof)).let {
            assertEquals(VerifyLevel.FULL, it.level)
            assertTrue(it.proofInEvent)
            assertEquals("Identität und Reputation vollständig bestätigt ✓", it.message.german)
        }
        verify(str, Rep(2, 3, proof)).let {
            assertEquals(VerifyLevel.PARTIAL, it.level)
            assertEquals("Reputation teilweise bestätigt — 2 von 3 Badges gebunden.", it.message.german)
        }
        // Username compared case-sensitively in the event.
        verify(str, Rep(3, 3, mapOf("telegram" to jsonObjectOf("username" to "alice")))).let {
            assertEquals(VerifyLevel.PARTIAL, it.level)
            assertEquals(VerifyMessage.PartialNoProof, it.message)
            assertFalse(it.proofInEvent)
        }
        verify(str, Rep(0, 3, proof)).let {
            assertEquals(VerifyLevel.SIGNATURE_ONLY, it.level)
            assertEquals(VerifyMessage.SignatureOnlyNoBindings, it.message)
        }
        verify(str, null).let {
            assertEquals(VerifyLevel.SIGNATURE_ONLY, it.level)
            assertEquals(VerifyMessage.NoReputation, it.message)
            assertEquals("Alice", it.claimedUsername)
        }
        verify(str, Rep(3, 3, proof), expected = "ALICE").let { assertEquals(VerifyLevel.FULL, it.level) }
        verify(str, Rep(3, 3, proof), expected = "bob").let {
            assertEquals(VerifyLevel.INVALID, it.level)
            assertEquals(
                "WARNUNG: Username stimmt nicht überein! String sagt \"Alice\", Account ist \"bob\".",
                it.message.german,
            )
        }
    }

    @Test
    fun `verification of bad strings and npubs`() = runTest {
        assertEquals(VerifyMessage.BadFormat, verify("hello", Rep(1, 1)).message)
        verify("21rep::npub1x::telegram::alice::sig=$s", Rep(1, 1)).let {
            assertEquals(VerifyLevel.INVALID, it.level)
            assertEquals(VerifyMessage.BadSignature, it.message)
        }
        val npub = KeyPair.generate().npub
        verify(" $npub ", null).let {
            assertEquals(VerifyLevel.SIGNATURE_ONLY, it.level)
            assertEquals(VerifyMessage.NpubNoReputation, it.message)
            assertEquals(npub, it.npub)
        }
        assertEquals(VerifyMessage.NpubWithBindings, verify(npub, Rep(1, 2)).message)
        assertEquals(VerifyLevel.SIGNATURE_ONLY, verify(npub, Rep(0, 2)).level)
        val failing = PlatformProofVerification.verify(npub, null, { error("boom") }) { _: Rep -> ReputationFacts(0, 0, emptyMap()) }
        assertEquals(VerifyLevel.INVALID, failing.level)
        assertEquals("Verifikations-Fehler: boom", failing.message.german)
    }

    // ---- repository ----

    private val store = InMemoryKeyValueStore()
    private val identity = FakeIdentity()
    private val time = FakeTime(1_760_000_000_000L)
    private val repo = DefaultPlatformProofRepository(store, identity, time, log, testDispatcher)

    private suspend fun storedJson(): JsonArray = DartJson.decode(store.getString("platform_proofs")!!) as JsonArray

    @Test
    fun `create signs kind 21003 with canonical content and stores the proof`() = runTest {
        val result = repo.createProof("telegram", "  alice  ")
        assertTrue(result is ProofCreateResult.Success)
        result as ProofCreateResult.Success
        val ev = identity.signed.single()
        assertEquals(21003, ev.kind)
        assertEquals(listOf(listOf("t", "platform_proof"), listOf("platform", "telegram"), listOf("username", "alice")), ev.tags)
        assertEquals(
            """{"action":"platform_proof","created_at":1760000000,"platform":"telegram","protocol":"21rep","username":"alice","version":2}""",
            ev.content,
        )
        assertEquals(PlatformProof("telegram", "alice", ev.sig, 1_760_000_000L), result.proof)
        assertEquals("21rep::${identity.keyPair!!.npub}::telegram::alice::sig=${ev.sig}", result.verifyString)
        assertEquals(
            """[{"platform":"telegram","username":"alice","proof_sig":"${ev.sig}","created_at":1760000000}]""",
            store.getString("platform_proofs"),
        )
    }

    @Test
    fun `custom platform id is lowercased with underscores`() = runTest {
        val r = repo.createProof("other", "bob", customPlatformName = "My Market") as ProofCreateResult.Success
        assertEquals("my_market", r.proof.platform)
        assertEquals(listOf("platform", "my_market"), identity.signed.single().tags[1])
    }

    @Test
    fun `create twice keeps one entry, newest at the end, remove deletes it`() = runTest {
        repo.createProof("telegram", "alice")
        repo.createProof("nostr", "bob")
        time.millis += 5_000
        repo.createProof("telegram", "alice2")
        assertEquals(listOf("nostr", "telegram"), repo.getSavedProofs().map { it.platform })
        assertEquals("alice2", repo.getSavedProofs().last().username)
        assertEquals(listOf("nostr", "telegram"), repo.getProofsForPublishing().keys.toList())
        repo.removeProof("telegram")
        assertEquals(listOf("nostr"), repo.getSavedProofs().map { it.platform })
    }

    @Test
    fun `create failures`() = runTest {
        assertEquals(ProofCreateResult.Failure(ProofCreateError.EMPTY_USERNAME), repo.createProof("telegram", "   "))
        identity.canSign = false
        assertEquals(ProofCreateResult.Failure(ProofCreateError.NO_KEY), repo.createProof("telegram", "a"))
        assertEquals("Kein Schlüssel vorhanden", repo.createProof("telegram", "a").message)
        identity.canSign = true
        identity.signError = IllegalStateException("Permission denied for sign_event kind:21003")
        val f = repo.createProof("telegram", "a")
        assertEquals(ProofCreateError.SIGNER_OR_UNKNOWN, (f as ProofCreateResult.Failure).error)
        assertEquals("Fehler: Permission denied for sign_event kind:21003", f.message)
        assertNull(store.getString("platform_proofs"))
    }

    @Test
    fun `malformed stored json gives an empty list`() = runTest {
        store.setString("platform_proofs", "{not json")
        assertEquals(emptyList<PlatformProof>(), repo.getSavedProofs())
    }

    @Test
    fun `per-entry parse keeps good entries and preserves the rest on write`() = runTest {
        store.setString(
            "platform_proofs",
            """[{"platform":"a"},{"platform":"telegram","username":"u","proof_sig":"$s","created_at":5,"extra":1},{"platform":"x","username":"v","proof_sig":"$s"}]""",
        )
        assertEquals(
            listOf(PlatformProof("telegram", "u", s, 5), PlatformProof("x", "v", s, 0)),
            repo.getSavedProofs(),
        )
        repo.createProof("nostr", "n")
        val stored = storedJson()
        assertEquals(4, stored.size)
        assertEquals("""{"platform":"a"}""", DartJson.encode(stored[0]))
        assertEquals("1", (stored[1] as JsonObject)["extra"]!!.jsonPrimitive.content)
    }

    // ---- backup ----

    @Test
    fun `Plattform-Proofs das Geraet behaelt denselben Namen`() {
        val merged = PlatformProofBackup.union(
            device = listOf(jsonObjectOf("platform" to "telegram", "username" to "Ada", "proof_sig" to "device")),
            incoming = listOf(
                jsonObjectOf("platform" to "telegram", "username" to "ada", "proof_sig" to "backup"),
                jsonObjectOf("platform" to "x", "username" to "ada", "proof_sig" to "extra"),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals("device", merged.first { it["platform"]!!.jsonPrimitive.content == "telegram" }["proof_sig"]!!.jsonPrimitive.content)
    }

    @Test
    fun `restorable keeps only 128-hex signatures and union is case-insensitive`() = runTest {
        val good = jsonObjectOf("platform" to "Telegram", "username" to "a", "proof_sig" to "AbC0".repeat(32), "x" to 1)
        val entries = listOf(
            good,
            jsonObjectOf("platform" to "b", "proof_sig" to ""),
            jsonObjectOf("platform" to "c", "proof_sig" to "g".repeat(128)),
            jsonObjectOf("platform" to "d", "proof_sig" to "a".repeat(127)),
            jsonObjectOf("platform" to "e", "proof_sig" to 5),
        )
        assertEquals(listOf<JsonElement>(good), PlatformProofBackup.restorable(entries))

        repo.createProof("telegram", "device")
        assertTrue(repo.unionRaw(PlatformProofBackup.restorable(entries)))
        assertEquals(listOf("device"), repo.getSavedProofs().map { it.username })

        assertTrue(repo.replaceAllRaw(emptyList()))
        assertEquals(1, repo.getSavedProofs().size)
        assertTrue(repo.replaceAllRaw(listOf(good)))
        assertEquals(listOf("Telegram"), repo.getSavedProofs().map { it.platform })
        assertTrue(repo.clear())
        assertNull(store.getString("platform_proofs"))
    }

    @Test
    fun `union keeps an unreadable device value and returns false`() = runTest {
        val good = jsonObjectOf("platform" to "telegram", "username" to "a", "proof_sig" to "ab".repeat(64), "created_at" to 1)
        store.setString("platform_proofs", "not json")
        assertFalse(repo.unionRaw(listOf(good)))
        assertEquals("not json", store.getString("platform_proofs"))
        store.setString("platform_proofs", "{\"a\":1}")
        assertFalse(repo.unionRaw(listOf(good)))
        assertEquals("{\"a\":1}", store.getString("platform_proofs"))
        store.setString("platform_proofs", "")
        assertTrue(repo.unionRaw(listOf(good)))
        assertEquals(listOf("telegram"), repo.getSavedProofs().map { it.platform })
    }

    @Test
    fun `remove reports success`() = runTest {
        repo.createProof("telegram", "alice")
        assertTrue(repo.removeProof("telegram"))
        assertEquals(emptyList<PlatformProof>(), repo.getSavedProofs())
    }

    @Test
    fun `wire shapes`() {
        val p = PlatformProof("telegram", "u", s, 7)
        assertEquals("""{"username":"u","proof_sig":"$s","created_at":7}""", DartJson.encode(p.toReputationJson()))
        assertEquals("""{"u":"u","s":"$s"}""", DartJson.encode(p.toQrJson()))
        assertEquals(PlatformProof("x", "", "", 0), PlatformProof.fromReputationJson("x", jsonObjectOf("username" to 5)))
    }
}
