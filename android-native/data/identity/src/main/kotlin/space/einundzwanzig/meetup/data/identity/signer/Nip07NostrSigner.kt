package space.einundzwanzig.meetup.data.identity.signer

import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.identity.SigningError
import space.einundzwanzig.meetup.data.identity.SigningException

/**
 * NIP-07 on Android: the mode can be restored from a web backup, but there is no browser
 * extension. Identity is readable; every signature / NIP-44 call throws.
 */
internal class Nip07NostrSigner(private val expectedPubkeyHex: String) : NostrSigner {
    override suspend fun pubkeyHex(): String = expectedPubkeyHex

    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent =
        throw SigningException(WEB_ONLY, error = SigningError.Nip07WebOnly)

    override suspend fun nip44Encrypt(peerHex: String, plaintext: String): String = throw SigningException(WEB_ONLY, error = SigningError.Nip07WebOnly)

    override suspend fun nip44Decrypt(peerHex: String, payload: String): String = throw SigningException(WEB_ONLY, error = SigningError.Nip07WebOnly)

    companion object {
        const val WEB_ONLY = "NIP-07 gibt es nur im Browser."
    }
}
