package space.einundzwanzig.meetup.data.badges

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaims
import space.einundzwanzig.meetup.data.badges.internal.DefaultBadgeClaimService
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeStoreKeys
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.badges.security.BadgeSigner

/**
 * Claim format (spec 02 §7.4, `fixtures.claim`), the claim service, retroactive claiming, and
 * the badge-level parts of `test/promotion_claim_k1_test.dart`.
 */
class BadgeClaimTest {

    private val organizerPub = Fixtures["keys"].jsonObject["organizer"]!!.jsonObject["pub"]!!.jsonPrimitive.content
    private val attendee = KeyPair.fromPrivateKeyHex(Fixtures["keys"].jsonObject["attendee"]!!.jsonObject["priv"]!!.jsonPrimitive.content)
    private val compactSig = Fixtures["compactBadge"].jsonObject["sig"]!!.jsonPrimitive.content

    @Test
    fun `fixture claim`() {
        val o = Fixtures["claim"].jsonObject
        val content = BadgeClaims.claimContent(compactSig, "", organizerPub, 917001, 1760000123)
        assertEquals(o["content"]!!.jsonPrimitive.content, content)
        val tags = BadgeClaims.claimTags(organizerPub, 917001)
        assertEquals(DartJson.encode(o["tags"]!!), DartJson.encode(tags))
        assertEquals(o["serialized_for_id"]!!.jsonPrimitive.content, EventIds.serialize(attendee.publicKeyHex, 1760000123, 21002, tags, content))
        assertEquals(o["id"]!!.jsonPrimitive.content, EventIds.compute(attendee.publicKeyHex, 1760000123, 21002, tags, content))
        val result = BadgeClaims.verifyClaim(
            claimSig = o["sig"]!!.jsonPrimitive.content, claimEventId = o["id"]!!.jsonPrimitive.content,
            claimPubkey = attendee.publicKeyHex, claimTimestamp = 1760000123,
            orgSig = compactSig, orgEventId = "", orgPubkey = organizerPub, blockHeight = 917001,
        )
        assertEquals(o["valid"]!!.jsonPrimitive.boolean, result.isValid)
        assertEquals(Fixtures["keys"].jsonObject["attendee"]!!.jsonObject["npub"]!!.jsonPrimitive.content, result.claimerNpub)
        assertTrue(result.message.startsWith("Claim verifiziert ✓ — Gebunden an npub1"))

        val wrongTime = BadgeClaims.verifyClaim(
            o["sig"]!!.jsonPrimitive.content, o["id"]!!.jsonPrimitive.content, attendee.publicKeyHex, 1760000124,
            compactSig, "", organizerPub, 917001,
        )
        assertFalse(wrongTime.isValid)
        assertEquals("Claim Event-ID stimmt nicht überein", wrongTime.message)
        assertEquals("Fehlende Claim-Daten", BadgeClaims.verifyClaim("", "x", "y", 1, "", "", "", 0).message)
    }

    @Test
    fun `verifyClaim reports a bad signature like Flutter`() {
        val o = Fixtures["claim"].jsonObject
        val sig = o["sig"]!!.jsonPrimitive.content
        val bad = (if (sig[0] == '0') "1" else "0") + sig.substring(1)
        val r = BadgeClaims.verifyClaim(bad, o["id"]!!.jsonPrimitive.content, attendee.publicKeyHex, 1760000123, compactSig, "", organizerPub, 917001)
        assertFalse(r.isValid)
        assertEquals("Claim-Verifikation fehlgeschlagen: Invalid event", r.message)
    }

    @Test
    fun `createClaim signs with created_at equal to claimed_at`() = runTest {
        val time = FakeTime(1_760_000_123_000)
        val signer = TestSigner(attendee, time)
        val service = DefaultBadgeClaimService(signer, StoreHarness(time = time).repo, time)
        val r = service.createClaim(compactSig, "", organizerPub, 917001)
        assertTrue(r.success)
        assertEquals("Badge gebunden ✓", r.message)
        assertEquals(1760000123L, r.claimTimestamp)
        assertEquals(Fixtures["claim"].jsonObject["id"]!!.jsonPrimitive.content, r.claimEventId)
        assertTrue(service.verifyClaim(r.claimSig, r.claimEventId, r.claimPubkey, r.claimTimestamp, compactSig, "", organizerPub, 917001).isValid)
    }

    @Test
    fun `claim signed with explicit created_at verifies across a second boundary`() = runTest {
        val time = FakeTime(1_760_000_123_900)
        // The clock ticks over while the user approves (Amber/bunker): the signer still uses the
        // requested created_at, so one request suffices.
        val signer = object : BadgeSigner {
            var calls = 0
            override suspend fun canSign() = true
            override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String, createdAt: Long?): NostrEvent {
                calls++
                time.millis += 1_500
                return EventIds.sign(attendee, createdAt ?: time.nowSeconds(), kind, tags, content)
            }
        }
        val service = DefaultBadgeClaimService(signer, StoreHarness(time = time).repo, time)
        val r = service.createClaim(compactSig, "", organizerPub, 917001)
        assertTrue(r.success)
        assertEquals(1, signer.calls)
        assertEquals(1760000123L, r.claimTimestamp)
        assertEquals(Fixtures["claim"].jsonObject["id"]!!.jsonPrimitive.content, r.claimEventId)
        assertTrue(BadgeClaims.verifyClaim(r.claimSig, r.claimEventId, r.claimPubkey, r.claimTimestamp, compactSig, "", organizerPub, 917001).isValid)
    }

    @Test
    fun `createClaim re-requests when the signer picks another created_at`() = runTest {
        val time = FakeTime(1_760_000_123_000)
        val signer = TestSigner(attendee, time, createdAtOverride = { call -> if (call == 0) 1_760_000_124 else null })
        val service = DefaultBadgeClaimService(signer, StoreHarness(time = time).repo, time)
        val r = service.createClaim(compactSig, "", organizerPub, 917001)
        assertTrue(r.success)
        assertEquals(2, signer.calls)
        assertTrue(BadgeClaims.verifyClaim(r.claimSig, r.claimEventId, r.claimPubkey, r.claimTimestamp, compactSig, "", organizerPub, 917001).isValid)

        val always = TestSigner(attendee, time, createdAtOverride = { 1_760_000_999 })
        val failed = DefaultBadgeClaimService(always, StoreHarness(time = time).repo, time).createClaim(compactSig, "", organizerPub, 1)
        assertFalse(failed.success)
        assertTrue(failed.message.startsWith("Claim-Fehler"))
    }

    @Test
    fun `createClaim without key`() = runTest {
        val time = FakeTime()
        val service = DefaultBadgeClaimService(TestSigner(attendee, time, canSign = false), StoreHarness(time = time).repo, time)
        val r = service.createClaim(compactSig, "", organizerPub, 1)
        assertFalse(r.success)
        assertEquals("Kein Schlüssel vorhanden — Claim nicht möglich", r.message)
    }

    private fun unclaimed(id: String, presenceVerified: Boolean = true) = MeetupBadge(
        id = id, meetupName = "Bad Neuenahr", meetupEventId = id, date = DartDateTime.utc(2025, 10, 9), iconPath = "",
        blockHeight = 917000, sig = compactSig, adminPubkey = organizerPub, sigVersion = 2, presenceVerified = presenceVerified,
    )

    @Test
    fun `ensureBadgesClaimed claims retroactively, keeps presence, saves`() = runTest {
        val h = StoreHarness()
        val service = DefaultBadgeClaimService(TestSigner(attendee, h.time), h.repo, h.time)
        val plain = MeetupBadge(id = "plain", meetupName = "X", date = DartDateTime.utc(2025, 1, 1), iconPath = "")
        assertTrue(h.repo.saveBadges(listOf(unclaimed("a", presenceVerified = false), plain)))
        val input = h.repo.badges.value
        val result = service.ensureBadgesClaimed(input)
        assertEquals(1, result.claimedCount)
        val claimed = result.badges[0]
        assertTrue(claimed.isFullyBound)
        assertTrue(claimed.isRetroactive)
        assertFalse(claimed.presenceVerified)
        assertSame(plain, result.badges[1])
        assertTrue(BadgeClaims.verifyBadgeClaim(claimed).isValid)
        assertEquals(listOf("a", "plain"), h.repo.loadBadges().map { it.id })
        assertTrue(h.repo.loadBadges().first().isClaimed)

        val again = service.ensureBadgesClaimed(result.badges)
        assertEquals(0, again.claimedCount)
        assertSame(result.badges, again.badges)
    }

    @Test
    fun `an unreadable store costs no signer prompt`() = runTest {
        val h = StoreHarness()
        assertTrue(h.repo.saveBadges(listOf(unclaimed("a"))))
        val stale = h.repo.badges.value
        // A fresh process (nothing loaded yet) whose keys no longer open the file.
        val fresh = StoreHarness(h.prefs, h.secure, h.time)
        assertTrue(h.secure.write(BadgeStoreKeys.KEY, "b".repeat(64)))
        assertTrue(h.secure.write(BadgeStoreKeys.KEY_BACKUP, "b".repeat(64)))
        val signer = TestSigner(attendee, h.time)
        val result = DefaultBadgeClaimService(signer, fresh.repo, h.time).ensureBadgesClaimed(stale)
        assertEquals(0, signer.calls)
        assertEquals(0, result.claimedCount)
        assertSame(stale, result.badges)
        assertTrue(fresh.repo.badgesUnreadable.value)
    }

    @Test
    fun `badges missing from the store cost no signer prompt`() = runTest {
        val h = StoreHarness()
        val signer = TestSigner(attendee, h.time)
        val foreign = listOf(unclaimed("x"))
        val result = DefaultBadgeClaimService(signer, h.repo, h.time).claimUnboundBadges(foreign)
        assertEquals(0, signer.calls)
        assertEquals(0, result.claimedCount)
        assertTrue(h.repo.loaded.value)
    }

    @Test
    fun `failed save reports zero claims`() = runTest {
        val h = StoreHarness()
        assertTrue(h.repo.saveBadges(listOf(unclaimed("a"))))
        h.prefs.failAllWrites = true
        val service = DefaultBadgeClaimService(TestSigner(attendee, h.time), h.repo, h.time)
        val input = h.repo.badges.value
        val result = service.claimUnboundBadges(input)
        assertEquals(0, result.claimedCount)
        assertSame(input, result.badges)
        assertFalse(h.repo.loadBadges().single().isClaimed)
    }

    @Test
    fun `a badge scanned during the claim batch survives`() = runTest {
        val h = StoreHarness()
        assertTrue(h.repo.saveBadges(listOf(unclaimed("a"))))
        val scanned = MeetupBadge(id = "scan", meetupName = "Wien", meetupEventId = "scan", date = DartDateTime.utc(2025, 11, 1), iconPath = "")
        // The scanner saves while the user approves the claim in the signer.
        val signer = object : BadgeSigner {
            val inner = TestSigner(attendee, h.time)
            override suspend fun canSign() = true
            override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String, createdAt: Long?): NostrEvent {
                assertTrue(h.repo.update { it + scanned })
                return inner.signEvent(kind, tags, content, createdAt)
            }
        }
        val service = DefaultBadgeClaimService(signer, h.repo, h.time)
        val result = service.claimUnboundBadges(h.repo.badges.value)
        assertEquals(1, result.claimedCount)
        val stored = h.repo.loadBadges()
        assertEquals(listOf("a", "scan"), stored.map { it.id })
        assertTrue(stored[0].isClaimed)
        assertEquals(listOf("a", "scan"), result.badges.map { it.id })
    }

    @Test
    fun `a badge removed during the claim batch is not brought back`() = runTest {
        val h = StoreHarness()
        val keep = MeetupBadge(id = "keep", meetupName = "Wien", meetupEventId = "keep", date = DartDateTime.utc(2025, 11, 1), iconPath = "")
        assertTrue(h.repo.saveBadges(listOf(unclaimed("a"), keep)))
        val signer = object : BadgeSigner {
            val inner = TestSigner(attendee, h.time)
            override suspend fun canSign() = true
            override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String, createdAt: Long?): NostrEvent {
                assertTrue(h.repo.update { current -> current.filter { it.id != "a" } })
                return inner.signEvent(kind, tags, content, createdAt)
            }
        }
        val result = DefaultBadgeClaimService(signer, h.repo, h.time).claimUnboundBadges(h.repo.badges.value)
        assertEquals(0, result.claimedCount)
        assertEquals(listOf("keep"), h.repo.loadBadges().map { it.id })
    }

    /** A signer that runs [during] (a sync/restore swapping the stored variant) while the user approves. */
    private fun swappingSigner(h: StoreHarness, during: suspend () -> Unit) = object : BadgeSigner {
        val inner = TestSigner(attendee, h.time)
        override suspend fun canSign() = true
        override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String, createdAt: Long?): NostrEvent {
            during()
            return inner.signEvent(kind, tags, content, createdAt)
        }
    }

    @Test
    fun `a claim is not applied to a variant with other org data swapped in meanwhile`() = runTest {
        val h = StoreHarness()
        assertTrue(h.repo.saveBadges(listOf(unclaimed("a"))))
        val otherSig = "f".repeat(128)
        val signer = swappingSigner(h) {
            assertTrue(h.repo.update { current -> current.map { it.copyWith(sig = otherSig, lat = 48.2) } })
        }
        val result = DefaultBadgeClaimService(signer, h.repo, h.time).claimUnboundBadges(h.repo.badges.value)
        assertEquals(0, result.claimedCount)
        val stored = h.repo.loadBadges().single()
        assertFalse(stored.isClaimed)
        assertEquals(otherSig, stored.sig)
        assertEquals(48.2, stored.lat, 0.0)
    }

    @Test
    fun `a claim keeps the fields of a variant swapped in meanwhile`() = runTest {
        val h = StoreHarness()
        assertTrue(h.repo.saveBadges(listOf(unclaimed("a", presenceVerified = false))))
        val signer = swappingSigner(h) {
            assertTrue(h.repo.update { current -> current.map { it.copyWith(presenceVerified = true, lat = 48.2, lng = 16.4, coverUrl = "c") } })
        }
        val result = DefaultBadgeClaimService(signer, h.repo, h.time).claimUnboundBadges(h.repo.badges.value)
        assertEquals(1, result.claimedCount)
        val stored = h.repo.loadBadges().single()
        assertTrue(stored.isClaimed)
        assertTrue(stored.isRetroactive)
        assertTrue(stored.presenceVerified)
        assertEquals(48.2, stored.lat, 0.0)
        assertEquals(16.4, stored.lng, 0.0)
        assertEquals("c", stored.coverUrl)
        assertTrue(BadgeClaims.verifyBadgeClaim(stored).isValid)
    }

    @Test
    fun `concurrent ensureBadgesClaimed prompts once per badge`() = runTest {
        val h = StoreHarness()
        // Different block heights: same proof and second would give identical claim events.
        assertTrue(h.repo.saveBadges(listOf(unclaimed("a"), unclaimed("b").copyWith(blockHeight = 917001))))
        val gate = CompletableDeferred<Unit>()
        val signer = object : BadgeSigner {
            val inner = TestSigner(attendee, h.time)
            override suspend fun canSign() = true
            override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String, createdAt: Long?): NostrEvent {
                gate.await()
                return inner.signEvent(kind, tags, content, createdAt)
            }
        }
        val service = DefaultBadgeClaimService(signer, h.repo, h.time)
        val snapshot = h.repo.badges.value
        val first = async { service.ensureBadgesClaimed(snapshot) }
        val second = async { service.ensureBadgesClaimed(snapshot) }
        testScheduler.runCurrent()
        gate.complete(Unit)
        val results = listOf(first.await(), second.await())

        assertEquals(2, signer.inner.calls)
        assertEquals(2, results.sumOf { it.claimedCount })
        val stored = h.repo.loadBadges()
        assertTrue(stored.all { it.isClaimed })
        assertEquals(2, stored.map { it.claimEventId }.toSet().size)
    }

    @Test
    fun `verifyBadgeClaim of an unclaimed badge`() {
        assertEquals("Badge hat keinen Claim", BadgeClaims.verifyBadgeClaim(unclaimed("a")).message)
    }

    // ---- promotion_claim_k1_test, badge-level parts

    private val admin = KeyPair.generate()
    private val claimer = KeyPair.generate()
    private val stranger = KeyPair.generate()

    private fun organizerBadge(org: KeyPair, createdAt: Long, meetupId: String = "aschaffenburg-de", block: Long = 875000): JsonObject {
        val content = jsonObjectOf("v" to 2, "t" to "B", "m" to meetupId, "b" to block, "x" to createdAt + 4 * 3600)
        val ev = EventIds.sign(org, createdAt, 21000, listOf(listOf("t", "badge"), listOf("m", meetupId)), BadgeSecurity.canonicalJsonEncode(content))
        return JsonObject(content + mapOf("c" to JsonPrimitive(ev.createdAt), "p" to JsonPrimitive(ev.pubkey), "s" to JsonPrimitive(ev.sig)))
    }

    private data class Binding(val sig: String, val id: String, val ts: Long, val pubkey: String)

    /** `bindBadge` of the Dart test: a claim exactly like `createClaim`. */
    private fun bind(who: KeyPair, badge: JsonObject, sigId: String, claimedAt: Long): Binding {
        val p = badge["p"]!!.jsonPrimitive.content
        val b = badge["b"]!!.jsonPrimitive.content.toLong()
        val content = BadgeClaims.claimContent(badge["s"]!!.jsonPrimitive.content, sigId, p, b, claimedAt)
        val ev = EventIds.sign(who, claimedAt, 21002, BadgeClaims.claimTags(p, b), content)
        return Binding(ev.sig, ev.id, claimedAt, ev.pubkey)
    }

    private fun verifyBinding(badge: JsonObject, sigId: String, binding: Binding, claimerPubkey: String) = BadgeClaims.verifyClaim(
        binding.sig, binding.id, claimerPubkey, binding.ts,
        badge["s"]!!.jsonPrimitive.content, sigId, badge["p"]!!.jsonPrimitive.content, badge["b"]!!.jsonPrimitive.content.toLong(),
    )

    private val day = 1_760_000_000L - 10 * 86400

    @Test
    fun `k1 positive - organizer proof verifies without expiry and the claimer binding verifies`() {
        val badge = organizerBadge(admin, day)
        val sigId = Sha256.hexOfUtf8(badge["s"]!!.jsonPrimitive.content)
        val binding = bind(claimer, badge, sigId, day + 60)
        val v = BadgeSecurity.verify(DartJson.decode(DartJson.encode(badge)).jsonObject, nowSeconds = 1_760_000_000, checkExpiry = false)
        assertTrue(v.isValid)
        assertEquals(admin.publicKeyHex, v.adminPubkey)
        assertTrue(verifyBinding(badge, sigId, binding, claimer.publicKeyHex).isValid)
    }

    @Test
    fun `k1 attack - binding by a stranger does not count for the claimer`() {
        val badge = organizerBadge(admin, day)
        val sigId = Sha256.hexOfUtf8(badge["s"]!!.jsonPrimitive.content)
        val binding = bind(stranger, badge, sigId, day + 60)
        assertFalse(verifyBinding(badge, sigId, binding, claimer.publicKeyHex).isValid)
    }

    @Test
    fun `k1 attack - admin label on a stranger signature is exposed by the verified signer`() {
        val badge = organizerBadge(stranger, day)
        val v = BadgeSecurity.verify(badge, nowSeconds = 1_760_000_000, checkExpiry = false)
        assertTrue(v.isValid)
        assertFalse(v.adminPubkey == admin.publicKeyHex)
    }

    @Test
    fun `k1 attack - copied proofs share one claim proof id`() {
        val badge = organizerBadge(admin, day)
        val sigId = Sha256.hexOfUtf8(badge["s"]!!.jsonPrimitive.content)
        val binding = bind(claimer, badge, sigId, day + 60)
        val b = MeetupBadge(
            id = "x", meetupName = "A", date = DartDateTime.utc(2025, 1, 1), iconPath = "",
            blockHeight = 875000,
            sig = badge["s"]!!.jsonPrimitive.content, sigId = sigId, adminPubkey = admin.publicKeyHex, sigVersion = 2,
            claimSig = binding.sig, claimEventId = binding.id, claimPubkey = claimer.publicKeyHex, claimTimestamp = binding.ts,
        )
        val copies = List(3) { MeetupBadge.fromJson(b.toJson()) }
        assertEquals(1, copies.map { it.claimProofId }.toSet().size)
        assertTrue(BadgeClaims.verifyBadgeClaim(copies[2]).isValid)
    }

    @Test
    fun `fixture claim tags are the claim format`() {
        assertEquals(3, Fixtures["claim"].jsonObject["tags"]!!.jsonArray.size)
    }
}
