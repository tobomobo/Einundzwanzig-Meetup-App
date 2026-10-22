package space.einundzwanzig.meetup.feature.events.editor

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.calendar.CalendarEventService
import space.einundzwanzig.meetup.data.calendar.format.IssuerInput
import space.einundzwanzig.meetup.data.calendar.model.CalendarEventDraft
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeAuthService
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeRight
import space.einundzwanzig.meetup.data.profile.blossom.BlossomUploadService
import space.einundzwanzig.meetup.feature.events.common.BadgeImagePreparer
import space.einundzwanzig.meetup.feature.events.common.UiMessage
import space.einundzwanzig.meetup.feature.events.common.UiText
import space.einundzwanzig.meetup.feature.events.common.ZoneProvider
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

@Immutable
data class EventEditorUiState(
    val zone: ZoneId,
    val now: Instant,
    val title: String = "",
    val location: String = "",
    val description: String = "",
    val allDay: Boolean = false,
    val start: Instant? = null,
    val end: Instant? = null,
    /** `null` while the permission check runs ("Berechtigung wird geprüft …"). */
    val right: EventBadgeRight? = null,
    val badgeEnabled: Boolean = false,
    val badgeImage: String = "",
    val uploading: Boolean = false,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val issuerRows: List<String> = listOf(""),
    val publishing: Boolean = false,
) {
    val mayCreateBadge: Boolean get() = right != null && right != EventBadgeRight.NONE
    val hasLocation: Boolean get() = !(lat == 0.0 && lng == 0.0)

    /** First selectable day of the date pickers (`now − 1 day`). */
    val firstDate: LocalDate get() = now.minus(Duration.ofDays(1)).atZone(zone).toLocalDate()

    /** Last selectable day (`now + 5 × 365 days`). */
    val lastDate: LocalDate get() = now.plus(Duration.ofDays(5 * 365)).atZone(zone).toLocalDate()
}

/** Which date button is being edited. */
enum class DateField { START, END }

sealed interface EventEditorAction {
    data class SetTitle(val value: String) : EventEditorAction
    data class SetLocation(val value: String) : EventEditorAction
    data class SetDescription(val value: String) : EventEditorAction
    data class SetAllDay(val value: Boolean) : EventEditorAction

    /** A picked date (+ time, `null` = time dialog dismissed → 19:00). */
    data class DatePicked(val field: DateField, val date: LocalDate, val time: LocalTime?) : EventEditorAction
    data object ClearEnd : EventEditorAction
    data class SetBadgeEnabled(val value: Boolean) : EventEditorAction
    data class SetBadgeImage(val value: String) : EventEditorAction
    data class ImagePicked(val uri: String) : EventEditorAction
    data class LocationPicked(val lat: Double, val lng: Double) : EventEditorAction
    data object ClearLocation : EventEditorAction
    data class SetIssuerRow(val index: Int, val value: String) : EventEditorAction
    data object AddIssuerRow : EventEditorAction
    data class RemoveIssuerRow(val index: Int) : EventEditorAction
    data object Publish : EventEditorAction

    // Route-handled.
    data object Back : EventEditorAction
    data object PickLocation : EventEditorAction
    data object PickImage : EventEditorAction
}

/** One-off effects. */
sealed interface EventEditorEffect {
    /** Published to ≥ 1 relay: send [space.einundzwanzig.meetup.core.navigation.NavResults.EventPublished] and pop. */
    data object Published : EventEditorEffect
}

/** "Veranstaltung eintragen" (Dart `EventEditorScreen`, spec 10 §2.4). */
@HiltViewModel
class EventEditorViewModel @Inject constructor(
    private val events: CalendarEventService,
    private val auth: EventBadgeAuthService,
    private val blossom: BlossomUploadService,
    private val imagePreparer: BadgeImagePreparer,
    private val time: TimeSource,
    zones: ZoneProvider,
) : ViewModel() {

    private val zone = zones.zone()
    private val mutableState = MutableStateFlow(EventEditorUiState(zone = zone, now = now()))
    val state: StateFlow<EventEditorUiState> = mutableState.asStateFlow()

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    private val effectChannel = Channel<EventEditorEffect>(Channel.BUFFERED)
    val effects: Flow<EventEditorEffect> = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val right = try {
                auth.myRight()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                EventBadgeRight.NONE
            }
            mutableState.update { it.copy(right = right) }
        }
    }

    fun onAction(action: EventEditorAction) {
        when (action) {
            is EventEditorAction.SetTitle -> mutableState.update { it.copy(title = action.value) }
            is EventEditorAction.SetLocation -> mutableState.update { it.copy(location = action.value) }
            is EventEditorAction.SetDescription -> mutableState.update { it.copy(description = action.value) }
            is EventEditorAction.SetAllDay -> mutableState.update { it.copy(allDay = action.value, start = null, end = null) }
            is EventEditorAction.DatePicked -> datePicked(action)
            EventEditorAction.ClearEnd -> mutableState.update { it.copy(end = null) }
            is EventEditorAction.SetBadgeEnabled -> mutableState.update {
                if (it.mayCreateBadge) it.copy(badgeEnabled = action.value) else it
            }
            is EventEditorAction.SetBadgeImage -> mutableState.update { it.copy(badgeImage = action.value) }
            is EventEditorAction.ImagePicked -> upload(action.uri)
            is EventEditorAction.LocationPicked -> mutableState.update { it.copy(lat = action.lat, lng = action.lng) }
            EventEditorAction.ClearLocation -> mutableState.update { it.copy(lat = 0.0, lng = 0.0) }
            is EventEditorAction.SetIssuerRow -> mutableState.update {
                if (action.index !in it.issuerRows.indices) {
                    it
                } else {
                    it.copy(issuerRows = it.issuerRows.toMutableList().apply { set(action.index, action.value) })
                }
            }
            EventEditorAction.AddIssuerRow -> mutableState.update { it.copy(issuerRows = it.issuerRows + "") }
            is EventEditorAction.RemoveIssuerRow -> mutableState.update {
                if (action.index !in it.issuerRows.indices) return@update it
                val rows = it.issuerRows.toMutableList().apply { removeAt(action.index) }
                it.copy(issuerRows = rows.ifEmpty { listOf("") })
            }
            EventEditorAction.Publish -> publish()
            EventEditorAction.Back, EventEditorAction.PickLocation, EventEditorAction.PickImage -> Unit
        }
    }

    /** All-day → local midnight of the date; else the time (19:00 when the time dialog was dismissed). */
    private fun datePicked(action: EventEditorAction.DatePicked) {
        mutableState.update { s ->
            val value = if (s.allDay) {
                action.date.atStartOfDay(zone).toInstant()
            } else {
                action.date.atTime(action.time ?: DEFAULT_TIME).atZone(zone).toInstant()
            }
            when (action.field) {
                DateField.START -> s.copy(start = value)
                DateField.END -> s.copy(end = value)
            }
        }
    }

    private fun upload(uri: String) {
        if (state.value.uploading) return
        mutableState.update { it.copy(uploading = true) }
        viewModelScope.launch {
            val result = try {
                val file = imagePreparer.prepare(uri)
                try {
                    blossom.uploadImage(file)
                } finally {
                    file.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                space.einundzwanzig.meetup.data.profile.blossom.UploadResult.failure(e.message ?: e.toString())
            } finally {
                mutableState.update { it.copy(uploading = false) }
            }
            if (result.ok) {
                mutableState.update { it.copy(badgeImage = result.url.orEmpty()) }
            } else {
                messageChannel.send(
                    UiMessage(UiText.res(L10nR.string.evBadgeUploadFailed, result.error.orEmpty()), Tone.Error),
                )
            }
        }
    }

    private fun publish() {
        val s = state.value
        if (s.publishing) return
        val title = s.title.dartTrim()
        val start = s.start
        val error = when {
            title.isEmpty() -> L10nR.string.calNeedTitle
            start == null -> L10nR.string.calNeedStart
            s.badgeEnabled && s.mayCreateBadge && !s.hasLocation -> L10nR.string.evBadgeNeedLocation
            else -> null
        }
        if (error != null || start == null) {
            messageChannel.trySend(UiMessage(UiText.res(error ?: L10nR.string.calNeedStart), Tone.Error))
            return
        }
        // Double gate kept from Dart: the switch AND the permission.
        val withBadge = s.badgeEnabled && s.mayCreateBadge
        val draft = CalendarEventDraft(
            title = title,
            description = s.description.dartTrim(),
            location = s.location.dartTrim(),
            start = start.atZone(zone),
            end = s.end?.atZone(zone),
            allDay = s.allDay,
            badgeEnabled = withBadge,
            badgeImageUrl = if (withBadge) s.badgeImage.dartTrim() else "",
            lat = if (withBadge) s.lat else 0.0,
            lng = if (withBadge) s.lng else 0.0,
            issuers = if (withBadge) IssuerInput.issuersOf(s.issuerRows) else emptyList(),
        )
        mutableState.update { it.copy(publishing = true) }
        viewModelScope.launch {
            val relays = try {
                events.publishEvent(draft)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0
            } finally {
                mutableState.update { it.copy(publishing = false) }
            }
            if (relays > 0) {
                effectChannel.send(EventEditorEffect.Published)
            } else {
                messageChannel.send(UiMessage(UiText.res(L10nR.string.calPublishFail), Tone.Error))
            }
        }
    }

    private fun now(): Instant = Instant.ofEpochMilli(time.nowMillis())

    companion object {
        /** Time used when the time dialog is dismissed (Dart: 19:00). */
        val DEFAULT_TIME: LocalTime = LocalTime.of(19, 0)
    }
}
