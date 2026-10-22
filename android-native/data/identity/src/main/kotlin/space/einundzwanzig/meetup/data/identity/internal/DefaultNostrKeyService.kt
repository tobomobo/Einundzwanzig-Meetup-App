package space.einundzwanzig.meetup.data.identity.internal

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.crypto.Nip49
import space.einundzwanzig.meetup.data.identity.KeyFormatException
import space.einundzwanzig.meetup.data.identity.keys.GeneratedKeys
import space.einundzwanzig.meetup.data.identity.keys.NostrKeyService
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultNostrKeyService @Inject constructor(
    private val keyStore: SecureKeyStore,
    @param:DefaultDispatcher private val cpu: CoroutineDispatcher,
) : NostrKeyService {

    internal var decryptKey: (String, String) -> String = Nip49::decrypt

    override suspend fun generateKeyPair(): GeneratedKeys {
        return store(KeyPair.generate().privateKeyHex)
    }

    override suspend fun importNsec(nsec: String): GeneratedKeys {
        val trimmed = nsec.dartTrim()
        if (!trimmed.startsWith("nsec1")) {
            throw KeyFormatException("Ungültiges Format. Der Key muss mit \"nsec1\" beginnen.", reason = space.einundzwanzig.meetup.data.identity.IdentityError.NSEC_PREFIX)
        }
        val hex = try {
            Nip19.decodeNsec(trimmed)
        } catch (e: Exception) {
            throw KeyFormatException("Ungültiger nsec Key: ${e.message ?: e::class.simpleName}", e)
        }
        return try {
            importPrivHex(hex)
        } catch (e: KeyFormatException) {
            throw KeyFormatException("Ungültiger nsec Key: ${e.message}", e)
        }
    }

    override suspend fun importPrivHex(privHex: String): GeneratedKeys {
        val normalized = privHex.dartTrim().lowercase()
        if (!HEX64.matches(normalized)) throw KeyFormatException("Ungültiger privater Schlüssel (hex).")
        return store(normalized)
    }

    override suspend fun importNcryptsec(ncryptsec: String, password: String): GeneratedKeys {
        val priv = withContext(cpu) { keyCrypto { decryptKey(ncryptsec.dartTrim(), password) } }
        return importPrivHex(priv)
    }

    private suspend fun store(privHex: String): GeneratedKeys {
        val pair = try {
            KeyPair.fromPrivateKeyHex(privHex)
        } catch (e: IllegalArgumentException) {
            throw KeyFormatException("Ungültiger privater Schlüssel (hex).", e)
        }
        val npub = Nip19.encodeNpub(pair.publicKeyHex)
        val nsec = Nip19.encodeNsec(privHex)
        keyStore.saveKeys(nsec = nsec, npub = npub, privHex = privHex)
        return GeneratedKeys(nsec = nsec, npub = npub, pubHex = pair.publicKeyHex)
    }

    private companion object {
        val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}
