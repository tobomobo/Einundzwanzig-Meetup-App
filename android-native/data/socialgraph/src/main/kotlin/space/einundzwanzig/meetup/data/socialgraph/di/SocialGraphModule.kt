package space.einundzwanzig.meetup.data.socialgraph.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.data.socialgraph.coattendance.CoAttendanceRepository
import space.einundzwanzig.meetup.data.socialgraph.internal.DefaultCoAttendanceRepository
import space.einundzwanzig.meetup.data.socialgraph.internal.DefaultSocialGraphService
import space.einundzwanzig.meetup.data.socialgraph.social.SocialGraphService
import javax.inject.Singleton

/** Hilt bindings of `:data:socialgraph`. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SocialGraphModule {

    @Binds
    @Singleton
    abstract fun socialGraphService(impl: DefaultSocialGraphService): SocialGraphService

    @Binds
    @Singleton
    abstract fun coAttendanceRepository(impl: DefaultCoAttendanceRepository): CoAttendanceRepository
}
