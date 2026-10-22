package space.einundzwanzig.meetup.feature.events.calendar

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.EventCalendar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.calendar.CalendarEventService
import space.einundzwanzig.meetup.data.calendar.EventCalendarRepository
import space.einundzwanzig.meetup.data.calendar.EventRsvpService
import space.einundzwanzig.meetup.data.calendar.format.CalendarFormat
import space.einundzwanzig.meetup.data.calendar.format.IssuerInput
import space.einundzwanzig.meetup.data.calendar.merge.CalendarMerger
import space.einundzwanzig.meetup.data.calendar.model.CalendarItem
import space.einundzwanzig.meetup.data.calendar.model.CalendarTypeFilter
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.calendar.model.RsvpStatus
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeSessionService
import space.einundzwanzig.meetup.data.issuance.event.EventSessionError
import space.einundzwanzig.meetup.feature.events.common.EventCrests
import space.einundzwanzig.meetup.feature.events.common.EventsIdentity
import space.einundzwanzig.meetup.feature.events.common.EventsSessions
import space.einundzwanzig.meetup.feature.events.common.MessageAction
import space.einundzwanzig.meetup.feature.events.common.UiMessage
import space.einundzwanzig.meetup.feature.events.common.UiText
import space.einundzwanzig.meetup.feature.events.common.ZoneProvider
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** The three views of the calendar (segmented switcher). */
enum class CalendarView { MONTH, YEAR, LIST }

/** "Helfer bearbeiten" sheet (spec 10 §2.3). */
@Immutable
data class HelperEditorState(
    val creator: String,
    val helpers: List<String>,
    val input: String = "",
    val error: UiText? = null,
    val saving: Boolean = false,
)

/** Everything the details sheet of one item needs besides the item. */
@Immutable
data class CalendarItemDetails(
    val item: CalendarItem,
    /** `myPubkey != null && nostr.isIssuer(myPubkey)` (no epoch). */
    val iAmIssuer: Boolean,
    /** `myPubkey == nostr.pubkey` (exact compare). */
    val isCreator: Boolean,
    /** Session button visible: issuer, coordinates set and badge window open. */
    val showSessionButton: Boolean,
    /** A running session belongs to this event ("QR anzeigen"). */
    val sessionRunning: Boolean,
    val links: List<String>,
    val rsvp: RsvpStatus?,
    val rsvpBusy: Boolean,
)

@Immutable
data class EventCalendarUiState(
    val zone: ZoneId,
    val today: LocalDate,
    val focused: YearMonth,
    val selected: LocalDate,
    val now: Instant,
    val loading: Boolean = true,
    val view: CalendarView = CalendarView.MONTH,
    val typeFilter: CalendarTypeFilter = CalendarTypeFilter.ALL,
    val query: String = "",
    val worldwide: Boolean = false,
    val items: List<CalendarItem> = emptyList(),
    val filtered: List<CalendarItem> = emptyList(),
    val byDay: Map<LocalDate, List<CalendarItem>> = emptyMap(),
    val myPubkey: String? = null,
    val runningSessionId: String? = null,
    val startingSession: Boolean = false,
    val rsvps: Map<String, RsvpStatus> = emptyMap(),
    val rsvpBusy: String? = null,
    val details: CalendarItemDetails? = null,
    val confirmCancel: Boolean = false,
    val cancelling: Boolean = false,
    val helperEditor: HelperEditorState? = null,
) {
    val filterActive: Boolean get() = CalendarMerger.isFilterActive(typeFilter, query)

    /** Items of the selected day (month view). */
    val selectedItems: List<CalendarItem> get() = byDay[selected].orEmpty()

    /** List view: filtered items from now − 1 day. */
    val upcoming: List<CalendarItem> get() = CalendarMerger.upcoming(filtered, now)

    /** Year view counts per month of the focused year. */
    val yearCounts: Map<Int, Int> get() = CalendarMerger.countsPerMonth(filtered, focused.year, zone)
}

/** Actions of [EventCalendarScreen]. */
sealed interface EventCalendarAction {
    data class SetView(val view: CalendarView) : EventCalendarAction
    data class ShiftMonth(val delta: Int) : EventCalendarAction
    data class ShiftYear(val delta: Int) : EventCalendarAction
    data class SelectDay(val day: LocalDate) : EventCalendarAction
    data class OpenMonth(val month: Int) : EventCalendarAction
    data class SetType(val type: CalendarTypeFilter) : EventCalendarAction
    data class SetQuery(val query: String) : EventCalendarAction
    data object ClearFilter : EventCalendarAction
    data object ToggleWorldwide : EventCalendarAction
    data object Refresh : EventCalendarAction
    data class OpenDetails(val item: CalendarItem) : EventCalendarAction
    data object CloseDetails : EventCalendarAction
    data class SetRsvp(val status: RsvpStatus) : EventCalendarAction
    data object StartSession : EventCalendarAction
    data object RequestCancel : EventCalendarAction
    data object DismissCancel : EventCalendarAction
    data object ConfirmCancel : EventCalendarAction
    data object OpenHelperEditor : EventCalendarAction
    data object CloseHelperEditor : EventCalendarAction
    data class HelperInput(val text: String) : EventCalendarAction
    data object AddHelper : EventCalendarAction
    data class RemoveHelper(val hex: String) : EventCalendarAction
    data object SaveHelpers : EventCalendarAction

    // Handled by the route.
    data object Back : EventCalendarAction
    data object AddEvent : EventCalendarAction
    data class OpenChat(val event: NostrCalendarEvent) : EventCalendarAction
    data class OpenUrl(val url: String) : EventCalendarAction
    data class AddToCalendar(val item: CalendarItem) : EventCalendarAction
}

/** One-off navigation effects. */
sealed interface EventCalendarEffect {
    /** Session started: the sheet is closed, open the rolling QR. */
    data object OpenRollingQr : EventCalendarEffect
}

/** "Veranstaltungskalender" (Dart `EventCalendarScreen`, spec 10 §2.3). Nothing is persisted. */
@HiltViewModel(assistedFactory = EventCalendarViewModel.Factory::class)
class EventCalendarViewModel @AssistedInject constructor(
    @Assisted key: EventCalendar,
    private val repository: EventCalendarRepository,
    private val events: CalendarEventService,
    private val rsvpService: EventRsvpService,
    private val sessionService: EventBadgeSessionService,
    private val identity: EventsIdentity,
    private val sessions: EventsSessions,
    private val crests: EventCrests,
    private val time: TimeSource,
    zones: ZoneProvider,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: EventCalendar): EventCalendarViewModel
    }

    private val zone = zones.zone()

    private val mutableState: MutableStateFlow<EventCalendarUiState>
    val state: StateFlow<EventCalendarUiState>

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    private val effectChannel = Channel<EventCalendarEffect>(Channel.BUFFERED)
    val effects: Flow<EventCalendarEffect> = effectChannel.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        val now = now()
        val today = now.atZone(zone).toLocalDate()
        val day = key.initialLocalDate ?: today
        mutableState = MutableStateFlow(
            EventCalendarUiState(zone = zone, today = today, focused = YearMonth.from(day), selected = day, now = now),
        )
        state = mutableState.asStateFlow()
        viewModelScope.launch { mutableState.update { it.copy(myPubkey = identity.pubkeyHex()).withDetails() } }
        reloadRunningSession()
        viewModelScope.launch {
            val mine = runCatching { rsvpService.loadMine() }.getOrDefault(emptyMap())
            mutableState.update { it.copy(rsvps = mine + it.rsvps).withDetails() }
        }
        load()
    }

    /** Crest of a meetup row (`MeetupCalendarService.logoFor(title)`). */
    fun crestFor(title: String): String = crests.logoFor(title)

    /** Reloads the running session after returning from the rolling QR (or on resume). */
    fun reloadRunningSession() {
        viewModelScope.launch {
            val id = sessions.runningSessionMeetupId()
            mutableState.update { it.copy(runningSessionId = id).withDetails() }
        }
    }

    /** Dart `_load()`: merged items for the current community/worldwide mode. */
    fun load() {
        loadJob?.cancel()
        mutableState.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            val worldwide = state.value.worldwide
            val items = repository.load(worldwide)
            mutableState.update { it.copy(loading = false, items = items, now = now()).refiltered() }
        }
    }

    fun onAction(action: EventCalendarAction) {
        when (action) {
            is EventCalendarAction.SetView -> mutableState.update { it.copy(view = action.view) }
            is EventCalendarAction.ShiftMonth -> mutableState.update { it.copy(focused = it.focused.plusMonths(action.delta.toLong())) }
            is EventCalendarAction.ShiftYear -> mutableState.update { it.copy(focused = it.focused.plusYears(action.delta.toLong())) }
            is EventCalendarAction.SelectDay -> mutableState.update { it.copy(selected = action.day) }
            is EventCalendarAction.OpenMonth -> mutableState.update {
                it.copy(focused = YearMonth.of(it.focused.year, action.month), view = CalendarView.MONTH)
            }
            is EventCalendarAction.SetType -> mutableState.update { it.copy(typeFilter = action.type).refiltered() }
            is EventCalendarAction.SetQuery -> mutableState.update { it.copy(query = action.query).refiltered() }
            EventCalendarAction.ClearFilter -> mutableState.update {
                it.copy(typeFilter = CalendarTypeFilter.ALL, query = "").refiltered()
            }
            EventCalendarAction.ToggleWorldwide -> {
                mutableState.update { it.copy(worldwide = !it.worldwide) }
                load()
            }
            EventCalendarAction.Refresh -> if (!state.value.loading) load()
            is EventCalendarAction.OpenDetails -> openDetails(action.item)
            EventCalendarAction.CloseDetails -> mutableState.update {
                it.copy(details = null, confirmCancel = false, helperEditor = null)
            }
            is EventCalendarAction.SetRsvp -> setRsvp(action.status)
            EventCalendarAction.StartSession -> startSession()
            EventCalendarAction.RequestCancel -> mutableState.update { it.copy(confirmCancel = true) }
            EventCalendarAction.DismissCancel -> mutableState.update { it.copy(confirmCancel = false) }
            EventCalendarAction.ConfirmCancel -> cancelEvent()
            EventCalendarAction.OpenHelperEditor -> openHelperEditor()
            EventCalendarAction.CloseHelperEditor -> mutableState.update {
                if (it.helperEditor?.saving == true) it else it.copy(helperEditor = null)
            }
            is EventCalendarAction.HelperInput -> updateEditor { it.copy(input = action.text, error = null) }
            EventCalendarAction.AddHelper -> addHelper()
            is EventCalendarAction.RemoveHelper -> updateEditor {
                if (it.saving) it else it.copy(helpers = it.helpers - action.hex)
            }
            EventCalendarAction.SaveHelpers -> saveHelpers()
            EventCalendarAction.Back, EventCalendarAction.AddEvent, is EventCalendarAction.OpenChat,
            is EventCalendarAction.OpenUrl, is EventCalendarAction.AddToCalendar,
            -> Unit
        }
    }

    private fun openDetails(item: CalendarItem) {
        mutableState.update { it.copy(details = detailsFor(item, it), now = now()) }
        val event = item.nostr ?: return
        if (event.badgeEnabled) {
            viewModelScope.launch { identity.prefetchDisplayNames(listOf(event.pubkey) + event.issuers) }
        }
    }

    private fun setRsvp(status: RsvpStatus) {
        val s = state.value
        val event = s.details?.item?.nostr ?: return
        val address = event.address
        if (s.rsvpBusy == address) return
        val previous = s.rsvps[address]
        mutableState.update { it.copy(rsvps = it.rsvps + (address to status), rsvpBusy = address).withDetails() }
        viewModelScope.launch {
            val error = rsvpService.setStatus(address, event.pubkey, status)
            mutableState.update { st ->
                val restored = if (error == null) {
                    st.rsvps
                } else if (previous != null) {
                    st.rsvps + (address to previous)
                } else {
                    st.rsvps - address
                }
                st.copy(rsvps = restored, rsvpBusy = null).withDetails()
            }
            if (error != null) {
                messageChannel.send(UiMessage(UiText.res(L10nR.string.rsvpFailed, rsvpErrorText(error)), Tone.Error))
            }
        }
    }

    private fun startSession() {
        val s = state.value
        if (s.startingSession) return
        val event = s.details?.item?.nostr ?: return
        mutableState.update { it.copy(startingSession = true) }
        viewModelScope.launch {
            val result = try {
                sessionService.start(event)
            } finally {
                mutableState.update { it.copy(startingSession = false) }
            }
            if (result.ok) {
                mutableState.update { it.copy(details = null) }
                effectChannel.send(EventCalendarEffect.OpenRollingQr)
            } else {
                messageChannel.send(sessionErrorMessage(result.error, result.distanceKm))
            }
        }
    }

    private fun cancelEvent() {
        val s = state.value
        val event = s.details?.item?.nostr ?: return
        if (s.cancelling) return
        mutableState.update { it.copy(confirmCancel = false, cancelling = true) }
        viewModelScope.launch {
            val ok = try {
                events.cancelEvent(event)
            } finally {
                mutableState.update { it.copy(cancelling = false) }
            }
            mutableState.update { it.copy(details = null) }
            if (ok) {
                messageChannel.send(UiMessage(UiText.res(L10nR.string.evCancelDone), Tone.Success))
                load()
            } else {
                messageChannel.send(UiMessage(UiText.res(L10nR.string.evCancelFailed), Tone.Error))
            }
        }
    }

    private fun openHelperEditor() {
        val event = state.value.details?.item?.nostr ?: return
        mutableState.update {
            it.copy(helperEditor = HelperEditorState(event.pubkey, IssuerInput.initialHelpers(event.issuers, event.pubkey)))
        }
    }

    private fun addHelper() {
        val editor = state.value.helperEditor ?: return
        if (editor.saving) return
        when (val r = IssuerInput.addHelper(editor.input, editor.creator, editor.helpers)) {
            is IssuerInput.AddResult.Added -> {
                updateEditor { it.copy(helpers = it.helpers + r.hex, input = "", error = null) }
                viewModelScope.launch { identity.prefetchDisplayNames(listOf(r.hex)) }
            }
            IssuerInput.AddResult.Invalid -> updateEditor { it.copy(error = UiText.res(L10nR.string.evIssuersInvalid)) }
            IssuerInput.AddResult.Duplicate -> updateEditor { it.copy(error = UiText.res(L10nR.string.evIssuersDuplicate)) }
        }
    }

    private fun saveHelpers() {
        val editor = state.value.helperEditor ?: return
        val event = state.value.details?.item?.nostr ?: return
        if (editor.saving) return
        updateEditor { it.copy(saving = true) }
        viewModelScope.launch {
            val ok = try {
                events.updateIssuers(event, editor.helpers)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            mutableState.update { it.copy(helperEditor = null, details = if (ok) null else it.details) }
            if (ok) {
                messageChannel.send(UiMessage(UiText.res(L10nR.string.evIssuersSaved), Tone.Success))
                load()
            } else {
                messageChannel.send(UiMessage(UiText.res(L10nR.string.evIssuersFailed), Tone.Error))
            }
        }
    }

    private fun updateEditor(transform: (HelperEditorState) -> HelperEditorState) {
        mutableState.update { s -> s.helperEditor?.let { s.copy(helperEditor = transform(it)) } ?: s }
    }

    private fun EventCalendarUiState.refiltered(): EventCalendarUiState {
        val f = CalendarMerger.filter(items, typeFilter, query)
        return copy(filtered = f, byDay = CalendarMerger.byDay(f, zone))
    }

    private fun EventCalendarUiState.withDetails(): EventCalendarUiState =
        details?.let { copy(details = detailsFor(it.item, this)) } ?: this

    private fun detailsFor(item: CalendarItem, s: EventCalendarUiState): CalendarItemDetails {
        val n = item.nostr
        val me = s.myPubkey
        val iAmIssuer = n != null && me != null && n.isIssuer(me)
        return CalendarItemDetails(
            item = item,
            iAmIssuer = iAmIssuer,
            isCreator = n != null && me != null && me == n.pubkey,
            showSessionButton = n != null && iAmIssuer && !n.hasNoLocation && n.isBadgeWindowOpen(now(), zone),
            sessionRunning = n != null && s.runningSessionId?.contains(n.dTag) == true,
            links = CalendarFormat.links(item.description, item.location),
            rsvp = n?.let { s.rsvps[it.address] },
            rsvpBusy = n != null && s.rsvpBusy == n.address,
        )
    }

    private fun now(): Instant = Instant.ofEpochMilli(time.nowMillis())

    companion object {
        /** Text of an RSVP error (the NotAccepted message is localized). */
        internal fun rsvpErrorText(error: space.einundzwanzig.meetup.data.calendar.RsvpError): Any = when (error) {
            space.einundzwanzig.meetup.data.calendar.RsvpError.NotAccepted ->
                UiText.res(space.einundzwanzig.meetup.feature.events.R.string.native_events_rsvp_not_accepted)
            is space.einundzwanzig.meetup.data.calendar.RsvpError.SigningFailed -> error.message
        }

        /** Snackbar of a failed session start (spec 10 §2.3). */
        internal fun sessionErrorMessage(error: EventSessionError?, distanceKm: Double?): UiMessage {
            val text = when (error) {
                EventSessionError.NO_IDENTITY -> UiText.res(L10nR.string.evSessionNoIdentity)
                EventSessionError.NOT_ISSUER -> UiText.res(L10nR.string.evSessionNotIssuer)
                EventSessionError.OUTSIDE_WINDOW -> UiText.res(L10nR.string.evSessionOutsideWindow)
                EventSessionError.NO_EVENT_LOCATION -> UiText.res(L10nR.string.evSessionNoEventLocation)
                EventSessionError.LOCATION_UNAVAILABLE -> UiText.res(L10nR.string.evSessionNoLocation)
                EventSessionError.LOCATION_SERVICE_OFF -> UiText.res(L10nR.string.evSessionLocationOff)
                EventSessionError.LOCATION_DENIED -> UiText.res(L10nR.string.evSessionLocationDenied)
                EventSessionError.LOCATION_DENIED_FOREVER -> UiText.res(L10nR.string.evSessionLocationDeniedForever)
                EventSessionError.LOCATION_NO_FIX -> UiText.res(L10nR.string.evSessionLocationNoFix)
                EventSessionError.TOO_FAR_AWAY ->
                    UiText.res(L10nR.string.evSessionTooFar, (distanceKm ?: 0.0).toStringAsFixed(1))
                EventSessionError.SESSION_FAILED, null -> UiText.res(L10nR.string.evSessionFailed)
            }
            val action = when (error) {
                EventSessionError.LOCATION_SERVICE_OFF -> MessageAction.OPEN_LOCATION_SETTINGS
                EventSessionError.LOCATION_DENIED_FOREVER -> MessageAction.OPEN_APP_SETTINGS
                else -> null
            }
            return UiMessage(
                text = text,
                tone = Tone.Error,
                durationMillis = if (error == EventSessionError.LOCATION_NO_FIX) {
                    AppSnackbarDurations.VeryLong
                } else {
                    AppSnackbarDurations.Long
                },
                actionLabel = if (action != null) L10nR.string.evSessionOpenSettings else null,
                action = action,
            )
        }
    }
}
