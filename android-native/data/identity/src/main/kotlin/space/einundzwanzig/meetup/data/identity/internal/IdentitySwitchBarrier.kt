package space.einundzwanzig.meetup.data.identity.internal

import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.data.identity.IdentityChangeHook
import space.einundzwanzig.meetup.data.identity.IdentityStorageException
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys

@Singleton
internal class IdentitySwitchBarrier @Inject constructor(
    private val prefs: KeyValueStore,
    private val secure: SecureStore,
    // Deferred: the proofs implementation may itself depend on SigningService.
    private val hooks: Provider<Set<@JvmSuppressWildcards IdentityChangeHook>>,
) {
    suspend fun beforeChange(next: String?) {
        val previous = when (SigningMode.fromStored(prefs.getString(IdentityKeys.SIGNING_MODE))) {
            SigningMode.AMBER -> prefs.getString(IdentityKeys.AMBER_NPUB)
            SigningMode.NIP07 -> prefs.getString(IdentityKeys.NIP07_NPUB)
            SigningMode.NIP46 -> prefs.getString(IdentityKeys.NIP46_NPUB)
            SigningMode.LOCAL -> secure.readOrThrow(IdentityKeys.NPUB)
        }
        if (previous == next || previous.isNullOrEmpty() && next.isNullOrEmpty()) return
        val registered = hooks.get()
        // Fail closed until the app binds the owning proof domain. Never expose old proofs
        // under a different public key merely because an integration binding was omitted.
        if (registered.isEmpty()) throw IdentityStorageException("Identity-change cleanup is not configured.")
        registered.forEach { it.beforeIdentityChange(previous.orEmpty(), next) }
    }
}
