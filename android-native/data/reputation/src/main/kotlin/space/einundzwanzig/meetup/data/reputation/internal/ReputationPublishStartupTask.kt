package space.einundzwanzig.meetup.data.reputation.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.reputation.publish.ReputationPublisher
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Background reputation publishing (spec 04 §8.13 triggers). Flutter published from the home
 * screen after the badges loaded, after a badge scan and after wallet merge/cleanup; here one
 * task covers all of them: once the [BadgeRepository] has loaded, after every
 * [BadgeRepository.savedEvents] and after every [SigningService.signerReady] (a signer connected
 * or restored after the wallet loaded), it calls [ReputationPublisher.publishInBackground] with
 * the wallet as it is when the publish fires (non-forced, so the 5-minute/change-hash throttle
 * applies).
 *
 * Triggers are debounced by [DEBOUNCE]: a burst (claim batch, merge, restore) publishes once,
 * with the final counts, instead of publishing the first intermediate state and throttling the
 * rest. Skipped while the wallet is unreadable or empty. The Amber skip (R-19) lives in
 * [ReputationPublisher.publishInBackground]. [run] returns immediately.
 */
internal class ReputationPublishStartupTask @Inject constructor(
    private val badges: BadgeRepository,
    private val signing: SigningService,
    private val publisher: ReputationPublisher,
    @ApplicationScope private val scope: CoroutineScope,
) : AppStartupTask {

    override val name: String = "ReputationPublish"

    override suspend fun run() {
        scope.launch { watch() }
    }

    /** Publishes after the first load, then after every (debounced) save or signer change, until cancelled. */
    @OptIn(FlowPreview::class)
    internal suspend fun watch() {
        badges.loaded.first { it }
        merge(flowOf(Unit), badges.savedEvents, signing.signerReady)
            .debounce(DEBOUNCE)
            .collect { trigger() }
    }

    /** Reads the wallet now (after the debounce), not when the trigger fired. */
    private fun trigger() {
        if (badges.badgesUnreadable.value) return
        val current = badges.badges.value
        if (current.isNotEmpty()) publisher.publishInBackground(current)
    }

    companion object {
        /** Quiet period after the last trigger before publishing. */
        val DEBOUNCE: Duration = 3.seconds
    }
}
