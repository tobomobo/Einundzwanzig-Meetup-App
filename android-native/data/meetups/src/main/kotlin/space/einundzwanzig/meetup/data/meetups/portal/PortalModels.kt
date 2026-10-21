package space.einundzwanzig.meetup.data.meetups.portal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.data.meetups.internal.dartInt
import space.einundzwanzig.meetup.data.meetups.internal.dartToString
import space.einundzwanzig.meetup.data.meetups.internal.isDartNull
import space.einundzwanzig.meetup.data.meetups.internal.stringOrEmpty

/** A meetup the user manages in the portal (`GET /api/my-meetups` `data[]`). */
data class PortalMeetup(
    val id: Int,
    val name: String,
    val slug: String,
    /** `is_leader == true` (strict boolean). */
    val isLeader: Boolean = false,
) {
    companion object {
        /** Dart `PortalMeetup.fromJson`: `null` unless `id` is an int. */
        fun fromJson(j: JsonObject): PortalMeetup? {
            val id = j["id"].dartInt() ?: return null
            return PortalMeetup(
                id = id,
                name = j.stringOrEmpty("name"),
                slug = j.stringOrEmpty("slug"),
                isLeader = (j["is_leader"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true,
            )
        }
    }
}

/**
 * An organizer's meetup date (`GET /api/my-meetup-events` `data[]`).
 *
 * @property start the RAW portal value (UTC without zone, e.g. `"2026-07-21 16:00:00"`); convert
 *   with [PortalTime.portalStartUtc]. The list sorts lexicographically on it.
 */
data class PortalMeetupEvent(
    val id: Int?,
    val meetupId: Int,
    val start: String,
    val location: String? = null,
    val description: String? = null,
    val link: String? = null,
) {
    companion object {
        /**
         * Dart `PortalMeetupEvent.fromJson`: `null` when `start` is missing. A non-int `id` reads
         * as `null` (Dart would throw and lose the whole list); non-string optional fields read as
         * `null` likewise.
         */
        fun fromJson(j: JsonObject): PortalMeetupEvent? {
            val start = j["start"].takeUnless { it.isDartNull } ?: return null
            return PortalMeetupEvent(
                id = j["id"].dartInt(),
                meetupId = j["meetup_id"].dartInt() ?: 0,
                start = start.dartToString(),
                location = j["location"].stringValue(),
                description = j["description"].stringValue(),
                link = j["link"].stringValue(),
            )
        }

        private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** Machine-readable reason of a failed [PortalResult], for localized messages. */
enum class PortalErrorKind {
    /** No token stored (`"Nicht angemeldet. …"`, status 401, no request sent). */
    NOT_LOGGED_IN,

    /** No HTTP response (`"Netzwerkfehler. Bist du online?"`, status 0). */
    NETWORK,

    /** The server sent `errors`/`message`; [PortalResult.error] carries its text + `(HTTP n)`. */
    SERVER_MESSAGE,

    /** 401 without a server message. */
    UNAUTHENTICATED,

    /** 403 without a server message. */
    FORBIDDEN,

    /** 404 without a server message. */
    NOT_FOUND,

    /** 422 without a server message. */
    VALIDATION,

    /** Any other status without a server message (`"Fehler (HTTP n)."`). */
    HTTP,

    /** Login answered 200/201 without a token (`"Kein Token vom Portal erhalten."`). */
    NO_TOKEN,

    /** Login threw (signer refused, network, …): `"Anmeldung fehlgeschlagen: <cause>"`, status 0. */
    LOGIN_FAILED,
}

/**
 * Result of a portal write or login (Dart `PortalResult`).
 *
 * @property statusCode HTTP status; `0` = no response.
 * @property error the user-visible German text exactly as the Flutter app showed it (`null` if [ok]).
 * @property errorKind machine-readable reason for localization (`null` if [ok]).
 * @property data decoded response JSON on success (`null` for 204 or a non-JSON body); for the
 *   login it is `body.user`.
 */
data class PortalResult(
    val ok: Boolean,
    val statusCode: Int,
    val error: String? = null,
    val errorKind: PortalErrorKind? = null,
    val data: JsonElement? = null,
)

/** Status + decoded body of [PortalApiService.rawGetStatus]; `status == 0` means no response. */
data class StatusBody(val status: Int, val body: JsonElement?)

/** A portal RSVP attendee (Dart `{npub, name}` map); either field may be empty. */
data class Attendee(val npub: String, val name: String)
