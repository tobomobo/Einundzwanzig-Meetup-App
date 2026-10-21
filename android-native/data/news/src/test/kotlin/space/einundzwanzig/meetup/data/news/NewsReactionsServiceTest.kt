package space.einundzwanzig.meetup.data.news

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.data.identity.SigningCancelledException
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.KEY_LIKED_REACTIONS
import space.einundzwanzig.meetup.data.news.internal.DefaultNewsReactionsService

/** NIP-25 likes (§6.7, §8.7, §4 `news_liked_reactions`) and the read/write targets (§6.1). */
class NewsReactionsServiceTest {

    private val time = FakeTime()
    private val now get() = time.millis / 1000
    private val relays = FakeRelays()
    private val store = InMemoryKeyValueStore()
    private val signer = FakeSigner()
    private val lookup = FakeLookup(
        targets = listOf(
            "wss://nostr.einundzwanzig.space", "wss://relay.damus.io", "wss://nos.lol",
            "wss://relay.nostr.band", "wss://my.relay",
        ),
    )
    private val me = pubOf(SECRET1)
    private val author = pubOf(SECRET5)
    private val addr = "30023:$author:artikel"

    private fun TestScope.service(signing: SigningService = signer.service) = DefaultNewsReactionsService(
        lookup,
        RelayClient(relays.connector, UnconfinedTestDispatcher(testScheduler)),
        signing,
        store,
        testLog(),
        backgroundScope,
    )

    private fun reaction(secret: String, content: String, address: String = addr) =
        signed(secret, 7, content, listOf(listOf("a", address), listOf("k", "30023")), now - 5).json()

    @Test
    fun `tag layout of a like`() {
        assertEquals(
            listOf(listOf("a", addr), listOf("p", author), listOf("k", "30023")),
            NewsReactionsService.likeTags(addr, author),
        )
        assertEquals(listOf(listOf("a", addr), listOf("k", "30023")), NewsReactionsService.likeTags(addr, ""))
    }

    @Test
    fun `non-hex author gives no p tag`() = runTest {
        // Link-only article: Dart `authorPubkey` returns "//host/..." (spec §14 risk 12).
        val linkAddr = "https://einundzwanzig.space/artikel"
        val linkAuthor = NewsArticles.authorPubkey(
            NewsArticle(linkAddr, "", "t", "", "", "", 0, linkAddr),
        )
        assertEquals("//einundzwanzig.space/artikel", linkAuthor)
        assertEquals(listOf(listOf("a", linkAddr), listOf("k", "30023")), NewsReactionsService.likeTags(linkAddr, linkAuthor))
        assertEquals(listOf(listOf("a", addr), listOf("k", "30023")), NewsReactionsService.likeTags(addr, author.dropLast(1)))
        assertEquals(listOf(listOf("a", addr), listOf("k", "30023")), NewsReactionsService.likeTags(addr, author + "0"))

        relays.okReplies["wss://nos.lol"] = true to ""
        assertTrue(service().like(linkAddr, linkAuthor))
        assertEquals(listOf(listOf("a", linkAddr), listOf("k", "30023")), signer.signedEvents.single().tags)
    }

    @Test
    fun `overlapping likes sign and publish once`() = runTest {
        relays.okReplies["wss://nos.lol"] = true to ""
        relays.reachable += "wss://relay.damus.io" // never answers, keeps the first publish open
        val s = service()
        val first = async { s.like(addr, author) }
        val second = async { s.like(addr, author) }
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(1, signer.signCount)
        testScheduler.advanceTimeBy(NewsReactionsService.TIMEOUT.inWholeMilliseconds)
        testScheduler.runCurrent()
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, signer.signCount)
        assertEquals(1, relays.published.count { it.first == "wss://nos.lol" })
        assertEquals(listOf("$me|$addr"), store.getStringList(KEY_LIKED_REACTIONS))
        // Afterwards the local list answers; still nothing new is signed.
        assertTrue(s.like(addr, author))
        assertEquals(1, signer.signCount)
    }

    @Test
    fun `a slow signer for one article does not block likes of another`() = runTest {
        relays.okReplies["wss://nos.lol"] = true to ""
        val addrB = "30023:$author:anderer-artikel"
        // Article A's signing hangs (bunker waiting / Amber approval pending) until released.
        val releaseA = CompletableDeferred<Unit>()
        val slowForA = object : SigningService by signer.service {
            override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent {
                if (tags.first() == listOf("a", addr)) releaseA.await()
                return signer.service.signEvent(kind, tags, content)
            }
        }
        val s = service(slowForA)
        val likeA = async { s.like(addr, author) }
        testScheduler.runCurrent()
        assertFalse(likeA.isCompleted)

        // B signs, publishes and is remembered while A is still suspended in the signer.
        assertTrue(s.like(addrB, author))
        assertEquals(1, signer.signCount)
        assertEquals(listOf("$me|$addrB"), store.getStringList(KEY_LIKED_REACTIONS))
        assertFalse(likeA.isCompleted)

        releaseA.complete(Unit)
        assertTrue(likeA.await())
        assertEquals(2, signer.signCount)
        assertEquals(listOf("$me|$addrB", "$me|$addr"), store.getStringList(KEY_LIKED_REACTIONS))
    }

    @Test
    fun `positive contents - only a trimmed minus is negative`() {
        assertTrue(listOf("+", "", "🤙").all(NewsReactionsService::isPositive))
        assertFalse(NewsReactionsService.isPositive("-"))
        assertFalse(NewsReactionsService.isPositive(" - "))
    }

    @Test
    fun `write targets drop the read-only aggregator`() = runTest {
        val s = service()
        assertEquals(lookup.targets, s.readTargets())
        assertEquals(lookup.targets - "wss://relay.nostr.band", s.writeTargets())
    }

    @Test
    fun `likes count distinct positive pubkeys`() = runTest {
        relays.add(
            "wss://nos.lol",
            reaction(SECRET2, "+"), reaction(SECRET3, ""), reaction(SECRET4, "🤙"),
            reaction(SECRET5, "-"), reaction("0000000000000000000000000000000000000000000000000000000000000006", " - "),
        )
        // Same reactions echoed by another relay and the same pubkey twice: still counted once.
        relays.add("wss://relay.damus.io", reaction(SECRET2, "+"), reaction(SECRET2, "🤙"))
        val likes = service().fetchLikes(addr)
        assertEquals(ArticleLikes(3, false), likes)
        val req = relays.reqs.first().second
        assertEquals("likes", req[1].jsonPrimitive.content)
        assertEquals("{\"kinds\":[7],\"#a\":[\"$addr\"],\"limit\":500}", DartJson.encode(req[2]))
    }

    @Test
    fun `own reaction from relays sets mine without double counting`() = runTest {
        relays.add("wss://nos.lol", reaction(SECRET1, "+"), reaction(SECRET2, "+"))
        store.setStringList(KEY_LIKED_REACTIONS, listOf("$me|$addr"))
        assertEquals(ArticleLikes(2, true), service().fetchLikes(addr))
    }

    @Test
    fun `local-only own like adds one`() = runTest {
        relays.add("wss://nos.lol", reaction(SECRET2, "+"))
        store.setStringList(KEY_LIKED_REACTIONS, listOf("$me|$addr"))
        val s = service()
        assertEquals(ArticleLikes(2, true), s.fetchLikes(addr))
        assertTrue(s.hasLikedLocally(addr))
        assertFalse(s.hasLikedLocally("30023:$author:other"))
    }

    @Test
    fun `another identity does not inherit likes`() = runTest {
        store.setStringList(KEY_LIKED_REACTIONS, listOf("${pubOf(SECRET2)}|$addr"))
        val s = service()
        assertFalse(s.hasLikedLocally(addr))
        assertEquals(ArticleLikes(0, false), s.fetchLikes(addr))
        signer.keyPair = null
        assertFalse(s.hasLikedLocally(addr))
    }

    @Test
    fun `like publishes kind 7 to write targets and remembers it`() = runTest {
        relays.okReplies["wss://nos.lol"] = true to ""
        relays.okReplies["wss://relay.damus.io"] = false to "blocked"
        relays.reachable += "wss://relay.nostr.band"
        assertTrue(service().like(addr, author))
        val ev = signer.signedEvents.single()
        assertEquals(7, ev.kind)
        assertEquals("+", ev.content)
        assertEquals(listOf(listOf("a", addr), listOf("p", author), listOf("k", "30023")), ev.tags)
        assertEquals(setOf("wss://nos.lol", "wss://relay.damus.io"), relays.published.map { it.first }.toSet())
        assertTrue(relays.connector.connects.none { it == "wss://relay.nostr.band" })
        val frameTags = relays.published.first().second["tags"]!!.jsonArray
        assertEquals("[[\"a\",\"$addr\"],[\"p\",\"$author\"],[\"k\",\"30023\"]]", DartJson.encode(frameTags))
        assertEquals(listOf("$me|$addr"), store.getStringList(KEY_LIKED_REACTIONS))
    }

    @Test
    fun `already liked locally - no signing, no network`() = runTest {
        store.setStringList(KEY_LIKED_REACTIONS, listOf("$me|$addr"))
        assertTrue(service().like(addr, author))
        assertEquals(0, signer.signCount)
        assertTrue(relays.connector.connects.isEmpty())
    }

    @Test
    fun `no relay accepts - false and nothing remembered`() = runTest {
        relays.okReplies["wss://nos.lol"] = false to "nope"
        relays.reachable += "wss://relay.damus.io" // never answers
        val start = currentTime
        assertFalse(service().like(addr, author))
        assertEquals(NewsReactionsService.TIMEOUT.inWholeMilliseconds, currentTime - start)
        assertEquals(null, store.getStringList(KEY_LIKED_REACTIONS))
    }

    @Test
    fun `all relays answered - finishes early`() = runTest {
        lookup.targets = listOf("wss://nos.lol", "wss://dead.relay")
        relays.okReplies["wss://nos.lol"] = true to ""
        val start = currentTime
        assertTrue(service().like(addr, author))
        assertTrue(currentTime - start < NewsReactionsService.TIMEOUT.inWholeMilliseconds)
    }

    @Test
    fun `signer failure - false`() = runTest {
        signer.failSigning = SigningCancelledException()
        relays.okReplies["wss://nos.lol"] = true to ""
        assertFalse(service().like(addr, author))
        assertTrue(relays.published.isEmpty())
    }

    @Test
    fun `local list is capped at 500 and drops the oldest`() = runTest {
        store.setStringList(KEY_LIKED_REACTIONS, (0 until 500).map { "x|$it" })
        relays.okReplies["wss://nos.lol"] = true to ""
        assertTrue(service().like(addr, author))
        val list = store.getStringList(KEY_LIKED_REACTIONS)!!
        assertEquals(500, list.size)
        assertEquals("x|1", list.first())
        assertEquals("$me|$addr", list.last())
    }

    @Test
    fun `like cancelled after an OK is still remembered`() = runTest {
        relays.okReplies["wss://nos.lol"] = true to ""
        relays.reachable += "wss://relay.damus.io" // never answers, keeps the publish open
        val s = service()
        val caller = async { s.like(addr, author) }
        testScheduler.advanceTimeBy(1_000)
        assertTrue(relays.published.any { it.first == "wss://nos.lol" })
        caller.cancel()
        // advanceUntilIdle() ignores backgroundScope (the app scope here): drive the clock past
        // the 8 s budget so the publish ends on its own.
        testScheduler.advanceTimeBy(NewsReactionsService.TIMEOUT.inWholeMilliseconds)
        testScheduler.runCurrent()
        assertTrue(caller.isCancelled)
        assertTrue(runCatching { caller.await() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("$me|$addr"), store.getStringList(KEY_LIKED_REACTIONS))
        assertTrue(s.hasLikedLocally(addr))
    }
}
