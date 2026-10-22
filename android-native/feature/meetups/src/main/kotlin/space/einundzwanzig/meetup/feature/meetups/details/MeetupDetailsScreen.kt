package space.einundzwanzig.meetup.feature.meetups.details

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.EmptyState
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.component.MonoText
import space.einundzwanzig.meetup.core.ui.crest.MeetupCrestWatermark
import space.einundzwanzig.meetup.core.ui.nostr.NpubFormat
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.calendar.format.displayTitle
import space.einundzwanzig.meetup.data.calendar.format.displayLocation
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.feature.meetups.R
import space.einundzwanzig.meetup.feature.meetups.common.LinkFailure
import space.einundzwanzig.meetup.feature.meetups.common.MeetupFormats
import space.einundzwanzig.meetup.feature.meetups.common.SectionCard
import space.einundzwanzig.meetup.feature.meetups.common.rememberLinkOpener
import java.time.Instant
import java.time.ZoneId
import space.einundzwanzig.meetup.core.l10n.R as L10nR

internal object MeetupDetailsTags {
    const val DATES = "details_dates"
    const val TELEGRAM = "details_link_telegram"
    const val TWITTER = "details_link_twitter"
    const val NOSTR = "details_link_nostr"
    const val ROUTE = "details_route"
    const val SHEET = "details_date_sheet"
    fun date(index: Int) = "details_date_$index"
}

/** Stateful meetup detail page (spec 09 §2.3). */
@Composable
fun MeetupDetailsRoute(
    meetupId: String,
    viewModel: MeetupDetailsViewModel = hiltViewModel<MeetupDetailsViewModel, MeetupDetailsViewModel.Factory>(
        key = "meetup-details-$meetupId",
        creationCallback = { it.create(meetupId) },
    ),
) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val links = rememberLinkOpener(snackbar)
    MeetupDetailsScreen(state, snackbar) { action ->
        when (action) {
            MeetupDetailsAction.Back -> navigator.back()
            is MeetupDetailsAction.OpenLink -> links.open(action.url, LinkFailure.Snackbar)
            is MeetupDetailsAction.OpenNostr -> links.open("https://njump.me/${action.npub.trim()}", LinkFailure.Silent)
            else -> viewModel.onAction(action)
        }
    }
}

/** Stateless meetup detail page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetupDetailsScreen(
    state: MeetupDetailsUiState,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onAction: (MeetupDetailsAction) -> Unit,
) {
    val meetup = state.meetup
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            AppLargeTopBar(
                title = meetup?.city?.uppercase().orEmpty(),
                onBack = { onAction(MeetupDetailsAction.Back) },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (meetup != null && meetup.telegramLink.isNotEmpty()) {
                        IconButton(onClick = { onAction(MeetupDetailsAction.OpenLink(MeetupFormats.communityUrl(meetup.telegramLink))) }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = stringResource(R.string.native_meetups_open_community_link),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.resolving -> FullScreenLoading(Modifier.padding(padding))
            meetup == null -> EmptyState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(R.string.native_meetups_not_found_title),
                subtitle = stringResource(R.string.native_meetups_not_found_sub),
                modifier = Modifier.padding(padding),
            )
            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(meetup)
                DatesCard(state, onAction)
                LinksCard(meetup, onAction)
                LocationCard(meetup, onAction)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    val open = state.openDate?.let { state.dates.getOrNull(it) }
    if (open != null) DateSheet(open, state.zone) { onAction(MeetupDetailsAction.CloseDate) }
}

@Composable
private fun Header(meetup: Meetup) {
    Box(Modifier.fillMaxWidth()) {
        MeetupCrestWatermark(
            meetupName = meetup.city,
            imageUrl = meetup.logoUrl.ifEmpty { null },
            modifier = Modifier.matchParentSize(),
            opacity = 0.12f,
        )
        Column(Modifier.padding(vertical = 8.dp)) {
            val shape = RoundedCornerShape(8.dp)
            Text(
                meetup.country.uppercase(),
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.primary, shape)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(10.dp))
            Text(meetup.city, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
            meetup.groupName?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DatesCard(state: MeetupDetailsUiState, onAction: (MeetupDetailsAction) -> Unit) {
    SectionCard(stringResource(L10nR.string.sectionDates), Icons.Rounded.CalendarMonth, Modifier.testTag(MeetupDetailsTags.DATES)) {
        when {
            state.datesLoading -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                AppLoadingIndicator(size = 36.dp)
            }
            state.dates.isEmpty() -> Text(
                stringResource(L10nR.string.meetupNoDatesCal),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column {
                state.dates.forEachIndexed { i, date ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DateRow(date, state.zone, Modifier.testTag(MeetupDetailsTags.date(i))) {
                        onAction(MeetupDetailsAction.OpenDate(i))
                    }
                }
            }
        }
    }
}

@Composable
private fun weekdayShort(dayOfWeek: Int): String = stringResource(
    when (dayOfWeek) {
        1 -> R.string.native_weekday_short_1
        2 -> R.string.native_weekday_short_2
        3 -> R.string.native_weekday_short_3
        4 -> R.string.native_weekday_short_4
        5 -> R.string.native_weekday_short_5
        6 -> R.string.native_weekday_short_6
        else -> R.string.native_weekday_short_7
    },
)

@Composable
private fun DateRow(date: MeetupDate, zone: ZoneId, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = date.start.atZone(zone)
    val chip = MeetupFormats.detailsChip(t, weekdayShort(t.dayOfWeek.value))
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(100.dp)
                .padding(end = 10.dp),
        ) {
            MonoText(chip, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 2)
        }
        Column(Modifier.weight(1f)) {
            Text(date.displayTitle(LocalContext.current.resources), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (date.location.isNotEmpty()) {
                Text(
                    date.displayLocation(LocalContext.current.resources),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.extendedColors.textTertiary)
    }
}

@Composable
private fun LinksCard(meetup: Meetup, onAction: (MeetupDetailsAction) -> Unit) {
    SectionCard(stringResource(L10nR.string.sectionLinks), Icons.Rounded.Link) {
        val telegram = meetup.telegramLink
        if (telegram.isNotEmpty()) {
            LinkRow("Telegram", telegram, Modifier.testTag(MeetupDetailsTags.TELEGRAM)) {
                onAction(MeetupDetailsAction.OpenLink(MeetupFormats.communityUrl(telegram)))
            }
        }
        val twitter = meetup.twitterUsername
        if (twitter.isNotEmpty()) {
            if (telegram.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LinkRow("Twitter / X", "@$twitter", Modifier.testTag(MeetupDetailsTags.TWITTER)) {
                onAction(MeetupDetailsAction.OpenLink(MeetupFormats.twitterUrl(twitter)))
            }
        }
        val npub = meetup.nostrNpub
        if (npub.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LinkRow("Nostr", NpubFormat.shorten(npub), Modifier.testTag(MeetupDetailsTags.NOSTR), mono = true) {
                onAction(MeetupDetailsAction.OpenNostr(npub))
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.width(96.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (mono) {
            MonoText(value, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, maxLines = 1)
        } else {
            Text(value, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = MaterialTheme.extendedColors.textTertiary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun LocationCard(meetup: Meetup, onAction: (MeetupDetailsAction) -> Unit) {
    SectionCard(stringResource(L10nR.string.sectionLocation), Icons.Rounded.LocationOn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText(MeetupFormats.coordinates4(meetup.lat, meetup.lng), Modifier.weight(1f))
            TextButton(
                onClick = { onAction(MeetupDetailsAction.OpenLink(MeetupFormats.googleMapsRoute(meetup.lat, meetup.lng))) },
                modifier = Modifier.testTag(MeetupDetailsTags.ROUTE),
            ) {
                Icon(Icons.Rounded.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(L10nR.string.meetupRoute))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSheet(date: MeetupDate, zone: ZoneId, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                .testTag(MeetupDetailsTags.SHEET),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(date.displayTitle(LocalContext.current.resources), color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.native_details_time_suffix, MeetupFormats.detailsSheetDate(date.start.atZone(zone))))
            }
            if (date.location.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(date.displayLocation(LocalContext.current.resources))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                stringResource(L10nR.string.sectionDescription),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.extendedColors.textTertiary,
            )
            Text(date.description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview
@Composable
private fun MeetupDetailsPreview() {
    val start = Instant.parse("2026-07-21T16:00:00Z")
    EinundzwanzigTheme {
        MeetupDetailsScreen(
            MeetupDetailsUiState(
                resolving = false,
                meetup = Meetup(
                    id = "107",
                    name = "Einundzwanzig Südniedersachsen",
                    city = "Göttingen",
                    country = "DE",
                    telegramLink = "https://t.me/einundzwanzig_goe",
                    lat = 51.5413,
                    lng = 9.9158,
                    twitterUsername = "einundzwanzig_goe",
                    nostrNpub = "npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc",
                ),
                datesLoading = false,
                dates = listOf(MeetupDate("Göttingen Stammtisch", "Bitcoin & Bier", "Dinx Diner", start, "")),
            ),
            SnackbarHostState(),
        ) {}
    }
}
