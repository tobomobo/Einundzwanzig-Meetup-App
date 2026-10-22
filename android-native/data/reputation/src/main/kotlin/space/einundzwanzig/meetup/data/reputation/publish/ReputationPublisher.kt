package space.einundzwanzig.meetup.data.reputation.publish

import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof

/**
 * Publishes the user's aggregate reputation as the replaceable kind-30078 event
 * `d = einundzwanzig-reputation` (Dart `ReputationPublisher.publish`, spec 04 §5.2).
 *
 * Inject the `@Singleton`. Publishes are serialized (one at a time, process-wide), so a burst of
 * triggers results in one relay round; the later ones see the throttle.
 */
interface ReputationPublisher {

    /**
     * Builds, signs and sends the event.
     *
     * Order (Dart): no signer → [PublishResult.NoKey]; no countable badge → [PublishResult.NoBadges];
     * not [force] and throttled (5 min, or same change hash for the same identity) →
     * [PublishResult.UpToDate]; then social stats (10 s timeout → empty), zap stats (10 s →
     * empty), humanity proof (each best-effort), sign, send to every active relay one after
     * the other (connect 5 s, send, linger 2 s, close; no `OK` parsing, PARITY).
     *
     * @param badges the wallet (`BadgeRepository.badges.value`); only `isNostrSigned &&
     *   !isOrganizer` badges are counted (spec 04 §8.8 FIX).
     * @param platformProofs the proofs to embed; `null` loads
     *   `PlatformProofRepository.getProofsForPublishing()` (spec 06 §14 R4 / spec 04 R-5 FIX: a
     *   publish never drops `platform_proofs` from relays). Pass an explicit map only to override.
     * @param force bypass the throttle (manual button, platform proof create/revoke, humanity).
     *
     * Never throws except for cancellation; failures are [PublishResult.Error].
     */
    suspend fun publish(
        badges: List<MeetupBadge>,
        platformProofs: Map<String, PlatformProof>? = null,
        force: Boolean = false,
    ): PublishResult

    /**
     * Fire-and-forget non-forced [publish] in the application scope (Dart
     * `publishInBackground`). The result is only logged. The module's startup task already
     * calls it (debounced) after the wallet loads, after `BadgeRepository.savedEvents` and after
     * `SigningService.signerReady`; features need not call it themselves.
     *
     * Skipped entirely in Amber (NIP-55) mode (spec 04 R-19): Amber shares one foreground signer
     * slot with user-started signs, so Amber users publish only through the explicit, forced
     * paths ([publish] from the QR screen button, proof create/revoke, humanity).
     */
    fun publishInBackground(badges: List<MeetupBadge>)
}

/**
 * Outcome of [ReputationPublisher.publish] (Dart `PublishResult`; same `success` semantics).
 * [germanMessage] is the Flutter text; the UI should localize by type instead (spec 04 §2.10).
 */
sealed interface PublishResult {
    val success: Boolean
    val relayCount: Int
    val eventId: String? get() = null

    /** The hard-coded German message of the Flutter app. */
    val germanMessage: String

    /** No signer (`!SigningService.canSign()`). */
    data object NoKey : PublishResult {
        override val success = false
        override val relayCount = 0
        override val germanMessage = "Kein Schlüssel vorhanden"
    }

    /** No countable badge. */
    data object NoBadges : PublishResult {
        override val success = false
        override val relayCount = 0
        override val germanMessage = "Keine Badges vorhanden"
    }

    /** Throttled (non-forced publish within 5 minutes or with unchanged counts). */
    data object UpToDate : PublishResult {
        override val success = true
        override val relayCount = 0
        override val germanMessage = "Reputation ist aktuell — kein Update nötig"
    }

    /** Sent to [relayCount] (> 0) relays. */
    data class Sent(override val relayCount: Int, override val eventId: String) : PublishResult {
        override val success: Boolean get() = true
        override val germanMessage: String get() = "Reputation auf $relayCount Relays aktualisiert"
    }

    /** Signed but no relay could be reached. */
    data class NoRelay(override val eventId: String) : PublishResult {
        override val success: Boolean get() = false
        override val relayCount: Int get() = 0
        override val germanMessage: String get() = "Konnte an keinen Relay senden"
    }

    /** Signing or building failed (signer cancelled, rejected, …). */
    data class Error(val cause: Throwable) : PublishResult {
        override val success: Boolean get() = false
        override val relayCount: Int get() = 0
        override val germanMessage: String get() = "Publish-Fehler: ${cause.message ?: cause}"
    }
}
