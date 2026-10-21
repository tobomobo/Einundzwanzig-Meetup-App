package space.einundzwanzig.meetup.data.proofs.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.data.badges.text.DartString
import javax.inject.Inject
import javax.inject.Singleton

/** Dart `VerifyLevel` (stage 3 … 0). */
enum class VerifyLevel { FULL, PARTIAL, SIGNATURE_ONLY, INVALID }

/** Typed form of the hard-coded German verification messages (spec 06 §2.6). */
sealed interface VerifyMessage {
    /** The exact German text of the Flutter app. */
    val german: String

    data object BadFormat : VerifyMessage {
        override val german = "Ungültiges Format. Erwartet: 21rep::npub::plattform::username::sig=..."
    }

    data object BadSignature : VerifyMessage {
        override val german = "WARNUNG: Signatur ungültig! Dieser Verify-String wurde manipuliert."
    }

    data class UsernameMismatch(val claimed: String, val expected: String) : VerifyMessage {
        override val german: String
            get() = "WARNUNG: Username stimmt nicht überein! String sagt \"$claimed\", Account ist \"$expected\"."
    }

    data object NoReputation : VerifyMessage {
        override val german = "Signatur gültig ✓ — Aber keine Reputation auf Relays gefunden."
    }

    data object Full : VerifyMessage {
        override val german = "Identität und Reputation vollständig bestätigt ✓"
    }

    data class PartialWithProof(val bound: Int, val total: Int) : VerifyMessage {
        override val german: String get() = "Reputation teilweise bestätigt — $bound von $total Badges gebunden."
    }

    data object PartialNoProof : VerifyMessage {
        override val german = "Reputation verifiziert, aber Plattform-Proof nicht im Event gefunden."
    }

    data object SignatureOnlyNoBindings : VerifyMessage {
        override val german = "Signatur gültig, aber keine gebundenen Badges."
    }

    data object NpubNoReputation : VerifyMessage {
        override val german = "Keine Reputation auf Relays gefunden für diesen npub."
    }

    data object NpubWithBindings : VerifyMessage {
        override val german = "Reputation gefunden — aber kein Plattform-Proof. Identitäts-Verknüpfung nicht bestätigt."
    }

    data object NpubNoBindings : VerifyMessage {
        override val german = "Reputation gefunden, aber keine gebundenen Badges."
    }

    data class Error(val detail: String) : VerifyMessage {
        override val german: String get() = "Verifikations-Fehler: $detail"
    }
}

/**
 * The fields of a fetched kind-30078 reputation event the verification needs. `:data:reputation`
 * maps its `ReputationEvent` to this.
 *
 * @property platformProofs `content.platform_proofs` as a raw map (values are usually objects).
 */
data class ReputationFacts(
    val boundBadges: Int,
    val totalBadges: Int,
    val platformProofs: Map<String, JsonElement>,
)

/**
 * Dart `ProofVerifyResult`. [R] is the reputation model of the caller (`ReputationEvent` in
 * `:data:reputation`), passed through untouched.
 */
data class ProofVerifyResult<out R>(
    val level: VerifyLevel,
    val message: VerifyMessage,
    val npub: String? = null,
    val platform: String? = null,
    val claimedUsername: String? = null,
    val reputation: R? = null,
    val proofInEvent: Boolean = false,
)

/**
 * Dart `PlatformProofService.verifyProofString` (spec 06 §8.2, `PlatformProofVerifier` of §13)
 * without the relay fetch. Injectable `@Singleton`.
 *
 * **Seam:** fetching the reputation event (`ReputationPublisher.fetchByNpub`) lives in
 * `:data:reputation`, which depends on this module (the spec's constructor dependency on a
 * `ReputationFetcher` would be a module cycle). That module injects this class and wraps it once:
 *
 * ```
 * verifier.verify(input, expectedUsername,
 *     fetchByNpub = { npub -> reputationFetcher.fetchByNpub(npub) },
 *     facts = { rep -> ReputationFacts(rep.boundBadges, rep.totalBadges, rep.platformProofs) })
 * ```
 *
 * and exposes the result to the verify screen and the QR scanner.
 */
@Singleton
class PlatformProofVerifier @Inject constructor(
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /**
     * Verifies a `21rep::` verify string or a bare npub. Never throws except for cancellation:
     * any exception (also from [fetchByNpub]) gives [VerifyLevel.INVALID] with [VerifyMessage.Error]
     * (Dart wording `"Verifikations-Fehler: $e"`). Main-safe: the Dart case-mapping table is
     * loaded on the injected `@DefaultDispatcher`; [fetchByNpub] runs in the caller's context.
     */
    suspend fun <R : Any> verify(
        input: String,
        expectedUsername: String? = null,
        fetchByNpub: suspend (npub: String) -> R?,
        facts: (R) -> ReputationFacts,
    ): ProofVerifyResult<R> = try {
        withContext(dispatcher) { DartString.warmUp() }
        verifyOrThrow(input, expectedUsername, fetchByNpub, facts)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ProofVerifyResult(level = VerifyLevel.INVALID, message = VerifyMessage.Error(e.message ?: e.toString()))
    }

    private suspend fun <R : Any> verifyOrThrow(
        input: String,
        expectedUsername: String?,
        fetchByNpub: suspend (String) -> R?,
        facts: (R) -> ReputationFacts,
    ): ProofVerifyResult<R> {
        val parsed = VerifyStringCodec.parse(input)
        if (parsed == null) {
            val trimmed = input.dartTrim()
            if (trimmed.startsWith("npub1")) return verifyByNpub(trimmed, fetchByNpub, facts)
            return ProofVerifyResult(level = VerifyLevel.INVALID, message = VerifyMessage.BadFormat)
        }
        if (!VerifyStringCodec.isWellFormed(parsed)) {
            return ProofVerifyResult(level = VerifyLevel.INVALID, message = VerifyMessage.BadSignature)
        }
        if (!expectedUsername.isNullOrEmpty() &&
            DartString.lower(parsed.username) != DartString.lower(expectedUsername)
        ) {
            return ProofVerifyResult(
                level = VerifyLevel.INVALID,
                message = VerifyMessage.UsernameMismatch(parsed.username, expectedUsername),
                npub = parsed.npub,
                platform = parsed.platform,
                claimedUsername = parsed.username,
            )
        }
        val rep = fetchByNpub(parsed.npub)
            ?: return ProofVerifyResult(
                level = VerifyLevel.SIGNATURE_ONLY,
                message = VerifyMessage.NoReputation,
                npub = parsed.npub,
                platform = parsed.platform,
                claimedUsername = parsed.username,
            )
        val f = facts(rep)
        val eventProof = f.platformProofs[parsed.platform] as? JsonObject
        // Exact, case-sensitive comparison (Dart `eventProof['username'] == parsed.username`).
        val proofInEvent = eventProof?.get("username")
            ?.let { it is JsonPrimitive && it.isString && it.content == parsed.username } == true
        val hasBindings = f.boundBadges > 0
        val allBound = f.boundBadges == f.totalBadges && f.totalBadges > 0
        val (level, message) = when {
            proofInEvent && hasBindings && allBound -> VerifyLevel.FULL to VerifyMessage.Full
            proofInEvent && hasBindings -> VerifyLevel.PARTIAL to VerifyMessage.PartialWithProof(f.boundBadges, f.totalBadges)
            hasBindings -> VerifyLevel.PARTIAL to VerifyMessage.PartialNoProof
            else -> VerifyLevel.SIGNATURE_ONLY to VerifyMessage.SignatureOnlyNoBindings
        }
        return ProofVerifyResult(
            level = level,
            message = message,
            npub = parsed.npub,
            platform = parsed.platform,
            claimedUsername = parsed.username,
            reputation = rep,
            proofInEvent = proofInEvent,
        )
    }

    private suspend fun <R : Any> verifyByNpub(
        npub: String,
        fetchByNpub: suspend (String) -> R?,
        facts: (R) -> ReputationFacts,
    ): ProofVerifyResult<R> {
        val rep = fetchByNpub(npub)
            ?: return ProofVerifyResult(level = VerifyLevel.SIGNATURE_ONLY, message = VerifyMessage.NpubNoReputation, npub = npub)
        val hasBindings = facts(rep).boundBadges > 0
        return ProofVerifyResult(
            level = if (hasBindings) VerifyLevel.PARTIAL else VerifyLevel.SIGNATURE_ONLY,
            message = if (hasBindings) VerifyMessage.NpubWithBindings else VerifyMessage.NpubNoBindings,
            npub = npub,
            reputation = rep,
            proofInEvent = false,
        )
    }
}
