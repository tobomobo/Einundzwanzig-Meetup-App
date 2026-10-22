package space.einundzwanzig.meetup.feature.network.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.feature.network.data.DefaultNetworkDataSource
import space.einundzwanzig.meetup.feature.network.data.NetworkDataSource

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkDataModule {
    @Binds
    abstract fun bindNetworkDataSource(impl: DefaultNetworkDataSource): NetworkDataSource
}
