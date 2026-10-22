package space.einundzwanzig.meetup.data.socialgraph.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistry
import space.einundzwanzig.meetup.data.admin.signing.AdminSigner
import javax.inject.Inject
import space.einundzwanzig.meetup.core.common.dart.dartRound
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.RelayParseTally
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryStatus
import space.einundzwanzig.meetup.core.nostr.relay.SubscriptionIds
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.socialgraph.social.SocialAnalysis
import space.einundzwanzig.meetup.data.socialgraph.social.SocialGraphService
import space.einundzwanzig.meetup.data.socialgraph.social.SocialGraphService.Companion.CACHE_DURATION_MS
import space.einundzwanzig.meetup.data.socialgraph.social.SocialGraphService.Companion.KEY_CACHE_TS
import space.einundzwanzig.meetup.data.socialgraph.social.SocialGraphService.Companion.KEY_MY_FOLLOWS
import space.einundzwanzig.meetup.data.socialgraph.social.SocialGraphService.Companion.MAX_ADMINS_CHECKED
import space.einundzwanzig.meetup.data.socialgraph.social.SocialGraphService.Companion.MUTUAL_SAMPLE
import space.einundzwanzig.meetup.data.socialgraph.social.SocialStats
import kotlin.coroutines.cancellation.CancellationException

/**
 * Port of `lib/services/social_graph_service.dart`.
 *
 * Hilt uses the secondary `@Inject` constructor; tests use the primary one with plain lambdas.
 *
 * @param ownNpub Dart `NostrService.getNpub()` (the signer's npub), `null` without identity.
 * @param organizers Dart `AdminRegistry.fetchFromRelays()` (fresh fetch, side effects included).
 */
internal class DefaultSocialGraphService(
    private val store: KeyValueStore,
    private val relayClient: RelayClient,
    private val relayConfig: RelayConfig,
    private val ownNpub: suspend () -> String?,
    private val organizers: suspend () -> List<AdminEntry>?,
    private val timeSource: TimeSource,
    private val log: DiagnosticLog,
) : SocialGraphService {

    @Inject
    constructor(
        store: KeyValueStore,
        relayClient: RelayClient,
        relayConfig: RelayConfig,
        signer: AdminSigner,
        adminRegistry: AdminRegistry,
        timeSource: TimeSource,
        log: DiagnosticLog,
    ) : this(
        store = store,
        relayClient = relayClient,
        relayConfig = relayConfig,
        ownNpub = signer::npub,
        organizers = adminRegistry::fetchFromRelays,
        timeSource = timeSource,
        log = log,
    )

    override suspend fun getMyFollows(forceRefresh: Boolean): Set<String> {
        if (!forceRefresh) {
            loadCachedFollows()?.let { return it }
        }
        val myHex = myPubkeyHex() ?: return emptySet()
        val follows = fetchContactList(myHex)
        cacheFollows(follows)
        return follows
    }

    override suspend fun fetchContactList(pubkeyHex: String): Set<String> {
        val relays = relayConfig.getActiveRelays()
        var best: Pair<Long, Set<String>>? = null
        for (relay in relays) {
            val hit = fetchContactListFromRelay(relay, pubkeyHex)
            if (hit != null && (best == null || hit.first > best.first)) best = hit
        }
        return best?.second ?: emptySet()
    }

    /** Newest kind 3 of [pubkeyHex] on [relay]; `null` if none came (or the relay failed). */
    private suspend fun fetchContactListFromRelay(relay: String, pubkeyHex: String): Pair<Long, Set<String>>? {
        val tally = RelayParseTally(TAG, "Kontaktliste von $relay", log)
        val filter = jsonObjectOf(
            "kinds" to listOf(3),
            "authors" to listOf(pubkeyHex),
            "limit" to 1,
        )
        var best: Pair<Long, Set<String>>? = null
        try {
            val result = relayClient.query(
                relay,
                listOf(filter),
                RelayQueryOptions(
                    subscriptionId = SubscriptionIds.secure("contacts"),
                    logTag = TAG,
                    parseTally = tally,
                ),
            ) { event ->
                val at = EventGuard.replaceableCreatedAt(event, timeSource) ?: return@query
                val current = best
                if (current != null && at < current.first) return@query
                val follows = try {
                    followsOf(event)
                } catch (e: IllegalArgumentException) {
                    tally.failed(e)
                    return@query
                }
                best = at to follows
            }
            if (result.status == RelayQueryStatus.CONNECT_FAILED) {
                // Dart: the connect timeout throws, the caller logs it on debug and moves on.
                log.debug(TAG, "Kontaktliste von $relay fehlgeschlagen: ${result.error?.let(::describeError) ?: "keine Verbindung"}")
            }
        } finally {
            tally.report()
        }
        return best
    }

    override suspend fun analyze(targetPubkeyHex: String): SocialAnalysis {
        val myHex = myPubkeyHex()
        if (myHex == null || myHex == targetPubkeyHex) return SocialAnalysis.empty()

        val myFollows = getMyFollows()
        val targetFollows = fetchContactList(targetPubkeyHex)
        val iFollow = targetPubkeyHex in myFollows
        val followsMe = myHex in targetFollows
        val isMutual = iFollow && followsMe
        val common = myFollows.count { it in targetFollows }
        val orgFollowers = countOrgFollowers(targetPubkeyHex)
        val hops = when {
            isMutual -> 1
            iFollow || followsMe -> 1
            common > 0 -> 2
            else -> -1
        }
        return SocialAnalysis(
            targetPubkey = targetPubkeyHex,
            myFollowCount = myFollows.size,
            targetFollowCount = targetFollows.size,
            iFollow = iFollow,
            followsMe = followsMe,
            isMutual = isMutual,
            commonContactCount = common,
            orgFollowerCount = orgFollowers,
            hops = hops,
        )
    }

    override suspend fun getMyStats(): SocialStats {
        val myHex = myPubkeyHex() ?: return SocialStats.empty()
        val myFollows = getMyFollows()

        var mutualCount = 0
        val sample = myFollows.take(MUTUAL_SAMPLE)
        for (follow in sample) {
            try {
                if (myHex in fetchContactList(follow)) mutualCount++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Dart: a failed sample lowers the extrapolated count silently; debug (up to 20 runs).
                log.debug(TAG, "Mutual-Stichprobe fehlgeschlagen: ${describeError(e)}")
            }
        }
        val estimated = estimateMutuals(mutualCount, sample.size, myFollows.size)
        val orgCount = countOrgFollowers(myHex)
        return SocialStats(
            followCount = myFollows.size,
            estimatedMutualCount = estimated,
            orgFollowerCount = orgCount,
        )
    }

    private suspend fun countOrgFollowers(targetPubkeyHex: String): Int {
        val admins = organizers()
        if (admins.isNullOrEmpty()) return 0
        var count = 0
        for (admin in admins.take(MAX_ADMINS_CHECKED)) {
            try {
                val adminHex = Nip19.decodeNpub(admin.npub)
                // Dart decodes a non-npub HRP to "" and queries `authors: [""]`, which finds nothing.
                if (adminHex.isEmpty()) continue
                if (targetPubkeyHex in fetchContactList(adminHex)) count++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug(TAG, "Organisator-Follow-Pruefung fehlgeschlagen: ${describeError(e)}")
            }
        }
        return count
    }

    private suspend fun myPubkeyHex(): String? {
        val npub = try {
            ownNpub()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Eigener npub nicht lesbar: ${describeError(e)}")
            return null
        }
        if (npub.isNullOrEmpty()) return null
        return try {
            Nip19.decodeNpub(npub).ifEmpty { null }
        } catch (e: Exception) {
            log.warn(TAG, "Eigener npub nicht dekodierbar: ${describeError(e)}")
            null
        }
    }

    private suspend fun loadCachedFollows(): Set<String>? {
        val ts = store.getInt(KEY_CACHE_TS) ?: 0L
        if (timeSource.nowMillis() - ts > CACHE_DURATION_MS) return null
        val json = store.getString(KEY_MY_FOLLOWS) ?: return null
        return try {
            val list = DartJson.decode(json) as? JsonArray ?: return null
            list.mapTo(LinkedHashSet()) { e ->
                (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun cacheFollows(follows: Set<String>) {
        val encoded = DartJson.encode(follows.toList())
        val now = timeSource.nowMillis()
        store.edit {
            set(KEY_MY_FOLLOWS, PrefValue.StringValue(encoded))
            set(KEY_CACHE_TS, PrefValue.IntValue(now))
        }
    }

    internal companion object {
        const val TAG = "SocialGraph"

        /**
         * Follows of a kind-3 event: `p` tags whose value has 64 characters (hex not checked, case
         * kept). Throws [IllegalArgumentException] where Dart's casts throw (non-list tag,
         * non-string value), which drops the whole event like Dart's catch.
         */
        fun followsOf(event: JsonObject): Set<String> {
            val tags = event["tags"] as? JsonArray ?: return emptySet()
            val follows = LinkedHashSet<String>()
            for (tag in tags) {
                val t = tag as? JsonArray ?: throw IllegalArgumentException("Tag ist keine Liste")
                val first = (t.firstOrNull() as? JsonPrimitive)
                if (t.isNotEmpty() && first != null && first.isString && first.content == "p" && t.size >= 2) {
                    val value = (t[1] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw IllegalArgumentException("p-Tag ohne String")
                    if (value.length == 64) follows.add(value)
                }
            }
            return follows
        }

        /** Dart `(mutual / sample * follows).round()`; `0` without follows. */
        fun estimateMutuals(mutualCount: Int, sampleSize: Int, followCount: Int): Int {
            if (followCount == 0 || sampleSize == 0) return 0
            return (mutualCount.toDouble() / sampleSize * followCount).dartRound().toInt()
        }
    }
}
