package space.einundzwanzig.meetup.data.reputation.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.data.reputation.fetch.ReputationFetcher
import space.einundzwanzig.meetup.data.reputation.internal.DefaultPublishThrottleStore
import space.einundzwanzig.meetup.data.reputation.internal.DefaultReputationFetcher
import space.einundzwanzig.meetup.data.reputation.internal.DefaultReputationPublisher
import space.einundzwanzig.meetup.data.reputation.internal.DefaultReputationQrGenerator
import space.einundzwanzig.meetup.data.reputation.internal.ReputationPublishStartupTask
import space.einundzwanzig.meetup.data.reputation.publish.PublishThrottleStore
import space.einundzwanzig.meetup.data.reputation.publish.ReputationPublisher
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrGenerator
import javax.inject.Singleton

/** Hilt bindings of `:data:reputation`. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ReputationModule {

    @Binds
    @Singleton
    abstract fun publisher(impl: DefaultReputationPublisher): ReputationPublisher

    @Binds
    @Singleton
    abstract fun fetcher(impl: DefaultReputationFetcher): ReputationFetcher

    @Binds
    @Singleton
    abstract fun qrGenerator(impl: DefaultReputationQrGenerator): ReputationQrGenerator

    @Binds
    @Singleton
    abstract fun throttleStore(impl: DefaultPublishThrottleStore): PublishThrottleStore

    @Binds
    @IntoSet
    abstract fun publishStartup(task: ReputationPublishStartupTask): AppStartupTask
}
