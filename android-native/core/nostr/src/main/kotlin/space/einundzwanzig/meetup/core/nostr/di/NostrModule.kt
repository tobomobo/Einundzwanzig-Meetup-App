package space.einundzwanzig.meetup.core.nostr.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.core.nostr.relay.DefaultRelayClient
import space.einundzwanzig.meetup.core.nostr.relay.DefaultRelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.OkHttpRelayConnector
import space.einundzwanzig.meetup.core.nostr.relay.ProfileRelayClient
import space.einundzwanzig.meetup.core.nostr.relay.ProfileRelayQuery
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnector
import javax.inject.Singleton

/** Binds the relay stack: [RelayConnector] (OkHttp), [RelayClient], [RelayConfig], [ProfileRelayQuery]. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class NostrModule {

    @Binds
    @Singleton
    abstract fun relayConnector(impl: OkHttpRelayConnector): RelayConnector

    @Binds
    @Singleton
    abstract fun relayClient(impl: DefaultRelayClient): RelayClient

    @Binds
    @Singleton
    abstract fun relayConfig(impl: DefaultRelayConfig): RelayConfig

    @Binds
    @Singleton
    abstract fun profileRelayQuery(impl: ProfileRelayClient): ProfileRelayQuery
}
