package space.einundzwanzig.meetup.data.reputation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.DiagnosticLogger
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnectException
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.core.nostr.toJsonObject
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/** The shared spec vectors (`spec/fixtures/04-trust-reputation-vectors.json`). */
object Fixtures {
    val root: JsonObject by lazy {
        DartJson.decode(File("../../spec/fixtures/04-trust-reputation-vectors.json").readText()).jsonObject
    }

    operator fun get(key: String): JsonObject = root.getValue(key).jsonObject

    fun string(section: String, key: String): String = this[section].getValue(key).jsonPrimitive.content
}

internal const val SECRET1 = "0000000000000000000000000000000000000000000000000000000000000001"
internal const val SECRET2 = "0000000000000000000000000000000000000000000000000000000000000002"
internal val KEY1: KeyPair = KeyPair.fromPrivateKeyHex(SECRET1)
internal val KEY2: KeyPair = KeyPair.fromPrivateKeyHex(SECRET2)

/** created_at of the fixture events (2025-10-20T12:00:00Z). */
internal const val FIXTURE_SECONDS: Long = 1_760_961_600L

/** Settable clock. */
internal class FakeTime(var millis: Long = FIXTURE_SECONDS * 1000) : TimeSource {
    override fun nowMillis(): Long = millis
}

internal fun testLog() = DiagnosticLogger(timeSource = FakeTime())

/** Deterministic (zero aux) signature. */
internal fun signed(
    key: KeyPair = KEY1,
    kind: Int,
    content: String,
    tags: List<List<String>> = emptyList(),
    createdAt: Long = FIXTURE_SECONDS,
): NostrEvent = EventIds.sign(key, createdAt, kind, tags, content, ByteArray(32))

/** A badge that passes `isNostrSigned` (format check only). */
internal fun signedBadge(
    id: String,
    meetup: String,
    date: DartDateTime,
    signerNpub: String = "npub1signer",
    adminPubkey: String = "b".repeat(64),
    bound: Boolean = false,
    retroactive: Boolean = false,
    organizer: Boolean = false,
    claimTimestamp: Long = 0,
    claimByte: Char = 'd',
) = MeetupBadge(
    id = id,
    meetupName = meetup,
    date = date,
    iconPath = "",
    signerNpub = signerNpub,
    meetupEventId = "$meetup-$id",
    sig = "a".repeat(128),
    sigId = id.padEnd(64, '0').take(64),
    adminPubkey = adminPubkey,
    sigVersion = 2,
    claimSig = if (bound) claimByte.toString().repeat(128) else "",
    claimPubkey = if (bound) "e".repeat(64) else "",
    claimEventId = if (bound) "f".repeat(64) else "",
    claimTimestamp = claimTimestamp,
    isRetroactive = retroactive,
    isOrganizer = organizer,
)

/** An unsigned organizer marker. */
internal fun markerBadge(id: String, meetup: String, date: DartDateTime) = MeetupBadge(
    id = id,
    meetupName = meetup,
    date = date,
    iconPath = "",
    meetupEventId = "org-$id",
    isOrganizer = true,
)

/**
 * [SigningService] proxy implementing `canSign`, `pubkeyHex`, `npub` and `signEvent` with a
 * local key (zero aux, `created_at` from [time]).
 */
internal class FakeSigner(var keyPair: KeyPair? = KEY1, private val time: TimeSource = FakeTime()) {
    var failSigning: Exception? = null
    var mode: SigningMode = SigningMode.LOCAL

    /** `pubkeyHex()` override (`""` simulates the stored-npub-with-wrong-HRP quirk). */
    var pubkeyHexOverride: String? = null
    val signedEvents = CopyOnWriteArrayList<NostrEvent>()
    val signerReady = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    val service: SigningService = proxy { name, args ->
        when (name) {
            "canSign" -> keyPair != null
            "getMode" -> mode
            "getSignerReady" -> signerReady
            "pubkeyHex" -> pubkeyHexOverride ?: keyPair?.publicKeyHex
            "npub" -> keyPair?.npub
            "signEvent" -> {
                failSigning?.let { throw it }
                @Suppress("UNCHECKED_CAST")
                EventIds.sign(
                    keyPair ?: error("Kein Nostr-Key vorhanden."),
                    time.nowSeconds(),
                    args[0] as Int,
                    args[1] as List<List<String>>,
                    args[2] as String,
                    ByteArray(32),
                ).also { signedEvents += it }
            }
            else -> throw UnsupportedOperationException(name)
        }
    }
}

/** A dynamic proxy for [T] (suspend methods may return their value directly). */
internal inline fun <reified T> proxy(crossinline handler: (name: String, args: Array<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        when (method.name) {
            "toString" -> "Fake${T::class.java.simpleName}"
            "hashCode" -> 0
            "equals" -> false
            else -> handler(method.name, args ?: emptyArray())
        }
    } as T

/** Fixed profile. */
internal class FakeProfiles(var current: UserProfile = UserProfile()) : UserProfileRepository {
    private val state = MutableStateFlow(current)
    override suspend fun load(): UserProfile = current
    override suspend fun save(profile: UserProfile) {
        current = profile
        state.value = profile
    }
    override suspend fun update(transform: (UserProfile) -> UserProfile): UserProfile = transform(current).also { save(it) }
    override val profile: Flow<UserProfile> get() = state
}

/**
 * Scripted relays: REQs are answered with the stored events of that relay (raw objects, no
 * filtering here — `RelayClient` filters), then EOSE. EVENT frames are recorded. Relays not in
 * [events]/[reachable] refuse the connection.
 */
internal class FakeRelays {
    val events = HashMap<String, MutableList<JsonObject>>()
    val reachable = HashSet<String>()
    val reqs = CopyOnWriteArrayList<Pair<String, JsonArray>>()
    val published = CopyOnWriteArrayList<Pair<String, String>>()

    fun add(relay: String, vararg evs: JsonObject) {
        events.getOrPut(relay) { mutableListOf() } += evs
    }

    val connector = FakeRelayConnector { url ->
        if (url !in events && url !in reachable) throw RelayConnectException(url, java.io.IOException("refused"))
        FakeRelaySocket(url) { frame, socket ->
            val msg = DartJson.decode(frame) as JsonArray
            when (msg[0].jsonPrimitive.content) {
                "REQ" -> {
                    reqs += url to msg
                    val sub = msg[1].jsonPrimitive.content
                    for (e in events[url].orEmpty()) socket.push(listOf("EVENT", sub, e))
                    socket.push(listOf("EOSE", sub))
                }
                "EVENT" -> published += url to frame
            }
        }
    }
}

internal fun NostrEvent.json(): JsonObject = toJsonObject()


/**
 * Runs pending work including `backgroundScope` coroutines (`advanceUntilIdle` stops once no
 * foreground task is left) and lets virtual time pass the relay linger.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun TestScope.settle() {
    advanceTimeBy(30_000)
    runCurrent()
}
