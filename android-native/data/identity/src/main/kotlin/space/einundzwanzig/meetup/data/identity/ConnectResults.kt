package space.einundzwanzig.meetup.data.identity

import space.einundzwanzig.meetup.data.identity.nip46.Nip46Client

/** Outcome of [SigningService.connectAmber]. */
sealed interface AmberConnectResult {
    /** Connected; the pubkey and npub are stored and the mode is [SigningMode.AMBER]. */
    data class Success(val pubkeyHex: String, val npub: String) : AmberConnectResult

    /** Amber is not installed. */
    data object Missing : AmberConnectResult

    /** The user cancelled in Amber. */
    data object Cancelled : AmberConnectResult

    /** Anything else; [message] is the user-facing (German) reason. */
    data class Error(val message: String) : AmberConnectResult
}

/** Outcome of [SigningService.connectNip07]. Always [Missing] on Android (web only). */
sealed interface Nip07ConnectResult {
    /** Connected (web only). */
    data class Success(val pubkeyHex: String, val npub: String) : Nip07ConnectResult

    /** No extension (always on Android). */
    data object Missing : Nip07ConnectResult

    /** The user rejected in the extension. */
    data object Rejected : Nip07ConnectResult

    /** Anything else. */
    data class Error(val message: String) : Nip07ConnectResult
}

/** Outcome of a NIP-46 connection attempt. */
sealed interface Nip46ConnectResult {
    /** Connected; session persisted, mode is [SigningMode.NIP46]. [pubkeyHex] is the **user's** key. */
    data class Success(val pubkeyHex: String, val npub: String) : Nip46ConnectResult

    /** The signer did not answer in time (the most common case: the signer app sleeps). */
    data object Timeout : Nip46ConnectResult

    /** Anything else; [message] is the raw service text (spec 01 §8.10). */
    data class Error(val message: String) : Nip46ConnectResult
}

/**
 * A running `nostrconnect://` pairing (Dart `Nip46Pairing`). Show [uri] as QR code / text, then
 * call [SigningService.completeNip46Pairing]. Call [cancel] when the user aborts, otherwise the
 * relay sockets stay open. Once [SigningService.completeNip46Pairing] succeeded the client is the
 * live session: do **not** cancel it any more.
 */
class Nip46Pairing internal constructor(
    /** The `nostrconnect://` URI (contains the one-time secret, never the client key). */
    val uri: String,
    /** The one-time pairing secret (16 hex). */
    val secret: String,
    internal val clientSecretKeyHex: String,
    /** The relays announced in [uri]. */
    val relays: List<String>,
    /** The unpaired client that waits for the signer (internal: it holds the session key). */
    internal val client: Nip46Client,
) {
    /** Closes the client and its relay sockets. */
    suspend fun cancel() = client.close()

    override fun toString(): String = "Nip46Pairing(relays=$relays)"
}
