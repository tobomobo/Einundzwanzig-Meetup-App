package space.einundzwanzig.meetup.data.reputation

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.data.reputation.internal.DefaultPublishThrottleStore
import space.einundzwanzig.meetup.data.reputation.publish.PublishThrottleState
import space.einundzwanzig.meetup.data.trust.reputation.RelayReputationMath

/** Spec 04 §10.4 `PublishThrottleTest`. */
class PublishThrottleTest {

    private val min = ReputationProtocol.MIN_PUBLISH_INTERVAL_MS
    private val pk = KEY1.publicKeyHex

    @Test
    fun publishContentHash16Vectors() {
        val rows = Fixtures["hashes"].getValue("publishContentHash16").jsonArray.map { it.jsonObject }
        for (row in rows) {
            val p = row.getValue("input").jsonPrimitive.content.split("|").map { it.toInt() }
            assertEquals(row.getValue("hex16").jsonPrimitive.content, RelayReputationMath.changeHash(p[0], p[1], p[2], p[3]))
        }
    }

    @Test
    fun fiveMinuteWindow() {
        val state = PublishThrottleState(lastPublishMillis = 1_000_000, lastHash = "old", lastPubkeyHex = pk)
        assertFalse(state.shouldPublish(1_000_000 + min - 1, "new", pk, min))
        assertTrue(state.shouldPublish(1_000_000 + min, "new", pk, min))
    }

    @Test
    fun unchangedHashSuppressesForTheSameIdentity() {
        val state = PublishThrottleState(lastPublishMillis = 0, lastHash = "h", lastPubkeyHex = pk)
        assertFalse(state.shouldPublish(10 * min, "h", pk, min))
        assertTrue(state.shouldPublish(10 * min, "h2", pk, min))
    }

    @Test
    fun anotherIdentityIsNeverThrottled() {
        val state = PublishThrottleState(lastPublishMillis = 1_000_000, lastHash = "h", lastPubkeyHex = KEY2.publicKeyHex)
        assertTrue(state.shouldPublish(1_000_001, "h", pk, min))
    }

    @Test
    fun migratedStateWithoutIdentityKeepsTheIntervalButNotTheHash() {
        val state = PublishThrottleState(lastPublishMillis = 1_000_000, lastHash = "h", lastPubkeyHex = null)
        assertFalse(state.shouldPublish(1_000_001, "h", pk, min))
        assertTrue(state.shouldPublish(1_000_000 + min, "h", pk, min))
    }

    @Test
    fun emptyStorePublishes() {
        assertTrue(PublishThrottleState(0, "", null).shouldPublish(min, "h", pk, min))
    }

    @Test
    fun storeUsesFlutterKeysAndTypes() = runTest {
        val prefs = InMemoryKeyValueStore()
        val store = DefaultPublishThrottleStore(prefs)
        assertEquals(PublishThrottleState(0, "", null), store.read())

        store.save(1_760_961_600_123L, "08e47cd91958e564", pk)
        assertEquals(PrefValue.IntValue(1_760_961_600_123L), prefs.get("reputation_last_publish"))
        assertEquals(PrefValue.StringValue("08e47cd91958e564"), prefs.get("reputation_last_hash"))
        assertEquals(PrefValue.StringValue(pk), prefs.get("reputation_last_pubkey"))
        assertEquals(PublishThrottleState(1_760_961_600_123L, "08e47cd91958e564", pk), store.read())

        store.clear()
        assertNull(prefs.get("reputation_last_publish"))
        assertEquals(PublishThrottleState(0, "", null), store.read())
    }

    @Test
    fun migratedFlutterValuesAreRead() = runTest {
        val prefs = InMemoryKeyValueStore(
            mapOf(
                "reputation_last_publish" to PrefValue.IntValue(42L),
                "reputation_last_hash" to PrefValue.StringValue("abc"),
            ),
        )
        assertEquals(PublishThrottleState(42L, "abc", null), DefaultPublishThrottleStore(prefs).read())
    }
}
