package space.einundzwanzig.meetup.feature.home

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.feature.home.organizer.OrganizerChange
import space.einundzwanzig.meetup.feature.home.organizer.PortalOrganizerChecker

/** Spec 12 §10 item 11: the organizer state machine of §8.7, table-driven. */
class PortalOrganizerCheckerTest {

    private val oneMeetup = JsonObject(mapOf("data" to JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("Wien")))))))
    private val noMeetups = JsonArray(emptyList())

    private suspend fun run(ports: FakeOrganizerPorts) = PortalOrganizerChecker(ports, AppLogger).check()

    @Test
    fun `no token with external signer shows the hint without login`() = runTest {
        val ports = FakeOrganizerPorts().apply { external = true }
        val r = run(ports)
        assertTrue(r.showHint)
        assertEquals(0, ports.logins)
        assertEquals(OrganizerChange.UNCHANGED, r.change)
    }

    @Test
    fun `no token with local key logs in silently and grants`() = runTest {
        val ports = FakeOrganizerPorts().apply { statuses.add(status(200, oneMeetup)) }
        val r = run(ports)
        assertTrue(r.loginAttempted)
        assertEquals(OrganizerChange.GRANTED, r.change)
        assertTrue(ports.profileValue.adminViaPortal)
        assertTrue(ports.profileValue.isAdminVerified)
        assertEquals(listOf("Wien"), ports.claims)
    }

    @Test
    fun `failed silent login changes nothing`() = runTest {
        val ports = FakeOrganizerPorts().apply { loginOk = false }
        val r = run(ports)
        assertEquals(OrganizerChange.UNCHANGED, r.change)
        assertFalse(r.showHint)
        assertEquals(1, ports.logins)
    }

    @Test
    fun `foreign token is deleted and renewed with the local key`() = runTest {
        val ports = FakeOrganizerPorts().apply {
            token = true
            matches = false
            statuses.add(status(200, noMeetups))
        }
        val r = run(ports)
        assertEquals(1, ports.deletes)
        assertEquals(1, ports.logins)
        assertEquals(OrganizerChange.UNCHANGED, r.change)
    }

    @Test
    fun `foreign token with external signer shows the mismatch`() = runTest {
        val ports = FakeOrganizerPorts().apply {
            token = true
            matches = false
            external = true
        }
        val r = run(ports)
        assertTrue(r.showTokenMismatch)
        assertEquals(1, ports.deletes)
        assertEquals(0, ports.logins)
    }

    @Test
    fun `failed renewal after a foreign token shows the hint`() = runTest {
        val ports = FakeOrganizerPorts().apply {
            token = true
            matches = false
            loginOk = false
        }
        val r = run(ports)
        assertTrue(r.showHint)
    }

    @Test
    fun `401 renews the token and retries`() = runTest {
        val ports = FakeOrganizerPorts().apply {
            token = true
            statuses.add(status(401, null))
            statuses.add(status(200, oneMeetup))
        }
        val r = run(ports)
        assertEquals(1, ports.deletes)
        assertEquals(1, ports.logins)
        assertEquals(OrganizerChange.GRANTED, r.change)
    }

    @Test
    fun `403 with external signer shows the hint`() = runTest {
        val ports = FakeOrganizerPorts().apply {
            token = true
            external = true
            statuses.add(status(403, null))
        }
        val r = run(ports)
        assertTrue(r.showHint)
        assertEquals(OrganizerChange.UNCHANGED, r.change)
    }

    @Test
    fun `offline or non-200 never revokes`() = runTest {
        for (code in listOf(0, 500, 404)) {
            val ports = FakeOrganizerPorts().apply {
                token = true
                profileValue = profile(adminViaPortal = true)
                statuses.add(status(code, null))
            }
            val r = run(ports)
            assertEquals(OrganizerChange.UNCHANGED, r.change)
            assertTrue(ports.profileValue.adminViaPortal)
        }
    }

    @Test
    fun `empty successful answer revokes and clears the own admin-cache entry`() = runTest {
        val ports = FakeOrganizerPorts().apply {
            token = true
            profileValue = profile(adminViaPortal = true)
            statuses.add(status(200, noMeetups))
        }
        val r = run(ports)
        assertEquals(OrganizerChange.REVOKED, r.change)
        assertFalse(ports.profileValue.adminViaPortal)
        assertEquals(listOf("npub1me"), ports.removed)
    }

    @Test
    fun `already granted stays unchanged and unnamed meetup falls back`() = runTest {
        val granted = FakeOrganizerPorts().apply {
            token = true
            profileValue = profile(adminViaPortal = true)
            statuses.add(status(200, oneMeetup))
        }
        assertEquals(OrganizerChange.UNCHANGED, run(granted).change)

        val unnamed = FakeOrganizerPorts().apply {
            token = true
            statuses.add(status(200, JsonArray(listOf(JsonObject(emptyMap())))))
        }
        assertEquals(OrganizerChange.GRANTED, run(unnamed).change)
        assertEquals(listOf("Unbekannt"), unnamed.claims)
    }

    @Test
    fun `body variants`() {
        assertEquals(1, PortalOrganizerChecker.meetupsOf(oneMeetup).size)
        assertEquals(0, PortalOrganizerChecker.meetupsOf(noMeetups).size)
        assertEquals(0, PortalOrganizerChecker.meetupsOf(JsonPrimitive("x")).size)
    }
}
