package space.einundzwanzig.meetup.data.reputation.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.RelayFanOut
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishOptions
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityProofRepository
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofRepository
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.proofs.zap.ZapVerificationService
import space.einundzwanzig.meetup.data.reputation.ReputationProtocol
import space.einundzwanzig.meetup.data.reputation.content.ReputationContentBuilder
import space.einundzwanzig.meetup.data.reputation.publish.PublishResult
import space.einundzwanzig.meetup.data.reputation.publish.PublishThrottleStore
import space.einundzwanzig.meetup.data.reputation.publish.ReputationPublisher
import space.einundzwanzig.meetup.data.socialgraph.social.SocialGraphService
import space.einundzwanzig.meetup.data.socialgraph.social.SocialStats
import space.einundzwanzig.meetup.data.trust.ZoneIdProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** [ReputationPublisher]: Dart `ReputationPublisher.publish` with the spec 04 FIXes (see README). */
@Singleton
internal class DefaultReputationPublisher @Inject constructor(
    private val signing: SigningService,
    private val profiles: UserProfileRepository,
    private val socialGraph: SocialGraphService,
    private val zaps: ZapVerificationService,
    private val humanity: HumanityProofRepository,
    private val platformProofs: PlatformProofRepository,
    private val relayClient: RelayClient,
    private val relayConfig: RelayConfig,
    private val throttle: PublishThrottleStore,
    private val time: TimeSource,
    private val zone: ZoneIdProvider,
    private val log: DiagnosticLog,
    @ApplicationScope private val scope: CoroutineScope,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : ReputationPublisher {

    /** One publish at a time: concurrent triggers would sign twice and race on the throttle. */
    private val mutex = Mutex()

    override suspend fun publish(
        badges: List<MeetupBadge>,
        platformProofs: Map<String, PlatformProof>?,
        force: Boolean,
    ): PublishResult = mutex.withLock {
        try {
            publishLocked(badges, platformProofs, force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Publish fehlgeschlagen: $e")
            PublishResult.Error(e)
        }
    }

    private suspend fun publishLocked(
        badges: List<MeetupBadge>,
        explicitProofs: Map<String, PlatformProof>?,
        force: Boolean,
    ): PublishResult {
        if (!signing.canSign()) return PublishResult.NoKey
        val basis = withContext(cpu) { ReputationContentBuilder.publishBasis(badges) }
        if (basis.isEmpty()) return PublishResult.NoBadges

        val hash = withContext(cpu) { ReputationContentBuilder.changeHash(basis) }
        val pubkey = signing.pubkeyHex().orEmpty()
        if (!force) {
            val state = throttle.read()
            // pubkeyHex() can be empty (stored npub with a wrong HRP) although the signer signs:
            // compare against the identity recorded last time (Dart throttle), because the save
            // below records the signed event's pubkey. Otherwise the throttle would never apply.
            val identity = pubkey.ifEmpty { state.lastPubkeyHex.orEmpty() }
            if (!state.shouldPublish(time.nowMillis(), hash, identity, ReputationProtocol.MIN_PUBLISH_INTERVAL_MS)) {
                return PublishResult.UpToDate
            }
        }

        val user = profiles.load()
        val social: SocialStats? = bestEffort("Social-Stats") {
            withTimeoutOrNull(STATS_TIMEOUT) { socialGraph.getMyStats() } ?: SocialStats.empty()
        }
        val zapStats: ZapStats? = bestEffort("Zap-Stats") {
            withTimeoutOrNull(STATS_TIMEOUT) { zaps.getMyStats() } ?: ZapStats.EMPTY
        }
        val humanityProof: JsonObject? = bestEffort("Humanity-Proof") { humanity.getProofForPublishing() }
        // Spec 06 §14 R4 / spec 04 R-5: never publish without the saved platform proofs. Not
        // best-effort: kind 30078 is replaceable, so publishing without them would delete them
        // from the relays. A load failure ends in PublishResult.Error and nothing is sent.
        val proofs = explicitProofs ?: this.platformProofs.getProofsForPublishing()

        val content = withContext(cpu) {
            ReputationContentBuilder.encode(
                ReputationContentBuilder.build(
                    badges = basis,
                    nickname = user.nickname,
                    social = social,
                    zaps = zapStats,
                    humanity = humanityProof,
                    platformProofs = proofs,
                    time = time,
                    zone = zone.zone(),
                ),
            )
        }
        val signed = signing.signEvent(ReputationProtocol.EVENT_KIND, ReputationProtocol.TAGS, content)

        val relays = relayConfig.getActiveRelays()
        val results = if (relays.isEmpty()) {
            emptyList()
        } else {
            relayClient.publishAll(
                relays,
                signed,
                RelayPublishOptions(connectTimeout = RelayConfig.PUBLISH_TIMEOUT, awaitOk = false),
                RelayFanOut.SEQUENTIAL,
            )
        }
        for (r in results) {
            if (r.succeeded) log.debug(TAG, "Event an ${r.relay} gesendet ✓") else log.warn(TAG, "${r.relay} fehlgeschlagen: ${r.error ?: r.status}")
        }
        val relayCount = results.count { it.succeeded }
        // Spec 04 R-7 FIX: a publish that reached no relay does not arm the throttle.
        if (relayCount > 0) throttle.save(time.nowMillis(), hash, pubkey.ifEmpty { signed.pubkey })
        return if (relayCount > 0) PublishResult.Sent(relayCount, signed.id) else PublishResult.NoRelay(signed.id)
    }

    override fun publishInBackground(badges: List<MeetupBadge>) {
        scope.launch {
            // Spec 04 R-19: background publishes must not block on UI. Amber (NIP-55) shares the
            // single foreground signer slot with user-started signs (badge claims, QR); a
            // background 30078 sign would fail those with `busy` or pop Amber up unasked. NIP-46
            // stays enabled: it has no foreground slot and its connect permissions cover 30078.
            if (backgroundBlockedBySigner()) {
                log.debug(TAG, "Hintergrund-Publish uebersprungen: Amber-Signer (nur explizit)")
                return@launch
            }
            when (val result = publish(badges)) {
                is PublishResult.Sent -> log.debug(TAG, "Hintergrund-Publish: ${result.germanMessage}")
                is PublishResult.Error, is PublishResult.NoRelay ->
                    log.warn(TAG, "Hintergrund-Publish fehlgeschlagen: ${result.germanMessage}")
                else -> Unit
            }
        }
    }

    private suspend fun backgroundBlockedBySigner(): Boolean = try {
        signing.getMode() == SigningMode.AMBER
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(TAG, "Signer-Modus nicht lesbar — kein Hintergrund-Publish: $e")
        true
    }

    /** Dart `try { … } catch (e) { AppLogger.warn(…) }`: `null` on failure. */
    private suspend fun <T> bestEffort(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(TAG, "$what fuer Reputation nicht ladbar — veroeffentliche ohne: $e")
        null
    }

    companion object {
        private const val TAG = "ReputationPublisher"

        /** Dart `.timeout(const Duration(seconds: 10))` of the social and zap stats. */
        val STATS_TIMEOUT: Duration = 10.seconds
    }
}
