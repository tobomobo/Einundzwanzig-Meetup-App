package space.einundzwanzig.meetup.data.proofs.internal

import space.einundzwanzig.meetup.data.identity.IdentityChangeHook
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityKeys
import javax.inject.Inject

/** Bind the proof generation before key persistence or signer-ready notifications. */
internal class ProofIdentityChangeHook @Inject constructor(
    private val store: KeyValueStore,
) : IdentityChangeHook {
    override suspend fun beforeIdentityChange(previousNpub: String, nextNpub: String?) {
        if (previousNpub == nextNpub || previousNpub.isBlank() && nextNpub.isNullOrBlank()) return
        check(store.edit {
            if (previousNpub.isNotBlank()) HumanityKeys.clearIdentityProofs(this)
            else ProofOwnership.advanceGeneration(this) // first key adopts unowned migrated/backup proofs
            ProofOwnership.bind(this, nextNpub)
        }) {
            "Identity switch blocked: proof generation could not be prepared."
        }
    }
}
