package space.einundzwanzig.meetup.data.reputation.internal

import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.reputation.ReputationProtocol
import space.einundzwanzig.meetup.data.reputation.publish.PublishThrottleState
import space.einundzwanzig.meetup.data.reputation.publish.PublishThrottleStore
import javax.inject.Inject

/** [PublishThrottleStore] on the shared [KeyValueStore] with the Flutter key names. */
internal class DefaultPublishThrottleStore @Inject constructor(
    private val store: KeyValueStore,
) : PublishThrottleStore {

    override suspend fun read(): PublishThrottleState {
        // Three typed reads instead of getAll(): app_prefs holds large caches. The keys are
        // written together by edit {}; a torn read is harmless for a throttle.
        return PublishThrottleState(
            lastPublishMillis = store.getInt(ReputationProtocol.KEY_LAST_PUBLISH) ?: 0L,
            lastHash = store.getString(ReputationProtocol.KEY_LAST_HASH) ?: "",
            lastPubkeyHex = store.getString(ReputationProtocol.KEY_LAST_PUBKEY),
        )
    }

    override suspend fun save(millis: Long, hash: String, pubkeyHex: String) {
        store.edit {
            set(ReputationProtocol.KEY_LAST_PUBLISH, PrefValue.IntValue(millis))
            set(ReputationProtocol.KEY_LAST_HASH, PrefValue.StringValue(hash))
            set(ReputationProtocol.KEY_LAST_PUBKEY, PrefValue.StringValue(pubkeyHex))
        }
    }

    override suspend fun clear() {
        store.edit {
            remove(ReputationProtocol.KEY_LAST_PUBLISH)
            remove(ReputationProtocol.KEY_LAST_HASH)
            remove(ReputationProtocol.KEY_LAST_PUBKEY)
        }
    }
}
