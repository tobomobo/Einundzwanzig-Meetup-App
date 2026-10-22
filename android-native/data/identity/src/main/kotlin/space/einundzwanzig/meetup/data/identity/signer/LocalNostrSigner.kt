package space.einundzwanzig.meetup.data.identity.signer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.crypto.Nip44
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.identity.SigningError
import space.einundzwanzig.meetup.data.identity.SigningException
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore

/** The app holds the key (Dart `LocalNostrSigner`). */
internal open class LocalNostrSigner(
    private val keyStore: SecureKeyStore,
    private val timeSource: TimeSource,
    private val cpu: CoroutineDispatcher,
) : NostrSigner {

    /** Keystore npub decoded; `null` if missing or undecodable, `""` for a wrong HRP (Dart quirk). */
    override suspend fun pubkeyHex(): String? {
        val npub = keyStore.getNpub()
        if (npub.isNullOrEmpty()) return null
        return try {
            Nip19.decodeNpub(npub)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent {
        val priv = requirePriv()
        return withContext(cpu) {
            EventIds.sign(KeyPair.fromPrivateKeyHex(priv), timeSource.nowSeconds(), kind, tags, content)
        }
    }

    override suspend fun nip44Encrypt(peerHex: String, plaintext: String): String {
        val key = conversationKey(peerHex)
        return withContext(cpu) { Nip44.encrypt(plaintext, key) }
    }

    override suspend fun nip44Decrypt(peerHex: String, payload: String): String {
        val key = conversationKey(peerHex)
        return withContext(cpu) { Nip44.decrypt(payload, key) }
    }

    private suspend fun conversationKey(peerHex: String): ByteArray {
        val priv = requirePriv()
        return withContext(cpu) { Nip44.conversationKey(priv, peerHex) }
    }

    private suspend fun requirePriv(): String {
        val priv = keyStore.getPrivHex()
        if (priv.isNullOrEmpty()) throw SigningException("Kein privater Schlüssel vorhanden.", error = SigningError.NoPrivateKey)
        return priv
    }
}
