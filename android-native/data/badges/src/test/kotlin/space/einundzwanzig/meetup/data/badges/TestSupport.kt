package space.einundzwanzig.meetup.data.badges

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.badges.security.BadgeSigner
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import java.io.File

/** The shared spec vectors (`spec/fixtures/02-badges-claim-vectors.json`). */
object Fixtures {
    val badges: JsonObject by lazy {
        DartJson.decode(File("../../spec/fixtures/02-badges-claim-vectors.json").readText()).jsonObject
    }

    operator fun get(key: String): JsonElement = badges.getValue(key)
}

/** Mutable clock. */
class FakeTime(var millis: Long = 1_760_000_000_000L) : TimeSource {
    override fun nowMillis(): Long = millis
}

/** A local-key [BadgeSigner] like `Event.from` (random aux), optionally forcing `created_at`. */
class TestSigner(
    val keyPair: KeyPair = KeyPair.generate(),
    private val time: TimeSource = FakeTime(),
    var canSign: Boolean = true,
    /** Overrides `created_at` per call (call index → value); `null` = clock. */
    var createdAtOverride: ((call: Int) -> Long?)? = null,
    /** Signs with a requested `created_at` (like a signer that supports it); `false` = picks its own. */
    var honourCreatedAt: Boolean = true,
) : BadgeSigner {
    var calls = 0
        private set

    override suspend fun canSign(): Boolean = canSign

    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String, createdAt: Long?): NostrEvent {
        val call = calls++
        val effective = createdAtOverride?.invoke(call) ?: (if (honourCreatedAt) createdAt else null) ?: time.nowSeconds()
        return EventIds.sign(keyPair, effective, kind, tags, content)
    }
}

/** A repository on in-memory stores with Flutter's mock semantics. */
class StoreHarness(
    val prefs: InMemoryKeyValueStore = InMemoryKeyValueStore(),
    val secure: InMemorySecureStore = InMemorySecureStore(),
    val time: FakeTime = FakeTime(),
) {
    val repo: BadgeRepository = BadgeRepository.create(prefs, secure, time, Dispatchers.Unconfined)
}
