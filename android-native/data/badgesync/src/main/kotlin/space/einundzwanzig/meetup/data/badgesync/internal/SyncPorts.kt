package space.einundzwanzig.meetup.data.badgesync.internal

import kotlinx.coroutines.flow.Flow
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityProofRepository
import javax.inject.Inject

/**
 * What the sync needs from the identity domain: the narrow seam that tests fake.
 * Production: [SigningSyncIdentity] on `SigningService` + `SecureKeyStore`.
 */
internal interface SyncIdentity {
    /** `SigningService.signerReady` (Dart `onSignerReady`). */
    val signerReady: Flow<Unit>

    suspend fun npub(): String?

    suspend fun pubkeyHex(): String?

    suspend fun mode(): SigningMode

    /** `SecureKeyStore.getPrivHex()` (local key, for the settle agreement check). */
    suspend fun localPrivHex(): String?

    suspend fun canNip44(): Boolean

    suspend fun nip44Encrypt(peerHex: String, plaintext: String): String

    suspend fun nip44Decrypt(peerHex: String, payload: String): String

    suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent
}

internal class SigningSyncIdentity @Inject constructor(
    private val signing: SigningService,
    private val keys: SecureKeyStore,
) : SyncIdentity {
    override val signerReady: Flow<Unit> get() = signing.signerReady

    override suspend fun npub(): String? = signing.npub()

    override suspend fun pubkeyHex(): String? = signing.pubkeyHex()

    override suspend fun mode(): SigningMode = signing.getMode()

    override suspend fun localPrivHex(): String? = keys.getPrivHex()

    override suspend fun canNip44(): Boolean = signing.canNip44()

    override suspend fun nip44Encrypt(peerHex: String, plaintext: String): String =
        signing.nip44Encrypt(peerHex, plaintext)

    override suspend fun nip44Decrypt(peerHex: String, payload: String): String =
        signing.nip44Decrypt(peerHex, payload)

    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent =
        signing.signEvent(kind, tags, content)
}

/**
 * Clears the previous identity's proofs (`humanity_*` incl. `platform_proofs`).
 * Production: [HumanityProofsWiper] (also stops a running humanity search from writing).
 */
internal fun interface IdentityProofsWiper {
    /** @return `false` if the write failed. */
    suspend fun clear(): Boolean
}

internal class HumanityProofsWiper @Inject constructor(
    private val humanity: HumanityProofRepository,
) : IdentityProofsWiper {
    override suspend fun clear(): Boolean = humanity.clearForIdentitySwitch()
}
