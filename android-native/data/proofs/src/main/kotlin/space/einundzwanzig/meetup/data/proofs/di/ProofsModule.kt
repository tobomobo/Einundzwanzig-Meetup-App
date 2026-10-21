package space.einundzwanzig.meetup.data.proofs.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityProofRepository
import space.einundzwanzig.meetup.data.proofs.internal.DefaultHumanityProofRepository
import space.einundzwanzig.meetup.data.proofs.internal.DefaultNip05Service
import space.einundzwanzig.meetup.data.proofs.internal.DefaultPlatformProofRepository
import space.einundzwanzig.meetup.data.proofs.internal.DefaultSatoshiDuellClient
import space.einundzwanzig.meetup.data.proofs.internal.DefaultZapProviderResolver
import space.einundzwanzig.meetup.data.proofs.internal.DefaultZapVerificationService
import space.einundzwanzig.meetup.data.proofs.internal.HumanityReverifyStartupTask
import space.einundzwanzig.meetup.data.proofs.internal.ProofsIdentity
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Service
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofRepository
import space.einundzwanzig.meetup.data.proofs.satoshiduell.SatoshiDuellClient
import space.einundzwanzig.meetup.data.proofs.zap.ZapProviderResolver
import space.einundzwanzig.meetup.data.proofs.zap.ZapVerificationService
import javax.inject.Singleton

/** Process-wide singletons of the proofs domain and its startup task. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProofsModule {

    @Binds
    @Singleton
    abstract fun platformProofRepository(impl: DefaultPlatformProofRepository): PlatformProofRepository

    @Binds
    @Singleton
    abstract fun humanityProofRepository(impl: DefaultHumanityProofRepository): HumanityProofRepository

    @Binds
    @Singleton
    abstract fun zapProviderResolver(impl: DefaultZapProviderResolver): ZapProviderResolver

    @Binds
    @Singleton
    abstract fun zapVerificationService(impl: DefaultZapVerificationService): ZapVerificationService

    @Binds
    @Singleton
    abstract fun nip05Service(impl: DefaultNip05Service): Nip05Service

    @Binds
    @Singleton
    abstract fun satoshiDuellClient(impl: DefaultSatoshiDuellClient): SatoshiDuellClient

    @Binds
    @IntoSet
    abstract fun humanityReverify(task: HumanityReverifyStartupTask): AppStartupTask

    companion object {
        @Provides
        fun identity(signing: SigningService): ProofsIdentity = ProofsIdentity.of(signing)
    }
}
