package space.einundzwanzig.meetup.data.calendar.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.RelayParseTally
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.NostrFilter
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.RelayFanOut
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.SubscriptionIds
import space.einundzwanzig.meetup.core.nostr.relay.query
import space.einundzwanzig.meetup.core.nostr.relay.queryAll
import space.einundzwanzig.meetup.data.calendar.CalendarEventService
import space.einundzwanzig.meetup.data.calendar.CalendarRelays
import space.einundzwanzig.meetup.data.calendar.model.CalendarEventDraft
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.calendar.nostr.NostrCalendarCodec
import space.einundzwanzig.meetup.data.identity.SigningService
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CalendarEventServiceImpl @Inject constructor(
    private val relayClient: RelayClient,
    private val relayConfig: RelayConfig,
    private val signing: SigningService,
    private val time: TimeSource,
    private val log: DiagnosticLog,
) : CalendarEventService {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    override suspend fun fetchEvents(limit: Int, worldwide: Boolean): List<NostrCalendarEvent> {
        val relays = if (worldwide) relayConfig.getActiveRelays() else listOf(CalendarRelays.COMMUNITY)
        val filter = NostrFilter(
            kinds = listOf(NostrCalendarEvent.KIND_TIME, NostrCalendarEvent.KIND_DATE),
            limit = limit,
        )
        val results = coroutineScope {
            relays.map { relay ->
                async {
                    val tally = RelayParseTally(TAG, "Nostr-Kalender von $relay", log)
                    try {
                        relayClient.query(
                            relay,
                            filter,
                            RelayQueryOptions(
                                subscriptionId = SubscriptionIds.secure("cal"),
                                logTag = TAG,
                                parseTally = tally,
                            ),
                        )
                    } finally {
                        tally.report()
                    }
                }
            }.awaitAll()
        }
        // Newest created_at per address, cancelled versions included (spec 10 §14 item 6), so an
        // older active copy on another relay cannot resurrect a cancelled event.
        val byKey = LinkedHashMap<String, Pair<Long, NostrCalendarEvent>>()
        for (result in results) {
            if (result.error != null && result.events.isEmpty()) log.debug(TAG, "${result.relay} Lesefehler: ${result.error}")
            for (raw in result.events) {
                val ev = NostrCalendarCodec.parse(raw, zone) ?: continue
                if (!worldwide && !ev.fromApp) continue
                val at = EventGuard.replaceableCreatedAt(raw, time) ?: -1L
                val key = "${ev.pubkey}:${ev.dTag}:${ev.kind}"
                val known = byKey[key]
                if (known == null || at >= known.first) byKey[key] = at to ev
            }
        }
        return byKey.values.map { it.second }.filter { !it.isCancelled }.sortedBy { it.start }
    }

    override suspend fun fetchByAddress(address: String): NostrCalendarEvent? {
        val parsed = NostrCalendarCodec.parseAddress(address) ?: return null
        val filter = NostrFilter(
            kinds = listOf(parsed.kind),
            authors = listOf(parsed.author),
            tags = mapOf("d" to listOf(parsed.dTag)),
            limit = 1,
        )
        val relays = CalendarRelays.readByAddress(relayConfig.getActiveRelays())
        val results = relayClient.queryAll(
            relays,
            filter,
            RelayQueryOptions(subscriptionId = "cal1-${SubscriptionIds.randomHex(6)}", logTag = TAG),
            fanOut = RelayFanOut.PARALLEL,
        )
        var winnerAt = -1L
        var winner: NostrCalendarEvent? = null
        for (result in results) {
            if (result.error != null && result.events.isEmpty()) log.debug(TAG, "fetchByAddress ${result.relay}: ${result.error}")
            var bestAt = -1L
            var best: NostrCalendarEvent? = null
            for (raw in result.events) {
                val at = EventGuard.replaceableCreatedAt(raw, time) ?: continue
                val ev = NostrCalendarCodec.parse(raw, zone) ?: continue
                if (at < bestAt) continue
                if (ev.pubkey.dartLower() != parsed.author || ev.dTag != parsed.dTag || ev.kind != parsed.kind) continue
                bestAt = at
                best = ev
            }
            if (best == null || bestAt < winnerAt) continue
            winnerAt = bestAt
            winner = best
        }
        val found = winner?.takeUnless { it.isCancelled }
        log.debug(TAG, "fetchByAddress $address -> ${if (found == null) "nicht gefunden" else "gefunden"}")
        return found
    }

    override suspend fun publishEvent(draft: CalendarEventDraft, existingDTag: String?): Int = try {
        val dTag = existingDTag ?: NostrCalendarCodec.newDTag()
        val (kind, tags) = NostrCalendarCodec.buildTags(draft, dTag)
        val signed = signing.signEvent(kind, tags, draft.description.dartTrim())
        publish(signed)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.debug(TAG, "Event-Publish fehlgeschlagen: $e")
        0
    }

    override suspend fun updateIssuers(event: NostrCalendarEvent, newIssuersHex: List<String>): Boolean = try {
        val me = signing.pubkeyHex()
        if (me == null || me != event.pubkey) {
            log.warn(TAG, "Helfer aendern abgelehnt: nicht der Ersteller.")
            false
        } else {
            val now = time.nowSeconds()
            val creator = event.pubkey.dartLower()
            val wanted = LinkedHashSet<String>()
            for (h in newIssuersHex) {
                val k = h.dartLower()
                if (k.length == 64 && k != creator) wanted += k
            }
            val former = LinkedHashMap(event.formerIssuers)
            for (old in event.issuers) {
                val k = old.dartLower()
                if (k !in wanted) former[k] = now
            }
            for (k in wanted) former.remove(k)
            val n = publishEvent(
                CalendarEventDraft.of(event, issuers = wanted.toList(), formerIssuers = former, zone = zone),
                existingDTag = event.dTag,
            )
            log.info(
                TAG,
                "Helfer fuer \"${event.title}\" geaendert: ${wanted.size} aktiv, ${former.size} ehemalig — $n Relay(s) haben angenommen.",
            )
            n > 0
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(TAG, "Helfer aendern fehlgeschlagen", e)
        false
    }

    override suspend fun cancelEvent(event: NostrCalendarEvent): Boolean = try {
        val me = signing.pubkeyHex()
        if (me == null || me != event.pubkey) {
            log.warn(TAG, "Absage abgelehnt: nicht der Ersteller.")
            false
        } else {
            val replaced = signing.signEvent(event.kind, NostrCalendarCodec.cancelTags(event, zone), event.description)
            val n = publish(replaced)
            try {
                val deletion = signing.signEvent(
                    KIND_DELETION,
                    NostrCalendarCodec.deletionTags(event),
                    NostrCalendarEvent.DELETION_CONTENT,
                )
                publish(deletion)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug(TAG, "Loeschbitte fehlgeschlagen: $e")
            }
            log.info(TAG, "Termin \"${event.title}\" abgesagt — $n Relay(s) haben die Ersetzung angenommen.")
            n > 0
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(TAG, "Absage fehlgeschlagen", e)
        false
    }

    /**
     * Dart `_publish`: `active ∪ community`, sequential, connect 5 s → send → 2 s → close,
     * counted without waiting for `OK` (parity, spec 10 §14 item 8).
     */
    private suspend fun publish(event: NostrEvent): Int {
        val relays = CalendarRelays.publish(relayConfig.getActiveRelays())
        if (relays.isEmpty()) return 0
        val results = relayClient.publishAll(relays, event, RelayPublishOptions(awaitOk = false), RelayFanOut.SEQUENTIAL)
        results.filterNot { it.succeeded }.forEach { log.debug(TAG, "${it.relay} Publish fehlgeschlagen: ${it.status} ${it.error ?: ""}") }
        return results.count { it.succeeded }
    }

    private companion object {
        const val TAG = "Calendar"
        const val KIND_DELETION = 5
    }
}
