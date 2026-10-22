package space.einundzwanzig.meetup.feature.proofs.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityCheckResult
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityOutcome
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityProofRepository
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityStatus
import space.einundzwanzig.meetup.data.proofs.platform.PlatformCatalog
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofRepository
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateResult
import space.einundzwanzig.meetup.feature.proofs.republish.ReputationRepublisher

internal class FakeRepublisher : ReputationRepublisher {
    var calls = 0
    override fun republishForced() {
        calls++
    }
}

internal class FakeTimeSource(var now: Long = 1_700_000_000_000) : TimeSource {
    override fun nowMillis(): Long = now
}

/** In-memory [PlatformProofRepository]; [proofs] stays silent until [loaded] is set. */
internal class FakePlatformProofRepository(initial: List<PlatformProof>? = emptyList()) : PlatformProofRepository {
    val stored = MutableStateFlow(initial)
    val createCalls = mutableListOf<Triple<String, String, String?>>()
    val removeCalls = mutableListOf<String>()
    var createResult: ProofCreateResult? = null
    var createGate: CompletableDeferred<Unit>? = null
    var removeSucceeds = true

    override val proofs: Flow<List<PlatformProof>> = stored.filterNotNull()

    override suspend fun getSavedProofs(): List<PlatformProof> = stored.value.orEmpty()

    override suspend fun createProof(platformId: String, username: String, customPlatformName: String?): ProofCreateResult {
        createCalls += Triple(platformId, username, customPlatformName)
        createGate?.await()
        val result = createResult ?: run {
            val id = customPlatformName?.let { PlatformCatalog.customPlatformId(it) } ?: platformId
            ProofCreateResult.Success("21rep::x", PlatformProof(id, username, "a".repeat(128), 1_700_000_000))
        }
        if (result is ProofCreateResult.Success) {
            stored.value = stored.value.orEmpty().filter { it.platform != result.proof.platform } + result.proof
        }
        return result
    }

    override suspend fun removeProof(platformId: String): Boolean {
        removeCalls += platformId
        if (!removeSucceeds) return false
        stored.value = stored.value.orEmpty().filter { it.platform != platformId }
        return true
    }

    override suspend fun getProofsForPublishing(): LinkedHashMap<String, PlatformProof> =
        LinkedHashMap(stored.value.orEmpty().associateBy { it.platform })

    override suspend fun replaceAllRaw(validated: List<JsonElement>): Boolean = true
    override suspend fun unionRaw(incoming: List<JsonElement>): Boolean = true
    override suspend fun clear(): Boolean = true.also { stored.value = emptyList() }
}

internal class FakeHumanityProofRepository(initial: HumanityStatus? = HumanityStatus(verified = false)) : HumanityProofRepository {
    val current = MutableStateFlow(initial)
    var checkCalls = 0
    var nextOutcome: HumanityOutcome = HumanityOutcome.NOT_FOUND
    var checkGate: CompletableDeferred<Unit>? = null
    var checkedAtSeconds: Long = 1_700_000_000

    override val status: Flow<HumanityStatus> = current.filterNotNull().map { it }

    override suspend fun getStatus(): HumanityStatus = current.value ?: HumanityStatus(verified = false)

    override suspend fun checkForZaps(): HumanityCheckResult {
        checkCalls++
        checkGate?.await()
        val found = nextOutcome == HumanityOutcome.FOUND
        val old = getStatus()
        current.value = if (found) {
            HumanityStatus(verified = true, firstZapAt = 1_746_230_400, receiptEventId = "r", lastCheckedAt = checkedAtSeconds)
        } else {
            old.copy(lastCheckedAt = checkedAtSeconds)
        }
        return HumanityCheckResult(found, nextOutcome)
    }

    override suspend fun reverifyIfNeeded(): Boolean = false
    override suspend fun reset() = Unit
    override suspend fun getProofForPublishing(): JsonObject? = null
    override suspend fun checkForZapsByPubkey(pubkeyHex: String): Boolean = false
    override suspend fun verifyReceiptExists(receiptEventId: String): Boolean = false
    override suspend fun restoreFromBackup(backupProof: JsonObject?, additive: Boolean): Boolean = false
    override suspend fun clearForIdentitySwitch(): Boolean = true
}
