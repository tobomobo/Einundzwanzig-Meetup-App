package space.einundzwanzig.meetup.data.identity.nip46

import kotlin.time.Duration

/**
 * Failure of the NIP-46 transport (Dart `Nip46Exception`). The signing service translates these
 * into [space.einundzwanzig.meetup.data.identity.SigningException]s for the rest of the app.
 */
open class Nip46Exception(override val message: String) : Exception(message) {
    override fun toString(): String = message
}

/** The signer answered with an `error` (typically: the user denied the request). */
class Nip46RemoteException(message: String) : Nip46Exception(message)

/**
 * No answer within the deadline (Dart `Nip46TimeoutException`).
 *
 * @property method the RPC method (`pairing` for [Nip46Client.awaitPairing]).
 * @property limit the time waited.
 * @property undecryptable events of this session that could not be decrypted (> 0 hints at a
 *   signer that answers with another key).
 * @property foreignSender events from another sender than the paired signer.
 * @property waitingForApproval the signer announced an `auth_url` approval for this request.
 */
class Nip46TimeoutException(
    val method: String,
    val limit: Duration,
    val undecryptable: Int,
    val foreignSender: Int = 0,
    val waitingForApproval: Boolean = false,
) : Nip46Exception("Der Signer hat nicht geantwortet.") {
    override fun toString(): String =
        "Nip46TimeoutException($method nach ${limit.inWholeSeconds}s ohne Antwort, $undecryptable unlesbare " +
            "Ereignisse, $foreignSender von fremdem Absender)"
}

/** Every relay rejected the request (`["OK", id, false, reason]`); the signer never saw it. */
class Nip46RelayRejectedException(
    /** The relay's reason (or `ohne Angabe`). */
    val reason: String,
) : Nip46Exception("Das Relay hat die Anfrage abgewiesen: $reason") {
    override fun toString(): String = "Nip46RelayRejectedException($reason)"
}
