package space.einundzwanzig.meetup.feature.wallet.map

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.feature.wallet.domain.WalletLibrary
import space.einundzwanzig.meetup.feature.wallet.domain.ensureLoaded
import javax.inject.Inject

/** State of the badge world map (spec 03 §2.4, §3.4). */
@Immutable
internal data class WorldMapUiState(
    val loaded: Boolean = false,
    /** Badges with `lat != 0 || lng != 0`, in collection order. */
    val located: List<MeetupBadge> = emptyList(),
    /** Distinct meetup names of [located]. */
    val cityCount: Int = 0,
    val selected: MeetupBadge? = null,
    val sharing: Boolean = false,
)

internal sealed interface WorldMapAction {
    data class Select(val badge: MeetupBadge?) : WorldMapAction
    data object Share : WorldMapAction
    data object Back : WorldMapAction
}

/** All located badges of the wallet (the full collection, organizer markers included). */
@HiltViewModel
internal class WorldMapViewModel @Inject constructor(repository: BadgeRepository) : ViewModel() {

    private val selected = MutableStateFlow<MeetupBadge?>(null)
    private val sharing = MutableStateFlow(false)

    val state: StateFlow<WorldMapUiState> =
        combine(repository.badges, repository.loaded, selected, sharing) { all, loaded, sel, isSharing ->
            build(all, loaded, sel, isSharing)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            build(repository.badges.value, repository.loaded.value, null, false),
        )

    init {
        repository.ensureLoaded(viewModelScope)
    }

    fun select(badge: MeetupBadge?) {
        selected.value = badge
    }

    /** Starts a share: hides the info card so it is not captured. False if one is running. */
    fun beginShare(): Boolean {
        if (sharing.value) return false
        selected.value = null
        sharing.update { true }
        return true
    }

    fun endShare() {
        sharing.update { false }
    }

    private fun build(all: List<MeetupBadge>, loaded: Boolean, sel: MeetupBadge?, isSharing: Boolean): WorldMapUiState {
        val located = WalletLibrary.located(all)
        return WorldMapUiState(
            loaded = loaded,
            located = located,
            cityCount = WalletLibrary.cityCount(located),
            selected = sel?.takeIf { s -> located.any { it === s } },
            sharing = isSharing,
        )
    }
}
