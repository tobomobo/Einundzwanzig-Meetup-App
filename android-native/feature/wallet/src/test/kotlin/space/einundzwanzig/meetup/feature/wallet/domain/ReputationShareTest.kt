package space.einundzwanzig.meetup.feature.wallet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.feature.wallet.badge

class ReputationShareTest {

    private val a = badge(
        id = "badge-001",
        name = "Aschaffenburg",
        date = "2025-03-15T00:00:00.000Z",
        block = 875000,
        sig = "a".repeat(128),
        sigId = "c".repeat(64),
        adminPubkey = "b".repeat(64),
        sigVersion = 2,
        claimSig = "d".repeat(128),
        claimPubkey = "e".repeat(64),
        claimTimestamp = 1_700_000_000,
        signerNpub = "npub1testadmin",
    )
    private val b = badge(id = "old-badge", name = "München, DE", date = "2026-10-11T19:03:12.345678", block = 0, signerNpub = "")

    @Test
    fun `share text matches the Dart vector`() {
        val expected = "🏆 MEINE EINUNDZWANZIG REPUTATION\n\nTotal Badges: 2\nMeetups besucht: 2\n\n\n📍 Besuchte Meetups:\n" +
            "  • Aschaffenburg (15.3.2025)\n  • München, DE (11.10.2026)\n\n✅ Proof of Attendance\n" +
            "Verifizierbar über die Einundzwanzig Meetup App\n\n---\nExportiert am 20.10.2026\n    "
        assertEquals(expected, ReputationShare.summaryText(listOf(a, b), "", DartDateTime.parse("2026-10-20T12:00:00.000")))
    }

    @Test
    fun `share text includes the npub line`() {
        val text = ReputationShare.summaryText(listOf(a), "npub1xyz", DartDateTime.parse("2026-10-20T12:00:00.000"))
        assertTrue(text.contains("Meetups besucht: 1\nNostr: npub1xyz\n\n"))
    }

    @Test
    fun `json export checksum matches the Dart vector`() {
        val json = ReputationShare.exportJson(
            all = listOf(a, b),
            npub = "npub1xyz",
            nickname = "Anon",
            telegram = "",
            twitter = "",
            exportedAt = DartDateTime.parse("2026-10-20T12:00:00.000"),
        )
        assertTrue(json, json.endsWith("  \"exported_at\": \"2026-10-20T12:00:00.000\",\n  \"checksum\": \"745487f0\"\n}"))
    }

    @Test
    fun `subjects are verbatim`() {
        assertEquals("Meine Einundzwanzig Meetup Reputation", ReputationShare.TEXT_SUBJECT)
        assertEquals("Einundzwanzig Reputation (v4, signiert)", ReputationShare.JSON_SUBJECT)
    }
}
