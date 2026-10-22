package space.einundzwanzig.meetup.feature.reputation.card

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.feature.reputation.data.ReputationDataSource
import javax.inject.Inject

/** The numbers of the "Reputations-Profil" card (spec 04 §2.2). */
@Immutable
internal data class ReputationCardInfo(
    /** `nickname`, else `fullName`, else `null` (the screen shows `rcMember`). */
    val displayName: String?,
    val score: Double,
    /** Local level wire string. */
    val level: String,
    val totalBadges: Int,
    val uniqueMeetups: Int,
    val uniqueCities: Int,
    val uniqueSigners: Int,
    val accountAgeDays: Int,
) {
    /** `score.totalBadges == 0`: the no-data variant. */
    val hasNoData: Boolean get() = totalBadges == 0
}

@Immutable
internal data class ReputationCardUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val info: ReputationCardInfo? = null,
)

internal sealed interface ReputationCardAction {
    data object Share : ReputationCardAction
    data object Retry : ReputationCardAction
}

@HiltViewModel
internal class ReputationCardViewModel @Inject constructor(
    private val source: ReputationDataSource,
) : ViewModel() {

    private val _state = MutableStateFlow(ReputationCardUiState())
    val state: StateFlow<ReputationCardUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun onAction(action: ReputationCardAction) {
        when (action) {
            ReputationCardAction.Retry -> load()
            ReputationCardAction.Share -> Unit // capture and share run in the route
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true, loadFailed = false) }
        viewModelScope.launch {
            try {
                val data = source.loadCard()
                val user = data.user
                val score = data.score
                val name = when {
                    user.nickname.isNotEmpty() -> user.nickname
                    user.fullName.isNotEmpty() -> user.fullName
                    else -> null
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        info = ReputationCardInfo(
                            displayName = name,
                            score = score.totalScore,
                            level = score.level,
                            totalBadges = score.totalBadges,
                            uniqueMeetups = score.uniqueMeetups,
                            uniqueCities = score.uniqueCities,
                            uniqueSigners = score.uniqueSigners,
                            accountAgeDays = score.accountAgeDays,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Reputations-Profil konnte nicht geladen werden", e)
                _state.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
    }

    private companion object {
        const val TAG = "ReputationCard"
    }
}
