package space.einundzwanzig.meetup.data.trust

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.DartDuration
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.trust.score.TrustScore
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import java.util.TimeZone

/**
 * Score part of `test/badge_reputation_regression_test.dart` (spec 04 §10.2): real signed
 * kind 21000 organizer events and kind 21002 claims. "Restore" is
 * `BackupService.restoreBadges` = `saveBadges(list, replaceUnreadable = true)` on an
 * in-memory badge repository. `BadgeSyncFormat.merge` belongs to `:data:badgesync`; its case
 * keeps the store round trip and the direct `sameReputation` assertion.
 */
class TrustScoreRegressionTest {

    private val organizer = KeyPair.fromPrivateKeyHex("1".padStart(64, '0'))
    private val attendee = KeyPair.fromPrivateKeyHex("2".padStart(64, '0'))

    private val time = FakeTime(DartDateTime.utc(2026, 10, 21, 21).microsecondsSinceEpoch)
    private val calc = TrustScoreCalculator.create(time, UTC_ZONE)
    private val firstDate = DartDateTime.now(time).subtract(DartDuration.of(days = 180))
    private val today = DartDateTime.utc(2026, 10, 21, 19)
    private lateinit var previousZone: TimeZone

    @Before
    fun pinZone() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(previousZone)

    private fun scan(meetup: String = "Aschaffenburg", daysAgo: Long = 14, secondsLater: Long = 0, id: String? = null): MeetupBadge {
        val date = today.subtract(DartDuration.of(days = daysAgo)).add(DartDuration.of(seconds = secondsLater))
        val slug = DartString.lower(meetup)
        val eventKey = "$slug-${date.toIso8601String().substring(0, 10)}"
        val createdAt = date.millisecondsSinceEpoch / 1000
        val org = EventIds.sign(
            organizer, createdAt, 21000,
            listOf(listOf("t", "badge"), listOf("m", slug)),
            DartJson.encode(mapOf("meetup" to meetup, "event" to eventKey)),
        )
        val claim = EventIds.sign(
            attendee, createdAt, 21002,
            listOf(listOf("t", "badge_claim"), listOf("p", organizer.publicKeyHex)),
            DartJson.encode(mapOf("action" to "claim_badge", "org_event_id" to org.id, "org_sig" to org.sig)),
        )
        return MeetupBadge(
            id = id ?: slug,
            meetupName = meetup,
            meetupEventId = eventKey,
            date = date,
            iconPath = "assets/badge_icon.png",
            delivery = "rolling_qr",
            blockHeight = 971000,
            signerNpub = organizer.npub,
            sigVersion = 2,
            sig = org.sig,
            sigId = org.id,
            sigContent = org.content,
            adminPubkey = organizer.publicKeyHex,
            claimSig = claim.sig,
            claimEventId = claim.id,
            claimPubkey = attendee.publicKeyHex,
            claimTimestamp = claim.createdAt,
        )
    }

    private fun marker(participant: MeetupBadge) = MeetupBadge(
        id = "org-${participant.meetupEventId}",
        meetupName = participant.meetupName,
        meetupEventId = participant.meetupEventId,
        date = participant.date,
        iconPath = "",
        delivery = "organizer",
        isOrganizer = true,
    )

    private fun score(badges: List<MeetupBadge>): TrustScore = calc.calculate(badges, firstDate)

    private fun sameReputation(baseline: List<MeetupBadge>, actual: List<MeetupBadge>) {
        val expected = score(baseline)
        val observed = score(actual)
        assertEquals(expected.totalBadges, observed.totalBadges)
        assertEquals(expected.uniqueMeetups, observed.uniqueMeetups)
        assertEquals(expected.uniqueSigners, observed.uniqueSigners)
        assertEquals(expected.uniqueCities, observed.uniqueCities)
        assertEquals(expected.totalScore, observed.totalScore, 1e-9)
        assertEquals(expected.activityScore, observed.activityScore, 1e-9)
        assertEquals(expected.diversityScore, observed.diversityScore, 0.0)
        assertEquals(expected.qualityScore, observed.qualityScore, 0.0)
        assertEquals(expected.currentPhase, observed.currentPhase)
        assertEquals(expected.meetsPromotionThreshold, observed.meetsPromotionThreshold)
        for ((key, p) in expected.progress) {
            assertEquals(key, p.current, observed.progress.getValue(key).current)
            assertEquals(key, p.required, observed.progress.getValue(key).required)
        }
    }

    private fun repo(): BadgeRepository =
        BadgeRepository.create(InMemoryKeyValueStore(), InMemorySecureStore(), time, Dispatchers.Unconfined)

    private suspend fun BadgeRepository.restore(list: List<MeetupBadge>): Boolean = saveBadges(list, replaceUnreadable = true)

    @Test
    fun monthlyVisits_bothDatesCount() = runTest {
        val r = repo()
        val visits = listOf(scan(daysAgo = 42), scan(daysAgo = 14))
        assertEquals(visits[0].id, visits[1].id)
        assertNotEquals(visits[0].meetupEventId, visits[1].meetupEventId)
        assertTrue(r.restore(visits))
        val loaded = r.loadBadges()
        assertEquals(2, loaded.size)
        assertEquals(2, score(loaded).totalBadges)
        assertEquals(1, score(loaded).uniqueMeetups)
        assertTrue(score(loaded).activityScore > score(listOf(visits.last())).activityScore)
        sameReputation(visits, loaded)
    }

    @Test
    fun rollingQr_sameDateCountsOnce() = runTest {
        val r = repo()
        val first = scan()
        val second = scan(id = "scan-2", secondsLater = 10)
        assertNotEquals(first.sigId, second.sigId)
        val copies = listOf(first, second, first, second)
        sameReputation(listOf(first), copies)
        assertTrue(r.restore(copies))
        val loaded = r.loadBadges()
        assertEquals(1, loaded.size)
        sameReputation(listOf(first), loaded)
    }

    @Test
    fun participantAndMarker_onlyParticipationCounts() = runTest {
        val r = repo()
        val participant = scan()
        val org = marker(participant)
        assertTrue(r.restore(listOf(participant, org, org)))
        val loaded = r.loadBadges()
        assertEquals(2, loaded.size)
        assertEquals(1, loaded.count { it.isOrganizer })
        sameReputation(listOf(participant), loaded)
        assertEquals(0.0, score(listOf(org, org)).totalScore, 0.0)
    }

    @Test
    fun syncAndReRestore_doNotRaiseScore() = runTest {
        val r = repo()
        val visits = listOf(scan(daysAgo = 70), scan(daysAgo = 42), scan(daysAgo = 14))
        val extraVisit = scan(meetup = "Darmstadt", daysAgo = 21)
        val baseline = visits + extraVisit
        val duplicateScans = visits.map { it.copyWith(id = "${it.id}-rescan") }
        val markers = visits.map(::marker)
        val all = baseline + duplicateScans + markers
        assertTrue(r.restore(all))
        val stored = r.loadBadges()
        assertEquals(7, stored.size) // four participations + three markers
        sameReputation(baseline, stored)
        assertTrue(r.restore(stored + all))
        val loaded = r.loadBadges()
        assertEquals(7, loaded.size)
        sameReputation(baseline, loaded)
        sameReputation(baseline, all)
    }

    @Test
    fun markersOfOtherCities_doNotRaiseReputation() {
        val visits = listOf(scan(daysAgo = 42), scan(daysAgo = 14), scan(meetup = "Darmstadt", daysAgo = 21))
        sameReputation(visits, visits + marker(scan(meetup = "Berlin")))
    }

    @Test
    fun rescans_doNotTakeWeeklySlots() {
        val visits = listOf(
            scan(daysAgo = 14),
            scan(meetup = "Darmstadt", daysAgo = 14),
            scan(meetup = "Frankfurt", daysAgo = 14),
        )
        val inflated = visits + List(20) { i -> visits.first().copyWith(id = "rescan-$i") }
        sameReputation(visits, inflated)
        assertEquals(2, score(inflated).badgeValues.count { it.totalValue > 0 })
    }

    @Test
    fun signedMarker_addsNothing() {
        val participant = scan()
        val signedMarker = participant.copyWith(id = "org-marker", isOrganizer = true)
        assertTrue(signedMarker.isNostrSigned)
        sameReputation(listOf(participant), listOf(participant, signedMarker, signedMarker))
        assertEquals(0.0, score(listOf(signedMarker)).totalScore, 0.0)
    }
}
