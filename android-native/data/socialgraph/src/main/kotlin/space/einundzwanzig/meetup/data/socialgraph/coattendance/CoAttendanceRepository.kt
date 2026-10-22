package space.einundzwanzig.meetup.data.socialgraph.coattendance

import kotlinx.coroutines.flow.Flow
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge

/**
 * The opt-in co-attendance trust network (Dart `CoAttendanceService`, spec 05 §8.2).
 *
 * Participants publish a kind-30079 event per organizer-signed badge (only after explicit
 * consent); organizers publish one automatically when they start a session. The network is
 * crawled from the user's active relays in up to three stages and cached per owner
 * (`coatt_graph_cache_v1`).
 */
interface CoAttendanceRepository {

    /**
     * Publishes the attendance of [badge] (call only after the user opted in). Requires a
     * Nostr-signed badge with a non-degenerate `meetupEventId`; otherwise returns 0 **without**
     * recording a status. Records `coatt_publish_status[sigId] = accepted relays` (0 on failure,
     * also when signing throws).
     *
     * @return number of relays that answered `["OK", id, true]` within 6 s (0 = failed).
     */
    suspend fun publishAttendance(badge: MeetupBadge): Int

    /**
     * Organizer self-attendance: stores the unsigned marker badge `org-<meetupEventId>`
     * (`isOrganizer`, `delivery = "organizer"`, excluded from the trust score) unless one exists,
     * falling back to `keepAside`, then publishes the organizer attendance (no publish status).
     * `meetupEventId = MeetupKey.eventId(MeetupKey.slug(meetupName), date)` (local date of
     * [date]). A [blockHeight] ≤ 0 is fetched from mempool.
     *
     * @return the marker badge, or `null` if it could be stored nowhere or anything threw.
     */
    suspend fun recordOrganizerAttendance(
        meetupName: String,
        date: DartDateTime,
        blockHeight: Long = 0,
        lat: Double = 0.0,
        lng: Double = 0.0,
        isEvent: Boolean = false,
    ): MeetupBadge?

    /** `sigId → accepted relays` (absent = never consented, 0 = consented but failed). `{}` on errors. */
    suspend fun publishStatus(): Map<String, Long>

    /** Badges of [all] whose consented publication reached 0 relays. */
    suspend fun failedBadges(all: List<MeetupBadge>): List<MeetupBadge>

    /** Re-publishes every [failedBadges] entry; returns how many now reached ≥ 1 relay. */
    suspend fun retryFailed(all: List<MeetupBadge>): Int

    /**
     * The network from the stored graph without relay traffic; `null` if none or another owner's.
     * Parsing and BFS run on the default dispatcher, so this is safe to call from the main thread.
     */
    suspend fun cachedNetwork(myNpub: String, maxDepth: Int = NetworkCalculator.DEFAULT_MAX_DEPTH): MyNetwork?

    /**
     * Builds the user's network: emits [NetworkBuildEvent.Stage] after each finished crawl stage
     * (0 to [maxDepth] of them; the crawl stops early when nothing new is found) and always ends
     * with exactly one [NetworkBuildEvent.Done]. Incremental (`since`) from the cache, full every 24 h or when
     * [forceFull]. Without any relay answer the result is the cached network marked
     * [MyNetwork.stale] (or an empty stale one) and the cache is not rewritten.
     *
     * **Job coalescing:** while a build for the same owner is running, collecting attaches to it
     * (its [forceFull] is then ignored, as in Flutter). The build runs in the application scope:
     * cancelling the collector does not cancel it. Collecting a second time starts or joins a build.
     * If the build itself is cancelled (application scope torn down) the flow fails with
     * [IllegalStateException].
     */
    fun buildMyNetwork(
        myNpub: String,
        maxDepth: Int = NetworkCalculator.DEFAULT_MAX_DEPTH,
        forceFull: Boolean = false,
    ): Flow<NetworkBuildEvent>

    /**
     * [buildMyNetwork] as a suspend call (Dart `buildMyNetwork(onStage:)`): [onStage] is called
     * after every stage (exceptions swallowed) and the final network is returned.
     *
     * [onStage] is invoked on a background thread of the application scope, not the caller's
     * context: it must be thread-safe (e.g. update a `StateFlow`).
     */
    suspend fun awaitMyNetwork(
        myNpub: String,
        maxDepth: Int = NetworkCalculator.DEFAULT_MAX_DEPTH,
        forceFull: Boolean = false,
        onStage: ((network: MyNetwork, doneDepth: Int) -> Unit)? = null,
    ): MyNetwork

    /**
     * Presence check (Dart `verifyPerson`): loads the graph to depth 3 from the user and
     * [targetNpub] (no cache read or write) and searches the shortest meetup chain up to
     * [maxDepth] hops.
     */
    suspend fun verifyPerson(
        myNpub: String,
        targetNpub: String,
        maxDepth: Int = NetworkCalculator.VERIFY_MAX_DEPTH,
    ): PresenceCheck

    /** Dart `analyze` (no app callers in Flutter): shared meetups, mutual contacts, target reach. */
    suspend fun analyze(myNpub: String, targetNpub: String): CoAttNetwork
}
