package space.einundzwanzig.meetup.data.identity.internal

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import android.app.Activity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.KeySecurity
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.crypto.Nip49
import space.einundzwanzig.meetup.data.identity.LocalEasyAuthException
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.easyauth.LocalEasyAuth
import space.einundzwanzig.meetup.data.identity.keys.LocalKeyVault
import space.einundzwanzig.meetup.data.identity.keys.NostrKeyService
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfService
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultLocalEasyAuth @Inject constructor(
    private val keyStore: SecureKeyStore,
    private val vault: LocalKeyVault,
    private val keys: NostrKeyService,
    private val signing: SigningService,
    private val profiles: UserProfileRepository,
    private val passkeys: PasskeyPrfService,
    @param:DefaultDispatcher private val cpu: CoroutineDispatcher,
) : LocalEasyAuth {

    override suspend fun register(nickname: String, password: String) {
        val name = nickname.dartTrim()
        if (name.isEmpty() || name == UserProfile.DEFAULT_NICKNAME) throw LocalEasyAuthException("Bitte einen Namen wählen.")
        if (password.length < MIN_PASSWORD_LENGTH) throw LocalEasyAuthException("Passwort muss mindestens 8 Zeichen haben.")

        // Key in memory, wrap computed, only then persist: an aborted scrypt must not leave a key
        // without the wrap the user just set a password for.
        val priv = KeyPair.generate().privateKeyHex
        // Not `secure`: the plaintext also lives in the keystore next to the wrap.
        val wrap = scrypt { Nip49.encrypt(priv, password, Nip49.DEFAULT_LOG_N, KeySecurity.UNKNOWN) }

        // New key → old passkey wraps belong to the previous identity.
        vault.clearPasskeyWrap()
        keys.importPrivHex(priv)
        signing.useLocalMode()
        val npub = keyStore.getNpub().orEmpty()
        vault.savePasswordWrap(wrap, npub)

        profiles.update {
            it.copy(
                nickname = name,
                nostrNpub = npub,
                hasNostrKey = true,
                isNostrVerified = true,
                // A real registration replaces the review demo identity.
                isReviewDemo = false,
            )
        }
    }

    override suspend fun addPasskeyWrap(activity: Activity, displayName: String) {
        val priv = keyStore.getPrivHex()
        val npub = keyStore.getNpub()
        if (priv.isNullOrEmpty() || npub.isNullOrEmpty()) throw LocalEasyAuthException("Kein lokaler Schlüssel vorhanden.")
        val pubHex = Nip19.decodeNpub(npub)
        val created = passkeys.register(
            activity = activity,
            userIdHex = pubHex,
            userName = displayName.dartTrim().ifEmpty { "21meetup" },
        )
        // log_n 8 is right here: the "password" is a 256-bit PRF secret, nothing to guess. This
        // wrap is therefore never exported.
        val wrap = scrypt { Nip49.encrypt(priv, created.prfSecretHex, PASSKEY_LOG_N, KeySecurity.UNKNOWN) }
        vault.savePasskeyWrap(wrap, created.credentialId)
    }

    override suspend fun unlockWithPassword(password: String) {
        val wrap = vault.getPasswordWrap()
        if (wrap.isNullOrEmpty()) throw LocalEasyAuthException("Kein gespeicherter Schlüssel.")
        val priv = scrypt { Nip49.decrypt(wrap, password) }
        keys.importPrivHex(priv)
        signing.useLocalMode()
    }

    override suspend fun unlockWithPasskey(activity: Activity) {
        val wrap = vault.getPasskeyWrap()
        val credId = vault.getPasskeyCredentialId()
        if (wrap.isNullOrEmpty() || credId.isNullOrEmpty()) throw LocalEasyAuthException("Kein Passkey hinterlegt.")
        val secret = passkeys.unlock(activity, listOf(credId))
        val priv = scrypt { Nip49.decrypt(wrap, secret.prfSecretHex) }
        keys.importPrivHex(priv)
        signing.useLocalMode()
    }

    private suspend fun <T> scrypt(block: () -> T): T = withContext(cpu) {
        try {
            block()
        } catch (e: OutOfMemoryError) {
            throw LocalEasyAuthException("Nicht genug Arbeitsspeicher für die Schlüssel-Verschlüsselung.")
        }
    }

    companion object {
        /** `kMinPasswordLength` (UTF-16 code units). */
        const val MIN_PASSWORD_LENGTH = 8

        /** scrypt cost of the passkey wrap. */
        const val PASSKEY_LOG_N = 8
    }
}
