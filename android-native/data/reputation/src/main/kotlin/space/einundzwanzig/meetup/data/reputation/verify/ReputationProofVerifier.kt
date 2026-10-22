package space.einundzwanzig.meetup.data.reputation.verify

import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofVerifier
import space.einundzwanzig.meetup.data.proofs.platform.ProofVerifyResult
import space.einundzwanzig.meetup.data.reputation.event.ReputationEvent
import space.einundzwanzig.meetup.data.reputation.fetch.ReputationFetcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dart `PlatformProofService.verifyProofString(input, expectedUsername)` (spec 04 §8.10,
 * spec 06 §8.2): the `:data:proofs` [PlatformProofVerifier] decision table wired to
 * [ReputationFetcher.fetchByNpub]. Used by the verify screen ("REPUTATION PRÜFEN").
 *
 * The verify-string signature is only format-checked (spec 04 S-9): `FULL` requires the proof
 * to be present in the author's signed kind-30078 event (`proofInEvent`); never label a result
 * as cryptographically verified otherwise.
 */
@Singleton
class ReputationProofVerifier @Inject constructor(
    private val verifier: PlatformProofVerifier,
    private val fetcher: ReputationFetcher,
) {
    /**
     * Verifies a `21rep::npub::platform::username::sig=…` string or a bare `npub1…` (not
     * lower-cased, no `nostr:` stripping, as in Dart). Never throws except for cancellation.
     */
    suspend fun verifyProofString(input: String, expectedUsername: String? = null): ProofVerifyResult<ReputationEvent> =
        verifier.verify(
            input = input,
            expectedUsername = expectedUsername,
            fetchByNpub = fetcher::fetchByNpub,
            facts = ReputationEvent::toFacts,
        )
}
