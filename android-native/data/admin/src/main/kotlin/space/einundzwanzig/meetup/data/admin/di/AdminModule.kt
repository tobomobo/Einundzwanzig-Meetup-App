package space.einundzwanzig.meetup.data.admin.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.data.admin.claims.PromotionClaimService
import space.einundzwanzig.meetup.data.admin.internal.DefaultAdminRegistry
import space.einundzwanzig.meetup.data.admin.internal.DefaultAdminStatusVerifier
import space.einundzwanzig.meetup.data.admin.internal.DefaultPromotionClaimService
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistry
import space.einundzwanzig.meetup.data.admin.signing.AdminSigner
import space.einundzwanzig.meetup.data.admin.verify.AdminStatusVerifier
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.trust.ZoneIdProvider
import javax.inject.Inject
import javax.inject.Singleton

/** Hilt bindings of `:data:admin`. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AdminModule {

    @Binds
    abstract fun adminSigner(impl: SigningServiceAdminSigner): AdminSigner

    @Binds
    abstract fun adminStatusVerifier(impl: DefaultAdminStatusVerifier): AdminStatusVerifier

    companion object {
        @Provides
        @Singleton
        fun adminRegistry(
            store: KeyValueStore,
            relayClient: RelayClient,
            signer: AdminSigner,
            timeSource: TimeSource,
            @ApplicationScope scope: CoroutineScope,
            zone: ZoneIdProvider,
        ): AdminRegistry = DefaultAdminRegistry(
            store = store,
            relayClient = relayClient,
            signer = signer,
            timeSource = timeSource,
            backgroundScope = scope,
            relayFetchOverride = null,
            zone = zone::zone,
            log = AppLogger,
        )

        @Provides
        @Singleton
        fun promotionClaimService(
            store: KeyValueStore,
            relayClient: RelayClient,
            signer: AdminSigner,
            registry: AdminRegistry,
            timeSource: TimeSource,
            zone: ZoneIdProvider,
            @DefaultDispatcher cpuDispatcher: CoroutineDispatcher,
        ): PromotionClaimService = DefaultPromotionClaimService(
            store = store,
            relayClient = relayClient,
            signer = signer,
            registry = registry,
            timeSource = timeSource,
            zone = zone::zone,
            log = AppLogger,
            cpuDispatcher = cpuDispatcher,
        )
    }
}

/** [AdminSigner] backed by the app's [SigningService]. */
internal class SigningServiceAdminSigner @Inject constructor(
    private val signingService: SigningService,
) : AdminSigner {
    override suspend fun canSign(): Boolean = signingService.canSign()
    override suspend fun npub(): String? = signingService.npub()
    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent =
        signingService.signEvent(kind, tags, content)
}
