package space.einundzwanzig.meetup.data.trust.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.data.trust.ZoneIdProvider
import space.einundzwanzig.meetup.data.trust.internal.DefaultTrustScoreCalculator
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TrustModule {

    @Binds
    @Singleton
    abstract fun trustScoreCalculator(impl: DefaultTrustScoreCalculator): TrustScoreCalculator

    companion object {
        /** The device zone, read on every use (Dart reads the local zone per conversion). */
        @Provides
        fun zoneIdProvider(): ZoneIdProvider = ZoneIdProvider.SYSTEM
    }
}
