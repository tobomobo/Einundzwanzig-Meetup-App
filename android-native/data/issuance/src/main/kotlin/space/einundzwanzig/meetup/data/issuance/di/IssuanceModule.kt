package space.einundzwanzig.meetup.data.issuance.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeAuthService
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeChainService
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeSessionService
import space.einundzwanzig.meetup.data.issuance.internal.AdminPort
import space.einundzwanzig.meetup.data.issuance.internal.BlockHeightPort
import space.einundzwanzig.meetup.data.issuance.internal.CalendarPort
import space.einundzwanzig.meetup.data.issuance.internal.CoAttendancePort
import space.einundzwanzig.meetup.data.issuance.internal.CryptoSeedSource
import space.einundzwanzig.meetup.data.issuance.internal.DefaultBadgeSaveUseCase
import space.einundzwanzig.meetup.data.issuance.internal.DefaultBadgeScanProcessor
import space.einundzwanzig.meetup.data.issuance.internal.DefaultEventBadgeAuthService
import space.einundzwanzig.meetup.data.issuance.internal.DefaultEventBadgeChainService
import space.einundzwanzig.meetup.data.issuance.internal.DefaultEventBadgeSessionService
import space.einundzwanzig.meetup.data.issuance.internal.DefaultOrganizerSessionService
import space.einundzwanzig.meetup.data.issuance.internal.DefaultRollingQrService
import space.einundzwanzig.meetup.data.issuance.internal.IdentityPort
import space.einundzwanzig.meetup.data.issuance.internal.LocationPort
import space.einundzwanzig.meetup.data.issuance.internal.MeetupDirectoryPort
import space.einundzwanzig.meetup.data.issuance.internal.MempoolBlockHeightPort
import space.einundzwanzig.meetup.data.issuance.internal.ProfileAdminPort
import space.einundzwanzig.meetup.data.issuance.internal.RegistryAdminPort
import space.einundzwanzig.meetup.data.issuance.internal.RepositoryCoAttendancePort
import space.einundzwanzig.meetup.data.issuance.internal.RepositoryProfileAdminPort
import space.einundzwanzig.meetup.data.issuance.internal.SeedSource
import space.einundzwanzig.meetup.data.issuance.internal.ServiceCalendarPort
import space.einundzwanzig.meetup.data.issuance.internal.ServiceLocationPort
import space.einundzwanzig.meetup.data.issuance.internal.ServicesMeetupDirectoryPort
import space.einundzwanzig.meetup.data.issuance.internal.SigningIdentityPort
import space.einundzwanzig.meetup.data.issuance.internal.SystemZoneSource
import space.einundzwanzig.meetup.data.issuance.internal.ZoneSource
import space.einundzwanzig.meetup.data.issuance.scan.BadgeSaveUseCase
import space.einundzwanzig.meetup.data.issuance.scan.BadgeScanProcessor
import space.einundzwanzig.meetup.data.issuance.session.OrganizerSessionService
import space.einundzwanzig.meetup.data.issuance.session.RollingQrService

/** Hilt bindings of `:data:issuance` (all implementations are `@Singleton`). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class IssuanceModule {
    @Binds abstract fun rollingQrService(impl: DefaultRollingQrService): RollingQrService

    @Binds abstract fun organizerSessionService(impl: DefaultOrganizerSessionService): OrganizerSessionService

    @Binds abstract fun eventBadgeChainService(impl: DefaultEventBadgeChainService): EventBadgeChainService

    @Binds abstract fun eventBadgeAuthService(impl: DefaultEventBadgeAuthService): EventBadgeAuthService

    @Binds abstract fun eventBadgeSessionService(impl: DefaultEventBadgeSessionService): EventBadgeSessionService

    @Binds abstract fun badgeScanProcessor(impl: DefaultBadgeScanProcessor): BadgeScanProcessor

    @Binds abstract fun badgeSaveUseCase(impl: DefaultBadgeSaveUseCase): BadgeSaveUseCase

    // Ports onto other modules.
    @Binds abstract fun identityPort(impl: SigningIdentityPort): IdentityPort

    @Binds abstract fun blockHeightPort(impl: MempoolBlockHeightPort): BlockHeightPort

    @Binds abstract fun adminPort(impl: RegistryAdminPort): AdminPort

    @Binds abstract fun meetupDirectoryPort(impl: ServicesMeetupDirectoryPort): MeetupDirectoryPort

    @Binds abstract fun locationPort(impl: ServiceLocationPort): LocationPort

    @Binds abstract fun calendarPort(impl: ServiceCalendarPort): CalendarPort

    @Binds abstract fun coAttendancePort(impl: RepositoryCoAttendancePort): CoAttendancePort

    @Binds abstract fun profileAdminPort(impl: RepositoryProfileAdminPort): ProfileAdminPort

    @Binds abstract fun zoneSource(impl: SystemZoneSource): ZoneSource

    @Binds abstract fun seedSource(impl: CryptoSeedSource): SeedSource
}
