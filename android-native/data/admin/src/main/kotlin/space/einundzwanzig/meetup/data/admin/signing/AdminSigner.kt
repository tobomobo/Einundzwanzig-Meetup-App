package space.einundzwanzig.meetup.data.admin.signing

import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.identity.SigningService

/**
 * The slice of the identity domain the admin domain needs (Dart `SigningService.canSign/npub/signEvent`
 * and `NostrService.getNpub`, which delegates to `SigningService.npub`).
 *
 * Hilt binds it to [SigningService]; tests pass a fake.
 */
interface AdminSigner {
    /** Dart `SigningService.canSign()`. */
    suspend fun canSign(): Boolean

    /** Dart `SigningService.npub()` / `NostrService.getNpub()`; `null` without identity. */
    suspend fun npub(): String?

    /** Dart `SigningService.signEvent(kind, tags, content)`; throws if signing fails. */
    suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent

    companion object {
        /** Adapter for code that has a [SigningService] but no Hilt graph. */
        fun of(signingService: SigningService): AdminSigner = object : AdminSigner {
            override suspend fun canSign(): Boolean = signingService.canSign()
            override suspend fun npub(): String? = signingService.npub()
            override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent =
                signingService.signEvent(kind, tags, content)
        }
    }
}
