package space.einundzwanzig.meetup.data.identity.signer

import space.einundzwanzig.meetup.core.nostr.NostrEvent

/**
 * One signing backend (Dart `NostrSigner`): local key, Amber, remote signer or the web-only
 * extension stub. The app never talks to a signer directly; it goes through
 * [space.einundzwanzig.meetup.data.identity.SigningService], which resolves the active one.
 */
interface NostrSigner {
    /** Hex pubkey of the identity this signer signs for, or `null`. */
    suspend fun pubkeyHex(): String?

    /**
     * Signs an event. `kind`/`content` of the result are the caller's values (verified equal for
     * external signers); `tags`/`created_at`/`id`/`sig` come from the signer.
     *
     * @throws space.einundzwanzig.meetup.data.identity.SigningException with a user-facing reason.
     */
    suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent

    /** NIP-44 v2 encryption to [peerHex]. Signers that cannot do it throw [UnsupportedOperationException]. */
    suspend fun nip44Encrypt(peerHex: String, plaintext: String): String =
        throw UnsupportedOperationException("${this::class.simpleName} kann NIP-44 nicht.")

    /** NIP-44 v2 decryption from [peerHex]. Signers that cannot do it throw [UnsupportedOperationException]. */
    suspend fun nip44Decrypt(peerHex: String, payload: String): String =
        throw UnsupportedOperationException("${this::class.simpleName} kann NIP-44 nicht.")
}
