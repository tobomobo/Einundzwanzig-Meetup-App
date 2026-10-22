package space.einundzwanzig.meetup.data.identity.internal

import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.data.identity.IdentityStorageException
import space.einundzwanzig.meetup.data.identity.LocalEasyAuthException
import space.einundzwanzig.meetup.data.identity.easyauth.LocalEasyAuth
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.identity.review.AppReviewDemo
import space.einundzwanzig.meetup.data.identity.review.AppReviewSignIn
import space.einundzwanzig.meetup.data.identity.review.ReviewDemoBadge
import space.einundzwanzig.meetup.data.identity.review.ReviewDemoBadgeWriter
import java.util.Optional
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** `BuildConfig.DEBUG || BuildConfig.APP_REVIEW_SIGNIN` of this module. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class ReviewSignInAllowed

@Singleton
internal class DefaultAppReviewSignIn @Inject constructor(
    @param:ReviewSignInAllowed override val isOffered: Boolean,
    private val prefs: KeyValueStore,
    private val keyStore: SecureKeyStore,
    private val easyAuth: LocalEasyAuth,
    private val profiles: UserProfileRepository,
    private val badgeWriter: Optional<ReviewDemoBadgeWriter>,
    private val timeSource: TimeSource,
) : AppReviewSignIn {

    override suspend fun signIn(user: String, pass: String) {
        if (!isOffered) throw LocalEasyAuthException("Demo sign-in is not available in this build.")
        if (!AppReviewDemo.isDemoLogin(user, pass)) throw LocalEasyAuthException("Unknown user name or password.")

        val existing = profiles.load()
        val isDemoProfile = existing.nickname == AppReviewDemo.USERNAME && (prefs.getBool(AppReviewDemo.PREFS_FLAG) ?: false)
        if (isDemoProfile && existing.hasNostrKey) return
        // register() creates a NEW key; never over a real identity.
        if (!isDemoProfile && keyStore.hasKey()) {
            throw LocalEasyAuthException("Demo sign-in is only available on a fresh install.")
        }

        easyAuth.register(AppReviewDemo.USERNAME, AppReviewDemo.PASSWORD)
        // No admin source: the demo may see organizer UI but gets no rights.
        profiles.update {
            it.copy(isReviewDemo = true, homeMeetupId = "Berlin", favoriteMeetupIds = listOf("Berlin"))
        }
        val writer = badgeWriter.orElse(null)
        if (writer != null) {
            writer.saveDemoBadge(ReviewDemoBadge(dateMicros = timeSource.nowMicros() - SEVEN_DAYS_MICROS))
        } else {
            AppLogger.warn("AppReviewDemo", "Kein Badge-Speicher gebunden; Demo-Badge nicht angelegt.")
        }
        if (!prefs.setBool(AppReviewDemo.PREFS_FLAG, true)) throw IdentityStorageException("app_review_demo konnte nicht gespeichert werden.")
    }

    private companion object {
        const val SEVEN_DAYS_MICROS = 7L * 24 * 60 * 60 * 1_000_000
    }
}
