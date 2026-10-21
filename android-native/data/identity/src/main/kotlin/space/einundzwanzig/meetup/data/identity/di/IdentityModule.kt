package space.einundzwanzig.meetup.data.identity.di

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.data.identity.BuildConfig
import space.einundzwanzig.meetup.data.identity.IdentityRepository
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.auth.DeviceAuthenticator
import space.einundzwanzig.meetup.data.identity.easyauth.LocalEasyAuth
import space.einundzwanzig.meetup.data.identity.integrity.DeviceIntegrityService
import space.einundzwanzig.meetup.data.identity.internal.BiometricDeviceAuthenticator
import space.einundzwanzig.meetup.data.identity.internal.DefaultAppReviewSignIn
import space.einundzwanzig.meetup.data.identity.internal.DefaultDeviceIntegrityService
import space.einundzwanzig.meetup.data.identity.internal.DefaultIdentityRecovery
import space.einundzwanzig.meetup.data.identity.internal.DefaultIdentityRepository
import space.einundzwanzig.meetup.data.identity.internal.DefaultLocalEasyAuth
import space.einundzwanzig.meetup.data.identity.internal.DefaultLocalKeyVault
import space.einundzwanzig.meetup.data.identity.internal.DefaultNip46ClientFactory
import space.einundzwanzig.meetup.data.identity.internal.DefaultNostrKeyService
import space.einundzwanzig.meetup.data.identity.internal.DefaultSecureKeyStore
import space.einundzwanzig.meetup.data.identity.internal.DefaultSigningService
import space.einundzwanzig.meetup.data.identity.internal.DefaultUserProfileRepository
import space.einundzwanzig.meetup.data.identity.internal.Nip46ClientFactory
import space.einundzwanzig.meetup.data.identity.internal.ReviewSignInAllowed
import space.einundzwanzig.meetup.data.identity.keys.IdentityRecovery
import space.einundzwanzig.meetup.data.identity.keys.LocalKeyVault
import space.einundzwanzig.meetup.data.identity.keys.NostrKeyService
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import space.einundzwanzig.meetup.data.identity.nip55.AndroidNip55Transport
import space.einundzwanzig.meetup.data.identity.nip55.DefaultExternalSignerBridge
import space.einundzwanzig.meetup.data.identity.nip55.ExternalSignerBridge
import space.einundzwanzig.meetup.data.identity.nip55.Nip55Transport
import space.einundzwanzig.meetup.data.identity.passkey.AndroidPasskeyPrfService
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfService
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.identity.review.AppReviewSignIn
import space.einundzwanzig.meetup.data.identity.review.ReviewDemoBadgeWriter

/** Binds the public identity interfaces to their singletons. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class IdentityModule {

    @Binds abstract fun signingService(impl: DefaultSigningService): SigningService

    @Binds abstract fun secureKeyStore(impl: DefaultSecureKeyStore): SecureKeyStore

    @Binds abstract fun localKeyVault(impl: DefaultLocalKeyVault): LocalKeyVault

    @Binds abstract fun nostrKeyService(impl: DefaultNostrKeyService): NostrKeyService

    @Binds abstract fun userProfileRepository(impl: DefaultUserProfileRepository): UserProfileRepository

    @Binds abstract fun localEasyAuth(impl: DefaultLocalEasyAuth): LocalEasyAuth

    @Binds abstract fun passkeyPrfService(impl: AndroidPasskeyPrfService): PasskeyPrfService

    @Binds abstract fun appReviewSignIn(impl: DefaultAppReviewSignIn): AppReviewSignIn

    @Binds abstract fun deviceIntegrityService(impl: DefaultDeviceIntegrityService): DeviceIntegrityService

    @Binds abstract fun deviceAuthenticator(impl: BiometricDeviceAuthenticator): DeviceAuthenticator

    @Binds abstract fun identityRepository(impl: DefaultIdentityRepository): IdentityRepository

    @Binds abstract fun identityRecovery(impl: DefaultIdentityRecovery): IdentityRecovery

    @Binds abstract fun externalSignerBridge(impl: DefaultExternalSignerBridge): ExternalSignerBridge

    @Binds abstract fun nip55Transport(impl: AndroidNip55Transport): Nip55Transport

    @Binds abstract fun nip46ClientFactory(impl: DefaultNip46ClientFactory): Nip46ClientFactory

    /** Bound by `:data:badges`; optional so the identity module works without it. */
    @BindsOptionalOf abstract fun reviewDemoBadgeWriter(): ReviewDemoBadgeWriter

    companion object {
        @Provides
        @ReviewSignInAllowed
        fun reviewSignInAllowed(): Boolean = BuildConfig.DEBUG || BuildConfig.APP_REVIEW_SIGNIN
    }
}
