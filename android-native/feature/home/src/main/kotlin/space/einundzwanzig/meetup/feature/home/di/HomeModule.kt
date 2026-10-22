package space.einundzwanzig.meetup.feature.home.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.feature.home.data.DefaultHomeDataSource
import space.einundzwanzig.meetup.feature.home.data.HomeDataSource
import space.einundzwanzig.meetup.feature.home.organizer.DefaultOrganizerPorts
import space.einundzwanzig.meetup.feature.home.organizer.OrganizerPorts
import space.einundzwanzig.meetup.feature.home.reset.AppResetter
import space.einundzwanzig.meetup.feature.home.reset.ResetAppUseCase

@Module
@InstallIn(SingletonComponent::class)
internal abstract class HomeModule {
    @Binds
    abstract fun dataSource(impl: DefaultHomeDataSource): HomeDataSource

    @Binds
    abstract fun organizerPorts(impl: DefaultOrganizerPorts): OrganizerPorts

    @Binds
    abstract fun resetter(impl: ResetAppUseCase): AppResetter
}
