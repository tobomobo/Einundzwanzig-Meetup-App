package space.einundzwanzig.meetup.data.trust.level

/**
 * The five trust levels with their internal wire values (spec 04 §2.0, §3.4, §8.4).
 *
 * Two scales use the same values (spec 04 R-3, never unify them):
 * - [forLocalScore]: the local Trust Score (`TrustScore.level`, QR field `lv`), 3/10/20/40.
 * - [forBasicScore]: the relay basic score (kind 30078 `stats.level`), >0/2/5/8.
 *
 * @property wire the internal value used in payloads (`NEU`, `STARTER`, …), never translated.
 * @property labelKey ARB key of the localized label in `:core:l10n` (`levelNew`, …).
 */
enum class TrustLevel(val wire: String, val labelKey: String) {
    NEU("NEU", "levelNew"),
    STARTER("STARTER", "levelStarter"),
    AKTIV("AKTIV", "levelActive"),
    ETABLIERT("ETABLIERT", "levelEstablished"),
    VETERAN("VETERAN", "levelVeteran"),
    ;

    companion object {
        /** The level for a wire value, or `null` for anything else (e.g. `""` or an unknown value from a QR). */
        fun fromWire(value: String): TrustLevel? = entries.firstOrNull { it.wire == value }

        /** Local scale on `totalScore`: ≥40 VETERAN, ≥20 ETABLIERT, ≥10 AKTIV, ≥3 STARTER, else NEU. */
        fun forLocalScore(totalScore: Double): TrustLevel = when {
            totalScore >= 40 -> VETERAN
            totalScore >= 20 -> ETABLIERT
            totalScore >= 10 -> AKTIV
            totalScore >= 3 -> STARTER
            else -> NEU
        }

        /** Relay scale on the 0..10 basic score: ≥8 VETERAN, ≥5 ETABLIERT, ≥2 AKTIV, >0 STARTER, else NEU. */
        fun forBasicScore(score: Double): TrustLevel = when {
            score >= 8.0 -> VETERAN
            score >= 5.0 -> ETABLIERT
            score >= 2.0 -> AKTIV
            score > 0 -> STARTER
            else -> NEU
        }
    }
}

/**
 * Icon of a level, named after the Flutter `Icons.*` constant. Features map it to their
 * Material icon (data modules carry no UI).
 */
enum class TrustLevelIcon {
    /** `Icons.bolt` */
    BOLT,

    /** `Icons.shield` */
    SHIELD,

    /** `Icons.local_fire_department` */
    LOCAL_FIRE_DEPARTMENT,

    /** `Icons.eco` */
    ECO,

    /** `Icons.fiber_new` */
    FIBER_NEW,
}

/**
 * Semantic colour of a level. Features map it to theme colours (`MaterialTheme.extendedColors`:
 * [SUCCESS_GREEN] → `success`, [COMMUNITY_GOLD] → `community`, [BITCOIN_ORANGE] → `bitcoin`,
 * [NEUTRAL_GREY] → a neutral/outline tone, or `textTertiary` on home); never hard-code ARGB.
 */
enum class TrustLevelColor {
    /** Flutter `Colors.amber` (VETERAN). */
    AMBER,

    /** Flutter `Colors.green` (ETABLIERT). */
    SUCCESS_GREEN,

    /** Flutter gold `cCyan` (AKTIV). */
    COMMUNITY_GOLD,

    /** Flutter Bitcoin orange `cOrange` (STARTER). */
    BITCOIN_ORANGE,

    /** Flutter `Colors.grey` (NEU, `""`, unknown) on the QR card and scanner. */
    NEUTRAL_GREY,
}

/**
 * Display style of a level value (spec 04 §2.0, shared by the QR card and the scanner result).
 *
 * Home (`home_screen.dart` `_levelColor`/`_levelIcon`) uses the same icons and colours but
 * falls back to `textTertiary` (Flutter `cTextTertiary` 0xFF868A94) instead of grey for
 * NEU/unknown and while no score is loaded yet; map [TrustLevelColor.NEUTRAL_GREY] accordingly there.
 *
 * @property color semantic colour; features map it to theme colours.
 * @property argb Flutter reference value only (`Colors.amber`, `Colors.green`, gold `cCyan`,
 *   Bitcoin orange `cOrange`, `Colors.grey`); do not render it directly.
 */
data class TrustLevelStyle(val icon: TrustLevelIcon, val color: TrustLevelColor, val argb: Long) {
    companion object {
        val VETERAN = TrustLevelStyle(TrustLevelIcon.BOLT, TrustLevelColor.AMBER, 0xFFFFC107)
        val ETABLIERT = TrustLevelStyle(TrustLevelIcon.SHIELD, TrustLevelColor.SUCCESS_GREEN, 0xFF4CAF50)
        val AKTIV = TrustLevelStyle(TrustLevelIcon.LOCAL_FIRE_DEPARTMENT, TrustLevelColor.COMMUNITY_GOLD, 0xFFD4A017)
        val STARTER = TrustLevelStyle(TrustLevelIcon.ECO, TrustLevelColor.BITCOIN_ORANGE, 0xFFF7931A)
        val OTHER = TrustLevelStyle(TrustLevelIcon.FIBER_NEW, TrustLevelColor.NEUTRAL_GREY, 0xFF9E9E9E)

        /** Style for a raw level value; `NEU`, `""` and unknown values get [OTHER]. */
        fun forWire(value: String): TrustLevelStyle = when (value) {
            "VETERAN" -> VETERAN
            "ETABLIERT" -> ETABLIERT
            "AKTIV" -> AKTIV
            "STARTER" -> STARTER
            else -> OTHER
        }

        /**
         * ARB label key for a raw level value (Dart `localizedLevel`), or `null` when the value
         * is unknown and the UI shows it verbatim.
         */
        fun labelKeyForWire(value: String): String? = TrustLevel.fromWire(value)?.labelKey
    }
}
