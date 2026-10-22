package space.einundzwanzig.meetup.feature.wallet.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository

/**
 * Loads the badge store once if nothing was read in this process yet (e.g. a wallet screen
 * restored after process death). Never writes.
 */
internal fun BadgeRepository.ensureLoaded(scope: CoroutineScope) {
    if (loaded.value) return
    scope.launch {
        runSuspendCatching { loadBadges() }.onFailure { AppLogger.warn("Wallet", "Badges laden fehlgeschlagen", it) }
    }
}
