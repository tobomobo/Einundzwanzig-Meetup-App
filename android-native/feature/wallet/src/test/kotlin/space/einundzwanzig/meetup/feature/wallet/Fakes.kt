package space.einundzwanzig.meetup.feature.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.data.badges.model.FormerCollection
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.reputation.publish.PublishResult
import space.einundzwanzig.meetup.data.reputation.publish.ReputationPublisher
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** 2026-10-20T12:00:00Z. */
const val NOW_MILLIS = 1_792_497_600_000L

class FakeTime(var millis: Long = NOW_MILLIS) : TimeSource {
    override fun nowMillis(): Long = millis
}

fun badge(
    id: String,
    name: String = "Aschaffenburg",
    date: String = "2026-09-12T19:00:00.000",
    block: Long = 900_000,
    meetupEventId: String = "evt-$id",
    claimSig: String = "",
    claimPubkey: String = "",
    presenceVerified: Boolean = true,
    isOrganizer: Boolean = false,
    isEvent: Boolean = false,
    lat: Double = 0.0,
    lng: Double = 0.0,
    sigVersion: Long = 0,
    sig: String = "",
    sigId: String = "",
    adminPubkey: String = "",
    signerNpub: String = "",
    delivery: String = "rolling_qr",
    claimTimestamp: Long = 0,
    isRetroactive: Boolean = false,
    coverUrl: String = "",
) = MeetupBadge(
    id = id,
    meetupName = name,
    date = DartDateTime.parse(date),
    iconPath = "",
    coverUrl = coverUrl,
    blockHeight = block,
    signerNpub = signerNpub,
    meetupEventId = meetupEventId,
    delivery = delivery,
    sig = sig,
    sigId = sigId,
    adminPubkey = adminPubkey,
    sigVersion = sigVersion,
    claimSig = claimSig,
    claimPubkey = claimPubkey,
    claimTimestamp = claimTimestamp,
    isRetroactive = isRetroactive,
    presenceVerified = presenceVerified,
    isOrganizer = isOrganizer,
    isEvent = isEvent,
    lat = lat,
    lng = lng,
)

/** In-memory [BadgeRepository] with switches for the failure paths. */
class FakeBadgeRepository(
    initial: List<MeetupBadge> = emptyList(),
    loaded: Boolean = true,
    unreadable: Boolean = false,
) : BadgeRepository {
    private val list = MutableStateFlow(initial)
    private val isLoaded = MutableStateFlow(loaded)
    private val isUnreadable = MutableStateFlow(unreadable)

    override val badges: StateFlow<List<MeetupBadge>> = list
    override val loaded: StateFlow<Boolean> = isLoaded
    override val badgesUnreadable: StateFlow<Boolean> = isUnreadable
    override val savedEvents: SharedFlow<Unit> = MutableSharedFlow()

    var formers: List<FormerCollection> = emptyList()
    var mergeResult: List<MeetupBadge>? = null
    var failWrites = false
    var loadCalls = 0
    val mergedIndices = mutableListOf<Int>()
    val saved = mutableListOf<List<MeetupBadge>>()

    fun set(badges: List<MeetupBadge>) {
        list.value = badges
    }

    override suspend fun loadBadges(): List<MeetupBadge> {
        loadCalls++
        isLoaded.value = true
        return list.value
    }

    override suspend fun saveBadges(badges: List<MeetupBadge>, replaceUnreadable: Boolean, discardLocked: Boolean): Boolean {
        if (failWrites || isUnreadable.value) return false
        saved += badges
        list.value = badges
        return true
    }

    override suspend fun update(transform: (current: List<MeetupBadge>) -> List<MeetupBadge>?): Boolean {
        if (failWrites || isUnreadable.value) return false
        val next = transform(list.value) ?: return true
        saved += next
        list.value = next
        return true
    }

    override suspend fun keepAside(badge: MeetupBadge): Boolean = false
    override suspend fun stageIncoming(badges: List<MeetupBadge>): Boolean = false
    override suspend fun promoteIncoming(formerNpub: String): Boolean = false
    override suspend fun discardIncoming() = Unit
    override suspend fun formerCollections(): List<FormerCollection> = formers

    override suspend fun mergeFormer(index: Int): Boolean {
        mergedIndices += index
        val result = mergeResult ?: return false
        list.value = result
        return true
    }

    override suspend fun resetAll() = Unit
}

class FakePublisher : ReputationPublisher {
    val background = mutableListOf<List<MeetupBadge>>()

    override suspend fun publish(badges: List<MeetupBadge>, platformProofs: Map<String, PlatformProof>?, force: Boolean): PublishResult =
        error("not used")

    override fun publishInBackground(badges: List<MeetupBadge>) {
        background += badges
    }
}

class FakeProfiles(var current: UserProfile = UserProfile(nickname = "Anon", nostrNpub = "npub1xyz")) : UserProfileRepository {
    override suspend fun load(): UserProfile = current
    override suspend fun save(profile: UserProfile) {
        current = profile
    }
    override suspend fun update(transform: (UserProfile) -> UserProfile): UserProfile = transform(current).also { current = it }
    override val profile = flowOf(current)
}
