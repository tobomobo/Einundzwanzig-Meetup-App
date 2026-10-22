package space.einundzwanzig.meetup.data.profile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.data.prefs.KeyValueEditor
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.ProfileRelayQuery
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.data.profile.NostrProfileLookup.Companion.INDEX_RELAY
import space.einundzwanzig.meetup.data.profile.internal.DefaultNostrProfileLookup
import space.einundzwanzig.meetup.data.profile.internal.DefaultNostrProfileService
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours

/** Port of `test/nostr_profile_lookup_test.dart` (spec 07 §10.7), all 16 cases. */
class NostrProfileLookupTest {

    private val time = FakeTime()
    private val store = InMemoryKeyValueStore()
    private val calls = CopyOnWriteArrayList<String>()
    private val responses = HashMap<String, List<JsonObject>>()
    private val pubkey = pubOf(SECRET1)
    private val image = "https://images.example/avatar.png"

    private fun event(
        kind: Int = 0,
        content: String = "{\"picture\":\"https://images.example/avatar.png\"}",
        tags: List<List<String>> = emptyList(),
        age: Long = 10,
        key: String = SECRET1,
    ): JsonObject = signed(key, kind, content, tags, time.millis / 1000 - age).json()

    private val defaultQuery = ProfileRelayQuery { relay, author ->
        assertTrue(Regex("^[0-9a-f]{64}$").matches(author))
        calls += relay
        if (author == pubkey) responses[relay].orEmpty() else emptyList()
    }

    private fun TestScope.lookup(
        relays: suspend () -> List<String> = { listOf("wss://app.example") },
        query: ProfileRelayQuery = defaultQuery,
        scope: CoroutineScope = backgroundScope,
    ) = DefaultNostrProfileLookup(relays, query, time, store, scope)

    private fun advance(hours: Int) {
        time.millis += hours.hours.inWholeMilliseconds
    }

    private suspend fun has(key: String) = store.contains(key)

    @Test
    fun `first import finds signed metadata on profile index without local key or NIP-05`() = runTest {
        val lookup = lookup()
        responses[INDEX_RELAY] = listOf(event())
        assertEquals(image, lookup.fetchPicture(pubkey))
        assertTrue(calls.containsAll(listOf("wss://app.example", INDEX_RELAY)))
        calls.clear()
        assertEquals(image, lookup.fetchPicture(pubkey))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `NIP-65 write and unmarked relays compete with bootstrap metadata, read relays excluded`() = runTest {
        val lookup = lookup()
        responses[INDEX_RELAY] = listOf(
            event(age = 100),
            event(
                kind = 10002,
                content = "",
                tags = listOf(
                    listOf("r", "wss://read.example", "read"),
                    listOf("r", "wss://write.example/", "write"),
                    listOf("r", "wss://both.example"),
                    listOf("r", "wss://write.example"),
                ),
            ),
        )
        responses["wss://write.example"] = listOf(event(content = "{\"picture\":\"https://images.example/new.png\"}"))
        assertEquals("https://images.example/new.png", lookup.fetchPicture(pubkey))
        assertEquals(1, calls.count { it == "wss://write.example" })
        assertTrue(calls.contains("wss://both.example"))
        assertFalse(calls.contains("wss://read.example"))
        advance(13)
        responses.remove(INDEX_RELAY)
        calls.clear()
        assertEquals("https://images.example/new.png", lookup.fetchPicture(pubkey))
        assertTrue(calls.contains("wss://write.example")) // stored relay hints
    }

    @Test
    fun `latest relay list replaces older hints, including removal of all relays`() = runTest {
        val lookup = lookup()
        responses["wss://app.example"] = listOf(
            event(kind = 10002, content = "", age = 100, tags = listOf(listOf("r", "wss://old.example"))),
        )
        responses[INDEX_RELAY] = listOf(event(kind = 10002, content = ""))
        assertNull(lookup.fetchPicture(pubkey))
        assertFalse(calls.contains("wss://old.example"))
    }

    @Test
    fun `newest metadata wins regardless of arrival order, equal timestamps use lowest ID`() = runTest {
        val lookup = lookup()
        val a = event()
        val b = event(content = "{\"picture\":\"https://images.example/b.png\"}")
        val winner = if (a["id"]!!.jsonPrimitive.content < b["id"]!!.jsonPrimitive.content) a else b
        responses["wss://app.example"] = listOf(b, event(age = 100), a)
        val expected = DartJson.decode(winner["content"]!!.jsonPrimitive.content).jsonObject["picture"]!!.jsonPrimitive.content
        assertEquals(expected, lookup.fetchPicture(pubkey))
    }

    @Test
    fun `rejects tampering, wrong author or kind, future timestamp and malformed metadata`() = runTest {
        val lookup = lookup()
        val tampered = JsonObject(event() + ("content" to JsonPrimitive("{\"picture\":\"https://evil.example\"}")))
        responses["wss://app.example"] = listOf(
            tampered,
            event(key = SECRET2),
            event(kind = 1),
            event(age = -1),
            event(content = "[]"),
            event(content = "not JSON"),
            event(),
        )
        assertEquals(image, lookup.fetchPicture(pubkey))
    }

    @Test
    fun `stale signed cache survives outage and older responses cannot downgrade it`() = runTest {
        val lookup = lookup()
        responses["wss://app.example"] = listOf(event())
        assertEquals(image, lookup.fetchPicture(pubkey))
        advance(13)
        responses.clear()
        assertEquals(image, lookup.fetchPicture(pubkey))
        responses["wss://app.example"] = listOf(event(age = 100000, content = "{\"picture\":\"https://images.example/old.png\"}"))
        assertEquals(image, lookup.fetchPicture(pubkey))
    }

    @Test
    fun `signed picture removal wins, is cached, and never revives legacy URL`() = runTest {
        val lookup = lookup()
        responses["wss://app.example"] = listOf(event())
        lookup.fetchPicture(pubkey)
        advance(13)
        store.setString("nostr_profile_picture_$pubkey", image)
        responses["wss://app.example"] = listOf(event(content = "{}"))
        assertNull(lookup.fetchPicture(pubkey))
        calls.clear()
        assertNull(lookup.fetchPicture(pubkey))
        assertTrue(calls.isEmpty())
        advance(13)
        responses.clear()
        assertNull(lookup.fetchPicture(pubkey))
        assertFalse(has("nostr_profile_picture_$pubkey"))
    }

    @Test
    fun `legacy cache migrates on expiry and remains an outage fallback`() = runTest {
        val lookup = lookup()
        store.setString("nostr_profile_picture_$pubkey", image)
        store.setInt("nostr_profile_picture_time_$pubkey", time.millis)
        assertEquals(image, lookup.fetchPicture(pubkey))
        assertTrue(calls.isEmpty())
        advance(13)
        assertEquals(image, lookup.fetchPicture(pubkey))
        responses["wss://app.example"] = listOf(event())
        assertEquals(image, lookup.fetchPicture(pubkey))
        assertTrue(has("nostr_profile_metadata_v2_$pubkey"))
        assertFalse(has("nostr_profile_picture_$pubkey"))
    }

    @Test
    fun `invalid cache and invalid key fail safely, missing metadata is retried`() = runTest {
        val lookup = lookup()
        store.setString(
            "nostr_profile_metadata_v2_$pubkey",
            DartJson.encode(jsonObjectOf("event" to event(kind = 10002, content = ""), "fetchedAt" to time.millis)),
        )
        assertNull(lookup.fetchPicture("npub-invalid"))
        assertTrue(calls.isEmpty())
        assertNull(lookup.fetchPicture(pubkey))
        responses["wss://app.example"] = listOf(event())
        assertEquals(image, lookup.fetchPicture(pubkey))
        // Another identity must not use this cache.
        assertNull(lookup.fetchPicture("0".repeat(64)))
    }

    @Test
    fun `simultaneous lookups coalesce, invalidation prevents old cache writes`() = runTest(UnconfinedTestDispatcher()) {
        val pending = CompletableDeferred<List<JsonObject>>()
        var count = 0
        val lookup = lookup(relays = { emptyList() }, query = { _, _ ->
            count++
            pending.await()
        })
        val first = async { lookup.fetchPicture(pubkey) }
        val second = async { lookup.fetchPicture(pubkey) }
        runCurrent()
        assertEquals(1, count)
        lookup.invalidate()
        pending.complete(listOf(event()))
        assertEquals(image, first.await())
        assertEquals(image, second.await())
        assertFalse(has("nostr_profile_metadata_v2_$pubkey"))
    }

    @Test
    fun `discovered relay destinations are deduplicated and bounded`() = runTest {
        val lookup = lookup()
        responses[INDEX_RELAY] = listOf(
            event(
                kind = 10002,
                content = "",
                tags = listOf(
                    listOf("r", "ws://insecure.example"),
                    listOf("r", "wss://127.0.0.1"),
                    listOf("r", "wss://10.0.0.1"),
                    listOf("r", "wss://user:pass@host.example"),
                    listOf("r", "wss://localhost"),
                ) + (0 until 10).map { listOf("r", "wss://relay$it.example") },
            ),
        )
        lookup.fetchPicture(pubkey)
        assertEquals(4, calls.count { it.startsWith("wss://relay") })
        assertEquals(6, calls.size)
    }

    @Test
    fun `bad relay failures are isolated and initial fan-out is bounded`() = runTest {
        val lookup = lookup(
            relays = { (0 until 30).map { "wss://relay$it.example" } },
            query = { relay, _ ->
                calls += relay
                if (relay == INDEX_RELAY) listOf(event()) else throw IllegalStateException("offline")
            },
        )
        assertEquals(image, lookup.fetchPicture(pubkey))
        assertEquals(NostrProfileLookup.MAX_INITIAL_RELAYS, calls.size)
    }

    @Test
    fun `picture URL accepts HTTP(S) only without embedded credentials`() {
        for (value in listOf<Any?>(
            null,
            12,
            "",
            "file:///tmp/picture",
            "data:image/png,abc",
            "javascript:alert(1)",
            "https://user:pass@images.example/a",
        )) {
            assertNull(value.toString(), NostrProfileLookup.pictureUrl(value))
        }
        assertEquals(image, NostrProfileLookup.pictureUrl(image))
        // JSON values as they come from kind-0 content.
        assertEquals(image, NostrProfileLookup.pictureUrl(JsonPrimitive(image)))
        assertNull(NostrProfileLookup.pictureUrl(JsonPrimitive(12)))
    }

    @Test
    fun `new relay hints from author relays are retained without recursive fan-out`() = runTest {
        val lookup = lookup()
        responses[INDEX_RELAY] = listOf(
            event(kind = 10002, content = "", age = 100, tags = listOf(listOf("r", "wss://old.example"))),
        )
        responses["wss://old.example"] = listOf(
            event(kind = 10002, content = "", tags = listOf(listOf("r", "wss://new.example"))),
        )
        assertNull(lookup.fetchPicture(pubkey))
        assertFalse(calls.contains("wss://new.example"))
        responses.clear()
        calls.clear()
        responses["wss://new.example"] = listOf(event())
        assertEquals(image, lookup.fetchPicture(pubkey))
        assertTrue(calls.contains("wss://new.example"))
    }

    @Test
    fun `app reset clears both cache generations and hints but preserves unrelated preferences`() = runTest {
        store.setString("nostr_profile_metadata_v2_$pubkey", "{}")
        store.setString("nostr_profile_relays_v2_$pubkey", "{}")
        store.setString("nostr_profile_picture_$pubkey", image)
        store.setInt("nostr_profile_picture_time_$pubkey", 1)
        store.setString("local_profile_picture", "/local.png")
        store.setString("nickname", "Example")
        val service = DefaultNostrProfileService(
            lookup = lookup(),
            relayConfig = RelayConfig(store, FakeRelayConnector.unreachable()),
            relayClient = RelayClient(FakeRelayConnector.unreachable()),
            store = store,
            time = time,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            scope = backgroundScope,
        )
        service.clearCache()
        assertEquals(setOf("nickname"), store.keys())
        assertEquals(PrefValue.StringValue("Example"), store.get("nickname"))
    }

    @Test
    fun `relay hint URL rejects private, abbreviated and invalid literal addresses`() {
        for (host in listOf(
            "127.1",
            "10.1",
            "192.168.1.1",
            "172.16.0.1",
            "169.254.1.1",
            "100.64.0.1",
            "0.0.0.0",
            "999.1.1.1",
            "[::1]",
            "relay.local",
            "relay.local.",
            "localhost.localdomain",
            "127.0.0.1.",
        )) {
            assertNull(host, NostrProfileLookup.relayUrl("wss://$host", discovered = true))
        }
        assertEquals("wss://relay.example", NostrProfileLookup.relayUrl("wss://relay.example/", discovered = true))
        assertEquals("wss://relay.example/path", NostrProfileLookup.relayUrl("wss://relay.example/path", discovered = true))
    }

    // ------------------------------------------------------------- native extras

    @Test
    fun `cache writes keep the Flutter wire format`() = runTest {
        val lookup = lookup()
        val ev = event()
        val list = event(kind = 10002, content = "", tags = listOf(listOf("r", "wss://w.example")))
        responses["wss://app.example"] = listOf(ev, list)
        assertEquals(image, lookup.fetchPicture(pubkey))
        assertEquals(
            DartJson.encode(jsonObjectOf("event" to ev, "fetchedAt" to time.millis)),
            store.getString("nostr_profile_metadata_v2_$pubkey"),
        )
        assertEquals(DartJson.encode(list), store.getString("nostr_profile_relays_v2_$pubkey"))
    }

    @Test
    fun `cached metadata with a non-int fetchedAt is used as stale cache`() = runTest {
        val lookup = lookup()
        store.setString(
            "nostr_profile_metadata_v2_$pubkey",
            DartJson.encode(jsonObjectOf("event" to event(), "fetchedAt" to "x")),
        )
        // Not fresh (fetchedAt = 0) → network; outage → stale cached picture.
        assertEquals(image, lookup.fetchPicture(pubkey))
        assertTrue(calls.isNotEmpty())
    }

    /** Runs [beforeFirstEdit] once, right before the next transaction (an app reset racing a lookup). */
    private class RacingStore(private val inner: KeyValueStore) : KeyValueStore by inner {
        var beforeFirstEdit: (suspend () -> Unit)? = null

        override suspend fun edit(block: KeyValueEditor.() -> Unit): Boolean {
            beforeFirstEdit?.let {
                beforeFirstEdit = null
                it()
            }
            return inner.edit(block)
        }
    }

    @Test
    fun `app reset during an in-flight lookup is not undone by its late cache write`() = runTest {
        val racing = RacingStore(store)
        val lookup = DefaultNostrProfileLookup({ listOf("wss://app.example") }, defaultQuery, time, racing, backgroundScope)
        val service = DefaultNostrProfileService(
            lookup = lookup,
            relayConfig = RelayConfig(store, FakeRelayConnector.unreachable()),
            relayClient = RelayClient(FakeRelayConnector.unreachable()),
            store = racing,
            time = time,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            scope = backgroundScope,
        )
        responses["wss://app.example"] = listOf(event())
        // The reset lands after the lookup decided to write, before its transaction commits.
        racing.beforeFirstEdit = { service.clearCache() }
        assertEquals(image, lookup.fetchPicture(pubkey))
        assertFalse(has("nostr_profile_metadata_v2_$pubkey"))
        assertFalse(has("nostr_profile_relays_v2_$pubkey"))
    }
}
