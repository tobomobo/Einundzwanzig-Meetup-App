package space.einundzwanzig.meetup.data.identity.nip46

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip44
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.TimeSource

// Test doubles shared by Nip46ClientTest and the service-level NIP-46 tests.

/** A relay that serves the test signer directly. */
internal class FakeLink(
    val url: String,
    private val onFrame: (FakeLink, String) -> Unit,
    private val throwOnEvent: Boolean = false,
) : Nip46RelayLink {
    val sentFrames = CopyOnWriteArrayList<String>()

    @Volatile
    var closed = false
    var beforeClose: CompletableDeferred<Unit>? = null
    private val lock = Any()
    private var frameListener: ((String) -> Unit)? = null
    private var doneListener: ((Throwable?) -> Unit)? = null
    private val buffered = ArrayList<String>()
    private var ended = false

    override fun listen(onFrame: (String) -> Unit, onDone: (Throwable?) -> Unit) {
        val pending = synchronized(lock) {
            frameListener = onFrame
            doneListener = onDone
            buffered.toList().also { buffered.clear() }
        }
        pending.forEach(onFrame)
    }

    override fun send(frame: String) {
        if (throwOnEvent && frame.startsWith("[\"EVENT\"")) throw IllegalStateException("Socket ist tot (Test)")
        sentFrames += frame
        onFrame(this, frame)
    }

    override suspend fun close() {
        closed = true
        beforeClose?.await()
        val done = synchronized(lock) {
            if (ended) return
            ended = true
            doneListener
        }
        done?.invoke(null)
    }

    fun push(event: JsonObject) = pushRaw(DartJson.encode(listOf("EVENT", "sub", event)))

    fun pushRaw(frame: String) {
        val listener = synchronized(lock) {
            if (ended) return
            frameListener ?: run {
                buffered += frame
                null
            }
        } ?: return
        listener(frame)
    }
}

/** Counterpart with a real key pair; decrypts every request and answers per [respond]. */
internal class FakeSigner {
    val keys: KeyPair = KeyPair.generate()
    val requests = CopyOnWriteArrayList<JsonObject>()
    val links = CopyOnWriteArrayList<FakeLink>()
    var respond: (JsonObject) -> JsonObject? = { r -> jsonObjectOf("id" to r["id"], "result" to "ack") }
    var failingRelays = setOf<String>()
    var deadAfterOpen = setOf<String>()
    var rejectingRelays = setOf<String>()
    var rejectReason = "rate-limited: slow down"
    var rejectionMark: TimeSource.Monotonic.ValueTimeMark? = null
    var answerFromAllRelays = false
    var acknowledge = true

    val pubkey: String get() = keys.publicKeyHex

    val opener = Nip46RelayOpener { url ->
        if (url in failingRelays) throw IllegalStateException("Relay $url ist unerreichbar (Test)")
        FakeLink(url, ::handleFrame, throwOnEvent = url in deadAfterOpen).also { links += it }
    }

    private fun handleFrame(link: FakeLink, frame: String) {
        val decoded = DartJson.decode(frame).jsonArray
        if (decoded[0].jsonPrimitive.content != "EVENT") return
        val event = decoded[1].jsonObject
        val eventId = event["id"]!!.jsonPrimitive.content
        if (link.url in rejectingRelays) {
            if (rejectionMark == null) rejectionMark = TimeSource.Monotonic.markNow()
            link.pushRaw(DartJson.encode(listOf("OK", eventId, false, rejectReason)))
            return
        }
        if (acknowledge) link.pushRaw(DartJson.encode(listOf("OK", eventId, true, "")))

        val clientPubkey = event["pubkey"]!!.jsonPrimitive.content
        val key = Nip44.conversationKey(keys.privateKeyHex, clientPubkey)
        val request = DartJson.decode(Nip44.decrypt(event["content"]!!.jsonPrimitive.content, key)).jsonObject
        requests += request
        val answer = respond(request) ?: return
        val targets = if (answerFromAllRelays) links.toList() else listOf(link)
        for (target in targets) target.push(wrap(answer, clientPubkey))
    }

    fun wrap(payload: JsonObject, toPubkey: String, signer: KeyPair = keys): JsonObject {
        val key = Nip44.conversationKey(signer.privateKeyHex, toPubkey)
        return jsonObjectOf(
            "kind" to 24133,
            "pubkey" to signer.publicKeyHex,
            "created_at" to System.currentTimeMillis() / 1000,
            "tags" to listOf(listOf("p", toPubkey)),
            "content" to Nip44.encrypt(DartJson.encode(payload), key),
            "sig" to "00".repeat(32),
        )
    }

    fun pushFromStranger(payload: JsonObject, toPubkey: String) {
        val stranger = KeyPair.generate()
        for (link in links) link.push(wrap(payload, toPubkey, stranger))
    }
}

