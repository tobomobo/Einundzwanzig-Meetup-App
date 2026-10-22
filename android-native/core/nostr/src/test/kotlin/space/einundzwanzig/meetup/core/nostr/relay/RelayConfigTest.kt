package space.einundzwanzig.meetup.core.nostr.relay

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RelayConfigTest {

    private val store = InMemoryKeyValueStore()
    private val reachable = mutableSetOf<String>()
    private val sockets = mutableListOf<FakeRelaySocket>()
    private val connector = FakeRelayConnector { url ->
        if (url in reachable) FakeRelaySocket(url).also { sockets += it } else throw RelayConnectException(url, null)
    }
    private val config = RelayConfig(store, connector)

    @Test
    fun constantsMatchDart() {
        assertEquals(
            listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.nostr.band", "wss://nostr.einundzwanzig.space"),
            RelayConfig.DEFAULT_RELAYS,
        )
        assertEquals(8.seconds, RelayConfig.RELAY_TIMEOUT)
        assertEquals(5.seconds, RelayConfig.PUBLISH_TIMEOUT)
        assertEquals("custom_relays", RelayConfig.KEY_CUSTOM_RELAYS)
        assertEquals("disabled_default_relays", RelayConfig.KEY_DISABLED_DEFAULTS)
        assertEquals(RelayConfig.DEFAULT_RELAYS, config.defaultRelays)
    }

    @Test
    fun activeRelaysAreEnabledDefaultsThenCustomWithoutDuplicates() = runTest {
        assertEquals(RelayConfig.DEFAULT_RELAYS, config.getActiveRelays())
        store.setStringList("disabled_default_relays", listOf("wss://nos.lol"))
        store.setStringList("custom_relays", listOf("wss://x.example", "wss://relay.damus.io", "wss://x.example"))
        assertEquals(
            listOf("wss://relay.damus.io", "wss://relay.nostr.band", "wss://nostr.einundzwanzig.space", "wss://x.example"),
            config.getActiveRelays(),
        )
        // A custom relay that equals a disabled default makes it active again (Set semantics in Dart).
        store.setStringList("custom_relays", listOf("wss://nos.lol"))
        assertEquals(RelayConfig.DEFAULT_RELAYS - "wss://nos.lol" + "wss://nos.lol", config.getActiveRelays())
    }

    @Test
    fun relayStatusIsOrderedDefaultsThenCustom() = runTest {
        store.setStringList("disabled_default_relays", listOf("wss://relay.nostr.band"))
        store.setStringList("custom_relays", listOf("wss://b.example", "wss://a.example"))
        val status = config.getRelayStatus()
        assertEquals(RelayConfig.DEFAULT_RELAYS + listOf("wss://b.example", "wss://a.example"), status.keys.toList())
        assertEquals(listOf(true, true, false, true, true, true), status.values.toList())
    }

    @Test
    fun addCustomRelayOutcomes() = runTest {
        assertEquals(RelayAddResult.INVALID_URL, config.addCustomRelay("wss://nohost"))
        assertEquals(RelayAddResult.ALREADY_PRESENT, config.addCustomRelay(" wss://nos.lol "))
        assertEquals(RelayAddResult.UNREACHABLE, config.addCustomRelay("wss://down.example"))
        assertTrue(connector.connects.contains("wss://down.example"))

        reachable += "wss://Up.Example"
        assertEquals(RelayAddResult.ADDED, config.addCustomRelay("  wss://Up.Example  "))
        assertEquals(listOf("wss://Up.Example"), store.getStringList("custom_relays"))
        assertTrue("probe socket closed", sockets.single().closedByClient)
        assertEquals(RelayAddResult.ALREADY_PRESENT, config.addCustomRelay("wss://Up.Example"))

        // Exact string compare: a trailing slash is a different relay (Dart parity).
        reachable += "wss://nos.lol/"
        assertEquals(RelayAddResult.ADDED, config.addCustomRelay("wss://nos.lol/"))
        assertEquals(listOf("wss://Up.Example", "wss://nos.lol/"), config.getCustomRelays())
    }

    @Test
    fun removeCustomRelayRemovesFirstExactMatch() = runTest {
        store.setStringList("custom_relays", listOf("wss://a.example", "wss://b.example", "wss://a.example"))
        config.removeCustomRelay("wss://a.example")
        assertEquals(listOf("wss://b.example", "wss://a.example"), config.getCustomRelays())
        config.removeCustomRelay("wss://missing.example")
        assertEquals(listOf("wss://b.example", "wss://a.example"), config.getCustomRelays())
    }

    @Test
    fun setDefaultRelayEnabledTogglesOnce() = runTest {
        config.setDefaultRelayEnabled("wss://nos.lol", false)
        config.setDefaultRelayEnabled("wss://nos.lol", false)
        assertEquals(listOf("wss://nos.lol"), config.getDisabledDefaults())
        assertEquals(PrefValue.StringListValue(listOf("wss://nos.lol")), store.get("disabled_default_relays"))
        config.setDefaultRelayEnabled("wss://nos.lol", true)
        assertEquals(emptyList<String>(), config.getDisabledDefaults())
    }

    @Test
    fun observeActiveRelaysFollowsBothKeys() = runTest {
        config.observeActiveRelays().test {
            assertEquals(RelayConfig.DEFAULT_RELAYS, awaitItem())
            config.setDefaultRelayEnabled("wss://relay.damus.io", false)
            assertEquals(RelayConfig.DEFAULT_RELAYS - "wss://relay.damus.io", awaitItem())
            store.setStringList("custom_relays", listOf("wss://c.example"))
            assertEquals(RelayConfig.DEFAULT_RELAYS - "wss://relay.damus.io" + "wss://c.example", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun probeTimesOutOnAStalledHandshake() = runTest {
        val stalled = RelayConfig(store, FakeRelayConnector { awaitCancellation() })
        assertFalse(stalled.probeRelay("wss://slow.example", timeout = 100.milliseconds))
    }

    @Test
    fun concurrentAddOfTheSameUrlStoresItOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gated = RelayConfig(store, FakeRelayConnector { url -> gate.await(); FakeRelaySocket(url) })
        val first = async { gated.addCustomRelay("wss://twice.example") }
        val second = async { gated.addCustomRelay("wss://twice.example") }
        runCurrent() // both passed the duplicate check and wait in the probe
        gate.complete(Unit)
        assertEquals(
            setOf(RelayAddResult.ADDED, RelayAddResult.ALREADY_PRESENT),
            setOf(first.await(), second.await()),
        )
        assertEquals(listOf("wss://twice.example"), gated.getCustomRelays())
    }

    @Test
    fun probeRelayOwnTimeoutIsFalseButCallersTimeoutPropagates() = runTest {
        val hanging = RelayConfig(store, FakeRelayConnector { awaitCancellation() })
        assertFalse(hanging.probeRelay("wss://slow.example", 100.milliseconds))
        var thrown: Throwable? = null
        try {
            withTimeout(100.milliseconds) { hanging.probeRelay("wss://slow.example", 5.seconds) }
        } catch (e: TimeoutCancellationException) {
            thrown = e
        }
        assertTrue("outer timeout must propagate", thrown is TimeoutCancellationException)
        thrown = null
        try {
            withTimeout(100.milliseconds) { hanging.addCustomRelay("wss://slow.example") }
        } catch (e: TimeoutCancellationException) {
            thrown = e
        }
        assertTrue("addCustomRelay must not report UNREACHABLE after the caller timed out", thrown is TimeoutCancellationException)
    }
}
