package space.einundzwanzig.meetup.data.identity

/**
 * Synchronous barrier before a different identity is persisted or signerReady is emitted.
 * Bind a proof cleanup implementation with @IntoSet. It must throw if cleanup fails; emitting
 * an asynchronous identity-change event is insufficient because publishing can race it.
 * [previousNpub] is empty when creating the first key (including after logout). Hooks must
 * bind pending proof writes to [nextNpub] then, while preserving unowned legacy/backup proofs.
 * A null [nextNpub] means logout and must fence writes for the departing key too.
 */
fun interface IdentityChangeHook {
    suspend fun beforeIdentityChange(previousNpub: String, nextNpub: String?)
}
