package space.einundzwanzig.meetup.data.profile.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.ProfileRelayQuery
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.ui.nostr.NostrProfileSource
import space.einundzwanzig.meetup.data.identity.SigningService
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
 * Process-wide singletons of the profile domain. The implementations take function seams
 * (relay list, signer, active npub) for the JVM tests, so they are provided instead of bound.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ProfileModule {

    @Provides
    @Singleton
    fun nostrProfileLookup(
        relayConfig: RelayConfig,
        query: ProfileRelayQuery,
        time: TimeSource,
        store: KeyValueStore,
        @ApplicationScope scope: CoroutineScope,
    ): NostrProfileLookup = DefaultNostrProfileLookup(relayConfig::getActiveRelays, query, time, store, scope)

    @Provides
    @Singleton
    fun nostrProfileService(
        lookup: NostrProfileLookup,
        relayConfig: RelayConfig,
        relayClient: RelayClient,
        store: KeyValueStore,
        time: TimeSource,
        @IoDispatcher io: CoroutineDispatcher,
    ): NostrProfileService = DefaultNostrProfileService(lookup, relayConfig, relayClient, store, time, io)

    @Provides
    @Singleton
    fun lightningAddressLookup(
        relayConfig: RelayConfig,
        relayClient: RelayClient,
        time: TimeSource,
    ): LightningAddressLookup = DefaultLightningAddressLookup(relayConfig, relayClient, time)

    @Provides
    @Singleton
    fun blossomUploadService(
        client: OkHttpClient,
        signing: SigningService,
        time: TimeSource,
        @IoDispatcher io: CoroutineDispatcher,
    ): BlossomUploadService = DefaultBlossomUploadService(client, { k, t, c -> signing.signEvent(k, t, c) }, time, io)

    @Provides
    @Singleton
    fun nostrProfileSource(signing: SigningService, profiles: NostrProfileService): NostrProfileSource =
        ProfileNostrProfileSource(signing::npub, profiles)
}
