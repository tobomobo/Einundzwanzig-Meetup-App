package space.einundzwanzig.meetup.data.proofs

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.data.proofs.internal.DefaultZapProviderResolver
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** `security_followup_test.dart` ("Quittung vom eigenen Schlüssel …") + spec 06 §10.6 LNURL cases. */
class ZapProviderResolverTest {

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var respond: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(404).build() }

    private val recipient = "11".repeat(32)
    private val providerKey = "ab".repeat(32)
    private val time = FakeTime()
    private val store = InMemoryKeyValueStore()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return respond(request)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun resolver(
        lud16: Map<String, String?> = mapOf(recipient to "Alice@Wallet.Example"),
        lookup: LightningAddressLookup = FakeLightningAddresses(lud16),
        http: OkHttpClient = clientFor(server),
    ) = DefaultZapProviderResolver(store, lookup, http, time, log, testDispatcher)

    private fun lnurl(body: String) = MockResponse.Builder().code(200).body(body).build()

    // ---- security_followup_test.dart ----

    @Test
    fun `Quittung vom eigenen Schluessel zaehlt nicht als Provider`() = runBlocking<Unit> {
        val self = "aa".repeat(32)
        val throwing = object : LightningAddressLookup {
            override suspend fun fetchLightningAddress(pubkeyHex: String): String? = error("no network expected")
            override suspend fun readTargets(): List<String> = error("no network expected")
        }
        val r = resolver(lookup = throwing)
        assertFalse(r.isFromRecipientProvider(receiptPubkey = self, recipientPubkey = self))
        assertFalse(r.isFromRecipientProvider(receiptPubkey = self, recipientPubkey = "bb".repeat(32), senderPubkey = self))
        // Case-insensitive self check.
        assertFalse(r.isFromRecipientProvider(receiptPubkey = self.uppercase(), recipientPubkey = self))
        assertFalse(r.isFromRecipientProvider(receiptPubkey = "", recipientPubkey = self))
        assertTrue(requests.isEmpty())
    }

    // ---- LNURL ----

    @Test
    fun `resolves nostrPubkey from lnurlp with raw user part and lowercased domain`() = runBlocking<Unit> {
        respond = { lnurl("""{"allowsNostr":true,"nostrPubkey":"${providerKey.uppercase()}"}""") }
        val r = resolver()
        assertEquals(providerKey, r.providerPubkeyFor(recipient))
        val req = requests.single()
        assertEquals("/.well-known/lnurlp/Alice", req.target)
        assertEquals("wallet.example", req.headers["X-Original-Host"])
        assertTrue(r.isFromRecipientProvider(providerKey.uppercase(), recipient, "cc".repeat(32)))
        // Stored with ms timestamp.
        val cached = store.getString("zap_provider_pubkey_$recipient")!!
        assertEquals("""{"pk":"$providerKey","ts":${time.millis}}""", cached)
    }

    @Test
    fun `allowsNostr as a string is rejected`() = runBlocking<Unit> {
        respond = { lnurl("""{"allowsNostr":"true","nostrPubkey":"$providerKey"}""") }
        assertNull(resolver().providerPubkeyFor(recipient))
        assertNull(store.getString("zap_provider_pubkey_$recipient"))
    }

    @Test
    fun `63-char key is rejected`() = runBlocking<Unit> {
        respond = { lnurl("""{"allowsNostr":true,"nostrPubkey":"${providerKey.drop(1)}"}""") }
        assertNull(resolver().providerPubkeyFor(recipient))
    }

    @Test
    fun `non-200 and oversized bodies are rejected and not cached`() = runBlocking<Unit> {
        respond = { MockResponse.Builder().code(500).body("{}").build() }
        val r = resolver()
        assertNull(r.providerPubkeyFor(recipient))
        respond = { lnurl("""{"allowsNostr":true,"nostrPubkey":"$providerKey","pad":"${"x".repeat(70_000)}"}""") }
        assertNull(r.providerPubkeyFor(recipient))
        // Failures are not cached: the next call asks again and succeeds.
        respond = { lnurl("""{"allowsNostr":true,"nostrPubkey":"$providerKey"}""") }
        assertEquals(providerKey, r.providerPubkeyFor(recipient))
        assertEquals(3, requests.size)
    }

    @Test
    fun `bad lud16 shapes never hit the network`() = runBlocking<Unit> {
        for (lud in listOf(null, "lud06:", "a@b@c", "@wallet.example", "alice@", "alice@wallet_example", "alice@wallet.example/x")) {
            assertNull(lud, resolver(lud16 = mapOf(recipient to lud)).providerPubkeyFor(recipient))
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `seven-day pref cache is honoured, empty pk is re-resolved`() = runBlocking<Unit> {
        store.setString("zap_provider_pubkey_$recipient", """{"pk":"$providerKey","ts":${time.millis - 6L * 86_400_000}}""")
        assertEquals(providerKey, resolver(lud16 = emptyMap()).providerPubkeyFor(recipient))
        assertTrue(requests.isEmpty())

        val other = "22".repeat(32)
        store.setString("zap_provider_pubkey_$other", """{"pk":"","ts":${time.millis}}""")
        respond = { lnurl("""{"allowsNostr":true,"nostrPubkey":"$providerKey"}""") }
        assertEquals(providerKey, resolver(lud16 = mapOf(other to "bob@wallet.example")).providerPubkeyFor(other))
        assertEquals(1, requests.size)
    }

    @Test
    fun `expired pref cache is re-resolved`() = runBlocking<Unit> {
        store.setString("zap_provider_pubkey_$recipient", """{"pk":"${"cd".repeat(32)}","ts":${time.millis - 7L * 86_400_000}}""")
        respond = { lnurl("""{"allowsNostr":true,"nostrPubkey":"$providerKey"}""") }
        assertEquals(providerKey, resolver().providerPubkeyFor(recipient))
    }

    @Test
    fun `memory cache answers without prefs or network`() = runBlocking<Unit> {
        respond = { lnurl("""{"allowsNostr":true,"nostrPubkey":"$providerKey"}""") }
        val r = resolver()
        assertEquals(providerKey, r.providerPubkeyFor(recipient))
        store.clear()
        assertEquals(providerKey, r.providerPubkeyFor(recipient))
        assertEquals(1, requests.size)
    }

    @Test
    fun `provider mismatch is not from provider`() = runBlocking<Unit> {
        respond = { lnurl("""{"allowsNostr":true,"nostrPubkey":"$providerKey"}""") }
        assertFalse(resolver().isFromRecipientProvider("ee".repeat(32), recipient))
    }

    @Test
    fun `non-int ts in cache is ignored`() = runBlocking<Unit> {
        store.set("zap_provider_pubkey_$recipient", PrefValue.StringValue(DartJson.encode(mapOf("pk" to providerKey, "ts" to "x"))))
        respond = { lnurl("""{"allowsNostr":true,"nostrPubkey":"${"cd".repeat(32)}"}""") }
        assertEquals("cd".repeat(32), resolver().providerPubkeyFor(recipient))
    }
}
