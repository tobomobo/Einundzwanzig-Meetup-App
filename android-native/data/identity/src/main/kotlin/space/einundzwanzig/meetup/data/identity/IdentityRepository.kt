package space.einundzwanzig.meetup.data.identity

/**
 * Identity part of the full app reset (spec 01 §8.9). The home/settings reset calls it before
 * clearing the prefs (`KeyValueStore.clear()`), the badges and the portal session.
 */
interface IdentityRepository {
    /**
     * Disconnects NIP-46 (closes the live client), deletes the local key, the NIP-46 session key,
     * every vault wrap (a password wrap is a full copy of the key) and every identity recovery copy
     * of the Flutter migration (`legacy_esp/`, `legacy_backup/`, `legacy_recovered/` + identity
     * key name, spec 14 §4.8). Each step runs even if an earlier one failed.
     *
     * @return `true` if every step succeeded.
     */
    suspend fun wipeAll(): Boolean
}
