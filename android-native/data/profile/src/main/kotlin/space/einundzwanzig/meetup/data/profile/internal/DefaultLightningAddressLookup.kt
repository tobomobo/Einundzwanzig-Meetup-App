package space.einundzwanzig.meetup.data.profile.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.RelayFanOut
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup.Companion.CONNECT_TIMEOUT
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup.Companion.LUD06_MARKER
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup.Companion.NEWS_RELAYS
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup.Companion.OVERALL_TIMEOUT
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup.Companion.READ_ONLY_RELAYS
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup.Companion.SUBSCRIPTION_ID
import javax.inject.Inject

/** Port of `NewsZapService.fetchLightningAddress` + `NewsReactionsService.readTargets`. */
internal class DefaultLightningAddressLookup @Inject constructor(
    private val relayConfig: RelayConfig,
    private val relayClient: RelayClient,
    private val time: TimeSource,
    private val log: DiagnosticLog = AppLogger,
) : LightningAddressLookup {

    override suspend fun readTargets(): List<String> {
        val all = LinkedHashSet<String>()
        all += NEWS_RELAYS
        all += READ_ONLY_RELAYS
        try {
            all += relayConfig.getActiveRelays()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The fixed relays are enough.
        }
        return all.toList()
    }

    override suspend fun fetchLightningAddress(pubkeyHex: String): String? {
        if (pubkeyHex.isEmpty()) return null
        val targets = readTargets()
        if (targets.isEmpty()) return null

        val filter = jsonObjectOf("kinds" to listOf(0), "authors" to listOf(pubkeyHex), "limit" to 1)
        var bestAt = -1L
        var found: String? = null
        // The overall budget ends the lookup with whatever was found so far (Dart Timer → finish()).
        withTimeoutOrNull(OVERALL_TIMEOUT) {
            relayClient.queryAll(
                targets,
                listOf(filter),
                RelayQueryOptions(
                    connectTimeout = CONNECT_TIMEOUT,
                    responseTimeout = OVERALL_TIMEOUT,
                    subscriptionId = SUBSCRIPTION_ID,
                    logTag = TAG,
                ),
                RelayFanOut.PARALLEL,
            ) { _, event ->
                val at = EventGuard.replaceableCreatedAt(event, time) ?: return@queryAll
                if (at < bestAt) return@queryAll
                val address = try {
                    addressOf(event)
                } catch (_: Exception) {
                    return@queryAll // parse failure: best stays unchanged
                }
                bestAt = at
                found = address
            }
        }
        log.debug(TAG, "Lightning-Adresse fuer ${pubkeyHex.take(8)}…: ${found ?: "keine"}")
        return found
    }

    companion object {
        const val TAG: String = "NewsZap"

        /**
         * `jsonDecode((event['content'] ?? '{}').toString()) as Map` → `lud16` trimmed if it
         * contains `@`, else [LUD06_MARKER] if `lud06` is non-empty, else `null` (a newer
         * profile without an address clears the older one). Throws if the content is not a
         * JSON object.
         */
        internal fun addressOf(event: JsonObject): String? {
            val content = event["content"].let { if (it == null || it is JsonNull) "{}" else dartToString(it) }
            val profile = DartJson.decode(content) as? JsonObject ?: throw IllegalArgumentException("not an object")
            val lud16 = profile["lud16"].let { if (it == null || it is JsonNull) "" else dartToString(it) }.dartTrim()
            if (lud16.contains('@')) return lud16
            val lud06 = profile["lud06"].let { if (it == null || it is JsonNull) "" else dartToString(it) }
            return if (lud06.isNotEmpty()) LUD06_MARKER else null
        }

        /** Dart `toString()` of a decoded JSON value (`{a: 1}`, `[1, 2]`, numbers verbatim). */
        private fun dartToString(e: JsonElement): String = when (e) {
            is JsonNull -> "null"
            is JsonPrimitive -> e.content
            is JsonArray -> e.joinToString(", ", "[", "]") { dartToString(it) }
            is JsonObject -> e.entries.joinToString(", ", "{", "}") { (k, v) -> "$k: ${dartToString(v)}" }
        }
    }
}
