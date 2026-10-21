package space.einundzwanzig.meetup.data.identity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.data.identity.nip55.DefaultExternalSignerBridge
import space.einundzwanzig.meetup.data.identity.nip55.Nip55Exception

/** The host protocol of [DefaultExternalSignerBridge] (one foreground request at a time). */
@RunWith(AndroidJUnit4::class)
class ExternalSignerBridgeTest {

    private val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))

    @Test
    fun publishesLaunchesAndDeliversTheResult() = runBlocking {
        val bridge = DefaultExternalSignerBridge().also { it.attachHost() }
        val request = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { bridge.launch(intent) }
        val pending = bridge.pendingLaunch.value!!
        assertEquals(false, pending.launched)
        assertTrue(bridge.onLaunched(pending.id))
        assertTrue(bridge.pendingLaunch.value!!.launched)
        // A second onLaunched for the same request does not launch twice.
        assertEquals(false, bridge.onLaunched(pending.id))

        // A second request while the first is running is "busy".
        try {
            bridge.launch(intent)
            throw AssertionError("expected busy")
        } catch (e: Nip55Exception) {
            assertEquals("busy", e.code)
        }

        val data = Intent().putExtra("result", "npub1x")
        // A result for another id is dropped.
        bridge.onResult(pending.id + 1, Activity.RESULT_CANCELED, null)
        assertTrue(request.isActive)
        bridge.onResult(pending.id, Activity.RESULT_OK, data)
        val result = request.await()
        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertEquals("npub1x", result.data!!.getStringExtra("result"))
        assertNull(bridge.pendingLaunch.value)
    }

    @Test
    fun launchFailureFailsTheRequest() = runBlocking {
        val bridge = DefaultExternalSignerBridge().also { it.attachHost() }
        val request = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { runCatching { bridge.launch(intent) } }
        bridge.onLaunchFailed(bridge.pendingLaunch.value!!.id, android.content.ActivityNotFoundException("kein Amber"))
        val error = request.await().exceptionOrNull() as Nip55Exception
        assertEquals("launch_failed", error.code)
        assertNull(bridge.pendingLaunch.value)
        // A stray result without a request is ignored.
        bridge.onResult(1, Activity.RESULT_OK, null)
    }

    @Test
    fun resultForANeverLaunchedRequestIsDropped() = runBlocking {
        // Process death while Amber was in front: the recreated Activity restores the old id and
        // a new request with that id is published before the stale result arrives.
        val bridge = DefaultExternalSignerBridge().also { it.attachHost() }
        val request = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { bridge.launch(intent) }
        val pending = bridge.pendingLaunch.value!!
        bridge.onResult(pending.id, Activity.RESULT_OK, Intent().putExtra("result", "stale"))
        assertTrue(request.isActive)
        assertEquals(pending, bridge.pendingLaunch.value)
        assertTrue(bridge.onLaunched(pending.id))
        bridge.onResult(pending.id, Activity.RESULT_OK, Intent().putExtra("result", "fresh"))
        assertEquals("fresh", request.await().data!!.getStringExtra("result"))
    }

    @Test
    fun idsDoNotRepeatAcrossInstances() {
        val ids = (1..8).map {
            val bridge = DefaultExternalSignerBridge().also { b -> b.attachHost() }
            runBlocking {
                val r = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { runCatching { bridge.launch(intent) } }
                val id = bridge.pendingLaunch.value!!.id
                r.cancel()
                id
            }
        }
        assertTrue(ids.all { it > 0 })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun withoutHostTheRequestFailsInsteadOfBlockingTheSlot() = runTest {
        val bridge = DefaultExternalSignerBridge()
        val error = runCatching { bridge.launch(intent) }.exceptionOrNull() as Nip55Exception
        assertEquals("cancelled", error.code)
        assertNull(bridge.pendingLaunch.value)

        // A host that attaches within the grace period (configuration change) still gets it.
        val waiting = async { bridge.launch(intent) }
        testScheduler.runCurrent()
        assertNull(bridge.pendingLaunch.value)
        val host = bridge.attachHost()
        testScheduler.runCurrent()
        val pending = bridge.pendingLaunch.value!!
        assertTrue(bridge.onLaunched(pending.id))
        bridge.onResult(pending.id, Activity.RESULT_OK, Intent().putExtra("result", "ok"))
        assertEquals("ok", waiting.await().data!!.getStringExtra("result"))

        // Detached again: back to failing fast; double close is harmless.
        host.close()
        host.close()
        val again = runCatching { bridge.launch(intent) }.exceptionOrNull() as Nip55Exception
        assertEquals("cancelled", again.code)
    }

    @Test
    fun abandonedLaunchedRequestStaysReservedUntilItsResult() = runBlocking {
        val bridge = DefaultExternalSignerBridge().also { it.attachHost() }
        val first = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { bridge.launch(intent) }
        val a = bridge.pendingLaunch.value!!
        assertTrue(bridge.onLaunched(a.id))
        // The caller gives up while Amber is still in front.
        first.cancel()
        assertTrue(first.isCancelled)

        // No new request may be launched on top of Amber's pending answer.
        try {
            bridge.launch(intent)
            throw AssertionError("expected busy")
        } catch (e: Nip55Exception) {
            assertEquals("busy", e.code)
        }

        // Amber answers the abandoned request: dropped, and the bridge is free again.
        bridge.onResult(a.id, Activity.RESULT_OK, Intent().putExtra("result", "plaintext-of-A"))
        assertNull(bridge.pendingLaunch.value)

        val second = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { bridge.launch(intent) }
        val b = bridge.pendingLaunch.value!!
        assertTrue(b.id != a.id)
        assertTrue(bridge.onLaunched(b.id))
        // A duplicate late answer for A never completes B.
        bridge.onResult(a.id, Activity.RESULT_OK, Intent().putExtra("result", "plaintext-of-A"))
        assertTrue(second.isActive)
        bridge.onResult(b.id, Activity.RESULT_OK, Intent().putExtra("result", "ciphertext-of-B"))
        assertEquals("ciphertext-of-B", second.await().data!!.getStringExtra("result"))
    }

    @Test
    fun requestCancelledBeforeLaunchIsReleasedAndNeverLaunched() = runBlocking {
        val bridge = DefaultExternalSignerBridge().also { it.attachHost() }
        val first = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { bridge.launch(intent) }
        val a = bridge.pendingLaunch.value!!
        first.cancel()
        assertNull(bridge.pendingLaunch.value)
        // The host saw the stale request: it must not launch it.
        assertEquals(false, bridge.onLaunched(a.id))
        val second = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { runCatching { bridge.launch(intent) } }
        val b = bridge.pendingLaunch.value!!
        bridge.onLaunchFailed(b.id, android.content.ActivityNotFoundException("kein Amber"))
        assertEquals("launch_failed", (second.await().exceptionOrNull() as Nip55Exception).code)
    }

    @Test
    fun publishedRequestIsReleasedWhenTheHostLeavesBeforeLaunching() = runTest {
        val bridge = DefaultExternalSignerBridge()
        val host = bridge.attachHost()
        val waiting = async { runCatching { bridge.launch(intent) } }
        testScheduler.runCurrent()
        val stale = bridge.pendingLaunch.value!!
        // The Activity stops right after the request was published, before it launched it.
        host.close()
        testScheduler.advanceTimeBy(DefaultExternalSignerBridge.HOST_WAIT_MS - 1)
        testScheduler.runCurrent()
        assertTrue(waiting.isActive)
        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()
        assertEquals("cancelled", (waiting.await().exceptionOrNull() as Nip55Exception).code)
        assertNull(bridge.pendingLaunch.value)

        // The slot is free; the returning Activity never launches the stale request.
        val again = bridge.attachHost()
        assertEquals(false, bridge.onLaunched(stale.id))
        val next = async { bridge.launch(intent) }
        testScheduler.runCurrent()
        val pending = bridge.pendingLaunch.value!!
        assertTrue(pending.id != stale.id)
        assertTrue(bridge.onLaunched(pending.id))
        bridge.onResult(pending.id, Activity.RESULT_OK, Intent().putExtra("result", "ok"))
        assertEquals("ok", next.await().data!!.getStringExtra("result"))
        again.close()
    }

    @Test
    fun briefHostLossBeforeLaunchKeepsTheRequest() = runTest {
        val bridge = DefaultExternalSignerBridge()
        val host = bridge.attachHost()
        val waiting = async { bridge.launch(intent) }
        testScheduler.runCurrent()
        val pending = bridge.pendingLaunch.value!!
        // Configuration change: detach and re-attach within the grace period.
        host.close()
        testScheduler.advanceTimeBy(DefaultExternalSignerBridge.HOST_WAIT_MS / 2)
        testScheduler.runCurrent()
        bridge.attachHost()
        testScheduler.advanceTimeBy(DefaultExternalSignerBridge.HOST_WAIT_MS * 2)
        testScheduler.runCurrent()
        assertEquals(pending, bridge.pendingLaunch.value)
        assertTrue(bridge.onLaunched(pending.id))
        bridge.onResult(pending.id, Activity.RESULT_OK, Intent().putExtra("result", "ok"))
        assertEquals("ok", waiting.await().data!!.getStringExtra("result"))
    }
}
