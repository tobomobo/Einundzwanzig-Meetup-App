package space.einundzwanzig.meetup.data.profile.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.core.ui.nostr.NostrProfileSource
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup
import space.einundzwanzig.meetup.data.profile.NostrProfileLookup
import space.einundzwanzig.meetup.data.profile.NostrProfileService
import space.einundzwanzig.meetup.data.profile.blossom.BlossomUploadService
import space.einundzwanzig.meetup.data.profile.internal.DefaultBlossomUploadService
import space.einundzwanzig.meetup.data.profile.internal.DefaultLightningAddressLookup
import space.einundzwanzig.meetup.data.profile.internal.DefaultNostrProfileLookup
import space.einundzwanzig.meetup.data.profile.internal.DefaultNostrProfileService
import space.einundzwanzig.meetup.data.profile.internal.ProfileNostrProfileSource
import javax.inject.Singleton

/**
 * Process-wide singletons of the profile domain. The implementations' `@Inject` constructors
 * take the real interfaces (RelayConfig, SigningService, ...); their function-seam constructors
 * exist for the JVM tests only.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProfileModule {

    @Binds
    @Singleton
    abstract fun nostrProfileLookup(impl: DefaultNostrProfileLookup): NostrProfileLookup

    @Binds
    @Singleton
    abstract fun nostrProfileService(impl: DefaultNostrProfileService): NostrProfileService

    @Binds
    @Singleton
    abstract fun lightningAddressLookup(impl: DefaultLightningAddressLookup): LightningAddressLookup

    @Binds
    @Singleton
    abstract fun blossomUploadService(impl: DefaultBlossomUploadService): BlossomUploadService

    @Binds
    @Singleton
    abstract fun nostrProfileSource(impl: ProfileNostrProfileSource): NostrProfileSource
}
