package space.einundzwanzig.meetup.data.identity.review

import space.einundzwanzig.meetup.core.common.dart.dartTrim

/**
 * App-Store review demo (Dart `AppReviewDemo`, spec 01 §8.7): a static QR payload and a demo
 * login. The texts are English on purpose (reviewers).
 */
object AppReviewDemo {
    /** QR payload of the demo badge scan. */
    const val PAYLOAD = "21review:einundzwanzig-meetup-demo"

    /** Demo user name. */
    const val USERNAME = "AppReview"

    /** Demo password (ships in the binary; the sign-in itself is gated by the build flag). */
    const val PASSWORD = "ReviewDemo1"

    /** Pref (bool) set after a demo sign-in. */
    const val PREFS_FLAG = "app_review_demo"

    /** Title of the success dialog after scanning [PAYLOAD]. */
    const val SUCCESS_TITLE = "Demo scan OK"

    /** Body of the success dialog. */
    const val SUCCESS_BODY = "This is how participants collect a meetup badge: they scan the organizer QR on site. " +
        "Live codes are cryptographically signed and expire within seconds so a photo cannot be reused. " +
        "This demo code does not issue a real badge."

    /** `raw.trim() == PAYLOAD` (Dart trim). */
    fun matches(raw: String?): Boolean = raw != null && raw.dartTrim() == PAYLOAD

    /** User name trimmed, password exact. */
    fun isDemoLogin(user: String, pass: String): Boolean = user.dartTrim() == USERNAME && pass == PASSWORD
}

/**
 * The demo login (Dart `AppReviewDemo.signIn`). Offered only in debug builds or builds with
 * `-PappReviewSignin=true` ([isOffered]); the check is repeated inside [signIn].
 */
interface AppReviewSignIn {
    /** `BuildConfig.DEBUG || BuildConfig.APP_REVIEW_SIGNIN` (always `false` in a normal release). */
    val isOffered: Boolean

    /**
     * Creates the local demo identity (or re-enters it).
     *
     * @throws space.einundzwanzig.meetup.data.identity.LocalEasyAuthException `Demo sign-in is not
     *   available in this build.` / `Unknown user name or password.` / `Demo sign-in is only
     *   available on a fresh install.`
     */
    suspend fun signIn(user: String, pass: String)
}

/**
 * Writes the demo badge. Implemented by `:data:badges` (which depends on this module) and bound
 * with `@Binds`; without a binding the demo login works without the badge (logged).
 */
fun interface ReviewDemoBadgeWriter {
    /** Saves [badge] as the only badge (Dart `MeetupBadge.saveBadges([…])`). */
    suspend fun saveDemoBadge(badge: ReviewDemoBadge)
}

/**
 * The demo badge (Dart: `MeetupBadge(id: 'review-demo-badge-1', meetupName: 'App Review Demo
 * Meetup', date: now − 7 days, iconPath: '', delivery: 'rolling_qr', isRetroactive: true)`).
 *
 * @property dateMicros local `DateTime` in microseconds since epoch (`DartDateTime(dateMicros, isUtc = false)`).
 */
data class ReviewDemoBadge(
    val id: String = "review-demo-badge-1",
    val meetupName: String = "App Review Demo Meetup",
    val dateMicros: Long,
    val iconPath: String = "",
    val delivery: String = "rolling_qr",
    val isRetroactive: Boolean = true,
)
