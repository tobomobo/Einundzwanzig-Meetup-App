package space.einundzwanzig.meetup.data.issuance

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.DiagnosticLogger
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.admin.model.AdminCheckResult
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaimService
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaims
import space.einundzwanzig.meetup.data.badges.claim.ClaimBatchResult
import space.einundzwanzig.meetup.data.badges.claim.ClaimResult
import space.einundzwanzig.meetup.data.badges.claim.ClaimVerifyResult
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.badges.security.BadgeSigner
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.issuance.internal.AdminPort
import space.einundzwanzig.meetup.data.issuance.internal.BlockHeightPort
import space.einundzwanzig.meetup.data.issuance.internal.CalendarPort
import space.einundzwanzig.meetup.data.issuance.internal.CoAttendancePort
import space.einundzwanzig.meetup.data.issuance.internal.IdentityPort
import space.einundzwanzig.meetup.data.issuance.internal.LocationPort
import space.einundzwanzig.meetup.data.issuance.internal.MeetupDirectoryPort
import space.einundzwanzig.meetup.data.issuance.internal.ProfileAdminPort
import space.einundzwanzig.meetup.data.issuance.internal.ZoneSource
import space.einundzwanzig.meetup.data.meetups.location.GpsStatus
import space.einundzwanzig.meetup.data.meetups.location.LocationMeetupResult
import space.einundzwanzig.meetup.data.meetups.location.LocationResult
import space.einundzwanzig.meetup.data.meetups.location.LocationStatus
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** The shared spec vectors (`spec/fixtures/02-badges-claim-vectors.json`). */
object Fixtures {
    val badges: JsonObject by lazy {
        DartJson.decode(File("../../spec/fixtures/02-badges-claim-vectors.json").readText()).jsonObject
    }

    fun obj(key: String): JsonObject = badges.getValue(key).jsonObject

    fun str(obj: JsonObject, key: String): String = obj.getValue(key).jsonPrimitive.content
}

val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/** 2025-10-09T10:13:20Z, the `c` of the fixture base payload. */
const val FIXTURE_NOW_MILLIS: Long = 1_760_000_000_000L

/** Mutable clock. */
class FakeTime(var millis: Long = FIXTURE_NOW_MILLIS) : TimeSource {
    override fun nowMillis(): Long = millis
}

/** A silent logger for the services under test. */
fun quietLog(): DiagnosticLogger = DiagnosticLogger(timeSource = FakeTime())

/** Private key 1 (pubkey `79be66…1798`, the fixture organizer). */
val ORGANIZER: KeyPair = KeyPair.fromPrivateKeyHex("0000000000000000000000000000000000000000000000000000000000000001")

/** A local-key [BadgeSigner] honouring a requested `created_at`. */
class TestSigner(
    val keyPair: KeyPair = KeyPair.generate(),
    private val time: TimeSource = FakeTime(),
    var canSign: Boolean = true,
    var failWith: Exception? = null,
) : BadgeSigner {
    var calls = 0
        private set

    override suspend fun canSign(): Boolean = canSign

    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String, createdAt: Long?): NostrEvent {
        calls++
        failWith?.let { throw it }
        return EventIds.sign(keyPair, createdAt ?: time.nowSeconds(), kind, tags, content)
    }
}

/** Signs a compact base payload as an organizer would. */
suspend fun signedPayload(
    meetupId: String,
    organizer: KeyPair = ORGANIZER,
    time: FakeTime = FakeTime(),
    blockHeight: Long = 917_000,
): JsonObject = BadgeSecurity.signCompact(TestSigner(organizer, time), meetupId, blockHeight, time.nowSeconds())

/** A claim service that really signs claims with the collector key (Dart `createClaim`). */
class FakeClaimService(
    private val collector: KeyPair = KeyPair.generate(),
    private val time: TimeSource,
    var fail: String? = null,
) : BadgeClaimService {
    var created = 0
        private set

    override suspend fun createClaim(orgSig: String, orgEventId: String, orgPubkey: String, blockHeight: Long): ClaimResult {
        fail?.let { return ClaimResult(success = false, message = it) }
        created++
        val now = time.nowSeconds()
        val event = EventIds.sign(
            collector,
            now,
            BadgeClaims.KIND_CLAIM,
            BadgeClaims.claimTags(orgPubkey, blockHeight),
            BadgeClaims.claimContent(orgSig, orgEventId, orgPubkey, blockHeight, now),
        )
        return ClaimResult(true, event.sig, event.id, event.pubkey, now, "Badge gebunden ✓")
    }

    override fun verifyClaim(
        claimSig: String,
        claimEventId: String,
        claimPubkey: String,
        claimTimestamp: Long,
        orgSig: String,
        orgEventId: String,
        orgPubkey: String,
        blockHeight: Long,
    ): ClaimVerifyResult = BadgeClaims.verifyClaim(
        claimSig, claimEventId, claimPubkey, claimTimestamp, orgSig, orgEventId, orgPubkey, blockHeight,
    )

    override suspend fun claimUnboundBadges(badges: List<MeetupBadge>): ClaimBatchResult = ClaimBatchResult(badges, 0)

    override suspend fun ensureBadgesClaimed(badges: List<MeetupBadge>): ClaimBatchResult = ClaimBatchResult(badges, 0)
}

internal class FakeIdentity(var npub: String? = null, var pubkeyHex: String? = null, var fail: Exception? = null) : IdentityPort {
    override suspend fun npub(): String? = fail?.let { throw it } ?: npub
    override suspend fun pubkeyHex(): String? = fail?.let { throw it } ?: pubkeyHex
}

internal class FakeBlockHeights(vararg values: Long) : BlockHeightPort {
    private val queue = ArrayDeque(values.toList())
    var calls = 0
        private set

    override suspend fun blockHeight(): Long {
        calls++
        return queue.removeFirstOrNull() ?: 0L
    }
}

internal class FakeAdmins(
    var byPubkey: (String) -> AdminCheckResult = { AdminCheckResult(false, source = AdminCheckResult.SOURCE_NOT_FOUND) },
    var byNpub: (String) -> AdminCheckResult = { AdminCheckResult(false, source = AdminCheckResult.SOURCE_NOT_FOUND) },
) : AdminPort {
    val pubkeyQueries = mutableListOf<String>()

    override suspend fun checkAdmin(npub: String): AdminCheckResult = byNpub(npub)

    override suspend fun checkAdminByPubkey(pubkeyHex: String): AdminCheckResult {
        pubkeyQueries += pubkeyHex
        return byPubkey(pubkeyHex)
    }
}

internal class FakeDirectory(
    var meetups: List<Meetup> = emptyList(),
    var fallback: List<Meetup> = emptyList(),
    var leaders: (Int, String) -> Boolean? = { _, _ -> false },
    var organizerFor: (String) -> Meetup? = { null },
    var myPortalMeetups: () -> Int = { 0 },
) : MeetupDirectoryPort {
    var fetchCalls = 0
        private set

    override fun bySlug(slug: String, country: String): Meetup? = meetups.firstOrNull {
        space.einundzwanzig.meetup.data.badges.security.MeetupKey.slug(it.city) == slug &&
            (country.isEmpty() || it.country.equals(country, ignoreCase = true))
    }

    override fun byName(name: String): Meetup? = meetups.firstOrNull { it.city.equals(name, ignoreCase = true) }
    override fun resolveFavorite(stored: String): Meetup? = meetups.firstOrNull { it.id == stored }
    override suspend fun organizerMeetupFor(pubkeyHexOrNpub: String): Meetup? = organizerFor(pubkeyHexOrNpub)
    override suspend fun fetchMeetups(): List<Meetup> {
        fetchCalls++
        return meetups
    }

    override val fallbackMeetups: List<Meetup> get() = fallback
    override suspend fun isLeaderOf(meetupId: Int, npub: String): Boolean? = leaders(meetupId, npub)
    override suspend fun myPortalMeetupCount(): Int = myPortalMeetups()
}

internal class FakeLocation(
    var current: LocationResult = LocationResult(LocationStatus.NO_FIX),
    var resolved: LocationMeetupResult = LocationMeetupResult(GpsStatus.ERROR),
) : LocationPort {
    override suspend fun getCurrentLocation(): LocationResult = current
    override suspend fun resolveLocation(): LocationMeetupResult = resolved
}

internal class FakeCalendar(var event: NostrCalendarEvent? = null) : CalendarPort {
    override suspend fun fetchByAddress(address: String): NostrCalendarEvent? = event?.takeIf { it.address == address }
}

internal class FakeCoAttendance(var publishResult: Int = 2) : CoAttendancePort {
    val published = mutableListOf<MeetupBadge>()
    val markers = mutableListOf<Triple<String, Long, Boolean>>()

    override suspend fun publishAttendance(badge: MeetupBadge): Int {
        published += badge
        return publishResult
    }

    override suspend fun recordOrganizerAttendance(
        meetupName: String,
        date: DartDateTime,
        blockHeight: Long,
        lat: Double,
        lng: Double,
        isEvent: Boolean,
    ): MeetupBadge? {
        markers += Triple(meetupName, blockHeight, isEvent)
        return MeetupBadge(id = "org-x", meetupName = meetupName, date = date, iconPath = "", isOrganizer = true)
    }
}

internal class FakeProfile(var admin: Boolean = false) : ProfileAdminPort {
    override suspend fun isAdmin(): Boolean = admin
}

internal val berlinZone = ZoneSource { BERLIN }

fun meetup(id: String, city: String, country: String = "DE", lat: Double = 0.0, lng: Double = 0.0, name: String = "") =
    Meetup(id = id, name = name, city = city, country = country, telegramLink = "", lat = lat, lng = lng)

/** A repository on in-memory stores. */
class StoreHarness(
    val prefs: InMemoryKeyValueStore = InMemoryKeyValueStore(),
    val secure: InMemorySecureStore = InMemorySecureStore(),
    val time: FakeTime = FakeTime(),
) {
    val repo: BadgeRepository = BadgeRepository.create(prefs, secure, time, Dispatchers.Unconfined)
}

/** A badge-enabled calendar event created by [creatorHex]. */
fun calendarEvent(
    creatorHex: String,
    title: String = "Bitcoin Zitadelle",
    start: Instant = Instant.ofEpochMilli(FIXTURE_NOW_MILLIS),
    end: Instant? = null,
    badgeEnabled: Boolean = true,
    lat: Double = 50.0,
    lng: Double = 8.0,
    issuers: List<String> = emptyList(),
    formerIssuers: Map<String, Long> = emptyMap(),
) = NostrCalendarEvent(
    id = "e".repeat(64),
    pubkey = creatorHex,
    dTag = "zitadelle-2025",
    title = title,
    description = "",
    location = "",
    start = start,
    end = end,
    allDay = false,
    kind = NostrCalendarEvent.KIND_TIME,
    badgeEnabled = badgeEnabled,
    badgeImageUrl = "https://example.org/badge.png",
    lat = lat,
    lng = lng,
    issuers = issuers,
    formerIssuers = formerIssuers,
)

fun JsonObject.with(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(LinkedHashMap(this).apply { putAll(pairs) })
