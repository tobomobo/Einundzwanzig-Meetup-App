package space.einundzwanzig.meetup.data.badges.review

import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.identity.review.AppReviewDemo
import space.einundzwanzig.meetup.data.identity.review.ReviewDemoBadge

/**
 * App-review demo hooks of the badge domain (spec 02 §1, §14 item 24).
 *
 * Both scanners (badge scanner and reputation scanner) must call [isReviewDemoPayload] **before**
 * any parsing and show the English "Demo scan OK" dialog (`AppReviewDemo.SUCCESS_TITLE/BODY`)
 * instead of issuing a badge.
 */
object BadgeReviewDemo {

    /** `true` for `21review:einundzwanzig-meetup-demo` (Dart-trimmed). Such a payload never issues a badge. */
    fun isReviewDemoPayload(raw: String?): Boolean = AppReviewDemo.matches(raw)

    /** The demo badge of the review sign-in as a [MeetupBadge] (local date, no proof). */
    fun toMeetupBadge(demo: ReviewDemoBadge): MeetupBadge = MeetupBadge(
        id = demo.id,
        meetupName = demo.meetupName,
        date = DartDateTime(demo.dateMicros, isUtc = false),
        iconPath = demo.iconPath,
        delivery = demo.delivery,
        isRetroactive = demo.isRetroactive,
    )
}
