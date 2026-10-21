package space.einundzwanzig.meetup.data.identity.signer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Keys
import space.einundzwanzig.meetup.data.identity.FakeNip55
import space.einundzwanzig.meetup.data.identity.FakeTime
import space.einundzwanzig.meetup.data.identity.SigningCancelledException
import space.einundzwanzig.meetup.data.identity.SigningException
import space.einundzwanzig.meetup.data.identity.WrongAccountException
import space.einundzwanzig.meetup.data.identity.nip46.Nip46Client
import space.einundzwanzig.meetup.data.identity.nip46.Nip46RemoteException
import space.einundzwanzig.meetup.data.identity.nip46.Nip46TimeoutException
import space.einundzwanzig.meetup.data.identity.nip55.Nip55SignResult
import kotlin.time.Duration.Companion.seconds

/**
 * Ports of `nip07_signer_test.dart`, `stage_a_security_test.dart` (N1) and the signer groups of
 * `nip46_signing_service_test.dart`: one shared verification for every external signer.
 */
class SignerResponseVerifierTest {

    private val mine = "fa5e3477d2d6b92d667dcab66c8bbb1527014599c5eecddd545e3a39d7870268"
    private val someoneElse = "0000000000000000000000000000000000000000000000000000000000000001"
    private val placeholderSig = "ab".repeat(64)
    private val eventId = "cd".repeat(32)

    private fun reply(
        pubkey: String = mine,
        kind: Int = 1,
        content: String = "hallo",
        tags: Any? = listOf(listOf("t", "test")),
        sig: String = placeholderSig,
    ): JsonObject = jsonObjectOf(
        "id" to eventId,
        "pubkey" to pubkey,
        "created_at" to 1786000000,
        "kind" to kind,
        "tags" to tags,
        "content" to content,
        "sig" to sig,
    )

    private fun signedReply(kc: KeyPair, kind: Int = 1, content: String = "hallo", tags: List<List<String>> = listOf(listOf("t", "test"))): JsonObject {
        val ev = EventIds.sign(kc, 1786000000, kind, tags, content)
        return jsonObjectOf(
            "id" to ev.id,
            "pubkey" to ev.pubkey,
            "created_at" to ev.createdAt,
            "kind" to ev.kind,
            "tags" to ev.tags,
            "content" to ev.content,
            "sig" to ev.sig,
        )
    }

    private fun verify(
        signed: JsonObject,
        expected: String = mine,
        kind: Int = 1,
        tags: List<List<String>> = listOf(listOf("t", "test")),
        content: String = "hallo",
        actor: SignerActor = SignerActor.EXTENSION,
    ) = verifySignerResponse(signed, expected, kind, tags, content, 1786000000, actor)

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    // ---- nip07_signer_test: good cases ----

    @Test
    fun takesIdSigAndCreatedAtFromTheSigner() {
        val kc = KeyPair.generate()
        val raw = signedReply(kc)
        val signed = verify(raw, expected = kc.publicKeyHex)
        assertEquals(kc.publicKeyHex, signed.pubkey)
        assertEquals(raw["id"].toString().trim('"'), signed.id)
        assertEquals(raw["sig"].toString().trim('"'), signed.sig)
        assertEquals(1786000000L, signed.createdAt)
    }

    @Test
    fun takesNormalizedTagsFromTheSigner() {
        val kc = KeyPair.generate()
        val tags = listOf(listOf("t", "test"), listOf("client", "alby"))
        val signed = verify(signedReply(kc, tags = tags), expected = kc.publicKeyHex)
        assertEquals(tags, signed.tags)
    }

    @Test
    fun brokenTagsFormatFallsBackAndPlaceholderIsRejected() {
        expect<SigningException> { verify(reply(tags = listOf("kein-array"))) }
    }

    @Test
    fun placeholderSignatureIsRejected() {
        expect<SigningException> { verify(reply()) }
    }

    // ---- security checks ----

    @Test
    fun otherAccountIsWrongAccount() {
        expect<WrongAccountException> { verify(reply(pubkey = someoneElse), tags = emptyList()) }
    }

    @Test
    fun changedKindIsRejected() {
        val e = expect<SigningException> { verify(reply(kind = 4), tags = emptyList()) }
        assertEquals("Die Erweiterung hat einen anderen Event-Typ signiert (4 statt 1).", e.message)
    }

    @Test
    fun changedContentIsRejected() {
        expect<SigningException> { verify(reply(content = "etwas ganz anderes"), tags = emptyList()) }
    }

    @Test
    fun missingSignatureIsRejected() {
        val e = expect<SigningException> { verify(reply(sig = ""), tags = emptyList()) }
        assertEquals("Die Erweiterung hat kein signiertes Event geliefert.", e.message)
    }

    @Test
    fun createdAtMustHaveTenDigits() {
        val kc = KeyPair.generate()
        val ev = EventIds.sign(kc, 999_999_999, 1, emptyList(), "hallo")
        val raw = jsonObjectOf(
            "id" to ev.id, "pubkey" to ev.pubkey, "created_at" to ev.createdAt, "kind" to 1,
            "tags" to emptyList<Any>(), "content" to "hallo", "sig" to ev.sig,
        )
        expect<SigningException> { verify(raw, expected = kc.publicKeyHex, tags = emptyList()) }
    }

    // ---- stage_a_security_test N1: Amber answer form {'event': '<json>'} ----

    private fun amberCheck(raw: JsonObject) = verify(
        DartJson.decode(DartJson.encode(raw)) as JsonObject,
        actor = SignerActor.AMBER,
    )

    @Test
    fun amberGoodCaseIsAccepted() {
        val kc = KeyPair.generate()
        val ev = verify(signedReply(kc), expected = kc.publicKeyHex, actor = SignerActor.AMBER)
        assertEquals(kc.publicKeyHex, ev.pubkey)
        assertEquals(1, ev.kind)
        assertEquals("hallo", ev.content)
    }

    @Test
    fun amberPlaceholderAccountKindContentAndSigChecks() {
        expect<SigningException> { amberCheck(reply()) }
        expect<WrongAccountException> { amberCheck(reply(pubkey = someoneElse)) }
        expect<SigningException> { amberCheck(reply(kind = 4)) }
        val e = expect<SigningException> { amberCheck(reply(content = "etwas ganz anderes")) }
        assertEquals("Amber hat den Inhalt des Events verändert.", e.message)
        expect<SigningException> { amberCheck(reply(sig = "")) }
    }

    @Test
    fun amberSignerRunsTheSameChecksOnTheTransportAnswer() = runBlocking {
        val kc = KeyPair.generate()
        var seenJson = ""
        var seenNpub = ""
        val transport = FakeNip55(installed = true)
        val signer = AmberNostrSigner(kc.publicKeyHex, transport, FakeTime(1_786_000_000_000L))
        transport.sign = { json, npub ->
            seenJson = json
            seenNpub = npub
            val ev = EventIds.sign(kc, 1786000000, 1, listOf(listOf("t", "test")), "hallo")
            Nip55SignResult(ev.toJson(), ev.sig)
        }
        val ev = signer.signEvent(1, listOf(listOf("t", "test")), "hallo")
        assertEquals(kc.publicKeyHex, ev.pubkey)
        assertEquals(
            """{"pubkey":"${kc.publicKeyHex}","created_at":1786000000,"kind":1,"tags":[["t","test"]],"content":"hallo"}""",
            seenJson,
        )
        assertEquals(kc.npub, seenNpub)

        transport.sign = { _, _ -> throw space.einundzwanzig.meetup.data.identity.nip55.Nip55Exception("rejected") }
        expect<SigningCancelledException> { runBlocking { signer.signEvent(1, emptyList(), "x") } }
        transport.sign = { _, _ -> Nip55SignResult("kein json", null) }
        val e = expect<SigningException> { runBlocking { signer.signEvent(1, emptyList(), "x") } }
        assertTrue(e.message, e.message.startsWith("Amber-Antwort nicht lesbar"))
    }

    // ---- nip46_signing_service_test: signer groups ----

    private val mine46 = "fa5e347dd2d6b92d667dcab6cc8bbb1524e0a2ccc5eecddd551787473c3814d3"

    private fun nip46Signer(expected: String = mine46, sign: suspend (JsonObject) -> JsonObject) = Nip46NostrSigner(
        expectedPubkeyHex = expected,
        clientProvider = { throw IllegalStateException("darf im Test nicht gerufen werden") },
        timeSource = FakeTime(),
        signFn = sign,
    )

    private fun reply46(pubkey: String = mine46, kind: Int = 21000, content: String = "Badge", sig: String = placeholderSig) =
        reply(pubkey = pubkey, kind = kind, content = content, tags = listOf(listOf("d", "badge")), sig = sig)

    @Test
    fun nip46GoodCaseTakesIdSigCreatedAtAndNormalizedTags() = runBlocking {
        val kc = KeyPair.generate()
        val tags = listOf(listOf("d", "badge"), listOf("client", "nsec.app"))
        val ev = EventIds.sign(kc, 1786000000, 21000, tags, "Badge")
        val signed = nip46Signer(kc.publicKeyHex) {
            jsonObjectOf(
                "id" to ev.id, "pubkey" to ev.pubkey, "created_at" to ev.createdAt, "kind" to ev.kind,
                "tags" to ev.tags, "content" to ev.content, "sig" to ev.sig,
            )
        }.signEvent(21000, listOf(listOf("d", "badge")), "Badge")
        assertEquals(ev.id, signed.id)
        assertEquals(ev.sig, signed.sig)
        assertEquals(1786000000L, signed.createdAt)
        assertEquals(tags, signed.tags)
        assertEquals(21000, signed.kind)
        assertEquals("Badge", signed.content)
    }

    @Test
    fun nip46SecurityChecks() {
        expect<SigningException> { runBlocking { nip46Signer { reply46() }.signEvent(21000, listOf(listOf("d", "badge")), "Badge") } }
        expect<WrongAccountException> {
            runBlocking { nip46Signer { reply46(pubkey = "aaaa347dd2d6b92d667dcab6cc8bbb1524e0a2ccc5eecddd551787473c3814d3") }.signEvent(21000, emptyList(), "Badge") }
        }
        expect<SigningException> { runBlocking { nip46Signer { reply46(sig = "") }.signEvent(21000, emptyList(), "Badge") } }
        expect<SigningException> { runBlocking { nip46Signer { reply46(kind = 1) }.signEvent(21000, emptyList(), "Badge") } }
        expect<SigningException> { runBlocking { nip46Signer { reply46(content = "etwas anderes") }.signEvent(21000, emptyList(), "Badge") } }
    }

    @Test
    fun nip46ErrorTextNamesTheSignerNotTheExtension() {
        val e = expect<SigningException> {
            runBlocking { nip46Signer { reply46(content = "anders") }.signEvent(21000, emptyList(), "Badge") }
        }
        assertTrue(e.message.contains("Der Signer"))
        assertFalse(e.message.contains("Erweiterung"))
    }

    @Test
    fun nip46TimeoutBecomesAHintAtTheSignerApp() {
        val e = expect<SigningException> {
            runBlocking { nip46Signer { throw Nip46TimeoutException("sign_event", 60.seconds, 0) }.signEvent(21000, emptyList(), "x") }
        }
        assertTrue(e.message.contains("Signer-App"))
    }

    @Test
    fun nip46RejectionIsPassedThrough() {
        val e = expect<SigningException> {
            runBlocking { nip46Signer { throw Nip46RemoteException("user rejected") }.signEvent(21000, emptyList(), "x") }
        }
        assertTrue(e.message.contains("user rejected"))
    }

    @Test
    fun nip46SignerSendsThePubkeyInTheUnsignedEvent() = runBlocking {
        var seen: JsonObject? = null
        runCatching {
            nip46Signer { seen = it; reply46() }.signEvent(21000, listOf(listOf("d", "badge")), "Badge")
        }
        assertEquals(
            """{"pubkey":"$mine46","created_at":1786000000,"kind":21000,"tags":[["d","badge"]],"content":"Badge"}""",
            DartJson.encode(seen!!),
        )
        assertTrue(Keys.isValidPubHex(KeyPair.generate().publicKeyHex))
        assertTrue(Nip46Client.SIGNED_KINDS.contains(21000))
    }
}
