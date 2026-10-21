package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.measure
import space.einundzwanzig.meetup.core.crypto.CryptoRandom
import space.einundzwanzig.meetup.core.crypto.toHex
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.secure.SecureRead
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.core.network.getCapped
import space.einundzwanzig.meetup.core.network.sendJson
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.meetups.portal.Attendee
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService.Companion.RSVP_CACHE_PREFIX
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService.Companion.RSVP_CACHE_TS_PREFIX
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService.Companion.RSVP_CACHE_TTL_MS
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService.Companion.TOKEN_KEY
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService.Companion.TOKEN_NPUB_KEY
import space.einundzwanzig.meetup.data.meetups.portal.PortalErrorKind
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetupEvent
import space.einundzwanzig.meetup.data.meetups.portal.PortalResult
import space.einundzwanzig.meetup.data.meetups.portal.PortalUrls
import space.einundzwanzig.meetup.data.meetups.portal.StatusBody
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** OkHttp port of Dart `PortalApiService` (spec 09 §5, §6, §8.9, §8.11). */
@Singleton
internal class PortalApiServiceImpl @Inject constructor(
    private val client: OkHttpClient,
    private val secure: SecureStore,
    private val prefs: KeyValueStore,
    private val signing: SigningService,
    private val time: TimeSource,
    private val log: DiagnosticLog,
) : PortalApiService {

    // ---- token ---------------------------------------------------------------------------

    override suspend fun getToken(): String? = when (val r = secure.readChecked(TOKEN_KEY)) {
        is SecureRead.Present -> r.value
        SecureRead.Absent -> null
        is SecureRead.Unreadable -> {
            // Only failures are logged (this runs before every request).
            log.warn(TAG, "Portal-Token nicht lesbar, App gilt als abgemeldet: ${r.reason}", r.cause)
            null
        }
    }

    override suspend fun hasToken(): Boolean = getToken() != null

    override suspend fun storeToken(token: String) {
        if (!secure.write(TOKEN_KEY, token)) log.warn(TAG, "Portal-Token konnte nicht gespeichert werden.")
        // Bind the token to the active identity (mode-aware: Amber/NIP-46/local; Amber bug v1.3.1).
        val ownNpub = runCatching { signing.npub() }.getOrNull()
        if (!ownNpub.isNullOrEmpty() && !secure.write(TOKEN_NPUB_KEY, ownNpub)) {
            log.warn(TAG, "Portal-Token-Bindung konnte nicht gespeichert werden.")
        }
    }

    override suspend fun setManualToken(token: String) = storeToken(token.dartTrim())

    override suspend fun deleteToken(): Boolean {
        // A silently failed delete keeps the user logged in after logout: never swallow it.
        val ok = log.measure(TAG, "Portal-Token loeschen") {
            val a = secure.delete(TOKEN_KEY)
            val b = secure.delete(TOKEN_NPUB_KEY)
            if (!a || !b) throw IllegalStateException("SecureStore-Loeschen fehlgeschlagen")
            true
        }
        return ok == true
    }

    override suspend fun loginWithNostr(deviceName: String): PortalResult {
        try {
            val k1 = CryptoRandom.bytes(32).toHex()
            val signed = signing.signEvent(
                kind = PortalApiService.LOGIN_EVENT_KIND,
                tags = listOf(listOf("challenge", k1)),
                content = "",
            )
            val body = DartJson.encode(jsonObjectOf("k1" to k1, "event" to signed.toMap(), "device_name" to deviceName))
            val r = client.sendJson(
                "POST",
                "$API/mobile/token",
                body,
                headers = mapOf("Accept" to JSON, "Content-Type" to JSON),
                maxBytes = MAX_BYTES,
                timeout = PortalUrls.TIMEOUT,
            )
            if (r !is HttpResult.Response) return loginFailed(describe(r))
            if (r.status != 200 && r.status != 201) {
                val (msg, kind) = PortalErrors.errorFrom(r.status, r.text())
                return PortalResult(false, r.status, msg, kind)
            }
            val decoded = runCatching { DartJson.decode(r.text()) }.getOrElse { return loginFailed(it.toString()) }
            val token = ((decoded as? JsonObject)?.get("token") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (token.isNullOrEmpty()) {
                return PortalResult(false, r.status, PortalErrors.NO_TOKEN, PortalErrorKind.NO_TOKEN)
            }
            storeToken(token)
            return PortalResult(true, r.status, data = (decoded as JsonObject)["user"])
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return loginFailed(e.message ?: e.toString())
        }
    }

    private fun loginFailed(cause: String): PortalResult {
        log.warn(TAG, "loginWithNostr Fehler: $cause")
        return PortalResult(false, 0, PortalErrors.LOGIN_FAILED_PREFIX + cause, PortalErrorKind.LOGIN_FAILED)
    }

    override suspend fun logout() {
        val token = getToken()
        if (token != null) {
            // Best effort: the token expires server-side anyway.
            client.sendJson(
                "DELETE",
                "$API/mobile/token",
                null,
                headers = mapOf("Authorization" to "Bearer $token", "Accept" to JSON),
                maxBytes = MAX_BYTES,
                timeout = PortalUrls.TIMEOUT,
            )
        }
        deleteToken()
    }

    override suspend fun tokenMatchesCurrentKey(): Boolean {
        val t = getToken() ?: return false
        val cur = runCatching { signing.npub() }.getOrNull()
        if (cur.isNullOrEmpty()) return false

        val stored = secure.read(TOKEN_NPUB_KEY)
        if (stored != null && stored == cur) return true

        val r = client.getCapped(
            "$API/user",
            headers = mapOf("Accept" to JSON, "Authorization" to "Bearer $t"),
            maxBytes = MAX_BYTES,
            timeout = PortalUrls.TIMEOUT,
        )
        if (r !is HttpResult.Response) {
            log.diag(
                TAG,
                "Token-Prüfung: Portal nicht erreichbar (${describe(r)}) — " +
                    if (stored == null) "Token bleibt (keine Bindung, Prüfung folgt)" else "lokale Bindung widerspricht -> fremd",
            )
            return stored == null
        }
        if (r.status == 401) {
            log.diag(TAG, "Token-Prüfung: Portal meldet 401 — Token serverseitig ungültig.")
            return false
        }
        if (r.status != 200) {
            log.diag(TAG, "Token-Prüfung: Portal antwortet ${r.status} — keine Änderung.")
            return true
        }
        val body = runCatching { DartJson.decode(r.text()) }.getOrElse {
            // Dart threw inside the try -> same branch as offline.
            log.diag(TAG, "Token-Prüfung: Antwort nicht lesbar ($it).")
            return stored == null
        }
        val nostr = (body as? JsonObject)?.get("nostr")
        // Dart: (body['nostr'] as String?)?.trim() — a non-string value throws -> offline branch.
        val portalNpub: String? = when {
            nostr.isDartNull -> null
            nostr is JsonPrimitive && nostr.isString -> nostr.content.dartTrim()
            else -> {
                log.diag(TAG, "Token-Prüfung: unerwartetes nostr-Feld im Portal-Konto.")
                return stored == null
            }
        }
        if (portalNpub.isNullOrEmpty()) {
            log.diag(TAG, "Token-Prüfung: Portal-Konto ohne npub (manueller Token?) — zugelassen.")
            return true
        }
        if (portalNpub.dartLower() == cur.dartLower()) {
            secure.write(TOKEN_NPUB_KEY, cur)
            log.diag(TAG, "Token-Prüfung: Portal bestätigt aktuellen Schlüssel — Bindung repariert.")
            return true
        }
        log.warn(
            TAG,
            "Token-Prüfung: Portal-Konto gehört zu anderem npub (${portalNpub.take(12)}…) als die aktive Identität (${cur.take(12)}…).",
        )
        return false
    }

    override suspend fun getProfile(): JsonObject? {
        if (getToken() == null) return null
        val r = client.getCapped("$API/user", headers = authHeaders(), maxBytes = MAX_BYTES, timeout = PortalUrls.TIMEOUT)
        if (r !is HttpResult.Response) {
            log.warn(TAG, "getProfile Fehler: ${describe(r)}")
            return null
        }
        if (r.status == 401) {
            deleteToken()
            return null
        }
        if (r.status != 200) return null
        return runCatching { DartJson.decode(r.text()) as? JsonObject }.getOrNull()
    }

    // ---- helpers -------------------------------------------------------------------------

    private suspend fun authHeaders(): Map<String, String> {
        val token = getToken()
        return buildMap {
            put("Accept", JSON)
            put("Content-Type", JSON)
            if (token != null) put("Authorization", "Bearer $token")
        }
    }

    private suspend fun optAuthHeaders(): Map<String, String> {
        val token = getToken()
        return buildMap {
            put("Accept", JSON)
            if (token != null) put("Authorization", "Bearer $token")
        }
    }

    /** Dart `_get`: `null` on non-200, undecodable body or no response. */
    private suspend fun get(path: String): JsonElement? {
        val r = client.getCapped("$API$path", headers = optAuthHeaders(), maxBytes = MAX_BYTES, timeout = PortalUrls.TIMEOUT)
        if (r !is HttpResult.Response) {
            log.warn(TAG, "GET $path Fehler: ${describe(r)}")
            return null
        }
        if (r.status != 200) return null
        return runCatching { DartJson.decode(r.text()) }.getOrElse {
            log.warn(TAG, "GET $path Fehler: $it")
            null
        }
    }

    override suspend fun rawGet(path: String): JsonElement? = get(path)

    override suspend fun rawGetStatus(path: String): StatusBody {
        val r = client.getCapped("$API$path", headers = optAuthHeaders(), maxBytes = MAX_BYTES, timeout = PortalUrls.TIMEOUT)
        if (r !is HttpResult.Response) {
            log.warn(TAG, "GET $path Netzfehler", describe(r))
            return StatusBody(0, null)
        }
        if (r.status != 200) {
            log.warn(TAG, "GET $path -> HTTP ${r.status}")
            return StatusBody(r.status, null)
        }
        return runCatching { StatusBody(200, DartJson.decode(r.text())) }.getOrElse {
            log.warn(TAG, "GET $path Netzfehler", it)
            StatusBody(0, null)
        }
    }

    /** Dart `_write` (spec 09 §5.2). */
    private suspend fun write(method: String, url: String, payload: JsonObject?): PortalResult {
        if (getToken() == null) {
            return PortalResult(false, 401, PortalErrors.NOT_LOGGED_IN, PortalErrorKind.NOT_LOGGED_IN)
        }
        if (method !in WRITE_METHODS) return PortalResult(false, 0, "Unbekannte Methode.", PortalErrorKind.NETWORK)
        val r = client.sendJson(
            method,
            url,
            payload?.let { DartJson.encode(it) },
            headers = authHeaders(),
            maxBytes = MAX_BYTES,
            timeout = PortalUrls.TIMEOUT,
        )
        if (r !is HttpResult.Response) {
            log.warn(TAG, "$method $url Fehler: ${describe(r)}")
            return PortalResult(false, 0, PortalErrors.NETWORK, PortalErrorKind.NETWORK)
        }
        if (r.status == 200 || r.status == 201 || r.status == 204) {
            val data = runCatching { DartJson.decode(r.text()) }.getOrElse {
                if (r.status != 204) log.warn(TAG, "$method $url: HTTP ${r.status}, Rumpf nicht lesbar: $it")
                null
            }
            return PortalResult(true, r.status, data = data)
        }
        if (r.status == 401) deleteToken()
        val (msg, kind) = PortalErrors.errorFrom(r.status, r.text())
        return PortalResult(false, r.status, msg, kind)
    }

    // ---- organizer -----------------------------------------------------------------------

    override suspend fun getMyMeetups(): List<PortalMeetup> =
        dataList("/my-meetups", "getMyMeetups").mapNotNull(PortalMeetup::fromJson)

    override suspend fun getMyMeetupEvents(): List<PortalMeetupEvent> =
        dataList("/my-meetup-events", "getMyMeetupEvents").mapNotNull(PortalMeetupEvent::fromJson)

    /** `body.data` objects of an authenticated GET; `[]` on any failure. */
    private suspend fun dataList(path: String, label: String): List<JsonObject> {
        val r = client.getCapped("$API$path", headers = authHeaders(), maxBytes = MAX_BYTES, timeout = PortalUrls.TIMEOUT)
        if (r !is HttpResult.Response) {
            log.warn(TAG, "$label Fehler: ${describe(r)}")
            return emptyList()
        }
        if (r.status != 200) return emptyList()
        val body = runCatching { DartJson.decode(r.text()) }.getOrElse {
            log.warn(TAG, "$label Fehler: $it")
            return emptyList()
        }
        val list = (body as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return list.filterIsInstance<JsonObject>()
    }

    override suspend fun createMeetupEvent(
        meetupId: Int,
        start: String,
        location: String?,
        description: String?,
        link: String?,
        recurrenceType: String?,
        recurrenceDayOfWeek: String?,
        recurrenceDayPosition: String?,
        recurrenceInterval: Int?,
        recurrenceEndDate: String?,
    ): PortalResult {
        val payload = createPayload(
            meetupId, start, location, description, link,
            recurrenceType, recurrenceDayOfWeek, recurrenceDayPosition, recurrenceInterval, recurrenceEndDate,
        )
        return write("POST", "$API/meetup-events", payload)
    }

    override suspend fun updateMeetupEvent(
        eventId: Int,
        meetupId: Int?,
        start: String?,
        location: String?,
        description: String?,
        link: String?,
        recurrenceType: String?,
        recurrenceDayOfWeek: String?,
        recurrenceDayPosition: String?,
        recurrenceInterval: Int?,
        recurrenceEndDate: String?,
    ): PortalResult {
        val payload = updatePayload(
            meetupId, start, location, description, link,
            recurrenceType, recurrenceDayOfWeek, recurrenceDayPosition, recurrenceInterval, recurrenceEndDate,
        )
        return write("PATCH", "$API/meetup-events/$eventId", payload)
    }

    override suspend fun getMeetupLeaders(meetupId: Int): List<JsonObject> {
        val b = get("/meetup/$meetupId/leaders")
        val data = if (b is JsonObject) b["data"] else b
        return (data as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
    }

    override suspend fun addMeetupLeader(meetupId: Int, npub: String): PortalResult =
        write("POST", "$API/meetup/$meetupId/leaders", jsonObjectOf("npub" to npub))

    override suspend fun removeMeetupLeader(meetupId: Int, userId: Int): PortalResult =
        write("DELETE", "$API/meetup/$meetupId/leaders/$userId", null)

    // ---- public events + RSVP ------------------------------------------------------------

    override suspend fun getAllMeetupEvents(today: LocalDate): List<JsonObject> {
        val byId = LinkedHashMap<String, JsonObject>()
        var anonymous = 0
        for (date in eventDates(today)) {
            val body = get("/meetup-events/$date")
            val data = if (body is JsonObject) body["data"] else body
            if (data !is JsonArray) continue
            for (e in data.filterIsInstance<JsonObject>()) {
                // Dart: "${e['id'] ?? e.hashCode}" — an entry without id never collides.
                val key = e["id"].takeUnless { it.isDartNull }?.dartToString() ?: "\u0000${anonymous++}"
                byId[key] = e
            }
        }
        return byId.values.toList()
    }

    override suspend fun getRsvp(eventId: Int): JsonObject? {
        val body = get("/meetup-events/$eventId/rsvp") as? JsonObject ?: return null
        return body["data"] as? JsonObject ?: body
    }

    override suspend fun getRsvpCached(eventId: Int, forceRefresh: Boolean): JsonObject? {
        val key = "$RSVP_CACHE_PREFIX$eventId"
        val tsKey = "$RSVP_CACHE_TS_PREFIX$eventId"
        if (!forceRefresh) {
            val ts = prefs.getInt(tsKey)
            val cached = prefs.getString(key)
            if (ts != null && cached != null && time.nowMillis() - ts < RSVP_CACHE_TTL_MS) {
                (runCatching { DartJson.decode(cached) }.getOrNull() as? JsonObject)?.let { return it }
            }
        }
        val fresh = getRsvp(eventId)
        if (fresh != null) {
            prefs.edit {
                setString(key, DartJson.encode(fresh))
                setInt(tsKey, time.nowMillis())
            }
        }
        return fresh
    }

    override suspend fun clearRsvpCache(eventId: Int) {
        prefs.edit {
            remove("$RSVP_CACHE_PREFIX$eventId")
            remove("$RSVP_CACHE_TS_PREFIX$eventId")
        }
    }

    override suspend fun rsvp(eventId: Int, status: String): PortalResult =
        write("POST", "$API/meetup-events/$eventId/rsvp", jsonObjectOf("status" to status))

    override suspend fun getRsvpAttendees(eventId: Int): List<Attendee> {
        // Way 0: the RSVP payload itself (today it only carries counts).
        (get("/meetup-events/$eventId/rsvp") as? JsonObject)?.let { body ->
            for (k in listOf("attendee_names", "attendees_list", "names")) {
                val parsed = AttendeeParser.parse(body[k])
                if (parsed.isNotEmpty()) {
                    log.diag(TAG_PORTAL, "Teilnehmer via /rsvp (Feld \"$k\"): ${parsed.size} Namen.")
                    return parsed
                }
            }
        }
        // Organizer endpoint for one event.
        get("/my-meetup-events/$eventId")?.let { body ->
            val root = if (body is JsonObject) body["data"].takeUnless { it.isDartNull } ?: body else body
            for (k in listOf("attendees", "rsvps", "participants", "going", "members", "guests")) {
                val parsed = AttendeeParser.parse((root as? JsonObject)?.get(k))
                if (parsed.isNotEmpty()) {
                    log.diag(TAG_PORTAL, "Teilnehmer via /my-meetup-events (Feld \"$k\"): ${parsed.size} Namen.")
                    return parsed
                }
            }
            val direct = AttendeeParser.parse(root)
            if (direct.isNotEmpty()) {
                log.diag(TAG_PORTAL, "Teilnehmer via /my-meetup-events (Liste): ${direct.size} Namen.")
                return direct
            }
        }
        // Dedicated attendee endpoints (speculative names).
        for (path in listOf("attendees", "rsvps", "participants").map { "/meetup-events/$eventId/$it" }) {
            val body = get(path)
            val data = if (body is JsonObject) {
                listOf("data", "attendees", "rsvps").firstNotNullOfOrNull { k -> body[k].takeUnless { it.isDartNull } } ?: body
            } else {
                body
            }
            val parsed = AttendeeParser.parse(data)
            if (parsed.isNotEmpty()) {
                log.diag(TAG_PORTAL, "Teilnehmerliste via $path: ${parsed.size} Namen.")
                return parsed
            }
        }
        // Names inside the normal RSVP answer.
        getRsvp(eventId)?.let { r ->
            for (k in listOf("attendees", "rsvps", "going_users", "participants", "members")) {
                val parsed = AttendeeParser.parse(r[k])
                if (parsed.isNotEmpty()) {
                    log.diag(TAG_PORTAL, "Teilnehmerliste aus RSVP-Feld \"$k\": ${parsed.size} Namen.")
                    return parsed
                }
            }
        }
        log.diag(TAG_PORTAL, "Teilnehmer-Namen: Portal liefert (noch) keine Liste für Event $eventId.")
        return emptyList()
    }

    override fun isGoing(r: JsonObject?): Boolean = isGoingStatic(r)

    // ---- courses / lecturers -------------------------------------------------------------

    override suspend fun getCourses(): List<JsonObject> = asList(get("/courses?withDetails=1"))

    override suspend fun getCourse(id: Int): JsonObject? = dataOrBody(get("/courses/$id"))

    override suspend fun getLecturers(): List<JsonObject> = asList(get("/lecturers?withDetails=1"))

    override suspend fun getLecturer(id: Int): JsonObject? = dataOrBody(get("/lecturers/$id"))

    private fun describe(r: HttpResult): String = when (r) {
        is HttpResult.Failure -> r.cause.toString()
        is HttpResult.TooLarge -> "Antwort zu gross (HTTP ${r.status})"
        is HttpResult.Response -> "HTTP ${r.status}"
    }

    companion object {
        private const val TAG = "PortalAPI"
        private const val TAG_PORTAL = "Portal"
        private const val API = PortalUrls.API_BASE
        private const val JSON = "application/json"
        private const val MAX_BYTES = 16L * 1024 * 1024
        private val WRITE_METHODS = setOf("POST", "PATCH", "DELETE")

        /** The three `yyyy-MM-dd` dates of [getAllMeetupEvents]: today, 1st of next month, 1st of the month after. */
        internal fun eventDates(today: LocalDate): List<String> {
            val first = today.withDayOfMonth(1)
            return listOf(today, first.plusMonths(1), first.plusMonths(2)).map { it.toString() }
        }

        /** Create payload in Dart insertion order; empty strings and nulls omitted. */
        internal fun createPayload(
            meetupId: Int,
            start: String,
            location: String?,
            description: String?,
            link: String?,
            recurrenceType: String?,
            recurrenceDayOfWeek: String?,
            recurrenceDayPosition: String?,
            recurrenceInterval: Int?,
            recurrenceEndDate: String?,
        ): JsonObject = jsonObjectOfNonNull(
            "meetup_id" to meetupId,
            "start" to start,
            "location" to location?.takeIf { it.isNotEmpty() },
            "description" to description?.takeIf { it.isNotEmpty() },
            "link" to link?.takeIf { it.isNotEmpty() },
            "recurrence_type" to recurrenceType,
            "recurrence_day_of_week" to recurrenceDayOfWeek,
            "recurrence_day_position" to recurrenceDayPosition,
            "recurrence_interval" to recurrenceInterval,
            "recurrence_end_date" to recurrenceEndDate,
        )

        /** Update payload: every key only if non-null. */
        internal fun updatePayload(
            meetupId: Int?,
            start: String?,
            location: String?,
            description: String?,
            link: String?,
            recurrenceType: String?,
            recurrenceDayOfWeek: String?,
            recurrenceDayPosition: String?,
            recurrenceInterval: Int?,
            recurrenceEndDate: String?,
        ): JsonObject = jsonObjectOfNonNull(
            "meetup_id" to meetupId,
            "start" to start,
            "location" to location,
            "description" to description,
            "link" to link,
            "recurrence_type" to recurrenceType,
            "recurrence_day_of_week" to recurrenceDayOfWeek,
            "recurrence_day_position" to recurrenceDayPosition,
            "recurrence_interval" to recurrenceInterval,
            "recurrence_end_date" to recurrenceEndDate,
        )

        private fun jsonObjectOfNonNull(vararg pairs: Pair<String, Any?>): JsonObject =
            jsonObjectOf(*pairs.filter { it.second != null }.toTypedArray())

        /** Dart `isGoing`. */
        internal fun isGoingStatic(r: JsonObject?): Boolean {
            if (r == null) return false
            val st = r["status"].takeUnless { it.isDartNull }?.dartToString() ?: ""
            if (st == "attending" || st == "maybe") return true
            val v = listOf("going", "is_going", "rsvped").firstNotNullOfOrNull { k -> r[k].takeUnless { it.isDartNull } }
            return v is JsonPrimitive && !v.isString && v.content == "true"
        }

        /** Dart `_asList`. */
        internal fun asList(body: JsonElement?): List<JsonObject> {
            val data = if (body is JsonObject) {
                listOf("data", "items").firstNotNullOfOrNull { k -> body[k].takeUnless { it.isDartNull } } ?: body
            } else {
                body
            }
            return (data as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        }

        /** Dart `(b is Map) ? (b['data'] ?? b) : null`, kept only if it is an object. */
        private fun dataOrBody(b: JsonElement?): JsonObject? {
            if (b !is JsonObject) return null
            return (b["data"].takeUnless { it.isDartNull } ?: b) as? JsonObject
        }
    }
}
