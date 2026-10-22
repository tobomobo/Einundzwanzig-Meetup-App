package space.einundzwanzig.meetup.data.profile

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup.Companion.LUD06_MARKER
import space.einundzwanzig.meetup.data.profile.internal.DefaultLightningAddressLookup

/** `NewsZapService.fetchLightningAddress` + `readTargets` (spec 11 §6.1/§6.9, spec 06 §5.5). */
class LightningAddressLookupTest {

    private val time = FakeTime()
    private val now get() = time.millis / 1000
    private val relays = FakeRelays()
    private val store = InMemoryKeyValueStore()
    private val alice = pubOf(SECRET1)

    private fun TestScope.lookup() = DefaultLightningAddressLookup(
        RelayConfig(store, FakeRelayConnector.unreachable()),
        RelayClient(relays.connector, UnconfinedTestDispatcher(testScheduler)),
        time,
    )

    private fun profile(content: String, age: Long, secret: String = SECRET1) =
        signed(secret, 0, content, createdAt = now - age).json()

    @Test
    fun `read targets are news relays, read-only relays, then active relays without duplicates`() = runTest {
        store.setStringList(RelayConfig.KEY_CUSTOM_RELAYS, listOf("wss://my.relay", "wss://nos.lol/"))
        store.setStringList(RelayConfig.KEY_DISABLED_DEFAULTS, listOf("wss://relay.damus.io"))
        assertEquals(
            listOf(
                "wss://nostr.einundzwanzig.space",
                "wss://relay.damus.io",
                "wss://nos.lol",
                "wss://relay.nostr.band",
                "wss://my.relay",
                "wss://nos.lol/",
            ),
            lookup().readTargets(),
        )
    }

    @Test
    fun `newest profile wins across relays`() = runTest {
        relays.add("wss://relay.damus.io", profile("{\"lud16\":\"old@wallet.example\"}", age = 100))
        relays.add("wss://nos.lol", profile("{\"lud16\":\"  new@wallet.example \"}", age = 10))
        assertEquals("new@wallet.example", lookup().fetchLightningAddress(alice))
        val req = relays.reqs.first().second
        assertEquals("zapmeta", req[1].jsonPrimitive.content)
        assertEquals("{\"kinds\":[0],\"authors\":[\"$alice\"],\"limit\":1}", DartJson.encode(req[2]))
    }

    @Test
    fun `newer profile without address clears an older one`() = runTest {
        relays.add("wss://relay.damus.io", profile("{\"lud16\":\"old@wallet.example\"}", age = 100))
        relays.add("wss://nos.lol", profile("{\"name\":\"x\"}", age = 10))
        assertNull(lookup().fetchLightningAddress(alice))
    }

    @Test
    fun `lud06 only yields the unsupported marker`() = runTest {
        relays.add("wss://nos.lol", profile("{\"lud16\":\"no-at-sign\",\"lud06\":\"lnurl1xyz\"}", age = 10))
        assertEquals(LUD06_MARKER, lookup().fetchLightningAddress(alice))
    }

    @Test
    fun `unparsable newer content leaves the best unchanged and future events are ignored`() = runTest {
        relays.add(
            "wss://nos.lol",
            profile("{\"lud16\":\"a@b.example\"}", age = 100),
            profile("not json", age = 10),
            profile("{\"lud16\":\"future@b.example\"}", age = -3600),
        )
        assertEquals("a@b.example", lookup().fetchLightningAddress(alice))
    }

    @Test
    fun `equal timestamps - the later arrival wins`() = runTest {
        relays.add(
            "wss://nos.lol",
            profile("{\"lud16\":\"first@b.example\"}", age = 10),
            profile("{\"lud16\":\"second@b.example\"}", age = 10),
        )
        assertEquals("second@b.example", lookup().fetchLightningAddress(alice))
    }

    @Test
    fun `non-string lud16 values use Dart toString`() {
        val ev = profile("{\"lud16\":12}", age = 1)
        assertNull(DefaultLightningAddressLookup.addressOf(ev))
        assertEquals(LUD06_MARKER, DefaultLightningAddressLookup.addressOf(profile("{\"lud06\":7}", age = 1)))
    }

    @Test
    fun `empty pubkey and unreachable relays give null`() = runTest {
        assertNull(lookup().fetchLightningAddress(""))
        assertNull(lookup().fetchLightningAddress(alice))
        assertTrue(relays.connector.connects.isNotEmpty())
    }

    @Test
    fun `a silent relay ends at the overall budget with the address found so far`() = runTest {
        relays.silent += "wss://relay.damus.io"
        relays.add("wss://nos.lol", profile("{\"lud16\":\"a@b.example\"}", age = 10))
        val start = currentTime
        assertEquals("a@b.example", lookup().fetchLightningAddress(alice))
        assertEquals(LightningAddressLookup.OVERALL_TIMEOUT.inWholeMilliseconds, currentTime - start)
    }
}
