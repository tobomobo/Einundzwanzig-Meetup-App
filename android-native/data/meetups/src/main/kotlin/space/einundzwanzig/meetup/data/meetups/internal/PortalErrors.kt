package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.data.meetups.portal.PortalLoginFailure
import space.einundzwanzig.meetup.data.meetups.portal.PortalErrorKind

/** Dart `PortalApiService._errorFrom` and the fixed German portal messages (spec 09 §5.2–§5.3). */
internal object PortalErrors {
    const val NOT_LOGGED_IN = "Nicht angemeldet. Bitte zuerst mit dem Portal verbinden."
    const val NETWORK = "Netzwerkfehler. Bist du online?"
    const val NO_TOKEN = "Kein Token vom Portal erhalten."
    const val LOGIN_FAILED_PREFIX = PortalLoginFailure.PREFIX

    /** Message + kind for an error response with [status] and raw [body]. */
    fun errorFrom(status: Int, body: String): Pair<String, PortalErrorKind> {
        val decoded = runCatching { DartJson.decode(body) }.getOrNull()
        if (decoded is JsonObject) {
            val errors = decoded["errors"]
            if (errors is JsonObject) {
                val msgs = mutableListOf<String>()
                for (v in errors.values) {
                    if (v is JsonArray) v.forEach { msgs += it.dartToString() } else msgs += v.dartToString()
                }
                if (msgs.isNotEmpty()) return "${msgs.joinToString(" · ")} (HTTP $status)" to PortalErrorKind.SERVER_MESSAGE
            }
            val message = decoded["message"]
            if (message is JsonPrimitive && message.isString && message.content.isNotEmpty()) {
                return "${message.content} (HTTP $status)" to PortalErrorKind.SERVER_MESSAGE
            }
        }
        return when (status) {
            401 -> "Nicht authentifiziert. Bitte erneut anmelden." to PortalErrorKind.UNAUTHENTICATED
            403 -> "Keine Berechtigung (nur Ersteller oder Admin)." to PortalErrorKind.FORBIDDEN
            404 -> "Nicht gefunden." to PortalErrorKind.NOT_FOUND
            422 -> "Validierungsfehler. Bitte Eingaben prüfen." to PortalErrorKind.VALIDATION
            else -> "Fehler (HTTP $status)." to PortalErrorKind.HTTP
        }
    }
}
