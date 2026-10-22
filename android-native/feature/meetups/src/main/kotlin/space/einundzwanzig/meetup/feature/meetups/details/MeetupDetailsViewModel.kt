package space.einundzwanzig.meetup.feature.meetups.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.data.calendar.MeetupCalendarService
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.meetups.MeetupScreenRules
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** State of the meetup detail page (Dart `MeetupDetailsScreen`, spec 09 §2.3). */
@Immutable
data class MeetupDetailsUiState(
    val resolving: Boolean = true,
    val meetup: Meetup? = null,
    val datesLoading: Boolean = true,
    val dates: List<MeetupDate> = emptyList(),
    /** Index into [dates] of the open details sheet. */
    val openDate: Int? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val notFound: Boolean get() = !resolving && meetup == null
}

sealed interface MeetupDetailsAction {
    data object Back : MeetupDetailsAction
    data class OpenDate(val index: Int) : MeetupDetailsAction
    data object CloseDate : MeetupDetailsAction

    /** A data-derived link: Telegram/community, Twitter, route (safe launcher, snackbar on failure). */
    data class OpenLink(val url: String) : MeetupDetailsAction

    /** `https://njump.me/<npub>`: failures are only logged (Dart). */
    data class OpenNostr(val npub: String) : MeetupDetailsAction
}

@HiltViewModel(assistedFactory = MeetupDetailsViewModel.Factory::class)
class MeetupDetailsViewModel @AssistedInject constructor(
    @Assisted private val meetupId: String,
    private val meetups: MeetupService,
    private val calendar: MeetupCalendarService,
    private val time: TimeSource,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(meetupId: String): MeetupDetailsViewModel
    }

    private val _state = MutableStateFlow(MeetupDetailsUiState())
    val state: StateFlow<MeetupDetailsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val meetup = resolve()
            _state.update { it.copy(resolving = false, meetup = meetup, datesLoading = meetup != null) }
            if (meetup != null) loadDates(meetup)
        }
    }

    fun onAction(action: MeetupDetailsAction) {
        when (action) {
            is MeetupDetailsAction.OpenDate -> _state.update { it.copy(openDate = action.index) }
            MeetupDetailsAction.CloseDate -> _state.update { it.copy(openDate = null) }
            else -> Unit
        }
    }

    /** ROUTES.md: portal meetup list (cache, else one fetch), then the bundled offline list. */
    private suspend fun resolve(): Meetup? {
        if (meetupId.isEmpty()) return null
        meetups.cached.firstOrNull { it.id == meetupId }?.let { return it }
        val fetched = runSuspendCatching { meetups.fetchMeetups() }.getOrDefault(emptyList())
        fetched.firstOrNull { it.id == meetupId }?.let { return it }
        return meetups.fallbackMeetups.firstOrNull { it.id == meetupId }
    }

    /**
     * Dart `_load`: portal-first dates whose title or location contains the city, sorted by start.
     * Native addition (spec 09 §14 item 15): dates older than now − 6 h are dropped, so the ICS
     * fallback no longer lists past dates (the portal path already applies this cutoff).
     */
    private suspend fun loadDates(meetup: Meetup) {
        val all = runSuspendCatching { calendar.fetchPortalFirst() }.getOrDefault(emptyList())
        val cutoff = Instant.ofEpochMilli(time.nowMillis()).minus(PAST_HOURS, ChronoUnit.HOURS)
        val dates = all
            .filter { MeetupScreenRules.detailsDateMatches(it.title, it.location, meetup.city) }
            .filter { it.start.isAfter(cutoff) }
            .sortedBy { it.start }
        _state.update { it.copy(datesLoading = false, dates = dates) }
    }

    private companion object {
        const val PAST_HOURS = 6L
    }
}
