package space.einundzwanzig.meetup.data.socialgraph.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.RelayFanOut
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishStatus
import space.einundzwanzig.meetup.data.admin.signing.AdminSigner
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.MeetupKey
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.mempool.MempoolService
import space.einundzwanzig.meetup.data.socialgraph.coattendance.AttendanceKeys
import space.einundzwanzig.meetup.data.socialgraph.coattendance.CoAttNetwork
import space.einundzwanzig.meetup.data.socialgraph.coattendance.CoAttendanceRepository
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkBuildEvent
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkCalculator
import space.einundzwanzig.meetup.data.socialgraph.coattendance.PresenceCheck
import space.einundzwanzig.meetup.data.socialgraph.internal.CoAttendanceConstants.CLIENT
import space.einundzwanzig.meetup.data.socialgraph.internal.CoAttendanceConstants.FULL_SYNC_EVERY_MS
import space.einundzwanzig.meetup.data.socialgraph.internal.CoAttendanceConstants.KEY_PUBLISH_STATUS
import space.einundzwanzig.meetup.data.socialgraph.internal.CoAttendanceConstants.KIND
import space.einundzwanzig.meetup.data.socialgraph.internal.CoAttendanceConstants.PUBLISH_OK_WAIT_S
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Port of `lib/services/coattendance_service.dart` (spec 05 §8.2).
 *
 * Hilt uses the secondary `@Inject` constructor; tests use the primary one (lambdas, fake fetcher).
 *
 * @param fetchBlockHeight Dart `MempoolService.getBlockHeight()` (0 = unknown).
 * @param scope the application scope the network builds run in (they outlive their collectors).
 * @param computeDispatcher where graph parsing and BFS run when called from a caller's context
 *   (cache read, verify/analyze), so a ViewModel on Main never computes the network itself.
 */
internal class DefaultCoAttendanceRepository(
    private val store: KeyValueStore,
    private val relayClient: RelayClient,
    private val relayConfig: RelayConfig,
    private val signer: AdminSigner,
    private val badges: BadgeRepository,
    private val fetchBlockHeight: suspend () -> Int,
    private val timeSource: TimeSource,
    private val scope: CoroutineScope,
    private val log: DiagnosticLog,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    fetcher: CoAttendanceFetcher = RelayCoAttendanceFetcher(relayClient, log),
) : CoAttendanceRepository {

    @Inject
    constructor(
        store: KeyValueStore,
        relayClient: RelayClient,
        relayConfig: RelayConfig,
        signer: AdminSigner,
        badges: BadgeRepository,
        mempool: MempoolService,
        timeSource: TimeSource,
        @ApplicationScope scope: CoroutineScope,
        @DefaultDispatcher computeDispatcher: CoroutineDispatcher,
        log: DiagnosticLog,
    ) : this(
        store = store,
        relayClient = relayClient,
        relayConfig = relayConfig,
        signer = signer,
        badges = badges,
        fetchBlockHeight = mempool::getBlockHeight,
        timeSource = timeSource,
        scope = scope,
        log = log,
        computeDispatcher = computeDispatcher,
    )

    private val crawler = GraphCrawler({ relayConfig.getActiveRelays() }, fetcher, log)
    private val statusMutex = Mutex()
    private val jobLock = Any()
    private var job: NetworkJob? = null

    // ------------------------------------------------------------------ publish

    override suspend fun publishAttendance(badge: MeetupBadge): Int {
        if (!badge.isNostrSigned || AttendanceKeys.isDegenerateEventId(badge.meetupEventId)) {
            log.warn(TAG, "Badge nicht qualifiziert (nicht Nostr-signiert)")
            return 0
        }
        return try {
            val key = AttendanceKeys.attendanceKey(badge.meetupEventId, badge.signerNpub, isEvent = badge.isEvent)
            val content = DartJson.encode(
                linkedMapOf(
                    "event" to key,
                    "meetup" to badge.meetupName,
                    "t" to epochSeconds(badge.date),
                ),
            )
            val signed = signer.signEvent(
                KIND,
                listOf(
                    listOf("d", key),
                    listOf("e_ref", badge.sigId),
                    listOf("client", CLIENT),
                ),
                content,
            )
            val n = publish(signed)
            saveStatus(badge.sigId, n.toLong())
            n
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Publish-Fehler: ${describeError(e)}")
            saveStatus(badge.sigId, 0)
            0
        }
    }

    /** Dart `_publish`: all active relays in parallel, counting only `["OK", <id>, true]` within 6 s. */
    private suspend fun publish(event: NostrEvent): Int {
        val relays = relayConfig.getActiveRelays()
        if (relays.isEmpty()) return 0
        val results = relayClient.publishAll(
            relays,
            event,
            RelayPublishOptions(connectTimeout = RelayConfig.PUBLISH_TIMEOUT, ackTimeout = PUBLISH_OK_WAIT_S.seconds),
            RelayFanOut.PARALLEL,
        )
        for (r in results) {
            when (r.status) {
                RelayPublishStatus.ACCEPTED, RelayPublishStatus.SENT -> Unit
                RelayPublishStatus.REJECTED ->
                    log.warn(TAG, "${r.relay} hat abgelehnt: ${r.message.ifEmpty { "ohne Grund" }}")
                RelayPublishStatus.TIMEOUT -> log.warn(TAG, "${r.relay}: keine Bestaetigung erhalten.")
                RelayPublishStatus.DISCONNECTED, RelayPublishStatus.CONNECT_FAILED, RelayPublishStatus.AUTH_FAILED ->
                    log.warn(TAG, "${r.relay} fehlgeschlagen: ${r.error?.let(::describeError) ?: r.status.name}")
            }
        }
        val ok = results.count { it.accepted }
        log.diag(TAG, "Teilnahme ${event.id.take(8)}…: $ok von ${relays.size} Relays haben angenommen.")
        return ok
    }

    override suspend fun recordOrganizerAttendance(
        meetupName: String,
        date: DartDateTime,
        blockHeight: Long,
        lat: Double,
        lng: Double,
        isEvent: Boolean,
    ): MeetupBadge? = try {
        val meetupEventId = MeetupKey.eventId(MeetupKey.slug(meetupName), date)
        val finalBlockHeight = if (blockHeight > 0) blockHeight else fetchBlockHeight().toLong()
        val badge = MeetupBadge(
            id = "org-$meetupEventId",
            meetupName = meetupName,
            date = date,
            iconPath = "",
            blockHeight = finalBlockHeight,
            meetupEventId = meetupEventId,
            isEvent = isEvent,
            delivery = ORGANIZER_DELIVERY,
            isOrganizer = true,
            lat = lat,
            lng = lng,
        )
        val stored = badges.update { current ->
            if (current.any { it.isOrganizer && it.meetupEventId == meetupEventId }) null else current + badge
        }
        if (!stored && !badges.keepAside(badge)) {
            log.error(TAG, "Organisator-Badge weder gespeichert noch vorgemerkt — Teilnahme nicht veröffentlicht")
            null
        } else {
            publishOrganizerAttendance(meetupEventId, meetupName, date, isEvent)
            badge
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(TAG, "Organisator-Teilnahme fehlgeschlagen: ${describeError(e)}")
        null
    }

    private suspend fun publishOrganizerAttendance(
        meetupEventId: String,
        meetupName: String,
        date: DartDateTime,
        isEvent: Boolean,
    ): Int {
        // Dart reads the npub outside the try: a throwing signer fails the whole call.
        val ownNpub = signer.npub()
        val key = AttendanceKeys.attendanceKey(meetupEventId, ownNpub ?: "", isEvent = isEvent)
        return try {
            val content = DartJson.encode(
                linkedMapOf(
                    "event" to key,
                    "meetup" to meetupName,
                    "t" to epochSeconds(date),
                    "role" to "organizer",
                ),
            )
            val signed = signer.signEvent(
                KIND,
                listOf(
                    listOf("d", key),
                    listOf("role", "organizer"),
                    listOf("client", CLIENT),
                ),
                content,
            )
            publish(signed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Organisator-Publish fehlgeschlagen: ${describeError(e)}")
            0
        }
    }

    // ------------------------------------------------------------------ status

    override suspend fun publishStatus(): Map<String, Long> = try {
        val raw = store.getString(KEY_PUBLISH_STATUS)
        if (raw == null) {
            emptyMap()
        } else {
            val obj = DartJson.decode(raw) as? JsonObject
            obj?.entries?.associateTo(LinkedHashMap()) { (k, v) -> k to (v.dartIntOrNull() ?: 0L) } ?: emptyMap()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }

    private suspend fun saveStatus(sigId: String, relays: Long) {
        if (sigId.isEmpty()) return
        try {
            statusMutex.withLock {
                val current = LinkedHashMap(publishStatus())
                current[sigId] = relays
                store.setString(KEY_PUBLISH_STATUS, DartJson.encode(current))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "Veroeffentlichungsstatus nicht gespeichert: ${describeError(e)}")
        }
    }

    override suspend fun failedBadges(all: List<MeetupBadge>): List<MeetupBadge> {
        val status = publishStatus()
        return all.filter { status[it.sigId] == 0L }
    }

    override suspend fun retryFailed(all: List<MeetupBadge>): Int {
        val failed = failedBadges(all)
        var fixed = 0
        for (b in failed) if (publishAttendance(b) > 0) fixed++
        log.info(TAG, "Erneut veroeffentlicht: $fixed von ${failed.size} Teilnahmen.")
        return fixed
    }

    // ------------------------------------------------------------------ network

    override suspend fun cachedNetwork(myNpub: String, maxDepth: Int): MyNetwork? = withContext(computeDispatcher) {
        val cache = GraphCache.read(store, log) ?: return@withContext null
        if (cache.owner != myNpub) return@withContext null
        NetworkCalculator.networkFromGraph(myNpub, cache.nodes, maxDepth, updatedAt = Instant.ofEpochMilli(cache.syncAt))
    }

    override fun buildMyNetwork(myNpub: String, maxDepth: Int, forceFull: Boolean): Flow<NetworkBuildEvent> = callbackFlow {
        val listener: StageListener = { net, depth -> trySend(NetworkBuildEvent.Stage(net, depth)) }
        val running = attach(myNpub, maxDepth, forceFull, listener)
        // The await runs in a child so the listener cleanup below is registered before suspending:
        // a collector cancelled mid-build detaches immediately instead of when the build ends.
        launch {
            try {
                val net = awaitBuild(running)
                send(NetworkBuildEvent.Done(net))
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                close(e)
            }
        }
        awaitClose { running.listeners.remove(listener) }
    }

    override suspend fun awaitMyNetwork(
        myNpub: String,
        maxDepth: Int,
        forceFull: Boolean,
        onStage: ((network: MyNetwork, doneDepth: Int) -> Unit)?,
    ): MyNetwork {
        val running = attach(myNpub, maxDepth, forceFull, onStage)
        try {
            return awaitBuild(running)
        } finally {
            if (onStage != null) running.listeners.remove(onStage)
        }
    }

    /**
     * Awaits the shared build. If the build itself was cancelled (application scope torn down)
     * while the caller is still active, the caller gets an [IllegalStateException] instead of a
     * foreign [CancellationException] that would silently cancel it.
     */
    private suspend fun awaitBuild(running: NetworkJob): MyNetwork = try {
        running.deferred.await()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("Netzwerkaufbau abgebrochen", e)
    }

    /** Joins the running build of [myNpub] or starts a new one (Dart `_job`). */
    private fun attach(myNpub: String, maxDepth: Int, forceFull: Boolean, listener: StageListener?): NetworkJob =
        synchronized(jobLock) {
            val running = job
            if (running != null && running.owner == myNpub) {
                if (listener != null) running.listeners.add(listener)
                log.diag(NET_TAG, "Aufbau laeuft bereits — schliesse mich an.")
                return running
            }
            val created = NetworkJob(myNpub)
            if (listener != null) created.listeners.add(listener)
            job = created
            created.deferred = scope.async(start = CoroutineStart.LAZY) {
                try {
                    build(myNpub, maxDepth, forceFull) { net, depth ->
                        for (l in created.listeners) {
                            try {
                                l(net, depth)
                            } catch (_: Exception) {
                                // Dart swallows listener errors.
                            }
                        }
                    }
                } finally {
                    synchronized(jobLock) { if (job === created) job = null }
                }
            }
            created.deferred.start()
            created
        }

    /** Dart `_buildMyNetwork`. */
    private suspend fun build(myNpub: String, maxDepth: Int, forceFull: Boolean, emit: StageListener): MyNetwork {
        val startedAt = timeSource.nowMillis()
        val stored = GraphCache.read(store, log)
        val cache = stored?.takeIf { it.owner == myNpub }
        val previous = cache?.let {
            NetworkCalculator.networkFromGraph(myNpub, it.nodes, maxDepth, updatedAt = Instant.ofEpochMilli(it.syncAt))
        }
        val fullSync = forceFull || cache == null || startedAt - cache.fullSyncAt > FULL_SYNC_EVERY_MS
        val incremental = cache != null && !fullSync

        log.diag(
            NET_TAG,
            if (cache == null) {
                "Kein Cache — baue vollstaendig auf."
            } else {
                "${if (fullSync) "Vollstaendiger" else "Inkrementeller"} Abruf, Cache mit ${cache.nodes.size} Personen."
            },
        )

        val run = crawler.loadGraph(
            myNpub = myNpub,
            maxDepth = maxDepth,
            cache = cache,
            fullSync = fullSync,
        ) { graph, depth ->
            val fresh = NetworkCalculator.networkFromGraph(myNpub, graph, maxDepth)
            emit(if (incremental) fresh else NetworkCalculator.mergeStage(fresh, previous, depth), depth)
        }

        if (!run.answered) {
            log.warn(NET_TAG, "Keine Relay-Antwort — zeige den gespeicherten Stand.")
            return previous?.markStale() ?: MyNetwork.empty(myNpub, stale = true)
        }

        val net = NetworkCalculator.networkFromGraph(
            myNpub,
            run.graph,
            maxDepth,
            updatedAt = Instant.ofEpochMilli(startedAt),
            log = true,
            logger = log,
        )
        NetworkCalculator.logChanges(previous, net, log)

        GraphCache.write(
            store,
            GraphCache(
                owner = myNpub,
                fullSyncAt = cache?.takeIf { !fullSync }?.fullSyncAt ?: startedAt,
                syncAt = startedAt,
                knownAuthors = run.knownAuthors,
                knownKeys = run.knownKeys,
                nodes = run.graph,
            ),
            log,
        )
        return net
    }

    override suspend fun verifyPerson(myNpub: String, targetNpub: String, maxDepth: Int): PresenceCheck =
        withContext(computeDispatcher) {
            val nodes = crawler.loadGraph(myNpub = myNpub, extraNpubs = listOf(targetNpub), maxDepth = 3).graph
            NetworkCalculator.presenceCheck(nodes, myNpub, targetNpub, maxDepth)
        }

    override suspend fun analyze(myNpub: String, targetNpub: String): CoAttNetwork = withContext(computeDispatcher) {
        val nodes = crawler.loadGraph(myNpub = myNpub, extraNpubs = listOf(targetNpub)).graph
        NetworkCalculator.analyze(nodes, myNpub, targetNpub)
    }

    private class NetworkJob(val owner: String) {
        val listeners = CopyOnWriteArrayList<StageListener>()
        lateinit var deferred: Deferred<MyNetwork>
    }

    companion object {
        private const val TAG = "CoAttendance"
        private const val NET_TAG = "Netzwerk"

        /** `delivery` of the organizer marker badge. */
        const val ORGANIZER_DELIVERY = "organizer"

        /** Dart `date.millisecondsSinceEpoch ~/ 1000` (truncating). */
        fun epochSeconds(date: DartDateTime): Long = (date.microsecondsSinceEpoch / 1000) / 1000
    }
}

private typealias StageListener = (MyNetwork, Int) -> Unit
