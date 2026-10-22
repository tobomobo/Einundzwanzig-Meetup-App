package space.einundzwanzig.meetup.feature.network.data

import kotlinx.coroutines.flow.Flow
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.profile.NostrProfileService
import space.einundzwanzig.meetup.data.socialgraph.coattendance.CoAttendanceRepository
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkBuildEvent
import javax.inject.Inject

/**
 * The calls "Meine Vernetzung" makes into the data layer (spec 05 §2.1), bundled behind one seam
 * so the ViewModel can be tested with a fake. Pure delegation: no logic of its own.
 */
internal interface NetworkDataSource {
    /** Profile npub (`UserProfile.nostrNpub`), as Flutter uses it; `""` without identity. */
    suspend fun myNpub(): String

    /** [CoAttendanceRepository.cachedNetwork]. */
    suspend fun cachedNetwork(myNpub: String): MyNetwork?

    /** [CoAttendanceRepository.buildMyNetwork]. */
    fun buildNetwork(myNpub: String, forceFull: Boolean): Flow<NetworkBuildEvent>

    /** `loadBadges()` → [CoAttendanceRepository.failedBadges] → count. */
    suspend fun failedCount(): Int

    /** `loadBadges()` → [CoAttendanceRepository.retryFailed]. */
    suspend fun retryFailed(): Int

    /** [NostrProfileService.cachedDisplayNames] (hex → name). */
    suspend fun cachedDisplayNames(): Map<String, String>

    /** [NostrProfileService.fetchDisplayNames] (hex → name). */
    suspend fun fetchDisplayNames(hexes: Collection<String>): Map<String, String>

    /** Dart `npubToHex`; `null` if [npub] does not decode. */
    fun npubToHex(npub: String): String?
}

internal class DefaultNetworkDataSource @Inject constructor(
    private val profiles: UserProfileRepository,
    private val coAttendance: CoAttendanceRepository,
    private val badges: BadgeRepository,
    private val names: NostrProfileService,
) : NetworkDataSource {
    override suspend fun myNpub(): String = profiles.load().nostrNpub

    override suspend fun cachedNetwork(myNpub: String): MyNetwork? = coAttendance.cachedNetwork(myNpub)

    override fun buildNetwork(myNpub: String, forceFull: Boolean): Flow<NetworkBuildEvent> =
        coAttendance.buildMyNetwork(myNpub, forceFull = forceFull)

    override suspend fun failedCount(): Int = coAttendance.failedBadges(badges.loadBadges()).size

    override suspend fun retryFailed(): Int = coAttendance.retryFailed(badges.loadBadges())

    override suspend fun cachedDisplayNames(): Map<String, String> = names.cachedDisplayNames()

    override suspend fun fetchDisplayNames(hexes: Collection<String>): Map<String, String> =
        names.fetchDisplayNames(hexes)

    override fun npubToHex(npub: String): String? =
        runCatching { Nip19.decodeNpub(npub) }.getOrNull()?.takeIf { it.isNotEmpty() }
}
