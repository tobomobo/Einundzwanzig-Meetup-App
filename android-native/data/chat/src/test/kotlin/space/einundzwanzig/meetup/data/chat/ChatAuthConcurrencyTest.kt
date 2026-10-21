package space.einundzwanzig.meetup.data.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.data.chat.internal.Nip42Authenticator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * [Nip42Authenticator] with the signer on a real pool thread (`Dispatchers.Default`) and frames
 * arriving on a separate "transport" thread, as on a device (spec 11 §8.2/§9.6). The virtual-time
 * tests in [ChatAuthHandshakeTest] run on one thread and cannot see these interleavings.
 *
 * Each test opens a window **inside** the signer's send of an AUTH frame and delivers a competing
 * frame on another thread during it: because "still current?" and the send are one critical
 * section, the competing abort / newer renewal must wait until the frame is out, so it can never
 * overtake a send that was already decided.
 */
class ChatAuthConcurrencyTest {

    private fun signed(id: String, challenge: String) = NostrEvent(
        id = id,
        pubkey = "test-key",
        createdAt = 1792144000,
        kind = 22242,
        tags = listOf(listOf("relay", GROUP_RELAY), listOf("challenge", challenge)),
        content = "",
        sig = "test-sig",
    )

    private fun challengeOf(tags: List<List<String>>) = tags.first { it.first() == "challenge" }[1]

    private fun FakeRelaySocket.authIds(): List<String> = sentJson.map { it as JsonArray }
        .filter { it[0].jsonPrimitive.content == "AUTH" }
        .map { (it[1] as JsonObject)["id"]!!.jsonPrimitive.content }

    @Test(timeout = 20_000)
    fun `a changed challenge on the transport thread cannot abort in the middle of the AUTH send`() {
        val abortedDuringSend = AtomicBoolean(false)
        val transportDone = CountDownLatch(1)
        val sendDone = CountDownLatch(1)
        lateinit var ws: FakeRelaySocket
        ws = FakeRelaySocket(GROUP_RELAY) { frame, _ ->
            if (!frame.startsWith("[\"AUTH\"")) return@FakeRelaySocket
            // Signer thread, inside send(): the relay changes the challenge right now.
            thread(name = "transport") {
                ws.push(listOf("AUTH", "changed"))
                transportDone.countDown()
            }
            // Without the atomic decision the abort would complete and authenticate() close the
            // socket within this window.
            Thread.sleep(WINDOW_MS)
            abortedDuringSend.set(ws.isClosed)
            sendDone.countDown()
        }
        val result = runBlocking(Dispatchers.Default) {
            val auth = Nip42Authenticator(GROUP_RELAY, 10.seconds, { _, tags, _ ->
                signed("auth-id", challengeOf(tags))
            })
            val connection = async { auth.authenticate(ws) {} }
            ws.push(listOf("AUTH", "challenge"))
            withTimeout(10.seconds) { connection.await() }
        }
        assertTrue(sendDone.await(5, TimeUnit.SECONDS))
        assertTrue(transportDone.await(5, TimeUnit.SECONDS))
        assertFalse("abort overtook a decided AUTH send", abortedDuringSend.get())
        // The changed challenge still aborts the handshake once the frame is out.
        assertNull(result)
        assertTrue(ws.isClosed)
        assertEquals(listOf("auth-id"), ws.authIds())
    }

    @Test(timeout = 20_000)
    fun `a newer renewal challenge cannot overtake the renewal send in progress`() {
        val renew2SentDuringRenew1 = AtomicBoolean(false)
        val renew1Done = CountDownLatch(1)
        val renew2Sent = CountDownLatch(1)
        val renewals = AtomicInteger()
        lateinit var ws: FakeRelaySocket
        ws = FakeRelaySocket(GROUP_RELAY) { frame, s ->
            when {
                "\"auth-id\"" in frame -> s.push(listOf("OK", "auth-id", true, ""))
                "\"renew-1\"" in frame -> {
                    thread(name = "transport") { ws.push(listOf("AUTH", "renew-2")) }
                    Thread.sleep(WINDOW_MS)
                    renew2SentDuringRenew1.set(ws.authIds().contains("renew-2"))
                    renew1Done.countDown()
                }
                "\"renew-2\"" in frame -> renew2Sent.countDown()
            }
        }
        val forwarded = mutableListOf<String>()
        runBlocking(Dispatchers.Default) {
            val auth = Nip42Authenticator(GROUP_RELAY, 10.seconds, { _, tags, _ ->
                val challenge = challengeOf(tags)
                if (challenge.startsWith("renew")) renewals.incrementAndGet()
                signed(if (challenge == "challenge") "auth-id" else challenge, challenge)
            })
            val connection = async { auth.authenticate(ws) { synchronized(forwarded) { forwarded += it.toString() } } }
            ws.push(listOf("AUTH", "challenge"))
            assertSame(ws, withTimeout(10.seconds) { connection.await() })
            ws.push(listOf("AUTH", "renew-1"))
        }
        assertTrue(renew1Done.await(5, TimeUnit.SECONDS))
        assertTrue(renew2Sent.await(5, TimeUnit.SECONDS))
        assertFalse("renew-2 overtook the renew-1 send", renew2SentDuringRenew1.get())
        assertEquals(listOf("auth-id", "renew-1", "renew-2"), ws.authIds())
        assertEquals(2, renewals.get())
        // The OK of the current renewal is consumed, never forwarded.
        ws.push(listOf("OK", "renew-2", true, ""))
        assertTrue(synchronized(forwarded) { forwarded.isEmpty() })
        ws.close()
    }

    private companion object {
        const val WINDOW_MS = 300L
    }
}
