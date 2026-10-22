package space.einundzwanzig.meetup.feature.reputation.scan

import space.einundzwanzig.meetup.data.badges.review.BadgeReviewDemo
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrCodec

/** What the reputation scanner does with a decoded QR text (spec 04 §2.3 `onDetect`). */
internal sealed interface ScanHit {
    /** App-review demo payload: show the demo dialog, then scan again. */
    data object Demo : ScanHit

    /** A `21v3:`/`21v2:`/`21:` code: replace the scanner with the result screen. */
    data class Reputation(val code: String) : ScanHit

    /** Anything else: ignored silently. */
    data object Ignore : ScanHit
}

/** Outcome of a gallery image (spec 04 §2.3 "Gallery"). */
internal sealed interface GalleryOutcome {
    data class Hit(val hit: ScanHit) : GalleryOutcome

    /** `qrNoCodeInImage` (orange). */
    data object NoCode : GalleryOutcome

    /** `qrNotEinundzwanzig` (orange). */
    data object NotEinundzwanzig : GalleryOutcome

    /** `qrProcessError(e)` (red). */
    data class Failed(val error: Throwable) : GalleryOutcome
}

/** Pure decision rules of the reputation scanner. */
internal object ReputationScanLogic {

    /** Camera detection: demo first, then the three prefixes, else ignore. */
    fun classify(raw: String): ScanHit = when {
        BadgeReviewDemo.isReviewDemoPayload(raw) -> ScanHit.Demo
        ReputationQrCodec.isReputationCode(raw) -> ScanHit.Reputation(raw)
        else -> ScanHit.Ignore
    }

    /** Gallery: the first code that is the demo or a reputation code wins (Flutter order). */
    fun classifyGallery(values: List<String>): GalleryOutcome {
        if (values.isEmpty()) return GalleryOutcome.NoCode
        for (value in values) {
            val hit = classify(value)
            if (hit != ScanHit.Ignore) return GalleryOutcome.Hit(hit)
        }
        return GalleryOutcome.NotEinundzwanzig
    }
}
