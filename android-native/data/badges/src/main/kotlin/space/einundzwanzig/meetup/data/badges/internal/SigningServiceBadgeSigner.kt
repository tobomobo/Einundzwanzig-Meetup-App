package space.einundzwanzig.meetup.data.badges.internal

import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.badges.security.BadgeSigner
import space.einundzwanzig.meetup.data.identity.SigningService
import javax.inject.Inject

/**
 * [BadgeSigner] backed by the app's [SigningService].
 *
 * `SigningService.signEvent` has no `created_at` parameter yet (spec 02 §12 asks for one in
 * `:data:identity`), so `createdAt` cannot be forwarded: the signer picks its own and the claim
 * service's equality check plus one retry covers a second boundary. Forward it once the
 * identity API takes it.
 */
internal class SigningServiceBadgeSigner @Inject constructor(
    private val signingService: SigningService,
) : BadgeSigner {
    override suspend fun canSign(): Boolean = signingService.canSign()

    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String, createdAt: Long?): NostrEvent =
        signingService.signEvent(kind, tags, content)
}
