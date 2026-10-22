package space.einundzwanzig.meetup.data.badges.security

import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.badges.internal.SigningServiceBadgeSigner
import space.einundzwanzig.meetup.data.identity.SigningService

/**
 * The two signing calls the badge domain needs (Dart `SigningService.canSign` /
 * `SigningService.signEvent`). Inject it (bound to the app's [SigningService]) or wrap one with
 * [of]; tests implement it with a local key.
 */
interface BadgeSigner {
    /** Can the active identity sign right now (no network)? */
    suspend fun canSign(): Boolean

    /**
     * Signs an event with the active identity.
     *
     * @param createdAt the `created_at` to sign with (claims need `created_at == claimed_at`,
     *   spec 02 §7.4); `null` lets the signer pick. A signer that cannot honour it signs with its
     *   own value: callers that depend on it must compare [NostrEvent.createdAt].
     * @throws space.einundzwanzig.meetup.data.identity.SigningException with a user-facing reason.
     */
    suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String, createdAt: Long? = null): NostrEvent

    companion object {
        /** Adapts [signingService]. */
        fun of(signingService: SigningService): BadgeSigner = object : BadgeSigner {
            override suspend fun canSign(): Boolean = signingService.canSign()

            override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String, createdAt: Long?): NostrEvent =
                SigningServiceBadgeSigner(signingService).signEvent(kind, tags, content, createdAt)
        }
    }
}
