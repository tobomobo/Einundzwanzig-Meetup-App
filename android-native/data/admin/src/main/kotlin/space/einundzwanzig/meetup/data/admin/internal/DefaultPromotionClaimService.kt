package space.einundzwanzig.meetup.data.admin.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.RelayParseTally
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrFilter
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayFanOut
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryStatus
import space.einundzwanzig.meetup.core.nostr.relay.SubscriptionIds
import space.einundzwanzig.meetup.core.nostr.relay.query
import space.einundzwanzig.meetup.data.admin.AdminConstants
import space.einundzwanzig.meetup.data.admin.claims.PromotionClaimService
import space.einundzwanzig.meetup.data.admin.claims.PromotionClaims
import space.einundzwanzig.meetup.data.admin.model.RawClaim
import space.einundzwanzig.meetup.data.admin.model.VerifiedClaim
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistry
import space.einundzwanzig.meetup.data.admin.signing.AdminSigner
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * Port of `lib/services/promotion_claim_service.dart` (spec 05 §8.5).
 *
 * @param cpuDispatcher where claim verification runs (JSON decode, BIP-340 and SHA-256 per
 *   proof); production injects `@DefaultDispatcher` so callers on Main never block.
 */
internal class DefaultPromotionClaimService(
    private val store: KeyValueStore,
    private val relayClient: RelayClient,
    private val signer: AdminSigner,
    private val registry: AdminRegistry,
    private val timeSource: TimeSource,
    private val zone: () -> ZoneId,
    private val log: DiagnosticLog,
    private val cpuDispatcher: CoroutineDispatcher,
) : PromotionClaimService {

    override suspend fun publishAdminClaim(badges: List<MeetupBadge>, meetupName: String): Boolean {
        val npub = signer.npub()
        if (npub == null || !signer.canSign()) return false
        val ownPubkey = try {
            Nip19.decodeNpub(npub)
        } catch (_: Exception) {
            return false
        }

        val verifiedBadges = PromotionClaims.claimableBadges(badges, ownPubkey)
        if (verifiedBadges.size < AdminConstants.MIN_VERIFIED_BADGES) return false

        val content = PromotionClaims.claimContent(npub, meetupName, verifiedBadges, timeSource.nowSeconds(), zone())
        val signed = signer.signEvent(AdminConstants.CLAIM_KIND, PromotionClaims.claimTags(meetupName), content)

        // Dart: connect (5 s), add, blind 2 s delay, close — no OK check (spec 05 §9 item 8).
        val results = relayClient.publishAll(
            AdminConstants.RELAYS,
            signed,
            RelayPublishOptions(connectTimeout = 5.seconds, awaitOk = false),
            RelayFanOut.SEQUENTIAL,
        )
        results.forEach { r ->
            if (r.succeeded) log.debug(TAG, "Claim an ${r.relay} gesendet ✓") else log.debug(TAG, "${r.relay} fehlgeschlagen: ${r.error ?: r.status}")
        }
        val successCount = results.count { it.succeeded }
        log.debug(TAG, "Claim publiziert an $successCount Relays")
        return successCount > 0
    }

    override suspend fun syncOrganicAdmins(): List<VerifiedClaim> {
        val verifiedClaims = mutableListOf<VerifiedClaim>()
        try {
            val claims = fetchClaimsFromRelays()
            if (claims.isNullOrEmpty()) return verifiedClaims

            // Up to 100 claims with several signature checks each: off the caller's thread.
            verifiedClaims += withContext(cpuDispatcher) { verifyAll(claims) }
            saveOrganicClaims(verifiedClaims)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "Sync fehlgeschlagen: $e")
        }
        return verifiedClaims
    }

    /** Verifies [claims] against genesis + registry; failures of single claims are logged and skipped. */
    private suspend fun verifyAll(claims: List<RawClaim>): List<VerifiedClaim> {
        val verifiedClaims = mutableListOf<VerifiedClaim>()
        // Genesis + registry. Organic claims deliberately do NOT count (K1).
        val knownPubkeys = LinkedHashSet<String>()
        runCatching { Nip19.decodeNpub(AdminConstants.SUPER_ADMIN_NPUB) }.getOrNull()?.let { knownPubkeys += it }
        for (admin in registry.getAdminList()) {
            runCatching { Nip19.decodeNpub(admin.npub) }.getOrNull()?.let { knownPubkeys += it }
        }

        for (claim in claims) {
            // CPU-bound loop (BIP-340 + SHA-256 per proof): stop when the caller is cancelled.
            currentCoroutineContext().ensureActive()
            try {
                val result = PromotionClaims.verifyClaim(claim, knownPubkeys, timeSource.nowSeconds())
                if (result != null) {
                    verifiedClaims += result
                    val short = runCatching { Nip19.shortenNpub(Nip19.encodeNpub(claim.pubkey)) }.getOrDefault(claim.pubkey)
                    log.debug(TAG, "Organischer Claim akzeptiert: $short")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug(TAG, "Claim-Verifikation fehlgeschlagen: $e")
            }
        }
        return verifiedClaims
    }

    override suspend fun getOrganicAdmins(): List<VerifiedClaim> = try {
        val raw = store.getString(AdminConstants.KEY_ORGANIC_CLAIMS)
        if (raw.isNullOrEmpty()) {
            emptyList()
        } else {
            val list = DartJson.decode(raw) as? JsonArray ?: throw IllegalArgumentException("not a list")
            list.map(VerifiedClaim::fromJson)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    override suspend fun verifyClaim(claim: RawClaim, knownAdminPubkeys: Set<String>): VerifiedClaim? =
        withContext(cpuDispatcher) { PromotionClaims.verifyClaim(claim, knownAdminPubkeys, timeSource.nowSeconds()) }

    /** Newest claim per claimer (strictly greater `claimedAt` wins), first-seen order. */
    private suspend fun saveOrganicClaims(claims: List<VerifiedClaim>) {
        val byClaimer = LinkedHashMap<String, VerifiedClaim>()
        for (c in claims) {
            val cur = byClaimer[c.claimerPubkey]
            if (cur == null || c.claimedAt > cur.claimedAt) byClaimer[c.claimerPubkey] = c
        }
        store.setString(AdminConstants.KEY_ORGANIC_CLAIMS, DartJson.encode(byClaimer.values.map { it.toJsonMap() }))
    }

    /** Dart `_fetchClaimsFromRelays`: sequential; the first relay with a non-empty list wins. */
    private suspend fun fetchClaimsFromRelays(): List<RawClaim>? {
        for (relayUrl in AdminConstants.RELAYS) {
            try {
                val result = fetchFromSingleRelay(relayUrl)
                if (result.isNotEmpty()) {
                    log.debug(TAG, "${result.size} Claims von $relayUrl geladen")
                    return result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug(TAG, "$relayUrl fehlgeschlagen: $e")
            }
        }
        return null
    }

    /**
     * Dart `_fetchFromSingleRelay`: signature check only (**no** filter match, spec 05 §9 item 6).
     * EOSE or a clean close → collected claims; transport error or timeout → `[]` (partial
     * results discarded); connect failure throws (next relay).
     */
    private suspend fun fetchFromSingleRelay(relayUrl: String): List<RawClaim> {
        val tally = RelayParseTally(TAG, "Promotion-Claims von $relayUrl", log)
        try {
            val result = relayClient.query(
                relayUrl,
                NostrFilter(
                    kinds = listOf(AdminConstants.CLAIM_KIND),
                    tags = mapOf("d" to listOf(AdminConstants.CLAIM_D_TAG)),
                    limit = CLAIM_LIMIT,
                ),
                RelayQueryOptions(
                    connectTimeout = RELAY_TIMEOUT,
                    responseTimeout = RELAY_TIMEOUT,
                    subscriptionId = SubscriptionIds.secure("organic-claims"),
                    matchFilters = false,
                    stopOnClosed = false,
                    logTag = TAG,
                    parseTally = tally,
                ),
            )
            val claims = result.events.mapNotNull { event ->
                val pubkey = (event["pubkey"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val content = (event["content"] as? JsonPrimitive)?.content ?: ""
                val createdAt = (event["created_at"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                // The guard lower-cases nothing; Dart's Event constructor lower-cased the pubkey.
                RawClaim(pubkey = pubkey.lowercase(), content = content, createdAt = createdAt)
            }
            return when (result.status) {
                RelayQueryStatus.EOSE, RelayQueryStatus.LIMIT -> claims
                RelayQueryStatus.DISCONNECTED -> if (result.error == null) claims else emptyList()
                RelayQueryStatus.CONNECT_FAILED ->
                    throw result.error as? Exception ?: IllegalStateException("Verbindung zu $relayUrl fehlgeschlagen")
                RelayQueryStatus.TIMEOUT, RelayQueryStatus.CLOSED, RelayQueryStatus.AUTH_FAILED -> emptyList()
            }
        } finally {
            tally.report()
        }
    }

    private companion object {
        const val TAG = "PromotionClaim"
        const val CLAIM_LIMIT = 100
        val RELAY_TIMEOUT = 8.seconds
    }
}
