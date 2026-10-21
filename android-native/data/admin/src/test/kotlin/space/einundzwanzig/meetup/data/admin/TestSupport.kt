package space.einundzwanzig.meetup.data.admin

import kotlinx.coroutines.test.TestScope
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistry
import space.einundzwanzig.meetup.data.admin.registry.AdminRelayFetch
import space.einundzwanzig.meetup.data.admin.signing.AdminSigner
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/** Settable clock; starts at the real wall clock so 10-digit `created_at` rules hold. */
class FakeClock(start: Long = System.currentTimeMillis()) : TimeSource {
    private val now = AtomicLong(start)
    override fun nowMillis(): Long = now.get()
    fun advance(ms: Long) { now.addAndGet(ms) }
}

/** [AdminSigner] backed by a local key pair (or none). */
class FakeAdminSigner(
    private val keyPair: KeyPair? = KeyPair.generate(),
    private val clock: TimeSource = FakeClock(),
) : AdminSigner {
    val signed = mutableListOf<NostrEvent>()
    override suspend fun canSign(): Boolean = keyPair != null
    override suspend fun npub(): String? = keyPair?.npub
    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent {
        val kp = keyPair ?: error("Kein Nostr-Key vorhanden.")
        return EventIds.sign(kp, clock.nowSeconds(), kind, tags, content).also { signed += it }
    }
}

val superHex: String = Nip19.decodeNpub(AdminConstants.SUPER_ADMIN_NPUB)

fun freshNpub(): String = KeyPair.generate().npub

fun prefs(vararg entries: Pair<String, Any>): InMemoryKeyValueStore = InMemoryKeyValueStore(
    entries.associate { (k, v) ->
        k to when (v) {
            is String -> PrefValue.StringValue(v)
            is Int -> PrefValue.IntValue(v.toLong())
            is Long -> PrefValue.IntValue(v)
            is Boolean -> PrefValue.BoolValue(v)
            else -> error("unsupported $v")
        }
    },
)

fun TestScope.registry(
    store: InMemoryKeyValueStore,
    fetch: AdminRelayFetch?,
    clock: TimeSource = FakeClock(),
    signer: AdminSigner = FakeAdminSigner(),
    connector: RelayConnector = FakeRelayConnector.unreachable(),
): AdminRegistry = AdminRegistry.create(
    store = store,
    relayClient = RelayClient(connector),
    signer = signer,
    timeSource = clock,
    backgroundScope = backgroundScope,
    relayFetch = fetch,
    zone = { ZoneOffset.UTC },
)
