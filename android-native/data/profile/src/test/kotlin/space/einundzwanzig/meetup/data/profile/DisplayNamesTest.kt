package space.einundzwanzig.meetup.data.profile

import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.ProfileRelayQuery
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.data.profile.internal.DefaultNostrProfileLookup
import space.einundzwanzig.meetup.data.profile.internal.DefaultNostrProfileService
import space.einundzwanzig.meetup.data.profile.internal.DefaultNostrProfileService.Companion.profileNameOrEmpty
import space.einundzwanzig.meetup.data.profile.internal.DefaultNostrProfileService.Companion.profileNameOrNull
import space.einundzwanzig.meetup.data.profile.internal.DefaultNostrProfileService.Companion.usableNickname
import java.io.File
import kotlin.time.Duration.Companion.days

/** `NostrProfileService` display names and local picture (spec 07 §8.4; no Dart unit test exists). */
class DisplayNamesTest {

    private val time = FakeTime()
    private val now get() = time.millis / 1000
    private val relays = FakeRelays()
    private val r1 = "wss://relay.damus.io"
    private val r2 = "wss://nos.lol"
    private val alice = pubOf(SECRET1)
    private val bob = pubOf(SECRET2)

    /** Only r1 and r2 are active (the other two defaults are switched off). */
    private val store = InMemoryKeyValueStore()

    private suspend fun TestScope.service(serviceStore: KeyValueStore = store): DefaultNostrProfileService {
        store.setStringList(
            RelayConfig.KEY_DISABLED_DEFAULTS,
            listOf("wss://relay.nostr.band", "wss://nostr.einundzwanzig.space"),
        )
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DefaultNostrProfileService(
            lookup = DefaultNostrProfileLookup({ emptyList() }, ProfileRelayQuery { _, _ -> emptyList() }, time, store, backgroundScope),
            relayConfig = RelayConfig(store, FakeRelayConnector.unreachable()),
            relayClient = RelayClient(relays.connector, dispatcher),
            store = serviceStore,
            time = time,
            ioDispatcher = dispatcher,
            scope = backgroundScope,
        )
    }

    private fun profile(secret: String, content: String, age: Long) =
        signed(secret, 0, content, createdAt = now - age).json()

    private fun reputation(secret: String, nickname: String?, age: Long): JsonObject {
        val identity = if (nickname == null) "{}" else DartJson.encode(jsonObjectOf("nickname" to nickname))
        return signed(
            secret,
            30078,
            "{\"identity\":$identity}",
            listOf(listOf("d", "einundzwanzig-reputation")),
            createdAt = now - age,
        ).json()
    }

    @Test
    fun `newest kind 0 across relays wins and display_name has priority`() = runTest {
        val service = service()
        relays.add(r1, profile(SECRET1, "{\"name\":\"alt\",\"display_name\":\"  Old  \"}", age = 100))
        relays.add(r2, profile(SECRET1, "{\"name\":\" alice \",\"display_name\":\"\"}", age = 10))
        assertEquals("alice", service.fetchDisplayName(alice))
        // REQ shape: ["REQ","nam-<16 hex>",{"kinds":[0],"authors":[hex],"limit":1}]
        val req = relays.reqs.first().second
        assertTrue(Regex("^nam-[0-9a-f]{16}$").matches(req[1].jsonPrimitive.content))
        assertEquals("{\"kinds\":[0],\"authors\":[\"$alice\"],\"limit\":1}", DartJson.encode(req[2]))
    }

    @Test
    fun `equal timestamps across relays keep the first relay`() = runTest {
        val service = service()
        relays.add(r1, profile(SECRET1, "{\"name\":\"first\"}", age = 10))
        relays.add(r2, profile(SECRET1, "{\"name\":\"second\"}", age = 10))
        assertEquals("first", service.fetchDisplayName(alice))
    }

    @Test
    fun `newer profile without a name clears the name and skips the nickname`() = runTest {
        val service = service()
        relays.add(r1, profile(SECRET1, "{\"name\":\"alice\"}", age = 100), reputation(SECRET1, "Nick", age = 5))
        relays.add(r2, profile(SECRET1, "{\"about\":\"x\"}", age = 10))
        assertNull(service.fetchDisplayName(alice))
        assertTrue(relays.reqs.none { it.second[1].jsonPrimitive.content.startsWith("nick-") })
    }

    @Test
    fun `without a profile the reputation nickname is used, anon counts as none`() = runTest {
        val service = service()
        relays.add(r1, reputation(SECRET1, "  Satoshi ", age = 10))
        relays.add(r2, reputation(SECRET2, "ANON", age = 10))
        assertEquals("Satoshi", service.fetchDisplayName(alice))
        assertEquals("", service.fetchDisplayName(bob))
        val nickReq = relays.reqs.first { it.second[1].jsonPrimitive.content.startsWith("nick-") }.second
        assertEquals(
            "{\"kinds\":[30078],\"authors\":[\"$alice\"],\"#d\":[\"einundzwanzig-reputation\"],\"limit\":1}",
            DartJson.encode(nickReq[2]),
        )
    }

    @Test
    fun `results including negative ones are cached for the session`() = runTest {
        val service = service()
        relays.add(r1)
        relays.add(r2)
        assertNull(service.fetchDisplayName(alice))
        val connects = relays.connector.connects.size
        relays.add(r1, profile(SECRET1, "{\"name\":\"alice\"}", age = 10))
        assertNull(service.fetchDisplayName(alice))
        assertEquals(connects, relays.connector.connects.size)
        assertNull(service.fetchDisplayName(""))
    }

    @Test
    fun `foreign, future and unparsable events are ignored`() = runTest {
        val service = service()
        relays.addUnfiltered(
            r1,
            profile(SECRET2, "{\"name\":\"mallory\"}", age = 1), // other author: filter mismatch
            profile(SECRET1, "{\"name\":\"future\"}", age = -3600), // > now + 600 s
            profile(SECRET1, "not json", age = 2),
            profile(SECRET1, "{\"name\":42}", age = 3),
            profile(SECRET1, "{\"name\":\"alice\"}", age = 50),
        )
        relays.add(r2)
        assertEquals("alice", service.fetchDisplayName(alice))
    }

    @Test
    fun `batch names use stored names, refresh stale ones, and persist the Flutter format`() = runTest {
        val service = service()
        val carol = pubOf(SECRET3)
        val dave = "d".repeat(64)
        store.setString(
            "nostr_display_names_v1",
            DartJson.encode(
                jsonObjectOf(
                    alice to jsonObjectOf("n" to "Alice (stored)", "t" to time.millis), // fresh
                    bob to jsonObjectOf("n" to "Bob (old)", "t" to time.millis - 2.days.inWholeMilliseconds), // stale
                    dave to jsonObjectOf("n" to "Dave", "t" to time.millis - 2.days.inWholeMilliseconds), // stale, outage
                    "junk" to 5,
                ),
            ),
        )
        relays.add(r1, profile(SECRET2, "{\"name\":\"Bob\"}", age = 10), reputation(SECRET3, "Carol", age = 10))
        relays.add(r2, profile(SECRET2, "{\"name\":\"Bobby\"}", age = 20))

        val names = service.fetchDisplayNames(listOf(alice, bob, carol, dave, "", alice))
        assertEquals(mapOf(alice to "Alice (stored)", bob to "Bob", carol to "Carol", dave to "Dave"), names)

        // One batch per kind per relay; the stored fresh name is not queried.
        val profileReq = relays.reqs.first { it.first == r1 }.second
        assertTrue(Regex("^names-[0-9a-f]{16}$").matches(profileReq[1].jsonPrimitive.content))
        assertEquals(
            "{\"kinds\":[0],\"authors\":[\"$bob\",\"$carol\",\"$dave\"],\"limit\":6}",
            DartJson.encode(profileReq[2]),
        )
        val nickReq = relays.reqs.filter { it.first == r1 }[1].second
        assertEquals(
            "{\"kinds\":[30078],\"authors\":[\"$carol\",\"$dave\"],\"limit\":4,\"#d\":[\"einundzwanzig-reputation\"]}",
            DartJson.encode(nickReq[2]),
        )

        val stored = DartJson.decode(store.getString("nostr_display_names_v1")!!).jsonObject
        assertEquals(listOf(alice, bob, dave, carol), stored.keys.toList())
        assertEquals("{\"n\":\"Bob\",\"t\":${time.millis}}", DartJson.encode(stored[bob]!!))
        // The outage keeps Dave's old entry untouched.
        assertEquals(time.millis - 2.days.inWholeMilliseconds, stored[dave]!!.jsonObject["t"]!!.jsonPrimitive.content.toLong())

        // The single lookup now answers from memory.
        val connects = relays.connector.connects.size
        assertEquals("Bob", service.fetchDisplayName(bob))
        assertEquals(connects, relays.connector.connects.size)
        assertEquals(
            mapOf(alice to "Alice (stored)", bob to "Bob", dave to "Dave", carol to "Carol"),
            service.cachedDisplayNames(),
        )
    }

    @Test
    fun `batch names - a newer profile without name removes the stored name`() = runTest {
        val service = service()
        store.setString(
            "nostr_display_names_v1",
            DartJson.encode(jsonObjectOf(bob to jsonObjectOf("n" to "Bob", "t" to 1))),
        )
        relays.add(r1, profile(SECRET2, "{\"name\":\"Bob\"}", age = 100))
        relays.add(r2, profile(SECRET2, "{}", age = 10), reputation(SECRET2, "Nick", age = 1))
        assertEquals(emptyMap<String, String>(), service.fetchDisplayNames(listOf(bob)))
        assertEquals("{}", store.getString("nostr_display_names_v1"))
        // No nickname query for someone with a profile event.
        assertTrue(relays.reqs.none { DartJson.encode(it.second[2]).contains("30078") })
        assertNull(service.fetchDisplayName(bob))
    }

    @Test
    fun `batch names without relays return only stored names`() = runTest {
        val service = service()
        store.setStringList(RelayConfig.KEY_DISABLED_DEFAULTS, RelayConfig.DEFAULT_RELAYS)
        store.setString("nostr_display_names_v1", DartJson.encode(jsonObjectOf(bob to jsonObjectOf("n" to "Bob", "t" to 1))))
        assertEquals(mapOf(bob to "Bob"), service.fetchDisplayNames(listOf(bob, alice)))
        assertTrue(relays.connector.connects.isEmpty())
        assertEquals(emptyMap<String, String>(), service.fetchDisplayNames(emptyList()))
    }

    @Test
    fun `batch names - session negatives are not re-queried`() = runTest {
        val service = service()
        relays.add(r1)
        relays.add(r2)
        assertEquals(emptyMap<String, String>(), service.fetchDisplayNames(listOf(alice)))
        val connects = relays.connector.connects.size
        assertEquals(emptyMap<String, String>(), service.fetchDisplayNames(listOf(alice)))
        assertEquals(connects, relays.connector.connects.size)
    }

    @Test
    fun `name parsers follow the Dart casts`() {
        assertEquals("Dn", profileNameOrEmpty("{\"display_name\":\" Dn \",\"name\":\"n\"}"))
        assertEquals("n", profileNameOrEmpty("{\"display_name\":\"  \",\"name\":\" n \"}"))
        assertEquals("", profileNameOrEmpty("{}"))
        assertNull(profileNameOrNull("{\"name\":\"\"}"))
        assertEquals("x", profileNameOrNull("{\"display_name\":null,\"name\":\"x\"}"))
        assertNull(usableNickname("{\"identity\":{\"nickname\":\"Anon\"}}"))
        assertNull(usableNickname("{\"identity\":\"x\"}"))
        assertEquals("Neo", usableNickname("{\"identity\":{\"nickname\":\"\\tNeo\\n\"}}"))
        for (bad in listOf("[]", "not json", "{\"name\":1}", "{\"display_name\":[]}")) {
            assertTrue(bad, runCatching { profileNameOrEmpty(bad) }.isFailure)
        }
        assertTrue(runCatching { usableNickname("{\"identity\":{\"nickname\":5}}") }.isFailure)
    }

    @Test
    fun `local picture is stored under local_profile_picture`() = runTest {
        val service = service()
        assertNull(service.getLocalPicture())
        assertNull(service.getExistingLocalPicture())
        val file = File.createTempFile("pic", ".jpg").apply { writeBytes(byteArrayOf(1)); deleteOnExit() }
        service.setLocalPicture(file.absolutePath)
        assertEquals(file.absolutePath, store.getString("local_profile_picture"))
        assertEquals(file.absolutePath, service.getExistingLocalPicture())
        service.setLocalPicture("/data/user/0/space.einundzwanzig.meetup/cache/gone.jpg")
        assertEquals("/data/user/0/space.einundzwanzig.meetup/cache/gone.jpg", service.getLocalPicture())
        assertNull(service.getExistingLocalPicture())
    }

    @Test
    fun `concurrent lookups of the same npub share one query per relay`() = runTest {
        val service = service()
        relays.add(r1, profile(SECRET1, "{\"name\":\"alice\"}", age = 10))
        relays.silent += r2 // keeps the first lookup in flight until the timeout
        val first = async { service.fetchDisplayName(alice) }
        val second = async { service.fetchDisplayName(alice) }
        assertEquals("alice", first.await())
        assertEquals("alice", second.await())
        assertEquals(1, relays.reqs.count { it.first == r1 })
        assertEquals(1, relays.reqs.count { it.first == r2 })
    }

    @Test
    fun `queued single lookups whose callers are gone are dropped before they take a permit`() = runTest {
        val service = service()
        relays.silent += r1
        relays.silent += r2 // every lookup runs until the timeouts
        val cap = DefaultNostrProfileService.MAX_PARALLEL_NAME_LOOKUPS
        val keys = (1..cap + 6).map { it.toString(16).padStart(64, '0') }
        fun queried(hex: String) = relays.reqs.any { DartJson.encode(it.second[2]).contains(hex) }

        // `cap` lookups hold the permits, 6 wait for one.
        val running = keys.take(cap).map { async { service.fetchDisplayName(it) } }
        val queuedCallers = keys.drop(cap).map { async { service.fetchDisplayName(it) } }
        testScheduler.runCurrent()
        assertEquals(keys.take(cap), keys.filter(::queried))

        // The callers of 4 queued lookups go away; the remaining 2 are next in line.
        queuedCallers.take(4).forEach { it.cancel() }
        testScheduler.runCurrent()
        running.forEach { assertNull(it.await()) }
        testScheduler.runCurrent()
        assertTrue(queried(keys[cap + 4]) && queried(keys[cap + 5]))
        assertTrue(keys.subList(cap, cap + 4).none(::queried))
        queuedCallers.drop(4).forEach { assertNull(it.await()) }

        // Dropped lookups cached nothing: a later caller queries again.
        assertNull(service.fetchDisplayName(keys[cap]))
        assertTrue(queried(keys[cap]))
    }

    @Test
    fun `overlapping batch calls keep each other's names and a failed write does not throw`() = runTest {
        val service = service()
        relays.add(r1, profile(SECRET1, "{\"name\":\"Alice\"}", age = 10), profile(SECRET2, "{\"name\":\"Bob\"}", age = 10))
        relays.silent += r2 // both calls stay in their network phase until the timeout
        val a = async { service.fetchDisplayNames(listOf(alice)) }
        val b = async { service.fetchDisplayNames(listOf(bob)) }
        assertEquals(mapOf(alice to "Alice"), a.await())
        assertEquals(mapOf(bob to "Bob"), b.await())
        val stored = DartJson.decode(store.getString("nostr_display_names_v1")!!).jsonObject
        assertEquals(setOf(alice, bob), stored.keys)

        val failing = object : KeyValueStore by store {
            override suspend fun setString(key: String, value: String): Boolean =
                if (key == "nostr_display_names_v1") throw java.io.IOException("disk full") else store.setString(key, value)
        }
        val carol = pubOf(SECRET3)
        relays.add(r1, profile(SECRET3, "{\"name\":\"Carol\"}", age = 10))
        assertEquals(mapOf(carol to "Carol"), service(failing).fetchDisplayNames(listOf(carol)))
    }
}
