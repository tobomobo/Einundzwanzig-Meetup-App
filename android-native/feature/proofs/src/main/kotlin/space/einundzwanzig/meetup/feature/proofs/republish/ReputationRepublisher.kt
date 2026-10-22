package space.einundzwanzig.meetup.feature.proofs.republish

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.reputation.publish.ReputationPublisher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fire-and-forget forced republish of the kind-30078 reputation event after a platform proof
 * was created or revoked or a humanity proof was found (spec 06 §2.1 / §2.2, §14 R4/R5).
 * Runs in the application scope, so leaving the screen (Flutter pops right after the
 * snackbar) never cancels it.
 */
internal interface ReputationRepublisher {
    fun republishForced()
}

@Singleton
internal class DefaultReputationRepublisher @Inject constructor(
    private val publisher: ReputationPublisher,
    private val badges: BadgeRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ReputationRepublisher {

    override fun republishForced() {
        appScope.launch {
            if (!badges.loaded.value) badges.loadBadges()
            // platformProofs = null loads getProofsForPublishing() fresh (spec 06 §14 R4).
            val result = publisher.publish(badges.badges.value, platformProofs = null, force = true)
            AppLogger.info(TAG, "Reputation nach Proof-Änderung: ${result.germanMessage}")
        }
    }

    private companion object {
        const val TAG = "Proofs"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProofsFeatureModule {
    @Binds
    abstract fun bindRepublisher(impl: DefaultReputationRepublisher): ReputationRepublisher
}
