package space.einundzwanzig.meetup.feature.chat

import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.profile.NostrProfileService
import javax.inject.Inject

/**
 * The small slice of identity and profile data the chat screen needs: the own pubkey (to tell
 * own messages apart), display names of senders, and the npub of an author. A seam so the
 * ViewModel tests do not have to fake the whole `SigningService` / `NostrProfileService`.
 */
interface ChatPeople {
    /** Own hex pubkey (Dart `SigningService.pubkeyHex()`), `null` without an identity. */
    suspend fun myPubkeyHex(): String?

    /** Display name of [pubkeyHex] (Dart `NostrProfileService.fetchDisplayName`), `null`/empty if unknown. */
    suspend fun displayName(pubkeyHex: String): String?

    /** NIP-19 npub of [pubkeyHex]; the raw value if encoding fails (Dart `_showAuthor`). */
    fun npubOf(pubkeyHex: String): String = try {
        Nip19.encodeNpub(pubkeyHex)
    } catch (_: Exception) {
        pubkeyHex
    }
}

internal class DefaultChatPeople @Inject constructor(
    private val signing: SigningService,
    private val profiles: NostrProfileService,
) : ChatPeople {
    override suspend fun myPubkeyHex(): String? = signing.pubkeyHex()

    override suspend fun displayName(pubkeyHex: String): String? = profiles.fetchDisplayName(pubkeyHex)
}
