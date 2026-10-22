package space.einundzwanzig.meetup.feature.wallet.collection

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import space.einundzwanzig.meetup.core.navigation.BadgeCollection
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.feature.wallet.domain.WalletLibrary
import space.einundzwanzig.meetup.feature.wallet.domain.ensureLoaded

/** State of the collection screen (spec 03 §2.1.1). */
@Immutable
internal data class CollectionUiState(
    val groupKey: String,
    val loaded: Boolean = false,
    /** The group's badges, newest first. */
    val badges: List<MeetupBadge> = emptyList(),
    /** The full collection, for the `#n` numbering (§8.5). */
    val all: List<MeetupBadge> = emptyList(),
) {
    val isOrganizer: Boolean get() = groupKey == WalletLibrary.ORGANIZER_GROUP_KEY

    /** Title for a meetup group: the key, `?` if empty. */
    val meetupTitle: String get() = groupKey.ifEmpty { "?" }
}

/** One wallet group as a 2-column grid (Dart `_CollectionScreen`). Observes the repository. */
@HiltViewModel(assistedFactory = CollectionViewModel.Factory::class)
internal class CollectionViewModel @AssistedInject constructor(
    @Assisted private val key: BadgeCollection,
    repository: BadgeRepository,
) : ViewModel() {

    val state: StateFlow<CollectionUiState> = combine(repository.badges, repository.loaded) { all, loaded ->
        CollectionUiState(
            groupKey = key.groupKey,
            loaded = loaded,
            badges = WalletLibrary.collection(all, key.groupKey, key.query),
            all = all,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial(repository))

    init {
        repository.ensureLoaded(viewModelScope)
    }

    private fun initial(repository: BadgeRepository): CollectionUiState {
        val all = repository.badges.value
        return CollectionUiState(
            groupKey = key.groupKey,
            loaded = repository.loaded.value,
            badges = WalletLibrary.collection(all, key.groupKey, key.query),
            all = all,
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(key: BadgeCollection): CollectionViewModel
    }
}
