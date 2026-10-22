package space.einundzwanzig.meetup.data.socialgraph.internal

import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.json.toJsonElement
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.RelayParseTally
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryStatus
import space.einundzwanzig.meetup.core.nostr.relay.SubscriptionIds
import space.einundzwanzig.meetup.data.socialgraph.coattendance.AttendanceKeys
import space.einundzwanzig.meetup.data.socialgraph.coattendance.CoAttendanceRecord
import kotlinx.serialization.json.JsonNull
import kotlin.coroutines.cancellation.CancellationException

/** Answer of ONE relay (Dart `_RelayAnswer`); [complete] = EOSE received and limit not reached. */
internal data class RelayAnswer(val records: List<CoAttendanceRecord>, val complete: Boolean)

/**
 * Per-relay co-attendance query (Dart `_fetchFromRelay`). Returns `null` for "no answer"
 * (connect failure, or timeout/error without any record). Must not throw except for cancellation.
 */
internal fun interface CoAttendanceFetcher {
    suspend fun fetch(relay: String, keys: Set<String>?, authorsHex: List<String>?, since: Long?): RelayAnswer?
}

/** [CoAttendanceFetcher] on [RelayClient] (8 s connect, 8 s until EOSE, M4 sub ids, tally). */
internal class RelayCoAttendanceFetcher(
    private val relayClient: RelayClient,
    private val log: DiagnosticLog,
) : CoAttendanceFetcher {

    override suspend fun fetch(relay: String, keys: Set<String>?, authorsHex: List<String>?, since: Long?): RelayAnswer? {
        val tally = RelayParseTally(TAG, "Co-Attendance von $relay", log)
        val filter = buildFilter(keys, authorsHex, since)
        val limit = filter["limit"].dartIntOrNull()
        val out = mutableListOf<CoAttendanceRecord>()
        try {
            val result = relayClient.query(
                relay,
                listOf(filter),
                RelayQueryOptions(
                    connectTimeout = RelayConfig.RELAY_TIMEOUT,
                    responseTimeout = RelayConfig.RELAY_TIMEOUT,
                    subscriptionId = SubscriptionIds.secure("coatt"),
                    logTag = TAG,
                    parseTally = tally,
                ),
            ) { event ->
                val record = try {
                    recordOf(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    tally.failed(e)
                    null
                }
                if (record != null) out.add(record)
            }
            // true = EOSE, false = connection ended first, null = error / timeout.
            val state: Boolean? = when (result.status) {
                RelayQueryStatus.EOSE, RelayQueryStatus.LIMIT -> true
                RelayQueryStatus.DISCONNECTED -> false
                RelayQueryStatus.CONNECT_FAILED, RelayQueryStatus.AUTH_FAILED -> {
                    log.warn(TAG, "Fetch-Fehler $relay: ${result.error?.let(::describeError) ?: "keine Verbindung"}")
                    return null
                }
                RelayQueryStatus.CLOSED -> {
                    log.warn(
                        TAG,
                        "$relay: Abfrage beendet (CLOSED: ${result.closedReason ?: "ohne Grund"}), " +
                            "${out.size} Eintraege erhalten — Ergebnis unvollstaendig.",
                    )
                    null
                }
                RelayQueryStatus.TIMEOUT -> {
                    if (out.isNotEmpty()) {
                        log.warn(
                            TAG,
                            "$relay: Zeit abgelaufen, ${out.size} Eintraege erhalten — Ergebnis moeglicherweise unvollstaendig.",
                        )
                    }
                    null
                }
            }
            if (state == null && out.isEmpty()) return null
            val hitLimit = limit != null && out.size >= limit
            if (hitLimit) {
                log.warn(TAG, "$relay: Limit von $limit erreicht — es gibt vermutlich weitere Eintraege.")
            }
            return RelayAnswer(out.toList(), complete = state == true && !hitLimit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Fetch-Fehler $relay: ${describeError(e)}")
            return null
        } finally {
            tally.report()
        }
    }

    companion object {
        const val TAG = "CoAttendance"

        /** Author queries carry up to 100 pubkeys, each with many attendances. */
        const val AUTHOR_LIMIT = 5000

        /** Key queries: a well-attended meetup easily has ~30 attendances per date. */
        const val KEY_LIMIT = 1000

        /**
         * The REQ filter in Dart key order: `kinds`, `authors` | `#d` (with [AttendanceKeys.wantedKeys]),
         * `limit`, `since` (only if > 0).
         */
        fun buildFilter(keys: Set<String>?, authorsHex: List<String>?, since: Long?): JsonObject {
            val filter = LinkedHashMap<String, Any?>()
            filter["kinds"] = listOf(CoAttendanceConstants.KIND)
            if (!authorsHex.isNullOrEmpty()) {
                filter["authors"] = authorsHex
                filter["limit"] = AUTHOR_LIMIT
            } else if (!keys.isNullOrEmpty()) {
                filter["#d"] = AttendanceKeys.wantedKeys(keys).toList()
                filter["limit"] = KEY_LIMIT
            }
            if (since != null && since > 0) filter["since"] = since
            return filter.toJsonElement() as JsonObject
        }

        /**
         * A verified, filter-matched event → record: author npub, `content.event` (Dart
         * `toString()`, `''` if missing → `null`), `content.t` if int else 0. Throws for
         * non-object content (counted as a parse failure, like Dart).
         */
        fun recordOf(event: JsonObject): CoAttendanceRecord? {
            val authorHex = event["pubkey"].dartStringOrNull() ?: throw IllegalArgumentException("pubkey fehlt")
            val npub = Nip19.encodeNpub(authorHex)
            val contentText = event["content"].dartStringOrNull() ?: throw IllegalArgumentException("content fehlt")
            val content = DartJson.decode(contentText) as? JsonObject
                ?: throw IllegalArgumentException("content ist kein JSON-Objekt")
            val rawEvent = content["event"]
            val key = if (rawEvent == null || rawEvent is JsonNull) "" else dartToString(rawEvent)
            val t = content["t"].dartIntOrNull() ?: 0L
            if (key.isEmpty()) return null
            return CoAttendanceRecord(npub = npub, meetupEventId = key, attendedAt = t)
        }
    }
}
