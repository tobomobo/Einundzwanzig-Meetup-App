package space.einundzwanzig.meetup.data.issuance

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.admin.model.AdminCheckResult
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaims
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.badges.security.ExpiryInfo
import space.einundzwanzig.meetup.data.issuance.internal.DefaultBadgeScanProcessor
import space.einundzwanzig.meetup.data.issuance.internal.DefaultEventBadgeChainService
import space.einundzwanzig.meetup.data.issuance.nfc.NfcBadgePayload
import space.einundzwanzig.meetup.data.issuance.nfc.NfcPayloadError
import space.einundzwanzig.meetup.data.issuance.scan.AdminCheckInfo
import space.einundzwanzig.meetup.data.issuance.scan.ClaimInfo
import space.einundzwanzig.meetup.data.issuance.scan.ScanOutcome
import space.einundzwanzig.meetup.data.issuance.scan.ScanStrings
import space.einundzwanzig.meetup.data.issuance.session.MeetupSession
import space.einundzwanzig.meetup.data.issuance.session.NonceStatus
import space.einundzwanzig.meetup.data.issuance.session.RollingQr
import java.util.TimeZone

/** `_startQRScan` / `_startNfcRead` / `_processFoundTagData` (spec 02 §2.1). */
class BadgeScanProcessorTest {

    private val time = FakeTime()
    private val store = StoreHarness(time = time)
    private val collector = KeyPair.generate()
    private val identity = FakeIdentity(npub = collector.npub, pubkeyHex = collector.publicKeyHex)
    private val ben = AdminCheckResult(true, meetup = "Bad Neuenahr", name = "Ben", source = AdminCheckResult.SOURCE_NOSTR_RELAY)
    private val admins = FakeAdmins(byPubkey = { if (it == ORGANIZER.publicKeyHex) ben else notFound })
    private val directory = FakeDirectory()
    private val calendar = FakeCalendar()
    private val claims = FakeClaimService(collector, time)
    private var heights = FakeBlockHeights(918_000, 918_000, 918_000)
    private val label = "Unbekanntes Meetup"
    private lateinit var savedZone: TimeZone

    @Before
    fun zone() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(BERLIN))
    }

    @After
    fun restore() = TimeZone.setDefault(savedZone)

    private fun processor() = DefaultBadgeScanProcessor(
        repository = store.repo,
        claims = claims,
        chain = DefaultEventBadgeChainService(calendar, admins).also { it.log = quietLog() },
        admins = admins,
        directory = directory,
        identity = identity,
        blockHeights = heights,
        time = time,
        zones = berlinZone,
    ).also { it.log = quietLog() }

    private suspend fun rollingQr(meetupId: String = "bad-neuenahr-de", signer: KeyPair = ORGANIZER, lat: Double = 0.0): String {
        val base = signedPayload(meetupId, signer, time)
        val session = MeetupSession("ab".repeat(32), 0, Long.MAX_VALUE, meetupId, "", "", 917_000, "", lat, lat)
        return RollingQr.buildQrString(DartJson.encode(base), session, time.millis / 1000)
    }

    private suspend fun found(raw: String): ScanOutcome.Found {
        val outcome = processor().processQrText(raw, label)
        assertTrue("$outcome", outcome is ScanOutcome.Found)
        return outcome as ScanOutcome.Found
    }

    @Test
    fun `fresh rolling qr of a known organizer gives a bound pending badge`() = runTest {
        val qr = rollingQr(lat = 50.5)
        val result = found(qr)
        val b = result.pending.badge
        assertEquals("bad-neuenahr", b.id)
        assertEquals("Bad Neuenahr, DE", b.meetupName)
        assertEquals("bad-neuenahr-2025-10-09", b.meetupEventId)
        assertEquals("rolling_qr", b.delivery)
        assertEquals("assets/badge_icon.png", b.iconPath)
        assertEquals(918_000, b.blockHeight)
        assertEquals(ORGANIZER.npub, b.signerNpub)
        assertEquals(ORGANIZER.publicKeyHex, b.adminPubkey)
        assertEquals(128, b.sig.length)
        assertEquals("", b.sigId)
        assertEquals(2L, b.sigVersion)
        assertFalse(b.isEvent)
        assertFalse(b.isRetroactive)
        assertTrue(b.presenceVerified)
        assertEquals("", b.coverUrl)
        assertFalse(b.date.isUtc)
        assertEquals(time.millis * 1000, b.date.microsecondsSinceEpoch)
        assertTrue(b.isNostrSigned)

        // sigContent = the full scanned map minus _verified_by, delivery appended last.
        assertEquals(qr.dropLast(1) + ",\"delivery\":\"rolling_qr\"}", b.sigContent)
        val reparsed = DartJson.decode(b.sigContent).jsonObject
        assertTrue(BadgeSecurity.verify(reparsed, time.millis / 1000 + 999_999, checkExpiry = false).isValid)

        // Claim bound to the collector and verifiable.
        assertEquals(collector.publicKeyHex, b.claimPubkey)
        assertEquals(time.millis / 1000, b.claimTimestamp)
        assertTrue(BadgeClaims.verifyBadgeClaim(b).isValid)
        assertEquals(ClaimInfo.Bound, result.info.claim)

        assertEquals(0.0, result.pending.orgLat, 0.0) // la/lo are unsigned (H1)
        assertEquals(AdminCheckInfo.KnownOrganizer("Ben"), result.info.adminCheck)
        assertFalse(result.info.unknownSigner)
        assertEquals(Nip19.shortenNpub(ORGANIZER.npub), result.info.verifiedBy)
        assertEquals(ExpiryInfo.Valid(14_400), result.info.expiry)
        assertEquals(listOf(ORGANIZER.publicKeyHex), admins.pubkeyQueries)
        assertTrue(store.repo.loadBadges().isEmpty()) // nothing stored before confirmation
    }

    @Test
    fun `confirmation text is assembled like Flutter`() = runTest {
        val info = found(rollingQr()).info
        assertEquals(
            "FOUND\n\nLOC(Bad Neuenahr, DE)\nBLOCK(918000)\nBY(${Nip19.shortenNpub(ORGANIZER.npub)})\nPROOF\nBOUND" +
                "\n\nKNOWN(Ben)\n\nEXPIRY(Gültig noch 4h 0min)",
            info.message(Strings),
        )
        val bare = info.copy(blockHeight = 0, verifiedBy = null, sigVersion = 0, claim = ClaimInfo.Failed("kaputt"),
            adminCheck = AdminCheckInfo.None, expiry = ExpiryInfo.NoExpiry)
        assertEquals("FOUND\n\nLOC(Bad Neuenahr, DE)\n⚠ Binding: kaputt", bare.message(Strings))
        assertEquals("FOUND\n\nLOC(Bad Neuenahr, DE)\n\n\nKNOWN(ADMIN)", bare.copy(claim = ClaimInfo.None,
            adminCheck = AdminCheckInfo.KnownOrganizer(null)).message(Strings))
    }

    @Test
    fun `stale rolling qr is rejected before verification`() = runTest {
        val qr = rollingQr()
        time.millis += 25_000
        val outcome = processor().processQrText(qr, label)
        assertTrue(outcome is ScanOutcome.NonceRejected)
        assertEquals(NonceStatus.EXPIRED, (outcome as ScanOutcome.NonceRejected).validation.status)
        assertEquals(0, claims.created)
    }

    @Test
    fun `wrongly typed rolling fields are malformed, not expired`() = runTest {
        val qr = DartJson.decode(rollingQr()).jsonObject
        val bad = JsonObject(LinkedHashMap(qr).apply { put("ts", JsonPrimitive("not-a-step")) })
        val outcome = processor().processQr(bad, label)
        assertTrue("$outcome", outcome is ScanOutcome.Malformed)
        assertEquals("Ungültiges Rolling-QR-Format.", (outcome as ScanOutcome.Malformed).detail)
        assertTrue(outcome.rollingQrFormat)
        assertEquals(0, claims.created)
    }

    @Test
    fun `tampered and expired payloads fail verification`() = runTest {
        val qr = DartJson.decode(rollingQr()).jsonObject
        val tampered = qr.with("m" to JsonPrimitive("wien-at"))
        val r = processor().processQr(tampered, label)
        assertTrue(r is ScanOutcome.VerificationFailed)
        assertEquals("Verifikations-Fehler: Invalid event", (r as ScanOutcome.VerificationFailed).result.message)
        assertEquals(space.einundzwanzig.meetup.data.badges.security.VerifyFailure.InvalidSignature, r.result.reason)

        // Static payload (no n/ts) after x: expiry check.
        val base = signedPayload("bad-neuenahr-de", time = time)
        time.millis += (4 * 3600 + 5) * 1000L
        val expired = processor().processQr(base, label)
        assertTrue(expired is ScanOutcome.VerificationFailed)
        assertTrue((expired as ScanOutcome.VerificationFailed).result.message.startsWith("Tag abgelaufen!"))
        assertTrue(expired.result.reason is space.einundzwanzig.meetup.data.badges.security.VerifyFailure.Expired)

        // v1 legacy is rejected.
        val v1 = processor().processQr(DartJson.decode("{\"v\":1,\"type\":\"BADGE\",\"meetup_id\":\"x\"}").jsonObject, label)
        assertEquals(1, (v1 as ScanOutcome.VerificationFailed).result.version)
        assertEquals(space.einundzwanzig.meetup.data.badges.security.VerifyFailure.LegacyRejected, v1.result.reason)
    }

    @Test
    fun `camera filter recognises review demo and badge json only`() = runTest {
        val p = processor()
        assertEquals(ScanOutcome.ReviewDemo, p.processQrText("21review:einundzwanzig-meetup-demo", label))
        assertEquals(ScanOutcome.ReviewDemo, p.processQrText("  21review:einundzwanzig-meetup-demo ", label))
        assertNull(p.processQrText("hello", label))
        assertNull(p.processQrText("{\"foo\":1}", label))
        assertNull(p.processQrText("[1,2]", label))
        assertNull(p.processQrText("21v3:abc", label))
        assertTrue(p.processQrText("{\"type\":\"BADGE\"}", label) is ScanOutcome.VerificationFailed)
    }

    @Test
    fun `organizer cannot badge himself`() = runTest {
        identity.npub = ORGANIZER.npub
        assertEquals(ScanOutcome.SelfScan, processor().processQrText(rollingQr(), label))
        assertEquals(0, claims.created)
        // Any organizer key: the npub comparison catches it.
        val other = KeyPair.generate()
        identity.npub = other.npub
        assertEquals(ScanOutcome.SelfScan, processor().processQrText(rollingQr(signer = other), label))
        // Lookup errors fail open.
        identity.fail = IllegalStateException("keystore")
        assertTrue(processor().processQrText(rollingQr(), label) is ScanOutcome.Found)
    }

    @Test
    fun `already collected today by event id, by name and day, organizer markers excluded`() = runTest {
        val today = DartDateTime.now(time)
        store.repo.saveBadges(listOf(MeetupBadge("org-bad-neuenahr-2025-10-09", "Bad Neuenahr, DE", today, "",
            meetupEventId = "bad-neuenahr-2025-10-09", isOrganizer = true)))
        assertTrue(processor().processQrText(rollingQr(), label) is ScanOutcome.Found)

        store.repo.saveBadges(listOf(MeetupBadge("bad-neuenahr", "x", today, "", meetupEventId = "bad-neuenahr-2025-10-09")))
        assertEquals(ScanOutcome.AlreadyCollected("Bad Neuenahr, DE", isEvent = false), processor().processQrText(rollingQr(), label))

        store.repo.saveBadges(listOf(MeetupBadge("legacy", "Bad Neuenahr, DE", today, "")))
        assertTrue(processor().processQrText(rollingQr(), label) is ScanOutcome.AlreadyCollected)

        val yesterday = DartDateTime.fromMillisecondsSinceEpoch(time.millis - 86_400_000)
        store.repo.saveBadges(listOf(MeetupBadge("legacy", "Bad Neuenahr, DE", yesterday, "")))
        assertTrue(processor().processQrText(rollingQr(), label) is ScanOutcome.Found)
    }

    @Test
    fun `self scan wins over already collected`() = runTest {
        store.repo.saveBadges(listOf(MeetupBadge("bad-neuenahr", "x", DartDateTime.now(time), "", meetupEventId = "bad-neuenahr-2025-10-09")))
        identity.npub = ORGANIZER.npub
        assertEquals(ScanOutcome.SelfScan, processor().processQrText(rollingQr(), label))
    }

    @Test
    fun `registry unavailable is a failed check, not an accusation`() = runTest {
        admins.byPubkey = { AdminCheckResult(false, source = AdminCheckResult.SOURCE_UNAVAILABLE) }
        val r = found(rollingQr())
        assertEquals(AdminCheckInfo.CheckFailed, r.info.adminCheck)
        assertTrue(r.info.unknownSigner)
        admins.byPubkey = { throw IllegalStateException("offline") }
        assertEquals(AdminCheckInfo.CheckFailed, found(rollingQr()).info.adminCheck)
    }

    @Test
    fun `portal leader of THIS meetup, then the portal nostr field, else unknown`() = runTest {
        admins.byPubkey = { notFound }
        val bn = meetup("107", "Bad Neuenahr")
        directory.meetups = listOf(bn)
        val leaderQueries = mutableListOf<Pair<Int, String>>()
        directory.leaders = { id, npub -> leaderQueries += id to npub; true }
        found(rollingQr()).let {
            assertEquals(AdminCheckInfo.PortalOrganizer("Bad Neuenahr"), it.info.adminCheck)
            assertFalse(it.info.unknownSigner)
        }
        assertEquals(listOf(107 to ORGANIZER.npub), leaderQueries)

        directory.leaders = { _, _ -> null }
        found(rollingQr()).let {
            assertEquals(AdminCheckInfo.CheckFailed, it.info.adminCheck)
            assertTrue(it.info.unknownSigner)
        }

        directory.leaders = { _, _ -> false }
        directory.organizerFor = { if (it == ORGANIZER.publicKeyHex) bn.copy(name = "Einundzwanzig Ahrtal") else null }
        assertEquals(AdminCheckInfo.PortalOrganizer("Einundzwanzig Ahrtal"), found(rollingQr()).info.adminCheck)

        directory.organizerFor = { null }
        found(rollingQr()).let {
            assertEquals(AdminCheckInfo.UnknownSigner, it.info.adminCheck)
            assertTrue(it.info.unknownSigner)
        }

        // A fallback meetup without numeric portal id skips the leader check.
        directory.meetups = listOf(meetup("m_x", "Bad Neuenahr"))
        directory.leaders = { _, _ -> error("must not be asked") }
        assertEquals(AdminCheckInfo.UnknownSigner, found(rollingQr()).info.adminCheck)
    }

    @Test
    fun `portal city is the display name but the signed slug stays the id`() = runTest {
        directory.meetups = listOf(meetup("5", "Neu-Ulm"))
        val b = found(rollingQr("neu-ulm-de")).pending.badge
        assertEquals("Neu-Ulm, DE", b.meetupName)
        assertEquals("neu-ulm", b.id)
        assertEquals("neu-ulm-2025-10-09", b.meetupEventId)

        // Old organizer apps signed only the slug: country from the portal.
        directory.meetups = listOf(meetup("6", "Wien", country = "AT"))
        assertEquals("Wien, AT", found(rollingQr("wien")).pending.badge.meetupName)
    }

    @Test
    fun `names without a usable slug get no co-attendance id`() = runTest {
        assertEquals("", found(rollingQr("unbekanntes-meetup")).pending.badge.meetupEventId)
        assertEquals("", found(rollingQr("---")).pending.badge.meetupEventId)
        val empty = found(rollingQr("")).pending.badge
        assertEquals("", empty.meetupEventId)
        assertEquals("", empty.meetupName)
    }

    @Test
    fun `block height falls back to the tag after one retry`() = runTest {
        heights = FakeBlockHeights()
        assertEquals(917_000, found(rollingQr()).pending.badge.blockHeight)
        assertEquals(2, heights.calls)
        heights = FakeBlockHeights(0, 918_500)
        assertEquals(918_500, found(rollingQr()).pending.badge.blockHeight)
    }

    @Test
    fun `failed binding is reported and the badge stays unbound`() = runTest {
        claims.fail = "Kein Schlüssel vorhanden — Claim nicht möglich"
        val r = found(rollingQr())
        assertEquals(ClaimInfo.Failed("Kein Schlüssel vorhanden — Claim nicht möglich"), r.info.claim)
        assertEquals("", r.pending.badge.claimSig)
        assertFalse(r.pending.badge.isClaimed)
    }

    @Test
    fun `event badge passes the trust chain and takes the cover image`() = runTest {
        val helper = KeyPair.generate()
        val event = calendarEvent(ORGANIZER.publicKeyHex, issuers = listOf(helper.publicKeyHex))
        calendar.event = event
        val m = BadgeSecurity.eventMeetupId(event.address, event.title)
        val r = found(rollingQr(m, signer = helper))
        val b = r.pending.badge
        assertTrue(b.isEvent)
        assertEquals("evt:zitadelle-2025", b.id)
        assertEquals("evt|${event.address}", b.meetupEventId)
        assertEquals("Bitcoin Zitadelle", b.meetupName)
        assertEquals("https://example.org/badge.png", b.coverUrl)
        assertEquals(AdminCheckInfo.EventIssuerOk("Bitcoin Zitadelle", "Ben"), r.info.adminCheck)
        assertFalse(r.info.unknownSigner)

        calendar.event = event.copy(issuers = emptyList())
        found(rollingQr(m, signer = helper)).let {
            assertEquals(AdminCheckInfo.EventSignerNotListed("Bitcoin Zitadelle"), it.info.adminCheck)
            assertTrue(it.info.unknownSigner)
            assertEquals("https://example.org/badge.png", it.pending.badge.coverUrl)
        }

        // A former issuer counts until his removal time, measured on the SCANNER's clock.
        val now = time.millis / 1000
        calendar.event = event.copy(issuers = emptyList(), formerIssuers = mapOf(helper.publicKeyHex to now))
        assertTrue(found(rollingQr(m, signer = helper)).info.adminCheck is AdminCheckInfo.EventIssuerOk)
        calendar.event = event.copy(issuers = emptyList(), formerIssuers = mapOf(helper.publicKeyHex to now - 1))
        assertTrue(found(rollingQr(m, signer = helper)).info.adminCheck is AdminCheckInfo.EventSignerNotListed)

        calendar.event = null
        assertEquals(AdminCheckInfo.EventNotFound, found(rollingQr(m, signer = helper)).info.adminCheck)
    }

    @Test
    fun `one badge per event regardless of the day`() = runTest {
        val event = calendarEvent(ORGANIZER.publicKeyHex)
        calendar.event = event
        val m = BadgeSecurity.eventMeetupId(event.address, event.title)
        val longAgo = DartDateTime.fromMillisecondsSinceEpoch(time.millis - 3 * 86_400_000L)
        store.repo.saveBadges(listOf(MeetupBadge("evt:zitadelle-2025", "x", longAgo, "", meetupEventId = "evt|${event.address}", isEvent = true)))
        assertEquals(ScanOutcome.AlreadyCollected("Bitcoin Zitadelle", isEvent = true), processor().processQrText(rollingQr(m), label))

        // Older event badges without the address: matched by title.
        store.repo.saveBadges(listOf(MeetupBadge("old", "Bitcoin Zitadelle", longAgo, "", isEvent = true)))
        assertTrue(processor().processQrText(rollingQr(m), label) is ScanOutcome.AlreadyCollected)
    }

    @Test
    fun `nfc text record yields a badge with delivery nfc`() = runTest {
        val base = signedPayload("bad-neuenahr-de", time = time)
        val json = NfcBadgePayload.tagJson(base)
        val r = processor().processNfcPayload(NfcBadgePayload.encodeTextPayload(json), label)
        assertTrue("$r", r is ScanOutcome.Found)
        val b = (r as ScanOutcome.Found).pending.badge
        assertEquals("nfc", b.delivery)
        assertEquals(json.dropLast(1) + ",\"delivery\":\"nfc\"}", b.sigContent)
    }

    @Test
    fun `nfc payload errors`() = runTest {
        val p = processor()
        assertEquals(ScanOutcome.NfcRejected(NfcPayloadError.PayloadEmpty), p.processNfcPayload(ByteArray(0), label))
        assertEquals(ScanOutcome.NfcRejected(NfcPayloadError.InvalidFormat), p.processNfcPayload(byteArrayOf(0x02, 0x65, 0x6E), label))
        assertTrue((p.processNfcPayload(NfcBadgePayload.encodeTextPayload("nope"), label) as ScanOutcome.NfcRejected).error is NfcPayloadError.InvalidTag)
        assertTrue((p.processNfcPayload(NfcBadgePayload.encodeTextPayload("[1]"), label) as ScanOutcome.NfcRejected).error is NfcPayloadError.InvalidTag)
        val badUtf8 = byteArrayOf(0x02, 0x65, 0x6E, 0xC3.toByte(), 0x28)
        assertTrue((p.processNfcPayload(badUtf8, label) as ScanOutcome.NfcRejected).error is NfcPayloadError.ReadError)
        val tampered = signedPayload("bad-neuenahr-de", time = time).with("b" to JsonPrimitive(1))
        assertTrue(p.processNfcPayload(NfcBadgePayload.encodeTextPayload(DartJson.encode(tampered)), label) is ScanOutcome.VerificationFailed)
    }

    private companion object {
        val notFound = AdminCheckResult(false, source = AdminCheckResult.SOURCE_NOT_FOUND)
    }

    private object Strings : ScanStrings {
        override fun verifyBadgeFound() = "FOUND"
        override fun verifyMsgLocation(name: String) = "LOC($name)"
        override fun verifyMsgBlock(block: Long) = "BLOCK($block)"
        override fun verifyMsgSignedBy(npub: String) = "BY($npub)"
        override fun verifyMsgProof() = "PROOF"
        override fun verifyMsgTagExpiry(expiry: String) = "EXPIRY($expiry)"
        override fun mvBadgeBound() = "BOUND"
        override fun verifyVerifiedAdmin() = "ADMIN"
        override fun mvKnownOrganizer(name: String) = "KNOWN($name)"
        override fun mvPortalOrganizer(meetup: String) = "PORTAL($meetup)"
        override fun mvAdminCheckFailed() = "FAILED"
        override fun mvUnknownSigner() = "UNKNOWN"
        override fun mvLegacyBadge() = "LEGACY"
        override fun mvEventIssuerOk(event: String, creator: String) = "EVOK($event,$creator)"
        override fun mvEventSignerNotListed(event: String) = "NOTLISTED($event)"
        override fun mvEventCreatorNotAuthorized(event: String) = "NOAUTH($event)"
        override fun mvEventHasNoBadge(event: String) = "NOBADGE($event)"
        override fun mvEventNotFound() = "NOTFOUND"
    }
}

