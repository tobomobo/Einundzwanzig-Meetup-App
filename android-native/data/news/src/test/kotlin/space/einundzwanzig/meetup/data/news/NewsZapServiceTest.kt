package space.einundzwanzig.meetup.data.news

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartUri
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.data.identity.SigningCancelledException
import space.einundzwanzig.meetup.data.news.internal.DefaultNewsZapService
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup
import java.net.InetAddress
import java.net.URLDecoder
import kotlin.time.Duration.Companion.seconds

/** NIP-57 zap invoice over LNURL-pay (§5 N2/N3, §6.8, §8.10, §10.4). */
class NewsZapServiceTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val signer = FakeSigner()
    private val lookup = FakeLookup(address = "markus@einundzwanzig.space")
    private val author = pubOf(SECRET5)
    private val addr = "30023:$author:artikel"
    private lateinit var service: DefaultNewsZapService

    @Before
    fun setUp() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        service = DefaultNewsZapService(
            lookup, signer.service, client, testLog(),
            metadataUrl = { user, domain -> "http://127.0.0.1:${server.port}/$domain/.well-known/lnurlp/$user" },
            timeout = 5.seconds,
        )
    }

    @After
    fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()

    private fun cb(path: String = "/cb") = "http://127.0.0.1:${server.port}$path"

    private fun meta(extra: String = "", callback: String = cb()) =
        """{"tag":"payRequest","callback":"$callback","minSendable":1000,"maxSendable":100000000$extra}"""

    private fun zap(sats: Long = 2100, comment: String? = "Die Ausnahme & die Uhr") =
        runBlocking { service.createZapInvoice(author, addr, sats, comment) }

    @Test
    fun `zap request tags for 2100 sat`() {
        assertEquals(
            listOf(
                listOf("relays", "wss://nostr.einundzwanzig.space", "wss://relay.damus.io", "wss://nos.lol"),
                listOf("amount", "2100000"),
                listOf("p", author),
                listOf("a", addr),
            ),
            NewsZapService.zapRequestTags(author, addr, 2_100_000),
        )
        assertEquals(listOf(21L, 210L, 2100L, 21000L), NewsZapService.ZAP_AMOUNTS_SATS)
    }

    @Test
    fun `with allowsNostr the signed zap request goes as nostr param`() {
        server.enqueue(json(meta(""","allowsNostr":true,"commentAllowed":100""")))
        server.enqueue(json("""{"pr":"lnbc21u1zap","routes":[]}"""))

        val result = zap()

        assertEquals(ZapResult.Success("lnbc21u1zap"), result)
        assertTrue(result.ok)
        assertEquals("lnbc21u1zap", result.invoice)
        assertEquals("/einundzwanzig.space/.well-known/lnurlp/markus", server.takeRequest().url.encodedPath)
        val target = server.takeRequest().target
        val ev = signer.signedEvents.single()
        val zapJson = ev.toJson()
        // Byte-exact: amount first, then the form-encoded zap request, no comment.
        assertEquals("/cb?amount=2100000&nostr=${DartUri.encodeQueryComponent(zapJson)}", target)
        assertEquals(9734, ev.kind)
        assertEquals("Die Ausnahme & die Uhr", ev.content)
        assertEquals(NewsZapService.zapRequestTags(author, addr, 2_100_000), ev.tags)

        // The JSON is the NIP-01 object in Dart key order and verifies.
        val decoded = DartJson.decode(URLDecoder.decode(target.substringAfter("nostr="), "UTF-8")) as JsonObject
        assertEquals(listOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig"), decoded.keys.toList())
        assertTrue(EventGuard.verifiedEvent(decoded) != null)
        assertEquals("relays", decoded["tags"]!!.jsonArray[0].jsonArray[0].jsonPrimitive.content)
        assertEquals(listOf(author), lookup.lookups)
    }

    @Test
    fun `without allowsNostr the comment is cut to commentAllowed`() {
        server.enqueue(json(meta(""","commentAllowed":5""", callback = cb("/cb?id=1"))))
        server.enqueue(json("""{"pr":"lnbc1"}"""))
        assertEquals(ZapResult.Success("lnbc1"), zap(21, "V4V – 21Meetup"))
        server.takeRequest()
        assertEquals("/cb?id=1&amount=21000&comment=V4V+%E2%80%93", server.takeRequest().target)
        assertEquals(0, signer.signCount)
    }

    @Test
    fun `without allowsNostr and commentAllowed 0 no comment is sent`() {
        server.enqueue(json(meta(""","allowsNostr":"true"""")))
        server.enqueue(json("""{"pr":"lnbc1"}"""))
        assertTrue(zap(21).ok)
        server.takeRequest()
        assertEquals("/cb?amount=21000", server.takeRequest().target)
        assertEquals(0, signer.signCount) // "true" as a string is not `== true`
    }

    @Test
    fun `address errors`() {
        lookup.address = null
        assertEquals(ZapResult.Failure(ZapError.NoLightningAddress), zap())
        lookup.address = LightningAddressLookup.LUD06_MARKER
        assertEquals(ZapResult.Failure(ZapError.UnsupportedAddress), zap())
        lookup.address = "a@b@c"
        assertEquals(ZapResult.Failure(ZapError.UnsupportedAddress), zap())
        lookup.address = "@domain"
        assertEquals(ZapResult.Failure(ZapError.UnsupportedAddress), zap())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `non-positive amount fails before any lookup`() {
        assertEquals(ZapResult.Failure(ZapError.AmountOutOfRange), zap(0))
        assertTrue(lookup.lookups.isEmpty())
    }

    @Test
    fun `metadata errors map to lnurlUnreachable`() {
        server.enqueue(json(meta(), code = 404))
        assertEquals(ZapResult.Failure(ZapError.LnurlUnreachable), zap())
        server.enqueue(json("not json"))
        assertEquals(ZapResult.Failure(ZapError.LnurlUnreachable), zap())
        server.enqueue(json("""{"tag":"withdrawRequest","callback":"${cb()}"}"""))
        assertEquals(ZapResult.Failure(ZapError.LnurlUnreachable), zap())
        server.enqueue(json("""{"tag":"payRequest"}"""))
        assertEquals(ZapResult.Failure(ZapError.LnurlUnreachable), zap())
        server.enqueue(json("""{"tag":"payRequest","callback":"${cb()}","minSendable":"1000"}"""))
        assertEquals(ZapResult.Failure(ZapError.LnurlUnreachable), zap())
        // Non-finite limits from an untrusted server must not escape as an exception.
        server.enqueue(json("""{"tag":"payRequest","callback":"${cb()}","minSendable":1e400}"""))
        assertEquals(ZapResult.Failure(ZapError.LnurlUnreachable), zap())
        // An integer literal beyond the double range also becomes Infinity. (A merely 30-digit
        // one is a finite double; Dart's toInt() clamps it, so it is accepted like in Dart.)
        server.enqueue(json("""{"tag":"payRequest","callback":"${cb()}","maxSendable":1${"0".repeat(400)}}"""))
        assertEquals(ZapResult.Failure(ZapError.LnurlUnreachable), zap())
        assertEquals(7, server.requestCount)
    }

    @Test
    fun `amount out of range`() {
        server.enqueue(json(meta()))
        assertEquals(ZapResult.Failure(ZapError.AmountOutOfRange), zap(200_000)) // 2e8 msat > maxSendable 1e8
        server.enqueue(json("""{"tag":"payRequest","callback":"${cb()}","minSendable":50000}"""))
        assertEquals(ZapResult.Failure(ZapError.AmountOutOfRange), zap(21))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `default limits accept 21 sat`() {
        server.enqueue(json("""{"tag":"payRequest","callback":"${cb()}"}"""))
        server.enqueue(json("""{"pr":"lnbc"}"""))
        assertTrue(zap(21).ok)
    }

    @Test
    fun `signing failure`() {
        signer.failSigning = SigningCancelledException()
        server.enqueue(json(meta(""","allowsNostr":true""")))
        assertEquals(ZapResult.Failure(ZapError.SigningFailed), zap())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `invoice errors`() {
        server.enqueue(json(meta()))
        server.enqueue(json("""{"pr":"x"}""", code = 500))
        assertEquals(ZapResult.Failure(ZapError.InvoiceFailed), zap())
        server.enqueue(json(meta()))
        server.enqueue(json("""{"status":"ERROR","reason":"nope"}"""))
        assertEquals(ZapResult.Failure(ZapError.InvoiceFailed), zap())
        server.enqueue(json(meta()))
        server.enqueue(json("""{"pr":""}"""))
        assertEquals(ZapResult.Failure(ZapError.InvoiceFailed), zap())
        server.enqueue(json(meta()))
        server.enqueue(json("[]"))
        val r = zap()
        assertEquals(ZapResult.Failure(ZapError.InvoiceFailed), r)
        assertFalse(r.ok)
        assertNull(r.invoice)
    }

    @Test
    fun `fetchLightningAddress delegates to the profile lookup`() = runBlocking {
        assertEquals("markus@einundzwanzig.space", service.fetchLightningAddress(author))
        assertEquals(listOf(author), lookup.lookups)
    }

    @Test
    fun `apostrophes and stars are percent-encoded like Dart and sent verbatim`() {
        // Dart Uri.encodeQueryComponent encodes ' and * (%27, %2A); OkHttp keeps those escapes.
        server.enqueue(json(meta(""","allowsNostr":true""")))
        server.enqueue(json("""{"pr":"lnbc1"}"""))
        assertTrue(zap(21, "Satoshi's *Uhr*").ok)
        server.takeRequest()
        val target = server.takeRequest().target
        val zapJson = signer.signedEvents.single().toJson()
        assertEquals("/cb?amount=21000&nostr=${DartUri.encodeQueryComponent(zapJson)}", target)
        assertTrue(target.contains("Satoshi%27s+%2AUhr%2A"))

        server.enqueue(json(meta(""","commentAllowed":100""")))
        server.enqueue(json("""{"pr":"lnbc1"}"""))
        assertTrue(zap(21, "Satoshi's *Uhr*").ok)
        server.takeRequest()
        assertEquals("/cb?amount=21000&comment=Satoshi%27s+%2AUhr%2A", server.takeRequest().target)
    }
}
