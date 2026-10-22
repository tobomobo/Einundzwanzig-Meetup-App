package space.einundzwanzig.meetup.feature.wallet.domain

import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.data.badges.model.BadgeProofs
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge

/** Reputation share text and JSON export of the wallet share sheet (spec 03 §2.1.2, §8.7, §8.8). */
internal object ReputationShare {
    const val TEXT_SUBJECT = "Meine Einundzwanzig Meetup Reputation"
    const val JSON_SUBJECT = "Einundzwanzig Reputation (v4, signiert)"

    /** Exact hard-coded German summary (§8.7), including the trailing `"\n    "`. */
    fun summaryText(all: List<MeetupBadge>, npub: String, today: DartDateTime): String =
        "🏆 MEINE EINUNDZWANZIG REPUTATION\n\n" +
            "Total Badges: ${all.size}\n" +
            "Meetups besucht: ${all.map { it.meetupName }.toSet().size}\n" +
            (if (npub.isNotEmpty()) "Nostr: $npub" else "") + "\n\n" +
            "📍 Besuchte Meetups:\n" +
            all.joinToString("\n") { "  • ${it.meetupName} (${it.date.day}.${it.date.month}.${it.date.year})" } + "\n\n" +
            "✅ Proof of Attendance\n" +
            "Verifizierbar über die Einundzwanzig Meetup App\n\n" +
            "---\n" +
            "Exportiert am ${today.day}.${today.month}.${today.year}\n    "

    /** JSON v4.0 export (§8.8), delegated to the badge model domain. */
    fun exportJson(
        all: List<MeetupBadge>,
        npub: String,
        nickname: String,
        telegram: String,
        twitter: String,
        exportedAt: DartDateTime,
    ): String = BadgeProofs.exportBadgesForReputation(all, npub, exportedAt, nickname, telegram, twitter)
}
