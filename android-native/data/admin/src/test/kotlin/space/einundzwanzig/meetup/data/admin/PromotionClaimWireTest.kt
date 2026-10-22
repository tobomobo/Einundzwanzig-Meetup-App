package space.einundzwanzig.meetup.data.admin

import org.junit.Assert.assertEquals
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.data.admin.claims.PromotionClaims
import space.einundzwanzig.meetup.data.admin.model.VerifiedClaim
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import java.time.ZoneId
import java.time.ZoneOffset

/** Wire format of the kind-30021 admin claim (spec 05 §3.5) and the stored organic claims. */
class PromotionClaimWireTest {

    private fun badge(
        claimPubkey: String,
        sig: String = "aa",
        date: DartDateTime = DartDateTime.utc(2026, 3, 7, 18),
    ) = MeetupBadge(
        id = "b",
        meetupName = "Aschaffenburg",
        date = date,
        iconPath = "",
        blockHeight = 875_000,
        sig = sig,
        sigId = "bb",
        adminPubkey = "cc",
        sigVersion = 2,
        sigContent = "{\"v\":2,\"t\":\"B\"}",
        claimSig = "dd",
        claimEventId = "ee",
        claimPubkey = claimPubkey,
        claimTimestamp = 1_700_000_000,
    )

    @Test
    fun `Claim-Inhalt hat exakt die Flutter-Schluesselreihenfolge`() {
        val content = PromotionClaims.claimContent(
            claimerNpub = "npub1x",
            meetupName = "Aschaffenburg",
            verifiedBadges = listOf(badge("ff")),
            claimedAtSeconds = 1_700_000_100,
            zone = ZoneOffset.UTC,
        )
        assertEquals(
            "{\"type\":\"admin_claim\",\"version\":1,\"claimer_npub\":\"npub1x\",\"meetup\":\"Aschaffenburg\"," +
                "\"verified_badges\":1,\"unique_signers\":1,\"proofs\":[{\"meetup\":\"Aschaffenburg\"," +
                "\"date\":\"2026-03-07T18:00:00.000Z\",\"block\":875000,\"sig\":\"aa\",\"sig_id\":\"bb\"," +
                "\"admin_pubkey\":\"cc\",\"sig_version\":2,\"sig_content\":\"{\\\"v\\\":2,\\\"t\\\":\\\"B\\\"}\"," +
                "\"claim_sig\":\"dd\",\"claim_event_id\":\"ee\",\"claim_timestamp\":1700000000}]," +
                "\"claimed_at\":1700000100}",
            content,
        )
        assertEquals(
            listOf(listOf("d", "einundzwanzig-admin-claim"), listOf("meetup", "Aschaffenburg")),
            PromotionClaims.claimTags("Aschaffenburg"),
        )
    }

    @Test
    fun `nur vom eigenen Schluessel gebundene Badges sind beanspruchbar`() {
        val own = "ff"
        val list = listOf(badge(own), badge("fremd"), badge(own, sig = ""), badge(""))
        assertEquals(1, PromotionClaims.claimableBadges(list, own).size)
    }

    @Test
    fun `VerifiedClaim JSON - Reihenfolge und organic-Flag`() {
        val claim = VerifiedClaim("abc", "Aschaffenburg", 3, 1, 1_700_000_000)
        assertEquals(
            "{\"claimer_pubkey\":\"abc\",\"meetup\":\"Aschaffenburg\",\"verified_badges\":3,\"unique_signers\":1," +
                "\"claimed_at\":1700000000,\"organic\":true}",
            DartJson.encode(claim.toJsonMap()),
        )
        assertEquals(claim, VerifiedClaim.fromJson(DartJson.decode("{\"claimer_pubkey\":\"abc\",\"meetup\":\"Aschaffenburg\",\"verified_badges\":3,\"unique_signers\":1,\"claimed_at\":1700000000}")))
        assertEquals(VerifiedClaim("", "", 0, 0, 0), VerifiedClaim.fromJson(DartJson.decode("{}")))
    }

    @Test
    fun `Beweis-Datum einer lokalen Badge-Zeit - lokale Zone, ohne Z`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 2026-03-07T18:00:00Z as a local (non-UTC) Dart DateTime, as stored for scanned badges.
        val local = badge("ff", date = DartDateTime.fromMillisecondsSinceEpoch(1_772_906_400_000L))
        assertEquals("2026-03-07T19:00:00.000", PromotionClaims.proofOf(local, berlin)["date"])
        // A UTC badge date keeps its UTC form with Z in any zone.
        assertEquals("2026-03-07T18:00:00.000Z", PromotionClaims.proofOf(badge("ff"), berlin)["date"])
    }
}
