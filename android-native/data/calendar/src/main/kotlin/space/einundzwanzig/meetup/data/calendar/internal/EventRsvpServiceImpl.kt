package space.einundzwanzig.meetup.data.calendar.internal

import kotlinx.coroutines.CancellationException
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.NostrFilter
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.RelayFanOut
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.queryAll
import space.einundzwanzig.meetup.data.calendar.CalendarRelays
import space.einundzwanzig.meetup.data.calendar.EventRsvpService
import space.einundzwanzig.meetup.data.calendar.RsvpError
import space.einundzwanzig.meetup.data.calendar.model.RsvpStatus
import space.einundzwanzig.meetup.data.calendar.nostr.RsvpCodec
import space.einundzwanzig.meetup.data.identity.SigningService
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
internal class EventRsvpServiceImpl @Inject constructor(
    private val relayClient: RelayClient,
    private val relayConfig: RelayConfig,
    private val signing: SigningService,
    private val time: TimeSource,
    private val log: DiagnosticLog,
) : EventRsvpService {

    override fun dTagFor(eventAddress: String): String = RsvpCodec.dTagFor(eventAddress)

    override suspend fun setStatus(eventAddress: String, eventAuthorPubkey: String, status: RsvpStatus): RsvpError? {
        val signed: NostrEvent = try {
            signing.signEvent(RsvpCodec.KIND_RSVP, RsvpCodec.tags(eventAddress, eventAuthorPubkey, status), "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Signieren fehlgeschlagen", e)
            return RsvpError.SigningFailed(e.toString(), e)
        }
        val relays = targets()
        val results = relayClient.publishAll(
            relays,
            signed,
            RelayPublishOptions(connectTimeout = CONNECT_TIMEOUT, ackTimeout = WAIT_TIMEOUT),
            RelayFanOut.PARALLEL,
        )
        val accepted = results.count { it.accepted }
        log.debug(TAG, "Antwort \"${status.wire}\" zu $eventAddress: $accepted von ${relays.size} Relays")
        return if (accepted == 0) RsvpError.NotAccepted else null
    }

    override suspend fun loadMine(): Map<String, RsvpStatus> {
        val me = try {
            signing.pubkeyHex()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "Kein Schluessel fuer eigene Antworten: $e")
            null
        } ?: return emptyMap()
        val results = relayClient.queryAll(
            targets(),
            NostrFilter(kinds = listOf(RsvpCodec.KIND_RSVP), authors = listOf(me), limit = LOAD_LIMIT),
            RelayQueryOptions(
                connectTimeout = CONNECT_TIMEOUT,
                responseTimeout = WAIT_TIMEOUT,
                subscriptionId = SUBSCRIPTION_ID,
                logTag = TAG,
            ),
            RelayFanOut.PARALLEL,
        )
        // Native hardening (spec 10 §14 item 10): a created_at more than 10 min in the future
        // cannot pin an answer.
        val entries = results.flatMap { it.events }
            .filter { EventGuard.replaceableCreatedAt(it, time) != null }
            .mapNotNull(RsvpCodec::parse)
        val out = RsvpCodec.newestPerAddress(entries)
        log.debug(TAG, "${out.size} eigene Antworten geladen.")
        return out
    }

    private suspend fun targets(): List<String> {
        val active = try {
            relayConfig.getActiveRelays()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return CalendarRelays.rsvp(active)
    }

    private companion object {
        const val TAG = "RSVP"
        const val SUBSCRIPTION_ID = "rsvp"
        const val LOAD_LIMIT = 300
        val CONNECT_TIMEOUT = 4.seconds
        val WAIT_TIMEOUT = 7.seconds
    }
}
