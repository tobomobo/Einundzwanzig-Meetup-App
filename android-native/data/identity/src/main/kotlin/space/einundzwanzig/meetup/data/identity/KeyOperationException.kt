package space.einundzwanzig.meetup.data.identity

/** Stable UI reasons; exception messages are never used to classify user input. */
enum class IdentityError {
    INVALID_KEY, NSEC_PREFIX, ENCRYPTED_KEY, MEMORY, NAME_REQUIRED, PASSWORD_SHORT,
    NO_LOCAL_KEY, NO_SAVED_KEY, NO_PASSKEY,
}

class KeyOperationException(val reason: IdentityError, cause: Throwable? = null) :
    SigningException("Key operation failed: $reason", cause)
