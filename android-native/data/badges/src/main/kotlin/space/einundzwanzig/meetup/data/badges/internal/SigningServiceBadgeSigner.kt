package space.einundzwanzig.meetup.data.badges.internal

import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.badges.security.BadgeSigner
import space.einundzwanzig.meetup.data.identity.SigningService
import javax.inject.Inject

/** [BadgeSigner] backed by the app's [SigningService]. */
internal class SigningServiceBadgeSigner @Inject constructor(
    private val signingService: SigningService,
) : BadgeSigner {
    override suspend fun canSign(): Boolean = signingService.canSign()

    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent =
        signingService.signEvent(kind, tags, content)
}
