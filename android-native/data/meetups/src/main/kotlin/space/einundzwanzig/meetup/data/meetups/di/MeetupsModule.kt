package space.einundzwanzig.meetup.data.meetups.di

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.geocoding.GeocodingService
import space.einundzwanzig.meetup.data.meetups.internal.AndroidLocationPlatform
import space.einundzwanzig.meetup.data.meetups.internal.GeocodingServiceImpl
import space.einundzwanzig.meetup.data.meetups.internal.LocationPermissionBridgeImpl
import space.einundzwanzig.meetup.data.meetups.internal.LocationPlatform
import space.einundzwanzig.meetup.data.meetups.internal.MeetupLocationServiceImpl
import space.einundzwanzig.meetup.data.meetups.internal.MeetupServiceImpl
import space.einundzwanzig.meetup.data.meetups.internal.NearbyMeetupServiceImpl
import space.einundzwanzig.meetup.data.meetups.internal.PortalApiServiceImpl
import space.einundzwanzig.meetup.data.meetups.internal.PortalLeaderServiceImpl
import space.einundzwanzig.meetup.data.meetups.internal.PortalLogoRegistryImpl
import space.einundzwanzig.meetup.data.meetups.location.LocationPermissionBridge
import space.einundzwanzig.meetup.data.meetups.location.MeetupLocationService
import space.einundzwanzig.meetup.data.meetups.nearby.MeetupEventsSource
import space.einundzwanzig.meetup.data.meetups.nearby.NearbyMeetupService
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.PortalLeaderService
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoLoader
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoRegistry

/**
 * Binds the meetups/portal/location domain as process-wide singletons.
 *
 * [MeetupEventsSource] and [PortalLogoLoader] are optional hooks that `:data:calendar` binds
 * (it depends on this module, so the dependency cannot point the other way).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MeetupsModule {

    @Binds
    abstract fun meetupService(impl: MeetupServiceImpl): MeetupService

    @Binds
    abstract fun locationPlatform(impl: AndroidLocationPlatform): LocationPlatform

    @Binds
    abstract fun locationPermissionBridge(impl: LocationPermissionBridgeImpl): LocationPermissionBridge

    @Binds
    abstract fun meetupLocationService(impl: MeetupLocationServiceImpl): MeetupLocationService

    @Binds
    abstract fun nearbyMeetupService(impl: NearbyMeetupServiceImpl): NearbyMeetupService

    @Binds
    abstract fun geocodingService(impl: GeocodingServiceImpl): GeocodingService

    @Binds
    abstract fun portalApiService(impl: PortalApiServiceImpl): PortalApiService

    @Binds
    abstract fun portalLeaderService(impl: PortalLeaderServiceImpl): PortalLeaderService

    @Binds
    abstract fun portalLogoRegistry(impl: PortalLogoRegistryImpl): PortalLogoRegistry

    @BindsOptionalOf
    abstract fun meetupEventsSource(): MeetupEventsSource

    @BindsOptionalOf
    abstract fun portalLogoLoader(): PortalLogoLoader
}
