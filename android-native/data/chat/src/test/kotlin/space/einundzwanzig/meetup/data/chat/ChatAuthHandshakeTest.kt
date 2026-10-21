package space.einundzwanzig.meetup.data.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.data.chat.internal.EventSigner
import space.einundzwanzig.meetup.data.chat.internal.Nip42Authenticator
import space.einundzwanzig.meetup.data.identity.SigningException
import kotlin.time.Duration.Companion.seconds

/**
 * Port of `test/chat_auth_handshake_test.dart` (spec 11 §10.1) against [Nip42Authenticator],
 * with virtual time and the same fake signed event.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatAuthHandshakeTest {

    private val deadline = 10.seconds

    private fun signed(id: String = "auth-id", challenge: String = "challenge") = NostrEvent(
        id = id,
        pubkey = "test-key",
        createdAt = 1792144000,
        kind = 22242,
        tags = listOf(listOf("relay", GROUP_RELAY), listOf("challenge", challenge)),
        content = "",
        sig = "test-sig",
    )

    private fun TestScope.start(ws: FakeRelaySocket, sign: EventSigner) =
        async { Nip42Authenticator(GROUP_RELAY, deadline, sign).authenticate(ws) {} }.also { runCurrent() }

    private fun FakeRelaySocket.sentArrays(): List<JsonArray> = sentJson.map { it as JsonArray }

    private fun challengeOf(tags: List<List<String>>) = tags.first { it.first() == "challenge" }[1]

    private fun chatWarnings() = AppLogger.entries.value.count { it.tag == "Chat" && it.level == "WARN" }

    @Test
    fun `slow signer releases the connection only after the matching AUTH-OK`() = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        val approval = CompletableDeferred<NostrEvent>()
        var calls = 0
        var requestedKind: Int? = null
        var requestedTags: List<List<String>>? = null
        var requestedContent: String? = null
        val connection = start(ws) { kind, tags, content ->
            calls++
            requestedKind = kind
            requestedTags = tags
            requestedContent = content
            approval.await()
        }
        ws.push(listOf("AUTH", "challenge"))
        runCurrent()
        advanceTimeBy(4.seconds)
        runCurrent()
        assertFalse("no three-second release", connection.isCompleted)
        assertTrue(ws.sent.isEmpty())
        ws.push(listOf("AUTH", "challenge"))
        runCurrent()
        assertEquals("no second signer prompt", 1, calls)
        assertEquals(22242, requestedKind)
        assertEquals(listOf(listOf("relay", GROUP_RELAY), listOf("challenge", "challenge")), requestedTags)
        assertEquals("", requestedContent)
        approval.complete(signed())
        runCurrent()
        assertEquals(1, ws.sent.size)
        assertEquals("AUTH", ws.sentArrays().single()[0].jsonPrimitive.content)
        assertFalse(connection.isCompleted)
        ws.push(listOf("OK", "other-id", true, ""))
        runCurrent()
        assertFalse(connection.isCompleted)
        ws.push(listOf("OK", "auth-id", true, ""))
        runCurrent()
        assertSame(ws, connection.await())
        ws.close()
    }

    @Test
    fun `without AUTH the connection is never released early`() = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        val connection = start(ws) { _, _, _ -> error("unused") }
        advanceTimeBy(4.seconds)
        runCurrent()
        assertFalse(connection.isCompleted)
        advanceTimeBy(6.seconds)
        runCurrent()
        assertNull(connection.await())
        assertTrue(ws.isClosed)
    }

    @Test
    fun `expired deadline discards a late signature`() = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        val approval = CompletableDeferred<NostrEvent>()
        val connection = start(ws) { _, _, _ -> approval.await() }
        ws.push(listOf("AUTH", "challenge"))
        runCurrent()
        advanceTimeBy(deadline)
        runCurrent()
        assertNull(connection.await())
        approval.complete(signed())
        runCurrent()
        assertTrue(ws.isClosed)
        assertTrue(ws.sent.isEmpty())
    }

    private fun lateRejectionAfterAbort(abort: String) = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        val approval = CompletableDeferred<NostrEvent>()
        var calls = 0
        val connection = start(ws) { _, _, _ ->
            calls++
            approval.await()
        }
        ws.push(listOf("AUTH", "challenge"))
        runCurrent()
        when (abort) {
            "deadline" -> {
                advanceTimeBy(deadline)
                runCurrent()
            }
            "changed challenge" -> {
                ws.push(listOf("AUTH", "changed"))
                runCurrent()
            }
            else -> {
                ws.close()
                runCurrent()
            }
        }
        assertNull(connection.await())
        assertTrue(ws.isClosed)
        val warnings = chatWarnings()
        approval.completeExceptionally(SigningException("user rejected"))
        runCurrent()
        assertEquals(warnings, chatWarnings())
        assertEquals(1, calls)
        assertTrue(ws.sent.isEmpty())
    }

    @Test
    fun `late signer rejection after abort logs no further warning (deadline)`() = lateRejectionAfterAbort("deadline")

    @Test
    fun `late signer rejection after abort logs no further warning (changed challenge)`() =
        lateRejectionAfterAbort("changed challenge")

    @Test
    fun `late signer rejection after abort logs no further warning (connection closed)`() =
        lateRejectionAfterAbort("connection closed")

    private fun missingOrRejectedOk(accepted: Boolean?) = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        val connection = start(ws) { _, _, _ -> signed() }
        ws.push(listOf("AUTH", "challenge"))
        runCurrent()
        if (accepted == false) {
            ws.push(listOf("OK", "auth-id", false, "denied"))
            runCurrent()
        } else {
            advanceTimeBy(deadline)
            runCurrent()
        }
        assertNull(connection.await())
        assertTrue(ws.isClosed)
    }

    @Test
    fun `rejected AUTH-OK stays failed`() = missingOrRejectedOk(false)

    @Test
    fun `missing AUTH-OK stays failed`() = missingOrRejectedOk(null)

    @Test
    fun `explicit signer rejection ends the authentication`() = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        val connection = start(ws) { _, _, _ -> throw SigningException("user rejected") }
        ws.push(listOf("AUTH", "challenge"))
        runCurrent()
        assertNull(connection.await())
        assertTrue(ws.sent.isEmpty())
        assertTrue(ws.isClosed)
    }

    @Test
    fun `a CancellationException leaked by the signer fails the handshake at once`() = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        val before = chatWarnings()
        // E.g. a TimeoutCancellationException of a withTimeout inside the signer.
        val connection = start(ws) { _, _, _ -> withTimeout(1.seconds) { awaitCancellation() } }
        ws.push(listOf("AUTH", "challenge"))
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(connection.isCompleted)
        assertNull(connection.await())
        assertEquals(1_001L, testScheduler.currentTime)
        assertEquals(before + 1, chatWarnings())
        assertTrue(ws.sent.isEmpty())
        assertTrue(ws.isClosed)
    }

    @Test
    fun `an Error thrown by the signer fails the handshake instead of crashing`() = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        val connection = start(ws) { _, _, _ -> throw AssertionError("signer bug") }
        ws.push(listOf("AUTH", "challenge"))
        runCurrent()
        assertTrue(connection.isCompleted)
        assertNull(connection.await())
        assertTrue(ws.isClosed)
    }

    @Test
    fun `changed challenge starts no parallel approval`() = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        val approval = CompletableDeferred<NostrEvent>()
        var calls = 0
        val connection = start(ws) { _, _, _ ->
            calls++
            approval.await()
        }
        ws.push(listOf("AUTH", "challenge"))
        runCurrent()
        ws.push(listOf("AUTH", "changed"))
        runCurrent()
        assertNull(connection.await())
        approval.complete(signed())
        runCurrent()
        assertEquals(1, calls)
        assertTrue(ws.sent.isEmpty())
    }

    @Test
    fun `renewed challenge after authentication stays on the open socket`() = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        var calls = 0
        val connection = start(ws) { _, tags, _ ->
            calls++
            signed(id = "auth-$calls", challenge = challengeOf(tags))
        }
        ws.push(listOf("AUTH", "challenge"))
        runCurrent()
        ws.push(listOf("OK", "auth-1", true, ""))
        runCurrent()
        assertSame(ws, connection.await())
        assertFalse(ws.isClosed)
        assertEquals(1, ws.sent.size)

        ws.push(listOf("AUTH", "next"))
        runCurrent()
        assertEquals(2, calls)
        assertEquals(2, ws.sent.size)
        assertEquals("AUTH", ws.sentArrays().last()[0].jsonPrimitive.content)
        assertEquals("auth-2", (ws.sentArrays().last()[1] as JsonObject)["id"]!!.jsonPrimitive.content)
        assertFalse(ws.isClosed)

        ws.push(listOf("AUTH", "next"))
        runCurrent()
        assertEquals(2, calls)
        assertEquals(2, ws.sent.size)

        ws.push(listOf("OK", "auth-2", false, "denied"))
        runCurrent()
        assertFalse(ws.isClosed)
        ws.close()
    }

    @Test
    fun `switch during renewal signs only the new challenge`() = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        val pending = CompletableDeferred<NostrEvent>()
        var calls = 0
        val connection = start(ws) { _, tags, _ ->
            calls++
            when (val challenge = challengeOf(tags)) {
                "renew-1" -> pending.await()
                else -> signed(id = if (challenge == "challenge") "auth-id" else "renew-2", challenge = challenge)
            }
        }
        ws.push(listOf("AUTH", "challenge"))
        runCurrent()
        ws.push(listOf("OK", "auth-id", true, ""))
        runCurrent()
        assertSame(ws, connection.await())
        assertEquals(1, calls)

        ws.push(listOf("AUTH", "renew-1"))
        runCurrent()
        assertEquals(2, calls)
        assertEquals(1, ws.sent.size)

        ws.push(listOf("AUTH", "renew-2"))
        runCurrent()
        assertEquals(3, calls)
        assertEquals(2, ws.sent.size)
        assertEquals("renew-2", (ws.sentArrays().last()[1] as JsonObject)["id"]!!.jsonPrimitive.content)

        pending.complete(signed(id = "renew-1", challenge = "renew-1"))
        runCurrent()
        assertEquals(3, calls)
        assertEquals(2, ws.sent.size)
        assertFalse(ws.isClosed)
        ws.close()
    }

    @Test
    fun `connection drop during approval discards the late result`() = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        val approval = CompletableDeferred<NostrEvent>()
        val connection = start(ws) { _, _, _ -> approval.await() }
        ws.push(listOf("AUTH", "challenge"))
        runCurrent()
        ws.close()
        runCurrent()
        assertNull(connection.await())
        approval.complete(signed())
        runCurrent()
        assertTrue(ws.sent.isEmpty())
    }

    // ---- native additions (§8.2 details) ----

    @Test
    fun `non-handshake frames are forwarded before and after authentication`() = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        val forwarded = mutableListOf<String>()
        val connection = async {
            Nip42Authenticator(GROUP_RELAY, deadline, { _, _, _ -> signed() })
                .authenticate(ws) { forwarded += it.toString() }
        }
        runCurrent()
        ws.pushFrame("not json")
        ws.pushFrame("[]")
        ws.push(listOf("NOTICE", "hello"))
        ws.push(listOf("AUTH", "challenge"))
        runCurrent()
        ws.push(listOf("OK", "auth-id", true, ""))
        runCurrent()
        assertSame(ws, connection.await())
        ws.push(listOf("OK", "auth-id", true, "duplicate"))
        ws.push(listOf("EOSE", "q"))
        assertEquals(listOf("[\"NOTICE\",\"hello\"]", "[\"EOSE\",\"q\"]"), forwarded)
        ws.close()
    }

    @Test
    fun `AUTH frame carries the signed event`() = runTest {
        val ws = FakeRelaySocket(GROUP_RELAY)
        start(ws) { _, _, _ -> signed() }
        ws.push(listOf("AUTH", "challenge"))
        runCurrent()
        assertEquals(
            "[\"AUTH\",{\"id\":\"auth-id\",\"pubkey\":\"test-key\",\"created_at\":1792144000,\"kind\":22242," +
                "\"tags\":[[\"relay\",\"wss://group.einundzwanzig.space\"],[\"challenge\",\"challenge\"]]," +
                "\"content\":\"\",\"sig\":\"test-sig\"}]",
            ws.sent.single(),
        )
        ws.close()
    }
}
