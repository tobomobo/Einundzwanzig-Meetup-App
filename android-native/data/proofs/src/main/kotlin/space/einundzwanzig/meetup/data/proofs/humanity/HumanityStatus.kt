package space.einundzwanzig.meetup.data.proofs.humanity

import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.data.proofs.internal.dartDayMonthYear
import java.time.ZoneId

/**
 * Proof-of-humanity state from the `humanity_*` prefs (Dart `HumanityStatus`, spec 06 §3.7).
 *
 * @property verified `false` while [needsReverification] is set, whatever `humanity_verified` says.
 * @property firstZapAt Unix seconds of the accepted receipt (misnomer kept, spec 06 §14 R7); 0 = none.
 * @property receiptEventId id of the accepted receipt, `""` if none.
 * @property lastCheckedAt Unix seconds of the last relay check; 0 = never.
 * @property needsReverification restored from a backup and not yet re-confirmed on relays.
 */
data class HumanityStatus(
    val verified: Boolean,
    val firstZapAt: Long = 0,
    val receiptEventId: String = "",
    val lastCheckedAt: Long = 0,
    val needsReverification: Boolean = false,
) {
    /** Dart `firstZapDateStr`: `""` if 0, else `d.M.yyyy` in [zone] without padding. */
    fun firstZapDateStr(zone: ZoneId = ZoneId.systemDefault()): String =
        if (firstZapAt == 0L) "" else dartDayMonthYear(firstZapAt, zone)

    /** Typed relative time of [lastCheckedAt] (Dart `lastCheckedStr` logic) at [nowMillis]. */
    fun lastChecked(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LastChecked {
        if (lastCheckedAt == 0L) return LastChecked.Never
        val diffMs = nowMillis - lastCheckedAt * 1000
        val minutes = diffMs / 60_000 // truncates toward zero like Dart inMinutes
        val hours = diffMs / 3_600_000
        val days = diffMs / 86_400_000
        return when {
            minutes < 1 -> LastChecked.JustNow
            hours < 1 -> LastChecked.MinutesAgo(minutes)
            days < 1 -> LastChecked.HoursAgo(hours)
            else -> LastChecked.OnDate(dartDayMonthYear(lastCheckedAt, zone))
        }
    }

    /** Dart `lastCheckedStr` in German ("Nie", "Gerade eben", "Vor N Min.", "Vor N Std.", `d.M.yyyy`). */
    fun lastCheckedStr(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        lastChecked(nowMillis, zone).german

    /**
     * Backup v5 entry (Dart `BackupService` export):
     * `{"verified","first_zap_at","receipt_event_id","checked_at"}`. `verified` is [verified],
     * so it is `false` while re-verification is pending.
     */
    fun toBackupJson(): JsonObject = jsonObjectOf(
        "verified" to verified,
        "first_zap_at" to firstZapAt,
        "receipt_event_id" to receiptEventId,
        "checked_at" to lastCheckedAt,
    )
}

/** Relative "last check" time of [HumanityStatus.lastChecked]. */
sealed interface LastChecked {
    /** The German text of the Flutter app. */
    val german: String

    data object Never : LastChecked {
        override val german = "Nie"
    }

    /** Less than a minute ago (also for timestamps in the future). */
    data object JustNow : LastChecked {
        override val german = "Gerade eben"
    }

    data class MinutesAgo(val minutes: Long) : LastChecked {
        override val german: String get() = "Vor $minutes Min."
    }

    data class HoursAgo(val hours: Long) : LastChecked {
        override val german: String get() = "Vor $hours Std."
    }

    /** One day or more: `d.M.yyyy` in local time without padding. */
    data class OnDate(val date: String) : LastChecked {
        override val german: String get() = date
    }
}

/** Outcome kind of [HumanityProofRepository.checkForZaps]. */
enum class HumanityOutcome {
    /** A provider-confirmed receipt was found ("Lightning-Zahlung gefunden — du bist verifiziert!"). */
    FOUND,

    /** Nothing found ("Kein Zap-Receipt gefunden. …"). */
    NOT_FOUND,

    /** No npub ("Kein Schlüssel vorhanden"). */
    NO_KEY,

    /** The npub does not decode ("Ungültiger npub"). */
    INVALID_NPUB,

    /**
     * Unexpected failure outside the per-relay loop (e.g. relay config or prefs). Flutter left the
     * screen spinning forever (spec 06 §14 R23); the native port reports it instead.
     */
    ERROR,
}

/** Dart `HumanityCheckResult`. */
data class HumanityCheckResult(
    val found: Boolean,
    val outcome: HumanityOutcome,
    val receiptEventId: String? = null,
    val zapTimestamp: Long? = null,
) {
    /** The German service message of the Flutter app. */
    val message: String
        get() = when (outcome) {
            HumanityOutcome.FOUND -> "Lightning-Zahlung gefunden — du bist verifiziert!"
            HumanityOutcome.NO_KEY -> "Kein Schlüssel vorhanden"
            HumanityOutcome.INVALID_NPUB -> "Ungültiger npub"
            HumanityOutcome.NOT_FOUND, HumanityOutcome.ERROR ->
                "Kein Zap-Receipt gefunden. Hast du schon einmal jemanden auf Nostr gezappt? " +
                    "Zappe irgendjemanden (egal wieviel Sats) und komm zurück."
        }
}
