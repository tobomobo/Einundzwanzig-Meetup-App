package space.einundzwanzig.meetup.feature.events.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.EventCalendar
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.MeetupCalendar
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.motion.Pressable
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.core.ui.theme.elevationShadow

/** Actions of the events hub. */
enum class EventsHubAction { OPEN_MEETUPS, OPEN_CALENDAR }

/** Bottom-bar tab "Events" (spec 10 §2.1). Stateless chooser, so it needs no ViewModel. */
@Composable
fun EventsHubRoute() {
    val navigator = LocalNavigator.current
    EventsHubScreen { action ->
        when (action) {
            EventsHubAction.OPEN_MEETUPS -> navigator.navigate(MeetupCalendar())
            EventsHubAction.OPEN_CALENDAR -> navigator.navigate(EventCalendar())
        }
    }
}

/** Events hub: two cards, "Meetups" and "Veranstaltungskalender". */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EventsHubScreen(onAction: (EventsHubAction) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { AppLargeTopBar(title = stringResource(L10nR.string.hubTitle), scrollBehavior = scrollBehavior) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HubCard(
                icon = Icons.Rounded.Groups,
                accent = MaterialTheme.colorScheme.primary,
                title = stringResource(L10nR.string.hubMeetups),
                subtitle = stringResource(L10nR.string.hubMeetupsSub),
                iconShape = MaterialShapes.Cookie9Sided.toShape(),
                onClick = { onAction(EventsHubAction.OPEN_MEETUPS) },
                modifier = Modifier.testTag("eventsHub.meetups"),
            )
            HubCard(
                icon = Icons.Rounded.CalendarMonth,
                accent = MaterialTheme.extendedColors.community,
                title = stringResource(L10nR.string.hubCalendar),
                subtitle = stringResource(L10nR.string.hubCalendarSub),
                iconShape = MaterialShapes.Clover4Leaf.toShape(),
                onClick = { onAction(EventsHubAction.OPEN_CALENDAR) },
                modifier = Modifier.testTag("eventsHub.calendar"),
            )
        }
    }
}

@Composable
private fun HubCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    iconShape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val card = MaterialTheme.colorScheme.surfaceContainer
    Pressable(onClick = onClick, modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .elevationShadow(2f, shape, accent)
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.10f), card.copy(alpha = 0.98f))), shape)
                .border(0.8.dp, accent.copy(alpha = 0.28f), shape)
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(54.dp).background(accent.copy(alpha = 0.16f), iconShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = accent)
        }
    }
}

@Preview
@Composable
private fun EventsHubPreview() {
    EinundzwanzigTheme { EventsHubScreen {} }
}

