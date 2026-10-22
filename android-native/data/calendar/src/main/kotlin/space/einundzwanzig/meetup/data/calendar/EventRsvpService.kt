package space.einundzwanzig.meetup.data.calendar

import space.einundzwanzig.meetup.data.calendar.model.RsvpStatus

/**
 * Nostr RSVPs to NIP-52 events (kind 31925, Dart `EventRsvpService`, spec 10 §6.7). The d-tag is
 * derived from the event address, so a new answer replaces the old one (decline = replace).
 */
interface EventRsvpService {

    /** Deterministic d-tag of the RSVP to [eventAddress] (see `RsvpCodec.dTagFor`). */
    fun dTagFor(eventAddress: String): String

    /**
     * Signs and publishes the own answer to all RSVP relays in parallel (connect 4 s, `OK` wait
     * 7 s).
     *
     * @return `null` on success (≥ 1 relay answered `["OK", id, true]`), else the reason:
     *   [RsvpError.SigningFailed] (message = the exception text) or [RsvpError.NotAccepted].
     */
    suspend fun setStatus(eventAddress: String, eventAuthorPubkey: String, status: RsvpStatus): RsvpError?

    /**
     * Own answers (`["REQ","rsvp",{"kinds":[31925],"authors":[me],"limit":300}]`) from all RSVP
     * relays: per address the newest; `{}` without identity.
     */
    suspend fun loadMine(): Map<String, RsvpStatus>
}

/** Why an RSVP was not saved; show it inside **rsvpFailed**(msg). */
sealed interface RsvpError {
    /** German Flutter text (diagnostics / fallback display). */
    val message: String

    /** Signing failed (cancelled, signer missing, …): [message] = exception text. */
    data class SigningFailed(override val message: String, val cause: Throwable? = null) : RsvpError

    /** No relay confirmed the event (`Kein Relay hat die Antwort angenommen`). */
    data object NotAccepted : RsvpError {
        override val message: String = "Kein Relay hat die Antwort angenommen"
    }
}
