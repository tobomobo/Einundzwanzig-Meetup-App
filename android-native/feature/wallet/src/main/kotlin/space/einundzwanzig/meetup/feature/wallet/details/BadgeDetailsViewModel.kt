package space.einundzwanzig.meetup.feature.wallet.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import space.einundzwanzig.meetup.core.navigation.BadgeDetails
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.feature.wallet.domain.WalletLibrary
import space.einundzwanzig.meetup.feature.wallet.domain.ensureLoaded

/** State of the badge details screen (spec 03 §2.2). */
@Immutable
internal data class BadgeDetailsUiState(
    val loaded: Boolean = false,
    /** The resolved badge, null when it is no longer in the wallet. */
    val badge: MeetupBadge? = null,
    /** `getVerificationHash()` (16 hex chars). */
    val hash: String = "",
    /** True while the share image is rendered. */
    val sharing: Boolean = false,
)

/**
 * Badge details: resolves the [BadgeDetails] key against the live collection (the badge at
 * `collectionIndex` if its union key matches, else the first with that union key).
 */
@HiltViewModel(assistedFactory = BadgeDetailsViewModel.Factory::class)
internal class BadgeDetailsViewModel @AssistedInject constructor(
    @Assisted private val key: BadgeDetails,
    repository: BadgeRepository,
) : ViewModel() {

    private val sharing = MutableStateFlow(false)

    val state: StateFlow<BadgeDetailsUiState> =
        combine(repository.badges, repository.loaded, sharing) { all, loaded, isSharing -> build(all, loaded, isSharing) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(repository.badges.value, repository.loaded.value, false))

    init {
        repository.ensureLoaded(viewModelScope)
    }

    /** Marks the share render as running; returns false if one is already running. */
    fun beginShare(): Boolean {
        if (sharing.value) return false
        sharing.update { true }
        return true
    }

    fun endShare() {
        sharing.update { false }
    }

    private fun build(all: List<MeetupBadge>, loaded: Boolean, isSharing: Boolean): BadgeDetailsUiState {
        val badge = WalletLibrary.resolve(all, key.unionKey, key.collectionIndex)
        return BadgeDetailsUiState(
            loaded = loaded,
            badge = badge,
            hash = badge?.verificationHash().orEmpty(),
            sharing = isSharing,
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(key: BadgeDetails): BadgeDetailsViewModel
    }
}
