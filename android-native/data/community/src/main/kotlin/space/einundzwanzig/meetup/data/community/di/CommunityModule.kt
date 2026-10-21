package space.einundzwanzig.meetup.data.community.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.data.community.plebrap.PlebrapPlayer
import space.einundzwanzig.meetup.data.community.plebrap.internal.DefaultPlebrapPlayer
import space.einundzwanzig.meetup.data.community.v4v.DefaultV4vService
import space.einundzwanzig.meetup.data.community.v4v.V4vService
import javax.inject.Singleton

/** Binds the public interfaces of `:data:community`. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class CommunityModule {

    @Binds
    @Singleton
    abstract fun v4vService(impl: DefaultV4vService): V4vService

    @Binds
    @Singleton
    abstract fun plebrapPlayer(impl: DefaultPlebrapPlayer): PlebrapPlayer
}
