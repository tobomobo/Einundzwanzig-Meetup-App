package space.einundzwanzig.meetup.feature.community.courses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.navigation.CourseDetails
import space.einundzwanzig.meetup.core.navigation.LecturerDetails
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppMediumTopBar
import space.einundzwanzig.meetup.core.ui.component.EmptyState
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.community.portal.PortalCourse
import space.einundzwanzig.meetup.data.community.portal.PortalCourseEvent
import space.einundzwanzig.meetup.data.community.portal.PortalLecturer
import space.einundzwanzig.meetup.data.community.portal.PortalLink
import space.einundzwanzig.meetup.feature.community.R
import space.einundzwanzig.meetup.feature.community.common.CardRow
import space.einundzwanzig.meetup.feature.community.common.DetailSectionHeader
import space.einundzwanzig.meetup.feature.community.common.LinkRow
import space.einundzwanzig.meetup.feature.community.common.PortalThumb
import space.einundzwanzig.meetup.feature.community.common.RowChevron
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the course/lecturer detail screens. */
internal object DetailTestTags {
    const val LIST = "portalDetailList"
    const val LOADING = "portalDetailLoading"
    const val NOT_FOUND = "portalDetailNotFound"
    const val ADD_TO_CALENDAR = "courseEventCalendar"
    const val EVENT_LINK = "courseEventLink"
    const val LECTURER_CARD = "courseLecturerCard"
}

// ---------------------------------------------------------------------------------------------
// Course detail (spec 11 §2.8)
// ---------------------------------------------------------------------------------------------

@Composable
fun CourseDetailsRoute(
    courseId: Int,
    viewModel: CourseDetailsViewModel = hiltViewModel<CourseDetailsViewModel, CourseDetailsViewModel.Factory>(
        key = "course-$courseId",
        creationCallback = { it.create(courseId) },
    ),
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    CourseDetailsScreen(
        state = state,
        onBack = navigator::back,
        onAction = { action ->
            when (action) {
                is CourseDetailsAction.OpenUrl -> navigator.openUrl(action.url)
                is CourseDetailsAction.AddToCalendar -> context.insertCourseEvent(action.event)
                is CourseDetailsAction.OpenLecturer -> navigator.navigate(LecturerDetails(action.lecturerId))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailsScreen(
    state: CourseDetailsUiState,
    onAction: (CourseDetailsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppMediumTopBar(
                title = stringResource(L10nR.string.crsCourses),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val course = state.course
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                course != null -> CourseDetailsContent(course, onAction)
                state.loading -> FullScreenLoading(Modifier.testTag(DetailTestTags.LOADING))
                else -> NotFound()
            }
        }
    }
}

@Composable
private fun CourseDetailsContent(course: PortalCourse, onAction: (CourseDetailsAction) -> Unit) {
    val nostr = MaterialTheme.extendedColors.nostr
    DetailList {
        item(key = "header") {
            DetailHeader(
                image = course.image,
                icon = Icons.Rounded.School,
                title = course.name,
                subtitle = course.lecturerName,
            )
        }
        if (course.portalLink.startsWith("http")) {
            item(key = "portal") {
                Spacer(Modifier.height(12.dp))
                LinkRow(
                    label = stringResource(L10nR.string.crsOpenPortal),
                    icon = Icons.Rounded.OpenInNew,
                    onClick = { onAction(CourseDetailsAction.OpenUrl(course.portalLink)) },
                )
            }
        }
        if (course.events.isNotEmpty()) {
            item(key = "upcomingHeader") { DetailSectionHeader(stringResource(L10nR.string.crsUpcoming)) }
            itemsIndexed(course.events, key = { i, _ -> "event-$i" }) { _, event ->
                CourseEventRow(event, onAction)
                Spacer(Modifier.height(8.dp))
            }
        }
        if (course.description.isNotEmpty()) {
            item(key = "aboutHeader") { DetailSectionHeader(stringResource(L10nR.string.crsAbout)) }
            item(key = "about") { AboutText(course.description) }
            linkRows(course.links) { onAction(CourseDetailsAction.OpenUrl(it)) }
        }
        if (course.lecturerName.isNotEmpty()) {
            item(key = "lecturerHeader") { DetailSectionHeader(stringResource(L10nR.string.crsLecturer)) }
            item(key = "lecturer") {
                val id = course.lecturerId
                CardRow(
                    onClick = id?.let { { onAction(CourseDetailsAction.OpenLecturer(it)) } },
                    modifier = Modifier.testTag(DetailTestTags.LECTURER_CARD),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PortalThumb(course.lecturerImage, Icons.Rounded.Person, nostr, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                course.lecturerName,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (course.lecturerIntro.isNotEmpty()) {
                                Text(
                                    course.lecturerIntro,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        RowChevron()
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseEventRow(event: PortalCourseEvent, onAction: (CourseDetailsAction) -> Unit) {
    val orange = MaterialTheme.colorScheme.primary
    CardRow(onClick = null) {
        Row(
            Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Event, contentDescription = null, tint = orange, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    event.whenLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (event.location.isNotEmpty()) {
                    Text(
                        event.location,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (event.start != null) {
                IconButton(
                    onClick = { onAction(CourseDetailsAction.AddToCalendar(event)) },
                    modifier = Modifier.testTag(DetailTestTags.ADD_TO_CALENDAR),
                ) {
                    Icon(
                        Icons.Rounded.CalendarMonth,
                        contentDescription = stringResource(R.string.native_community_add_to_calendar),
                        tint = orange,
                    )
                }
            }
            if (event.link.startsWith("http")) {
                IconButton(
                    onClick = { onAction(CourseDetailsAction.OpenUrl(event.link)) },
                    modifier = Modifier.testTag(DetailTestTags.EVENT_LINK),
                ) {
                    Icon(
                        Icons.Rounded.Link,
                        contentDescription = stringResource(R.string.native_community_open_link),
                        tint = orange,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Lecturer detail (spec 11 §2.8)
// ---------------------------------------------------------------------------------------------

@Composable
fun LecturerDetailsRoute(
    lecturerId: Int,
    viewModel: LecturerDetailsViewModel = hiltViewModel<LecturerDetailsViewModel, LecturerDetailsViewModel.Factory>(
        key = "lecturer-$lecturerId",
        creationCallback = { it.create(lecturerId) },
    ),
) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    LecturerDetailsScreen(
        state = state,
        onBack = navigator::back,
        onAction = { action ->
            when (action) {
                is LecturerDetailsAction.OpenUrl -> navigator.openUrl(action.url)
                is LecturerDetailsAction.OpenCourse -> navigator.navigate(CourseDetails(action.courseId))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LecturerDetailsScreen(
    state: LecturerDetailsUiState,
    onAction: (LecturerDetailsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppMediumTopBar(
                title = stringResource(L10nR.string.crsLecturer),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val lecturer = state.lecturer
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                lecturer != null -> LecturerDetailsContent(lecturer, onAction)
                state.loading -> FullScreenLoading(Modifier.testTag(DetailTestTags.LOADING))
                else -> NotFound()
            }
        }
    }
}

@Composable
private fun LecturerDetailsContent(lecturer: PortalLecturer, onAction: (LecturerDetailsAction) -> Unit) {
    val nostr = MaterialTheme.extendedColors.nostr
    DetailList {
        item(key = "header") {
            DetailHeader(image = lecturer.image, icon = Icons.Rounded.Person, title = lecturer.name, subtitle = lecturer.intro)
        }
        if (lecturer.bio.isNotEmpty()) {
            item(key = "aboutHeader") { DetailSectionHeader(stringResource(L10nR.string.lecAbout)) }
            item(key = "about") { AboutText(lecturer.bio) }
        }
        if (lecturer.courses.isNotEmpty()) {
            item(key = "coursesHeader") { DetailSectionHeader(stringResource(L10nR.string.crsCourses)) }
            itemsIndexed(lecturer.courses, key = { i, _ -> "course-$i" }) { _, c ->
                CardRow(onClick = c.id?.let { id -> { onAction(LecturerDetailsAction.OpenCourse(id)) } }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PortalThumb(c.image, Icons.Rounded.School, nostr, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            c.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        RowChevron()
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (lecturer.links.isNotEmpty()) {
            item(key = "linksHeader") { DetailSectionHeader(stringResource(L10nR.string.lecLinks)) }
            items(lecturer.links, key = { "link-${it.label}" }) { link: PortalLink ->
                LinkRow(label = link.text, icon = Icons.Rounded.Link, onClick = { onAction(LecturerDetailsAction.OpenUrl(link.url)) })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun DetailList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(DetailTestTags.LIST),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
        content = content,
    )
}

/** Header card (cCard, radius 12): 64×64 image or icon box, title 17sp w800, subtitle 2 lines. */
@Composable
private fun DetailHeader(image: String, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    CardRow(onClick = null, radius = 12.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PortalThumb(image, icon, MaterialTheme.extendedColors.nostr, size = 64.dp, radius = 14.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = (14 * 1.55).sp),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

private fun LazyListScope.linkRows(links: List<String>, onOpen: (String) -> Unit) {
    items(links, key = { "url-$it" }) { url ->
        LinkRow(label = url, icon = Icons.Rounded.Link, onClick = { onOpen(url) })
    }
}

@Composable
private fun NotFound() {
    EmptyState(
        icon = Icons.Rounded.SearchOff,
        title = stringResource(R.string.native_community_not_found),
        subtitle = "",
        modifier = Modifier.testTag(DetailTestTags.NOT_FOUND),
    )
}

@Preview
@Composable
private fun CourseDetailsScreenPreview() {
    EinundzwanzigTheme {
        CourseDetailsScreen(
            state = CourseDetailsUiState(
                loading = false,
                course = PortalCourse(
                    name = "Bitcoin Grundlagen",
                    description = "Einsteigerkurs über Geld und Selbstverwahrung. https://portal.einundzwanzig.space",
                    image = "",
                    lecturerName = "Satoshi",
                    lecturerIntro = "Pleb",
                    lecturerImage = "",
                    lecturerId = 3,
                    lecturer = JsonObject(emptyMap()),
                    events = listOf(
                        PortalCourseEvent(
                            from = "2026-11-02T18:00:00",
                            location = "Wien",
                            link = "https://example.org",
                            title = "Teil 1",
                            description = "",
                            start = DartDateTime.tryParse("2026-11-02T18:00:00"),
                            whenLabel = "02.11.2026 · 18:00",
                        ),
                    ),
                    portalLink = "https://portal.einundzwanzig.space/course/1",
                    links = listOf("https://portal.einundzwanzig.space"),
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun LecturerDetailsScreenPreview() {
    EinundzwanzigTheme {
        LecturerDetailsScreen(
            state = LecturerDetailsUiState(
                loading = false,
                lecturer = PortalLecturer(
                    name = "Satoshi",
                    intro = "Pleb",
                    bio = "Schreibt Whitepaper.",
                    image = "",
                    courses = emptyList(),
                    links = listOf(PortalLink("Website", "bitcoin.org", "https://bitcoin.org")),
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}
