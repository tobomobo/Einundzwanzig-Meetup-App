package space.einundzwanzig.meetup.data.proofs.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.RelayParseTally
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.core.network.getCapped
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.SubscriptionIds
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Result
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Service
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Service.Companion.MAX_BODY_BYTES
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Service.Companion.TIMEOUT
import javax.inject.Inject

/** Port of `Nip05Service`. */
internal class DefaultNip05Service @Inject constructor(
    private val http: OkHttpClient,
    private val relayClient: RelayClient,
    private val time: TimeSource,
    private val log: DiagnosticLog,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : Nip05Service {

    override suspend fun verify(nip05: String, expectedPubkeyHex: String): Nip05Result =
        withContext(dispatcher) { verifyNip05(nip05, expectedPubkeyHex) }

    private suspend fun verifyNip05(nip05: String, expectedPubkeyHex: String): Nip05Result {
        if (nip05.isEmpty() || !nip05.contains('@')) return Nip05Result(valid = false, nip05 = nip05)
        val parts = nip05.split('@')
        if (parts.size != 2) return Nip05Result(valid = false, nip05 = nip05)
        val name = DartString.lower(parts[0])
        val domain = DartString.lower(parts[1])
        val invalid = Nip05Result(valid = false, nip05 = nip05, domain = domain)
        return try {
            val response = http.getCapped(
                "https://$domain/.well-known/nostr.json?name=$name",
                maxBytes = MAX_BODY_BYTES,
                timeout = TIMEOUT,
            ) as? HttpResult.Response ?: return invalid
            if (response.status != 200) return invalid
            val json = DartJson.decode(DartUtf8.decodeStrict(response.body)) as? JsonObject ?: return invalid
            val namesElement = json["names"]
            if (namesElement == null || namesElement is JsonNull) return invalid
            val names = namesElement as? JsonObject ?: return invalid // Dart cast threw → invalid
            val pubkeyElement = names[name]
            val pubkey = when {
                pubkeyElement == null || pubkeyElement is JsonNull -> null
                pubkeyElement is JsonPrimitive && pubkeyElement.isString -> pubkeyElement.content
                else -> return invalid // Dart `as String?` threw
            }
            Nip05Result(
                valid = pubkey != null && pubkey == expectedPubkeyHex,
                nip05 = nip05,
                domain = domain,
                domainType = Nip05Service.classify(domain),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            invalid
        }
    }

    override suspend fun fetchNip05FromProfile(pubkeyHex: String, relays: List<String>): String? =
        withContext(dispatcher) { fetchBest(pubkeyHex, relays) }

    private suspend fun fetchBest(pubkeyHex: String, relays: List<String>): String? {
        var best: Pair<Long, String?>? = null
        for (relay in relays.take(2)) {
            val hit = try {
                fetchProfileNip05(relay, pubkeyHex)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (hit != null && (best == null || hit.first > best.first)) best = hit
        }
        return best?.second?.takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchProfileNip05(relay: String, pubkeyHex: String): Pair<Long, String?>? {
        val filter = jsonObjectOf("kinds" to listOf(0), "authors" to listOf(pubkeyHex), "limit" to 1)
        val tally = RelayParseTally(TAG, "Profil-NIP-05 von $relay", log)
        var best: Pair<Long, String?>? = null
        try {
            // Dart completed with the best hit on EOSE, error, close or timeout alike.
            relayClient.query(
                relay,
                listOf(filter),
                RelayQueryOptions(
                    connectTimeout = TIMEOUT,
                    responseTimeout = TIMEOUT,
                    subscriptionId = SubscriptionIds.secure("nip05"),
                    logTag = TAG,
                    parseTally = tally,
                ),
            ) { event ->
                val at = EventGuard.replaceableCreatedAt(event, time) ?: return@query
                val current = best
                if (current != null && at < current.first) return@query
                best = at to nip05Of(event["content"].dartStringOrNull() ?: "")
            }
        } finally {
            tally.report()
        }
        return best
    }

    private companion object {
        const val TAG = "NIP05"

        /** Dart: `jsonDecode(content) as Map` → `profile['nip05'] as String?`; any failure → null. */
        fun nip05Of(content: String): String? = try {
            val profile = DartJson.decode(content) as? JsonObject
            profile?.get("nip05")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        } catch (_: Exception) {
            null
        }
    }
}
