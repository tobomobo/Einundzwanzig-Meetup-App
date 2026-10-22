package space.einundzwanzig.meetup.feature.admin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.admin.AdminConstants
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.issuance.session.OrganizerSessionService
import space.einundzwanzig.meetup.data.issuance.session.RollingQrService
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.location.MeetupLocationService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetup

class DefaultAdminPanelRepositoryTest {

    private var npub: () -> String? = { "npub1me" }
    private var profile: () -> UserProfile = { UserProfile() }
    private var fetched: () -> List<Meetup> = { emptyList() }
    private var myMeetups: () -> List<PortalMeetup> = { emptyList() }
    private val fallback = listOf(meetup("Fallback"))

    private fun repo() = DefaultAdminPanelRepository(
        signing = stub<SigningService> { if (it == "npub") npub() else error("unexpected $it") },
        profiles = stub<UserProfileRepository> { if (it == "load") profile() else error("unexpected $it") },
        rollingQr = stub<RollingQrService> { error("unexpected $it") },
        organizer = stub<OrganizerSessionService> { error("unexpected $it") },
        location = stub<MeetupLocationService> { error("unexpected $it") },
        meetups = stub<MeetupService> {
            when (it) {
                "fetchMeetups" -> fetched()
                "getFallbackMeetups" -> fallback
                else -> error("unexpected $it")
            }
        },
        portal = stub<PortalApiService> { if (it == "getMyMeetups") myMeetups() else error("unexpected $it") },
    )

    @Test
    fun `portal meetups come from the fetch when it has rows`() = runTest {
        fetched = { listOf(meetup("Würzburg")) }
        assertEquals(listOf("Würzburg"), repo().portalMeetups().map { it.city })
    }

    @Test
    fun `empty fetch falls back to the bundled list`() = runTest {
        assertEquals(fallback, repo().portalMeetups())
    }

    @Test
    fun `failed fetch falls back to the bundled list`() = runTest {
        fetched = { throw IllegalStateException("offline") }
        assertEquals(fallback, repo().portalMeetups())
    }

    @Test
    fun `own meetup names are trimmed and lowercased`() = runTest {
        myMeetups = { listOf(PortalMeetup(1, "  Einundzwanzig Würzburg ", "wue"), PortalMeetup(2, "KÖLN", "koeln")) }
        assertEquals(setOf("einundzwanzig würzburg", "köln"), repo().myMeetupNames())
    }

    @Test
    fun `failing own meetups give an empty set`() = runTest {
        myMeetups = { throw IllegalStateException("401") }
        assertEquals(emptySet<String>(), repo().myMeetupNames())
    }

    @Test
    fun `admin info uses the signer npub and the promotion source`() = runTest {
        profile = { UserProfile(nostrNpub = "npub1profile", adminViaVouch = true) }
        val info = repo().loadAdminInfo()
        assertEquals("npub1me", info.npub)
        assertEquals("trust_score", info.promotionSource)
        assertFalse(info.isSuperAdmin)
    }

    @Test
    fun `missing or failing signer npub falls back to the profile npub`() = runTest {
        profile = { UserProfile(nostrNpub = "npub1profile") }
        npub = { null }
        assertEquals("npub1profile", repo().loadAdminInfo().npub)
        npub = { throw IllegalStateException("no signer") }
        assertEquals("npub1profile", repo().loadAdminInfo().npub)
    }

    @Test
    fun `failing profile and signer give empty info without super admin`() = runTest {
        profile = { throw IllegalStateException("store") }
        npub = { null }
        val info = repo().loadAdminInfo()
        assertEquals(AdminInfo("", "", false), info)
    }

    @Test
    fun `super admin is recognised by the exact npub`() = runTest {
        npub = { AdminConstants.SUPER_ADMIN_NPUB }
        assertTrue(repo().loadAdminInfo().isSuperAdmin)
        npub = { AdminConstants.SUPER_ADMIN_NPUB.dropLast(1) + "x" }
        assertFalse(repo().loadAdminInfo().isSuperAdmin)
    }
}
