package space.einundzwanzig.meetup.data.proofs.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.identity.SigningService

/**
 * The slice of [SigningService] this domain uses. Keeps the implementations testable without a
 * full signing stack; Hilt provides it from the real [SigningService] (see `ProofsModule`).
 */
internal interface ProofsIdentity {
    /** `SigningService.signerReady`: fires whenever an identity became able to sign. */
    val signerReady: Flow<Unit> get() = emptyFlow()

    /** `SigningService.npub()` (also Dart `NostrService.getNpub()`). */
    suspend fun npub(): String?

    /** `SigningService.canSign()`. */
    suspend fun canSign(): Boolean

    /** `SigningService.signEvent(kind, tags, content)`. Throws on signer rejection or timeout. */
    suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent

    companion object {
        fun of(signing: SigningService): ProofsIdentity = object : ProofsIdentity {
            override val signerReady: Flow<Unit> get() = signing.signerReady
            override suspend fun npub(): String? = signing.npub()
            override suspend fun canSign(): Boolean = signing.canSign()
            override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent =
                signing.signEvent(kind, tags, content)
        }
    }
}
