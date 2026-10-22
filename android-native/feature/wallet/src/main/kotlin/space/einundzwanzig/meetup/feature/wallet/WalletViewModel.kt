package space.einundzwanzig.meetup.feature.wallet

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.FormerCollection
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.reputation.publish.ReputationPublisher
import space.einundzwanzig.meetup.feature.wallet.domain.BadgeFormat
import space.einundzwanzig.meetup.feature.wallet.domain.BadgeGroup
import space.einundzwanzig.meetup.feature.wallet.domain.CollectionSubtitle
import space.einundzwanzig.meetup.feature.wallet.domain.ReputationShare
import space.einundzwanzig.meetup.feature.wallet.domain.WalletLibrary
import space.einundzwanzig.meetup.feature.wallet.domain.ensureLoaded
import javax.inject.Inject
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** A wallet group ready for display (collection card or year section). */
@Immutable
internal data class GroupUi(
    val group: BadgeGroup,
    val subtitle: CollectionSubtitle,
) {
    val key: String get() = group.key
}

/** Cleanup dialog content (spec 03 §2.1.4). */
@Immutable
internal data class CleanupDialogState(
    val duplicates: List<MeetupBadge>,
    val summary: List<Pair<String, Int>>,
)

/** State of the badge wallet (spec 03 §3.4 plus the sheet/dialog flags of §2.1.2–§2.1.4). */
@Immutable
internal data class WalletUiState(
    val loaded: Boolean = false,
    val unreadable: Boolean = false,
    val badges: List<MeetupBadge> = emptyList(),
    val query: String = "",
    val groupByMeetup: Boolean = true,
    val compact: Boolean = false,
    val collapsed: Set<String> = emptySet(),
    val groups: List<GroupUi> = emptyList(),
    val duplicates: List<MeetupBadge> = emptyList(),
    val shareSheetOpen: Boolean = false,
    val loadingFormer: Boolean = false,
    val formerCollections: List<FormerCollection>? = null,
    val mergeCandidate: FormerCollection? = null,
    val cleanup: CleanupDialogState? = null,
    val working: Boolean = false,
) {
    val count: Int get() = badges.size
    val isEmptyWallet: Boolean get() = loaded && badges.isEmpty()
    val showMap: Boolean get() = count >= 1
    val showShare: Boolean get() = count >= 1
    val showCompactToggle: Boolean get() = WalletLibrary.showCompactToggle(count)
    val showSearch: Boolean get() = WalletLibrary.showSearch(count)
    val showCleanup: Boolean get() = duplicates.isNotEmpty()
}

/** User intents of the wallet screen. */
internal sealed interface WalletAction {
    data class QueryChanged(val query: String) : WalletAction
    data object ClearQuery : WalletAction
    data class SetGroupByMeetup(val byMeetup: Boolean) : WalletAction
    data object ToggleCompact : WalletAction
    data class ToggleCollapsed(val key: String) : WalletAction

    data object OpenMap : WalletAction
    data class OpenCollection(val groupKey: String) : WalletAction
    data class OpenDetails(val badge: MeetupBadge) : WalletAction
    data object FindMeetup : WalletAction

    data object OpenShareSheet : WalletAction
    data object DismissShareSheet : WalletAction
    data object ShareText : WalletAction
    data object ShowQr : WalletAction
    data object ExportJson : WalletAction

    data object OpenFormer : WalletAction
    data object DismissFormer : WalletAction
    data class RequestMerge(val entry: FormerCollection) : WalletAction
    data object ConfirmMerge : WalletAction
    data object DismissMerge : WalletAction

    data object OpenCleanup : WalletAction
    data object ConfirmCleanup : WalletAction
    data object DismissCleanup : WalletAction
}

/** A snackbar request: string resource with arguments and a tone. */
@Immutable
internal data class WalletMessage(
    @param:StringRes val text: Int,
    val args: List<Any> = emptyList(),
    val tone: Tone = Tone.Neutral,
)

/** One-shot effects the route executes (they need a Context). */
internal sealed interface WalletEvent {
    data class Snackbar(val message: WalletMessage) : WalletEvent

    /**
     * Share [text] as `text/plain`; on failure copy it to the clipboard and show [fallback]
     * (orange snackbar, spec 03 §2.1.2).
     */
    data class ShareText(val text: String, val subject: String, val fallback: WalletMessage) : WalletEvent
}

/** Badge wallet (Dart `BadgeWalletScreen`, spec 03 §2.1). */
@HiltViewModel
internal class WalletViewModel @Inject constructor(
    private val repository: BadgeRepository,
    private val publisher: ReputationPublisher,
    private val profiles: UserProfileRepository,
    private val timeSource: TimeSource,
) : ViewModel() {

    private val ui = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = ui.asStateFlow()

    private val eventChannel = Channel<WalletEvent>(Channel.BUFFERED)
    val events: Flow<WalletEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(repository.badges, repository.loaded, repository.badgesUnreadable) { b, l, u -> Triple(b, l, u) }
                .collect { (badges, loaded, unreadable) ->
                    ui.update { recompute(it.copy(badges = badges, loaded = loaded, unreadable = unreadable)) }
                }
        }
        repository.ensureLoaded(viewModelScope)
    }

    fun onAction(action: WalletAction) {
        when (action) {
            is WalletAction.QueryChanged -> ui.update { recompute(it.copy(query = action.query)) }
            WalletAction.ClearQuery -> ui.update { recompute(it.copy(query = "")) }
            is WalletAction.SetGroupByMeetup -> ui.update { recompute(it.copy(groupByMeetup = action.byMeetup)) }
            WalletAction.ToggleCompact -> ui.update { it.copy(compact = !it.compact) }
            is WalletAction.ToggleCollapsed -> ui.update {
                it.copy(collapsed = if (action.key in it.collapsed) it.collapsed - action.key else it.collapsed + action.key)
            }
            WalletAction.OpenShareSheet -> ui.update { it.copy(shareSheetOpen = true) }
            WalletAction.DismissShareSheet -> ui.update { it.copy(shareSheetOpen = false) }
            WalletAction.ShareText -> shareText()
            WalletAction.ExportJson -> shareJson()
            WalletAction.OpenFormer -> openFormer()
            WalletAction.DismissFormer -> ui.update { it.copy(formerCollections = null) }
            is WalletAction.RequestMerge -> ui.update { it.copy(formerCollections = null, mergeCandidate = action.entry) }
            WalletAction.DismissMerge -> ui.update { it.copy(mergeCandidate = null) }
            WalletAction.ConfirmMerge -> confirmMerge()
            WalletAction.OpenCleanup -> openCleanup()
            WalletAction.DismissCleanup -> ui.update { it.copy(cleanup = null) }
            WalletAction.ConfirmCleanup -> confirmCleanup()
            // Navigation is handled by the route.
            WalletAction.OpenMap, is WalletAction.OpenCollection, is WalletAction.OpenDetails,
            WalletAction.FindMeetup, WalletAction.ShowQr,
            -> ui.update { it.copy(shareSheetOpen = false) }
        }
    }

    private fun recompute(s: WalletUiState): WalletUiState {
        // The query is cleared when the search field disappears (spec 03 §8.1, UX fix).
        val query = if (WalletLibrary.showSearch(s.badges.size)) s.query else ""
        val now = timeSource.nowMillis()
        val groups = WalletLibrary.groups(WalletLibrary.filter(s.badges, query), s.groupByMeetup)
            .map { GroupUi(it, BadgeFormat.collectionSubtitle(it.badges, now)) }
        return s.copy(query = query, groups = groups, duplicates = WalletLibrary.duplicates(s.badges))
    }

    private suspend fun loadProfile(): UserProfile =
        runSuspendCatching { profiles.load() }
            .onFailure { AppLogger.warn(TAG, "Profil nicht lesbar, Teilen ohne Identität", it) }
            .getOrDefault(UserProfile())

    private fun shareText() {
        ui.update { it.copy(shareSheetOpen = false) }
        val all = ui.value.badges
        if (all.isEmpty()) return
        viewModelScope.launch {
            val profile = loadProfile()
            val text = ReputationShare.summaryText(all, profile.nostrNpub, DartDateTime.now(timeSource))
            eventChannel.send(
                WalletEvent.ShareText(text, ReputationShare.TEXT_SUBJECT, WalletMessage(L10nR.string.walletReputationCopied, tone = Tone.Warning)),
            )
        }
    }

    private fun shareJson() {
        ui.update { it.copy(shareSheetOpen = false) }
        val all = ui.value.badges
        if (all.isEmpty()) return
        viewModelScope.launch {
            val profile = loadProfile()
            val json = ReputationShare.exportJson(
                all = all,
                npub = profile.nostrNpub,
                nickname = profile.nickname,
                telegram = profile.telegramHandle,
                twitter = profile.twitterHandle,
                exportedAt = DartDateTime.now(timeSource),
            )
            eventChannel.send(
                WalletEvent.ShareText(json, ReputationShare.JSON_SUBJECT, WalletMessage(L10nR.string.walletJsonCopied, tone = Tone.Warning)),
            )
        }
    }

    private fun openFormer() {
        if (ui.value.loadingFormer) return
        ui.update { it.copy(loadingFormer = true) }
        viewModelScope.launch {
            val formers = runSuspendCatching { repository.formerCollections() }
                .onFailure { AppLogger.warn(TAG, "Frühere Sammlungen nicht lesbar", it) }
                .getOrDefault(emptyList())
            ui.update { it.copy(loadingFormer = false, formerCollections = formers.ifEmpty { null }) }
            if (formers.isEmpty()) snackbar(WalletMessage(L10nR.string.walletFormerNone))
        }
    }

    private fun confirmMerge() {
        val entry = ui.value.mergeCandidate ?: return
        ui.update { it.copy(mergeCandidate = null, working = true) }
        viewModelScope.launch {
            try {
                val before = ui.value.badges.map { BadgeIdentity.unionKey(it) }.toSet()
                val ok = runSuspendCatching { repository.mergeFormer(entry.index) }.getOrDefault(false)
                if (!ok) {
                    snackbar(WalletMessage(L10nR.string.walletFormerMergeFailed, tone = Tone.Error))
                    return@launch
                }
                val loaded = runSuspendCatching { repository.loadBadges() }.getOrElse { repository.badges.value }
                val added = WalletLibrary.countAdded(before, loaded)
                AppLogger.diag(TAG, "Frühere Sammlung zusammengeführt: $added neu, gesamt ${loaded.size}.")
                publisher.publishInBackground(loaded)
                snackbar(WalletMessage(L10nR.string.walletFormerMerged, listOf(added), Tone.Success))
            } finally {
                ui.update { it.copy(working = false) }
            }
        }
    }

    private fun openCleanup() {
        val dups = WalletLibrary.duplicates(ui.value.badges)
        if (dups.isEmpty()) {
            snackbar(WalletMessage(L10nR.string.walletCleanupNone))
            return
        }
        ui.update {
            it.copy(cleanup = CleanupDialogState(dups, WalletLibrary.cleanupSummary(dups).toList()))
        }
    }

    private fun confirmCleanup() {
        val dialog = ui.value.cleanup ?: return
        ui.update { it.copy(cleanup = null, working = true) }
        viewModelScope.launch {
            try {
                var removed = 0
                var kept: List<MeetupBadge> = emptyList()
                // Removes exactly the objects the dialog showed (identity), atomically (spec 03 §9.1).
                val ok = runSuspendCatching {
                    repository.update { current ->
                        val next = WalletLibrary.withoutDuplicates(current, dialog.duplicates)
                        removed = current.size - next.size
                        kept = next
                        if (removed == 0) null else next
                    }
                }.getOrDefault(false)
                if (!ok) {
                    AppLogger.warn(TAG, "Doppelte Badges nicht entfernt, Speichern fehlgeschlagen.")
                    snackbar(WalletMessage(L10nR.string.badgesUnreadableShort, tone = Tone.Error))
                    return@launch
                }
                if (removed == 0) {
                    // The shown objects are gone (e.g. replaced by a reload or sync merge).
                    snackbar(WalletMessage(L10nR.string.walletCleanupNone))
                    return@launch
                }
                AppLogger.diag(TAG, "$removed doppelte Badge(s) entfernt. Neu: ${kept.size}.")
                publisher.publishInBackground(repository.badges.value)
                snackbar(WalletMessage(L10nR.string.walletCleanupDone, listOf(removed), Tone.Success))
            } finally {
                ui.update { it.copy(working = false) }
            }
        }
    }

    private var onClearedListener: (() -> Unit)? = null

    /** Runs [listener] once when the entry is popped (the ViewModel is cleared). */
    fun setOnClearedListener(listener: () -> Unit) {
        onClearedListener = listener
    }

    override fun onCleared() {
        onClearedListener?.invoke()
        onClearedListener = null
    }

    private fun snackbar(message: WalletMessage) {
        eventChannel.trySend(WalletEvent.Snackbar(message))
    }

    private companion object {
        const val TAG = "Wallet"
    }
}
