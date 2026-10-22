package space.einundzwanzig.meetup.feature.wallet.domain

import androidx.compose.runtime.Immutable
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** "vor …" label of a collection card (spec 03 §8.4); the UI maps it to the `walletAgo*` strings. */
@Immutable
internal sealed interface AgoLabel {
    data object Today : AgoLabel
    data object Yesterday : AgoLabel
    data class Days(val days: Long) : AgoLabel
    data class Months(val months: Long) : AgoLabel
    data class Years(val years: Long) : AgoLabel
}

/**
 * Collection-card subtitle parts (spec 03 §8.4): `walletSince(sinceMonthYear)`, plus
 * ` · walletLastVisit(ago)` when the group has more than one badge.
 */
@Immutable
internal data class CollectionSubtitle(val sinceMonthYear: String, val lastVisit: AgoLabel?)

/** All display formatters of the wallet domain (spec 03 §8.3, §8.4). */
internal object BadgeFormat {
    /** Hard-coded German month abbreviations, used in every locale (Flutter parity). */
    val MONTHS_DE = listOf("Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez")

    /** Dot grouping `1.234.567` of a positive number. */
    private fun group(h: Long): String {
        val s = h.toString()
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append('.')
            sb.append(s[i])
        }
        return sb.toString()
    }

    /** Wallet card block: `---` for ≤ 0, otherwise dot-grouped. */
    fun blockWallet(h: Long): String = if (h <= 0) "---" else group(h)

    /** Details block: [unknown] for 0, otherwise grouped (negative values keep their sign). */
    fun blockDetails(h: Long, unknown: String): String = when {
        h == 0L -> unknown
        h < 0 -> "-" + group(-h)
        else -> group(h)
    }

    /** Share-card block: `""` for ≤ 0 (row hidden). */
    fun blockShareCard(h: Long): String = if (h <= 0) "" else group(h)

    /** `d.M.yyyy` from the date's own fields. */
    fun dateShort(d: DartDateTime): String = "${d.day}.${d.month}.${d.year}"

    /** `dd.MM.yyyy` from the date's own fields. */
    fun datePadded(d: DartDateTime): String = "${pad(d.day)}.${pad(d.month)}.${d.year}"

    /** Scan time `dd.MM.yyyy  HH:mm` (two spaces) in device local time. */
    fun scanTime(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), zone)
        return "${pad(t.dayOfMonth)}.${pad(t.monthValue)}.${t.year}  ${pad(t.hour)}:${pad(t.minute)}"
    }

    /** Archive entry time `dd.MM.yyyy HH:mm` (one space) in device local time. */
    fun formerTime(at: DartDateTime, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = LocalDateTime.ofInstant(at.toInstant(), zone)
        return "${pad(t.dayOfMonth)}.${pad(t.monthValue)}.${t.year} ${pad(t.hour)}:${pad(t.minute)}"
    }

    /** Details npub: `—` when empty, unchanged below 20 chars, else 12 + `…` + 8. */
    fun shortNpubDetails(npub: String): String = when {
        npub.isEmpty() -> "—"
        npub.length < 20 -> npub
        else -> npub.take(12) + "…" + npub.takeLast(8)
    }

    /** Nostr event id: 12 + `…` + 8; shorter ids (< 20) unchanged (Flutter would throw). */
    fun shortSigId(sigId: String): String = if (sigId.length < 20) sigId else sigId.take(12) + "…" + sigId.takeLast(8)

    /** `Mär 2025` with the hard-coded German month names. */
    fun monthYearDe(d: DartDateTime): String = MONTHS_DE[d.month - 1] + " " + d.year

    /** Dart `Duration.inDays` (truncating) of `now - then`, then the §8.4 buckets (360–364 → years(0), kept). */
    fun agoLabel(nowMillis: Long, then: DartDateTime): AgoLabel {
        val days = (nowMillis * 1000 - then.microsecondsSinceEpoch) / DAY_MICROS
        return agoForDays(days)
    }

    fun agoForDays(days: Long): AgoLabel = when {
        days <= 0 -> AgoLabel.Today
        days == 1L -> AgoLabel.Yesterday
        days < 30 -> AgoLabel.Days(days)
        days / 30 < 12 -> AgoLabel.Months(days / 30)
        else -> AgoLabel.Years(days / 365)
    }

    /** Subtitle of a group listed newest first ([groupDesc] non-empty). */
    fun collectionSubtitle(groupDesc: List<MeetupBadge>, nowMillis: Long): CollectionSubtitle {
        val since = monthYearDe(groupDesc.last().date)
        val last = if (groupDesc.size == 1) null else agoLabel(nowMillis, groupDesc.first().date)
        return CollectionSubtitle(since, last)
    }

    /** Text of the details/share image: `"<name> · dd.MM.yyyy\n<tagline>"`. */
    fun shareImageText(badge: MeetupBadge, tagline: String): String =
        "${badge.meetupName} · ${datePadded(badge.date)}\n$tagline"

    private const val DAY_MICROS = 86_400_000_000L

    private fun pad(v: Int): String = v.toString().padStart(2, '0')
}
