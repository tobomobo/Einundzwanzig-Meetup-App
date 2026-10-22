package space.einundzwanzig.meetup.data.identity

import androidx.annotation.StringRes
import space.einundzwanzig.meetup.core.crypto.Nip49Exception
import space.einundzwanzig.meetup.data.identity.nip46.Nip46Exception
import space.einundzwanzig.meetup.data.identity.nip46.Nip46RemoteException
import space.einundzwanzig.meetup.data.identity.nip46.Nip46RelayRejectedException
import space.einundzwanzig.meetup.data.identity.nip46.Nip46TimeoutException

/** Localized, secret-safe message shared by profile and onboarding. No exception text is echoed. */
@get:StringRes
val Throwable.identityErrorMessage: Int
    get() = when (this) {
        is KeyOperationException -> reason.messageResource
        is KeyFormatException -> reason.messageResource
        is LocalEasyAuthException -> reason.messageResource
        is OutOfMemoryError -> R.string.native_identity_key_memory
        is Nip49Exception -> R.string.native_identity_key_encrypted
        is SecureStorageUnreadableException, is IdentityStorageException -> R.string.native_identity_storage
        is Nip46TimeoutException -> R.string.native_identity_bunker_timeout
        is Nip46RemoteException, is Nip46RelayRejectedException -> R.string.native_identity_bunker_rejected
        is Nip46Exception -> R.string.native_identity_bunker_failure
        is SigningException -> when (error) {
            SigningError.SignCancelled -> R.string.native_identity_sign_cancelled
            SigningError.AmberMissing -> R.string.native_identity_amber_missing
            SigningError.WrongAccount -> R.string.native_identity_wrong_account
            SigningError.NoPrivateKey -> R.string.native_identity_no_local_key
            is SigningError.SignerNoEvent -> R.string.native_identity_signer_no_event
            is SigningError.SignerKindChanged -> R.string.native_identity_signer_kind
            is SigningError.SignerContentChanged -> R.string.native_identity_signer_content
            is SigningError.SignerBadSig -> R.string.native_identity_signer_signature
            is SigningError.SignerUnreadable, is SigningError.AmberError -> R.string.native_identity_signer_unreadable
            SigningError.BunkerNoAnswer -> R.string.native_identity_bunker_timeout
            is SigningError.BunkerRejected -> R.string.native_identity_bunker_rejected
            is SigningError.BunkerFailure -> R.string.native_identity_bunker_failure
            is SigningError.ModeNoKey -> R.string.native_identity_signer_no_key
            SigningError.NoBunkerSession -> R.string.native_identity_no_session
            SigningError.Nip07WebOnly -> R.string.native_identity_web_only
            null -> R.string.native_identity_generic
        }
        else -> R.string.native_identity_generic
    }

private val IdentityError.messageResource: Int
    get() = when (this) {
        IdentityError.INVALID_KEY -> R.string.native_identity_key_invalid
        IdentityError.NSEC_PREFIX -> R.string.native_identity_key_prefix
        IdentityError.ENCRYPTED_KEY -> R.string.native_identity_key_encrypted
        IdentityError.MEMORY -> R.string.native_identity_key_memory
        IdentityError.NAME_REQUIRED -> R.string.native_identity_name_required
        IdentityError.PASSWORD_SHORT -> R.string.native_identity_password_short
        IdentityError.NO_LOCAL_KEY -> R.string.native_identity_no_local_key
        IdentityError.NO_SAVED_KEY -> R.string.native_identity_no_saved_key
        IdentityError.NO_PASSKEY -> R.string.native_identity_no_passkey
    }
