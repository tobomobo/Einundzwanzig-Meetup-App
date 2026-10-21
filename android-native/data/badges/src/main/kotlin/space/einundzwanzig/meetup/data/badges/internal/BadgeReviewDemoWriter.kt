package space.einundzwanzig.meetup.data.badges.internal

import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.badges.review.BadgeReviewDemo
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.identity.review.ReviewDemoBadge
import space.einundzwanzig.meetup.data.identity.review.ReviewDemoBadgeWriter
import javax.inject.Inject

/** Saves the review demo badge as the only badge (Dart `MeetupBadge.saveBadges([demo])`). */
internal class BadgeReviewDemoWriter @Inject constructor(
    private val repository: BadgeRepository,
) : ReviewDemoBadgeWriter {
    override suspend fun saveDemoBadge(badge: ReviewDemoBadge) {
        if (!repository.saveBadges(listOf(BadgeReviewDemo.toMeetupBadge(badge)))) {
            AppLogger.warn("Badge", "Demo-Badge nicht gespeichert")
        }
    }
}
