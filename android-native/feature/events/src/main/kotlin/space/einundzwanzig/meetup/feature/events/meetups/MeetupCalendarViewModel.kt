package space.einundzwanzig.meetup.feature.events.meetups

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.MeetupCalendar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.calendar.MeetupCalendarService
import space.einundzwanzig.meetup.data.calendar.MyEventsService
import space.einundzwanzig.meetup.data.calendar.format.CalendarFormat
import space.einundzwanzig.meetup.data.calendar.match.MeetupEventMatcher
import space.einundzwanzig.meetup.data.calendar.meetups.MeetupDatesLogic
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.calendar.portal.PortalRsvp
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.Attendee
import space.einundzwanzig.meetup.feature.events.R
import space.einundzwanzig.meetup.feature.events.common.EventsPortal
import space.einundzwanzig.meetup.feature.events.common.UiMessage
import space.einundzwanzig.meetup.feature.events.common.UiText
import space.einundzwanzig.meetup.feature.events.common.ZoneProvider

/** One card of the meetup dates list (spec 10 §2.2). */
@Immutable
data class MeetupDateRow(
    /** Position in [MeetupCalendarUiState.dates] (stable while the list is shown). */
    val index: Int,
    val date: MeetupDate,
    val dayText: String,
    val monthText: String,
    val timeText: String,
    val crestUrl: String,
    /** Resolved meetup id (star shown only when non-null). */
    val favKey: String?,
    val isFavorite: Boolean,
    val rsvp: RsvpRowState?,
)

/** Portal RSVP row under a card (only for dates with a portal id). */
@Immutable
data class RsvpRowState(
    val eventId: Int,
    /** `-1` = unknown (no count shown). */
    val count: Int,
    val might: Int,
    val going: Boolean,
    val status: String,
    val busy: Boolean,
    val expanded: Boolean,
    /** `null` while the first attendee load runs. */
    val attendees: List<Attendee>?,
)

/** Details sheet of one date. */
@Immutable
data class MeetupDateDetails(
    val row: MeetupDateRow,
    val whenText: String,
    /** Button whose request runs (`attending`/`maybe`), `null` when idle. */
    val busyButton: String?,
)

@Immutable
data class MeetupCalendarUiState(
    val loading: Boolean = true,
    val query: String = "",
    val rows: List<MeetupDateRow> = emptyList(),
    val details: MeetupDateDetails? = null,
    internal val dates: List<MeetupDate> = emptyList(),
    internal val meetups: List<Meetup> = emptyList(),
    internal val favorites: List<String> = emptyList(),
    internal val rsvp: Map<Int, JsonObject> = emptyMap(),
    internal val busy: Set<Int> = emptySet(),
    internal val expanded: Set<Int> = emptySet(),
    internal val attendees: Map<Int, List<Attendee>> = emptyMap(),
    internal val detailsIndex: Int? = null,
    internal val sheetBusy: String? = null,
    internal val initialSearch: String? = null,
    internal val initialMeetupId: String? = null,
)

/** Actions of [MeetupCalendarScreen]. */
sealed interface MeetupCalendarAction {
    data class Search(val text: String) : MeetupCalendarAction
    data class ToggleFavorite(val favKey: String) : MeetupCalendarAction
    data class Rsvp(val eventId: Int, val status: String) : MeetupCalendarAction
    data class ToggleAttendees(val eventId: Int) : MeetupCalendarAction
    data class OpenDetails(val index: Int) : MeetupCalendarAction
    data object CloseDetails : MeetupCalendarAction

    /** Details-sheet toggle ("Ich komme" / "Vielleicht"); tapping the active one withdraws. */
    data class SheetRsvp(val pressed: String) : MeetupCalendarAction
    data object ShareCopied : MeetupCalendarAction
    data object Back : MeetupCalendarAction
    data class OpenUrl(val url: String) : MeetupCalendarAction
    data class Share(val text: String) : MeetupCalendarAction
    data class AddToCalendar(val date: MeetupDate) : MeetupCalendarAction
}

/** "MEETUP TERMINE" (Dart `CalendarScreen`, spec 10 §2.2). */
@HiltViewModel(assistedFactory = MeetupCalendarViewModel.Factory::class)
class MeetupCalendarViewModel @AssistedInject constructor(
    @Assisted private val key: MeetupCalendar,
    private val calendar: MeetupCalendarService,
    private val myEvents: MyEventsService,
    private val meetupService: MeetupService,
    private val profiles: UserProfileRepository,
    private val portal: EventsPortal,
    private val zones: ZoneProvider,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: MeetupCalendar): MeetupCalendarViewModel
    }

    private val mutableState = MutableStateFlow(MeetupCalendarUiState())
    val state: StateFlow<MeetupCalendarUiState> = mutableState.asStateFlow()

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val (search, meetupId) = resolveInitialFilter()
            mutableState.update {
                it.copy(initialSearch = search, initialMeetupId = meetupId, query = search ?: "").rebuilt()
            }
            launch { loadFavorites() }
            launch { loadEvents() }
            launch { loadMeetupCrests() }
        }
    }

    /** Widget route `meetup`: the front favorite card (the one the widget shows), spec 12 §2.5.10. */
    private suspend fun resolveInitialFilter(): Pair<String?, String?> {
        if (!key.preselectNextFavorite) return key.initialSearch to key.initialMeetupId
        val front = myEvents.loadFavoriteCards(updateWidget = false).cards.firstOrNull()
        val favKey = front?.favKey ?: runCatching { profiles.load().homeMeetupId }.getOrDefault("")
        if (favKey.isEmpty()) return null to null
        val city = front?.city ?: meetupService.cityFor(favKey)
        if (city.isEmpty()) return null to null
        return city to meetupService.resolveFavorite(favKey)?.id
    }

    private suspend fun loadFavorites() {
        val user = runCatching { profiles.load() }.getOrNull()
        meetupService.fetchMeetups()
        mutableState.update { it.copy(favorites = user?.favoriteMeetupIds.orEmpty()).rebuilt() }
    }

    private suspend fun loadMeetupCrests() {
        val list = meetupService.fetchMeetups().ifEmpty { meetupService.cached }
        mutableState.update { it.copy(meetups = list).rebuilt() }
    }

    private suspend fun loadEvents() {
        val dates = calendar.fetchPortalFirst()
        val initial = dates.mapNotNull { d -> d.portalEventId?.let { it to PortalRsvp.initial(d) } }.toMap()
        mutableState.update { it.copy(loading = false, dates = dates, rsvp = initial).rebuilt() }
        loadRsvpStatuses(dates.mapNotNull { it.portalEventId })
    }

    private suspend fun loadRsvpStatuses(ids: List<Int>) {
        for (wave in MeetupDatesLogic.rsvpPrefetchWaves(ids)) {
            val responses = coroutineScope {
                wave.map { id -> async { id to portal.getRsvpCached(id) } }.awaitAll()
            }
            mutableState.update { s ->
                val merged = s.rsvp.toMutableMap()
                for ((id, r) in responses) if (r != null) merged[id] = PortalRsvp.merge(merged[id], r)
                s.copy(rsvp = merged).rebuilt()
            }
        }
    }

    fun onAction(action: MeetupCalendarAction) {
        when (action) {
            is MeetupCalendarAction.Search -> mutableState.update { it.copy(query = action.text).rebuilt() }
            is MeetupCalendarAction.ToggleFavorite -> toggleFavorite(action.favKey)
            is MeetupCalendarAction.Rsvp -> viewModelScope.launch { doRsvp(action.eventId, action.status) }
            is MeetupCalendarAction.ToggleAttendees -> toggleAttendees(action.eventId)
            is MeetupCalendarAction.OpenDetails -> mutableState.update { it.copy(detailsIndex = action.index).rebuilt() }
            MeetupCalendarAction.CloseDetails -> mutableState.update { it.copy(detailsIndex = null, sheetBusy = null).rebuilt() }
            is MeetupCalendarAction.SheetRsvp -> sheetRsvp(action.pressed)
            MeetupCalendarAction.ShareCopied -> messageChannel.trySend(
                UiMessage(UiText.res(L10nR.string.evShareCopied), Tone.Warning, AppSnackbarDurations.Short),
            )
            // Handled by the route (navigation / platform).
            MeetupCalendarAction.Back, is MeetupCalendarAction.OpenUrl, is MeetupCalendarAction.Share,
            is MeetupCalendarAction.AddToCalendar,
            -> Unit
        }
    }

    private fun toggleFavorite(favKey: String) {
        viewModelScope.launch {
            val user = runCatching { profiles.load() }.getOrNull() ?: return@launch
            val toggle = MeetupDatesLogic.toggleFavorite(user.favoriteMeetupIds, favKey, meetupService::resolveFavorite)
            runCatching {
                profiles.update { it.copy(favoriteMeetupIds = toggle.favorites, homeMeetupId = toggle.homeMeetupId) }
            }.onFailure { return@launch }
            mutableState.update { it.copy(favorites = toggle.favorites).rebuilt() }
            val label = meetupService.labelFor(favKey)
            messageChannel.send(
                if (toggle.added) {
                    UiMessage(UiText.res(L10nR.string.calFavAdded, label), Tone.Success, AppSnackbarDurations.Short)
                } else {
                    UiMessage(UiText.res(L10nR.string.calFavRemoved, label), Tone.Neutral, AppSnackbarDurations.Short)
                },
            )
        }
    }

    private fun toggleAttendees(eventId: Int) {
        val expanding = eventId !in state.value.expanded
        mutableState.update {
            it.copy(expanded = if (expanding) it.expanded + eventId else it.expanded - eventId).rebuilt()
        }
        if (expanding && eventId !in state.value.attendees) {
            viewModelScope.launch {
                val list = portal.getRsvpAttendees(eventId)
                mutableState.update { it.copy(attendees = it.attendees + (eventId to list)).rebuilt() }
            }
        }
    }

    private fun sheetRsvp(pressed: String) {
        val s = state.value
        if (s.sheetBusy != null) return // double-tap guard
        val id = s.details?.row?.rsvp?.eventId ?: return
        val target = PortalRsvp.toggleTarget(PortalRsvp.status(s.rsvp[id]), pressed)
        mutableState.update { it.copy(sheetBusy = pressed).rebuilt() }
        viewModelScope.launch {
            try {
                doRsvp(id, target)
            } finally {
                mutableState.update { it.copy(sheetBusy = null).rebuilt() }
            }
        }
    }

    private suspend fun doRsvp(eventId: Int, status: String) {
        if (eventId in state.value.busy) return
        if (!portal.tokenMatchesCurrentKey() && !ensurePortalLogin()) return
        mutableState.update { it.copy(busy = it.busy + eventId).rebuilt() }
        val result = try {
            portal.rsvp(eventId, status)
        } finally {
            mutableState.update { it.copy(busy = it.busy - eventId).rebuilt() }
        }
        if (result.ok) {
            mutableState.update { it.copy(rsvp = it.rsvp + (eventId to PortalRsvp.withStatus(it.rsvp[eventId], status))).rebuilt() }
            val fresh = portal.getRsvpCached(eventId, forceRefresh = true)
            if (fresh != null) {
                mutableState.update { it.copy(rsvp = it.rsvp + (eventId to PortalRsvp.merge(it.rsvp[eventId], fresh))).rebuilt() }
            }
        } else {
            messageChannel.send(
                UiMessage(UiText.res(L10nR.string.rsvpFailed, result.error ?: ""), Tone.Error),
            )
        }
    }

    /** Dart `_ensurePortalLogin`: Nostr-signed portal login with progress snackbars. */
    private suspend fun ensurePortalLogin(): Boolean {
        if (portal.tokenMatchesCurrentKey()) return true
        messageChannel.send(
            UiMessage(UiText.res(L10nR.string.portalConnecting), Tone.Neutral, AppSnackbarDurations.ExtraLong),
        )
        val result = portal.loginWithNostr()
        return if (result.ok) {
            messageChannel.send(UiMessage(UiText.res(L10nR.string.portalConnected), Tone.Success))
            true
        } else {
            messageChannel.send(
                UiMessage(
                    UiText.res(R.string.native_events_label_detail, UiText.res(L10nR.string.portalLoginFailed), result.error ?: ""),
                    Tone.Error,
                ),
            )
            false
        }
    }

    /** Recomputes the visible rows and the details sheet from the raw state. */
    private fun MeetupCalendarUiState.rebuilt(): MeetupCalendarUiState {
        val zone = zones.zone()
        val visibleDates = MeetupDatesLogic.filter(dates, query, initialSearch, initialMeetupId, meetups)
        val indexOf = HashMap<MeetupDate, Int>()
        // Identity, not equality: two portal dates may compare equal (spec 10 §14 item 13).
        val identity = java.util.IdentityHashMap<MeetupDate, Int>()
        dates.forEachIndexed { i, d -> identity[d] = i; indexOf.putIfAbsent(d, i) }
        val cachedMeetups = meetups.ifEmpty { meetupService.cached }
        fun rowFor(i: Int, d: MeetupDate): MeetupDateRow {
            val local = d.start.atZone(zone)
            val favKey = MeetupEventMatcher.resolve(d, cachedMeetups)?.id
            val id = d.portalEventId
            val r = id?.let { rsvp[it] }
            return MeetupDateRow(
                index = i,
                date = d,
                dayText = local.dayOfMonth.toString().padStart(2, '0'),
                monthText = local.monthValue.toString().padStart(2, '0'),
                timeText = CalendarFormat.hhmm(d.start, zone),
                crestUrl = MeetupDatesLogic.crestUrl(d, meetups),
                favKey = favKey,
                isFavorite = favKey != null && MeetupDatesLogic.isFavorite(favorites, favKey, meetupService::resolveFavorite),
                rsvp = id?.let {
                    RsvpRowState(
                        eventId = it,
                        count = PortalRsvp.count(r),
                        might = PortalRsvp.might(r),
                        going = portal.isGoing(r),
                        status = PortalRsvp.status(r),
                        busy = it in busy,
                        expanded = it in expanded,
                        attendees = attendees[it],
                    )
                },
            )
        }
        val rows = visibleDates.map { d -> rowFor(identity[d] ?: indexOf.getValue(d), d) }
        val details = detailsIndex?.let { i -> dates.getOrNull(i) }?.let { d ->
            MeetupDateDetails(rowFor(detailsIndex, d), CalendarFormat.meetupWhen(d.start, zone), sheetBusy)
        }
        return copy(rows = rows, details = details)
    }
}
