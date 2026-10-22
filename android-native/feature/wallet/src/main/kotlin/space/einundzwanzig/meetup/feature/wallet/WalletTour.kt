package space.einundzwanzig.meetup.feature.wallet

import space.einundzwanzig.meetup.core.ui.guide.GuideStep
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * Wallet spotlight tour (Dart `WalletTour`, `lib/tours/more_tours.dart`, spec 03 §2.1).
 * Persisted as `guide_completed_wallet` through `GuideTour.Wallet`.
 */
internal object WalletTour {
    const val FIRST_BADGE = "wallet.firstBadge"
    const val MAP = "wallet.map"
    const val VIEW = "wallet.view"
    const val SHARE = "wallet.share"

    /** Delay after the first frame before the tour starts. */
    const val START_DELAY_MS = 600L

    /** The first step waits at most 800 ms for the first card. */
    const val FIRST_STEP_WAIT_MS = 800L

    fun steps(): List<GuideStep> = listOf(
        GuideStep(
            targetId = FIRST_BADGE,
            title = L10nR.string.guideWalletBadgesTitle,
            body = L10nR.string.guideWalletBadgesBody,
            waitTimeoutMillis = FIRST_STEP_WAIT_MS,
        ),
        GuideStep(MAP, L10nR.string.guideWalletMapTitle, L10nR.string.guideWalletMapBody, scrollIntoView = false),
        GuideStep(VIEW, L10nR.string.guideWalletViewTitle, L10nR.string.guideWalletViewBody, scrollIntoView = false),
        GuideStep(SHARE, L10nR.string.guideWalletShareQrTitle, L10nR.string.guideWalletShareQrBody, scrollIntoView = false),
    )
}
