package space.einundzwanzig.meetup.feature.onboarding.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.feature.onboarding.setup.AndroidKeyFileWriter
import space.einundzwanzig.meetup.feature.onboarding.setup.KeyFileWriter

@Module
@InstallIn(SingletonComponent::class)
internal abstract class OnboardingFeatureModule {
    @Binds
    abstract fun keyFileWriter(impl: AndroidKeyFileWriter): KeyFileWriter
}
