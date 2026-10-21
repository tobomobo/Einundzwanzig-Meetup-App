package space.einundzwanzig.meetup.data.badges

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.DartDuration
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.text.DartString

/**
 * Badge-level part of `test/badge_reputation_regression_test.dart`: realistic scans with real
 * test signatures, restored the way `BackupService.restoreBadges` does it
 * (`saveBadges(badges, replaceUnreadable = true)`) and loaded again.
 *
 * Only the storage/identity assertions live here (entry counts, union per event, organizer
 * markers kept apart). The `TrustScoreService` comparisons (`sameReputation`) and the
 * `BadgeSyncFormat.merge` case belong to `:data:trust` / `:data:badgesync`, which depend on this
 * module and port them there. In particular these Dart cases are ported with identical inputs in
 * `:data:trust` `TrustScoreRegressionTest`, not here:
 * - 'Organisator-Marker anderer Städte erhöhen die Reputation nicht' (visits at 42/14/21 days,
 *   Berlin marker),
 * - 'Mehrfachscans belegen keine zusätzlichen Plätze im Wochenlimit' (20 rescans `rescan-$i`,
 *   weekly cap = 2 slots),
 * - the `score([signedMarker]).totalScore == 0` assertion of 'auch Marker mit Signatur…'.
 */
class BadgeReputationRegressionTest {

    private val organizer = KeyPair.fromPrivateKeyHex("1".padStart(64, '0'))
    private val attendee = KeyPair.fromPrivateKeyHex("2".padStart(64, '0'))

    /** Fixed "today" instead of `DateTime.now()`, so the vectors are reproducible. */
    private val today = DartDateTime.utc(2026, 10, 21, 19)

    private fun scan(
        meetup: String = "Aschaffenburg",
        daysAgo: Long = 14,
        secondsLater: Long = 0,
        id: String? = null,
    ): MeetupBadge {
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
            // Like the regular scanner: the meetup id stays the same across visits.
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

    @Test
    fun `monthly visits - same meetup id, both dates stay`() = runTest {
        val h = StoreHarness()
        val visits = listOf(scan(daysAgo = 42), scan(daysAgo = 14))
        assertEquals(visits[0].id, visits[1].id)
        assertNotEquals(visits[0].meetupEventId, visits[1].meetupEventId)
        assertTrue(h.repo.saveBadges(visits, replaceUnreadable = true))
        val loaded = h.repo.loadBadges()
        assertEquals(2, loaded.size)
        assertEquals(visits.map { it.meetupEventId }, loaded.map { it.meetupEventId })
    }

    @Test
    fun `rolling QR - different scan ids and signatures on the same date count once`() = runTest {
        val h = StoreHarness()
        val first = scan()
        val second = scan(id = "scan-2", secondsLater = 10)
        assertNotEquals(first.sigId, second.sigId)
        val copies = listOf(first, second, first, second)
        assertEquals(1, BadgeIdentity.unionByIdentity(copies, emptyList()).size)
        assertTrue(h.repo.saveBadges(copies, replaceUnreadable = true))
        val loaded = h.repo.loadBadges()
        assertEquals(1, loaded.size)
        assertEquals(first.id, loaded.single().id)
    }

    @Test
    fun `participation and organizer marker stay apart`() = runTest {
        val h = StoreHarness()
        val participant = scan()
        val org = marker(participant)
        assertTrue(h.repo.saveBadges(listOf(participant, org, org), replaceUnreadable = true))
        val loaded = h.repo.loadBadges()
        assertEquals(2, loaded.size)
        assertEquals(1, loaded.count { it.isOrganizer })
    }

    @Test
    fun `re-restore of the same collection adds nothing`() = runTest {
        val h = StoreHarness()
        val visits = listOf(scan(daysAgo = 70), scan(daysAgo = 42), scan(daysAgo = 14))
        val extraVisit = scan(meetup = "Darmstadt", daysAgo = 21)
        val baseline = visits + extraVisit
        val duplicateScans = visits.map { it.copyWith(id = "${it.id}-rescan") }
        val markers = visits.map(::marker)
        val all = baseline + duplicateScans + markers
        assertTrue(h.repo.saveBadges(all, replaceUnreadable = true))
        assertEquals(7, h.repo.loadBadges().size) // four participations + three markers
        assertTrue(h.repo.saveBadges(h.repo.loadBadges() + all, replaceUnreadable = true))
        assertEquals(7, h.repo.loadBadges().size)
    }

    @Test
    fun `signed marker is still a marker`() {
        val participant = scan()
        val signedMarker = participant.copyWith(id = "org-marker", isOrganizer = true)
        assertTrue(signedMarker.isNostrSigned)
        // Markers never replace a participation of the same event.
        val union = BadgeIdentity.unionByIdentity(listOf(participant, signedMarker, signedMarker), emptyList())
        assertEquals(listOf(false, true), union.map { it.isOrganizer })
    }
}
