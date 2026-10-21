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
import space.einundzwanzig.meetup.data.identity.easyauth.PasskeyRegistration
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfResult
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
        if (password.length < LocalEasyAuth.MIN_PASSWORD_LENGTH) throw LocalEasyAuthException("Passwort muss mindestens 8 Zeichen haben.")

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

    override suspend fun passkeyRegistration(displayName: String): PasskeyRegistration {
        val npub = keyStore.getNpub()
        if (keyStore.getPrivHex().isNullOrEmpty() || npub.isNullOrEmpty()) throw LocalEasyAuthException(NO_LOCAL_KEY)
        return PasskeyRegistration(
            userIdHex = Nip19.decodeNpub(npub),
            userName = displayName.dartTrim().ifEmpty { "21meetup" },
        )
    }

    override suspend fun savePasskeyWrap(registration: PasskeyRegistration, result: PasskeyPrfResult) {
        val priv = keyStore.getPrivHex()
        val npub = keyStore.getNpub()
        if (priv.isNullOrEmpty() || npub.isNullOrEmpty()) throw LocalEasyAuthException(NO_LOCAL_KEY)
        // The key changed while the passkey dialog was open: the passkey belongs to the old user id.
        if (Nip19.decodeNpub(npub) != registration.userIdHex) throw LocalEasyAuthException(NO_LOCAL_KEY)
        // log_n 8 is right here: the "password" is a 256-bit PRF secret, nothing to guess. This
        // wrap is therefore never exported.
        val wrap = scrypt { Nip49.encrypt(priv, result.prfSecretHex, PASSKEY_LOG_N, KeySecurity.UNKNOWN) }
        vault.savePasskeyWrap(wrap, result.credentialId)
    }

    override suspend fun addPasskeyWrap(activity: Activity, displayName: String) {
        val registration = passkeyRegistration(displayName)
        val created = passkeys.register(activity = activity, userIdHex = registration.userIdHex, userName = registration.userName)
        savePasskeyWrap(registration, created)
    }

    override suspend fun unlockWithPassword(password: String) {
        val wrap = vault.getPasswordWrap()
        if (wrap.isNullOrEmpty()) throw LocalEasyAuthException("Kein gespeicherter Schlüssel.")
        val priv = scrypt { Nip49.decrypt(wrap, password) }
        keys.importPrivHex(priv)
        signing.useLocalMode()
    }

    override suspend fun passkeyCredentialIds(): List<String> {
        val wrap = vault.getPasskeyWrap()
        val credId = vault.getPasskeyCredentialId()
        if (wrap.isNullOrEmpty() || credId.isNullOrEmpty()) throw LocalEasyAuthException(NO_PASSKEY)
        return listOf(credId)
    }

    override suspend fun unlockWithPrfSecret(result: PasskeyPrfResult) {
        val wrap = vault.getPasskeyWrap()
        if (wrap.isNullOrEmpty()) throw LocalEasyAuthException(NO_PASSKEY)
        val priv = scrypt { Nip49.decrypt(wrap, result.prfSecretHex) }
        keys.importPrivHex(priv)
        signing.useLocalMode()
    }

    override suspend fun unlockWithPasskey(activity: Activity) {
        val secret = passkeys.unlock(activity, passkeyCredentialIds())
        unlockWithPrfSecret(secret)
    }

    private suspend fun <T> scrypt(block: () -> T): T = withContext(cpu) {
        try {
            block()
        } catch (e: OutOfMemoryError) {
            throw LocalEasyAuthException("Nicht genug Arbeitsspeicher für die Schlüssel-Verschlüsselung.")
        }
    }

    companion object {
        /** scrypt cost of the passkey wrap. */
        const val PASSKEY_LOG_N = 8

        const val NO_LOCAL_KEY = "Kein lokaler Schlüssel vorhanden."
        const val NO_PASSKEY = "Kein Passkey hinterlegt."
    }
}
