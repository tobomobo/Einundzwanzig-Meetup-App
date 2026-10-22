package space.einundzwanzig.meetup.data.identity

import space.einundzwanzig.meetup.data.identity.signer.SignerActor

/**
 * Every signer failure the app shows to the user (Dart `SigningException`). [message] is the
 * user-facing German text of the Flutter app. [error] is the machine-readable reason with its
 * arguments, so features can show the localized `err*` string (spec 01 §8.10) without parsing
 * German text; `null` uses a generic localized message rather than exposing exception text.
 */
open class SigningException(
    override val message: String,
    cause: Throwable? = null,
    /** The machine-readable reason, or `null`. */
    val error: SigningError? = null,
) : Exception(message, cause) {
    override fun toString(): String = message
}

/**
 * Machine-readable [SigningException] reasons. The comment of each case names the string
 * resource of spec 01 §8.10 and its arguments.
 */
sealed interface SigningError {
    /** `errSignCancelledAmber`. */
    data object SignCancelled : SigningError

    /** `errAmberMissing`. */
    data object AmberMissing : SigningError

    /** `errWrongAccount`. */
    data object WrongAccount : SigningError

    /** `errNoPrivateKey`. */
    data object NoPrivateKey : SigningError

    /** `errSignerNoEvent(actor)`. */
    data class SignerNoEvent(val actor: SignerActor) : SigningError

    /** `errSignerKindChanged(actor, got, want)`. */
    data class SignerKindChanged(val actor: SignerActor, val got: Long, val want: Int) : SigningError

    /** `errSignerContentChanged(actor)`. */
    data class SignerContentChanged(val actor: SignerActor) : SigningError

    /** `errSignerBadSig(actor)`. */
    data class SignerBadSig(val actor: SignerActor) : SigningError

    /** Amber's answer is not a JSON object (`errAmber(detail)`). */
    data class SignerUnreadable(val actor: SignerActor, val detail: String) : SigningError

    /** `errBunkerNoAnswer`. */
    data object BunkerNoAnswer : SigningError

    /** `errBunkerRejected(reason)`; [reason] is the signer's raw text. */
    data class BunkerRejected(val reason: String) : SigningError

    /** Another NIP-46 transport failure; [detail] is the `Nip46Exception` text. */
    data class BunkerFailure(val detail: String) : SigningError

    /** `errModeAmberNoKey` / `errModeNip07NoKey` / `errModeBunkerNoKey` by [mode]. */
    data class ModeNoKey(val mode: SigningMode) : SigningError

    /** `errNoBunkerSession`. */
    data object NoBunkerSession : SigningError

    /** `errNip07WebOnly`. */
    data object Nip07WebOnly : SigningError

    /** `errAmber(detail)` (ARB `errorAmber`); [detail] is the bridge's text or code. */
    data class AmberError(val detail: String) : SigningError
}

/** The user cancelled or rejected the request in the external signer. */
class SigningCancelledException : SigningException("Signatur in Amber abgebrochen.", error = SigningError.SignCancelled)

/** Amber is not installed. */
class SignerMissingException : SigningException("Amber (Nostr Signer) ist nicht installiert.", error = SigningError.AmberMissing)

/**
 * The external signer signed with another account than the connected one. Used for **every**
 * signer type (the Dart text names Amber regardless).
 */
class WrongAccountException :
    SigningException("Amber hat mit einem anderen Konto signiert als verbunden.", error = SigningError.WrongAccount)

/**
 * A secret exists in the secure store but cannot be read (Keystore failure, corrupt storage).
 * Never treat this as "no key": creating a new key would overwrite an identity that may become
 * readable again (Dart `resetOnError = false`).
 */
class SecureStorageUnreadableException(
    /** The secure-store key that failed. */
    val key: String,
    /** `true` if retrying cannot help. */
    val isPermanent: Boolean,
    cause: Throwable?,
) : SigningException("Der sichere Speicher ist gerade nicht lesbar ($key).", cause)

/** A write to the secure store or the prefs failed; nothing was changed. */
class IdentityStorageException(message: String) : SigningException(message)

/** Invalid `nsec` / private key input (Dart `FormatException`, without the type prefix). */
class KeyFormatException(message: String, cause: Throwable? = null, val reason: IdentityError = IdentityError.INVALID_KEY) : SigningException(message, cause)

/** User-facing failure of the password / passkey registration and unlock (Dart `LocalEasyAuthException`). */
class LocalEasyAuthException(override val message: String, val reason: IdentityError = IdentityError.NO_LOCAL_KEY) : Exception(message) {
    override fun toString(): String = message
}
