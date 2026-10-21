package space.einundzwanzig.meetup.data.trust.score

/**
 * Bootstrap phase of the trust network (Dart `BootstrapPhase`, spec 04 §3.2). Determined by
 * the number of distinct non-empty `signerNpub`s over the user's trusted badges:
 * ≥ 6 [STABIL], ≥ 2 [WACHSTUM], else [KEIMPHASE].
 */
enum class BootstrapPhase {
    /** A single signer in the network. */
    KEIMPHASE,

    /** Two to five active signers. */
    WACHSTUM,

    /** Six or more active signers. */
    STABIL,
}

/**
 * Promotion thresholds of one [BootstrapPhase] (Dart `PhaseThresholds`).
 *
 * @property name German phase name (`Keimphase`, `Wachstum`, `Stabil`).
 * @property emoji Phase glyph (`◇`, `◈`, `◉`).
 * @property promotionScore minimum `totalScore`; the promotion check compares
 *   `totalScore.round()` with `promotionScore.round()`.
 */
data class PhaseThresholds(
    val name: String,
    val emoji: String,
    val minBadges: Int,
    val minUniqueMeetups: Int,
    val minUniqueSigners: Int,
    val minAccountAgeDays: Int,
    val promotionScore: Double,
)

/** Constants of Trust Score v3 (Dart `TrustConfig`, spec 04 §3.2). Values are byte-for-byte Flutter's. */
object TrustConfig {

    /** Thresholds per phase, in enum order. */
    val phases: Map<BootstrapPhase, PhaseThresholds> = linkedMapOf(
        BootstrapPhase.KEIMPHASE to PhaseThresholds("Keimphase", "◇", 3, 2, 1, 14, 5.0),
        BootstrapPhase.WACHSTUM to PhaseThresholds("Wachstum", "◈", 4, 3, 2, 30, 10.0),
        BootstrapPhase.STABIL to PhaseThresholds("Stabil", "◉", 5, 3, 2, 60, 15.0),
    )

    /** Thresholds of [phase] (never `null`: every phase is configured). */
    fun thresholds(phase: BootstrapPhase): PhaseThresholds = phases.getValue(phase)

    const val WEIGHT_DIVERSITY: Double = 1.5
    const val WEIGHT_MATURITY: Double = 1.0

    /** Declared in Dart but unused by the calculation (kept for parity). */
    const val WEIGHT_DECAY: Double = 1.0
    const val WEIGHT_QUALITY: Double = 1.2

    /** Time-decay half life in weeks (~6 months). */
    const val HALF_LIFE_WEEKS: Double = 26.0

    /** Frequency cap: at most this many badges per week count. */
    const val MAX_BADGES_PER_WEEK: Int = 2
}
