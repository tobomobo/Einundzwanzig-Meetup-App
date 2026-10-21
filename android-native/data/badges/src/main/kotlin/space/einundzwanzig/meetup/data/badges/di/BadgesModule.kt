package space.einundzwanzig.meetup.data.badges.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaimService
import space.einundzwanzig.meetup.data.badges.internal.BadgeReviewDemoWriter
import space.einundzwanzig.meetup.data.badges.internal.DefaultBadgeClaimService
import space.einundzwanzig.meetup.data.badges.internal.EncryptedBadgeRepository
import space.einundzwanzig.meetup.data.badges.internal.SigningServiceBadgeSigner
import space.einundzwanzig.meetup.data.badges.security.BadgeSigner
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.identity.review.ReviewDemoBadgeWriter

/** Hilt bindings of `:data:badges`. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BadgesModule {
    @Binds
    abstract fun badgeRepository(impl: EncryptedBadgeRepository): BadgeRepository

    @Binds
    abstract fun badgeClaimService(impl: DefaultBadgeClaimService): BadgeClaimService

    @Binds
    abstract fun badgeSigner(impl: SigningServiceBadgeSigner): BadgeSigner

    /** Fills identity's `@BindsOptionalOf ReviewDemoBadgeWriter`. */
    @Binds
    abstract fun reviewDemoBadgeWriter(impl: BadgeReviewDemoWriter): ReviewDemoBadgeWriter
}
