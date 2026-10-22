package space.einundzwanzig.meetup.data.reputation.publish

/**
 * Persistence of the publish throttle (Dart `_shouldPublish` / `_savePublishStatus`) in the
 * shared `KeyValueStore`: `reputation_last_publish` (Long ms), `reputation_last_hash`
 * (16 hex), plus the native-only `reputation_last_pubkey`.
 */
interface PublishThrottleStore {

    /** Snapshot of the stored state (absent keys: `0`, `""`, `null`). */
    suspend fun read(): PublishThrottleState

    /** Records a publish by [pubkeyHex] at [millis] with change hash [hash]. */
    suspend fun save(millis: Long, hash: String, pubkeyHex: String)

    /** Forgets the last publish (the next non-forced publish goes out). */
    suspend fun clear()
}

/**
 * Stored throttle state.
 *
 * @property lastPublishMillis `reputation_last_publish`, `0` if absent.
 * @property lastHash `reputation_last_hash`, `""` if absent.
 * @property lastPubkeyHex `reputation_last_pubkey`, `null` if absent (values migrated from
 *   Flutter have none).
 */
data class PublishThrottleState(
    val lastPublishMillis: Long,
    val lastHash: String,
    val lastPubkeyHex: String?,
) {
    /**
     * Dart `_shouldPublish`: at least [minIntervalMillis] since the last publish AND the hash
     * changed. Native additions (spec 04 §4 FIX): the last publish of **another** identity is
     * ignored entirely (no interval, no hash), and one without recorded identity (migrated
     * from Flutter) never counts as "unchanged".
     */
    fun shouldPublish(nowMillis: Long, currentHash: String, pubkeyHex: String, minIntervalMillis: Long): Boolean {
        if (lastPubkeyHex != null && lastPubkeyHex != pubkeyHex) return true
        if (nowMillis - lastPublishMillis < minIntervalMillis) return false
        if (currentHash == lastHash && lastPubkeyHex == pubkeyHex) return false
        return true
    }
}
