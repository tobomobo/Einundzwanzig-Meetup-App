package space.einundzwanzig.meetup.data.meetups

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.meetups.internal.AttendeeParser
import space.einundzwanzig.meetup.data.meetups.internal.PortalApiServiceImpl
import space.einundzwanzig.meetup.data.meetups.internal.PortalErrors
import space.einundzwanzig.meetup.data.meetups.portal.Attendee
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.PortalErrorKind
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetupEvent
import java.time.LocalDate

class PortalApiServiceTest {

    private val server = FakeServer()
    private val secure = InMemorySecureStore()
    private val prefs = InMemoryKeyValueStore()
    private val signer = FakeSigner()
    private val time = FakeTime()
    private val api = PortalApiServiceImpl(server.client, secure, prefs, signer.service, time, testLog())

    @After
    fun tearDown() = server.close()

    private fun obj(text: String) = DartJson.decode(text) as JsonObject

    private suspend fun loginToken(token: String = "tok", npub: String? = signer.keyPair!!.npub) {
        secure.write(PortalApiService.TOKEN_KEY, token)
        if (npub != null) secure.write(PortalApiService.TOKEN_NPUB_KEY, npub)
    }

    // ---- _errorFrom ----------------------------------------------------------------------

    @Test
    fun `errorFrom spec vectors`() {
        assertEquals(
            "a · b · c (HTTP 422)" to PortalErrorKind.SERVER_MESSAGE,
            PortalErrors.errorFrom(422, """{"errors":{"start":["a","b"],"link":"c"}}"""),
        )
        assertEquals("Nope (HTTP 403)" to PortalErrorKind.SERVER_MESSAGE, PortalErrors.errorFrom(403, """{"message":"Nope"}"""))
        assertEquals("Nicht gefunden." to PortalErrorKind.NOT_FOUND, PortalErrors.errorFrom(404, "<html>"))
        assertEquals("Fehler (HTTP 500)." to PortalErrorKind.HTTP, PortalErrors.errorFrom(500, "oops"))
        assertEquals("Nicht authentifiziert. Bitte erneut anmelden.", PortalErrors.errorFrom(401, "").first)
        assertEquals("Keine Berechtigung (nur Ersteller oder Admin).", PortalErrors.errorFrom(403, """{"message":""}""").first)
        assertEquals("Validierungsfehler. Bitte Eingaben prüfen.", PortalErrors.errorFrom(422, """{"errors":{}}""").first)
    }

    // ---- payloads ------------------------------------------------------------------------

    @Test
    fun `create payload omits empty and null values in Dart order`() {
        val p = PortalApiServiceImpl.createPayload(7, "2026-07-21T16:00:00.000Z", "", "D", null, null, null, null, null, null)
        assertEquals("""{"meetup_id":7,"start":"2026-07-21T16:00:00.000Z","description":"D"}""", DartJson.encode(p))
        val series = PortalApiServiceImpl.createPayload(7, "s", "L", null, "https://x", "weekly", "tuesday", "first", 2, "e")
        assertEquals(
            """{"meetup_id":7,"start":"s","location":"L","link":"https://x","recurrence_type":"weekly",""" +
                """"recurrence_day_of_week":"tuesday","recurrence_day_position":"first","recurrence_interval":2,"recurrence_end_date":"e"}""",
            DartJson.encode(series),
        )
    }

    @Test
    fun `update payload with only start`() {
        val p = PortalApiServiceImpl.updatePayload(null, "s", null, null, null, null, null, null, null, null)
        assertEquals("""{"start":"s"}""", DartJson.encode(p))
        assertEquals("""{"location":""}""", DartJson.encode(PortalApiServiceImpl.updatePayload(null, null, "", null, null, null, null, null, null, null)))
    }

    // ---- _write --------------------------------------------------------------------------

    @Test
    fun `write without token sends nothing`() = runTest {
        val r = api.createMeetupEvent(1, "s")
        assertEquals(PortalErrorKind.NOT_LOGGED_IN, r.errorKind)
        assertEquals(401, r.statusCode)
        assertEquals("Nicht angemeldet. Bitte zuerst mit dem Portal verbinden.", r.error)
        assertTrue(server.seen.isEmpty())
    }

    @Test
    fun `write sends auth json and decodes the answer`() = runTest {
        loginToken()
        server.respond = { _, _ -> json("""{"id":5}""", 201) }
        val r = api.createMeetupEvent(3, "2026-07-21T16:00:00.000Z", location = "L")
        assertTrue(r.ok)
        assertEquals(201, r.statusCode)
        assertEquals(JsonPrimitive(5), (r.data as JsonObject)["id"])
        val s = server.seen.single()
        assertEquals("POST", s.method)
        assertEquals("/api/meetup-events", s.path)
        assertEquals("Bearer tok", s.header("Authorization"))
        assertEquals("application/json", s.header("Accept"))
        assertTrue(s.header("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"meetup_id":3,"start":"2026-07-21T16:00:00.000Z","location":"L"}""", s.body)
    }

    @Test
    fun `204 without body is ok with null data`() = runTest {
        loginToken()
        server.respond = { _, _ -> status(204, "") }
        val r = api.removeMeetupLeader(2, 9)
        assertTrue(r.ok)
        assertNull(r.data)
        assertEquals("DELETE", server.seen.single().method)
        assertEquals("/api/meetup/2/leaders/9", server.seen.single().path)
    }

    @Test
    fun `401 deletes the token`() = runTest {
        loginToken()
        server.respond = { _, _ -> json("""{"message":"Unauthenticated."}""", 401) }
        val r = api.rsvp(12, "maybe")
        assertFalse(r.ok)
        assertEquals("Unauthenticated. (HTTP 401)", r.error)
        assertNull(api.getToken())
        assertNull(secure.entries.value[PortalApiService.TOKEN_NPUB_KEY])
        assertEquals("""{"status":"maybe"}""", server.seen.single().body)
    }

    @Test
    fun `network failure gives the offline message`() = runTest {
        loginToken()
        server.server.close()
        val r = api.addMeetupLeader(1, "npub1x")
        assertEquals(0, r.statusCode)
        assertEquals("Netzwerkfehler. Bist du online?", r.error)
        assertEquals(PortalErrorKind.NETWORK, r.errorKind)
    }

    // ---- login ---------------------------------------------------------------------------

    @Test
    fun `login posts k1 and a kind 22242 challenge event and stores the bound token`() = runTest {
        server.respond = { _, _ -> json("""{"token":"t","user":{"id":1,"nostr":"npub1"}}""", 201) }
        val r = api.loginWithNostr()
        assertTrue(r.ok)
        assertEquals(obj("""{"id":1,"nostr":"npub1"}"""), r.data)
        assertEquals("t", api.getToken())
        assertEquals(signer.keyPair!!.npub, secure.entries.value[PortalApiService.TOKEN_NPUB_KEY])

        val s = server.seen.single()
        assertEquals("/api/mobile/token", s.path)
        assertEquals(null, s.header("Authorization"))
        val body = obj(s.body)
        assertEquals(listOf("k1", "event", "device_name"), body.keys.toList())
        val k1 = body["k1"]!!.jsonPrimitive.content
        assertTrue(Regex("[0-9a-f]{64}").matches(k1))
        assertEquals("Einundzwanzig Meetup App", body["device_name"]!!.jsonPrimitive.content)
        val ev = body["event"] as JsonObject
        assertEquals(listOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig"), ev.keys.toList())
        assertEquals(22242, ev["kind"]!!.jsonPrimitive.content.toInt())
        assertEquals(JsonArray(listOf(JsonArray(listOf(JsonPrimitive("challenge"), JsonPrimitive(k1))))), ev["tags"])
        assertEquals("", ev["content"]!!.jsonPrimitive.content)
        assertTrue(
            EventIds.isValid(
                ev["id"]!!.jsonPrimitive.content, ev["pubkey"]!!.jsonPrimitive.content,
                ev["created_at"]!!.jsonPrimitive.content.toLong(), 22242, listOf(listOf("challenge", k1)), "",
                ev["sig"]!!.jsonPrimitive.content,
            ),
        )
    }

    @Test
    fun `login without token in the answer`() = runTest {
        server.respond = { _, _ -> json("{}") }
        val r = api.loginWithNostr()
        assertFalse(r.ok)
        assertEquals(200, r.statusCode)
        assertEquals("Kein Token vom Portal erhalten.", r.error)
        assertNull(api.getToken())
    }

    @Test
    fun `login fails when the token cannot be stored`() = runTest {
        server.respond = { _, _ -> json("""{"token":"t","user":{"id":1}}""", 201) }
        secure.failWrite = { it == PortalApiService.TOKEN_NPUB_KEY }
        val r = api.loginWithNostr()
        assertFalse(r.ok)
        assertEquals(0, r.statusCode)
        assertEquals("Anmeldung fehlgeschlagen: Token konnte nicht gespeichert werden", r.error)
        assertEquals(PortalErrorKind.LOGIN_FAILED, r.errorKind)
        // Atomic: no unbound token left behind.
        assertNull(api.getToken())
        assertFalse(api.hasToken())
    }

    @Test
    fun `login error status and signer failure`() = runTest {
        server.respond = { _, _ -> json("""{"message":"Event zu alt"}""", 422) }
        assertEquals("Event zu alt (HTTP 422)", api.loginWithNostr().error)
        signer.failSigning = IllegalStateException("abgelehnt")
        val r = api.loginWithNostr()
        assertEquals(0, r.statusCode)
        assertEquals("Anmeldung fehlgeschlagen: abgelehnt", r.error)
        assertEquals(PortalErrorKind.LOGIN_FAILED, r.errorKind)
    }

    @Test
    fun `logout revokes best effort and deletes locally`() = runTest {
        loginToken()
        server.respond = { _, _ -> status(500, "") }
        api.logout()
        assertEquals("DELETE", server.seen.single().method)
        assertEquals("Bearer tok", server.seen.single().header("Authorization"))
        assertFalse(api.hasToken())
    }

    @Test
    fun `manual token is trimmed and bound`() = runTest {
        api.setManualToken("  abc \n")
        assertEquals("abc", api.getToken())
        assertEquals(signer.keyPair!!.npub, secure.entries.value[PortalApiService.TOKEN_NPUB_KEY])
    }

    @Test
    fun `unreadable token counts as logged out`() = runTest {
        loginToken()
        secure.failRead = { it == PortalApiService.TOKEN_KEY }
        assertFalse(api.hasToken())
    }

    // ---- tokenMatchesCurrentKey (spec 09 §8.11 table) ------------------------------------

    @Test
    fun `token match - no token`() = runTest { assertFalse(api.tokenMatchesCurrentKey()) }

    @Test
    fun `token match - no identity`() = runTest {
        loginToken()
        signer.keyPair = null
        assertFalse(api.tokenMatchesCurrentKey())
    }

    @Test
    fun `token match - binding equal is offline safe`() = runTest {
        loginToken()
        assertTrue(api.tokenMatchesCurrentKey())
        assertTrue(server.seen.isEmpty())
    }

    @Test
    fun `token match - 401 is false`() = runTest {
        loginToken(npub = null)
        server.respond = { _, _ -> status(401, "") }
        assertFalse(api.tokenMatchesCurrentKey())
        assertEquals("Bearer tok", server.seen.single().header("Authorization"))
        assertEquals("/api/user", server.seen.single().path)
    }

    @Test
    fun `token match - other status is true`() = runTest {
        loginToken(npub = "npub1other")
        server.respond = { _, _ -> status(503, "") }
        assertTrue(api.tokenMatchesCurrentKey())
    }

    @Test
    fun `token match - account without npub is true`() = runTest {
        loginToken(npub = null)
        server.respond = { _, _ -> json("""{"nostr":"  "}""") }
        assertTrue(api.tokenMatchesCurrentKey())
        server.respond = { _, _ -> json("""{"nostr":null}""") }
        assertTrue(api.tokenMatchesCurrentKey())
    }

    @Test
    fun `token match - same npub ignoring case heals the binding`() = runTest {
        loginToken(npub = null)
        val npub = signer.keyPair!!.npub
        server.respond = { _, _ -> json("""{"nostr":" ${npub.uppercase()} "}""") }
        assertTrue(api.tokenMatchesCurrentKey())
        assertEquals(npub, secure.entries.value[PortalApiService.TOKEN_NPUB_KEY])
    }

    @Test
    fun `token match - different npub is false`() = runTest {
        loginToken(npub = null)
        server.respond = { _, _ -> json("""{"nostr":"${KeyPair.generate().npub}"}""") }
        assertFalse(api.tokenMatchesCurrentKey())
    }

    @Test
    fun `token match - offline depends on the binding`() = runTest {
        loginToken(npub = null)
        server.server.close()
        assertTrue(api.tokenMatchesCurrentKey())
        secure.write(PortalApiService.TOKEN_NPUB_KEY, "npub1someoneelse")
        assertFalse(api.tokenMatchesCurrentKey())
    }

    // ---- reads ---------------------------------------------------------------------------

    @Test
    fun `rawGetStatus distinguishes 401 from offline`() = runTest {
        server.respond = { _, _ -> status(401, "") }
        assertEquals(401, api.rawGetStatus("/my-meetups").status)
        server.respond = { _, _ -> json("""{"data":[]}""") }
        val ok = api.rawGetStatus("/my-meetups")
        assertEquals(200, ok.status)
        assertEquals(obj("""{"data":[]}"""), ok.body)
        server.server.close()
        assertEquals(0, api.rawGetStatus("/my-meetups").status)
    }

    @Test
    fun `my meetups and events parse tolerantly`() = runTest {
        loginToken()
        server.respond = { _, path ->
            when (path) {
                "/api/my-meetups" -> json("""{"data":[{"id":1,"name":"A","slug":"a","is_leader":true},{"id":"2"},{"id":3,"is_leader":"true"}]}""")
                "/api/my-meetup-events" -> json(
                    """{"data":[{"id":7,"meetup_id":1,"start":"2026-07-21 16:00:00","location":"L"},{"meetup_id":"x","start":"s"},
                       {"id":"bad","start":"t","link":5},{"id":9}]}""",
                )
                else -> status(404, "")
            }
        }
        assertEquals(listOf(PortalMeetup(1, "A", "a", true), PortalMeetup(3, "", "", false)), api.getMyMeetups())
        assertEquals(
            listOf(
                PortalMeetupEvent(7, 1, "2026-07-21 16:00:00", "L"),
                PortalMeetupEvent(null, 0, "s"),
                PortalMeetupEvent(null, 0, "t"),
            ),
            api.getMyMeetupEvents(),
        )
        server.respond = { _, _ -> status(401, "") }
        assertTrue(api.getMyMeetups().isEmpty())
        assertTrue(api.hasToken()) // no deletion here
    }

    @Test
    fun `event dates of getAllMeetupEvents`() {
        assertEquals(listOf("2026-12-15", "2027-01-01", "2027-02-01"), PortalApiServiceImpl.eventDates(LocalDate.of(2026, 12, 15)))
        assertEquals(listOf("2026-11-30", "2026-12-01", "2027-01-01"), PortalApiServiceImpl.eventDates(LocalDate.of(2026, 11, 30)))
    }

    @Test
    fun `getAllMeetupEvents merges by id keeping the first position`() = runTest {
        server.respond = { _, path ->
            when (path) {
                "/api/meetup-events/2026-07-20" -> json("""{"data":[{"id":1,"v":"a"},{"id":2,"v":"a"},{"v":"noid"}]}""")
                "/api/meetup-events/2026-08-01" -> json("""[{"id":1,"v":"b"},{"id":3},"junk"]""")
                else -> status(500, "")
            }
        }
        val events = api.getAllMeetupEvents(LocalDate.of(2026, 7, 20))
        assertEquals(listOf("1", "2", null, "3"), events.map { it["id"]?.jsonPrimitive?.content })
        assertEquals("b", events[0]["v"]!!.jsonPrimitive.content)
        assertEquals(3, server.seen.size)
        assertNull(server.seen[0].header("Authorization"))
        assertEquals("application/json", server.seen[0].header("Accept"))
    }

    @Test
    fun `getAllMeetupEvents without a date uses the injected clock`() = runTest {
        server.respond = { _, _ -> json("[]") }
        time.now = java.time.LocalDate.of(2026, 3, 10).atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue(api.getAllMeetupEvents().isEmpty())
        assertEquals(
            listOf("/api/meetup-events/2026-03-10", "/api/meetup-events/2026-04-01", "/api/meetup-events/2026-05-01"),
            server.seen.map { it.path },
        )
    }

    // ---- RSVP ----------------------------------------------------------------------------

    @Test
    fun `isGoing variants`() {
        assertTrue(api.isGoing(obj("""{"status":"maybe"}""")))
        assertTrue(api.isGoing(obj("""{"status":"attending"}""")))
        assertTrue(api.isGoing(obj("""{"going":true}""")))
        assertTrue(api.isGoing(obj("""{"rsvped":true}""")))
        assertFalse(api.isGoing(obj("""{"status":"none","going":false}""")))
        assertFalse(api.isGoing(obj("""{"going":"true"}""")))
        assertFalse(api.isGoing(null))
    }

    @Test
    fun `getRsvp unwraps data`() = runTest {
        server.respond = { _, _ -> json("""{"data":{"count":3}}""") }
        assertEquals(obj("""{"count":3}"""), api.getRsvp(4))
        server.respond = { _, _ -> json("""{"count":2,"data":[1]}""") }
        assertEquals(obj("""{"count":2,"data":[1]}"""), api.getRsvp(4))
        server.respond = { _, _ -> json("[]") }
        assertNull(api.getRsvp(4))
    }

    @Test
    fun `rsvp cache TTL 59 min hit and 61 min miss`() = runTest {
        server.respond = { _, _ -> json("""{"data":{"status":"attending","attendees":3}}""") }
        val first = api.getRsvpCached(8)
        assertEquals(1, server.seen.size)
        assertEquals("""{"status":"attending","attendees":3}""", prefs.getString("rsvp_cache_8"))
        assertEquals(time.now, prefs.getInt("rsvp_cache_ts_8"))

        time.now += 59 * 60_000L
        assertEquals(first, api.getRsvpCached(8))
        assertEquals(1, server.seen.size)

        time.now += 2 * 60_000L
        api.getRsvpCached(8)
        assertEquals(2, server.seen.size)

        api.getRsvpCached(8, forceRefresh = true)
        assertEquals(3, server.seen.size)

        api.clearRsvpCache(8)
        assertNull(prefs.get("rsvp_cache_8"))
        assertNull(prefs.get("rsvp_cache_ts_8"))
    }

    @Test
    fun `rsvp cache migrated from Flutter is read`() = runTest {
        val migrated = InMemoryKeyValueStore(
            mapOf(
                "rsvp_cache_5" to PrefValue.StringValue("""{"going":true}"""),
                "rsvp_cache_ts_5" to PrefValue.IntValue(time.now - 1000),
            ),
        )
        val a = PortalApiServiceImpl(server.client, secure, migrated, signer.service, time, testLog())
        assertTrue(a.isGoing(a.getRsvpCached(5)))
        assertTrue(server.seen.isEmpty())
    }

    // ---- attendees -----------------------------------------------------------------------

    @Test
    fun `attendee parsing`() {
        assertEquals("Alice", AttendeeParser.cleanName("id_12|Alice"))
        assertEquals("id_x|Bob", AttendeeParser.cleanName("id_x|Bob"))
        assertEquals(listOf(Attendee("", "Bob")), AttendeeParser.parse(DartJson.decode("""["id_3|Bob", ""]""")))
        assertEquals(
            listOf(Attendee("npub1x", "N")),
            AttendeeParser.parse(DartJson.decode("""[{"user":{"npub":"npub1x","nickname":"N"}}]""")),
        )
        assertEquals(
            listOf(Attendee("pk", "id")),
            AttendeeParser.parse(DartJson.decode("""[{"user":"str","pubkey":"pk","name":"id"},{"x":1}]""")),
        )
        assertTrue(AttendeeParser.parse(DartJson.decode("3")).isEmpty())
    }

    @Test
    fun `attendees fall through the endpoints`() = runTest {
        server.respond = { _, path ->
            when (path) {
                "/api/meetup-events/4/rsvp" -> json("""{"attendees":3}""")
                "/api/meetup-events/4/rsvps" -> json("""{"data":["id_1|Carol"]}""")
                else -> status(404, "")
            }
        }
        assertEquals(listOf(Attendee("", "Carol")), api.getRsvpAttendees(4))
        assertEquals(
            listOf("/api/meetup-events/4/rsvp", "/api/my-meetup-events/4", "/api/meetup-events/4/attendees", "/api/meetup-events/4/rsvps"),
            server.seen.map { it.path },
        )
    }

    @Test
    fun `attendees none found`() = runTest {
        server.respond = { _, _ -> json("""{"attendees":3}""") }
        assertTrue(api.getRsvpAttendees(4).isEmpty())
    }

    // ---- courses / leaders ---------------------------------------------------------------

    @Test
    fun `courses lecturers and leaders lists`() = runTest {
        server.respond = { _, path ->
            when (path) {
                "/api/courses?withDetails=1" -> json("""[{"id":1},2]""")
                "/api/lecturers?withDetails=1" -> json("""{"items":[{"id":5}]}""")
                "/api/courses/1" -> json("""{"data":{"id":1,"name":"K"}}""")
                "/api/lecturers/5" -> json("""{"id":5}""")
                "/api/meetup/3/leaders" -> json("""{"data":[{"id":1,"name":"A","nostr":"npub1","is_creator":true}]}""")
                else -> status(404, "")
            }
        }
        assertEquals(1, api.getCourses().size)
        assertEquals(1, api.getLecturers().size)
        assertEquals("K", api.getCourse(1)!!["name"]!!.jsonPrimitive.content)
        assertEquals(obj("""{"id":5}"""), api.getLecturer(5))
        assertNull(api.getLecturer(6))
        assertEquals(1, api.getMeetupLeaders(3).size)
        assertTrue(api.getMeetupLeaders(4).isEmpty())
    }

    @Test
    fun `npub input check`() {
        assertTrue(PortalApiService.looksLikeNpub(KeyPair.generate().npub))
        assertFalse(PortalApiService.looksLikeNpub("npub1short"))
        assertFalse(PortalApiService.looksLikeNpub("nsec1" + "x".repeat(60)))
    }
}
