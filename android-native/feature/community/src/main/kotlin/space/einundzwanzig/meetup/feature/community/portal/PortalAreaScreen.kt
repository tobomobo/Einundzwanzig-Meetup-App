package space.einundzwanzig.meetup.feature.community.portal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.navigation.CommunityPortal
import space.einundzwanzig.meetup.core.navigation.Courses
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.MeetupCalendar
import space.einundzwanzig.meetup.core.navigation.NearbyMeetups
import space.einundzwanzig.meetup.core.navigation.PortalMeetups
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.guide.FinishTourOnDispose
import space.einundzwanzig.meetup.core.ui.guide.GuideStep
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.StartTourEffect
import space.einundzwanzig.meetup.core.ui.guide.guideAnchor
import space.einundzwanzig.meetup.core.ui.guide.guideScrollContainer
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.community.common.AccentCard
import space.einundzwanzig.meetup.feature.community.common.IconBox
import space.einundzwanzig.meetup.feature.community.common.RowChevron
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** The six rows of the portal area, in display order (spec 11 §2.6). */
enum class PortalAreaRow { Meetups, Events, Courses, Map, Mine, Web }

/** Guide anchors of `PortalAreaTour`. */
internal object PortalAreaAnchors {
    const val MEETUPS = "portalArea.meetups"
    const val COURSES = "portalArea.courses"
    const val MAP = "portalArea.map"
    const val MINE = "portalArea.mine"
}

internal fun portalAreaTourSteps(): List<GuideStep> = listOf(
    GuideStep(PortalAreaAnchors.MEETUPS, L10nR.string.guidePaMeetupsTitle, L10nR.string.guidePaMeetupsBody),
    GuideStep(PortalAreaAnchors.COURSES, L10nR.string.guidePaCoursesTitle, L10nR.string.guidePaCoursesBody),
    GuideStep(PortalAreaAnchors.MAP, L10nR.string.guidePaMapTitle, L10nR.string.guidePaMapBody),
    GuideStep(PortalAreaAnchors.MINE, L10nR.string.guidePaMineTitle, L10nR.string.guidePaMineBody),
)

@Composable
fun PortalAreaRoute() {
    val navigator = LocalNavigator.current
    StartTourEffect(GuideTour.PortalArea, delayMillis = 500, steps = ::portalAreaTourSteps)
    FinishTourOnDispose(GuideTour.PortalArea)
    PortalAreaScreen(
        onBack = navigator::back,
        onRowClick = { row ->
            navigator.navigate(
                when (row) {
                    // "Meetups" and "Events" open the same calendar (Flutter parity).
                    PortalAreaRow.Meetups, PortalAreaRow.Events -> MeetupCalendar()
                    PortalAreaRow.Courses -> Courses
                    PortalAreaRow.Map -> NearbyMeetups
                    PortalAreaRow.Mine -> PortalMeetups
                    PortalAreaRow.Web -> CommunityPortal
                },
            )
        },
    )
}

private data class RowSpec(
    val row: PortalAreaRow,
    val icon: ImageVector,
    val color: Color,
    val title: Int,
    val subtitle: Int,
    val anchor: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalAreaScreen(
    onBack: () -> Unit,
    onRowClick: (PortalAreaRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val ext = MaterialTheme.extendedColors
    val rows = listOf(
        RowSpec(PortalAreaRow.Meetups, Icons.Rounded.Groups, colors.primary, L10nR.string.paMeetups, L10nR.string.paMeetupsSub, PortalAreaAnchors.MEETUPS),
        RowSpec(PortalAreaRow.Events, Icons.Rounded.EventAvailable, ext.success, L10nR.string.paEvents, L10nR.string.paEventsSub, null),
        RowSpec(PortalAreaRow.Courses, Icons.Rounded.School, ext.nostr, L10nR.string.paCourses, L10nR.string.paCoursesSub, PortalAreaAnchors.COURSES),
        RowSpec(PortalAreaRow.Map, Icons.Rounded.Map, ext.community, L10nR.string.paMap, L10nR.string.paMapSub, PortalAreaAnchors.MAP),
        RowSpec(PortalAreaRow.Mine, Icons.Rounded.EditCalendar, colors.primary, L10nR.string.paMine, L10nR.string.paMineSub, PortalAreaAnchors.MINE),
        RowSpec(PortalAreaRow.Web, Icons.Rounded.Language, colors.onSurfaceVariant, L10nR.string.paWeb, L10nR.string.paWebSub, null),
    )
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scrollState = rememberScrollState()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.paTitle),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .guideScrollContainer(scrollState)
                .verticalScroll(scrollState)
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            rows.forEach { spec ->
                AccentCard(
                    accent = spec.color,
                    onClick = { onRowClick(spec.row) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (spec.anchor != null) Modifier.guideAnchor(spec.anchor) else Modifier),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBox(spec.icon, spec.color)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(spec.title),
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                                color = colors.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(spec.subtitle),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = ext.textTertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        RowChevron()
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun PortalAreaScreenPreview() {
    EinundzwanzigTheme { PortalAreaScreen(onBack = {}, onRowClick = {}) }
}
