package space.einundzwanzig.meetup.data.identity.internal

import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.keys.LocalKeyVault
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultLocalKeyVault @Inject constructor(
    private val secure: SecureStore,
    private val keyStore: SecureKeyStore,
) : LocalKeyVault {

    override suspend fun savePasswordWrap(ncryptsec: String, npub: String) {
        keyStore.ensureMigrated()
        secure.writeAllOrThrow(
            linkedMapOf(
                IdentityKeys.EASYAUTH_PASSWORD_NCRYPTSEC to ncryptsec,
                IdentityKeys.EASYAUTH_PASSWORD_WRAP_NPUB to npub,
            ),
        )
    }

    override suspend fun getPasswordWrap(): String? {
        keyStore.ensureMigrated()
        return secure.readOrThrow(IdentityKeys.EASYAUTH_PASSWORD_NCRYPTSEC)
    }

    override suspend fun getPasswordWrapNpub(): String? {
        keyStore.ensureMigrated()
        return secure.readOrThrow(IdentityKeys.EASYAUTH_PASSWORD_WRAP_NPUB)
    }

    override suspend fun passwordWrapMatchesNpub(npub: String): Boolean {
        val wrap = getPasswordWrap()
        if (wrap.isNullOrEmpty()) return false
        val bound = getPasswordWrapNpub()
        return !bound.isNullOrEmpty() && bound == npub
    }

    override suspend fun savePasskeyWrap(ncryptsec: String, credentialId: String) {
        keyStore.ensureMigrated()
        secure.writeAllOrThrow(
            linkedMapOf(
                IdentityKeys.EASYAUTH_PASSKEY_NCRYPTSEC to ncryptsec,
                IdentityKeys.EASYAUTH_PASSKEY_CREDENTIAL_ID to credentialId,
            ),
        )
    }

    override suspend fun getPasskeyWrap(): String? {
        keyStore.ensureMigrated()
        return secure.readOrThrow(IdentityKeys.EASYAUTH_PASSKEY_NCRYPTSEC)
    }

    override suspend fun getPasskeyCredentialId(): String? {
        keyStore.ensureMigrated()
        return secure.readOrThrow(IdentityKeys.EASYAUTH_PASSKEY_CREDENTIAL_ID)
    }

    override suspend fun hasPasskeyWrap(): Boolean =
        !getPasskeyWrap().isNullOrEmpty() && !getPasskeyCredentialId().isNullOrEmpty()

    override suspend fun clearPasskeyWrap() {
        keyStore.ensureMigrated()
        secure.deleteOrThrow(IdentityKeys.EASYAUTH_PASSKEY_NCRYPTSEC)
        secure.deleteOrThrow(IdentityKeys.EASYAUTH_PASSKEY_CREDENTIAL_ID)
    }

    override suspend fun clearAll() {
        keyStore.ensureMigrated()
        secure.deleteOrThrow(IdentityKeys.EASYAUTH_PASSWORD_NCRYPTSEC)
        secure.deleteOrThrow(IdentityKeys.EASYAUTH_PASSWORD_WRAP_NPUB)
        secure.deleteOrThrow(IdentityKeys.EASYAUTH_PASSKEY_NCRYPTSEC)
        secure.deleteOrThrow(IdentityKeys.EASYAUTH_PASSKEY_CREDENTIAL_ID)
    }
}
