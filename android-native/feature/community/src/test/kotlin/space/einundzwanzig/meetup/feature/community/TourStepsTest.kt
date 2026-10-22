package space.einundzwanzig.meetup.feature.community

import org.junit.Assert.assertEquals
import org.junit.Test
import space.einundzwanzig.meetup.feature.community.hub.CommunityHubAnchors
import space.einundzwanzig.meetup.feature.community.hub.communityTourSteps
import space.einundzwanzig.meetup.feature.community.portal.PortalAreaAnchors
import space.einundzwanzig.meetup.feature.community.portal.portalAreaTourSteps
import space.einundzwanzig.meetup.core.l10n.R as L10nR

class TourStepsTest {

    @Test
    fun `community tour has four anchored steps with the ARB keys in order`() {
        val steps = communityTourSteps()
        assertEquals(
            listOf(
                Triple(CommunityHubAnchors.PORTAL, L10nR.string.guideCommunityPortalTitle, L10nR.string.guideCommunityPortalBody),
                Triple(CommunityHubAnchors.NEWS, L10nR.string.guideCommunityNewsTitle, L10nR.string.guideCommunityNewsBody),
                Triple(CommunityHubAnchors.SHOUTOUT, L10nR.string.guidePortalShoutoutTitle, L10nR.string.guidePortalShoutoutBody),
                Triple(CommunityHubAnchors.DUELL, L10nR.string.guideCommunityFunTitle, L10nR.string.guideCommunityFunBody),
            ),
            steps.map { Triple(it.targetId, it.title, it.body) },
        )
    }

    @Test
    fun `portal area tour has four anchored steps with the ARB keys in order`() {
        val steps = portalAreaTourSteps()
        assertEquals(
            listOf(
                Triple(PortalAreaAnchors.MEETUPS, L10nR.string.guidePaMeetupsTitle, L10nR.string.guidePaMeetupsBody),
                Triple(PortalAreaAnchors.COURSES, L10nR.string.guidePaCoursesTitle, L10nR.string.guidePaCoursesBody),
                Triple(PortalAreaAnchors.MAP, L10nR.string.guidePaMapTitle, L10nR.string.guidePaMapBody),
                Triple(PortalAreaAnchors.MINE, L10nR.string.guidePaMineTitle, L10nR.string.guidePaMineBody),
            ),
            steps.map { Triple(it.targetId, it.title, it.body) },
        )
    }
}
