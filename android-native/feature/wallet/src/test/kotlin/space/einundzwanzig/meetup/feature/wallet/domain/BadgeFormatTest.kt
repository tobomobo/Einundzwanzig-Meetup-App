package space.einundzwanzig.meetup.feature.wallet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.feature.wallet.badge
import java.time.ZoneOffset

class BadgeFormatTest {

    @Test
    fun `wallet block vectors`() {
        val cases = mapOf(-5L to "---", 0L to "---", 7L to "7", 999L to "999", 1000L to "1.000", 875000L to "875.000", 1234567L to "1.234.567")
        cases.forEach { (h, expected) -> assertEquals("$h", expected, BadgeFormat.blockWallet(h)) }
    }

    @Test
    fun `details and share-card block`() {
        assertEquals("unbekannt", BadgeFormat.blockDetails(0, "unbekannt"))
        assertEquals("875.000", BadgeFormat.blockDetails(875000, "unbekannt"))
        assertEquals("", BadgeFormat.blockShareCard(0))
        assertEquals("1.000", BadgeFormat.blockShareCard(1000))
    }

    @Test
    fun `ago label vectors`() {
        val cases = mapOf(
            -3L to AgoLabel.Today, 0L to AgoLabel.Today, 1L to AgoLabel.Yesterday,
            2L to AgoLabel.Days(2), 29L to AgoLabel.Days(29),
            30L to AgoLabel.Months(1), 59L to AgoLabel.Months(1), 60L to AgoLabel.Months(2), 359L to AgoLabel.Months(11),
            360L to AgoLabel.Years(0), 364L to AgoLabel.Years(0), 365L to AgoLabel.Years(1), 729L to AgoLabel.Years(1), 730L to AgoLabel.Years(2),
        )
        cases.forEach { (days, expected) -> assertEquals("$days", expected, BadgeFormat.agoForDays(days)) }
    }

    @Test
    fun `ago label truncates like Duration inDays`() {
        val then = DartDateTime.parse("2026-10-20T00:00:00.000Z")
        val almostDay = then.millisecondsSinceEpoch + 23 * 3_600_000L + 59 * 60_000L
        assertEquals(AgoLabel.Today, BadgeFormat.agoLabel(almostDay, then))
        assertEquals(AgoLabel.Yesterday, BadgeFormat.agoLabel(then.millisecondsSinceEpoch + 86_400_000L, then))
    }

    @Test
    fun `dates use the own fields`() {
        val d = DartDateTime.parse("2025-03-05T00:00:00.000Z")
        assertEquals("5.3.2025", BadgeFormat.dateShort(d))
        assertEquals("05.03.2025", BadgeFormat.datePadded(d))
        assertEquals("Mär 2025", BadgeFormat.monthYearDe(d))
    }

    @Test
    fun `scan time and former time formats`() {
        assertEquals("14.11.2023  22:13", BadgeFormat.scanTime(1_700_000_000, ZoneOffset.UTC))
        assertEquals("14.11.2023 22:13", BadgeFormat.formerTime(DartDateTime.fromMillisecondsSinceEpoch(1_700_000_000_000, isUtc = true), ZoneOffset.UTC))
    }

    @Test
    fun `npub and sig id shortening`() {
        assertEquals("—", BadgeFormat.shortNpubDetails(""))
        assertEquals("npub1short", BadgeFormat.shortNpubDetails("npub1short"))
        val npub = "npub1" + "a".repeat(58)
        assertEquals(npub.take(12) + "…" + npub.takeLast(8), BadgeFormat.shortNpubDetails(npub))
        assertEquals("abc", BadgeFormat.shortSigId("abc"))
        assertEquals("c".repeat(12) + "…" + "c".repeat(8), BadgeFormat.shortSigId("c".repeat(64)))
    }

    @Test
    fun `collection subtitle for one and several badges`() {
        val one = listOf(badge("1", date = "2025-03-15T19:00:00.000"))
        val single = BadgeFormat.collectionSubtitle(one, System.currentTimeMillis())
        assertEquals("Mär 2025", single.sinceMonthYear)
        assertNull(single.lastVisit)

        val newest = badge("2", date = "2026-10-18T12:00:00.000Z")
        val oldest = badge("3", date = "2024-01-02T12:00:00.000Z")
        val now = DartDateTime.parse("2026-10-20T12:00:00.000Z").millisecondsSinceEpoch
        val several = BadgeFormat.collectionSubtitle(listOf(newest, oldest), now)
        assertEquals("Jan 2024", several.sinceMonthYear)
        assertEquals(AgoLabel.Days(2), several.lastVisit)
    }

    @Test
    fun `share image text`() {
        val b = badge("1", name = "Aschaffenburg", date = "2025-03-05T19:00:00.000")
        assertEquals("Aschaffenburg · 05.03.2025\nTag", BadgeFormat.shareImageText(b, "Tag"))
    }
}
